package com.bontecou.syncmd.services.git.lfs

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.*
import com.google.gson.JsonObject
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class LfsAccess(val endpoint: URI, val headers: Map<String, String>, val expires: Instant? = null, val credentialRevision: String? = null) {
    fun checkFresh() { check(expires == null || expires.isAfter(Instant.now().plusSeconds(5))) { "Git LFS authorization expired. Retry to request fresh access." } }
    override fun toString() = "Git LFS access (credentials redacted)"
}
internal object LfsAuthorization {
    fun resolve(repository: Repository, remoteUrl: String, credentials: Credentials, operation: SshLfsOperation, remoteName: String = "origin"): LfsAccess {
        val remote = GitRemote.parse(remoteUrl)
        val config = repository.config
        val configured = config.getString("remote", remoteName, if (operation == SshLfsOperation.UPLOAD) "lfspushurl" else "lfsurl")
            ?: config.getString("lfs", null, if (operation == SshLfsOperation.UPLOAD) "pushurl" else "url")
            ?: config.getString("remote", remoteName, "lfsurl") ?: config.getString("lfs", null, "url")
            ?: readLfsConfig(repository)
        if (configured != null) {
            val target = LfsUrls.endpoint(configured)
            // .lfsconfig is repository content. A changed host/path must not receive Git credentials.
            val standard = remote.isHttpTransport && target == guessed(remote.url)
            val headers = if (standard) basic(credentials) else LfsEndpointTrust.authorize(repository.workTree.canonicalPath, remote.url, target.toASCIIString(), operation)
            val revision = if (standard) null else java.security.MessageDigest.getInstance("SHA-256").run {
                headers.toSortedMap().forEach { (name, value) ->
                    for (part in listOf(name, value)) { val bytes = part.toByteArray(Charsets.UTF_8); update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); update(bytes) }
                }
                digest().joinToString("") { "%02x".format(it) }
            }
            return LfsAccess(target, headers, credentialRevision = revision)
        }
        if (remote.isSsh) {
            val key = credentials as? Credentials.Ssh ?: error("Git LFS SSH authentication requires a private key")
            return ssh(repository.workTree.canonicalPath, SshRemote.parse(remote.url), key, operation)
        }
        require(remote.isHttpTransport) { "Configure and review an HTTP(S) Git LFS endpoint for this Git repository" }
        return LfsAccess(guessed(remote.url), basic(credentials))
    }
    private fun readLfsConfig(repository: Repository): String? {
        val file = java.io.File(repository.workTree.canonicalFile, ".lfsconfig")
        if (!file.exists()) return null
        require(!java.nio.file.Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() <= 65536) { "Unsafe or oversized Git LFS endpoint configuration" }
        val parsed = org.eclipse.jgit.lib.Config().apply { fromText(file.readText()) }
        return parsed.getString("lfs", null, "url")
    }
    private fun guessed(remote: String): URI {
        val endpoint = HttpRemote.parse(remote)
        val withSuffix = endpoint.url.let { if (it.endsWith(".git")) it else "$it.git" }
        return LfsUrls.endpoint("$withSuffix/info/lfs")
    }
    private fun basic(credentials: Credentials): Map<String, String> {
        val pair = when (credentials) {
            is Credentials.Basic -> credentials.username to credentials.password
            is Credentials.Pat -> "x-access-token" to credentials.token
            Credentials.None -> return emptyMap()
            is Credentials.Ssh -> error("Do not send an SSH key to an HTTPS LFS endpoint")
        }
        require(':' !in pair.first && pair.first.none { it.isISOControl() } && pair.second.none { it == '\r' || it == '\n' }) { "Invalid Git LFS username or token" }
        return mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("${pair.first}:${pair.second}".toByteArray(Charsets.UTF_8)))
    }
    private fun ssh(repositoryPath: String, endpoint: SshRemote, key: Credentials.Ssh, operation: SshLfsOperation): LfsAccess {
        val factory = RepositorySshSessionFactory(endpoint, key, SshTrust.verifier(), operation)
        val session = factory.getSession(URIish(endpoint.url), null, FS.DETECTED, 30000)
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "GitSync LFS SSH response").apply { isDaemon = true } }
        var process: Process? = null
        try {
            process = session.exec(scopedSshLfsCommand(endpoint, operation), 30)
            process.outputStream.close()
            val running = process
            val errors = executor.submit<Boolean> { try { LfsJson.bounded(running.errorStream, 65536); true } catch (_: Exception) { running.destroy(); false } }
            val output = LfsJson.bounded(process.inputStream, 65536)
            check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0 && errors.get(5, TimeUnit.SECONDS)) { "Git LFS SSH authorization failed. Check server support, key access and endpoint configuration." }
            return actionAccess(LfsJson.parse(output)) { url ->
                LfsEndpointTrust.authorizeSshHttp(repositoryPath, endpoint.url, url.toASCIIString(), operation)
            }
        } finally { process?.destroy(); session.disconnect(); executor.shutdownNow() }
    }
    internal fun actionAccess(json: JsonObject, reviewHttp: ((URI) -> Unit)? = null): LfsAccess {
        val url = LfsUrls.endpoint(LfsJson.text(json.get("href") ?: error("Git LFS authentication returned no endpoint")))
        val headers = json.get("header")?.let { value ->
            require(value.isJsonObject)
            LfsUrls.headers(value.asJsonObject.entrySet().associate { (key, value) -> key to LfsJson.text(value) })
        } ?: emptyMap()
        val expires = expiry(json)
        return LfsAccess(url, headers, expires).also {
            it.checkFresh()
            if (url.scheme.equals("http", true)) {
                requireNotNull(reviewHttp) { "HTTP SSH LFS authorization requires endpoint review" }(url)
                // Reviewing an endpoint can outlive the server's short-lived authorization.
                it.checkFresh()
            }
        }
    }
    internal fun expiry(json: JsonObject): Instant? = when {
        json.has("expires_in") -> {
            val seconds = LfsJson.integer(json.get("expires_in"))
            require(seconds in -2147483647L..2147483647L) { "Invalid Git LFS authorization expiry" }
            Instant.now().plusSeconds(seconds)
        }
        json.has("expires_at") -> runCatching { Instant.parse(LfsJson.text(json.get("expires_at"))) }.getOrElse { throw IllegalArgumentException("Invalid Git LFS authorization expiry") }
        else -> null
    }
}

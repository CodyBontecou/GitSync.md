package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.HttpRemote
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.RepositoryUpdates
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.LockFile
import org.eclipse.jgit.lib.Config
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest

/** Review data contains cleaned addresses only, never usernames, passwords, or original URLs. */
data class RemoteCredentialMigrationEntry(val remote: String, val setting: String, val url: String)
data class RemoteCredentialMigrationPlan(val path: String, val revision: String, val metadataIdentity: String?,
    val entries: List<RemoteCredentialMigrationEntry>, val problems: List<String>, val primaryUrl: String?) {
    val canMigrate: Boolean get() = entries.isNotEmpty() && problems.isEmpty()
}
class EmbeddedRemoteCredential(val url: String, private val username: String, private val secret: String) {
    fun credentials(): Credentials.Basic = Credentials.Basic(username, secret)
    override fun toString(): String = "EmbeddedRemoteCredential(url=$url, credentials=[redacted])"
}

/** Moves repository-local HTTPS URL credentials through an encrypted host transaction.
 * Every URL list/order and separate fetch/push endpoint is retained. Unsupported/ambiguous
 * credentials, includes, inherited remote values and conflicting bindings require review.
 */
class RemoteCredentialMigration {
    private data class Scan(val config: Config, val entries: List<RemoteCredentialMigrationEntry>,
        val bindings: List<EmbeddedRemoteCredential>, val urls: List<String>, val problems: List<String>, val primary: String?)

    suspend fun inspect(path: String): RemoteCredentialMigrationPlan = RepositoryOperationCoordinator.withRepository(path) {
        val root = RepositoryDiscovery().inspect(path).path
        Git.open(File(root)).use { git ->
            val file = configFile(git)
            val bytes = readConfig(file)
            val scan = scan(bytes, git.repository.config)
            RemoteCredentialMigrationPlan(root, digest(bytes), GitTrackingRemoval.metadataIdentity(root), scan.entries, scan.problems, scan.primary)
        }
    }

    /** [secure] must persist/verify encrypted bindings first, publish once, and roll back only
     * if publication fails. It receives secrets in memory; no plaintext backup is created.
     */
    suspend fun migrate(plan: RemoteCredentialMigrationPlan,
        secure: (bindings: List<EmbeddedRemoteCredential>, configuredUrls: List<String>, publish: () -> Unit) -> Unit,
    ) = RepositoryOperationCoordinator.withRepository(plan.path) {
        check(plan.canMigrate) { "Review unsupported remote credentials before migrating" }
        RepositoryDiscovery().inspect(plan.path)
        check(plan.metadataIdentity == GitTrackingRemoval.metadataIdentity(plan.path)) { "Repository metadata changed. Reload the migration." }
        Git.open(File(plan.path)).use { git ->
            val file = configFile(git)
            val lock = LockFile(file)
            check(lock.lock()) { "Another Git operation is updating this connection. Retry when it finishes." }
            var published = false
            try {
                val bytes = readConfig(file)
                check(digest(bytes) == plan.revision) { "Repository configuration changed. Reload the migration." }
                val scan = scan(bytes, git.repository.config)
                check(scan.problems.isEmpty() && scan.entries == plan.entries && scan.primary == plan.primaryUrl) { "Remote addresses changed. Reload the migration." }
                check(scan.bindings.isNotEmpty()) { "No supported URL credentials remain to migrate" }
                val context = currentCoroutineContext()
                context.ensureActive()
                lock.setFSync(true)
                lock.write(scan.config.toText().toByteArray(Charsets.UTF_8))
                secure(scan.bindings, scan.urls) {
                    check(!published) { "Migration already published" }
                    context.ensureActive()
                    check(plan.metadataIdentity == GitTrackingRemoval.metadataIdentity(plan.path) &&
                        !Files.isSymbolicLink(file.toPath()) && digest(readConfig(file)) == plan.revision) {
                        "Repository configuration changed while saving. Reload the migration."
                    }
                    check(lock.commit()) { "Could not save the cleaned remote addresses" }
                    published = true
                }
                check(published) { "Secure credentials were not published" }
            } finally {
                lock.unlock()
                if (published) RepositoryUpdates.changed(plan.path)
            }
        }
    }

    private fun configFile(git: Git): File = File(git.repository.directory, "config").also {
        check(!Files.isSymbolicLink(it.toPath())) { "Repository config must not be a symlink" }
    }
    private fun readConfig(file: File): ByteArray {
        check(file.length() in 1..8L * 1024 * 1024) { "Repository configuration needs manual review" }
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 8 * 1024 * 1024) { "Repository configuration needs manual review" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    private fun scan(bytes: ByteArray, effective: Config): Scan {
        val config = try { Config().apply { fromText(Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()) } }
        catch (_: Exception) { error("Repository configuration could not be read safely") }
        val entries = mutableListOf<RemoteCredentialMigrationEntry>()
        val bindings = linkedMapOf<String, EmbeddedRemoteCredential>()
        val urls = mutableListOf<String>(); val problems = mutableListOf<String>()
        if (config.getStringList("include", null, "path").isNotEmpty() || config.getSubsections("includeIf").isNotEmpty())
            problems += "Included Git configuration needs separate review before migration."
        val remotes = (config.getSubsections("remote") + effective.getSubsections("remote")).sorted()
        for (remote in remotes) for (setting in listOf("url", "pushurl")) {
            val original = config.getStringList("remote", remote, setting).toList()
            if (original != effective.getStringList("remote", remote, setting).toList())
                problems += "Inherited remote addresses need separate review before migration."
            val cleaned = original.map { raw ->
                var result = raw
                try {
                    val uri = if (listOf("https:", "http:", "ssh:").any { raw.startsWith(it, true) }) URI(raw) else null
                    if (uri == null) {
                        val other = org.eclipse.jgit.transport.URIish(raw)
                        require(other.pass == null && other.user?.contains(':') != true && (other.scheme == null || other.user == null))
                    } else if ((uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && uri.rawUserInfo != null) {
                        require(uri.rawQuery == null && uri.rawFragment == null && raw.length <= 16384)
                        val split = uri.rawUserInfo.indexOf(':')
                        require(split > 0 && split < uri.rawUserInfo.lastIndex)
                        val username = decode(uri.rawUserInfo.substring(0, split))
                        val secret = decode(uri.rawUserInfo.substring(split + 1))
                        require(username.isNotBlank() && ':' !in username && username.none(Char::isISOControl) && secret.isNotEmpty() && secret.none { it == '\r' || it == '\n' || it == '\u0000' })
                        val authority = uri.rawAuthority.substringAfterLast('@')
                        val endpoint = HttpRemote.parse("${uri.scheme.lowercase()}://$authority${uri.rawPath}").url
                        val binding = EmbeddedRemoteCredential(endpoint, username, secret)
                        val previous = bindings[endpoint]
                        require(previous == null || previous.credentials() == binding.credentials())
                        bindings[endpoint] = binding
                        result = endpoint
                        entries += RemoteCredentialMigrationEntry(remote, setting, endpoint)
                    } else if ((uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                        (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) ||
                        uri.scheme.equals("ssh", true) && uri.rawUserInfo?.contains(':') == true) {
                        problems += "Some URL credentials require a complete HTTP/HTTPS connection or an SSH key. Review them in the connection editor."
                    }
                } catch (_: Exception) {
                    // Parser exceptions can contain the original secret URL: never propagate them.
                    problems += "Some remote URL credentials are incomplete, ambiguous or unsupported. Review them separately."
                }
                urls += result
                result
            }
            if (cleaned != original) config.setStringList("remote", remote, setting, cleaned)
        }
        return Scan(config, entries, bindings.values.toList(), urls.distinct(), problems.distinct(),
            config.getStringList("remote", "origin", "url").firstOrNull()?.let { value ->
                runCatching { com.bontecou.syncmd.services.git.GitRemote.parse(value).url }.getOrNull()
                    ?: RepositoryDiscovery.redactRemoteUrl(value)
            })
    }
    private fun decode(raw: String): String {
        val bytes = java.io.ByteArrayOutputStream(); var offset = 0
        while (offset < raw.length) {
            if (raw[offset] == '%') {
                require(offset + 2 < raw.length)
                bytes.write(raw.substring(offset + 1, offset + 3).toInt(16)); offset += 3
            } else {
                val end = raw.indexOf('%', offset).takeIf { it >= 0 } ?: raw.length
                bytes.write(raw.substring(offset, end).toByteArray(Charsets.UTF_8)); offset = end
            }
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

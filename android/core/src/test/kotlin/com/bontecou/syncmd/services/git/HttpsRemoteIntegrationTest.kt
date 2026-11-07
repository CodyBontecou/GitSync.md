package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.repositories.SafeCloneService
import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*

/** Real smart HTTPS Git traffic, isolated loopback server and test-only certificate trust. */
class HttpsRemoteIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `HTTPS clone fetch pull and push authenticate while redirects and untrusted TLS fail closed`() = runBlocking { exercise(true) }
    @Test fun `HTTP clone fetch pull push rebase and credential refresh stay inside the selected repository`() = runBlocking { exercise(false) }
    private suspend fun exercise(secure: Boolean) {
        val root = temporary.newFolder("https-fixture")
        val cert = File(root, "fixture.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
            "-keystore", cert.path, "-storepass", "fixture-password", "-keypass", "fixture-password", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "1")
            .redirectErrorStream(true).start()
        process.inputStream.readBytes()
        check(process.waitFor() == 0) { "Could not create the loopback TLS fixture" }
        val keys = KeyStore.getInstance("PKCS12").apply { cert.inputStream().use { load(it, "fixture-password".toCharArray()) } }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fixture-password".toCharArray()) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val serverContext = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, trust.trustManagers, null) }
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        val bare = File(root, "fixture.git")
        Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().use { git ->
            git.repository.config.apply { setBoolean("http", null, "receivepack", true); save() }
        }
        val source = File(root, "writer")
        Git.init().setDirectory(source).setInitialBranch("main").call().use { git ->
            File(source, "notes.md").writeText("Main content\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Initial").setAuthor("Fixture", "fixture@example.invalid").call()
            git.checkout().setCreateBranch(true).setName("feature/notes").call()
            File(source, "branch.md").writeText("Feature content\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Feature").setAuthor("Fixture", "fixture@example.invalid").call()
            git.push().setRemote(bare.toURI().toString()).setPushAll().call()
        }
        val server = if (secure) HttpsServer.create(InetSocketAddress("localhost", 0), 0) else HttpServer.create(InetSocketAddress("localhost", 0), 0)
        if (server is HttpsServer) server.httpsConfigurator = HttpsConfigurator(serverContext)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val authenticated = AtomicInteger()
        val escaped = AtomicInteger()
        val redirectedWithAuth = AtomicInteger()
        val anonymousAuthorization = AtomicInteger()
        val expected = "Basic " + Base64.getEncoder().encodeToString("fixture-user:fixture-password".toByteArray())
        server.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                val auth = exchange.requestHeaders.getFirst("Authorization")
                if (path.startsWith("/escape.git/")) {
                    escaped.incrementAndGet(); exchange.sendResponseHeaders(500, -1)
                } else if (!path.startsWith("/public.git/") && auth != expected) {
                    exchange.responseHeaders.set("WWW-Authenticate", "Basic realm=fixture")
                    exchange.sendResponseHeaders(401, -1)
                } else if (path.startsWith("/redirect.git/")) {
                    authenticated.incrementAndGet()
                    redirectedWithAuth.incrementAndGet()
                    exchange.responseHeaders.set("Location", "/escape.git/info/refs?service=git-upload-pack")
                    exchange.sendResponseHeaders(302, -1)
                } else {
                    if (path.startsWith("/public.git/")) { if (auth != null) anonymousAuthorization.incrementAndGet() }
                    else authenticated.incrementAndGet()
                    val body = if (exchange.requestHeaders.getFirst("Content-Encoding") == "gzip")
                        java.util.zip.GZIPInputStream(exchange.requestBody).readBytes() else exchange.requestBody.readBytes()
                    val backend = ProcessBuilder("git", "http-backend").redirectError(File(root, "backend.log"))
                    backend.environment().apply {
                        put("GIT_PROJECT_ROOT", root.path); put("GIT_HTTP_EXPORT_ALL", "1")
                        put("PATH_INFO", path.replaceFirst("/public.git/", "/fixture.git/"))
                        put("REQUEST_METHOD", exchange.requestMethod)
                        put("QUERY_STRING", exchange.requestURI.rawQuery.orEmpty())
                        put("CONTENT_TYPE", exchange.requestHeaders.getFirst("Content-Type").orEmpty())
                        put("CONTENT_LENGTH", body.size.toString()); put("REMOTE_USER", "fixture-user")
                    }
                    val git = backend.start()
                    git.outputStream.use { it.write(body) }
                    val response = git.inputStream.readBytes()
                    check(git.waitFor() == 0) { "Fixture backend failed: ${File(root, "backend.log").readText()}" }
                    val marker = "\r\n\r\n".toByteArray()
                    val split = (0..response.size - marker.size).first { index -> marker.indices.all { response[index + it] == marker[it] } }
                    val headers = String(response, 0, split, Charsets.ISO_8859_1).split("\r\n")
                    var status = 200
                    headers.forEach { line ->
                        val key = line.substringBefore(':'); val value = line.substringAfter(':').trim()
                        if (key.equals("Status", true)) status = value.substringBefore(' ').toInt()
                        else exchange.responseHeaders.add(key, value)
                    }
                    val payload = response.copyOfRange(split + marker.size, response.size)
                    exchange.sendResponseHeaders(status, payload.size.toLong())
                    exchange.responseBody.write(payload)
                }
            } catch (e: Exception) { System.err.println("HTTPS fixture: ${e.message}"); runCatching { exchange.sendResponseHeaders(500, -1) }
            } finally { exchange.close() }
        }
        server.start()
        val originalTrust = HttpsURLConnection.getDefaultSSLSocketFactory()
        try {
            val url = "${if (secure) "https" else "http"}://localhost:${server.address.port}/fixture.git"
            val auth = Credentials.Basic("fixture-user", "fixture-password")
            val service = SafeCloneService(LocalGitRepository())
            if (secure) {
                val untrusted = File(root, "untrusted")
                assertThat(service.clone(url, untrusted.path, auth, "feature/notes").isFailure).isTrue()
                assertThat(untrusted.exists()).isFalse()
                assertThat(authenticated.get()).isEqualTo(0)
                HttpsURLConnection.setDefaultSSLSocketFactory(clientContext.socketFactory)
            }
            val target = File(root, "cloned")
            service.clone(url, target.path, auth, "feature/notes").getOrThrow()
            assertThat(File(target, "branch.md").readText()).isEqualTo("Feature content\n")
            File(target, "notes.md").writeText("HTTPS push content\n")
            val diff = LocalDiffRepository()
            diff.stageAll(target.path).getOrThrow()
            diff.commit(target.path, "HTTPS commit", "Fixture", "fixture@example.invalid").getOrThrow()
            val remote = LocalRemoteRepository(credentialResolver = { _, selected -> assertThat(selected).isEqualTo(url); auth })
            remote.push(target.path, "feature/notes", "origin", "feature/notes").getOrThrow()
            val pushed = Git.open(target).use { it.repository.resolve("HEAD") }
            assertThat(Git.open(bare).use { it.repository.resolve("refs/heads/feature/notes") }).isEqualTo(pushed)
            Git.open(source).use { git ->
                git.fetch().setRemote(bare.toURI().toString()).setRefSpecs(org.eclipse.jgit.transport.RefSpec("+refs/heads/*:refs/remotes/fixture/*")).call()
                git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(pushed.name).call()
                File(source, "upstream.md").writeText("Pulled over HTTPS\n")
                git.add().addFilepattern(".").call()
                git.commit().setMessage("Upstream").setAuthor("Fixture", "fixture@example.invalid").call()
                git.push().setRemote(bare.toURI().toString()).setPushAll().call()
            }
            remote.fetch(target.path, "origin").getOrThrow()
            LocalPullRepository(credentialResolver = { _, _ -> auth }).executePull(target.path).getOrThrow()
            assertThat(File(target, "upstream.md").readText()).isEqualTo("Pulled over HTTPS\n")
            // Refresh an expired credential through the connection edit without cloning again.
            var savedCredentials = Credentials.Basic("fixture-user", "expired-fixture-password")
            val refreshingRemote = LocalRemoteRepository(credentialResolver = { _, _ -> savedCredentials })
            assertThat(refreshingRemote.fetch(target.path, "origin").isFailure).isTrue()
            val headBeforeRefresh = Git.open(target).use { it.repository.resolve("HEAD") }
            val editor = com.bontecou.syncmd.services.repositories.RepositoryConnection()
            editor.save(editor.inspect(target.path), url) { commit -> savedCredentials = auth; commit() }
            refreshingRemote.fetch(target.path, "origin").getOrThrow()
            assertThat(Git.open(target).use { it.repository.resolve("HEAD") }).isEqualTo(headBeforeRefresh)
            // Rebase uses the same scoped HTTPS credentials, fetches the actual configured branch,
            // and leaves the server untouched after replaying a divergent local commit.
            File(target, "rebase-local.md").writeText("Local HTTPS replay\n")
            diff.stageAll(target.path).getOrThrow(); diff.commit(target.path, "Local HTTPS rebase", "Fixture", "fixture@example.invalid").getOrThrow()
            Git.open(source).use { git ->
                File(source, "rebase-remote.md").writeText("Remote HTTPS replay\n")
                git.add().addFilepattern(".").call(); git.commit().setMessage("Remote HTTPS rebase").setAuthor("Fixture", "fixture@example.invalid").call()
                git.push().setRemote(bare.toURI().toString()).setPushAll().call()
            }
            val rebase = RepositoryRebase(credentials = { _, selected -> assertThat(selected).isEqualTo(url); auth }, author = { "HTTPS Rebase Author" to "rebase@example.invalid" })
            val reviewed = rebase.plan(target.path)
            assertThat(reviewed.ahead).isEqualTo(1); assertThat(reviewed.behind).isEqualTo(1)
            val serverHead = Git.open(bare).use { it.repository.resolve("refs/heads/feature/notes") }
            assertThat(rebase.begin(reviewed).session).isNull()
            assertThat(File(target, "rebase-local.md").readText()).isEqualTo("Local HTTPS replay\n")
            assertThat(File(target, "rebase-remote.md").readText()).isEqualTo("Remote HTTPS replay\n")
            assertThat(Git.open(bare).use { it.repository.resolve("refs/heads/feature/notes") }).isEqualTo(serverHead)
            val publicUrl = "${if (secure) "https" else "http"}://localhost:${server.address.port}/public.git"
            service.clone(publicUrl, File(root, "public-clone").path, Credentials.None, "feature/notes").getOrThrow()
            assertThat(anonymousAuthorization.get()).isEqualTo(0)
            val redirect = "${if (secure) "https" else "http"}://localhost:${server.address.port}/redirect.git"
            val redirected = File(root, "redirected")
            assertThat(service.clone(redirect, redirected.path, auth).isFailure).isTrue()
            assertThat(redirected.exists()).isFalse()
            assertThat(escaped.get()).isEqualTo(0)
            assertThat(redirectedWithAuth.get()).isGreaterThan(0)
            assertThat(authenticated.get()).isGreaterThan(0)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(originalTrust)
            server.stop(0); executor.shutdownNow()
        }
    }
}

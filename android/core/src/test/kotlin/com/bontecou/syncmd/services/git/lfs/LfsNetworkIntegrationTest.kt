package com.bontecou.syncmd.services.git.lfs

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.repositories.SafeCloneService
import com.google.common.truth.Truth.assertThat
import com.google.gson.*
import com.sun.net.httpserver.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.Executors
import javax.net.ssl.*

/** Real Git smart HTTPS and LFS batch/storage endpoints, with trust limited to a generated fixture certificate. */
class LfsNetworkIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: com.sun.net.httpserver.HttpServer
    private val executor = Executors.newCachedThreadPool()
    private lateinit var root: File
    private lateinit var bare: File
    private lateinit var writer: File
    private lateinit var url: String
    private val credentials = Credentials.Basic("fixture", "fixture-token")
    private val expectedAuth = "Basic " + Base64.getEncoder().encodeToString("fixture:fixture-token".toByteArray())
    private val storage = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private var originalTrust: SSLSocketFactory? = null
    private var corrupt = false
    private var redirect = false
    private var escapedRequests = 0
    private var failVerify = false
    private var beforeDownload: (() -> Unit)? = null
    private var malformedBatch: String? = null
    private var uploads = 0
    private var verifications = 0
    private var escapedCredentials = 0
    private var lockStatus = 200
    private var lockReply: ((String, JsonObject?) -> JsonObject)? = null
    private val lockRequests = mutableListOf<Pair<String, JsonObject?>>()
    private var beforeLockReply: (() -> Unit)? = null
    private var customLockAuth: String? = null
    private var denyGitFetch = false
    private val initialBytes = ByteArray(8192) { (it % 251).toByte() }
    private fun pointer(bytes: ByteArray): LfsPointer = LfsPointer(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size.toLong())
    @Before fun setup() {
        root = temporary.newFolder()
        val cert = File(root, "fixture.p12")
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/keytool").path, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
            "-keystore", cert.path, "-storepass", "fixture-password", "-keypass", "fixture-password", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "1").redirectErrorStream(true).start()
        process.inputStream.readBytes(); check(process.waitFor() == 0)
        val keys = KeyStore.getInstance("PKCS12").apply { cert.inputStream().use { load(it, "fixture-password".toCharArray()) } }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fixture-password".toCharArray()) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val serverTls = SSLContext.getInstance("TLS").apply { init(km.keyManagers, tm.trustManagers, null) }
        val clientTls = SSLContext.getInstance("TLS").apply { init(null, tm.trustManagers, null) }
        server = HttpsServer.create(InetSocketAddress("localhost", 0), 0)
        (server as HttpsServer).httpsConfigurator = HttpsConfigurator(serverTls); server.executor = executor
        bare = File(root, "repo.git")
        Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().use { it.repository.config.apply { setBoolean("http", null, "receivepack", true); save() } }
        writer = File(root, "writer")
        Git.init().setDirectory(writer).setInitialBranch("main").call().use { git ->
            File(writer, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
            File(writer, "asset.bin").writeBytes(pointer(initialBytes).bytes())
            git.add().addFilepattern(".").call(); git.commit().setAuthor("Fixture", "fixture@example.invalid").setMessage("LFS source").call()
            git.push().setRemote(bare.toURI().toString()).setPushAll().call()
        }
        storage[pointer(initialBytes).oid] = initialBytes
        server.createContext("/") { exchange ->
            try { serve(exchange) }
            catch (_: Exception) { runCatching { exchange.sendResponseHeaders(500, -1) } }
            finally { exchange.close() }
        }
        server.start(); url = "https://localhost:${server.address.port}/repo.git"
        originalTrust = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.socketFactory)
    }
    @After fun cleanup() {
        originalTrust?.let { HttpsURLConnection.setDefaultSSLSocketFactory(it) }
        if (::server.isInitialized) server.stop(0)
        executor.shutdownNow()
    }
    private fun respond(exchange: HttpExchange, code: Int, bytes: ByteArray) {
        exchange.sendResponseHeaders(code, bytes.size.toLong()); exchange.responseBody.write(bytes)
    }
    private fun serve(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val auth = exchange.requestHeaders.getFirst("Authorization")
        if (denyGitFetch && (exchange.requestURI.rawQuery.orEmpty().contains("service=git-upload-pack") || path.endsWith("/git-upload-pack"))) {
            exchange.sendResponseHeaders(403, -1); return
        }
        if (path == "/escaped") { escapedRequests++; exchange.sendResponseHeaders(500, -1); return }
        if (path.startsWith("/storage/") || path.startsWith("/verify/")) {
            if (auth == expectedAuth) escapedCredentials++
            check(auth == "Bearer fixture-action")
            val oid = path.substringAfterLast('/')
            when {
                path.startsWith("/verify/") -> { verifications++; respond(exchange, if (failVerify) 500 else 200, byteArrayOf()) }
                exchange.requestMethod == "PUT" -> { storage[oid] = exchange.requestBody.readBytes(); uploads++; respond(exchange, 200, byteArrayOf()) }
                else -> {
                    if (redirect) { exchange.responseHeaders.set("Location", "${java.net.URI(url).scheme}://localhost:${server.address.port}/escaped"); exchange.sendResponseHeaders(302, -1); return }
                    beforeDownload?.invoke(); respond(exchange, 200, if (corrupt) byteArrayOf(1, 2, 3) else storage.getValue(oid)) }
            }
        } else if (auth != expectedAuth && !(path.startsWith("/custom-lfs/locks") && auth == customLockAuth)) {
            exchange.responseHeaders.set("WWW-Authenticate", "Basic realm=fixture"); exchange.sendResponseHeaders(401, -1)
        } else if (path.startsWith("/repo.git/info/lfs/locks") || path.startsWith("/custom-lfs/locks")) {
            val request = if (exchange.requestMethod == "POST") LfsJson.parse(exchange.requestBody.readBytes()) else null
            lockRequests += exchange.requestURI.toASCIIString() to request
            beforeLockReply?.invoke()
            if (lockStatus == 302) exchange.responseHeaders.set("Location", "${java.net.URI(url).scheme}://localhost:${server.address.port}/escaped")
            respond(exchange, lockStatus, (lockReply?.invoke(path, request) ?: lockPage()).toString().toByteArray())
        } else if (path.endsWith("/objects/batch")) {
            malformedBatch?.let { respond(exchange, 200, it.toByteArray()); return }
            val request = LfsJson.parse(exchange.requestBody.readBytes())
            val operation = request.get("operation").asString
            val objects = request.getAsJsonArray("objects").map { raw ->
                val requested = raw.asJsonObject
                val oid = requested.get("oid").asString
                JsonObject().apply {
                    addProperty("oid", oid); add("size", requested.get("size"))
                    if (operation == "download" || !storage.containsKey(oid)) {
                        val actions = JsonObject()
                        fun action(route: String) = JsonObject().apply {
                            addProperty("href", "${java.net.URI(url).scheme}://localhost:${server.address.port}/$route/$oid?fixtureSigned=1")
                            add("header", JsonObject().apply { addProperty("Authorization", "Bearer fixture-action") })
                        }
                        actions.add(operation, action("storage"))
                        if (operation == "upload") actions.add("verify", action("verify"))
                        add("actions", actions)
                    }
                }
            }
            respond(exchange, 200, JsonObject().apply { addProperty("transfer", "basic"); add("objects", JsonArray().apply { objects.forEach { add(it) } }) }.toString().toByteArray())
        } else {
            val body = if (exchange.requestHeaders.getFirst("Content-Encoding") == "gzip") java.util.zip.GZIPInputStream(exchange.requestBody).readBytes() else exchange.requestBody.readBytes()
            val backend = ProcessBuilder("git", "http-backend").redirectError(File(root, "backend.log"))
            backend.environment().apply {
                put("GIT_PROJECT_ROOT", root.path); put("GIT_HTTP_EXPORT_ALL", "1"); put("PATH_INFO", path)
                put("REQUEST_METHOD", exchange.requestMethod); put("QUERY_STRING", exchange.requestURI.rawQuery.orEmpty())
                put("CONTENT_TYPE", exchange.requestHeaders.getFirst("Content-Type").orEmpty()); put("CONTENT_LENGTH", body.size.toString()); put("REMOTE_USER", "fixture")
            }
            val process = backend.start(); process.outputStream.use { it.write(body) }
            val response = process.inputStream.readBytes(); check(process.waitFor() == 0)
            val delimiter = "\r\n\r\n".toByteArray()
            val split = (0..response.size - delimiter.size).first { i -> delimiter.indices.all { response[i + it] == delimiter[it] } }
            var code = 200
            String(response, 0, split, Charsets.ISO_8859_1).split("\r\n").forEach { line ->
                val name = line.substringBefore(':'); val value = line.substringAfter(':').trim()
                if (name == "Status") code = value.substringBefore(' ').toInt() else exchange.responseHeaders.add(name, value)
            }
            respond(exchange, code, response.copyOfRange(split + delimiter.size, response.size))
        }
    }
    private fun plainHttp() {
        server.stop(0)
        server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try { serve(exchange) } catch (_: Exception) { runCatching { exchange.sendResponseHeaders(500, -1) } } finally { exchange.close() }
        }
        url="http://localhost:${server.address.port}/repo.git";server.start()
    }
    @Test fun `real HTTP LFS clone upload verify and pull preserve pointers and bytes`() = runBlocking {
        plainHttp(); roundTrip()
    }
    @Test fun `HTTP corruption cannot replace pointer or cache`() = runBlocking {
        plainHttp();val target=clone();val expected=pointer(initialBytes)
        GitLfs.open(target).use { LfsObjectStore(it.repository).verified(expected)!!.delete() }
        File(target,"asset.bin").writeBytes(expected.bytes());corrupt=true
        assertThat(runCatching { LfsRepository(credentialResolver={_,_->credentials}).hydrate(target.path) }.isFailure).isTrue()
        assertThat(File(target,"asset.bin").readBytes()).isEqualTo(expected.bytes())
        GitLfs.open(target).use { assertThat(LfsObjectStore(it.repository).verified(expected)).isNull() }
    }
    @Test fun `HTTP redirect does not receive object credentials and leaves pointer intact`() = runBlocking {
        plainHttp();val target=clone();val expected=pointer(initialBytes)
        GitLfs.open(target).use { LfsObjectStore(it.repository).verified(expected)!!.delete() }
        File(target,"asset.bin").writeBytes(expected.bytes());redirect=true
        assertThat(runCatching { LfsRepository(credentialResolver={_,_->credentials}).hydrate(target.path) }.isFailure).isTrue()
        assertThat(File(target,"asset.bin").readBytes()).isEqualTo(expected.bytes());assertThat(escapedRequests).isEqualTo(0)
    }
    private suspend fun clone(): File = File(root, "cloned").also { SafeCloneService(LocalGitRepository()).clone(url, it.path, credentials).getOrThrow() }
    @Test fun `clone retains completed Git history when LFS object verification fails and hydration can retry`() = runBlocking {
        corrupt = true
        val target = File(root,"clone-warning")
        val result = SafeCloneService(LocalGitRepository()).clone(url,target.path,credentials)
        assertThat(result.isSuccess).isTrue()
        assertThat(File(target,"asset.bin").readBytes()).isEqualTo(pointer(initialBytes).bytes())
        val head = Git.open(target).use { it.repository.resolve("HEAD") }
        assertThat(head).isEqualTo(Git.open(bare).use { it.repository.resolve("HEAD") })
        assertThat(LfsRepository().inspect(target.path).single().hydrated).isFalse()
        corrupt = false
        LfsRepository(credentialResolver = { _, _ -> credentials }).hydrate(target.path)
        assertThat(File(target,"asset.bin").readBytes()).isEqualTo(initialBytes)
        assertThat(Git.open(target).use { it.repository.resolve("HEAD") }).isEqualTo(head)
        assertThat(Git.open(target).use { it.status().call().isClean }).isTrue()
    }
    @Test fun `clone with a rejected LFS object redirect keeps the pointer without forwarding credentials`() = runBlocking {
        redirect = true
        val target = File(root,"clone-redirect")
        assertThat(SafeCloneService(LocalGitRepository()).clone(url,target.path,credentials).isSuccess).isTrue()
        assertThat(File(target,"asset.bin").readBytes()).isEqualTo(pointer(initialBytes).bytes())
        assertThat(escapedRequests).isEqualTo(0); assertThat(escapedCredentials).isEqualTo(0)
        redirect = false
        LfsRepository(credentialResolver = { _, _ -> credentials }).hydrate(target.path)
        assertThat(File(target,"asset.bin").readBytes()).isEqualTo(initialBytes)
    }
    @Test fun `clone hydration warning preserves an edit made during LFS download`() = runBlocking {
        val target = File(root,"clone-draft")
        val draft = "Concurrent clone draft\n".toByteArray()
        beforeDownload = {
            val stage = root.listFiles()!!.single { it.isDirectory && it.name.startsWith(".gitsync-clone-") }
            File(stage,"asset.bin").writeBytes(draft)
        }
        assertThat(SafeCloneService(LocalGitRepository()).clone(url,target.path,credentials).isSuccess).isTrue()
        assertThat(File(target,"asset.bin").readBytes()).isEqualTo(draft)
        assertThat(Git.open(target).use { it.repository.resolve("HEAD") }).isEqualTo(Git.open(bare).use { it.repository.resolve("HEAD") })
        assertThat(LfsRepository().inspect(target.path).single().modified).isTrue()
        beforeDownload = null
        LfsRepository(credentialResolver = { _, _ -> credentials }).hydrate(target.path)
        assertThat(File(target,"asset.bin").readBytes()).isEqualTo(draft)
    }
    private fun lock(path: String = "asset.bin", id: String = "fixture-lock") = JsonObject().apply {
        addProperty("id", id); addProperty("path", path); addProperty("locked_at", "2026-10-27T12:00:00Z")
        add("owner", JsonObject().apply { addProperty("name", "Fixture teammate") })
    }
    private fun lockPage(ours: List<JsonObject> = emptyList(), theirs: List<JsonObject> = emptyList(), cursor: String? = null) = JsonObject().apply {
        add("ours", JsonArray().apply { ours.forEach { add(it) } }); add("theirs", JsonArray().apply { theirs.forEach { add(it) } })
        cursor?.let { addProperty("next_cursor", it) }
    }
    private suspend fun commitLockable(target: File) {
        File(target, ".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text lockable\n")
        File(target, "asset.bin").writeBytes(initialBytes + byteArrayOf(67))
        LocalDiffRepository().apply { stageAll(target.path).getOrThrow(); commit(target.path, "Lockable change", "Fixture", "fixture@example.invalid").getOrThrow() }
    }
    private fun pusher() = LocalRemoteRepository(credentialResolver = { _, selected -> check(selected == url); credentials })

    @Test fun `reviewed lock creation and release preserve local content index and refs`() = runBlocking {
        val target = clone(); commitLockable(target)
        val index = File(target,".git/index").readBytes(); val data = File(target,"asset.bin").readBytes()
        val head = Git.open(target).use { it.repository.resolve("HEAD") }
        var active: JsonObject? = null; var writes = 0
        lockReply = { path, body -> when {
            path.endsWith("/verify") -> lockPage(ours = listOfNotNull(active))
            path.endsWith("/unlock") -> JsonObject().apply { writes++; add("lock", active); active = null }
            else -> JsonObject().apply { writes++; active = lock(body!!.get("path").asString); add("lock", active) }
        } }
        val service = LfsRepository(credentialResolver = { _, _ -> credentials })
        val review = service.reviewLocks(target.path)
        assertThat(review.candidates).contains("asset.bin")
        val created = service.createReviewedLock(review,"asset.bin")
        assertThat(created.review!!.ours.single().path).isEqualTo("asset.bin")
        val released = service.releaseReviewedLock(created.review!!, created.review!!.ours.single())
        assertThat(released.review!!.ours).isEmpty(); assertThat(writes).isEqualTo(2)
        assertThat(File(target,".git/index").readBytes()).isEqualTo(index)
        assertThat(File(target,"asset.bin").readBytes()).isEqualTo(data)
        assertThat(Git.open(target).use { it.repository.resolve("HEAD") }).isEqualTo(head)
    }
    @Test fun `read only listing never authorizes a mutation and shows the fetch URL`() = runBlocking {
        val target = clone(); commitLockable(target)
        Git.open(target).use { it.repository.config.apply { setString("remote","origin","pushurl","https://write.example.invalid/repo.git"); save() } }
        lockReply = { _, _ -> JsonObject().apply { add("locks",JsonArray().apply { add(lock()) }) } }
        val service = LfsRepository(credentialResolver = { _, _ -> credentials })
        val review = service.reviewLocks(target.path,false)
        assertThat(review.context.remote).isEqualTo(url); assertThat(review.listed).hasSize(1)
        assertThat(runCatching { service.createReviewedLock(review,"asset.bin") }.isFailure).isTrue()
        assertThat(runCatching { service.createReviewedLock(review.copy(verified=true),"asset.bin") }.isFailure).isTrue()
        assertThat(lockRequests).hasSize(1)
    }
    @Test fun `review rejects changed branch attributes and credentials before server writes`() = runBlocking {
        val target = clone(); commitLockable(target)
        var selected = credentials
        val service = LfsRepository(credentialResolver = { _, _ -> selected })
        val initial = service.reviewLocks(target.path)
        selected = Credentials.Basic("fixture", "replacement-not-sent")
        assertThat(runCatching { service.createReviewedLock(initial,"asset.bin") }.exceptionOrNull()).isInstanceOf(LfsLockReviewChanged::class.java)
        selected = credentials
        val next = service.reviewLocks(target.path)
        File(target,".gitattributes").appendText("\n# Changed rules\n")
        assertThat(runCatching { service.createReviewedLock(next,"asset.bin") }.exceptionOrNull()).isInstanceOf(LfsLockReviewChanged::class.java)
        val last = service.reviewLocks(target.path)
        Git.open(target).use { it.checkout().setCreateBranch(true).setName("other").call() }
        assertThat(runCatching { service.createReviewedLock(last,"asset.bin") }.exceptionOrNull()).isInstanceOf(LfsLockReviewChanged::class.java)
        assertThat(lockRequests.all { it.first.contains("/verify") }).isTrue()
        assertThat(initial.toString()).doesNotContain("fixture-token")
    }
    @Test fun `global attributes and credential record revision invalidate lock confirmation`() = runBlocking {
        val target = clone(); commitLockable(target)
        val attrs = File(root,"global-attributes").canonicalFile.apply { writeText("*.bin lockable\n") }
        Git.open(target).use { it.repository.config.apply { setString("core",null,"attributesFile",attrs.path); save() } }
        var revision = "one"
        val service = LfsRepository(credentialResolver = { _, _ -> credentials },credentialRevision = { revision })
        val review = service.reviewLocks(target.path)
        attrs.writeText("*.bin -lockable\n")
        assertThat(runCatching { service.createReviewedLock(review,"asset.bin") }.exceptionOrNull()).isInstanceOf(LfsLockReviewChanged::class.java)
        val next = service.reviewLocks(target.path); revision = "two"
        assertThat(runCatching { service.createReviewedLock(next,"asset.bin") }.exceptionOrNull()).isInstanceOf(LfsLockReviewChanged::class.java)
        assertThat(lockRequests.all { it.first.contains("/verify") }).isTrue()
    }
    @Test fun `cancelled lock review stops before requesting another page`() = runBlocking {
        val target = clone(); commitLockable(target)
        val service = LfsRepository(credentialResolver = { _, _ -> credentials })
        val task = async(Dispatchers.IO, start = CoroutineStart.LAZY) { service.reviewLocks(target.path) }
        beforeLockReply = { task.cancel() }
        lockReply = { _, request -> lockPage(cursor = if (request?.get("cursor") == null) "next-page" else null) }
        task.start()
        assertThat(runCatching { task.await() }.exceptionOrNull()).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        task.join()
        assertThat(lockRequests).hasSize(1)
    }
    @Test fun `changed ownership cannot silently turn ordinary release into a force release`() = runBlocking {
        val target = clone(); commitLockable(target)
        var ours = true
        lockReply = { _, _ -> if (ours) lockPage(ours=listOf(lock())) else lockPage(theirs=listOf(lock())) }
        val service = LfsRepository(credentialResolver = { _, _ -> credentials })
        val review = service.reviewLocks(target.path); ours = false
        assertThat(runCatching { service.releaseReviewedLock(review,review.ours.single()) }.exceptionOrNull()).isInstanceOf(LfsLockReviewChanged::class.java)
        assertThat(runCatching { service.releaseReviewedLock(review,review.ours.single(),true) }.exceptionOrNull()).isInstanceOf(LfsLockReviewChanged::class.java)
        assertThat(lockRequests.all { it.first.contains("/verify") }).isTrue()
    }
    @Test fun `confirmed write with failed refresh is distinct from an unconfirmed write reply`() = runBlocking {
        val target = clone(); commitLockable(target)
        var active: JsonObject? = null; var malformed = false; var writes = 0
        lockReply = { path, _ -> if (path.endsWith("/verify")) {
            if (malformed) JsonObject() else lockPage(ours=listOfNotNull(active))
        } else JsonObject().apply { writes++; active = lock(); add("lock",active); malformed=true } }
        val service = LfsRepository(credentialResolver = { _, _ -> credentials })
        val review = service.reviewLocks(target.path)
        val result = service.createReviewedLock(review,"asset.bin")
        assertThat(result.review).isNull(); assertThat(result.notice).contains("change was confirmed")
        malformed=false; val fresh=service.reviewLocks(target.path); assertThat(fresh.ours).hasSize(1)
        active=null
        lockReply = { path, _ -> if (path.endsWith("/verify")) lockPage(ours=listOfNotNull(active)) else JsonObject().apply { writes++;active=lock() } }
        val retryReview=service.reviewLocks(target.path)
        val failure=runCatching { service.createReviewedLock(retryReview,"asset.bin") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(LfsLockManagementException::class.java);assertThat(failure!!.message).contains("may have applied")
        assertThat(runCatching { service.createReviewedLock(retryReview,"asset.bin") }.exceptionOrNull()).isInstanceOf(LfsLockReviewChanged::class.java)
        assertThat(writes).isEqualTo(2)
    }
    @Test fun `separate endpoint credentials and repository changes during approval stop lock writes`() = runBlocking {
        val target=clone();commitLockable(target)
        Git.open(target).use { it.repository.config.apply { setString("lfs",null,"url","${java.net.URI(url).scheme}://localhost:${server.address.port}/custom-lfs");save() } }
        val oldVerifier=LfsEndpointTrust.verifier();val oldCredentials=LfsEndpointTrust.credentials()
        var header="Bearer separate-one";customLockAuth=header
        var duringApproval:(()->Unit)?=null
        try {
            LfsEndpointTrust.install(LfsEndpointVerifier { _,_,_,_ -> duringApproval?.invoke() }, LfsEndpointCredentials { _,_,_,_ -> mapOf("Authorization" to header) })
            val service=LfsRepository(credentialResolver={_,_->credentials})
            val review=service.reviewLocks(target.path)
            header="Bearer separate-two";customLockAuth=header
            assertThat(runCatching { service.createReviewedLock(review,"asset.bin") }.exceptionOrNull()).isInstanceOf(LfsLockReviewChanged::class.java)
            val current=service.reviewLocks(target.path)
            duringApproval={File(target,".gitattributes").appendText("\n# Changed while consent was open\n")}
            assertThat(runCatching { service.createReviewedLock(current,"asset.bin") }.exceptionOrNull()).isInstanceOf(LfsLockReviewChanged::class.java)
            assertThat(lockRequests.all { it.first.contains("/verify") }).isTrue()
            assertThat(review.toString()).doesNotContain("separate-one")
        } finally {LfsEndpointTrust.install(oldVerifier,oldCredentials)}
    }

    @Test fun lockCreationRejectsReplacementWorkingFolderWithOriginalMetadata(): Unit = runBlocking { rejectReplacedLockWorktree("create") }
    @Test fun lockReleaseRejectsReplacementWorkingFolderWithOriginalMetadata(): Unit = runBlocking { rejectReplacedLockWorktree("release") }
    @Test fun forceReleaseRejectsReplacementWorkingFolderWithOriginalMetadata(): Unit = runBlocking { rejectReplacedLockWorktree("force") }

    private suspend fun rejectReplacedLockWorktree(mode: String) {
        val target = clone(); commitLockable(target)
        var active: JsonObject? = if (mode == "create") null else lock()
        var writes = 0
        lockReply = { path, body -> when {
            path.endsWith("/verify") -> if (mode == "force") lockPage(theirs = listOfNotNull(active)) else lockPage(ours = listOfNotNull(active))
            path.endsWith("/unlock") -> JsonObject().apply { writes++; add("lock", active); active = null }
            else -> JsonObject().apply { writes++; active = lock(body!!.get("path").asString); add("lock", active) }
        } }
        val service = LfsRepository(credentialResolver = { _, _ -> credentials })
        val review = service.reviewLocks(target.path)
        val oldIdentity = com.bontecou.syncmd.services.repositories.GitTrackingRemoval.metadataIdentity(target.path)
        val parked = File(root, "parked-${target.name}")
        java.nio.file.Files.move(target.toPath(), parked.toPath())
        check(target.mkdir())
        // Preserve the exact metadata directory, not a copy: only the worktree identity changes.
        java.nio.file.Files.move(File(parked, ".git").toPath(), File(target, ".git").toPath())
        parked.listFiles()!!.forEach { check(it.copyRecursively(File(target, it.name))) }
        assertThat(com.bontecou.syncmd.services.repositories.GitTrackingRemoval.metadataIdentity(target.path)).isEqualTo(oldIdentity)
        fun contents(folder: File) = folder.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(folder).invariantSeparatorsPath to it.readBytes().toList() }
        val before = contents(target); val parkedBefore = contents(parked)
        val requests = lockRequests.size
        suspend fun apply(selected: LfsLockReview): LfsLockChange = when (mode) {
            "create" -> service.createReviewedLock(selected, "asset.bin")
            "force" -> service.releaseReviewedLock(selected, selected.theirs.single(), true)
            else -> service.releaseReviewedLock(selected, selected.ours.single())
        }
        assertThat(runCatching { apply(review) }.exceptionOrNull()).isInstanceOf(LfsLockReviewChanged::class.java)
        assertThat(writes).isEqualTo(0)
        assertThat(lockRequests).hasSize(requests)
        assertThat(contents(target)).isEqualTo(before)
        assertThat(contents(parked)).isEqualTo(parkedBefore)
        val current = service.reviewLocks(target.path)
        assertThat(current.context).isNotEqualTo(review.context)
        apply(current)
        assertThat(writes).isEqualTo(1)
        assertThat(contents(target)).isEqualTo(before)
        assertThat(contents(parked)).isEqualTo(parkedBefore)
    }

    private fun publishPointer(bytes: ByteArray, branch: String = "main", conflict: Boolean = false) {
        storage[pointer(bytes).oid] = bytes
        Git.open(writer).use { git ->
            if (branch != "main") git.checkout().setCreateBranch(true).setName(branch).call()
            File(writer, "asset.bin").writeBytes(pointer(bytes).bytes())
            if (conflict) File(writer, "conflict.md").writeText("Remote choice\n")
            git.add().addFilepattern(".").call()
            git.commit().setAuthor("Fixture", "fixture@example.invalid").setMessage("New pointer on $branch").call()
            git.push().setRemote(bare.toURI().toString()).setPushAll().call()
        }
    }
    private fun assetBranch(target: File) {
        GitLfs.open(target).use { git ->
            git.fetch().setRemote(bare.toURI().toString()).setRefSpecs(org.eclipse.jgit.transport.RefSpec("+refs/heads/*:refs/remotes/origin/*")).call()
            git.branchCreate().setName("assets").setStartPoint("origin/assets")
                .setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.TRACK).call()
        }
    }

    @Test fun `branch switch downloads missing objects using the branch remote and keeps pointer index`() = runBlocking {
        val bytes = initialBytes + byteArrayOf(11)
        publishPointer(bytes, "assets")
        val target = clone(); assetBranch(target)
        Git.open(target).use { git ->
            git.repository.config.apply {
                setStringList("remote", "mirror", "url", listOf(url)); unsetSection("remote", "origin")
                setString("branch", "assets", "remote", "mirror"); save()
            }
        }
        var authCalls = 0
        LocalBranchRepository(credentialResolver = { path, selected ->
            check(path == target.canonicalPath && selected == url); authCalls++; credentials
        }).switchBranch(target.path, "assets").getOrThrow()
        assertThat(authCalls).isEqualTo(1)
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(bytes)
        GitLfs.open(target).use { git ->
            val indexed = git.repository.readDirCache().getEntry("asset.bin")
            assertThat(git.repository.open(indexed.objectId).bytes).isEqualTo(pointer(bytes).bytes())
            assertThat(git.status().call().isClean).isTrue()
            assertThat(git.repository.branch).isEqualTo("assets")
        }
        assertThat(uploads).isEqualTo(0); assertThat(escapedCredentials).isEqualTo(0)
    }
    @Test fun `branch switch download failure keeps changed branch and allows independent LFS retry`() = runBlocking {
        val bytes = initialBytes + byteArrayOf(12)
        publishPointer(bytes, "assets"); val target = clone(); assetBranch(target)
        corrupt = true
        val result = LocalBranchRepository(credentialResolver = { _, _ -> credentials }).switchBranch(target.path, "assets")
        assertThat(result.exceptionOrNull()).isInstanceOf(LfsCheckoutIncompleteException::class.java)
        assertThat(result.exceptionOrNull()!!.message).contains("Branch switched")
        assertThat(Git.open(target).use { it.repository.branch }).isEqualTo("assets")
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(pointer(bytes).bytes())
        val head = Git.open(target).use { it.repository.resolve("HEAD") }
        corrupt = false; LfsRepository(credentialResolver = { _, _ -> credentials }).hydrate(target.path)
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(bytes)
        assertThat(Git.open(target).use { it.repository.resolve("HEAD") }).isEqualTo(head)
    }
    @Test fun `branch switch protects external edits during object download`() = runBlocking {
        publishPointer(initialBytes + byteArrayOf(13), "assets"); val target = clone(); assetBranch(target)
        beforeDownload = { File(target, "asset.bin").writeText("External draft\n") }
        val result = LocalBranchRepository(credentialResolver = { _, _ -> credentials }).switchBranch(target.path, "assets")
        assertThat(result.exceptionOrNull()).isInstanceOf(LfsCheckoutIncompleteException::class.java)
        assertThat(File(target, "asset.bin").readText()).isEqualTo("External draft\n")
        assertThat(Git.open(target).use { it.repository.branch }).isEqualTo("assets")
    }
    @Test fun `cached branch checkout remains offline without a configured remote`() = runBlocking {
        publishPointer(initialBytes + byteArrayOf(14), "assets"); val target = clone(); assetBranch(target)
        val branch = LocalBranchRepository(credentialResolver = { _, _ -> credentials })
        branch.switchBranch(target.path, "assets").getOrThrow(); branch.switchBranch(target.path, "main").getOrThrow()
        Git.open(target).use { it.repository.config.apply { unsetSection("remote", "origin"); save() } }
        LocalBranchRepository(credentialResolver = { _, _ -> error("Offline cache must not request credentials") })
            .switchBranch(target.path, "assets").getOrThrow()
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(initialBytes + byteArrayOf(14))
    }
    @Test fun `successful merge hydrates new pointer and failed download reports completed merge`() = runBlocking {
        val bytes = initialBytes + byteArrayOf(15)
        publishPointer(bytes, "assets"); val target = clone(); assetBranch(target)
        corrupt = true
        val result = LocalBranchRepository(credentialResolver = { _, _ -> credentials }).merge(target.path, "assets").getOrThrow()
        assertThat(result.success).isTrue(); assertThat(result.message).contains("Merge completed")
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(pointer(bytes).bytes())
        corrupt = false
        LocalBranchRepository(credentialResolver = { _, _ -> credentials }).merge(target.path, "assets").getOrThrow()
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(bytes)
        assertThat(GitLfs.open(target).use { it.status().call().isClean }).isTrue()
    }
    @Test fun `completed rebase hydrates upstream pointer and retains replayed local work`() = runBlocking {
        val target = clone(); val bytes = initialBytes + byteArrayOf(16)
        File(target, "local.md").writeText("Retained local commit\n")
        LocalDiffRepository().apply { stageAll(target.path).getOrThrow(); commit(target.path, "Local work", "Fixture", "fixture@example.invalid").getOrThrow() }
        publishPointer(bytes)
        val rebase = RepositoryRebase(credentials = { _, _ -> credentials }, author = { "Fixture" to "fixture@example.invalid" })
        val plan = rebase.plan(target.path)
        val result = rebase.begin(plan)
        assertThat(result.session).isNull(); assertThat(result.notice).isNull()
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(bytes)
        assertThat(File(target, "local.md").readText()).isEqualTo("Retained local commit\n")
        GitLfs.open(target).use { git ->
            val status = git.status().call()
            val entry = git.repository.readDirCache().getEntry("asset.bin")
            assertThat(git.repository.open(entry.objectId).bytes).isEqualTo(pointer(bytes).bytes())
            org.eclipse.jgit.treewalk.TreeWalk.forPath(git.repository, "asset.bin", git.repository.resolve("HEAD^{tree}")).use { tree ->
                assertThat(tree.getObjectId(0)).isEqualTo(entry.objectId)
            }
            assertThat(status.uncommittedChanges).isEmpty()
            assertThat(status.untracked).isEmpty()
            assertThat(git.log().setMaxCount(1).call().single().getParent(0).name).isEqualTo(plan.upstreamHead)
        }
        File(target, "asset.bin").writeBytes(bytes.copyOf().apply { this[0] = 99 })
        assertThat(LocalGitRepository().getStatus(target.path).getOrThrow().map { it.filePath }).contains("asset.bin")
        assertThat(uploads).isEqualTo(0)
    }
    @Test fun `rebase completed before download failure returns attention without retrying history rewrite`() = runBlocking {
        val target = clone(); val bytes = initialBytes + byteArrayOf(17)
        File(target, "local.md").writeText("Local work\n")
        LocalDiffRepository().apply { stageAll(target.path).getOrThrow(); commit(target.path, "Local work", "Fixture", "fixture@example.invalid").getOrThrow() }
        publishPointer(bytes); corrupt = true
        val rebase = RepositoryRebase(credentials = { _, _ -> credentials }, author = { "Fixture" to "fixture@example.invalid" })
        val result = rebase.begin(rebase.plan(target.path))
        assertThat(result.session).isNull(); assertThat(result.notice).contains("Rebase action completed")
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(pointer(bytes).bytes())
        val head = Git.open(target).use { it.repository.resolve("HEAD") }
        corrupt = false; LfsRepository(credentialResolver = { _, _ -> credentials }).hydrate(target.path)
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(bytes)
        assertThat(Git.open(target).use { it.repository.resolve("HEAD") }).isEqualTo(head)
    }
    @Test fun `paused rebase hydrates nonconflicting files and returns a fresh resumable session`() = runBlocking {
        val target = clone(); val bytes = initialBytes + byteArrayOf(18)
        File(target, "conflict.md").writeText("Local choice\n")
        LocalDiffRepository().apply { stageAll(target.path).getOrThrow(); commit(target.path, "Local conflict", "Fixture", "fixture@example.invalid").getOrThrow() }
        publishPointer(bytes, conflict = true)
        val rebase = RepositoryRebase(credentials = { _, _ -> credentials }, author = { "Fixture" to "fixture@example.invalid" })
        val result = rebase.begin(rebase.plan(target.path))
        assertThat(result.session!!.conflicts.single().filePath).isEqualTo("conflict.md")
        assertThat(result.notice).isNull()
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(bytes)
        assertThat(rebase.session(target.path)!!.revision).isEqualTo(result.session!!.revision)
        LocalConflictRepository().resolveConflict(target.path, "conflict.md", com.bontecou.syncmd.data.models.ConflictResolutionStrategy.MANUAL, "Combined\n").getOrThrow()
        val finished = rebase.continueRebase(rebase.session(target.path)!!)
        assertThat(finished.session).isNull()
        assertThat(File(target, "conflict.md").readText()).isEqualTo("Combined\n")
        assertThat(GitLfs.open(target).use { it.status().call().isClean }).isTrue()
    }
    @Test fun `LFS conflict chooses a whole pointer side hydrates it and keeps the staged pointer`() = runBlocking {
        val target = clone(); val local = initialBytes + byteArrayOf(19); val upstream = initialBytes + byteArrayOf(20)
        File(target, "asset.bin").writeBytes(local)
        LocalDiffRepository().apply { stageAll(target.path).getOrThrow(); commit(target.path, "Local asset", "Fixture", "fixture@example.invalid").getOrThrow() }
        publishPointer(upstream)
        val rebase = RepositoryRebase(credentials = { _, _ -> credentials }, author = { "Fixture" to "fixture@example.invalid" })
        val result = rebase.begin(rebase.plan(target.path))
        assertThat(result.session!!.conflicts.single().isBinaryOrTooLarge).isTrue()
        val conflicts = LocalConflictRepository(credentialResolver = { _, _ -> credentials })
        assertThat(conflicts.resolveConflict(target.path, "asset.bin", com.bontecou.syncmd.data.models.ConflictResolutionStrategy.MANUAL, "Not a pointer").isFailure).isTrue()
        val resolution = conflicts.resolveConflict(target.path, "asset.bin", com.bontecou.syncmd.data.models.ConflictResolutionStrategy.OURS).getOrThrow()
        assertThat(resolution.resolved).isTrue(); assertThat(resolution.message).isEmpty()
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(upstream)
        GitLfs.open(target).use { git ->
            assertThat(git.status().call().conflicting).isEmpty()
            assertThat(git.repository.open(git.repository.readDirCache().getEntry("asset.bin").objectId).bytes).isEqualTo(pointer(upstream).bytes())
        }
        assertThat(rebase.abort(rebase.session(target.path)!!).session).isNull()
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(local)
    }
    @Test fun `resolved LFS conflict remains staged on download failure and can hydrate without resolving again`() = runBlocking {
        val target = clone(); val local = initialBytes + byteArrayOf(21); val upstream = initialBytes + byteArrayOf(22)
        File(target, "asset.bin").writeBytes(local)
        LocalDiffRepository().apply { stageAll(target.path).getOrThrow(); commit(target.path, "Local asset", "Fixture", "fixture@example.invalid").getOrThrow() }
        publishPointer(upstream)
        val rebase = RepositoryRebase(credentials = { _, _ -> credentials }, author = { "Fixture" to "fixture@example.invalid" })
        rebase.begin(rebase.plan(target.path)); corrupt = true
        val resolution = LocalConflictRepository(credentialResolver = { _, _ -> credentials })
            .resolveConflict(target.path, "asset.bin", com.bontecou.syncmd.data.models.ConflictResolutionStrategy.OURS).getOrThrow()
        assertThat(resolution.resolved).isTrue(); assertThat(resolution.message).contains("Conflict resolved and staged")
        assertThat(rebase.session(target.path)!!.conflicts).isEmpty()
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(pointer(upstream).bytes())
        corrupt = false; LfsRepository(credentialResolver = { _, _ -> credentials }).hydrate(target.path)
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(upstream)
        assertThat(rebase.session(target.path)!!.conflicts).isEmpty()
    }

    @Test fun `lock preflight needs only push advertisement on a write only Git endpoint`() = runBlocking {
        val target = clone(); commitLockable(target)
        denyGitFetch = true
        pusher().push(target.path, "main", "origin", "main").getOrThrow()
        assertThat(lockRequests).hasSize(1)
        assertThat(uploads).isEqualTo(1)
    }
    @Test fun `new branch and tag do not claim unmodified locked files already published on another ref`() = runBlocking {
        Git.open(writer).use { git ->
            File(writer, ".gitattributes").appendText("*.bin lockable\n")
            git.add().addFilepattern(".").call(); git.commit().setAuthor("Fixture", "fixture@example.invalid").setMessage("Lock existing asset").call()
            git.push().setRemote(bare.toURI().toString()).setPushAll().call()
        }
        val target = clone()
        lockReply = { _, _ -> lockPage(theirs = listOf(lock())) }
        pusher().push(target.path, "main", "origin", "new-branch").getOrThrow()
        File(target, "notes.md").writeText("New notes only")
        LocalDiffRepository().apply { stageAll(target.path).getOrThrow(); commit(target.path, "Notes only", "Fixture", "fixture@example.invalid").getOrThrow() }
        pusher().push(target.path, "main", "origin", "notes-branch").getOrThrow()
        Git.open(target).use { it.tag().setName("published-notes").setAnnotated(false).call() }
        pusher().pushTag(target.path, "published-notes", "origin").getOrThrow()
        assertThat(lockRequests).isEmpty()
        assertThat(uploads).isEqualTo(0)
    }

    @Test fun `foreign lock on later page blocks before object upload and retry keeps the same local commit`() = runBlocking {
        val target = clone(); commitLockable(target)
        val remoteBefore = Git.open(bare).use { it.repository.resolve("HEAD") }
        val local = Git.open(target).use { it.repository.resolve("HEAD") }
        val index = File(target, ".git/index").readBytes()
        lockReply = { _, body -> if (body?.has("cursor") == true) lockPage(theirs = listOf(lock())) else lockPage(ours = listOf(lock("owned.bin", "ours")), cursor = "next / 名") }
        val blocked = pusher().push(target.path, "main", "origin", "main")
        assertThat(blocked.exceptionOrNull()).isInstanceOf(LfsLockedFilesException::class.java)
        assertThat((blocked.exceptionOrNull() as LfsLockedFilesException).paths).containsExactly("asset.bin")
        assertThat(blocked.exceptionOrNull()!!.message).contains("locked by Fixture teammate")
        assertThat(uploads).isEqualTo(0); assertThat(verifications).isEqualTo(0)
        assertThat(Git.open(bare).use { it.repository.resolve("HEAD") }).isEqualTo(remoteBefore)
        assertThat(File(target, ".git/index").readBytes()).isEqualTo(index)
        assertThat(lockRequests.size).isEqualTo(2)
        assertThat(lockRequests[0].second!!.getAsJsonObject("ref").get("name").asString).isEqualTo("refs/heads/main")
        assertThat(lockRequests[1].second!!.get("cursor").asString).isEqualTo("next / 名")
        lockReply = { _, _ -> lockPage(ours = listOf(lock())) }
        pusher().push(target.path, "main", "origin", "main").getOrThrow()
        assertThat(Git.open(bare).use { it.repository.resolve("HEAD") }).isEqualTo(local)
        assertThat(Git.open(target).use { it.repository.resolve("HEAD") }).isEqualTo(local)
        assertThat(uploads).isEqualTo(1)
    }
    @Test fun `only unsupported locking responses allow push while auth redirects and server errors fail closed`() = runBlocking {
        val target = clone(); commitLockable(target)
        val before = Git.open(bare).use { it.repository.resolve("HEAD") }
        for (status in listOf(401, 403, 500, 302)) {
            lockStatus = status
            assertThat(pusher().push(target.path, "main", "origin", "main").isFailure).isTrue()
            assertThat(Git.open(bare).use { it.repository.resolve("HEAD") }).isEqualTo(before)
        }
        assertThat(uploads).isEqualTo(0); assertThat(escapedRequests).isEqualTo(0)
        lockStatus = 404; pusher().push(target.path, "main", "origin", "main").getOrThrow()
        File(target, "asset.bin").writeBytes(initialBytes + byteArrayOf(68))
        LocalDiffRepository().apply { stageAll(target.path).getOrThrow(); commit(target.path, "501 server", "Fixture", "fixture@example.invalid").getOrThrow() }
        lockStatus = 501; pusher().push(target.path, "main", "origin", "main").getOrThrow()
        assertThat(uploads).isEqualTo(2)
    }
    @Test fun `malformed ownership duplicate locks and repeated cursors cannot bypass verification`() = runBlocking {
        val target = clone(); commitLockable(target)
        val replies = listOf(JsonObject(), lockPage(ours = listOf(lock()), theirs = listOf(lock())), lockPage(cursor = "loop"))
        for (reply in replies) {
            lockReply = { _, _ -> reply }
            assertThat(pusher().push(target.path, "main", "origin", "main").isFailure).isTrue()
        }
        assertThat(uploads).isEqualTo(0)
    }
    @Test fun `create list and unlock retain opaque identifiers Unicode cursors ref and force semantics`() = runBlocking {
        val target = clone()
        val locks = LfsRepository(credentialResolver = { _, _ -> credentials })
        val name = "folder/guide 名\tfile.bin"; val id = "opaque / 名 id"
        lockReply = { _, _ -> JsonObject().apply { add("lock", lock(name, id)) } }
        assertThat(locks.createLock(target.path, name, "refs/heads/main")!!.id).isEqualTo(id)
        assertThat(lockRequests.last().second!!.get("path").asString).isEqualTo(name)
        lockReply = { _, _ -> JsonObject().apply { add("locks", JsonArray().apply { add(lock(name, id)) }); addProperty("next_cursor", "next") } }
        assertThat(locks.listLocks(target.path, "a & 名", "refs/heads/main").locks.single().path).isEqualTo(name)
        assertThat(lockRequests.last().first).contains("cursor=a%20%26%20")
        lockReply = { _, _ -> JsonObject().apply { add("lock", lock(name, id)) } }
        assertThat(locks.unlock(target.path, id, ref = "refs/heads/main")!!.id).isEqualTo(id)
        assertThat(lockRequests.last().second!!.get("force").asBoolean).isFalse()
        assertThat(lockRequests.last().first).contains("opaque%20%2F%20")
        locks.unlock(target.path, id, force = true, ref = "refs/heads/main")
        assertThat(lockRequests.last().second!!.get("force").asBoolean).isTrue()
        lockStatus = 409
        assertThat(runCatching { locks.createLock(target.path, name) }.exceptionOrNull()).isInstanceOf(LfsHttpStatusException::class.java)
    }
    @Test fun `destination created during lock verification is not overwritten even by force push`() = runBlocking {
        val target = clone(); commitLockable(target)
        val before = Git.open(bare).use { it.repository.resolve("HEAD") }
        beforeLockReply = { Git.open(bare).use { git ->
            if (git.repository.exactRef("refs/heads/race") == null) git.branchCreate().setName("race").setStartPoint("main").call()
        } }
        assertThat(pusher().push(target.path, "main", "origin", "race", true).isFailure).isTrue()
        assertThat(Git.open(bare).use { it.repository.resolve("refs/heads/race") }).isEqualTo(before)
    }
    @Test fun `real HTTPS clone hydrates stage cleans push verifies and pull hydrates new objects`() = runBlocking { roundTrip() }
    private suspend fun roundTrip() {
        val target = clone()
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(initialBytes)
        assertThat(LocalGitRepository().getStatus(target.path).getOrThrow()).isEmpty()
        val updated = initialBytes + byteArrayOf(4, 5, 6)
        File(target, "asset.bin").writeBytes(updated)
        val diff = LocalDiffRepository()
        diff.stageAll(target.path).getOrThrow(); diff.commit(target.path, "Updated LFS", "Fixture", "fixture@example.invalid").getOrThrow()
        LocalRemoteRepository(credentialResolver = { _, selected -> check(selected == url); credentials }).push(target.path, "main", "origin", "main").getOrThrow()
        assertThat(storage[pointer(updated).oid]).isEqualTo(updated)
        assertThat(uploads).isEqualTo(1); assertThat(verifications).isEqualTo(1); assertThat(escapedCredentials).isEqualTo(0)
        val remoteBytes = ByteArray(9000) { (it % 243).toByte() }
        storage[pointer(remoteBytes).oid] = remoteBytes
        Git.open(writer).use { git ->
            git.fetch().setRemote(bare.toURI().toString()).setRefSpecs(org.eclipse.jgit.transport.RefSpec("+refs/heads/*:refs/remotes/fixture/*")).call()
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("refs/remotes/fixture/main").call()
            File(writer, "asset.bin").writeBytes(pointer(remoteBytes).bytes())
            git.add().addFilepattern("asset.bin").call(); git.commit().setAuthor("Fixture", "fixture@example.invalid").setMessage("Upstream LFS").call()
            git.push().setRemote(bare.toURI().toString()).setPushAll().call()
        }
        LocalPullRepository(credentialResolver = { _, _ -> credentials }).executePull(target.path).getOrThrow()
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(remoteBytes)
        assertThat(LocalGitRepository().getStatus(target.path).getOrThrow()).isEmpty()
    }
    @Test fun `completed fast forward reports LFS attention when object download fails`() = runBlocking {
        val target = clone()
        val remoteBytes = initialBytes + byteArrayOf(77, 78)
        val expected = pointer(remoteBytes)
        storage[expected.oid] = remoteBytes
        Git.open(writer).use { git ->
            File(writer, "asset.bin").writeBytes(expected.bytes())
            git.add().addFilepattern("asset.bin").call()
            git.commit().setAuthor("Fixture", "fixture@example.invalid").setMessage("New upstream pointer").call()
            git.push().setRemote(bare.toURI().toString()).setPushAll().call()
        }
        corrupt = true
        val result = LocalPullRepository(credentialResolver = { _, _ -> credentials }).executePull(target.path).getOrThrow()
        assertThat(result.success).isTrue()
        assertThat(result.attention).contains("Pull completed")
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(expected.bytes())
        assertThat(Git.open(target).use { it.repository.resolve("HEAD") }).isEqualTo(Git.open(bare).use { it.repository.resolve("HEAD") })
        corrupt = false
        LfsRepository(credentialResolver = { _, _ -> credentials }).hydrate(target.path)
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(remoteBytes)
    }
    @Test fun `failed object verification prevents publishing the Git commit and retains local edits`() = runBlocking {
        val target = clone()
        val before = Git.open(bare).use { it.repository.resolve("HEAD") }
        File(target, "asset.bin").writeBytes(initialBytes + byteArrayOf(99))
        val diff = LocalDiffRepository(); diff.stageAll(target.path).getOrThrow(); diff.commit(target.path, "Retained local commit", "Fixture", "fixture@example.invalid").getOrThrow()
        val localHead = Git.open(target).use { it.repository.resolve("HEAD") }
        failVerify = true
        val result = LocalRemoteRepository(credentialResolver = { _, _ -> credentials }).push(target.path, "main", "origin", "main")
        assertThat(result.isFailure).isTrue()
        assertThat(Git.open(bare).use { it.repository.resolve("HEAD") }).isEqualTo(before)
        assertThat(Git.open(target).use { it.repository.resolve("HEAD") }).isEqualTo(localHead)
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(initialBytes + byteArrayOf(99))
    }
    @Test fun `corrupt download cannot replace pointer or publish corrupt cache`() = runBlocking {
        val target = clone(); val expected = pointer(initialBytes)
        GitLfs.open(target).use { LfsObjectStore(it.repository).verified(expected)!!.delete() }
        File(target, "asset.bin").writeBytes(expected.bytes()); corrupt = true
        assertThat(runCatching { LfsRepository(credentialResolver = { _, _ -> credentials }).hydrate(target.path) }.isFailure).isTrue()
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(expected.bytes())
        GitLfs.open(target).use { assertThat(LfsObjectStore(it.repository).verified(expected)).isNull() }
    }
    @Test fun `corrupt cached pointer content can be downloaded and repaired without trusting old bytes`() = runBlocking {
        val target = clone(); val expected = pointer(initialBytes)
        GitLfs.open(target).use { LfsObjectStore(it.repository).verified(expected)!!.writeText("Corrupt fixture cache") }
        File(target, "asset.bin").writeBytes(expected.bytes())
        LfsRepository(credentialResolver = { _, _ -> credentials }).hydrate(target.path)
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(initialBytes)
        GitLfs.open(target).use { assertThat(LfsObjectStore(it.repository).verified(expected)!!.readBytes()).isEqualTo(initialBytes) }
    }
    @Test fun `redirected object requests stop without forwarding action credentials`() = runBlocking {
        val target = clone(); val expected = pointer(initialBytes)
        GitLfs.open(target).use { LfsObjectStore(it.repository).verified(expected)!!.delete() }
        File(target, "asset.bin").writeBytes(expected.bytes()); redirect = true
        assertThat(runCatching { LfsRepository(credentialResolver = { _, _ -> credentials }).hydrate(target.path) }.isFailure).isTrue()
        assertThat(escapedRequests).isEqualTo(0)
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(expected.bytes())
    }
    @Test fun `external edits during download survive while verified cache remains reusable`() = runBlocking {
        val target = clone(); val expected = pointer(initialBytes)
        GitLfs.open(target).use { LfsObjectStore(it.repository).verified(expected)!!.delete() }
        File(target, "asset.bin").writeBytes(expected.bytes())
        beforeDownload = { File(target, "asset.bin").writeText("External draft while downloading\n") }
        val failure = runCatching { LfsRepository(credentialResolver = { _, _ -> credentials }).hydrate(target.path) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(LfsWorktreeChangedException::class.java)
        assertThat(File(target, "asset.bin").readText()).isEqualTo("External draft while downloading\n")
        GitLfs.open(target).use { assertThat(LfsObjectStore(it.repository).verified(expected)!!.readBytes()).isEqualTo(initialBytes) }
    }
    @Test fun `index lock held by another client prevents hydration from replacing working pointer`() = runBlocking {
        val target = clone(); val expected = pointer(initialBytes)
        GitLfs.open(target).use { LfsObjectStore(it.repository).verified(expected)!!.delete() }
        File(target, "asset.bin").writeBytes(expected.bytes())
        val before = File(target, ".git/index").readBytes()
        val external = File(target, ".git/index.lock").apply { writeText("External Git operation\n") }
        assertThat(runCatching { LfsRepository(credentialResolver = { _, _ -> credentials }).hydrate(target.path) }.isFailure).isTrue()
        assertThat(File(target, "asset.bin").readBytes()).isEqualTo(expected.bytes())
        assertThat(File(target, ".git/index").readBytes()).isEqualTo(before)
        assertThat(external.readText()).isEqualTo("External Git operation\n")
    }
    @Test fun `batch omissions and duplicate JSON are rejected before object replacement`() = runBlocking {
        for ((name,body) in listOf("omitted" to "{\"objects\":[]}","duplicate" to "{\"objects\":[],\"objects\":[]}")) {
            malformedBatch = body
            val target = File(root,name)
            assertThat(SafeCloneService(LocalGitRepository()).clone(url,target.path,credentials).isSuccess).isTrue()
            val index = File(target,".git/index").readBytes()
            val head = Git.open(target).use { it.repository.resolve("HEAD") }
            assertThat(runCatching { LfsRepository(credentialResolver = { _, _ -> credentials }).hydrate(target.path) }.isFailure).isTrue()
            assertThat(File(target,"asset.bin").readBytes()).isEqualTo(pointer(initialBytes).bytes())
            assertThat(File(target,".git/index").readBytes()).isEqualTo(index)
            Git.open(target).use { git ->
                assertThat(git.repository.resolve("HEAD")).isEqualTo(head)
                assertThat(LfsObjectStore(git.repository).contains(pointer(initialBytes))).isFalse()
            }
        }
    }
}

package com.bontecou.syncmd.services.obsidian

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class ObsidianRequest(val requestID: String, val repoID: String, val remoteURL: String, val expiresAt: Double) {
    fun json() = JsonObject().apply {
        addProperty("version", 1); addProperty("requestID", requestID); addProperty("repoID", repoID)
        addProperty("remoteURL", remoteURL); addProperty("expiresAt", expiresAt)
    }
}

/** Only fingerprints of public handoff files are journaled; no credential or private key. */
data class ObsidianPreparation(val request: ObsidianRequest, val previous: Map<String, String?>) {
    init {
        require(previous.keys == FILES.toSet())
        require(previous.values.all { it == null || it.matches(Regex("[a-f0-9]{64}")) })
    }
    companion object { val FILES = listOf("request", "connection", "recipient", "response") }
}

/** Wire compatible with GitSync.md Connect's WebCrypto P-256 / HKDF / AES-GCM protocol. */
class ObsidianHandoff(private val now: () -> Double = { System.currentTimeMillis() / 1000.0 }) {
    fun prepare(path: String, repoID: String, remoteURL: String, authorize: () -> Unit = {}): ObsidianRequest {
        val plan = planPreparation(path, repoID, remoteURL, authorize)
        finishPreparation(path, plan, authorize)
        return plan.request
    }

    /** The host durably reserves this plan before any existing handoff file changes. */
    fun planPreparation(path: String, repoID: String, remoteURL: String, authorize: () -> Unit = {}): ObsidianPreparation {
        uuid(repoID); require(isGitHubHTTPS(remoteURL)); authorize()
        val directory = checkedDirectory(path, true)
        require(originURL(read(File(path, ".git/config"))) == remoteURL)
        val request = ObsidianRequest(UUID.randomUUID().toString(), repoID, remoteURL, now() + 7 * 86400)
        val previous = ObsidianPreparation.FILES.associateWith { fingerprint(File(directory, "$it.json")) }
        authorize()
        return ObsidianPreparation(request, previous)
    }

    /** Accept exactly the old snapshot or a prefix of this plan's own publication. */
    fun verifyPreparation(path: String, plan: ObsidianPreparation) {
        require(plan.request.expiresAt.isFinite() && plan.request.expiresAt > now())
        uuid(plan.request.repoID); uuid(plan.request.requestID); require(isGitHubHTTPS(plan.request.remoteURL))
        val directory = checkedDirectory(path, false)
        require(originURL(read(File(path, ".git/config"))) == plan.request.remoteURL)
        val current = ObsidianPreparation.FILES.associateWith { fingerprint(File(directory, "$it.json")) }
        val published = digest(plan.request.json().toString())
        if (current["request"] == published) {
            require(current["connection"] == published || current["connection"] == plan.previous["connection"])
        } else {
            require(current["request"] == plan.previous["request"] && current["connection"] == plan.previous["connection"])
            for (name in listOf("recipient", "response")) require(current[name] == null || current[name] == plan.previous[name])
        }
    }

    /** Replaying preserves a recipient written after this request became visible. */
    fun finishPreparation(path: String, plan: ObsidianPreparation, authorize: () -> Unit = {}) {
        verifyPreparation(path, plan); authorize()
        val directory = checkedDirectory(path, false)
        val value = plan.request.json().toString(); val published = digest(value)
        if (fingerprint(File(directory, "request.json")) != published) {
            for (name in listOf("recipient", "response")) {
                authorize(); verifyPreparation(path, plan)
                val file = File(directory, "$name.json")
                if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.delete(file.toPath())
            }
            write(directory, "request.json", value) {
                authorize(); verifyPreparation(path, plan)
                require(fingerprint(File(directory, "recipient.json")) == null && fingerprint(File(directory, "response.json")) == null)
            }
        }
        if (fingerprint(File(directory, "connection.json")) != published) write(directory, "connection.json", value) {
            authorize(); verifyPreparation(path, plan)
            require(fingerprint(File(directory, "request.json")) == published)
        }
        authorize(); verifyConnection(path, plan.request)
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun fingerprint(file: File): String? = if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) digest(read(file)) else null

    /** Read-only check for a verified vault relocation; never renews or consumes the request. */
    fun verifyConnection(path: String, request: ObsidianRequest, pending: Boolean = true) {
        val directory = checkedDirectory(path, false)
        require(originURL(read(File(path, ".git/config"))) == request.remoteURL)
        require(parseRequest(read(File(directory, "connection.json"))) == request)
        val requestFile = File(directory, "request.json")
        // The companion deletes request/recipient/response after successfully importing credentials.
        if (pending || Files.exists(requestFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(parseRequest(read(requestFile)) == request)
        }
    }

    /** The host consumes its private authorization in beforePublish, before ciphertext becomes visible. */
    fun respond(path: String, request: ObsidianRequest, username: String, token: String,
        author: Pair<String, String>, beforePublish: () -> Unit) {
        require(request.expiresAt > now() && request.expiresAt.isFinite() && username.isNotBlank() && token.isNotBlank())
        require(username.length <= 1024 && token.length <= 32768 && isGitHubHTTPS(request.remoteURL))
        uuid(request.repoID); uuid(request.requestID)
        val directory = checkedDirectory(path, false)
        require(originURL(read(File(path, ".git/config"))) == request.remoteURL)
        require(parseRequest(read(File(directory, "request.json"))) == request)
        val recipient = json(read(File(directory, "recipient.json")))
        require(recipient.keySet() == setOf("version", "requestID", "publicKey") && integer(recipient, "version") == 1)
        require(string(recipient, "requestID") == request.requestID)
        val raw = Base64.getDecoder().decode(string(recipient, "publicKey"))
        require(raw.size == 65 && raw[0] == 4.toByte())
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(
            ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65))), params))
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val shared = KeyAgreement.getInstance("ECDH").apply { init(pair.private); doPhase(publicKey, true) }.generateSecret()
        val key = hkdf(shared, request.requestID.toByteArray(Charsets.UTF_8))
        val payload = request.json().apply {
            remove("repoID")
            addProperty("username", username); addProperty("password", token)
            addProperty("authorName", author.first); addProperty("authorEmail", author.second)
            addProperty("expiresAt", minOf(request.expiresAt, now() + 300))
        }.toString().toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val encrypted = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(request.requestID.toByteArray(Charsets.UTF_8)); doFinal(payload)
            }
        } finally { payload.fill(0); shared.fill(0); key.fill(0) }
        val nativePublic = pair.public as ECPublicKey
        fun coordinate(value: BigInteger) = value.toByteArray().let { bytes -> ByteArray(32).also { bytes.copyInto(it, 32 - minOf(32, bytes.size), maxOf(0, bytes.size - 32)) } }
        val exported = byteArrayOf(4) + coordinate(nativePublic.w.affineX) + coordinate(nativePublic.w.affineY)
        val envelope = JsonObject().apply {
            addProperty("version", 1); addProperty("requestID", request.requestID)
            addProperty("publicKey", Base64.getEncoder().encodeToString(exported))
            addProperty("nonce", Base64.getEncoder().encodeToString(nonce))
            addProperty("ciphertext", Base64.getEncoder().encodeToString(encrypted))
        }
        write(directory, "response.json", envelope.toString()) {
            require(parseRequest(read(File(directory, "request.json"))) == request)
            require(originURL(read(File(path, ".git/config"))) == request.remoteURL)
            require(request.expiresAt > now())
            beforePublish()
        }
    }

    private fun checkedDirectory(path: String, create: Boolean): File {
        val root = File(path); require(root.isAbsolute && root.canonicalPath == root.path)
        for (file in listOf(root, File(root, ".git"))) {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(attributes.isDirectory && !attributes.isSymbolicLink)
        }
        val directory = File(root, DIRECTORY)
        if (create && !Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory.toPath())
        val attrs = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isDirectory && !attrs.isSymbolicLink)
        return directory
    }
    private fun write(directory: File, name: String, value: String, authorize: () -> Unit) {
        require(value.toByteArray(Charsets.UTF_8).size <= 65536)
        val destination = File(directory, name)
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) read(destination)
        val temp = Files.createTempFile(directory.toPath(), ".gitsync-", ".tmp").toFile()
        try {
            FileOutputStream(temp).use { it.write(value.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            checkedDirectory(directory.parentFile.parentFile.path, false)
            authorize()
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp.toPath()) }
    }
    companion object {
        const val DIRECTORY = ".git/gitsync-obsidian"
        const val CONTEXT = "gitsync-obsidian-v1"
        fun isGitHubHTTPS(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.matches(Regex("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) &&
                uri.rawPath.split('/').none { it == "." || it == ".." }
        }.getOrDefault(false)
        fun originURL(config: String): String? {
            var section = ""; val urls = mutableListOf<String>()
            for (raw in config.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) continue
                if (line.startsWith('[')) {
                    section = line.lowercase(java.util.Locale.ROOT)
                    if (section.startsWith("[include") || section.startsWith("[url ")) return null
                } else if (section == "[remote \"origin\"]") {
                    if ('=' !in line) return null
                    val key = line.substringBefore('=').trim().lowercase(java.util.Locale.ROOT)
                    if (key == "pushurl") return null
                    if (key == "url") urls += line.substringAfter('=').trim()
                }
            }
            return urls.singleOrNull()
        }
        fun parseRequest(value: String): ObsidianRequest {
            val json = json(value)
            require(json.keySet() == setOf("version", "requestID", "repoID", "remoteURL", "expiresAt") && integer(json, "version") == 1)
            val expires = json.get("expiresAt").also { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) }.asDouble
            require(expires.isFinite())
            return ObsidianRequest(uuid(string(json, "requestID")), uuid(string(json, "repoID")), string(json, "remoteURL"), expires)
                .also { require(isGitHubHTTPS(it.remoteURL)) }
        }
        fun read(file: File): String {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(attrs.isRegularFile && !attrs.isSymbolicLink && attrs.size() <= 65536)
            val bytes = ByteArrayOutputStream()
            Files.newByteChannel(file.toPath(), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                val buffer = ByteBuffer.allocate(4096)
                while (channel.read(buffer) != -1) {
                    buffer.flip(); require(bytes.size() + buffer.remaining() <= 65536)
                    while (buffer.hasRemaining()) bytes.write(buffer.get().toInt()); buffer.clear()
                }
            }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        }
        private fun json(value: String): JsonObject { require(value.length <= 65536); return JsonParser.parseString(value).also { require(it.isJsonObject) }.asJsonObject }
        private fun string(json: JsonObject, key: String): String = json.get(key).also { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }.asString
        private fun integer(json: JsonObject, key: String) = json.get(key).also { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == "1") }.asInt
        fun uuid(value: String) = value.also { require(it.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))) }
        private fun hkdf(secret: ByteArray, salt: ByteArray): ByteArray {
            val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(secret)
            return try { Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(extract, "HmacSHA256")) }
                .doFinal(CONTEXT.toByteArray(Charsets.UTF_8) + byteArrayOf(1)) } finally { extract.fill(0) }
        }
    }
}

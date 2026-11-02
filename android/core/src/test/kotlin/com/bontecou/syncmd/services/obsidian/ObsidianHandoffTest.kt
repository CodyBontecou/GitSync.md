package com.bontecou.syncmd.services.obsidian

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.HKDFParameters
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.util.Base64
import java.util.UUID
import javax.crypto.*
import javax.crypto.spec.*

class ObsidianHandoffTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var service: ObsidianHandoff
    private val url = "https://github.com/fixture/vault.git"
    private var now = 1793200000.0
    @Before fun setup() {
        root = temp.root.canonicalFile
        File(root, ".git").mkdir()
        File(root, ".git/config").writeText("[remote \"origin\"]\n\turl = $url\n")
        service = ObsidianHandoff { now }
    }
    private fun request() = service.prepare(root.path, UUID.randomUUID().toString(), url)
    private fun artifact(name: String) = File(root, "${ObsidianHandoff.DIRECTORY}/$name.json")
    private fun recipient(request: ObsidianRequest): KeyPair {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val key = pair.public as ECPublicKey
        fun bytes(value: BigInteger) = value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        artifact("recipient").writeText(JsonObject().apply {
            addProperty("version", 1); addProperty("requestID", request.requestID)
            addProperty("publicKey", Base64.getEncoder().encodeToString(byteArrayOf(4) + bytes(key.w.affineX) + bytes(key.w.affineY)))
        }.toString())
        return pair
    }
    @Test fun preparesOnlyPublicMetadataWithoutChangingConfigAndReplacesOldPendingRequest() {
        val config = File(root, ".git/config").readBytes(); val first = request()
        assertEquals(first, ObsidianHandoff.parseRequest(artifact("request").readText()))
        assertEquals(artifact("request").readText(), artifact("connection").readText())
        assertEquals(now + 7 * 86400, first.expiresAt, 0.0)
        artifact("recipient").writeText("old"); artifact("response").writeText("old")
        val second = service.prepare(root.path, first.repoID, url)
        assertNotEquals(first.requestID, second.requestID)
        assertFalse(artifact("recipient").exists()); assertFalse(artifact("response").exists())
        assertArrayEquals(config, File(root, ".git/config").readBytes())
    }
    @Test fun copiedConnectionVerificationIsReadOnlyAndRetainsTheOriginalRequest() {
        val request = request()
        val moved = File(root, "moved-vault").apply { mkdir() }
        assertTrue(File(root, ".git").copyRecursively(File(moved, ".git")))
        val before = moved.walkTopDown().filter { it.isFile }.associate { it.relativeTo(moved).path to it.readBytes().toList() }
        service.verifyConnection(moved.canonicalPath, request)
        assertEquals(before, moved.walkTopDown().filter { it.isFile }.associate { it.relativeTo(moved).path to it.readBytes().toList() })
        assertFalse(File(moved, "${ObsidianHandoff.DIRECTORY}/response.json").exists())
    }
    @Test fun completedConnectionCanBeVerifiedAfterCompanionCleanupWithoutReauthorizingAPendingRequest() {
        val request = request()
        assertTrue(artifact("request").delete())
        assertTrue(runCatching { service.verifyConnection(root.path, request) }.isFailure)
        now = request.expiresAt + 1
        service.verifyConnection(root.path, request, pending = false)
        assertFalse(artifact("request").exists()); assertFalse(artifact("response").exists())
        artifact("request").writeText(request.copy(requestID = UUID.randomUUID().toString()).json().toString())
        assertTrue(runCatching { service.verifyConnection(root.path, request, pending = false) }.isFailure)
    }
    @Test fun connectionVerificationRejectsChangedRequestConnectionRemoteAndSymlinks() {
        val request = request()
        val original = artifact("connection").readText()
        artifact("connection").writeText(request.copy(requestID = UUID.randomUUID().toString()).json().toString())
        assertTrue(runCatching { service.verifyConnection(root.path, request) }.isFailure)
        artifact("connection").writeText(original)
        artifact("request").writeText(request.copy(repoID = UUID.randomUUID().toString()).json().toString())
        assertTrue(runCatching { service.verifyConnection(root.path, request) }.isFailure)
        artifact("request").writeText(original)
        val config = File(root, ".git/config"); val before = config.readText()
        config.writeText(before.replace("github.com/fixture/vault", "github.com/other/vault"))
        assertTrue(runCatching { service.verifyConnection(root.path, request) }.isFailure)
        config.writeText(before)
        val outside = File(root, "outside.json").apply { writeText(original) }
        artifact("connection").delete()
        java.nio.file.Files.createSymbolicLink(artifact("connection").toPath(), outside.toPath())
        assertTrue(runCatching { service.verifyConnection(root.path, request) }.isFailure)
        assertEquals(original, outside.readText())
        assertFalse(artifact("response").exists())
    }
    @Test fun interruptedPreparationCanResumeAtEveryAuthorizationBoundary() {
        val first = request()
        val original = first.json().toString()
        fun reset() {
            File(root, ObsidianHandoff.DIRECTORY).listFiles().orEmpty().forEach { it.delete() }
            artifact("request").writeText(original); artifact("connection").writeText(original)
            artifact("recipient").writeText("old recipient"); artifact("response").writeText("old ciphertext")
        }
        reset()
        val reference = service.planPreparation(root.path, first.repoID, url)
        var boundaries = 0
        service.finishPreparation(root.path, reference) { boundaries++ }
        assertTrue(boundaries >= 5)
        for (cut in 1..boundaries) {
            reset()
            val plan = service.planPreparation(root.path, first.repoID, url)
            var current = 0
            assertTrue("boundary $cut", runCatching { service.finishPreparation(root.path, plan) {
                current++; if (current == cut) error("Fixture interruption")
            } }.isFailure)
            service.finishPreparation(root.path, plan)
            assertEquals(plan.request, ObsidianHandoff.parseRequest(artifact("request").readText()))
            assertEquals(plan.request, ObsidianHandoff.parseRequest(artifact("connection").readText()))
            assertFalse(artifact("recipient").exists()); assertFalse(artifact("response").exists())
        }
    }
    @Test fun recipientWrittenAfterRequestPublicationSurvivesRecoveryAndRepeatedCompletion() {
        val first = request(); val plan = service.planPreparation(root.path, first.repoID, url)
        var interrupted = false
        assertTrue(runCatching { service.finishPreparation(root.path, plan) {
            if (!interrupted && runCatching { ObsidianHandoff.parseRequest(artifact("request").readText()) }.getOrNull() == plan.request) {
                interrupted = true; recipient(plan.request); error("Stop before descriptor publication")
            }
        } }.isFailure)
        val recipient = artifact("recipient").readBytes()
        service.finishPreparation(root.path, plan)
        service.finishPreparation(root.path, plan)
        assertArrayEquals(recipient, artifact("recipient").readBytes())
        service.respond(root.path, plan.request, "user", "synthetic-token", "Fixture" to "fixture@example.invalid") {}
        assertTrue(artifact("response").isFile)
    }
    @Test fun foreignPreparationEditsAndSymlinksAreKeptForExplicitReview() {
        val first = request(); val plan = service.planPreparation(root.path, first.repoID, url)
        val foreign = first.copy(requestID = UUID.randomUUID().toString()).json().toString()
        artifact("request").writeText(foreign)
        val before = File(root, ObsidianHandoff.DIRECTORY).listFiles()!!.associate { it.name to it.readBytes().toList() }
        assertTrue(runCatching { service.finishPreparation(root.path, plan) }.isFailure)
        assertEquals(before, File(root, ObsidianHandoff.DIRECTORY).listFiles()!!.associate { it.name to it.readBytes().toList() })
        artifact("request").writeText(first.json().toString())
        val outside = File(root, "outside-preparation.json").apply { writeText("Keep outside contents") }
        Files.createSymbolicLink(artifact("recipient").toPath(), outside.toPath())
        assertTrue(runCatching { service.finishPreparation(root.path, plan) }.isFailure)
        assertEquals("Keep outside contents", outside.readText())
        assertEquals(first, ObsidianHandoff.parseRequest(artifact("request").readText()))
    }
    @Test fun expiredPreparationCannotBePublishedOrSilentlyRenewed() {
        val first = request(); val plan = service.planPreparation(root.path, first.repoID, url)
        now = plan.request.expiresAt + 1
        assertTrue(runCatching { service.finishPreparation(root.path, plan) }.isFailure)
        assertEquals(first, ObsidianHandoff.parseRequest(artifact("request").readText()))
    }
    @Test fun envelopeDecryptsWithIndependentHkdfAndAuthenticatesTheRequest() {
        val request = request(); val pair = recipient(request); var authorized = false
        service.respond(root.path, request, "x-access-token", "synthetic-obsidian-token", "東京 Fixture" to "fixture@example.invalid") { authorized = true }
        assertTrue(authorized)
        val envelope = JsonParser.parseString(artifact("response").readText()).asJsonObject
        assertEquals(setOf("version", "requestID", "publicKey", "nonce", "ciphertext"), envelope.keySet())
        assertFalse(artifact("response").readText().contains("synthetic-obsidian-token"))
        val raw = Base64.getDecoder().decode(envelope["publicKey"].asString)
        assertEquals(65, raw.size); assertEquals(4.toByte(), raw[0])
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65))), params))
        val shared = KeyAgreement.getInstance("ECDH").apply { init(pair.private); doPhase(publicKey, true) }.generateSecret()
        val key = ByteArray(32)
        HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(shared, request.requestID.toByteArray(), ObsidianHandoff.CONTEXT.toByteArray())); generateBytes(key, 0, key.size) }
        fun decrypt(aad: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, Base64.getDecoder().decode(envelope["nonce"].asString)))
            updateAAD(aad.toByteArray()); doFinal(Base64.getDecoder().decode(envelope["ciphertext"].asString))
        }
        val credential = JsonParser.parseString(String(decrypt(request.requestID), Charsets.UTF_8)).asJsonObject
        assertEquals("synthetic-obsidian-token", credential["password"].asString)
        assertEquals("東京 Fixture", credential["authorName"].asString)
        assertEquals(now + 300, credential["expiresAt"].asDouble, 0.0)
        assertTrue(runCatching { decrypt(UUID.randomUUID().toString()) }.isFailure)
        System.getenv("SYNCMD_OBSIDIAN_INTEROP")?.let { directory ->
            val folder = File(directory); require(folder.isDirectory && File(folder, "owned-fixture").isFile)
            File(folder, "android-handoff.json").writeText(JsonObject().apply {
                add("request", request.json()); add("envelope", envelope)
                addProperty("privateKeyPKCS8", Base64.getEncoder().encodeToString(pair.private.encoded)); addProperty("now", now * 1000)
            }.toString())
        }
    }
    @Test fun expiryMismatchedRequestAndHostAuthorizationFailureCannotPublishCiphertext() {
        val request = request(); recipient(request)
        now = request.expiresAt + 1
        assertTrue(runCatching { service.respond(root.path, request, "user", "fixture", "Author" to "a@example.invalid") {} }.isFailure)
        now = request.expiresAt - 100
        assertTrue(runCatching { service.respond(root.path, request.copy(requestID = UUID.randomUUID().toString()), "user", "fixture", "Author" to "a@example.invalid") {} }.isFailure)
        assertTrue(runCatching { service.respond(root.path, request, "user", "fixture", "Author" to "a@example.invalid") { error("Private authorization changed") } }.isFailure)
        assertFalse(artifact("response").exists())
        assertTrue(artifact("request").exists()); assertTrue(artifact("recipient").exists())
        assertFalse(artifact("request").parentFile.listFiles()!!.any { it.name.startsWith(".gitsync-") })
    }
    @Test fun changedRemoteIncludesDuplicateUrlsAndPushUrlsAreRejected() {
        val request = request(); recipient(request)
        for (config in listOf("[remote \"origin\"]\nurl = https://github.com/other/vault.git\n", "[include]\npath = other\n[remote \"origin\"]\nurl = $url\n",
            "[remote \"origin\"]\nurl = $url\nurl = $url\n", "[remote \"origin\"]\nurl = $url\npushurl = $url\n")) {
            File(root, ".git/config").writeText(config)
            assertTrue(runCatching { service.respond(root.path, request, "user", "fixture", "Author" to "a@example.invalid") {} }.isFailure)
            assertFalse(artifact("response").exists())
        }
    }
    @Test fun metadataLinksOversizedFilesAndInvalidRecipientKeysAreRejected() {
        val request = request(); recipient(request)
        val outside = File(root, "outside").apply { writeText("Keep outside bytes") }
        artifact("recipient").delete(); Files.createSymbolicLink(artifact("recipient").toPath(), outside.toPath())
        assertTrue(runCatching { service.respond(root.path, request, "user", "fixture", "Author" to "a@example.invalid") {} }.isFailure)
        assertEquals("Keep outside bytes", outside.readText()); artifact("recipient").delete()
        artifact("recipient").writeText("x".repeat(65537))
        assertTrue(runCatching { service.respond(root.path, request, "user", "fixture", "Author" to "a@example.invalid") {} }.isFailure)
        artifact("recipient").writeText("{\"version\":1,\"requestID\":\"${request.requestID}\",\"publicKey\":\"AAAA\"}")
        assertTrue(runCatching { service.respond(root.path, request, "user", "fixture", "Author" to "a@example.invalid") {} }.isFailure)
        assertFalse(artifact("response").exists())
    }
    @Test fun onlyOrdinaryGitHubHttpsRemotesAndStrictRequestsAreAccepted() {
        assertTrue(ObsidianHandoff.isGitHubHTTPS(url))
        listOf("http://github.com/a/b", "https://github.com.evil/a/b", "https://github.com:443/a/b", "https://user:secret@github.com/a/b", "https://github.com/a/b?token=x", "https://github.com/a/../b", "ssh://git@github.com/a/b").forEach {
            assertFalse(it, ObsidianHandoff.isGitHubHTTPS(it))
        }
        val request = request()
        assertTrue(runCatching { ObsidianHandoff.parseRequest(request.json().apply { addProperty("version", "1") }.toString()) }.isFailure)
        assertTrue(runCatching { ObsidianHandoff.parseRequest(request.json().apply { addProperty("unexpected", true) }.toString()) }.isFailure)
    }
}

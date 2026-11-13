package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A key pair made on this phone: an unencrypted OpenSSH private key and its `ssh-ed25519 … comment` line. */
data class GeneratedSshKey(val privateKey: String, val publicKey: String) {
    val credentials: Credentials.Ssh get() = Credentials.Ssh(privateKey, "", publicKey)
    /** OpenSSH's `SHA256:…` fingerprint of the public key. */
    val fingerprint: String get() = SshKeyGenerator.fingerprint(publicKey)
}

/** Generates Ed25519 keys in memory with BouncyCastle; nothing touches ~/.ssh or an agent. */
object SshKeyGenerator {
    fun ed25519(comment: String, random: SecureRandom = SecureRandom()): GeneratedSshKey {
        val generator = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(random)) }
        val pair = generator.generateKeyPair()
        val privateBlob = OpenSSHPrivateKeyUtil.encodePrivateKey(pair.private)
        val publicBlob = OpenSSHPublicKeyUtil.encodePublicKey(pair.public)
        val body = Base64.getEncoder().encodeToString(privateBlob).chunked(70).joinToString("\n")
        val private = "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"
        val cleanComment = comment.replace(Regex("[\\s]+"), "-").take(64)
        val public = "ssh-ed25519 " + Base64.getEncoder().encodeToString(publicBlob) + if (cleanComment.isNotEmpty()) " $cleanComment" else ""
        privateBlob.fill(0)
        return GeneratedSshKey(private, public)
    }

    /** `SHA256:<base64 without padding>` of an OpenSSH public key line. */
    fun fingerprint(publicKey: String): String {
        val parts = publicKey.trim().split(Regex("\\s+"))
        require(parts.size >= 2) { "Not an OpenSSH public key" }
        val digest = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(parts[1]))
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** The design's short form, `SHA256:q8Zt…f2Lk`. */
    fun shortFingerprint(fingerprint: String): String {
        val hash = fingerprint.removePrefix("SHA256:")
        return if (hash.length <= 10) fingerprint else "SHA256:${hash.take(4)}…${hash.takeLast(4)}"
    }

    /** The design's short public key, `ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIK7w…3mPq cody@pixel`. */
    fun shortPublicKey(publicKey: String): String {
        val parts = publicKey.trim().split(Regex("\\s+"), limit = 3)
        if (parts.size < 2 || parts[1].length <= 40) return publicKey.trim()
        return listOfNotNull(parts[0], parts[1].take(36) + "…" + parts[1].takeLast(4), parts.getOrNull(2)).joinToString(" ")
    }
}

package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import com.jcraft.jsch.*
import org.eclipse.jgit.transport.*
import org.eclipse.jgit.util.FS
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

data class SshHostKey(val host: String, val port: Int, val algorithm: String, val encoded: String) {
    val fingerprint: String get() = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(encoded)))
    companion object {
        fun presented(host: String, port: Int, bytes: ByteArray): SshHostKey {
            require(bytes.size in 8..131072) { "Invalid SSH host key" }
            val length = ByteBuffer.wrap(bytes, 0, 4).int
            require(length in 1..128 && length + 4 <= bytes.size) { "Invalid SSH host key" }
            val algorithm = String(bytes, 4, length, Charsets.US_ASCII)
            require(algorithm.matches(Regex("[A-Za-z0-9@._+-]+"))) { "Invalid SSH host key algorithm" }
            return SshHostKey(host, port, algorithm, Base64.getEncoder().encodeToString(bytes))
        }
    }
}
fun interface SshHostVerifier { fun verify(key: SshHostKey) }
class SshTrustRequired(val key: SshHostKey) : IOException("Verify the SSH server host key before connecting")
object SshTrust {
    @Volatile private var current = SshHostVerifier { throw SshTrustRequired(it) }
    fun install(verifier: SshHostVerifier) { current = verifier }
    fun verifier(): SshHostVerifier = current
}

/** Keys are parsed in memory; neither validation nor sessions read ~/.ssh or contact an agent. */
object SshKeys {
    fun validate(credentials: Credentials.Ssh) {
        require(credentials.privateKey.length in 1..262144 && credentials.passphrase.length <= 16384 && credentials.publicKey.length <= 65536) { "The SSH key or passphrase is too large" }
        val secret = credentials.privateKey.toByteArray(Charsets.UTF_8)
        val passphrase = credentials.passphrase.toByteArray(Charsets.UTF_8)
        var key: KeyPair? = null
        try {
            key = KeyPair.load(JSch(), secret, null)
            check(!key.isEncrypted || key.decrypt(passphrase)) { "The SSH key could not be decrypted. Check its passphrase." }
            require(key.keyType in listOf(KeyPair.RSA, KeyPair.ECDSA, KeyPair.ED25519, KeyPair.ED448) && (key.keyType != KeyPair.RSA || key.keySize >= 2048)) {
                "Use an RSA key of at least 2048 bits, ECDSA, or Ed25519/Ed448"
            }
            if (credentials.publicKey.isNotBlank()) {
                val parts = credentials.publicKey.trim().split(Regex("\\s+"))
                require(parts.size >= 2 && parts[0] == key.keyTypeString && Base64.getDecoder().decode(parts[1]).contentEquals(key.publicKeyBlob)) { "The public key does not match the private key" }
            }
        } catch (e: IllegalArgumentException) { throw e
        } catch (e: IllegalStateException) { throw e
        } catch (e: Exception) { throw IllegalArgumentException("Could not open the SSH private key. Check its format and passphrase.")
        } finally { secret.fill(0); passphrase.fill(0); key?.dispose() }
    }
}

/** Per-transport factory. Private keys never become the process-wide JGit/JSch default. */
internal class RepositorySshSessionFactory(private val endpoint: SshRemote, private val credentials: Credentials.Ssh,
    private val verifier: SshHostVerifier, private val lfsOperation: SshLfsOperation? = null) : SshSessionFactory() {
    override fun getType() = "GitSyncSSH"
    override fun getSession(uri: URIish, provider: CredentialsProvider?, fs: FS?, timeout: Int): RemoteSession {
        require(SshRemote.parse(uri.toPrivateString()) == endpoint) { "SSH authentication was requested for a different repository address" }
        SshKeys.validate(credentials)
        val jsch = JSch()
        val privateKey = credentials.privateKey.toByteArray(Charsets.UTF_8)
        val publicKey = credentials.publicKey.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
        val passphrase = credentials.passphrase.toByteArray(Charsets.UTF_8)
        var session: Session? = null
        try {
            jsch.addIdentity("GitSync repository key", privateKey, publicKey, passphrase)
            jsch.hostKeyRepository = object : HostKeyRepository {
                override fun check(host: String, key: ByteArray): Int {
                    verifier.verify(SshHostKey.presented(endpoint.host, endpoint.port, key))
                    return HostKeyRepository.OK
                }
                override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
                override fun remove(host: String?, type: String?) = Unit
                override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
                override fun getKnownHostsRepositoryID() = "GitSync verified host keys"
                override fun getHostKey(): Array<HostKey> = emptyArray()
                override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
            }
            session = jsch.getSession(endpoint.user, endpoint.host, endpoint.port)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey")
            session.setConfig("IdentitiesOnly", "yes")
            session.timeout = 60000
            session.serverAliveInterval = 15000
            session.serverAliveCountMax = 2
            session.connect(if (timeout > 0) timeout else 60000)
            return JschRepositorySession(session, jsch, endpoint, uri, lfsOperation)
        } catch (e: Exception) {
            session?.disconnect(); jsch.removeAllIdentity()
            throw org.eclipse.jgit.errors.TransportException(uri, "SSH connection failed. Verify the server key, private key, passphrase and repository access.", e)
        } finally { privateKey.fill(0); publicKey?.fill(0); passphrase.fill(0) }
    }
}

enum class SshLfsOperation(val wire: String) { DOWNLOAD("download"), UPLOAD("upload") }
internal fun scopedSshLfsCommand(endpoint: SshRemote, operation: SshLfsOperation): String =
    "git-lfs-authenticate ${org.eclipse.jgit.util.QuotedString.BOURNE.quote(endpoint.path.trimStart('/'))} ${operation.wire}"

/** Bind JGit's command to the reviewed address, including its older SCP/IPv6 parser. */
internal fun scopedSshCommand(endpoint: SshRemote, uri: URIish, command: String): String {
    require(SshRemote.parse(uri.toPrivateString()) == endpoint) { "SSH command address changed" }
    val transportPath = uri.path.let { if (uri.scheme != null && it.startsWith("/~")) it.substring(1) else it }
    val endpointPath = endpoint.path.let { if (endpoint.url.startsWith("ssh://") && it.startsWith("/~")) it.substring(1) else it }
    val operation = listOf("git-upload-pack", "git-receive-pack").singleOrNull {
        command == "$it ${org.eclipse.jgit.util.QuotedString.BOURNE.quote(transportPath)}"
    } ?: error("SSH requires the standard Git pack commands for the configured repository path")
    return "$operation ${org.eclipse.jgit.util.QuotedString.BOURNE.quote(endpointPath)}"
}

private class JschRepositorySession(private val session: Session, private val jsch: JSch, private val endpoint: SshRemote, private val uri: URIish, private val lfsOperation: SshLfsOperation?) : RemoteSession {
    override fun disconnect() { session.disconnect(); jsch.removeAllIdentity() }
    override fun exec(command: String, timeout: Int): Process {
        val scopedCommand = if (lfsOperation == null) scopedSshCommand(endpoint, uri, command) else {
            val allowed = scopedSshLfsCommand(endpoint, lfsOperation)
            require(command == allowed) { "SSH LFS authentication command does not match its repository and operation" }
            allowed
        }
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(scopedCommand.toByteArray(Charsets.UTF_8))
        channel.setPty(false); channel.setAgentForwarding(false); channel.setXForwarding(false)
        val output = channel.outputStream
        val input = channel.inputStream
        val errors = channel.errStream
        try { channel.connect(if (timeout > 0) Math.multiplyExact(timeout, 1000) else 60000) }
        catch (e: Exception) { channel.disconnect(); throw IOException("Could not start the Git SSH command", e) }
        return object : Process() {
            override fun getOutputStream() = output
            override fun getInputStream() = input
            override fun getErrorStream() = errors
            override fun destroy() { channel.disconnect() }
            override fun exitValue(): Int { if (!channel.isClosed) throw IllegalThreadStateException("Git SSH command is running"); return channel.exitStatus }
            override fun waitFor(): Int { while (!channel.isClosed) Thread.sleep(10); return channel.exitStatus }
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                val start = System.nanoTime(); val limit = unit.toNanos(timeout)
                while (!channel.isClosed) { if (System.nanoTime() - start >= limit) return false; Thread.sleep(10) }
                return true
            }
        }
    }
}

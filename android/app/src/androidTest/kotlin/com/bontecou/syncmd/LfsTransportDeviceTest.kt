package com.bontecou.syncmd

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.git.lfs.*
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import java.util.UUID
import javax.net.ssl.*

/** Explicit loopback fixture only. Platform trust and SSH verifier are restored after the test. */
class LfsTransportDeviceTest {
    @Test fun encryptedSshAndHttpsCloneHydrateAndUploadBeforePushingOnAndroidMedia() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val ssh = args.getString("sshFixtureUrl") ?: error("Supply isolated SSH fixture")
        val https = args.getString("lfsFixtureUrl") ?: error("Supply isolated HTTPS fixture")
        val endpoint = SshRemote.parse(ssh)
        check(endpoint.host == "127.0.0.1" && java.net.URI(https).host == "127.0.0.1")
        val pin = SshHostKey.presented(endpoint.host, endpoint.port, Base64.getDecoder().decode(args.getString("sshFixtureHostKey")))
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(args.getString("lfsFixtureCertificate"))))
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("isolated-lfs-fixture", certificate) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        val assets = instrumentation.context.assets
        val key = Credentials.Ssh(assets.open("ssh-fixture/identity").bufferedReader().use { it.readText() }, "android-fixture-passphrase",
            assets.open("ssh-fixture/identity.pub").bufferedReader().use { it.readText() })
        val context = instrumentation.targetContext
        val media = context.externalMediaDirs.firstOrNull() ?: error("App-owned Android/media is unavailable")
        val workspace = File(media, "lfs-transport-${UUID.randomUUID()}").apply { mkdirs() }
        val second = File(context.filesDir, "lfs-https-${UUID.randomUUID()}")
        val oldTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        val oldSsh = SshTrust.verifier()
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            SshTrust.install { check(it == pin) }
            val initial = ByteArray(16384) { (it % 251).toByte() }
            val target = File(workspace, "ssh-clone")
            LocalGitRepository(credentialResolver = { _, url -> check(url == ssh); key }).clone(ssh, target.path, key).getOrThrow()
            assertArrayEquals(initial, File(target, "asset.bin").readBytes())
            assertTrue(LocalGitRepository().getStatus(target.path).getOrThrow().isEmpty())
            val updated = initial + byteArrayOf(91, 92, 93)
            File(target, "asset.bin").writeBytes(updated)
            val diff = LocalDiffRepository()
            diff.stageAll(target.path).getOrThrow(); diff.commit(target.path, "Android LFS push", "Fixture", "fixture@example.invalid").getOrThrow()
            GitLfs.open(target).use { git ->
                val entry = git.repository.readDirCache().getEntry("asset.bin")
                assertEquals(LfsObjectStore.hash(File(target, "asset.bin")), LfsPointer.parse(git.repository.open(entry.objectId).bytes))
            }
            LocalRemoteRepository(credentialResolver = { _, url -> check(url == ssh); key }).push(target.path, "main", "origin", "main").getOrThrow()
            val basic = Credentials.Basic("fixture", "fixture-token")
            LocalGitRepository().clone(https, second.path, basic).getOrThrow()
            assertArrayEquals(updated, File(second, "asset.bin").readBytes())
            assertTrue(LocalGitRepository().getStatus(second.path).getOrThrow().isEmpty())
            LocalPullRepository(credentialResolver = { _, _ -> key }).executePull(target.path).getOrThrow()
        } finally {
            SshTrust.install(oldSsh); HttpsURLConnection.setDefaultSSLSocketFactory(oldTls)
            workspace.deleteRecursively(); second.deleteRecursively()
        }
        Unit
    }
}

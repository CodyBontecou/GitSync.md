package com.bontecou.syncmd

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.*
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Run explicitly with a loopback fixture URL and generated server pin supplied by the host. */
class SshTransportDeviceTest {
    @Test fun encryptedEd25519CloneFetchPullPushAndHostRejection() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("sshFixtureUrl") ?: error("Supply the isolated SSH fixture URL")
        val encoded = args.getString("sshFixtureHostKey") ?: error("Supply the isolated server pin")
        val endpoint = SshRemote.parse(url)
        check(endpoint.host == "127.0.0.1")
        val pin = SshHostKey.presented(endpoint.host, endpoint.port, java.util.Base64.getDecoder().decode(encoded))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val credentials = Credentials.Ssh(assets.open("ssh-fixture/identity").bufferedReader().use { it.readText() }, "android-fixture-passphrase",
            assets.open("ssh-fixture/identity.pub").bufferedReader().use { it.readText() })
        val workspace = File(instrumentation.targetContext.filesDir, "ssh-transport-${UUID.randomUUID()}").apply { mkdirs() }
        val old = SshTrust.verifier()
        var verifications = 0
        try {
            SshTrust.install { key -> check(key == pin); verifications++ }
            val local = LocalGitRepository(credentialResolver = { _, address -> check(address == url); credentials })
            val target = File(workspace, "clone")
            local.clone(url, target.path, credentials).getOrThrow()
            assertEquals("Native SSH fixture\n", File(target, "notes.md").readText())
            val pull = LocalPullRepository(credentialResolver = { _, _ -> credentials })
            pull.fetch(target.path).getOrThrow()
            pull.executePull(target.path).getOrThrow()
            Git.open(target).use { git ->
                File(target, "android.md").writeText("Verified Android SSH push\n")
                git.add().addFilepattern(".").call()
                git.commit().setAuthor("Android Fixture", "android@example.invalid").setCommitter("Android Fixture", "android@example.invalid").setMessage("Android SSH fixture").call()
                val auth = RemoteAuthentication.provider(git.repository, "origin", true, resolver = { _, _ -> credentials })
                val updates = git.push().setCredentialsProvider(auth).setTransportConfigCallback(auth).call().flatMap { it.remoteUpdates }
                assertTrue(updates.isNotEmpty())
                assertTrue(updates.all { it.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK })
            }
            assertTrue(verifications >= 4)
            SshTrust.install { throw SshTrustRequired(it) }
            assertTrue(local.clone(url, File(workspace, "denied").path, credentials).isFailure)
        } finally { SshTrust.install(old); workspace.deleteRecursively() }
    }
}

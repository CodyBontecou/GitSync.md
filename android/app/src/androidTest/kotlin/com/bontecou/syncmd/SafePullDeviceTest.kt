package com.bontecou.syncmd

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.LocalPullRepository
import com.bontecou.syncmd.services.git.SampleRepository
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.transport.RefSpec
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Android filesystem/JGit qualification; no catalog, account or global policy is modified. */
class SafePullDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workspace: File
    private lateinit var root: File
    @Before fun setup() {
        workspace = File(context.filesDir, "safe-pull-${UUID.randomUUID()}")
        root = SampleRepository.openOrCreate(workspace)
        Git.open(root).use { it.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~1").call() }
    }
    @After fun cleanup() {
        if (::workspace.isInitialized) {
            check(workspace.canonicalFile.parentFile == context.filesDir.canonicalFile && workspace.name.startsWith("safe-pull-"))
            assertTrue(workspace.deleteRecursively())
        }
    }
    private fun incoming(branch: String = "main", name: String = "incoming.md"): String {
        return Git.cloneRepository().setURI(File(workspace, "sample-remote.git").toURI().toString())
            .setDirectory(File(workspace, "peer")).call().use { peer ->
                File(peer.repository.workTree, name).writeText("Incoming contents\n")
                peer.add().addFilepattern(".").call()
                val commit = peer.commit().setMessage("Incoming").setAuthor("Peer", "peer@example.invalid")
                    .setCommitter("Peer", "peer@example.invalid").call()
                peer.push().setRefSpecs(RefSpec("HEAD:refs/heads/$branch")).call()
                commit.name
            }
    }
    @Test fun configuredUpstreamAndDifferentBranchFastForwardOnAndroid() = runBlocking {
        val target = incoming("release")
        Git.open(root).use { git ->
            git.repository.config.apply {
                setString("remote", "upstream", "url", File(workspace, "sample-remote.git").toURI().toString())
                setString("remote", "upstream", "fetch", "+refs/heads/*:refs/remotes/upstream/*")
                setString("remote", "origin", "url", File(workspace, "missing.git").toURI().toString())
                setString("branch", "main", "remote", "upstream")
                setString("branch", "main", "merge", "refs/heads/release"); save()
            }
            val repository = LocalPullRepository(credentialResolver = { _, _ -> Credentials.None })
            assertEquals("upstream/release", repository.planPull(root.path).getOrThrow().remoteBranch)
            val result = repository.executePull(root.path).getOrThrow()
            assertTrue(result.success); assertEquals(1, result.commitsApplied); assertFalse(result.mergeRequired)
            assertEquals(target, git.repository.resolve("HEAD").name)
            assertEquals("Incoming contents\n", File(root, "incoming.md").readText())
        }
    }
    @Test fun ignoredContentsAndIndexAreKeptThenExplicitRetryFastForwards() = runBlocking {
        val target = incoming(name = "private.cache")
        Git.open(root).use { git ->
            File(root, ".git/info/exclude").apply { parentFile.mkdirs(); appendText("\nprivate.cache\n") }
            val contents = File(root, "private.cache").apply { writeText("Keep my private contents\n") }
            val before = git.repository.resolve("HEAD"); val index = File(root, ".git/index").readBytes()
            val repository = LocalPullRepository(credentialResolver = { _, _ -> Credentials.None })
            assertFalse(repository.planPull(root.path).getOrThrow().canPull)
            assertTrue(repository.executePull(root.path).isFailure)
            assertEquals(before, git.repository.resolve("HEAD")); assertArrayEquals(index, File(root, ".git/index").readBytes())
            assertEquals("Keep my private contents\n", contents.readText())
            assertTrue(contents.delete())
            assertTrue(repository.executePull(root.path).getOrThrow().success)
            assertEquals(target, git.repository.resolve("HEAD").name)
        }
    }
    @Test fun branchSwitchDuringAuthenticationCannotUpdateEitherLocalBranch() = runBlocking {
        incoming()
        Git.open(root).use { git ->
            git.branchCreate().setName("other").call()
            git.repository.config.apply {
                setString("branch", "other", "remote", "origin")
                setString("branch", "other", "merge", "refs/heads/main"); save()
            }
            val before = git.repository.resolve("HEAD")
            val repository = LocalPullRepository(credentialResolver = { _, _ ->
                git.checkout().setName("other").call(); Credentials.None
            })
            assertTrue(repository.executePull(root.path).isFailure)
            assertEquals("other", git.repository.branch)
            assertEquals(before, git.repository.resolve("refs/heads/main"))
            assertEquals(before, git.repository.resolve("refs/heads/other"))
            assertFalse(File(root, "incoming.md").exists())
        }
    }
}

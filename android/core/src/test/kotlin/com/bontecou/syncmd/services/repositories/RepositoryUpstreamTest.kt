package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.services.git.SampleRepository
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryUpstreamTest {
    @get:Rule val temp = TemporaryFolder()
    private val service = RepositoryUpstream()
    private fun root() = SampleRepository.openOrCreate(temp.newFolder()).also { root ->
        Git.open(root).use { git -> git.repository.config.apply {
            setString("remote","backup","url","https://backup.example/notes.git")
            setString("remote","backup","pushurl","https://push.example/notes.git")
            setString("remote","backup","fetch","+refs/heads/*:refs/remotes/backup/*");save()
        } }
    }
    private fun content(root: File) = listOf(".git/HEAD",".git/index","notes.md").map { File(root,it).readBytes().toList() } +
        listOf(Git.open(root).use { it.repository.resolve("HEAD").name.toByteArray().toList() })
    @Test fun savesOnlySelectedLocalBranchTrackingAndPreservesAllEndpointsAndData() = runBlocking {
        val root = root(); val before = content(root)
        val plan = service.inspect(root.path)
        service.save(plan,"backup","release/notes")
        Git.open(root).use { git ->
            val c = git.repository.config
            assertEquals("backup",c.getString("branch",plan.localBranch,"remote"))
            assertEquals("refs/heads/release/notes",c.getString("branch",plan.localBranch,"merge"))
            assertEquals("https://backup.example/notes.git",c.getString("remote","backup","url"))
            assertEquals("https://push.example/notes.git",c.getString("remote","backup","pushurl"))
            assertEquals("+refs/heads/*:refs/remotes/backup/*",c.getString("remote","backup","fetch"))
        }
        assertEquals(before,content(root)); assertFalse(File(root,".git/config.lock").exists())
    }
    @Test fun rejectsStaleConfigWithoutChangingTheExternalEdit() = runBlocking {
        val root = root(); val plan = service.inspect(root.path)
        File(root,".git/config").appendText("\n# preserve external change\n")
        val before = File(root,".git/config").readBytes()
        assertTrue(runCatching { service.save(plan,"backup","main") }.isFailure)
        assertArrayEquals(before,File(root,".git/config").readBytes())
    }
    @Test fun rejectsBranchSwitchEvenWhenConfigDidNotChange() = runBlocking {
        val root = root(); val plan = service.inspect(root.path)
        Git.open(root).use { it.checkout().setCreateBranch(true).setName("other").call() }
        val before = File(root,".git/config").readBytes()
        assertTrue(runCatching { service.save(plan,"backup","main") }.isFailure)
        assertArrayEquals(before,File(root,".git/config").readBytes())
    }
    @Test fun rejectsUnknownRemoteAndInvalidBranchNames() = runBlocking {
        val root = root(); val plan = service.inspect(root.path); val before = File(root,".git/config").readBytes()
        for ((remote,branch) in listOf("unknown" to "main","." to "main","backup" to "bad..name","backup" to "bad branch","backup" to "refs/tags/release","backup" to "")) {
            assertTrue(runCatching { service.save(plan,remote,branch) }.isFailure)
            assertArrayEquals(before,File(root,".git/config").readBytes())
        }
    }
    @Test fun rejectsUnmappedBranchAndPreservesOtherFetchRules() = runBlocking {
        val root = root()
        Git.open(root).use { it.repository.config.apply { setString("remote","backup","fetch","+refs/heads/main:refs/remotes/backup/main");save() } }
        val plan = service.inspect(root.path); val before = File(root,".git/config").readBytes()
        assertTrue(runCatching { service.save(plan,"backup","release") }.isFailure)
        assertArrayEquals(before,File(root,".git/config").readBytes())
    }
    @Test fun externalConfigLockAndActiveMergeCannotBeOverwritten() = runBlocking {
        val root = root(); val plan = service.inspect(root.path)
        val lock = File(root,".git/config.lock").apply { writeText("external lock") }
        assertTrue(runCatching { service.save(plan,"backup","main") }.isFailure)
        assertEquals("external lock",lock.readText()); lock.delete()
        File(root,".git/MERGE_HEAD").writeText(Git.open(root).use { it.repository.resolve("HEAD").name } + "\n")
        assertTrue(runCatching { service.save(plan,"backup","main") }.isFailure)
    }
    @Test fun detachedHeadIsReadableButCannotHaveAnUpstreamAssigned() = runBlocking {
        val root = root()
        Git.open(root).use { it.checkout().setName(it.repository.resolve("HEAD").name).call() }
        val plan = service.inspect(root.path); assertFalse(plan.editable); assertNull(plan.branchRef)
        assertTrue(runCatching { service.save(plan,"backup","main") }.isFailure)
    }
    @Test fun pullUsesTheChosenRemoteBranchWithoutSwitchingTheLocalBranch() = runBlocking {
        val root = root(); val remote = File(root.parentFile,"sample-remote.git")
        Git.open(root).use { git ->
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call()
            git.repository.config.apply { setString("remote","backup","url",remote.toURI().toString());save() }
            git.checkout().setCreateBranch(true).setName("publish-seed").call()
            File(root,"README.md").appendText("\nChosen sync branch content\n")
            git.add().addFilepattern("README.md").call(); git.commit().setMessage("Sync target").call()
            git.push().setRemote(remote.toURI().toString()).setRefSpecs(org.eclipse.jgit.transport.RefSpec("HEAD:refs/heads/sync-target")).call()
            git.checkout().setName("main").call()
        }
        service.save(service.inspect(root.path),"backup","sync-target")
        val pulls = com.bontecou.syncmd.services.git.LocalPullRepository()
        val plan = pulls.planPull(root.path).getOrThrow()
        assertEquals("backup/sync-target",plan.remoteBranch); assertTrue(plan.canPull)
        assertTrue(pulls.executePull(root.path).getOrThrow().success)
        assertTrue(File(root,"README.md").readText().contains("Chosen sync branch content"))
        Git.open(root).use { assertEquals("main",it.repository.branch) }
    }

}

package com.bontecou.syncmd.ui.viewmodels

import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.LocalPullRepository
import com.bontecou.syncmd.services.git.PullService
import com.bontecou.syncmd.ui.util.UiText
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

/** Issues #67 (fetch reports what it found) and #66 (stale lock recovery), on real Git. */
@OptIn(ExperimentalCoroutinesApi::class)
class FetchFeedbackAndLockRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private lateinit var seed: File

    private fun cloneWithRemote(): File {
        val remote = temporary.newFolder("remote.git").canonicalFile
        Git.init().setBare(true).setDirectory(remote).setInitialBranch("main").call().close()
        seed = temporary.newFolder("seed").canonicalFile
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(seed).call().use { git ->
            File(seed, "notes.md").writeText("base\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Base").setAuthor("Seed", "seed@example.invalid").call()
            git.push().setRemote("origin").setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
        }
        val local = temporary.newFolder("local").canonicalFile
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(local).setBranch("main").call().close()
        return local
    }

    private fun pushRemoteCommits(count: Int) = Git.open(seed).use { git ->
        repeat(count) { index ->
            File(seed, "remote-$index.md").writeText("remote $index\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Remote $index").setAuthor("Seed", "seed@example.invalid").call()
        }
        git.push().setRemote("origin").setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
    }

    private fun awaitIdle(model: PullViewModel) = runBlocking {
        withTimeout(20_000) { while (model.isLoading.value || model.removingLocks.value) delay(20) }
    }

    @Test fun fetchReportsUpToDateThenNewCommits() {
        val local = cloneWithRemote()
        val model = PullViewModel(PullService(LocalPullRepository()))
        model.setRepositoryPath(local.path); awaitIdle(model)

        model.fetch(model.selection.value!!); awaitIdle(model)
        assertEquals(FetchOutcome(ahead = 0, behind = 0), model.fetchResult.value)

        pushRemoteCommits(2)
        model.fetch(model.selection.value!!); awaitIdle(model)
        assertEquals(FetchOutcome(ahead = 0, behind = 2), model.fetchResult.value)
        assertNull(model.errorMessage.value)

        model.clearFetchResult(model.selection.value)
        assertNull(model.fetchResult.value)
    }

    @Test fun staleIndexLockIsSurfacedAndRemovedOnConfirmation() {
        val local = cloneWithRemote()
        val lock = File(local, ".git/index.lock").apply { writeText("interrupted\n"); setLastModified(System.currentTimeMillis() - 60_000) }
        val model = PullViewModel(PullService(LocalPullRepository()))
        model.setRepositoryPath(local.path); awaitIdle(model)
        runBlocking { withTimeout(5_000) { while (model.staleLocks.value.isEmpty()) delay(20) } }
        assertEquals(listOf("index.lock"), model.staleLocks.value.map { it.name })

        model.removeStaleLocks(model.selection.value!!); awaitIdle(model)
        runBlocking { withTimeout(5_000) { while (model.staleLocks.value.isNotEmpty()) delay(20) } }
        assertFalse(lock.exists())
        assertNull(model.errorMessage.value)
    }

    @Test fun freshLockIsKeptAndReportedAsInUse() {
        val local = cloneWithRemote()
        val lock = File(local, ".git/index.lock").apply { writeText("active\n") }
        val model = PullViewModel(PullService(LocalPullRepository()))
        model.setRepositoryPath(local.path); awaitIdle(model)
        runBlocking { withTimeout(5_000) { while (model.staleLocks.value.isEmpty()) delay(20) } }

        model.removeStaleLocks(model.selection.value!!); awaitIdle(model)
        assertTrue(lock.exists())
        assertEquals(UiText.res(R.string.gitops_lock_in_use), model.errorText.value)
    }

    @Test fun jgitLockFailuresAreRecognised() {
        assertTrue(org.eclipse.jgit.errors.LockFailedException(File("/r/.git/index")).isGitLockFailure())
        assertTrue(RuntimeException("wrapped", org.eclipse.jgit.errors.LockFailedException(File("/r/.git/index"))).isGitLockFailure())
        assertFalse(IllegalStateException("Authentication failed").isGitLockFailure())
    }
}

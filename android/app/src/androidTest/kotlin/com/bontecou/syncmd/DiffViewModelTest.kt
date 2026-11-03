package com.bontecou.syncmd

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.domain.repository.RemoteRepository
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Real Git state plus controlled transport failure; never contacts an external remote. */
class DiffViewModelTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var workspace: File
    private lateinit var root: File
    private val models = mutableListOf<DiffViewModel>()
    private val extraWorkspaces = mutableListOf<File>()
    @Before fun setup() {
        workspace = File(context.filesDir, "parity-commit-${UUID.randomUUID()}")
        root = SampleRepository.openOrCreate(workspace)
        Git.open(root).use { it.reset().setMode(ResetCommand.ResetType.HARD).call() }
    }
    @After fun cleanup() {
        main { models.forEach { ViewModelStore().apply { put("diff", it); clear() } } }
        workspace.deleteRecursively()
        extraWorkspaces.forEach { it.deleteRecursively() }
    }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun model(remote: RemoteRepository = LocalRemoteRepository()): DiffViewModel {
        lateinit var vm: DiffViewModel
        main {
            vm = DiffViewModel(DiffService(LocalDiffRepository()),
                PushService(remote, LocalBranchRepository(), LocalHistoryRepository()), BranchService(LocalBranchRepository()),
                com.bontecou.syncmd.security.RepositoryAuthors(GitHubAuthManager(context), com.bontecou.syncmd.security.RepositoryCredentialStore(context)))
            models += vm
            vm.setRepositoryPath(root.path)
        }
        runBlocking { withTimeout(10_000) { vm.isLoading.first { !it } } }
        return vm
    }
    private fun awaitIdle(vm: DiffViewModel) = runBlocking { withTimeout(10_000) { vm.isLoading.first { !it } } }
    private fun stage(text: String) = Git.open(root).use { git ->
        File(root, "notes.md").writeText(text)
        git.add().addFilepattern("notes.md").call()
    }

    @Test fun rapidCommitRequestsCreateOneCommitAndNoPush() {
        stage("Only this version\n")
        val before = Git.open(root).use { it.repository.resolve("HEAD") }
        var pushes = 0
        val remote = object : RemoteRepository by LocalRemoteRepository() {
            override suspend fun push(repoPath: String, branch: String, remote: String, remoteBranch: String, force: Boolean): Result<Unit> {
                pushes++; return Result.failure(IllegalStateException("Local commit must not push"))
            }
        }
        val vm = model(remote)
        main { vm.setCommitMessage("One local commit"); vm.commitOnly(); vm.commitOnly() }
        awaitIdle(vm)
        assertNull(vm.errorMessage.value)
        assertEquals(0, pushes)
        assertEquals("", vm.commitMessage.value)
        Git.open(root).use { git ->
            assertEquals(before, git.log().call().first().getParent(0).id)
            assertEquals(true, git.status().call().isClean)
        }
    }

    private fun review(vm: DiffViewModel, path: String? = null): DiscardReview {
        main { vm.requestDiscard(path) }
        return runBlocking { withTimeout(10_000) { vm.discardReview.first { it != null }!! } }
    }

    @Test fun cancelKeepsEveryByteAndDuplicateDiscardConfirmationExecutesOnlyOnce() {
        File(root, "notes.md").writeText("Discard this draft\n")
        File(root, "new.md").writeText("Discard this new file\n")
        val before = File(root, ".git/index").readBytes()
        val head = Git.open(root).use { it.repository.resolve("HEAD") }
        val vm = model()
        val cancelled = review(vm)
        main { vm.dismissDiscard(cancelled); vm.confirmDiscard(cancelled) }
        assertEquals("Discard this draft\n", File(root, "notes.md").readText())
        assertTrue(File(root, "new.md").exists()); assertArrayEquals(before, File(root, ".git/index").readBytes())
        val approved = review(vm)
        val completed = java.util.concurrent.atomic.AtomicInteger()
        main { vm.confirmDiscard(approved) { completed.incrementAndGet() }; vm.confirmDiscard(approved) { completed.incrementAndGet() } }
        awaitIdle(vm)
        assertNull(vm.errorMessage.value); assertFalse(vm.isDiscarding.value); assertEquals(1, completed.get())
        assertFalse(File(root, "new.md").exists())
        Git.open(root).use { assertTrue(it.status().call().isClean); assertEquals(head, it.repository.resolve("HEAD")) }
    }

    @Test fun staleFileAndForeignIndexLockKeepChangesAndClearTheDiscardBusyState() {
        File(root, "notes.md").writeText("Reviewed\n")
        val vm = model()
        main { vm.selectFile("notes.md") }
        runBlocking { withTimeout(10_000) { vm.selectedFileDiff.first { it != null } } }
        val stale = review(vm, "notes.md")
        val before = File(root, ".git/index").readBytes()
        File(root, "notes.md").writeText("Keep later edits\n")
        var completed = false
        main { vm.confirmDiscard(stale) { completed = true } }; awaitIdle(vm)
        assertNotNull(vm.errorMessage.value); assertFalse(vm.isDiscarding.value); assertFalse(completed)
        assertTrue(vm.selectedFileDiff.value!!.files.flatMap { it.hunks }.flatMap { it.lines }.any { it.content == "Keep later edits" })
        assertEquals("Keep later edits\n", File(root, "notes.md").readText()); assertArrayEquals(before, File(root, ".git/index").readBytes())
        val locked = review(vm)
        val foreign = File(root, ".git/index.lock").apply { writeText("Foreign lock\n") }
        try {
            main { vm.confirmDiscard(locked) { completed = true } }; awaitIdle(vm)
            assertNotNull(vm.errorMessage.value); assertFalse(vm.isDiscarding.value); assertFalse(completed)
            assertEquals("Foreign lock\n", foreign.readText()); assertEquals("Keep later edits\n", File(root, "notes.md").readText())
            assertArrayEquals(before, File(root, ".git/index").readBytes())
        } finally { foreign.delete() }
    }

    @Test fun queuedDiscardRejectsRepositorySwitchAndSameRepositoryReentryBeforeItsLeaseStarts() {
        val otherFolder = File(workspace.parentFile, workspace.name + "-other").also { extraWorkspaces += it }
        val other = SampleRepository.openOrCreate(otherFolder)
        // Keep the other fixture outside this repository's worktree revision.
        for (selected in listOf(root.path, other.path)) {
            File(root, "notes.md").writeText("Keep queued draft\n")
            val vm = model(); val approved = review(vm, "notes.md")
            val before = File(root, ".git/index").readBytes()
            val completed = java.util.concurrent.atomic.AtomicInteger()
            runBlocking {
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val holder = launch(Dispatchers.IO) { RepositoryOperationCoordinator.withRepository(root.path) { entered.complete(Unit); release.await() } }
                entered.await()
                try { main { vm.confirmDiscard(approved) { completed.incrementAndGet() }; vm.setRepositoryPath(selected) } }
                finally { release.complete(Unit); holder.join() }
            }
            awaitIdle(vm)
            assertEquals(0, completed.get()); assertFalse(vm.isDiscarding.value)
            assertEquals("Keep queued draft\n", File(root, "notes.md").readText()); assertArrayEquals(before, File(root, ".git/index").readBytes())
            assertNull(vm.discardReview.value)
        }
    }

    @Test fun observationRefreshDoesNotReplaceTheOwnerOfAnApprovedQueuedDiscard() {
        File(root, "notes.md").writeText("Approved draft\n")
        val vm = model(); val approved = review(vm, "notes.md")
        main { vm.refreshRepositoryPath(root.path) }; awaitIdle(vm)
        assertSame(approved, vm.discardReview.value)
        val completed = java.util.concurrent.atomic.AtomicInteger()
        runBlocking {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val holder = launch(Dispatchers.IO) { RepositoryOperationCoordinator.withRepository(root.path) { entered.complete(Unit); release.await() } }
            entered.await()
            try { main { vm.confirmDiscard(approved) { completed.incrementAndGet() }; vm.refreshRepositoryPath(root.path) } }
            finally { release.complete(Unit); holder.join() }
        }
        awaitIdle(vm)
        assertNull(vm.errorMessage.value); assertFalse(vm.isDiscarding.value); assertEquals(1, completed.get())
        Git.open(root).use { assertTrue(it.status().call().isClean) }
    }

    @Test fun failedPushRetainsTheCommitAndExplicitRetryDoesNotCreateAnother() {
        stage("Keep this committed\n")
        val delegate = LocalRemoteRepository()
        var pushes = 0
        val remote = object : RemoteRepository by delegate {
            override suspend fun push(repoPath: String, branch: String, remote: String, remoteBranch: String, force: Boolean): Result<Unit> {
                pushes++
                return if (pushes == 1) Result.failure(IllegalStateException("Simulated offline remote"))
                    else delegate.push(repoPath, branch, remote, remoteBranch, force)
            }
        }
        val vm = model(remote)
        main { vm.setCommitMessage("Commit before offline push"); vm.commitAndPush("main") }
        awaitIdle(vm)
        assertEquals("Simulated offline remote", vm.errorMessage.value?.resolve(instrumentation.targetContext))
        assertEquals("Committed locally. Push stopped; retry when ready.", vm.operationMessage.value?.resolve(instrumentation.targetContext))
        assertEquals("", vm.commitMessage.value)
        assertEquals(0, vm.diff.value!!.files.count { it.isStaged })
        val committed = Git.open(root).use { it.repository.resolve("HEAD") }
        main { vm.pushCurrentBranch("main"); vm.pushCurrentBranch("main") }
        awaitIdle(vm)
        assertNull(vm.errorMessage.value)
        assertEquals(2, pushes)
        assertEquals(committed, Git.open(root).use { it.repository.resolve("HEAD") })
        assertEquals(committed, Git.open(File(workspace, "sample-remote.git")).use { it.repository.resolve("refs/heads/main") })
    }

    @Test fun changedBranchRejectsCommitAndPushBeforeWritingHistory() {
        stage("Keep staged\n")
        val before = Git.open(root).use { it.repository.resolve("HEAD") }
        val vm = model()
        main { vm.setCommitMessage("Stale branch request"); vm.commitAndPush("sample-branch") }
        awaitIdle(vm)
        assertTrue(vm.errorMessage.value?.resolve(instrumentation.targetContext).orEmpty().contains("branch changed"))
        assertEquals(before, Git.open(root).use { it.repository.resolve("HEAD") })
        assertEquals("Stale branch request", vm.commitMessage.value)
        assertEquals(1, vm.diff.value!!.files.count { it.isStaged })
    }
}

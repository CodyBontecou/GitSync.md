package com.bontecou.syncmd

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.domain.repository.BranchRepository
import com.bontecou.syncmd.data.models.*
import java.io.IOException
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.git.lfs.LfsCheckoutIncompleteException
import com.bontecou.syncmd.ui.viewmodels.BranchViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

class BranchViewModelTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var workspace: File
    private lateinit var root: File
    private val models = mutableListOf<BranchViewModel>()
    private val gates = mutableListOf<Gate>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val paths = mutableSetOf<String>()
    private class Gate {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        suspend fun hold() = withContext(NonCancellable) { entered.complete(Unit); release.await() }
    }
    private fun gate() = Gate().also { gates += it }
    private fun entered(gate: Gate) = runBlocking { withTimeout(10_000) { gate.entered.await() } }
    private fun jobs(vm: BranchViewModel) = main { vm.viewModelScope.coroutineContext[Job]!!.children.toList() }
    private fun await(condition: () -> Boolean) = runBlocking { withTimeout(10_000) { while (!main(condition)) delay(5) } }
    private fun commit(folder: File, file: String = "later.md") = Git.open(folder).use { git ->
        File(folder, file).writeText("Owned later branch contents\n"); git.add().addFilepattern(file).call(); git.commit().setMessage("Owned later commit").call().name
    }
    @Before fun setup() {
        workspace = File(instrumentation.targetContext.filesDir, "branch-vm-${UUID.randomUUID()}")
        root = SampleRepository.openOrCreate(File(workspace, "one")).canonicalFile
        paths += root.path
        Git.open(root).use { it.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call(); it.branchCreate().setName("other").call() }
    }
    @After fun cleanup() {
        gates.forEach { it.release.complete(Unit) }; scopes.forEach { it.cancel() }
        val owned = main { models.map { it.viewModelScope.coroutineContext[Job]!! }.also {
            ViewModelStore().apply { models.forEachIndexed { i, vm -> put("branch-$i", vm) }; clear() }
        } }
        runBlocking { withTimeout(10_000) { owned.joinAll() }; RepositoryOperationCoordinator.withRepositories(paths) { assertTrue(workspace.deleteRecursively()) } }
    }
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null; instrumentation.runOnMainSync { result = runCatching(action) }
        return requireNotNull(result).getOrThrow()
    }
    private fun idle(vm: BranchViewModel) = runBlocking { withTimeout(10_000) { vm.state.first { !it.isLoading } } }
    private fun model(repository: BranchRepository): BranchViewModel {
        lateinit var vm: BranchViewModel
        main { vm = BranchViewModel(BranchService(repository)); models += vm; vm.setRepositoryPath(root.path) }
        idle(vm); return vm
    }
    @Test fun duplicateSwitchIsGatedOffMainThreadAndCompletedCheckoutNoticeSurvivesRefresh() = runBlocking {
        val held = gate(); val entered = held.entered; val release = held.release; val calls = AtomicInteger()
        val actual = LocalBranchRepository()
        val repository = object : BranchRepository by actual {
            override suspend fun switchBranch(repoPath: String, name: String): Result<Unit> {
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                calls.incrementAndGet(); entered.complete(Unit); release.await()
                actual.switchBranch(repoPath, name).getOrThrow()
                return Result.failure(LfsCheckoutIncompleteException("Branch switched. Download missing file contents."))
            }
        }
        val vm = model(repository)
        main { vm.switchBranch(vm.state.value.selection!!, vm.state.value.localBranches.first { it.name == "other" }); vm.switchBranch(vm.state.value.selection!!, vm.state.value.localBranches.first { it.name == "other" }) }
        withTimeout(10_000) { entered.await() }
        assertTrue(vm.state.value.isLoading); assertEquals(1, calls.get())
        release.complete(Unit); idle(vm)
        assertEquals("other", vm.state.value.currentBranch!!.name)
        assertEquals("Branch switched. Download missing file contents.", vm.state.value.errorMessage)
        main { vm.loadBranches() }; idle(vm)
        assertEquals("Branch switched. Download missing file contents.", vm.state.value.errorMessage)
        main { vm.clearError(vm.state.value.selection) }; assertNull(vm.state.value.errorMessage)
    }
    @Test fun lateBranchReadCannotOverwriteAnotherRepository() = runBlocking {
        val second = SampleRepository.openOrCreate(File(workspace, "two")).canonicalFile
        paths += second.path
        Git.open(second).use { it.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call(); it.checkout().setCreateBranch(true).setName("second").call() }
        val held = gate(); val entered = held.entered; val release = held.release; val finished = CompletableDeferred<Unit>()
        var hold = false
        val actual = LocalBranchRepository()
        val repository = object : BranchRepository by actual {
            override suspend fun listBranches(repoPath: String): Result<List<com.bontecou.syncmd.data.models.Branch>> {
                if (hold && repoPath == root.path) {
                    val captured = actual.listBranches(repoPath)
                    withContext(NonCancellable) { entered.complete(Unit); release.await(); finished.complete(Unit) }
                    return captured
                }
                return actual.listBranches(repoPath)
            }
        }
        val vm = model(repository)
        hold = true; main { vm.loadBranches() }
        withTimeout(10_000) { entered.await() }
        main { vm.setRepositoryPath(second.path) }; idle(vm)
        assertEquals("second", vm.state.value.currentBranch!!.name)
        release.complete(Unit); withTimeout(10_000) { finished.await() }
        // Drain the UI dispatcher after the old IO result has returned.
        withContext(Dispatchers.Main) { yield() }
        assertEquals(second.path, vm.state.value.repositoryPath)
        assertEquals("second", vm.state.value.currentBranch!!.name)
        assertTrue(vm.state.value.localBranches.any { it.name == "second" && it.isHead })
    }
    @Test fun queuedSwitchCannotRunAfterItsRepositorySelectionIsLeft() = runBlocking {
        val second = SampleRepository.openOrCreate(File(workspace, "two")).canonicalFile; paths += second.path
        Git.open(second).use { it.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).call() }
        val vm = model(LocalBranchRepository()); val held = gate()
        val scope = CoroutineScope(Dispatchers.IO).also { scopes += it }
        val blocker = scope.launch { RepositoryOperationCoordinator.withRepository(root.path) { held.hold() } }
        entered(held)
        main { vm.switchBranch(vm.state.value.selection!!, vm.state.value.localBranches.first { it.name == "other" }) }; val queued = jobs(vm)
        main { vm.setRepositoryPath(second.path) }
        held.release.complete(Unit); blocker.join(); withTimeout(10_000) { queued.joinAll() }; idle(vm)
        assertEquals("main", Git.open(root).use { it.repository.branch })
        assertEquals(second.path, vm.state.value.repositoryPath)
    }
    @Test fun changedHeadCannotSilentlyBecomeTheStartOfAReviewedNewBranch() {
        val vm = model(LocalBranchRepository()); commit(root)
        main { vm.createBranch(vm.state.value.selection!!, "reviewed-new") }; idle(vm)
        assertNull(Git.open(root).use { it.repository.exactRef("refs/heads/reviewed-new") })
    }
    @Test fun anotherCurrentBranchAtTheSameCommitInvalidatesTheReview() {
        val vm = model(LocalBranchRepository())
        Git.open(root).use { it.checkout().setName("other").call() }
        main { vm.createBranch(vm.state.value.selection!!, "wrong-branch-review") }; idle(vm)
        assertNull(Git.open(root).use { it.repository.exactRef("refs/heads/wrong-branch-review") })
    }
    @Test fun aMovedSourceBranchCannotBeMergedUsingItsOldRow() {
        val vm = model(LocalBranchRepository()); val before = Git.open(root).use { it.repository.resolve("HEAD") }
        Git.open(root).use { it.checkout().setName("other").call() }; commit(root)
        Git.open(root).use { it.checkout().setName("main").call() }
        main { vm.mergeBranch(vm.state.value.selection!!, vm.state.value.localBranches.first { it.name == "other" }) }; idle(vm)
        assertEquals(before, Git.open(root).use { it.repository.resolve("HEAD") })
    }
    @Test fun failedBranchRefreshDisablesWritesFromCachedRows() {
        var fail = false; val actual = LocalBranchRepository()
        val vm = model(object : BranchRepository by actual {
            override suspend fun listBranches(repoPath: String): Result<List<Branch>> = if (fail) Result.failure(IOException("Owned inventory failure")) else actual.listBranches(repoPath)
        })
        fail = true; main { vm.loadBranches() }; idle(vm)
        main { vm.createBranch(vm.state.value.selection!!, "after-failed-read") }; idle(vm)
        assertNull(Git.open(root).use { it.repository.exactRef("refs/heads/after-failed-read") })
    }
    @Test fun aRefreshRequestedDuringAnOlderReadMustStillObserveLaterBranches() = runBlocking {
        var hold = false; val held = gate(); val actual = LocalBranchRepository()
        val vm = model(object : BranchRepository by actual {
            override suspend fun listBranches(repoPath: String): Result<List<Branch>> {
                val captured = actual.listBranches(repoPath)
                if (hold) { hold = false; held.hold() }
                return captured
            }
        })
        hold = true; main { vm.loadBranches() }; entered(held); val old = jobs(vm)
        // Simulate an external writer: it deliberately does not participate in the app's lease.
        Git.open(root).use { it.branchCreate().setName("arrived-later").call() }
        main { vm.loadBranches() }; held.release.complete(Unit); withTimeout(10_000) { old.joinAll() }; idle(vm)
        assertTrue(vm.state.value.localBranches.any { it.name == "arrived-later" })
    }
    @Test fun checkoutAttentionSurvivesAFollowingSnapshotFailure() {
        var fail = false; val actual = LocalBranchRepository()
        val vm = model(object : BranchRepository by actual {
            override suspend fun switchBranch(repoPath: String, name: String): Result<Unit> {
                actual.switchBranch(repoPath, name).getOrThrow(); fail = true
                return Result.failure(LfsCheckoutIncompleteException("Branch switched. Download missing file contents."))
            }
            override suspend fun listBranches(repoPath: String): Result<List<Branch>> = if (fail) Result.failure(IOException("Owned inventory failure")) else actual.listBranches(repoPath)
        })
        main { vm.switchBranch(vm.state.value.selection!!, vm.state.value.localBranches.first { it.name == "other" }) }; idle(vm)
        assertEquals("other", Git.open(root).use { it.repository.branch })
        assertEquals("Branch switched. Download missing file contents.", vm.state.value.errorMessage)
    }

    @Test fun oldRoundTripSelectionCannotWriteOrAcknowledgeTheNewVisit() {
        val vm = model(LocalBranchRepository()); val old = vm.state.value.selection!!
        val second = SampleRepository.openOrCreate(File(workspace, "two")).canonicalFile; paths += second.path
        main { vm.setRepositoryPath(second.path) }; idle(vm)
        main { vm.setRepositoryPath(root.path) }; idle(vm)
        main { vm.createBranch(vm.state.value.selection!!, "invalid branch name") }; idle(vm)
        val error = vm.state.value.errorMessage; assertNotNull(error); val revision = vm.state.value.operationRevision
        main {
            val row = vm.state.value.localBranches.first { it.name == "other" }
            vm.createBranch(old, "stale-created"); vm.switchBranch(old, row); vm.mergeBranch(old, row); vm.deleteBranch(old, row, force = true)
            vm.clearError(old)
        }
        assertEquals(error, vm.state.value.errorMessage); assertEquals(revision, vm.state.value.operationRevision)
        Git.open(root).use { assertEquals("main", it.repository.branch); assertNotNull(it.repository.exactRef("refs/heads/other")); assertNull(it.repository.exactRef("refs/heads/stale-created")) }
        main { vm.clearError(vm.state.value.selection) }; assertNull(vm.state.value.errorMessage)
    }
    @Test fun duplicateCreateKeepsItsCapturedHeadAndOriginalPathWithoutClearingAnotherForm() = runBlocking {
        val held = gate(); val actual = LocalBranchRepository(); val calls = CopyOnWriteArrayList<Triple<String, String, String>>()
        val vm = model(object : BranchRepository by actual {
            override suspend fun createBranch(repoPath: String, name: String, startPoint: String): Result<Unit> {
                calls += Triple(repoPath, name, startPoint); held.hold(); return actual.createBranch(repoPath, name, startPoint)
            }
        })
        val reviewedHead = Git.open(root).use { it.repository.resolve("HEAD").name }; var callbacks = 0
        main {
            val expected = vm.state.value.selection!!
            vm.createBranch(expected, "owned-created") { callbacks++ }; vm.createBranch(expected, "duplicate") { callbacks++ }
        }
        entered(held); val running = jobs(vm)
        val second = SampleRepository.openOrCreate(File(workspace, "two")).canonicalFile; paths += second.path
        main { vm.setRepositoryPath(second.path) }; held.release.complete(Unit); withTimeout(10_000) { running.joinAll() }; idle(vm)
        assertEquals(listOf(Triple(root.path, "owned-created", reviewedHead)), calls.toList())
        assertEquals(0, main { callbacks }); assertEquals(second.path, vm.state.value.repositoryPath)
        assertEquals(reviewedHead, Git.open(root).use { it.repository.exactRef("refs/heads/owned-created").objectId.name })
        assertNull(Git.open(second).use { it.repository.exactRef("refs/heads/owned-created") })
    }
    @Test fun movedTargetCannotRetargetSwitchOrForcedDelete() {
        val vm = model(LocalBranchRepository()); val before = Git.open(root).use { it.repository.resolve("HEAD") }
        for (index in 0..1) {
            val expected = vm.state.value.selection!!; val row = vm.state.value.localBranches.first { it.name == "other" }
            Git.open(root).use { it.checkout().setName("other").call() }; commit(root, "later-$index.md")
            Git.open(root).use { it.checkout().setName("main").call() }
            main { if (index == 0) vm.switchBranch(expected, row) else vm.deleteBranch(expected, row, force = true) }; idle(vm)
            Git.open(root).use { assertEquals("main", it.repository.branch); assertEquals(before, it.repository.resolve("HEAD")); assertNotNull(it.repository.exactRef("refs/heads/other")) }
            assertTrue(vm.state.value.errorMessage!!.contains("Branch changed"))
        }
    }
    @Test fun aCurrentBranchChangeDuringInventoryReadCannotPublishAnActionableMixedSnapshot() {
        var move = false; val actual = LocalBranchRepository()
        val vm = model(object : BranchRepository by actual {
            override suspend fun listBranches(repoPath: String): Result<List<Branch>> {
                val captured = actual.listBranches(repoPath)
                if (move) { move = false; Git.open(File(repoPath)).use { it.checkout().setName("other").call() } }
                return captured
            }
        })
        move = true; main { vm.loadBranches() }; idle(vm)
        assertFalse(vm.state.value.actionsReady); assertTrue(vm.state.value.errorMessage!!.contains("changed while loading"))
        main { vm.createBranch(vm.state.value.selection!!, "mixed-snapshot") }; idle(vm)
        assertNull(Git.open(root).use { it.repository.exactRef("refs/heads/mixed-snapshot") })
        main { vm.loadBranches() }; idle(vm)
        assertTrue(vm.state.value.actionsReady); assertEquals("other", vm.state.value.currentBranch!!.name)
    }

}

package com.bontecou.syncmd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.*
import com.bontecou.syncmd.domain.repository.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.viewmodels.ConflictViewModel
import com.bontecou.syncmd.ui.viewmodels.PullViewModel
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Controlled responses outlive cancellation deliberately; no catalog, account or feature policy is replaced. */
class RepositoryViewModelTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var workspace: File
    private lateinit var a: String
    private lateinit var b: String
    private val models = mutableListOf<ViewModel>()
    private val gates = mutableListOf<Gate>()
    private val extraScopes = mutableListOf<CoroutineScope>()
    private class Gate {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        suspend fun hold() = withContext(NonCancellable) { entered.complete(Unit); release.await() }
    }
    private fun gate() = Gate().also { gates += it }
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(condition: () -> Boolean) = runBlocking { withTimeout(10_000) { while (!main(condition)) delay(5) } }
    private fun entered(gate: Gate) = runBlocking { withTimeout(10_000) { gate.entered.await() } }
    private fun activeJob(model: ViewModel) = main { model.viewModelScope.coroutineContext[Job]!!.children.single { it.isActive } }
    private fun finish(gate: Gate, job: Job) { gate.release.complete(Unit); runBlocking { withTimeout(10_000) { job.join() } } }
    @Before fun setup() {
        workspace = File(instrumentation.targetContext.filesDir, "viewmodel-staleness-${UUID.randomUUID()}")
        a = SampleRepository.openOrCreate(File(workspace, "a")).canonicalPath
        b = SampleRepository.openOrCreate(File(workspace, "b")).canonicalPath
    }
    @After fun cleanup() {
        gates.forEach { it.release.complete(Unit) }
        extraScopes.forEach { it.cancel() }
        val jobs = main { models.map { it.viewModelScope.coroutineContext[Job]!! }.also {
            ViewModelStore().apply { models.forEachIndexed { index, model -> put("model-$index", model) }; clear() }
        } }
        runBlocking { withTimeout(10_000) { jobs.joinAll() } }
        if (::workspace.isInitialized) {
            check(workspace.canonicalFile.parentFile == instrumentation.targetContext.filesDir.canonicalFile)
            runBlocking { RepositoryOperationCoordinator.withRepositories(listOf(a, b)) { assertTrue(workspace.deleteRecursively()) } }
        }
    }
    private fun pull(repository: ControlledPull) = main { PullViewModel(PullService(repository)).also { models += it } }
    private fun conflicts(repository: ControlledConflicts) = main { ConflictViewModel(ConflictService(repository)).also { models += it } }
    private fun status(path: String, branch: String = "main") = RepositoryStatus(path, branch, true)
    private fun conflict(name: String) = Conflict("same.md", "$name ours\n", "$name incoming\n", "base\n")

    @Test fun oldPullReadCannotReplaceTheNewRepositoryStatus() {
        val delayed = gate(); val repository = ControlledPull()
        repository.status = { path -> if (path == a) { delayed.hold(); Result.success(status(path, "old")) } else Result.success(status(path, "new")) }
        val model = pull(repository); main { model.setRepositoryPath(a) }; entered(delayed); val old = activeJob(model)
        main { model.setRepositoryPath(b) }; await { model.status.value?.repoPath == b }
        finish(delayed, old)
        assertEquals(b, main { model.status.value!!.repoPath }); assertEquals("new", main { model.status.value!!.currentBranch })
    }
    @Test fun olderPullRefreshCannotClearTheNewRequestBusyState() {
        val repository = ControlledPull(); val model = pull(repository)
        main { model.setRepositoryPath(a) }; await { model.status.value != null && !model.isLoading.value }
        val first = gate(); val second = gate(); val calls = AtomicInteger()
        repository.status = { path ->
            if (calls.incrementAndGet() == 1) { first.hold(); Result.success(status(path, "older")) }
            else { second.hold(); Result.success(status(path, "latest")) }
        }
        main { model.loadStatus() }; entered(first); val old = activeJob(model)
        main { model.loadStatus() }; val fresh = main { model.viewModelScope.coroutineContext[Job]!!.children.first { it !== old } }
        finish(first, old)
        entered(second)
        assertTrue(main { model.isLoading.value })
        finish(second, fresh); assertEquals("latest", main { model.status.value!!.currentBranch })
        assertFalse(main { model.isLoading.value })
    }
    @Test fun duplicateFetchIsRejectedBeforeEitherSubmissionCanFinish() {
        val repository = ControlledPull(); val delayed = gate(); val calls = AtomicInteger()
        repository.fetching = { calls.incrementAndGet(); delayed.hold(); Result.success(Unit) }
        val model = pull(repository); main { model.setRepositoryPath(a) }; await { !model.isLoading.value && model.status.value != null }
        main { model.fetch(model.selection.value!!); model.fetch(model.selection.value!!) }; entered(delayed)
        assertEquals(1, calls.get())
    }
    @Test fun oldPullMutationFailureCannotLeakIntoTheNewRepository() {
        val repository = ControlledPull(); val delayed = gate()
        repository.pulling = { delayed.hold(); throw IOException("Owned old-repository failure") }
        val model = pull(repository); main { model.setRepositoryPath(a) }; await { model.status.value != null && !model.isLoading.value }
        main { model.executePull(model.selection.value!!) }; entered(delayed); val old = activeJob(model)
        main { model.setRepositoryPath(b) }; finish(delayed, old)
        await { model.status.value?.repoPath == b && !model.isLoading.value }
        assertNull(main { model.errorMessage.value }); assertNull(main { model.attention.value })
    }
    @Test fun oldConflictReadCannotReplaceTheNewRepositorySnapshot() {
        val repository = ControlledConflicts(); val delayed = gate()
        repository.values[a] = listOf(conflict("old")); repository.values[b] = listOf(conflict("new"))
        repository.readingState = { path -> if (path == a) delayed.hold(); Result.success(MergeState(true, path)) }
        val model = conflicts(repository); main { model.setRepositoryPath(a) }; entered(delayed); val old = activeJob(model)
        main { model.setRepositoryPath(b) }; await { model.conflicts.value.singleOrNull()?.currentContent == "new ours\n" }
        finish(delayed, old)
        assertEquals(b, main { model.mergeState.value!!.sourceBranch })
        assertEquals("new ours\n", main { model.conflicts.value.single().currentContent })
    }
    @Test fun changingRepositoryClearsTheOldConflictEditorAndDraftImmediately() {
        val repository = ControlledConflicts(); repository.values[a] = listOf(conflict("old")); repository.values[b] = listOf(conflict("new"))
        val model = conflicts(repository); main { model.setRepositoryPath(a) }; await { model.conflicts.value.isNotEmpty() && !model.isLoading.value }
        main { model.selectConflict(model.selection.value!!, model.conflicts.value.single()); model.setManualResolutionContent(model.selection.value!!, model.selectedConflict.value!!, "Old editor draft"); model.setRepositoryPath(b) }
        assertNull(main { model.selectedConflict.value }); assertEquals("", main { model.manualResolutionContent.value })
    }
    @Test fun callbacksFromAnEarlierVisitCannotActAfterARoundTripToTheSamePath() {
        val pulls = ControlledPull(); val conflicts = ControlledConflicts(); conflicts.values[a] = listOf(conflict("same"))
        val pull = pull(pulls); val editor = conflicts(conflicts)
        main { pull.setRepositoryPath(a); editor.setRepositoryPath(a) }
        await { !pull.isLoading.value && editor.actionsReady.value && !editor.isLoading.value }
        val oldPull = main { pull.selection.value!! }; val oldEditor = main { editor.selection.value!! }
        val oldConflict = main { editor.conflicts.value.single() }
        main { pull.setRepositoryPath(b); editor.setRepositoryPath(b); pull.setRepositoryPath(a); editor.setRepositoryPath(a) }
        await { pull.status.value?.repoPath == a && !pull.isLoading.value && editor.actionsReady.value && !editor.isLoading.value }
        assertNotSame(oldPull, main { pull.selection.value }); assertNotSame(oldEditor, main { editor.selection.value })
        main {
            pull.executePull(oldPull); pull.fetch(oldPull); pull.planPull(oldPull)
            editor.selectConflict(oldEditor, oldConflict); editor.acceptOurs(oldEditor, oldConflict)
            editor.completeMerge(oldEditor, "Stale completion"); editor.abortMerge(oldEditor)
        }
        assertEquals(0, pulls.pullCalls.get()); assertEquals(0, pulls.fetchCalls.get()); assertEquals(0, pulls.planCalls.get())
        assertEquals(0, conflicts.resolveCalls.get()); assertEquals(0, conflicts.completeCalls.get()); assertEquals(0, conflicts.abortCalls.get())
        assertNull(main { editor.selectedConflict.value }); assertNull(main { pull.pullPlan.value })
        assertTrue(main { pull.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive } })
    }
    @Test fun duplicateManualResolutionFinishesOnlyTheOriginalRepositoryWithItsCapturedDraft() {
        val repository = ControlledConflicts(); repository.values[a] = listOf(conflict("a")); repository.values[b] = listOf(conflict("b"))
        val delayed = gate(); repository.resolving = { _, file, strategy, _ -> delayed.hold(); Result.success(ConflictResolution(file, strategy, true)) }
        val model = conflicts(repository); main { model.setRepositoryPath(a) }; await { model.actionsReady.value && !model.isLoading.value }
        val binding = main { model.selection.value!! }; val selected = main { model.conflicts.value.single() }
        main {
            model.selectConflict(binding, selected); model.setManualResolutionContent(binding, selected, "Captured a draft\n")
            model.acceptManual(binding, selected); model.acceptManual(binding, selected)
        }
        entered(delayed); val job = activeJob(model)
        assertEquals(1, repository.resolveCalls.get())
        main { model.setRepositoryPath(b) }
        assertNull(main { model.selectedConflict.value }); assertEquals("", main { model.manualResolutionContent.value })
        assertTrue(main { model.isLoading.value })
        finish(delayed, job)
        await { model.actionsReady.value && !model.isLoading.value && model.conflicts.value.singleOrNull()?.currentContent == "b ours\n" }
        assertEquals(listOf(a to "Captured a draft\n"), repository.resolvedDrafts.toList())
        assertTrue(main { model.resolvedConflicts.value.isEmpty() }); assertNull(main { model.errorMessage.value })
    }
    @Test fun mutationsQueuedBehindALeaseAreAbandonedAfterChangingRepository() {
        val pulls = ControlledPull(); val conflicts = ControlledConflicts(); conflicts.values[a] = listOf(conflict("a"))
        val pull = pull(pulls); val editor = conflicts(conflicts)
        main { pull.setRepositoryPath(a); editor.setRepositoryPath(a) }
        await { !pull.isLoading.value && pull.status.value != null && editor.actionsReady.value && !editor.isLoading.value }
        val binding = main { editor.selection.value!! }; val selected = main { editor.conflicts.value.single() }
        val held = gate(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { extraScopes += it }
        val blocker = scope.launch { RepositoryOperationCoordinator.withRepository(a) { held.entered.complete(Unit); held.release.await() } }
        entered(held)
        main { pull.executePull(pull.selection.value!!); editor.acceptOurs(binding, selected); pull.setRepositoryPath(b); editor.setRepositoryPath(b) }
        finish(held, blocker)
        await { pull.status.value?.repoPath == b && !pull.isLoading.value && editor.actionsReady.value && !editor.isLoading.value }
        assertEquals(0, pulls.pullCalls.get()); assertEquals(0, conflicts.resolveCalls.get())
        assertNull(main { pull.errorMessage.value }); assertNull(main { editor.errorMessage.value })
    }
    @Test fun changedConflictIsNotWrittenAndAnUnsubmittedDraftRemainsAvailable() {
        val repository = ControlledConflicts(); repository.values[a] = listOf(conflict("original"))
        val model = conflicts(repository); main { model.setRepositoryPath(a) }; await { model.actionsReady.value && !model.isLoading.value }
        val binding = main { model.selection.value!! }; val original = main { model.conflicts.value.single() }
        main { model.selectConflict(binding, original); model.setManualResolutionContent(binding, original, "Keep my draft\n") }
        repository.values[a] = listOf(conflict("new index"))
        main { model.loadConflicts(); model.acceptManual(binding, original) }
        await { !model.isLoading.value && model.errorMessage.value != null }
        assertEquals(0, repository.resolveCalls.get())
        assertSame(original, main { model.selectedConflict.value }); assertEquals("Keep my draft\n", main { model.manualResolutionContent.value })
        assertEquals("new index ours\n", main { model.conflicts.value.single().currentContent })
        assertTrue(main { model.errorMessage.value!!.resolve(instrumentation.targetContext).contains("Conflict changed") })
    }
    @Test fun failedConflictRefreshDisablesDestructiveCommandsFromTheCachedSnapshot() {
        val repository = ControlledConflicts(); val model = conflicts(repository)
        main { model.setRepositoryPath(a) }; await { model.actionsReady.value && !model.isLoading.value }
        val binding = main { model.selection.value!! }
        repository.readingState = { Result.failure(IOException("Owned read failure")) }
        main { model.loadConflicts() }; await { !model.isLoading.value && model.errorMessage.value != null }
        assertFalse(main { model.actionsReady.value })
        main { model.completeMerge(binding, "Cached completion"); model.abortMerge(binding) }
        assertEquals(0, repository.completeCalls.get()); assertEquals(0, repository.abortCalls.get())
    }
    @Test fun queuedRefreshDoesNotEraseCurrentMutationFailuresBeforeAcknowledgement() {
        val pulls = ControlledPull(); val pullGate = gate()
        pulls.pulling = { pullGate.hold(); Result.failure(IOException("Owned current pull failure")) }
        val conflicts = ControlledConflicts(); val conflictGate = gate()
        conflicts.aborting = { conflictGate.hold(); Result.failure(IOException("Owned current abort failure")) }
        val pull = pull(pulls); val editor = conflicts(conflicts)
        main { pull.setRepositoryPath(a); editor.setRepositoryPath(b) }
        await { !pull.isLoading.value && pull.status.value != null && editor.actionsReady.value && !editor.isLoading.value }
        val pullBinding = main { pull.selection.value!! }; val conflictBinding = main { editor.selection.value!! }
        main { pull.executePull(pullBinding); editor.abortMerge(conflictBinding) }
        entered(pullGate); entered(conflictGate)
        val pullJob = activeJob(pull); val conflictJob = activeJob(editor)
        main { pull.loadStatus(); editor.loadConflicts() }
        finish(pullGate, pullJob); finish(conflictGate, conflictJob)
        await { !pull.isLoading.value && !editor.isLoading.value && editor.actionsReady.value }
        assertEquals("Owned current pull failure", main { pull.errorMessage.value })
        assertEquals("Owned current abort failure", main { editor.errorMessage.value?.resolve(instrumentation.targetContext) })
        main { pull.clearError(conflictBinding); editor.clearError(pullBinding) }
        assertNotNull(main { pull.errorMessage.value }); assertNotNull(main { editor.errorMessage.value })
        main { pull.clearError(pullBinding); editor.clearError(conflictBinding) }
        assertNull(main { pull.errorMessage.value }); assertNull(main { editor.errorMessage.value })
    }

    private class ControlledPull : PullRepository {
        val pullCalls = AtomicInteger(); val fetchCalls = AtomicInteger(); val planCalls = AtomicInteger()
        @Volatile var status: suspend (String) -> Result<RepositoryStatus> = { Result.success(RepositoryStatus(it, "main", true)) }
        @Volatile var fetching: suspend (String) -> Result<Unit> = { Result.success(Unit) }
        @Volatile var pulling: suspend (String) -> Result<SafePullResult> = { Result.success(SafePullResult(true, "Owned result")) }
        override suspend fun getStatus(repoPath: String) = status(repoPath)
        override suspend fun planPull(repoPath: String): Result<SafePullPlan> { planCalls.incrementAndGet(); return Result.success(SafePullPlan(repoPath, "main", "origin/main", PullPlan(MergeType.UP_TO_DATE, false, false))) }
        override suspend fun executePull(repoPath: String): Result<SafePullResult> { pullCalls.incrementAndGet(); return pulling(repoPath) }
        override suspend fun fetch(repoPath: String): Result<Unit> { fetchCalls.incrementAndGet(); return fetching(repoPath) }
        override suspend fun getCommitsAhead(repoPath: String) = Result.success(0)
        override suspend fun getCommitsBehind(repoPath: String) = Result.success(0)
    }
    private class ControlledConflicts : ConflictRepository {
        val values = ConcurrentHashMap<String, List<Conflict>>()
        val resolveCalls = AtomicInteger(); val abortCalls = AtomicInteger(); val completeCalls = AtomicInteger()
        val resolvedDrafts = CopyOnWriteArrayList<Pair<String, String?>>()
        private val active = ConcurrentHashMap<String, Boolean>()
        @Volatile var resolving: suspend (String, String, ConflictResolutionStrategy, String?) -> Result<ConflictResolution> = { _, file, strategy, _ -> Result.success(ConflictResolution(file, strategy, true)) }
        @Volatile var aborting: suspend (String) -> Result<Unit> = { Result.success(Unit) }
        @Volatile var readingState: suspend (String) -> Result<MergeState> = { Result.success(MergeState(true, it)) }
        override suspend fun getMergeState(repoPath: String) = readingState(repoPath)
        override suspend fun getConflicts(repoPath: String) = Result.success(values[repoPath].orEmpty())
        override suspend fun getFileConflict(repoPath: String, filePath: String) = Result.success(values[repoPath].orEmpty().find { it.filePath == filePath })
        override suspend fun resolveConflict(repoPath: String, filePath: String, strategy: ConflictResolutionStrategy, customContent: String?): Result<ConflictResolution> {
            resolveCalls.incrementAndGet(); resolvedDrafts += repoPath to customContent
            val result = resolving(repoPath, filePath, strategy, customContent)
            if (result.getOrNull()?.resolved == true) values[repoPath] = values[repoPath].orEmpty().filterNot { it.filePath == filePath }
            return result
        }
        override suspend fun markAllResolved(repoPath: String) = Result.success(Unit)
        override suspend fun completeMerge(repoPath: String, message: String): Result<Unit> { completeCalls.incrementAndGet(); active[repoPath] = false; values[repoPath] = emptyList(); return Result.success(Unit) }
        override suspend fun abortMerge(repoPath: String): Result<Unit> { abortCalls.incrementAndGet(); return aborting(repoPath).onSuccess { active[repoPath] = false; values[repoPath] = emptyList() } }
        override suspend fun parseConflictMarkers(content: String): Result<Conflict?> = Result.success(null)
    }
}

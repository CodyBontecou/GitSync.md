package com.bontecou.syncmd

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.*
import com.bontecou.syncmd.domain.repository.HistoryRepository
import com.bontecou.syncmd.domain.repository.RemoteRepository
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.util.RepositorySelection
import com.bontecou.syncmd.ui.viewmodels.HistoryViewModel
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class HistoryViewModelTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var workspace: File
    private lateinit var a: String
    private lateinit var b: String
    private val models = mutableListOf<HistoryViewModel>()
    private val gates = mutableListOf<Gate>()
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
    private fun jobs(model: HistoryViewModel) = main { model.viewModelScope.coroutineContext[Job]!!.children.toList() }
    private fun finish(gate: Gate, jobs: List<Job>) { gate.release.complete(Unit); runBlocking { withTimeout(10_000) { jobs.joinAll() } } }
    @Before fun setup() {
        workspace = File(instrumentation.targetContext.filesDir, "history-state-${UUID.randomUUID()}")
        a = SampleRepository.openOrCreate(File(workspace, "a")).canonicalPath
        b = SampleRepository.openOrCreate(File(workspace, "b")).canonicalPath
    }
    @After fun cleanup() {
        gates.forEach { it.release.complete(Unit) }
        val owned = main { models.map { it.viewModelScope.coroutineContext[Job]!! }.also {
            ViewModelStore().apply { models.forEachIndexed { i, model -> put("history-$i", model) }; clear() }
        } }
        runBlocking { withTimeout(10_000) { owned.joinAll() } }
        if (::workspace.isInitialized) runBlocking {
            check(workspace.canonicalFile.parentFile == instrumentation.targetContext.filesDir.canonicalFile)
            RepositoryOperationCoordinator.withRepositories(listOf(a, b)) { assertTrue(workspace.deleteRecursively()) }
        }
    }
    private fun commit(path: String) = Commit(hash = path, author = "Owned author", message = path)
    private fun stash(path: String) = Stash("stash@{0}", path, path)
    private fun tag(path: String) = Tag("same-tag", path)
    private fun model(repository: ControlledHistory, pushing: suspend (String, String) -> Result<Unit> = { _, _ -> Result.success(Unit) }): HistoryViewModel = main {
        val remote = object : RemoteRepository by LocalRemoteRepository() {
            override suspend fun pushTag(repoPath: String, tagName: String, remote: String): Result<Unit> = pushing(repoPath, tagName)
        }
        HistoryViewModel(HistoryService(repository), PushService(remote, LocalBranchRepository(), repository)).also { models += it }
    }
    private fun ready(model: HistoryViewModel, path: String = a) { main { model.setRepositoryPath(path) }; await { !model.state.value.isLoading && model.state.value.commits.isNotEmpty() && model.state.value.tags.isNotEmpty() && model.state.value.stashes.isNotEmpty() } }

    @Test fun oldHistoryReadCannotReplaceNewRepositoryOrClearItsLists() {
        val held = gate(); val repository = ControlledHistory()
        repository.reading = { path -> if (path == a) held.hold(); Result.success(listOf(commit(path))) }
        val model = model(repository); main { model.setRepositoryPath(a) }; entered(held); val old = jobs(model)
        main { model.setRepositoryPath(b) }; await { model.state.value.commits.singleOrNull()?.hash == b }
        finish(held, old)
        assertEquals(b, main { model.state.value.commits.single().hash })
        assertEquals(b, main { model.state.value.stashes.single().commitHash }); assertEquals(b, main { model.state.value.tags.single().commitHash })
    }
    @Test fun duplicateStashSubmissionUsesTheCapturedMessageOnlyOnce() {
        val held = gate(); val messages = CopyOnWriteArrayList<String?>(); val repository = ControlledHistory()
        repository.saving = { _, message -> messages += message; held.hold(); Result.success(StashResult(true, message = "Saved")) }
        val model = model(repository); ready(model)
        main { model.stashSave(model.state.value.selection!!, "Owned message"); model.stashSave(model.state.value.selection!!, "Duplicate") }; entered(held)
        assertEquals(listOf("Owned message"), messages.toList())
    }
    @Test fun changingRepositoryClearsTheSelectedCommitImmediately() {
        val repository = ControlledHistory(); val model = model(repository); ready(model)
        main { model.selectCommit(model.state.value.selection!!, model.state.value.commits.single()); model.setRepositoryPath(b) }
        assertNull(main { model.state.value.selectedCommit })
    }
    @Test fun olderHistoryRefreshCannotClearTheLatestBusyState() {
        val repository = ControlledHistory(); val model = model(repository); ready(model)
        val first = gate(); val second = gate(); val calls = AtomicInteger()
        repository.reading = { path -> if (calls.incrementAndGet() == 1) first.hold() else second.hold(); Result.success(listOf(commit(path))) }
        main { model.loadHistory() }; entered(first); val old = jobs(model)
        main { model.loadHistory() }; finish(first, old); entered(second)
        assertTrue(main { model.state.value.isLoading })
    }
    @Test fun aTagPushFailureCannotLeakIntoAnotherRepository() {
        val held = gate(); val repository = ControlledHistory()
        val model = model(repository) { _, _ -> held.hold(); Result.failure(IOException("Owned push failure")) }
        ready(model); main { model.pushTag(model.state.value.selection!!, model.state.value.tags.single()) }; entered(held); val old = jobs(model)
        main { model.setRepositoryPath(b) }; finish(held, old)
        await { model.state.value.commits.singleOrNull()?.hash == b && !model.state.value.isLoading }
        assertNull(main { model.state.value.errorMessage })
    }
    @Test fun refreshDoesNotEraseAnUnacknowledgedMutationFailure() {
        val repository = ControlledHistory(); repository.creating = { _, _, _, _, _ -> Result.failure(IOException("Owned create failure")) }
        val model = model(repository); ready(model)
        main { model.createTag(model.state.value.selection!!, "new-tag") }; await { model.state.value.errorMessage?.resolve(instrumentation.targetContext) == "Owned create failure" && !model.state.value.isLoading }
        main { model.loadHistory() }; await { !model.state.value.isLoading }
        assertEquals("Owned create failure", main { model.state.value.errorMessage?.resolve(instrumentation.targetContext) })
    }
    @Test fun oldSelectionCannotActAfterReturningToTheSamePath() {
        val repository = ControlledHistory(); val writes = AtomicInteger()
        repository.saving = { _, _ -> writes.incrementAndGet(); Result.success(StashResult(true, message = "Saved")) }
        repository.creating = { _, _, _, _, _ -> writes.incrementAndGet(); Result.success(Unit) }
        val model = model(repository) { _, _ -> writes.incrementAndGet(); Result.success(Unit) }
        ready(model); val old = main { model.state.value.selection!! }; val oldTag = main { model.state.value.tags.single() }
        ready(model, b); ready(model, a)
        main { model.stashSave(old, "Old callback"); model.createTag(old, "old-tag"); model.pushTag(old, oldTag); model.selectCommit(old, model.state.value.commits.single()) }
        assertEquals(0, writes.get()); assertNull(main { model.state.value.selectedCommit })
    }
    @Test fun aQueuedMutationIsAbandonedBeforeBackendEntryAfterSwitching() = runBlocking {
        val repository = ControlledHistory(); val writes = AtomicInteger()
        repository.saving = { _, _ -> writes.incrementAndGet(); Result.success(StashResult(true, message = "Saved")) }
        val model = model(repository); ready(model)
        val held = gate(); val scope = CoroutineScope(Dispatchers.IO)
        val blocker = scope.launch { RepositoryOperationCoordinator.withRepository(a) { held.hold() } }
        try {
            entered(held)
            main { model.stashSave(model.state.value.selection!!, "Queued") }; val queued = jobs(model)
            main { model.setRepositoryPath(b) }; held.release.complete(Unit); blocker.join()
            withTimeout(10_000) { queued.joinAll() }; await { model.state.value.commits.singleOrNull()?.hash == b && !model.state.value.isLoading }
            assertEquals(0, writes.get())
        } finally { held.release.complete(Unit); blocker.cancelAndJoin(); scope.cancel() }
    }
    @Test fun movedStashIndicesCannotRetargetApplyPopOrDrop() {
        val repository = ControlledHistory(); val writes = AtomicInteger(); var version = 0
        repository.listingStashes = { path -> Result.success(listOf(stash(path).copy(commitHash = "stash-$version"))) }
        repository.applying = { _, _ -> writes.incrementAndGet(); Result.success(Unit) }
        repository.popping = repository.applying; repository.dropping = repository.applying
        val model = model(repository); ready(model)
        val actions = listOf<(RepositorySelection, Stash) -> Unit>({ s, r -> model.stashApply(s, r) }, { s, r -> model.stashPop(s, r) }, { s, r -> model.stashDrop(s, r) })
        for (action in actions) {
            val row = main { model.state.value.stashes.single() }; version++
            main { action(model.state.value.selection!!, row) }
            await { !model.state.value.isLoading && model.state.value.errorMessage?.resolve(instrumentation.targetContext)?.contains("Stash list changed") == true }
            assertEquals(0, writes.get()); assertEquals("stash-$version", main { model.state.value.stashes.single().commitHash })
        }
    }
    @Test fun changedAnnotatedObjectCannotBePublishedOrDeletedEvenWithTheSameTarget() {
        val repository = ControlledHistory(); var version = 0; val writes = AtomicInteger()
        repository.listingTags = { path -> Result.success(listOf(tag(path).copy(isAnnotated = true, message = "Same annotation", refObjectId = "tag-$version"))) }
        repository.deleting = { _, _ -> writes.incrementAndGet(); Result.success(Unit) }
        val model = model(repository) { _, _ -> writes.incrementAndGet(); Result.success(Unit) }; ready(model)
        for (action in listOf<(RepositorySelection, Tag) -> Unit>({ s, r -> model.pushTag(s, r) }, { s, r -> model.deleteTag(s, r) })) {
            val row = main { model.state.value.tags.single() }; version++
            main { action(model.state.value.selection!!, row) }
            await { !model.state.value.isLoading && model.state.value.errorMessage?.resolve(instrumentation.targetContext)?.contains("Tag changed") == true }
            assertEquals(0, writes.get()); assertEquals("tag-$version", main { model.state.value.tags.single().refObjectId })
        }
    }
    @Test fun failedSnapshotDisablesCachedRowsAndCannotClearAnotherSelectionsError() {
        val repository = ControlledHistory(); val writes = AtomicInteger()
        repository.saving = { _, _ -> writes.incrementAndGet(); Result.success(StashResult(true, message = "Saved")) }
        val model = model(repository) { _, _ -> writes.incrementAndGet(); Result.success(Unit) }; ready(model)
        val old = main { model.state.value.selection!! }; ready(model, b)
        repository.listingTags = { Result.failure(IOException("Owned tag inventory failure")) }
        main { model.loadTags() }; await { !model.state.value.isLoading && model.state.value.errorMessage != null }
        assertFalse(main { model.state.value.actionsReady })
        main { model.stashSave(model.state.value.selection!!); model.pushTag(model.state.value.selection!!, model.state.value.tags.single()); model.clearError(old) }
        assertEquals(0, writes.get()); assertEquals("Owned tag inventory failure", main { model.state.value.errorMessage?.resolve(instrumentation.targetContext) })
    }
    @Test fun annotationAndExactReviewedHeadAreCapturedAndSuccessIsDeliveredOnce() {
        val repository = ControlledHistory(); val held = gate(); val calls = CopyOnWriteArrayList<List<Any?>>()
        repository.creating = { path, name, hash, annotated, message -> calls += listOf(path, name, hash, annotated, message); held.hold(); Result.success(Unit) }
        val model = model(repository); ready(model); var completed = 0
        main {
            model.createTag(model.state.value.selection!!, "owned-tag", annotated = true, message = "Owned annotation") { completed++ }
            model.createTag(model.state.value.selection!!, "duplicate") { completed++ }
        }
        entered(held); assertEquals(listOf(listOf(a, "owned-tag", a, true, "Owned annotation")), calls.toList())
        finish(held, jobs(model)); assertEquals(1, main { completed }); assertEquals(1L, main { model.state.value.operationRevision })
    }
    @Test fun aChangedHeadRejectsTheMutationAndRetainsTheUnsubmittedForm() {
        val repository = ControlledHistory(); val writes = AtomicInteger(); var completed = false
        repository.creating = { _, _, _, _, _ -> writes.incrementAndGet(); Result.success(Unit) }
        val model = model(repository); ready(model)
        repository.reading = { Result.success(listOf(commit("Changed HEAD"))) }
        main { model.createTag(model.state.value.selection!!, "owned-tag", message = "Keep draft") { completed = true } }
        await { !model.state.value.isLoading && model.state.value.errorMessage != null }
        assertEquals(0, writes.get()); assertFalse(main { completed }); assertEquals("Changed HEAD", main { model.state.value.commits.single().hash })
    }
    private inner class ControlledHistory : HistoryRepository by LocalHistoryRepository() {
        var reading: suspend (String) -> Result<List<Commit>> = { Result.success(listOf(commit(it))) }
        var saving: suspend (String, String?) -> Result<StashResult> = { _, _ -> Result.success(StashResult(true, message = "Saved")) }
        var creating: suspend (String, String, String, Boolean, String?) -> Result<Unit> = { _, _, _, _, _ -> Result.success(Unit) }
        override suspend fun getHistory(repoPath: String, maxCommits: Int) = reading(repoPath)
        var listingStashes: suspend (String) -> Result<List<Stash>> = { Result.success(listOf(stash(it))) }
        var listingTags: suspend (String) -> Result<List<Tag>> = { Result.success(listOf(tag(it))) }
        var applying: suspend (String, String) -> Result<Unit> = { _, _ -> Result.success(Unit) }
        var popping: suspend (String, String) -> Result<Unit> = { _, _ -> Result.success(Unit) }
        var dropping: suspend (String, String) -> Result<Unit> = { _, _ -> Result.success(Unit) }
        var deleting: suspend (String, String) -> Result<Unit> = { _, _ -> Result.success(Unit) }
        override suspend fun listStashes(repoPath: String) = listingStashes(repoPath)
        override suspend fun stashApply(repoPath: String, stashId: String) = applying(repoPath, stashId)
        override suspend fun stashPop(repoPath: String, stashId: String) = popping(repoPath, stashId)
        override suspend fun stashDrop(repoPath: String, stashId: String) = dropping(repoPath, stashId)
        override suspend fun deleteTag(repoPath: String, name: String) = deleting(repoPath, name)
        override suspend fun listTags(repoPath: String) = listingTags(repoPath)
        override suspend fun stashSave(repoPath: String, message: String?) = saving(repoPath, message)
        override suspend fun createTag(repoPath: String, name: String, commitHash: String, annotated: Boolean, message: String?) = creating(repoPath, name, commitHash, annotated, message)
    }
}

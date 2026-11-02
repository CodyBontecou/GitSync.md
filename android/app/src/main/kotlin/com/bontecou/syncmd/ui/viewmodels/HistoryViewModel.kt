package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.data.models.*
import com.bontecou.syncmd.services.git.HistoryService
import com.bontecou.syncmd.services.git.PushService
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.ui.util.RepositorySelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** History rows, reviews and callbacks belong to one visit to one repository. */
@HiltViewModel
class HistoryViewModel @Inject constructor(private val historyService: HistoryService, private val pushService: PushService) : ViewModel() {
    data class State(
        val repositoryPath: String? = null,
        val selection: RepositorySelection? = null,
        val commits: List<Commit> = emptyList(),
        val stashes: List<Stash> = emptyList(),
        val tags: List<Tag> = emptyList(),
        val selectedCommit: Commit? = null,
        val isLoading: Boolean = false,
        val actionsReady: Boolean = false,
        val errorMessage: String? = null,
        val operationRevision: Long = 0,
    )
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private fun update(change: (State) -> State) { _state.value = change(_state.value) }
    private data class Snapshot(val commits: List<Commit>, val stashes: List<Stash>, val tags: List<Tag>) {
        val head get() = commits.firstOrNull()?.hash
    }
    private var snapshot: Snapshot? = null
    private var readJob: Job? = null
    @Volatile private var readId = 0L
    private var mutationRunning = false
    private var refreshPending = false
    private var operationNotice: String? = null
    private fun current(expected: RepositorySelection?) = expected != null && _state.value.selection === expected
    private fun available(expected: RepositorySelection) = current(expected) && _state.value.actionsReady && !mutationRunning && !_state.value.isLoading

    fun setRepositoryPath(path: String) {
        if (_state.value.repositoryPath != path) {
            readId++; readJob?.cancel(); snapshot = null; operationNotice = null
            update { State(repositoryPath = path, selection = path.takeIf { it.isNotBlank() }?.let(::RepositorySelection),
                isLoading = mutationRunning, operationRevision = it.operationRevision) }
        }
        loadHistory()
    }
    // All three inventories form one snapshot; a successful sub-read must not hide another failure.
    fun loadStashes() = loadHistory()
    fun loadTags() = loadHistory()
    fun loadHistory() {
        if (mutationRunning) { refreshPending = true; return }
        val expected = _state.value.selection ?: return
        val request = ++readId
        readJob?.cancel(); update { it.copy(isLoading = true, actionsReady = false, errorMessage = operationNotice) }
        readJob = viewModelScope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    RepositoryOperationCoordinator.withRepository(expected.path) {
                        if (current(expected) && request == readId) readSnapshot(expected.path) else null
                    }
                }
                if (current(expected) && request == readId && fresh != null) publish(fresh)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (current(expected) && request == readId) update { it.copy(errorMessage = operationNotice ?: failure.message ?: "Could not refresh history") }
            } finally {
                if (current(expected) && request == readId && !mutationRunning) update { it.copy(isLoading = false) }
            }
        }
    }
    private suspend fun readSnapshot(path: String) = Snapshot(
        historyService.getHistory(path, maxCommits = 100).getOrThrow(),
        historyService.listStashes(path).getOrThrow(), historyService.listTags(path).getOrThrow()
    )
    private fun publish(fresh: Snapshot) {
        snapshot = fresh
        update { before -> before.copy(commits = fresh.commits, stashes = fresh.stashes, tags = fresh.tags, actionsReady = true,
            selectedCommit = before.selectedCommit?.takeIf { selected -> fresh.commits.any { it == selected } }) }
    }
    fun selectCommit(expected: RepositorySelection, commit: Commit) {
        if (available(expected) && _state.value.commits.any { it === commit }) update { it.copy(selectedCommit = commit) }
    }
    fun deselectCommit(expected: RepositorySelection) { if (current(expected)) update { it.copy(selectedCommit = null) } }
    fun revertCommit(expected: RepositorySelection, commit: Commit, strategy: RevertStrategy = RevertStrategy.CREATE_NEW_COMMIT) {
        if (_state.value.commits.none { it === commit }) return
        mutate(expected) { path, fresh ->
            check(fresh.commits.any { it == commit }) { "Commit changed. Refresh history and review again." }
            val result = historyService.revertCommit(path, commit.hash, strategy).getOrThrow()
            check(result.success) { result.message }
        }
    }
    fun stashSave(expected: RepositorySelection, message: String = "", onSuccess: () -> Unit = {}) = mutate(expected, onSuccess) { path, _ ->
        val result = historyService.stash(path, message.ifBlank { null }).getOrThrow()
        check(result.success) { result.message }
    }
    fun stashApply(expected: RepositorySelection, stash: Stash) = changeStash(expected, stash, historyService::stashApply)
    fun stashPop(expected: RepositorySelection, stash: Stash) = changeStash(expected, stash, historyService::stashPop)
    fun stashDrop(expected: RepositorySelection, stash: Stash) = changeStash(expected, stash, historyService::stashDrop)
    private fun changeStash(expected: RepositorySelection, stash: Stash, action: suspend (String, String) -> Result<Unit>) {
        if (_state.value.stashes.none { it === stash }) return
        mutate(expected) { path, fresh ->
            // stash@{n} is positional: a newly added/dropped entry must not retarget an old row.
            check(fresh.stashes.firstOrNull { it.id == stash.id } == stash) { "Stash list changed. Review the current stash before trying again." }
            action(path, stash.id).getOrThrow()
        }
    }
    fun createTag(expected: RepositorySelection, name: String, commitHash: String = "HEAD", annotated: Boolean = false, message: String? = null, onSuccess: () -> Unit = {}) {
        if (!available(expected)) return
        if (name.isBlank()) { operationNotice = "Tag name cannot be empty"; update { it.copy(errorMessage = operationNotice) }; return }
        mutate(expected, onSuccess) { path, fresh ->
            val target = if (commitHash == "HEAD") requireNotNull(fresh.head) { "Commit a file before creating a tag." } else commitHash
            historyService.createTag(path, name, target, annotated, message).getOrThrow()
        }
    }
    fun pushTag(expected: RepositorySelection, tag: Tag) = changeTag(expected, tag) { path -> pushService.pushTag(path, tag.name) }
    fun deleteTag(expected: RepositorySelection, tag: Tag) = changeTag(expected, tag) { path -> historyService.deleteTag(path, tag.name) }
    private fun changeTag(expected: RepositorySelection, tag: Tag, action: suspend (String) -> Result<Unit>) {
        if (_state.value.tags.none { it === tag }) return
        mutate(expected) { path, fresh ->
            check(fresh.tags.firstOrNull { it.name == tag.name } == tag) { "Tag changed. Review the current tag before trying again." }
            action(path).getOrThrow()
        }
    }
    private fun mutate(expected: RepositorySelection, onSuccess: () -> Unit = {}, action: suspend (String, Snapshot) -> Unit) {
        if (!available(expected)) return
        val reviewed = snapshot ?: return
        mutationRunning = true; refreshPending = false; readId++; readJob?.cancel()
        operationNotice = null; update { it.copy(isLoading = true, actionsReady = false, errorMessage = null) }
        viewModelScope.launch {
            var fresh: Snapshot? = null
            var failure: Exception? = null
            var completed = false
            try {
                withContext(Dispatchers.IO) {
                    RepositoryOperationCoordinator.withRepository(expected.path) {
                        if (!current(expected)) return@withRepository
                        try {
                            val before = readSnapshot(expected.path)
                            check(before.head == reviewed.head) { "Repository history changed. Refresh and review again." }
                            action(expected.path, before); completed = true
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) { failure = error }
                        try { fresh = readSnapshot(expected.path) }
                        catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) { if (failure == null) failure = error }
                    }
                }
                if (current(expected)) {
                    fresh?.let(::publish)
                    operationNotice = failure?.let { it.message ?: "Git could not complete this history action" }
                    update { it.copy(errorMessage = operationNotice) }
                    if (completed) { update { it.copy(selectedCommit = null) }; onSuccess() }
                    update { it.copy(operationRevision = it.operationRevision + 1) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (current(expected)) { operationNotice = error.message ?: "Git could not complete this history action"; update { it.copy(errorMessage = operationNotice) } }
            } finally {
                mutationRunning = false; update { it.copy(isLoading = false) }
                val refresh = !current(expected) || refreshPending; refreshPending = false
                if (refresh) loadHistory()
            }
        }
    }
    fun clearError(expected: RepositorySelection?) { if (current(expected)) { operationNotice = null; update { it.copy(errorMessage = null) } } }
}

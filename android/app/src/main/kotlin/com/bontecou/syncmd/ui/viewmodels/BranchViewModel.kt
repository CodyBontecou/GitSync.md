package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.data.models.BranchType
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.services.git.BranchService
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.ui.util.RepositorySelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Branch reviews and callbacks belong to one repository visit and one loaded Git snapshot. */
@HiltViewModel
class BranchViewModel @Inject constructor(private val branchService: BranchService) : ViewModel() {
    data class State(
        val repositoryPath: String? = null,
        val selection: RepositorySelection? = null,
        val currentBranch: Branch? = null,
        val branches: List<Branch> = emptyList(),
        val isLoading: Boolean = false,
        val isMerging: Boolean = false,
        val actionsReady: Boolean = false,
        val errorMessage: String? = null,
        val operationRevision: Long = 0,
    ) {
        val localBranches get() = branches.filter { it.type == BranchType.LOCAL || it.type == BranchType.TRACKING }
        val remoteBranches get() = branches.filter { it.type == BranchType.REMOTE }
    }
    private val _state = MutableStateFlow(State()); val state = _state.asStateFlow()
    private fun update(change: (State) -> State) { _state.value = change(_state.value) }
    private data class Snapshot(val current: Branch, val branches: List<Branch>)
    private var snapshot: Snapshot? = null
    private var readJob: Job? = null
    @Volatile private var readId = 0L
    private var mutationRunning = false
    private var refreshPending = false
    private var operationNotice: String? = null
    private fun current(expected: RepositorySelection?) = expected != null && _state.value.selection === expected
    private fun available(expected: RepositorySelection) = current(expected) && _state.value.actionsReady && !_state.value.isLoading && !mutationRunning

    fun setRepositoryPath(path: String) {
        if (_state.value.repositoryPath != path) {
            readId++; readJob?.cancel(); snapshot = null; operationNotice = null
            update { State(repositoryPath = path, selection = path.takeIf { it.isNotBlank() }?.let(::RepositorySelection),
                isLoading = mutationRunning, operationRevision = it.operationRevision) }
        }
        loadBranches()
    }
    fun loadBranches() {
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
                if (current(expected) && request == readId) update { it.copy(errorMessage = operationNotice ?: failure.message ?: "Could not refresh branches") }
            } finally {
                if (current(expected) && request == readId && !mutationRunning) update { it.copy(isLoading = false) }
            }
        }
    }
    private suspend fun readSnapshot(path: String): Snapshot {
        val before = branchService.getCurrentBranch(path).getOrThrow()
        val branches = branchService.listBranches(path).getOrThrow()
        check(before == branchService.getCurrentBranch(path).getOrThrow()) { "Current branch changed while loading. Refresh and review again." }
        return Snapshot(before, branches)
    }
    private fun publish(fresh: Snapshot) {
        snapshot = fresh
        update { it.copy(currentBranch = fresh.current, branches = fresh.branches, actionsReady = true) }
    }
    fun createBranch(expected: RepositorySelection, name: String, onSuccess: () -> Unit = {}) {
        if (!available(expected)) return
        if (name.isBlank()) { operationNotice = "Branch name cannot be empty"; update { it.copy(errorMessage = operationNotice) }; return }
        mutate(expected, onSuccess = onSuccess) { path, fresh ->
            val head = requireNotNull(fresh.current.lastCommit) { "Commit a file before creating a branch." }
            branchService.createBranch(path, name, head).getOrThrow(); null
        }
    }
    fun switchBranch(expected: RepositorySelection, branch: Branch) = changeBranch(expected, branch) { path ->
        branchService.switchBranch(path, branch.name).getOrThrow(); null
    }
    fun deleteBranch(expected: RepositorySelection, branch: Branch, force: Boolean = false) = changeBranch(expected, branch) { path ->
        branchService.deleteBranch(path, branch.name, force).getOrThrow(); null
    }
    fun mergeBranch(expected: RepositorySelection, branch: Branch, strategy: MergeStrategy = MergeStrategy.PREFER_FF) = changeBranch(expected, branch, merging = true) { path ->
        val result = branchService.merge(path, branch.name, strategy).getOrThrow()
        if (result.success) "Merge successful: ${result.message}"
        else "Merge stopped: ${result.message}" + if (result.conflictCount > 0) " (${result.conflictCount} conflicts)" else ""
    }
    private fun changeBranch(expected: RepositorySelection, branch: Branch, merging: Boolean = false, action: suspend (String) -> String?) {
        if (_state.value.localBranches.none { it === branch } || branch.isHead || branch.name == _state.value.currentBranch?.name) return
        mutate(expected, merging) { path, fresh ->
            check(fresh.branches.firstOrNull { it.type != BranchType.REMOTE && it.name == branch.name } == branch) {
                "Branch changed. Review the current branch before trying again."
            }
            action(path)
        }
    }
    private fun mutate(expected: RepositorySelection, merging: Boolean = false, onSuccess: () -> Unit = {}, action: suspend (String, Snapshot) -> String?) {
        if (!available(expected)) return
        val reviewed = snapshot ?: return
        mutationRunning = true; refreshPending = false; readId++; readJob?.cancel(); operationNotice = null
        update { it.copy(isLoading = true, isMerging = merging, actionsReady = false, errorMessage = null) }
        viewModelScope.launch {
            var fresh: Snapshot? = null
            var failure: Exception? = null
            var notice: String? = null
            var completed = false
            try {
                withContext(Dispatchers.IO) {
                    RepositoryOperationCoordinator.withRepository(expected.path) {
                        if (!current(expected)) return@withRepository
                        try {
                            val before = readSnapshot(expected.path)
                            check(before.current == reviewed.current) { "Current branch or commit changed. Refresh and review again." }
                            notice = action(expected.path, before); completed = true
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) { failure = error }
                        // A checkout/merge may have completed before LFS or refresh failed.
                        try { fresh = readSnapshot(expected.path) }
                        catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) { if (failure == null) failure = error }
                    }
                }
                if (current(expected)) {
                    fresh?.let(::publish)
                    operationNotice = failure?.let { it.message ?: "Branch action could not complete. Refresh repository status." } ?: notice
                    update { it.copy(errorMessage = operationNotice, operationRevision = it.operationRevision + 1) }
                    if (completed) onSuccess()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (current(expected)) { operationNotice = error.message ?: "Branch action could not complete"; update { it.copy(errorMessage = operationNotice) } }
            } finally {
                mutationRunning = false; update { it.copy(isLoading = false, isMerging = false) }
                val refresh = !current(expected) || refreshPending; refreshPending = false
                if (refresh) loadBranches()
            }
        }
    }
    fun clearError(expected: RepositorySelection?) { if (current(expected)) { operationNotice = null; update { it.copy(errorMessage = null) } } }
}

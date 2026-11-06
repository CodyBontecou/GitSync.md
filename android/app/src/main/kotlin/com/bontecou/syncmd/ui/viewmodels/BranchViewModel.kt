package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.data.models.BranchType
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.services.git.BranchService
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.ui.util.RepositorySelection
import com.bontecou.syncmd.ui.util.UiText
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
        val errorMessage: UiText? = null,
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
    private var operationNotice: UiText? = null
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
                if (current(expected) && request == readId) update { it.copy(errorMessage = operationNotice ?: failure.gitOpsText(R.string.gitops_branches_refresh_failed)) }
            } finally {
                if (current(expected) && request == readId && !mutationRunning) update { it.copy(isLoading = false) }
            }
        }
    }
    private suspend fun readSnapshot(path: String): Snapshot {
        val before = branchService.getCurrentBranch(path).getOrThrow()
        val branches = branchService.listBranches(path).getOrThrow()
        gitOpsCheck(before == branchService.getCurrentBranch(path).getOrThrow(), R.string.gitops_branch_changed_while_loading)
        return Snapshot(before, branches)
    }
    private fun publish(fresh: Snapshot) {
        snapshot = fresh
        update { it.copy(currentBranch = fresh.current, branches = fresh.branches, actionsReady = true) }
    }
    fun createBranch(expected: RepositorySelection, name: String, onSuccess: () -> Unit = {}) {
        if (!available(expected)) return
        if (name.isBlank()) { operationNotice = UiText.res(R.string.gitops_branch_name_empty); update { it.copy(errorMessage = operationNotice) }; return }
        mutate(expected, onSuccess = onSuccess) { path, fresh ->
            val head = fresh.current.lastCommit ?: gitOpsError(R.string.gitops_branch_needs_commit)
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
        if (result.success) UiText.res(R.string.gitops_merge_successful, result.message)
        else if (result.conflictCount > 0) UiText.plural(R.plurals.gitops_merge_stopped_conflicts, result.conflictCount, result.message, result.conflictCount)
        else UiText.res(R.string.gitops_merge_stopped, result.message)
    }
    private fun changeBranch(expected: RepositorySelection, branch: Branch, merging: Boolean = false, action: suspend (String) -> UiText?) {
        if (_state.value.localBranches.none { it === branch } || branch.isHead || branch.name == _state.value.currentBranch?.name) return
        mutate(expected, merging) { path, fresh ->
            gitOpsCheck(fresh.branches.firstOrNull { it.type != BranchType.REMOTE && it.name == branch.name } == branch, R.string.gitops_branch_changed)
            action(path)
        }
    }
    private fun mutate(expected: RepositorySelection, merging: Boolean = false, onSuccess: () -> Unit = {}, action: suspend (String, Snapshot) -> UiText?) {
        if (!available(expected)) return
        val reviewed = snapshot ?: return
        mutationRunning = true; refreshPending = false; readId++; readJob?.cancel(); operationNotice = null
        update { it.copy(isLoading = true, isMerging = merging, actionsReady = false, errorMessage = null) }
        viewModelScope.launch {
            var fresh: Snapshot? = null
            var failure: Exception? = null
            var notice: UiText? = null
            var completed = false
            try {
                withContext(Dispatchers.IO) {
                    RepositoryOperationCoordinator.withRepository(expected.path) {
                        if (!current(expected)) return@withRepository
                        try {
                            val before = readSnapshot(expected.path)
                            gitOpsCheck(before.current == reviewed.current, R.string.gitops_branch_or_commit_changed)
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
                    operationNotice = failure?.gitOpsText(R.string.gitops_branch_action_failed_refresh) ?: notice
                    update { it.copy(errorMessage = operationNotice, operationRevision = it.operationRevision + 1) }
                    if (completed) onSuccess()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (current(expected)) { operationNotice = error.gitOpsText(R.string.gitops_branch_action_failed); update { it.copy(errorMessage = operationNotice) } }
            } finally {
                mutationRunning = false; update { it.copy(isLoading = false, isMerging = false) }
                val refresh = !current(expected) || refreshPending; refreshPending = false
                if (refresh) loadBranches()
            }
        }
    }
    fun clearError(expected: RepositorySelection?) { if (current(expected)) { operationNotice = null; update { it.copy(errorMessage = null) } } }
}

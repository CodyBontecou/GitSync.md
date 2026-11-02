package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.data.models.*
import com.bontecou.syncmd.services.git.ConflictService
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.lfs.LfsCheckoutIncompleteException
import com.bontecou.syncmd.ui.util.RepositorySelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** One coherent conflict snapshot and editor, scoped to the selection that produced their callbacks. */
@HiltViewModel
class ConflictViewModel @Inject constructor(private val conflictService: ConflictService) : ViewModel() {
    private val _conflicts = MutableStateFlow<List<Conflict>>(emptyList())
    val conflicts = _conflicts.asStateFlow()
    private val _mergeState = MutableStateFlow<MergeState?>(null)
    val mergeState = _mergeState.asStateFlow()
    private val _selectedConflict = MutableStateFlow<Conflict?>(null)
    val selectedConflict = _selectedConflict.asStateFlow()
    private val _manualResolutionContent = MutableStateFlow("")
    val manualResolutionContent = _manualResolutionContent.asStateFlow()
    private val _resolutionPath = MutableStateFlow<String?>(null)
    val resolutionPath = _resolutionPath.asStateFlow()
    private val _resolvedConflicts = MutableStateFlow<Set<String>>(emptySet())
    val resolvedConflicts = _resolvedConflicts.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    private val _actionsReady = MutableStateFlow(false)
    val actionsReady = _actionsReady.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()
    private val _currentRepoPath = MutableStateFlow<String?>(null)
    val currentRepoPath = _currentRepoPath.asStateFlow()
    private val _selection = MutableStateFlow<RepositorySelection?>(null)
    val selection = _selection.asStateFlow()
    private var readJob: Job? = null
    @Volatile private var readId = 0L
    private var mutationRunning = false
    private var refreshPending = false
    private var operationNotice: String? = null
    private data class Snapshot(val merge: MergeState, val conflicts: List<Conflict>)
    private fun current(expected: RepositorySelection?) = expected != null && _selection.value === expected
    private fun ready(expected: RepositorySelection) = current(expected) && _actionsReady.value && !mutationRunning && !_isLoading.value
    private fun clearEditor() { _selectedConflict.value = null; _manualResolutionContent.value = ""; _resolutionPath.value = null }

    fun setRepositoryPath(path: String) {
        if (_currentRepoPath.value != path) {
            readId++; readJob?.cancel()
            _currentRepoPath.value = path
            _selection.value = path.takeIf { it.isNotBlank() }?.let(::RepositorySelection)
            _conflicts.value = emptyList(); _mergeState.value = null; _resolvedConflicts.value = emptySet()
            clearEditor(); operationNotice = null; _errorMessage.value = null; _actionsReady.value = false; _isLoading.value = mutationRunning
            refreshPending = false
        }
        loadConflicts()
    }
    private suspend fun snapshot(path: String): Snapshot {
        val state = conflictService.getMergeState(path).getOrThrow()
        val conflicts = conflictService.getConflicts(path).getOrThrow()
        return Snapshot(state.copy(conflicts = conflicts), conflicts)
    }
    private fun publish(value: Snapshot) {
        _mergeState.value = value.merge; _conflicts.value = value.conflicts
        _resolvedConflicts.value = _resolvedConflicts.value - value.conflicts.map { it.filePath }.toSet()
        _actionsReady.value = true
    }
    fun loadConflicts() {
        if (mutationRunning || _selectedConflict.value != null) { refreshPending = true; return }
        val expected = _selection.value ?: return
        val request = ++readId
        readJob?.cancel(); _isLoading.value = true; _actionsReady.value = false; _errorMessage.value = operationNotice
        readJob = viewModelScope.launch {
            try {
                val value = withContext(Dispatchers.IO) {
                    RepositoryOperationCoordinator.withRepository(expected.path) {
                        if (!current(expected) || request != readId) null else snapshot(expected.path)
                    }
                }
                if (current(expected) && request == readId && value != null) publish(value)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (current(expected) && request == readId) _errorMessage.value = operationNotice ?: failure.message ?: "Could not refresh merge conflicts"
            } finally {
                if (current(expected) && request == readId && !mutationRunning) _isLoading.value = false
            }
        }
    }
    private fun visible(conflict: Conflict) = _conflicts.value.any { it === conflict } ||
        (_selectedConflict.value === conflict && _conflicts.value.any { it == conflict })
    fun selectConflict(expected: RepositorySelection, conflict: Conflict) {
        if (!ready(expected) || !visible(conflict) || conflict.isBinaryOrTooLarge) return
        _selectedConflict.value = conflict; _manualResolutionContent.value = conflict.currentContent
        _resolutionPath.value = conflict.ours?.path ?: conflict.filePath
    }
    fun deselectConflict(expected: RepositorySelection, conflict: Conflict) {
        if (!current(expected) || mutationRunning || _selectedConflict.value !== conflict) return
        clearEditor(); operationNotice = null; _errorMessage.value = null; refreshPending = false; loadConflicts()
    }
    fun setManualResolutionContent(expected: RepositorySelection, conflict: Conflict, content: String) {
        if (current(expected) && !mutationRunning && _selectedConflict.value === conflict) _manualResolutionContent.value = content
    }
    fun setResolutionPath(expected: RepositorySelection, conflict: Conflict, path: String) {
        if (current(expected) && !mutationRunning && _selectedConflict.value === conflict &&
            conflict.isRenameRename && path in listOfNotNull(conflict.ours?.path, conflict.theirs?.path)) _resolutionPath.value = path
    }
    fun acceptOurs(expected: RepositorySelection, conflict: Conflict) = resolve(expected, conflict, ConflictResolutionStrategy.OURS)
    fun acceptTheirs(expected: RepositorySelection, conflict: Conflict) = resolve(expected, conflict, ConflictResolutionStrategy.THEIRS)
    fun acceptManual(expected: RepositorySelection, conflict: Conflict) {
        if (_selectedConflict.value !== conflict) return
        resolve(expected, conflict, ConflictResolutionStrategy.MANUAL, _manualResolutionContent.value, _resolutionPath.value)
    }
    private fun resolve(expected: RepositorySelection, conflict: Conflict, strategy: ConflictResolutionStrategy, draft: String? = null, keepPath: String? = null) {
        if (!ready(expected) || !visible(conflict)) return
        mutate(expected, onSuccess = { clearEditor(); _resolvedConflicts.value += conflict.filePath }) { path ->
            check(conflictService.getFileConflict(path, conflict.filePath).getOrThrow() == conflict) {
                "Conflict changed. Your draft is still shown. Copy any text you want to keep, then close and review the current conflict."
            }
            val resolution = if (strategy == ConflictResolutionStrategy.MANUAL && conflict.isRenameRename)
                conflictService.resolveConflictWithPath(path, conflict.filePath, requireNotNull(keepPath), requireNotNull(draft)).getOrThrow()
            else conflictService.resolveConflict(path, conflict.filePath, strategy, draft).getOrThrow()
            check(resolution.resolved) { resolution.message.ifBlank { "Conflict could not be resolved" } }
            resolution.message.takeIf { it.isNotBlank() }
        }
    }
    fun resolveAllConflicts(expected: RepositorySelection, strategy: ConflictResolutionStrategy) {
        if (!ready(expected) || _selectedConflict.value != null) return
        val before = _conflicts.value
        if (before.isEmpty()) return
        mutate(expected) { path ->
            require(strategy == ConflictResolutionStrategy.OURS || strategy == ConflictResolutionStrategy.THEIRS) { "Choose ours or theirs for a batch resolution" }
            check(conflictService.getConflicts(path).getOrThrow() == before) { "Conflicts changed. Refresh before resolving." }
            val results = conflictService.resolveAllConflicts(path, strategy).getOrThrow()
            check(results.size == before.size && results.all { it.resolved }) { "Some conflicts remain unresolved. Review the refreshed conflict list." }
            results.map { it.message }.filter { it.isNotBlank() }.distinct().joinToString("\n\n").takeIf { it.isNotEmpty() }
        }
    }
    fun completeMerge(expected: RepositorySelection, message: String) {
        if (!ready(expected) || _conflicts.value.isNotEmpty()) return
        if (message.isBlank()) { _errorMessage.value = "Merge message cannot be empty"; return }
        val before = _mergeState.value ?: return
        mutate(expected, onSuccess = { clearEditor(); _resolvedConflicts.value = emptySet() }) { path ->
            verifyMerge(path, before)
            val result = conflictService.completeMerge(path, message)
            if (result.exceptionOrNull() is LfsCheckoutIncompleteException) result.exceptionOrNull()?.message else { result.getOrThrow(); null }
        }
    }
    fun abortMerge(expected: RepositorySelection) {
        if (!ready(expected)) return
        val before = _mergeState.value ?: return
        mutate(expected, onSuccess = { clearEditor(); _resolvedConflicts.value = emptySet() }) { path ->
            verifyMerge(path, before)
            val result = conflictService.abortMerge(path)
            if (result.exceptionOrNull() is LfsCheckoutIncompleteException) result.exceptionOrNull()?.message else { result.getOrThrow(); null }
        }
    }
    private suspend fun verifyMerge(path: String, before: MergeState) {
        val fresh = conflictService.getMergeState(path).getOrThrow()
        check(before.isMergeInProgress && fresh.isMergeInProgress && fresh.sourceBranch == before.sourceBranch) { "Merge session changed. Refresh before continuing." }
    }
    private fun mutate(expected: RepositorySelection, onSuccess: () -> Unit = {}, action: suspend (String) -> String?) {
        if (!ready(expected)) return
        mutationRunning = true; refreshPending = false
        readId++; readJob?.cancel(); _isLoading.value = true; _actionsReady.value = false; operationNotice = null; _errorMessage.value = null
        viewModelScope.launch {
            var updated: Snapshot? = null
            var failure: Exception? = null
            var notice: String? = null
            var succeeded = false
            try {
                withContext(Dispatchers.IO) {
                    RepositoryOperationCoordinator.withRepository(expected.path) {
                        if (!current(expected)) return@withRepository
                        try { notice = action(expected.path); succeeded = true }
                        catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) { failure = error }
                        try { updated = snapshot(expected.path) }
                        catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) { if (failure == null) failure = error }
                    }
                }
                if (current(expected)) {
                    if (succeeded) onSuccess()
                    updated?.let(::publish)
                    operationNotice = failure?.let { it.message ?: "Git could not complete this action" } ?: notice
                    _errorMessage.value = operationNotice
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (current(expected)) { operationNotice = error.message ?: "Git could not complete this action"; _errorMessage.value = operationNotice }
            } finally {
                mutationRunning = false; _isLoading.value = false
                val refresh = !current(expected) || refreshPending
                refreshPending = false
                if (refresh) loadConflicts()
            }
        }
    }
    fun clearError(expected: RepositorySelection?) { if (current(expected)) { operationNotice = null; _errorMessage.value = null } }
}

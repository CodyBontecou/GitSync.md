package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.RepositoryStatus
import com.bontecou.syncmd.data.models.SafePullPlan
import com.bontecou.syncmd.services.git.PullService
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.ui.util.RepositorySelection
import com.bontecou.syncmd.ui.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Read requests and mutation callbacks belong to one immutable repository selection. */
@HiltViewModel
class PullViewModel @Inject constructor(private val pullService: PullService) : ViewModel() {
    private val _status = MutableStateFlow<RepositoryStatus?>(null)
    val status = _status.asStateFlow()
    private val _pullPlan = MutableStateFlow<SafePullPlan?>(null)
    val pullPlan = _pullPlan.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    /** English/raw text of the current error; screens display [errorText]. */
    val errorMessage = _errorMessage.asStateFlow()
    private val _errorText = MutableStateFlow<UiText?>(null)
    /** The same error as [errorMessage], with app-authored copy resolved in the display locale. */
    val errorText = _errorText.asStateFlow()
    private val _attention = MutableStateFlow<String?>(null)
    val attention = _attention.asStateFlow()
    /** Typed so the UI can offer iOS's commit-then-merge resolution instead of a raw error. */
    private val _localChangesBlock = MutableStateFlow(false)
    val localChangesBlock = _localChangesBlock.asStateFlow()
    private val _currentRepoPath = MutableStateFlow<String?>(null)
    val currentRepoPath = _currentRepoPath.asStateFlow()
    private val _selection = MutableStateFlow<RepositorySelection?>(null)
    val selection = _selection.asStateFlow()
    private var readJob: Job? = null
    @Volatile private var readId = 0L
    private var mutationRunning = false
    private var refreshPending = false
    private class Notice(val english: String, val text: UiText)
    private var operationNotice: Notice? = null
    private fun showError(notice: Notice?) { _errorMessage.value = notice?.english; _errorText.value = notice?.text }
    private fun Throwable.notice(@androidx.annotation.StringRes fallback: Int, english: String) =
        Notice(message ?: english, gitOpsText(fallback))

    private fun current(expected: RepositorySelection?) = expected != null && _selection.value === expected
    fun setRepositoryPath(path: String) {
        if (_currentRepoPath.value != path) {
            readId++; readJob?.cancel()
            _currentRepoPath.value = path
            _selection.value = path.takeIf { it.isNotBlank() }?.let(::RepositorySelection)
            _status.value = null; _pullPlan.value = null; _attention.value = null; _localChangesBlock.value = false; operationNotice = null; showError(null)
            _isLoading.value = mutationRunning
        }
        loadStatus()
    }
    fun loadStatus() {
        if (mutationRunning) { refreshPending = true; return }
        _pullPlan.value = null
        read({ pullService.getStatus(it).getOrThrow() }) { _status.value = it }
    }
    fun planPull(expected: RepositorySelection) {
        if (!current(expected) || mutationRunning) return
        _pullPlan.value = null
        read({ pullService.planPull(it).getOrThrow() }) { _pullPlan.value = it }
    }
    private fun <T : Any> read(action: suspend (String) -> T, publish: (T) -> Unit) {
        val expected = _selection.value ?: return
        val request = ++readId
        readJob?.cancel(); _isLoading.value = true; showError(operationNotice)
        readJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    RepositoryOperationCoordinator.withRepository(expected.path) {
                        if (!current(expected) || request != readId) null else action(expected.path)
                    }
                }
                if (current(expected) && request == readId && result != null) publish(result)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (current(expected) && request == readId) showError(operationNotice ?: failure.notice(R.string.gitops_status_refresh_failed, "Could not refresh repository status"))
            } finally {
                if (current(expected) && request == readId && !mutationRunning) _isLoading.value = false
            }
        }
    }
    fun executePull(expected: RepositorySelection) = mutate(expected, pull = true)
    fun fetch(expected: RepositorySelection) = mutate(expected, pull = false)
    private fun mutate(expected: RepositorySelection, pull: Boolean) {
        if (!current(expected) || mutationRunning) return
        mutationRunning = true; refreshPending = false
        readId++; readJob?.cancel()
        _isLoading.value = true; operationNotice = null; showError(null); _pullPlan.value = null
        if (pull) { _attention.value = null; _localChangesBlock.value = false }
        viewModelScope.launch {
            var updated: RepositoryStatus? = null
            var attention: String? = null
            var failure: Exception? = null
            try {
                withContext(Dispatchers.IO) {
                    RepositoryOperationCoordinator.withRepository(expected.path) {
                        // A queued operation is no longer authorized after switching away.
                        if (!current(expected)) return@withRepository
                        try {
                            if (pull) {
                                val result = pullService.pull(expected.path).getOrThrow()
                                if (!result.success) {
                                    result.message.takeIf { it.isNotBlank() }?.let { throw IllegalStateException(it) }
                                    gitOpsError(R.string.gitops_pull_incomplete, "Pull did not complete")
                                }
                                attention = result.attention
                            } else pullService.fetch(expected.path).getOrThrow()
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) { failure = error }
                        try { updated = pullService.getStatus(expected.path).getOrThrow() }
                        catch (cancelled: CancellationException) { throw cancelled
                        } catch (error: Exception) { if (failure == null) failure = error }
                    }
                }
                if (current(expected)) {
                    updated?.let { _status.value = it }
                    if (pull) _attention.value = attention
                    val blocked = pull && failure is com.bontecou.syncmd.data.models.DirtyRepoException
                    _localChangesBlock.value = blocked
                    operationNotice = failure?.takeUnless { blocked }?.notice(R.string.gitops_action_failed, "Git could not complete this action")
                    showError(operationNotice)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if (current(expected)) { operationNotice = error.notice(R.string.gitops_action_failed, "Git could not complete this action"); showError(operationNotice) }
            } finally {
                mutationRunning = false; _isLoading.value = false
                val refresh = !current(expected) || refreshPending
                refreshPending = false
                if (refresh) loadStatus()
            }
        }
    }
    fun clearError(expected: RepositorySelection?) { if (current(expected)) { operationNotice = null; showError(null) } }
    fun clearAttention(expected: RepositorySelection?) { if (current(expected)) _attention.value = null }
    fun clearLocalChangesBlock(expected: RepositorySelection?) { if (current(expected)) _localChangesBlock.value = false }
}

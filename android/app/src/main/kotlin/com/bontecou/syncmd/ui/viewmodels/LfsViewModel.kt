package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.lfs.*
import com.bontecou.syncmd.ui.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LfsViewModel @Inject constructor(private val repositories: LfsRepository) : ViewModel() {
    data class State(val busy: Boolean = false, val files: List<LfsFileInfo> = emptyList(), val plan: LfsTrackingPlan? = null,
        val error: UiText? = null, val notice: LfsNotice? = null, val locks: LfsLockReview? = null, val blockBack: Boolean = false,
        val extensionReview: LfsExtensionReview? = null, val refreshRequired: Boolean = false)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val tracking = LfsTracking()
    private val path = MutableStateFlow<String?>(null)
    /** Live progress of "Download missing contents" for this screen's vault (null when nothing is downloading). */
    val download: kotlinx.coroutines.flow.StateFlow<LfsDownloadProgress?> = kotlinx.coroutines.flow.combine(path, LfsDownloads.progress) { current, all -> current?.let { all[it] } }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    private var currentPath: String? = null
        set(value) { field = value; path.value = value }
    private var generation = 0L
    @Volatile private var requestOwner: Any = Any()
    fun load(path: String) {
        // This is an explicit screen entry/reload, not a periodic observation refresh.
        // Even a same-folder re-entry supersedes a mutation still waiting for its lease.
        requestOwner = Any()
        currentPath = path
        val request = ++generation
        val owner = requestOwner
        _state.value = State(busy = true)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    withCurrentRepository(path, owner) {
                        // Publish files and tracking choices from the same serialized repository state.
                        State(files = repositories.inspect(path), plan = tracking.plan(path))
                    }
                } ?: return@launch
                if (request == generation) _state.value = result
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { if (request == generation) _state.value = State(error = UiText.res(R.string.gitops_lfs_inspect_failed)) }
        }
    }
    override fun onCleared() {
        requestOwner = Any()
        generation++
        super.onCleared()
    }

    private suspend fun <T> withCurrentRepository(path: String, owner: Any, action: suspend () -> T): T? =
        RepositoryOperationCoordinator.withRepository(path) {
            // Check after acquiring the lease: a screen can change while a request waits.
            if (requestOwner !== owner) return@withRepository null
            action()
        }
    fun clearError() { _state.value = _state.value.copy(error = null) }
    fun checkLocks(verifyOwnership: Boolean = true) = lockAction(false) { path ->
        val review = repositories.reviewLocks(path, verifyOwnership)
        LfsLockChange(review, "")
    }
    fun createLock(review: LfsLockReview, file: String) = changeLock(review) { repositories.createReviewedLock(review, file) }
    fun releaseLock(review: LfsLockReview, lock: LfsLock, force: Boolean) = changeLock(review) { repositories.releaseReviewedLock(review, lock, force) }
    private fun changeLock(review: LfsLockReview, action: suspend () -> LfsLockChange) {
        if (_state.value.busy || _state.value.refreshRequired) return
        if (_state.value.locks != review) { _state.value = _state.value.copy(locks = null, error = UiText.of(LfsLockReviewChanged(), R.string.gitops_lfs_locks_check_failed)); return }
        lockAction(true) { action() }
    }
    private fun lockAction(write: Boolean, action: suspend (String) -> LfsLockChange) {
        if (_state.value.busy || _state.value.refreshRequired) return
        val path = currentPath ?: return
        val before = _state.value
        val request = generation
        val owner = requestOwner
        _state.value = before.copy(busy = true, blockBack = write, error = null, notice = null, locks = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO + if (write) kotlinx.coroutines.NonCancellable else kotlin.coroutines.EmptyCoroutineContext) {
                    withCurrentRepository(path, owner) { action(path) }
                } ?: return@launch
                if (request == generation) _state.value = before.copy(locks = result.review, notice = if (write) LfsNotice.Plain(result.notice) else LfsNotice.LockListRefreshed(result.review?.supported == true), error = null)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                val safe = when (e) {
                    is LfsLockReviewChanged, is LfsLockManagementException -> UiText.of(e, R.string.gitops_lfs_locks_check_failed)
                    is LfsEndpointReviewCancelled, is LfsEndpointApprovalFailed -> UiText.of(e, R.string.gitops_lfs_locks_check_failed)
                    is LfsHttpStatusException -> UiText.res(if (e.status in listOf(401,403)) R.string.gitops_lfs_locks_denied else R.string.gitops_lfs_locks_check_failed)
                    else -> UiText.res(R.string.gitops_lfs_locks_unavailable)
                }
                if (request == generation) _state.value = before.copy(locks = null, error = safe)
            } finally { if (request == generation) _state.value = _state.value.copy(busy = false, blockBack = false) }
        }
    }
    fun hydrate() = perform {
        val result = repositories.hydrate(it)
        LfsNotice.Hydrated(result.hydrated, result.downloaded)
    }
    fun reviewExtensions(selected: Set<String>) {
        if (_state.value.busy || _state.value.refreshRequired) return
        val before = _state.value
        val plan = before.plan ?: return
        val choice = selected.toSet()
        val request = generation
        val owner = requestOwner
        _state.value = before.copy(busy = true, blockBack = false, error = null, extensionReview = null)
        viewModelScope.launch {
            try {
                val review = withContext(Dispatchers.IO) {
                    withCurrentRepository(plan.path, owner) { tracking.reviewExtensions(plan, choice) }
                } ?: return@launch
                if (request == generation) _state.value = before.copy(extensionReview = review, error = null)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (request == generation) _state.value = before.copy(extensionReview = null, error = if (e is LfsExtensionReviewChanged) UiText.of(e, R.string.gitops_lfs_extensions_review_failed) else
                    UiText.res(R.string.gitops_lfs_extensions_review_failed))
            } finally { if (request == generation) _state.value = _state.value.copy(busy = false, blockBack = false) }
        }
    }
    fun dismissExtensionReview() { if (!_state.value.busy) _state.value = _state.value.copy(extensionReview = null) }
    fun applyExtensions(review: LfsExtensionReview) {
        if (_state.value.busy || _state.value.refreshRequired) return
        if (_state.value.extensionReview != review) { _state.value = _state.value.copy(extensionReview = null, error = UiText.of(LfsExtensionReviewChanged(), R.string.gitops_lfs_extensions_review_failed)); return }
        _state.value = _state.value.copy(extensionReview = null)
        perform {
            tracking.applyExtensions(review)
            LfsNotice.ExtensionsUpdated
        }
    }
    fun decide(selected: Set<String>, useLfs: Boolean, expectedPlan: LfsTrackingPlan? = null) {
        if (_state.value.busy || _state.value.refreshRequired) return
        val plan = _state.value.plan ?: return
        if (expectedPlan != null && expectedPlan != plan) { _state.value = _state.value.copy(error = UiText.of(LfsExtensionReviewChanged(), R.string.gitops_lfs_extensions_review_failed)); return }
        val choice = selected.toSet()
        perform {
            tracking.decide(plan, choice, useLfs)
            LfsNotice.TrackingUpdated
        }
    }
    private fun perform(action: suspend (String) -> LfsNotice) {
        if (_state.value.busy || _state.value.refreshRequired) return
        val path = currentPath ?: return
        val before = _state.value.copy(locks = null, extensionReview = null)
        val request = generation
        val owner = requestOwner
        _state.value = before.copy(busy = true, blockBack = true, error = null, notice = null)
        viewModelScope.launch {
            try {
                val next = withContext(Dispatchers.IO) {
                    withCurrentRepository(path, owner) {
                        val notice = action(path)
                        // The write is confirmed. A subsequent read failure must not turn it into
                        // an action failure or leave its pre-write plan available for another choice.
                        try {
                            State(files = repositories.inspect(path), plan = tracking.plan(path), notice = notice)
                        } catch (e: CancellationException) { throw e
                        } catch (_: Exception) {
                            State(notice = notice, refreshRequired = true)
                        }
                    }
                } ?: return@launch
                if (request == generation) _state.value = next
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (request == generation) _state.value = if (e is LfsEndpointReviewCancelled || e is LfsEndpointApprovalFailed) before.copy(notice = e.message?.let { LfsNotice.Plain(it) })
                    else before.copy(error = if (e is LfsWorktreeChangedException || e is LfsEndpointApprovalRequired || e is LfsExtensionReviewChanged) UiText.of(e, R.string.gitops_lfs_failed) else
                        UiText.res(R.string.gitops_lfs_failed))
            } finally { if (request == generation) _state.value = _state.value.copy(busy = false, blockBack = false) }
        }
    }
}

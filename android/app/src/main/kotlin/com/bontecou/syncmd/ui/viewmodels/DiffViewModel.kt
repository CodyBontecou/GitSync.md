package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.data.models.PushConfig
import com.bontecou.syncmd.data.models.UnifiedDiffResult
import com.bontecou.syncmd.services.git.BranchService
import com.bontecou.syncmd.services.git.DiffService
import com.bontecou.syncmd.services.git.DiscardReview
import com.bontecou.syncmd.services.git.PushService
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Owns one repository's draft and prevents stale reads or duplicate mutation submissions. */
@HiltViewModel
class DiffViewModel @Inject constructor(
    private val diffService: DiffService,
    private val pushService: PushService,
    private val branchService: BranchService,
    private val authors: com.bontecou.syncmd.security.RepositoryAuthors,
) : ViewModel() {
    private val _diff = MutableStateFlow<UnifiedDiffResult?>(null)
    val diff = _diff.asStateFlow()
    private val _selectedFilePath = MutableStateFlow<String?>(null)
    val selectedFilePath = _selectedFilePath.asStateFlow()
    private val _selectedFileDiff = MutableStateFlow<UnifiedDiffResult?>(null)
    val selectedFileDiff = _selectedFileDiff.asStateFlow()
    private val _commitMessage = MutableStateFlow("")
    val commitMessage = _commitMessage.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    private val _isPushing = MutableStateFlow(false)
    val isPushing = _isPushing.asStateFlow()
    private val _isCommitting = MutableStateFlow(false)
    val isCommitting = _isCommitting.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()
    private val _lockedPaths = MutableStateFlow<List<String>?>(null)
    val lockedPaths = _lockedPaths.asStateFlow()
    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage = _operationMessage.asStateFlow()
    private val _currentRepoPath = MutableStateFlow<String?>(null)
    val currentRepoPath = _currentRepoPath.asStateFlow()
    @Volatile private var generation = 0L
    private val _operationRevision = MutableStateFlow(0L)
    val operationRevision = _operationRevision.asStateFlow()
    private var readVersion = 0L
    private var loadJob: Job? = null
    private var fileJob: Job? = null
    private var selectedStage: Boolean? = null
    private var mutationRunning = false
    private val _discardReview = MutableStateFlow<DiscardReview?>(null)
    val discardReview = _discardReview.asStateFlow()
    private val _isReviewingDiscard = MutableStateFlow(false)
    val isReviewingDiscard = _isReviewingDiscard.asStateFlow()
    private val _isDiscarding = MutableStateFlow(false)
    val isDiscarding = _isDiscarding.asStateFlow()
    private var discardRequest = 0L
    private var discardJob: Job? = null

    fun setRepositoryPath(repoPath: String) {
        // Re-entering the same repository also changes the visible request owner.
        generation++
        dismissDiscard()
        if (_currentRepoPath.value != repoPath) {
            fileJob?.cancel()
            _currentRepoPath.value = repoPath
            _commitMessage.value = ""
            _diff.value = null
            clearSelection()
            _errorMessage.value = null
            _lockedPaths.value = null
            _operationMessage.value = null
        }
        loadDiff()
    }

    fun loadDiff() {
        if (mutationRunning) return
        val path = _currentRepoPath.value ?: return
        val version = ++readVersion
        val requestGeneration = generation
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = diffService.getDiff(path)
                if (generation == requestGeneration && readVersion == version) {
                    result.onSuccess { _diff.value = it }.onFailure { _lockedPaths.value = null; _errorMessage.value = it.message ?: "Failed to load changes" }
                }
            } catch (e: CancellationException) { throw e
            } finally {
                if (readVersion == version && !mutationRunning) _isLoading.value = false
            }
        }
    }

    /** Periodic observation reads preserve the frozen review and never replace an active mutation's owner. */
    fun refreshRepositoryPath(repoPath: String) {
        if (_currentRepoPath.value != repoPath) setRepositoryPath(repoPath)
        else if (!mutationRunning) loadDiff()
    }

    fun selectFile(filePath: String, staged: Boolean? = null) {
        val path = _currentRepoPath.value ?: return
        val requestGeneration = generation
        fileJob?.cancel()
        _selectedFilePath.value = filePath
        selectedStage = staged
        _selectedFileDiff.value = null
        fileJob = viewModelScope.launch {
            val result = diffService.getFileDiff(path, filePath, staged)
            if (generation == requestGeneration && _selectedFilePath.value == filePath) {
                result.onSuccess { _selectedFileDiff.value = it }.onFailure { _errorMessage.value = it.message ?: "Failed to load file diff" }
            }
        }
    }

    private fun clearSelection() {
        fileJob?.cancel()
        _selectedFilePath.value = null
        _selectedFileDiff.value = null
        selectedStage = null
    }

    /** Gate is set synchronously, before launch, so rapid taps cannot enqueue duplicate commits. */
    private fun mutate(push: Boolean = false, commit: Boolean = false, discard: Boolean = false, onComplete: () -> Unit = {}, action: suspend (String) -> Unit) {
        val path = _currentRepoPath.value ?: return
        if (mutationRunning) return
        dismissDiscard()
        mutationRunning = true
        val requestGeneration = generation
        val selectedPath = _selectedFilePath.value
        val selectedLayer = selectedStage
        readVersion++
        loadJob?.cancel()
        fileJob?.cancel()
        _isLoading.value = true
        _isPushing.value = push
        _isCommitting.value = commit
        _isDiscarding.value = discard
        _errorMessage.value = null
        _lockedPaths.value = null
        _operationMessage.value = null
        viewModelScope.launch {
            var refreshed: Result<UnifiedDiffResult>? = null
            var refreshedFile: Result<UnifiedDiffResult>? = null
            try {
                try {
                    RepositoryOperationCoordinator.withRepository(path) {
                        check(generation == requestGeneration && _currentRepoPath.value == path) {
                            "The selected repository changed. Review the operation again."
                        }
                        try { action(path) } finally {
                            // Keep the refresh under the same lease, including failed pushes.
                            refreshed = diffService.getDiff(path)
                            if (selectedPath != null) refreshedFile = diffService.getFileDiff(path, selectedPath, selectedLayer)
                        }
                    }
                } finally {
                    // Publish on the ViewModel's main context, after returning from repository I/O.
                    if (generation == requestGeneration) {
                        refreshed?.onSuccess { _diff.value = it }
                        if (_selectedFilePath.value == selectedPath && selectedStage == selectedLayer) {
                            refreshedFile?.onSuccess { _selectedFileDiff.value = it }
                        }
                    }
                }
                if (generation == requestGeneration) { clearSelection(); onComplete() }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (generation == requestGeneration) {
                    _errorMessage.value = e.message ?: "Git operation failed"
                    _lockedPaths.value = (e as? com.bontecou.syncmd.services.git.lfs.LfsLockedFilesException)?.paths?.toList()
                }
            } finally {
                mutationRunning = false
                _isLoading.value = false
                _isPushing.value = false
                _isCommitting.value = false
                _isDiscarding.value = false
                if (generation != requestGeneration) loadDiff()
                else _operationRevision.value++
            }
        }
    }

    fun stageFile(filePath: String) = mutate { diffService.stageFile(it, filePath).getOrThrow() }
    fun unstageFile(filePath: String) = mutate { diffService.unstageFile(it, filePath).getOrThrow() }
    fun stageAll() = mutate { diffService.stageAll(it).getOrThrow() }
    fun unstageAll() = mutate { diffService.unstageAll(it).getOrThrow() }
    fun setCommitMessage(message: String) { _commitMessage.value = message }

    private fun commit(push: Boolean, branch: String = "", remote: String = "origin") {
        if (mutationRunning) return
        val message = _commitMessage.value
        if (message.isBlank()) { _errorMessage.value = "Commit message cannot be empty"; return }
        val requestGeneration = generation
        mutate(push = push, commit = true) { path ->
            if (push) check(branchService.getCurrentBranch(path).getOrThrow().name == branch) { "The current branch changed. Refresh before committing and pushing." }
            val author = authors.resolve(path)
            diffService.commit(path, message, author.first, author.second).getOrThrow()
            withContext(Dispatchers.Main.immediate) {
                if (generation == requestGeneration) {
                    if (_commitMessage.value == message) _commitMessage.value = ""
                    _operationMessage.value = if (push) "Committed locally. Pushing…" else "Committed locally. Ready to push when you choose."
                }
            }
            if (push) {
                try { pushService.push(path, PushConfig(branch = branch, remote = remote)).getOrThrow() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    withContext(Dispatchers.Main.immediate) {
                        if (generation == requestGeneration) _operationMessage.value = "Committed locally. Push stopped; retry when ready."
                    }
                    throw e
                }
                withContext(Dispatchers.Main.immediate) { if (generation == requestGeneration) _operationMessage.value = "Committed and pushed" }
            }
        }
    }

    fun commitOnly() = commit(push = false)
    fun commitAndPush(branch: String, remote: String = "origin") = commit(push = true, branch = branch, remote = remote)
    fun pushCurrentBranch(branch: String, remote: String = "origin") {
        val requestGeneration = generation
        mutate(push = true) { path ->
            check(branchService.getCurrentBranch(path).getOrThrow().name == branch) { "The current branch changed. Refresh before pushing." }
            pushService.push(path, PushConfig(branch = branch, remote = remote)).getOrThrow()
            withContext(Dispatchers.Main.immediate) { if (generation == requestGeneration) _operationMessage.value = "Branch pushed" }
        }
    }
    fun requestDiscard(filePath: String? = null) {
        if (mutationRunning) return
        val path = _currentRepoPath.value ?: return
        dismissDiscard()
        val request = discardRequest
        val owner = generation
        _isReviewingDiscard.value = true
        _errorMessage.value = null
        discardJob = viewModelScope.launch {
            try {
                val result = diffService.reviewDiscard(path, filePath)
                if (discardRequest == request && generation == owner && _currentRepoPath.value == path && !mutationRunning) {
                    result.onSuccess { _discardReview.value = it }.onFailure { _errorMessage.value = it.message ?: "Could not review local changes" }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                if (discardRequest == request && generation == owner) _errorMessage.value = failure.message ?: "Could not review local changes"
            } finally {
                if (discardRequest == request) _isReviewingDiscard.value = false
            }
        }
    }

    fun dismissDiscard(expected: DiscardReview? = null) {
        if (expected != null && _discardReview.value !== expected) return
        discardRequest++
        discardJob?.cancel(); discardJob = null
        _discardReview.value = null
        _isReviewingDiscard.value = false
    }

    fun confirmDiscard(review: DiscardReview, onComplete: () -> Unit = {}) {
        if (mutationRunning || _discardReview.value !== review) return
        mutate(discard = true, onComplete = onComplete) { diffService.discardReviewed(it, review).getOrThrow() }
    }
    fun clearError() { _errorMessage.value = null; _lockedPaths.value = null }
}

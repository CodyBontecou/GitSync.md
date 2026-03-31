package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.data.models.MergeResult
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.services.git.BranchService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for branch operations and merge management.
 */
@HiltViewModel
class BranchViewModel @Inject constructor(
    private val branchService: BranchService
) : ViewModel() {

    // All branches
    private val _allBranches = MutableStateFlow<List<Branch>>(emptyList())
    val allBranches: StateFlow<List<Branch>> = _allBranches.asStateFlow()

    // Local branches only
    private val _localBranches = MutableStateFlow<List<Branch>>(emptyList())
    val localBranches: StateFlow<List<Branch>> = _localBranches.asStateFlow()

    // Remote branches only
    private val _remoteBranches = MutableStateFlow<List<Branch>>(emptyList())
    val remoteBranches: StateFlow<List<Branch>> = _remoteBranches.asStateFlow()

    // Current branch
    private val _currentBranch = MutableStateFlow<Branch?>(null)
    val currentBranch: StateFlow<Branch?> = _currentBranch.asStateFlow()

    // Merge in progress
    private val _mergeInProgress = MutableStateFlow(false)
    val mergeInProgress: StateFlow<Boolean> = _mergeInProgress.asStateFlow()

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error message
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Current repository path
    private val _currentRepoPath = MutableStateFlow<String?>(null)
    val currentRepoPath: StateFlow<String?> = _currentRepoPath.asStateFlow()

    /**
     * Set the current repository path and load branches.
     */
    fun setRepositoryPath(repoPath: String) {
        _currentRepoPath.value = repoPath
        loadBranches()
    }

    /**
     * Load all branches from the repository.
     */
    fun loadBranches() {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                // Load current branch
                val currentResult = branchService.getCurrentBranch(repoPath)
                if (currentResult.isSuccess) {
                    _currentBranch.value = currentResult.getOrNull()
                } else {
                    _errorMessage.value = currentResult.exceptionOrNull()?.message ?: "Failed to load current branch"
                }

                // Load all branches
                val allResult = branchService.listBranches(repoPath)
                if (allResult.isSuccess) {
                    _allBranches.value = allResult.getOrNull() ?: emptyList()
                    _errorMessage.value = null
                } else {
                    _errorMessage.value = allResult.exceptionOrNull()?.message ?: "Failed to load branches"
                }

                // Load local branches
                val localResult = branchService.getLocalBranches(repoPath)
                if (localResult.isSuccess) {
                    _localBranches.value = localResult.getOrNull() ?: emptyList()
                } else {
                    _errorMessage.value = localResult.exceptionOrNull()?.message ?: "Failed to load local branches"
                }

                // Load remote branches
                val remoteResult = branchService.getRemoteBranches(repoPath)
                if (remoteResult.isSuccess) {
                    _remoteBranches.value = remoteResult.getOrNull() ?: emptyList()
                } else {
                    _errorMessage.value = remoteResult.exceptionOrNull()?.message ?: "Failed to load remote branches"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Switch to a different branch.
     */
    fun switchBranch(branchName: String) {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = branchService.switchBranch(repoPath, branchName)
                if (result.isSuccess) {
                    loadBranches() // Reload to update current branch
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to switch branch"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Create a new branch.
     */
    fun createBranch(name: String, startPoint: String = "HEAD") {
        val repoPath = _currentRepoPath.value ?: return

        if (name.isBlank()) {
            _errorMessage.value = "Branch name cannot be empty"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = branchService.createBranch(repoPath, name, startPoint)
                if (result.isSuccess) {
                    loadBranches() // Reload to show new branch
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to create branch"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Delete a branch.
     */
    fun deleteBranch(branchName: String, force: Boolean = false) {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = branchService.deleteBranch(repoPath, branchName, force)
                if (result.isSuccess) {
                    loadBranches() // Reload after deletion
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to delete branch"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Merge a source branch into the current branch.
     */
    fun mergeBranch(sourceBranch: String, strategy: MergeStrategy = MergeStrategy.PREFER_FF) {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _mergeInProgress.value = true
            _errorMessage.value = null

            try {
                val result = branchService.merge(repoPath, sourceBranch, strategy)
                if (result.isSuccess) {
                    val mergeResult = result.getOrNull()
                    if (mergeResult != null) {
                        if (mergeResult.success) {
                            _errorMessage.value = "Merge successful: ${mergeResult.message}"
                            loadBranches()
                        } else {
                            _errorMessage.value = "Merge failed: ${mergeResult.message}"
                            if (mergeResult.conflictCount > 0) {
                                _errorMessage.value += " (${mergeResult.conflictCount} conflicts)"
                            }
                        }
                    }
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Merge operation failed"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error during merge"
            } finally {
                _isLoading.value = false
                _mergeInProgress.value = false
            }
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _errorMessage.value = null
    }
}

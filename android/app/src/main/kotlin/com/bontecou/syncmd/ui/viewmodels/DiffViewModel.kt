package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.bontecou.syncmd.data.models.FileDiff
import com.bontecou.syncmd.data.models.PushConfig
import com.bontecou.syncmd.data.models.UnifiedDiffResult
import com.bontecou.syncmd.services.git.DiffService
import com.bontecou.syncmd.services.git.PushService
import com.bontecou.syncmd.services.github.GitHubAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for diff operations, staging, and commit creation.
 */
@HiltViewModel
class DiffViewModel @Inject constructor(
    private val diffService: DiffService,
    private val pushService: PushService,
    private val authManager: GitHubAuthManager,
) : ViewModel() {

    // All files in the repository with their status
    private val _diff = MutableStateFlow<UnifiedDiffResult?>(null)
    val diff: StateFlow<UnifiedDiffResult?> = _diff.asStateFlow()

    // Currently selected file for diff viewing
    private val _selectedFilePath = MutableStateFlow<String?>(null)
    val selectedFilePath: StateFlow<String?> = _selectedFilePath.asStateFlow()

    // Diff for the selected file
    private val _selectedFileDiff = MutableStateFlow<UnifiedDiffResult?>(null)
    val selectedFileDiff: StateFlow<UnifiedDiffResult?> = _selectedFileDiff.asStateFlow()

    // Commit message
    private val _commitMessage = MutableStateFlow("")
    val commitMessage: StateFlow<String> = _commitMessage.asStateFlow()

    // Loading state (staging / diff operations)
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Dedicated push-in-progress state (commit + push network call)
    private val _isPushing = MutableStateFlow(false)
    val isPushing: StateFlow<Boolean> = _isPushing.asStateFlow()

    // Error message
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Current repository path
    private val _currentRepoPath = MutableStateFlow<String?>(null)
    val currentRepoPath: StateFlow<String?> = _currentRepoPath.asStateFlow()

    /**
     * Set the current repository path and load diff status.
     */
    fun setRepositoryPath(repoPath: String) {
        _currentRepoPath.value = repoPath
        loadDiff()
    }

    /**
     * Load diff for all files in the repository.
     */
    fun loadDiff() {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = diffService.getStatus(repoPath)
                if (result.isSuccess) {
                    _diff.value = UnifiedDiffResult(
                        files = result.getOrNull() ?: emptyList(),
                        summary = _diff.value?.summary ?: com.bontecou.syncmd.data.models.DiffSummary(0, 0, 0)
                    )
                    _errorMessage.value = null
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to load diff"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Select a file to view its full diff.
     */
    fun selectFile(filePath: String) {
        _selectedFilePath.value = filePath
        loadFileDiff(filePath)
    }

    /**
     * Load diff for a specific file.
     */
    private fun loadFileDiff(filePath: String) {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            try {
                val result = diffService.getFileDiff(repoPath, filePath)
                if (result.isSuccess) {
                    _selectedFileDiff.value = result.getOrNull()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to load file diff"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            }
        }
    }

    /**
     * Stage a file for commit.
     */
    fun stageFile(filePath: String) {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = diffService.stageFile(repoPath, filePath)
                if (result.isSuccess) {
                    // Clear selected file and reload diff
                    _selectedFilePath.value = null
                    _selectedFileDiff.value = null
                    loadDiff()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to stage file"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Unstage a file.
     */
    fun unstageFile(filePath: String) {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = diffService.unstageFile(repoPath, filePath)
                if (result.isSuccess) {
                    // Clear selected file and reload diff
                    _selectedFilePath.value = null
                    _selectedFileDiff.value = null
                    loadDiff()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to unstage file"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Stage all modified files.
     */
    fun stageAll() {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = diffService.stageAll(repoPath)
                if (result.isSuccess) {
                    _selectedFilePath.value = null
                    _selectedFileDiff.value = null
                    loadDiff()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to stage all files"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Unstage all files.
     */
    fun unstageAll() {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = diffService.unstageAll(repoPath)
                if (result.isSuccess) {
                    _selectedFilePath.value = null
                    _selectedFileDiff.value = null
                    loadDiff()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to unstage all files"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Update the commit message.
     */
    fun setCommitMessage(message: String) {
        _commitMessage.value = message
    }

    /**
     * Commit all staged files and push to remote.
     *
     * @param branch  The current local branch name (e.g. "main"). Used to build the push refspec.
     * @param remote  The remote name to push to (defaults to "origin").
     */
    fun commitAndPush(branch: String, remote: String = "origin") {
        val repoPath = _currentRepoPath.value ?: return
        val message = _commitMessage.value

        Log.d("DiffViewModel", "commitAndPush called: branch=$branch remote=$remote repoPath=$repoPath message='$message'")

        if (message.isBlank()) {
            _errorMessage.value = "Commit message cannot be empty"
            return
        }

        viewModelScope.launch {
            _isPushing.value = true
            _errorMessage.value = null

            try {
                // Step 1: local commit (attributed to the signed-in GitHub user)
                val authorName  = authManager.getAuthorName()
                val authorEmail = authManager.getAuthorEmail()
                Log.d("DiffViewModel", "Starting local commit as $authorName <$authorEmail>...")
                val commitResult = diffService.commit(repoPath, message, authorName, authorEmail)
                if (commitResult.isFailure) {
                    val err = commitResult.exceptionOrNull()?.message ?: "Commit failed"
                    Log.e("DiffViewModel", "Commit failed: $err")
                    _errorMessage.value = err
                    return@launch
                }
                Log.d("DiffViewModel", "Local commit succeeded, pushing to $remote/$branch...")

                // Step 2: push to remote
                val pushResult = pushService.push(
                    repository = repoPath,
                    config = PushConfig(branch = branch, remote = remote)
                )
                if (pushResult.isFailure) {
                    val err = pushResult.exceptionOrNull()?.message ?: "Push failed"
                    Log.e("DiffViewModel", "Push failed: $err")
                    // Commit succeeded but push failed — surface the error so the user can retry
                    _errorMessage.value = err
                    return@launch
                }

                Log.d("DiffViewModel", "Push succeeded!")
                // Both succeeded — reset form state
                _commitMessage.value = ""
                _selectedFilePath.value = null
                _selectedFileDiff.value = null
                loadDiff()
            } catch (e: Exception) {
                Log.e("DiffViewModel", "commitAndPush exception: ${e.message}", e)
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isPushing.value = false
            }
        }
    }

    /**
     * Discard all local changes in the current repository (equivalent to `git reset --hard HEAD`).
     * Reloads the diff status when complete.
     */
    fun discardAllChanges(onComplete: () -> Unit = {}) {
        val repoPath = _currentRepoPath.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val result = diffService.discardAllChanges(repoPath)
                if (result.isSuccess) {
                    _selectedFilePath.value = null
                    _selectedFileDiff.value = null
                    loadDiff()
                    onComplete()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to discard changes"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Discard local changes for a single file.
     * New (untracked) files are deleted from disk; modified files are restored to HEAD.
     */
    fun discardFileChanges(filePath: String, onComplete: () -> Unit = {}) {
        val repoPath = _currentRepoPath.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val result = diffService.discardFileChanges(repoPath, filePath)
                if (result.isSuccess) {
                    if (_selectedFilePath.value == filePath) {
                        _selectedFilePath.value = null
                        _selectedFileDiff.value = null
                    }
                    loadDiff()
                    onComplete()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to discard file changes"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
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

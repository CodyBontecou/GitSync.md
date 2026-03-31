package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.data.models.FileDiff
import com.bontecou.syncmd.data.models.UnifiedDiffResult
import com.bontecou.syncmd.services.git.DiffService
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
    private val diffService: DiffService
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
     * Commit all staged files.
     */
    fun commit() {
        val repoPath = _currentRepoPath.value ?: return
        val message = _commitMessage.value

        if (message.isBlank()) {
            _errorMessage.value = "Commit message cannot be empty"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = diffService.commit(repoPath, message)
                if (result.isSuccess) {
                    // Reset state after successful commit
                    _commitMessage.value = ""
                    _selectedFilePath.value = null
                    _selectedFileDiff.value = null
                    loadDiff()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Commit failed"
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

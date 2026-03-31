package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.data.models.Commit
import com.bontecou.syncmd.data.models.RevertStrategy
import com.bontecou.syncmd.data.models.Stash
import com.bontecou.syncmd.data.models.Tag
import com.bontecou.syncmd.services.git.HistoryService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for history operations, stash management, and tag management.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyService: HistoryService
) : ViewModel() {

    // Commit history
    private val _commits = MutableStateFlow<List<Commit>>(emptyList())
    val commits: StateFlow<List<Commit>> = _commits.asStateFlow()

    // Stashes
    private val _stashes = MutableStateFlow<List<Stash>>(emptyList())
    val stashes: StateFlow<List<Stash>> = _stashes.asStateFlow()

    // Tags
    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    // Selected commit for detailed view
    private val _selectedCommit = MutableStateFlow<Commit?>(null)
    val selectedCommit: StateFlow<Commit?> = _selectedCommit.asStateFlow()

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
     * Set the current repository path and load all history data.
     */
    fun setRepositoryPath(repoPath: String) {
        _currentRepoPath.value = repoPath
        loadAll()
    }

    /**
     * Load all history data (commits, stashes, tags).
     */
    private fun loadAll() {
        loadHistory()
        loadStashes()
        loadTags()
    }

    /**
     * Load commit history.
     */
    fun loadHistory() {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = historyService.getHistory(repoPath, maxCommits = 100)
                if (result.isSuccess) {
                    _commits.value = result.getOrNull() ?: emptyList()
                    _errorMessage.value = null
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to load history"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Load stashes.
     */
    fun loadStashes() {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            try {
                val result = historyService.listStashes(repoPath)
                if (result.isSuccess) {
                    _stashes.value = result.getOrNull() ?: emptyList()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to load stashes"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            }
        }
    }

    /**
     * Load tags.
     */
    fun loadTags() {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            try {
                val result = historyService.listTags(repoPath)
                if (result.isSuccess) {
                    _tags.value = result.getOrNull() ?: emptyList()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to load tags"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            }
        }
    }

    /**
     * Select a commit for detailed view.
     */
    fun selectCommit(commit: Commit) {
        _selectedCommit.value = commit
    }

    /**
     * Deselect the currently selected commit.
     */
    fun deselectCommit() {
        _selectedCommit.value = null
    }

    /**
     * Revert a commit.
     */
    fun revertCommit(commitHash: String, strategy: RevertStrategy = RevertStrategy.CREATE_NEW_COMMIT) {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = historyService.revertCommit(repoPath, commitHash, strategy)
                if (result.isSuccess) {
                    val revertResult = result.getOrNull()
                    if (revertResult != null && revertResult.success) {
                        loadHistory() // Reload history after revert
                        _selectedCommit.value = null
                    } else {
                        _errorMessage.value = revertResult?.message ?: "Revert operation failed"
                    }
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Revert failed"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error during revert"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Save current changes to stash.
     */
    fun stashSave(message: String = "") {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = historyService.stash(repoPath, message.ifBlank { null })
                if (result.isSuccess) {
                    val stashResult = result.getOrNull()
                    if (stashResult != null && stashResult.success) {
                        loadStashes()
                    } else {
                        _errorMessage.value = stashResult?.message ?: "Stash operation failed"
                    }
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Stash failed"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error during stash"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Apply a stash without removing it.
     */
    fun stashApply(stashId: String) {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = historyService.stashApply(repoPath, stashId)
                if (result.isSuccess) {
                    loadStashes()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to apply stash"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error applying stash"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Pop a stash (apply and remove).
     */
    fun stashPop(stashId: String) {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = historyService.stashPop(repoPath, stashId)
                if (result.isSuccess) {
                    loadStashes()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to pop stash"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error popping stash"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Drop a stash without applying.
     */
    fun stashDrop(stashId: String) {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = historyService.stashDrop(repoPath, stashId)
                if (result.isSuccess) {
                    loadStashes()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to drop stash"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error dropping stash"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Create a new tag.
     */
    fun createTag(
        name: String,
        commitHash: String = "HEAD",
        annotated: Boolean = false,
        message: String? = null
    ) {
        val repoPath = _currentRepoPath.value ?: return

        if (name.isBlank()) {
            _errorMessage.value = "Tag name cannot be empty"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = historyService.createTag(repoPath, name, commitHash, annotated, message)
                if (result.isSuccess) {
                    loadTags()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to create tag"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error creating tag"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Delete a tag.
     */
    fun deleteTag(name: String) {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = historyService.deleteTag(repoPath, name)
                if (result.isSuccess) {
                    loadTags()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to delete tag"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error deleting tag"
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

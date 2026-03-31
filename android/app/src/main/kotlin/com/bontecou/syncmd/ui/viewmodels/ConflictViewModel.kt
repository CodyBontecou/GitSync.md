package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.data.models.MergeState
import com.bontecou.syncmd.services.git.ConflictService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for merge conflict detection and resolution.
 */
@HiltViewModel
class ConflictViewModel @Inject constructor(
    private val conflictService: ConflictService
) : ViewModel() {

    // All conflicts
    private val _conflicts = MutableStateFlow<List<Conflict>>(emptyList())
    val conflicts: StateFlow<List<Conflict>> = _conflicts.asStateFlow()

    // Current merge state
    private val _mergeState = MutableStateFlow<MergeState?>(null)
    val mergeState: StateFlow<MergeState?> = _mergeState.asStateFlow()

    // Selected conflict for detail view
    private val _selectedConflict = MutableStateFlow<Conflict?>(null)
    val selectedConflict: StateFlow<Conflict?> = _selectedConflict.asStateFlow()

    // Manual resolution content for current conflict
    private val _manualResolutionContent = MutableStateFlow("")
    val manualResolutionContent: StateFlow<String> = _manualResolutionContent.asStateFlow()

    // Resolved conflicts (tracking progress)
    private val _resolvedConflicts = MutableStateFlow<Set<String>>(emptySet())
    val resolvedConflicts: StateFlow<Set<String>> = _resolvedConflicts.asStateFlow()

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
     * Set the current repository path and load conflicts.
     */
    fun setRepositoryPath(repoPath: String) {
        _currentRepoPath.value = repoPath
        loadConflicts()
    }

    /**
     * Load all conflicts from the repository.
     */
    fun loadConflicts() {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                // Get merge state
                val stateResult = conflictService.getMergeState(repoPath)
                if (stateResult.isSuccess) {
                    _mergeState.value = stateResult.getOrNull()
                }

                // Get conflicts
                val conflictsResult = conflictService.getConflicts(repoPath)
                if (conflictsResult.isSuccess) {
                    _conflicts.value = conflictsResult.getOrNull() ?: emptyList()
                    _errorMessage.value = null
                } else {
                    _errorMessage.value = conflictsResult.exceptionOrNull()?.message ?: "Failed to load conflicts"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Select a conflict to view details.
     */
    fun selectConflict(conflict: Conflict) {
        _selectedConflict.value = conflict
        _manualResolutionContent.value = ""
    }

    /**
     * Deselect current conflict.
     */
    fun deselectConflict() {
        _selectedConflict.value = null
        _manualResolutionContent.value = ""
    }

    /**
     * Update manual resolution content.
     */
    fun setManualResolutionContent(content: String) {
        _manualResolutionContent.value = content
    }

    /**
     * Resolve a conflict using specified strategy.
     */
    fun resolveConflict(
        filePath: String,
        strategy: ConflictResolutionStrategy,
        customContent: String? = null
    ) {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = conflictService.resolveConflict(
                    repoPath,
                    filePath,
                    strategy,
                    customContent
                )

                if (result.isSuccess) {
                    val resolution = result.getOrNull()
                    if (resolution != null && resolution.resolved) {
                        // Mark as resolved
                        val updated = _resolvedConflicts.value.toMutableSet()
                        updated.add(filePath)
                        _resolvedConflicts.value = updated

                        // Deselect and reload
                        _selectedConflict.value = null
                        _manualResolutionContent.value = ""
                        loadConflicts()
                    } else {
                        _errorMessage.value = resolution?.message ?: "Failed to resolve conflict"
                    }
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Conflict resolution failed"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error during conflict resolution"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Resolve conflict accepting our version.
     */
    fun acceptOurs(filePath: String) {
        resolveConflict(filePath, ConflictResolutionStrategy.OURS)
    }

    /**
     * Resolve conflict accepting their version.
     */
    fun acceptTheirs(filePath: String) {
        resolveConflict(filePath, ConflictResolutionStrategy.THEIRS)
    }

    /**
     * Resolve conflict with manual content.
     */
    fun acceptManual(filePath: String) {
        resolveConflict(filePath, ConflictResolutionStrategy.MANUAL, _manualResolutionContent.value)
    }

    /**
     * Resolve all conflicts using the same strategy.
     */
    fun resolveAllConflicts(strategy: ConflictResolutionStrategy) {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = conflictService.resolveAllConflicts(repoPath, strategy)
                if (result.isSuccess) {
                    val resolutions = result.getOrNull() ?: emptyList()
                    if (resolutions.isNotEmpty() && resolutions.all { it.resolved }) {
                        loadConflicts()
                    } else {
                        _errorMessage.value = "Some conflicts could not be resolved"
                    }
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to resolve conflicts"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Complete merge with a commit message.
     */
    fun completeMerge(message: String) {
        val repoPath = _currentRepoPath.value ?: return

        if (message.isBlank()) {
            _errorMessage.value = "Merge message cannot be empty"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = conflictService.completeMerge(repoPath, message)
                if (result.isSuccess) {
                    _conflicts.value = emptyList()
                    _mergeState.value = null
                    _resolvedConflicts.value = emptySet()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to complete merge"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error completing merge"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Abort merge and return to pre-merge state.
     */
    fun abortMerge() {
        val repoPath = _currentRepoPath.value ?: return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                val result = conflictService.abortMerge(repoPath)
                if (result.isSuccess) {
                    _conflicts.value = emptyList()
                    _mergeState.value = null
                    _resolvedConflicts.value = emptySet()
                    _selectedConflict.value = null
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to abort merge"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error aborting merge"
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

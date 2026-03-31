package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.data.models.RepositoryStatus
import com.bontecou.syncmd.data.models.SafePullPlan
import com.bontecou.syncmd.services.git.PullService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for pull operations and repository status management.
 */
@HiltViewModel
class PullViewModel @Inject constructor(
    private val pullService: PullService
) : ViewModel() {

    // Repository status
    private val _status = MutableStateFlow<RepositoryStatus?>(null)
    val status: StateFlow<RepositoryStatus?> = _status.asStateFlow()

    // Pull plan
    private val _pullPlan = MutableStateFlow<SafePullPlan?>(null)
    val pullPlan: StateFlow<SafePullPlan?> = _pullPlan.asStateFlow()

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
     * Set the current repository path and load status.
     */
    fun setRepositoryPath(repoPath: String) {
        _currentRepoPath.value = repoPath
        loadStatus()
    }

    /**
     * Load repository status.
     */
    fun loadStatus() {
        val repoPath = _currentRepoPath.value ?: return
        
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            try {
                val result = pullService.getStatus(repoPath)
                if (result.isSuccess) {
                    _status.value = result.getOrNull()
                    _errorMessage.value = null
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to load status"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Plan a pull operation.
     */
    fun planPull() {
        val repoPath = _currentRepoPath.value ?: return
        
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            try {
                val result = pullService.planPull(repoPath)
                if (result.isSuccess) {
                    _pullPlan.value = result.getOrNull()
                    _errorMessage.value = null
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to plan pull"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Execute a pull operation.
     */
    fun executePull() {
        val repoPath = _currentRepoPath.value ?: return
        
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            try {
                val result = pullService.pull(repoPath)
                if (result.isSuccess) {
                    _pullPlan.value = null
                    loadStatus() // Refresh status after pull
                    _errorMessage.value = null
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Pull failed"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Fetch without merging.
     */
    fun fetch() {
        val repoPath = _currentRepoPath.value ?: return
        
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            try {
                val result = pullService.fetch(repoPath)
                if (result.isSuccess) {
                    loadStatus() // Refresh status after fetch
                    _errorMessage.value = null
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Fetch failed"
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

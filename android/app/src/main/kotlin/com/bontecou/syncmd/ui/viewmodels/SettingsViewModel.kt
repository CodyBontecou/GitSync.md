package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Data class for app settings.
 */
data class AppSettings(
    val selectedRepository: String = "",
    val autoFetchInterval: Long = 3600000L, // 1 hour in ms
    val isDarkTheme: Boolean = false,
    val showDebugInfo: Boolean = false,
)

/**
 * Data class for a saved repository.
 */
data class SavedRepository(
    val name: String,
    val path: String,
    val alias: String = "",
    val dateAdded: Long = System.currentTimeMillis()
)

/**
 * ViewModel for app settings and repository management.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val sharedPrefs: SharedPreferences = 
        context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE)

    // Selected repository path
    private val _selectedRepository = MutableStateFlow("")
    val selectedRepository: StateFlow<String> = _selectedRepository.asStateFlow()

    // All saved repositories
    private val _allRepositories = MutableStateFlow<List<SavedRepository>>(emptyList())
    val allRepositories: StateFlow<List<SavedRepository>> = _allRepositories.asStateFlow()

    // App settings
    private val _appSettings = MutableStateFlow(AppSettings())
    val appSettings: StateFlow<AppSettings> = _appSettings.asStateFlow()

    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Error message
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        loadSettings()
    }

    /**
     * Load all settings and repositories from SharedPreferences.
     */
    fun loadSettings() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            try {
                // Load selected repository
                val selected = sharedPrefs.getString("selected_repository", "") ?: ""
                _selectedRepository.value = selected

                // Load all repositories
                val repos = mutableListOf<SavedRepository>()
                val repoCount = sharedPrefs.getInt("repository_count", 0)
                
                for (i in 0 until repoCount) {
                    val name = sharedPrefs.getString("repo_${i}_name", null) ?: continue
                    val path = sharedPrefs.getString("repo_${i}_path", null) ?: continue
                    val alias = sharedPrefs.getString("repo_${i}_alias", "") ?: ""
                    val dateAdded = sharedPrefs.getLong("repo_${i}_date", System.currentTimeMillis())
                    
                    repos.add(SavedRepository(name, path, alias, dateAdded))
                }
                _allRepositories.value = repos

                // Load app settings
                val autoFetchInterval = sharedPrefs.getLong("auto_fetch_interval", 3600000L)
                val isDarkTheme = sharedPrefs.getBoolean("is_dark_theme", false)
                val showDebugInfo = sharedPrefs.getBoolean("show_debug_info", false)

                _appSettings.value = AppSettings(
                    selectedRepository = selected,
                    autoFetchInterval = autoFetchInterval,
                    isDarkTheme = isDarkTheme,
                    showDebugInfo = showDebugInfo,
                )
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load settings: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Set the selected repository.
     */
    fun setRepositoryPath(path: String) {
        if (path.isBlank()) {
            _errorMessage.value = "Repository path cannot be empty"
            return
        }

        viewModelScope.launch {
            try {
                _selectedRepository.value = path
                sharedPrefs.edit().putString("selected_repository", path).apply()
                
                // Update app settings
                _appSettings.value = _appSettings.value.copy(selectedRepository = path)
                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = "Failed to set repository: ${e.message}"
            }
        }
    }

    /**
     * Add a new repository.
     */
    fun addRepository(name: String, path: String, alias: String = "") {
        if (name.isBlank() || path.isBlank()) {
            _errorMessage.value = "Repository name and path cannot be empty"
            return
        }

        viewModelScope.launch {
            try {
                val repos = _allRepositories.value.toMutableList()
                
                // Check if repository already exists
                if (repos.any { it.path == path }) {
                    _errorMessage.value = "Repository with this path already exists"
                    return@launch
                }

                // Add new repository
                val newRepo = SavedRepository(name, path, alias)
                repos.add(newRepo)
                _allRepositories.value = repos

                // Save to SharedPreferences
                sharedPrefs.edit().apply {
                    putInt("repository_count", repos.size)
                    
                    for ((i, repo) in repos.withIndex()) {
                        putString("repo_${i}_name", repo.name)
                        putString("repo_${i}_path", repo.path)
                        putString("repo_${i}_alias", repo.alias)
                        putLong("repo_${i}_date", repo.dateAdded)
                    }
                }.apply()

                // Auto-select if first repository
                if (repos.size == 1) {
                    setRepositoryPath(path)
                }

                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = "Failed to add repository: ${e.message}"
            }
        }
    }

    /**
     * Remove a repository.
     */
    fun removeRepository(path: String) {
        viewModelScope.launch {
            try {
                val repos = _allRepositories.value.filter { it.path != path }
                _allRepositories.value = repos

                // Save to SharedPreferences
                sharedPrefs.edit().apply {
                    putInt("repository_count", repos.size)
                    
                    // Clear all entries first
                    for (i in 0..100) {
                        remove("repo_${i}_name")
                        remove("repo_${i}_path")
                        remove("repo_${i}_alias")
                        remove("repo_${i}_date")
                    }
                    
                    // Write new entries
                    for ((i, repo) in repos.withIndex()) {
                        putString("repo_${i}_name", repo.name)
                        putString("repo_${i}_path", repo.path)
                        putString("repo_${i}_alias", repo.alias)
                        putLong("repo_${i}_date", repo.dateAdded)
                    }
                }.apply()

                // Clear selection if removed
                if (_selectedRepository.value == path) {
                    if (repos.isNotEmpty()) {
                        setRepositoryPath(repos.first().path)
                    } else {
                        _selectedRepository.value = ""
                        sharedPrefs.edit().putString("selected_repository", "").apply()
                    }
                }

                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = "Failed to remove repository: ${e.message}"
            }
        }
    }

    /**
     * Update app settings.
     */
    fun updateSettings(settings: AppSettings) {
        viewModelScope.launch {
            try {
                _appSettings.value = settings

                sharedPrefs.edit().apply {
                    putLong("auto_fetch_interval", settings.autoFetchInterval)
                    putBoolean("is_dark_theme", settings.isDarkTheme)
                    putBoolean("show_debug_info", settings.showDebugInfo)
                    // Cleanup legacy setting; clone location is now always app storage.
                    remove("default_clone_dir")
                }.apply()

                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = "Failed to update settings: ${e.message}"
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

package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.services.github.GitHubApiService
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.github.GitHubRepo
import com.bontecou.syncmd.services.github.GitHubUser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Drives GitHub authentication and repository browsing.
 *
 * Login flow:
 *   1. UI calls [generateOAuthUrl] → gets a URL to open in a Chrome Custom Tab
 *   2. After the OAuth redirect, MainActivity calls [GitHubAuthManager.handleCallback]
 *   3. [isLoggedIn] flips to true → [loadUserAndRepos] is triggered automatically
 */
@HiltViewModel
class GitHubViewModel @Inject constructor(
    val authManager: GitHubAuthManager
) : ViewModel() {

    // ─── Auth state ──────────────────────────────────────────────────────────

    val isLoggedIn: StateFlow<Boolean> = authManager.token
        .map { !it.isNullOrEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, authManager.isLoggedIn)

    // ─── Profile ─────────────────────────────────────────────────────────────

    private val _user = MutableStateFlow<GitHubUser?>(null)
    val user: StateFlow<GitHubUser?> = _user.asStateFlow()

    // ─── Repos ───────────────────────────────────────────────────────────────

    private val _repos = MutableStateFlow<List<GitHubRepo>>(emptyList())
    val repos: StateFlow<List<GitHubRepo>> = _repos.asStateFlow()

    private val _filteredRepos = MutableStateFlow<List<GitHubRepo>>(emptyList())
    val filteredRepos: StateFlow<List<GitHubRepo>> = _filteredRepos.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // ─── UI state ────────────────────────────────────────────────────────────

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        if (authManager.isLoggedIn) {
            loadUserAndRepos()
        }
    }

    // ─── OAuth ───────────────────────────────────────────────────────────────

    /**
     * Generates a one-time OAuth login URL with a random CSRF state.
     * The caller (LoginScreen) opens this in a Custom Tab.
     */
    fun generateOAuthUrl(): String {
        val state = UUID.randomUUID().toString().replace("-", "")
        return authManager.buildLoginUrl(state)
    }

    /** Called after a successful OAuth deep link callback. */
    fun onOAuthSuccess() {
        loadUserAndRepos()
    }

    // ─── Data loading ─────────────────────────────────────────────────────────

    fun loadUserAndRepos() {
        val token = authManager.getToken() ?: return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val user = GitHubApiService.fetchUser(token)
                _user.value = user

                // Fetch email separately (may not be on the public profile)
                val email = user.email
                    ?: GitHubApiService.fetchPrimaryEmail(token)
                authManager.cacheUserProfile(user.login, user.name, user.avatarUrl, email)

                val repos = GitHubApiService.fetchRepos(token)
                _repos.value = repos
                applyFilter(_searchQuery.value)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load GitHub data"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Sign in with a Personal Access Token instead of the browser OAuth flow.
     * Validates the token immediately by fetching the user profile.
     */
    fun signInWithPAT(token: String) {
        if (token.isBlank()) {
            _error.value = "Token cannot be empty"
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val user = GitHubApiService.fetchUser(token)
                // Token is valid — store it
                authManager.handleCallback(token)
                _user.value = user

                val email = user.email
                    ?: GitHubApiService.fetchPrimaryEmail(token)
                authManager.cacheUserProfile(user.login, user.name, user.avatarUrl, email)

                val repos = GitHubApiService.fetchRepos(token)
                _repos.value = repos
                applyFilter(_searchQuery.value)
            } catch (e: IllegalStateException) {
                _error.value = "Invalid token — please check it and try again"
            } catch (e: Exception) {
                _error.value = e.message ?: "Sign-in failed"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ─── Search / filter ─────────────────────────────────────────────────────

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        applyFilter(query)
    }

    private fun applyFilter(query: String) {
        _filteredRepos.value = if (query.isBlank()) {
            _repos.value
        } else {
            _repos.value.filter { repo ->
                repo.name.contains(query, ignoreCase = true) ||
                        repo.fullName.contains(query, ignoreCase = true) ||
                        (repo.description?.contains(query, ignoreCase = true) == true)
            }
        }
    }

    // ─── Sign out ────────────────────────────────────────────────────────────

    fun signOut() {
        authManager.signOut()
        _user.value = null
        _repos.value = emptyList()
        _filteredRepos.value = emptyList()
        _searchQuery.value = ""
        _error.value = null
    }

    fun clearError() {
        _error.value = null
    }
}

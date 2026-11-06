package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.github.*
import com.bontecou.syncmd.ui.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Account-owned publications: a delayed response cannot repopulate another session's profile or repos. */
@HiltViewModel
class GitHubViewModel @Inject constructor(val authManager: GitHubAuthManager, private val api: GitHubClient) : ViewModel() {
    val isLoggedIn = authManager.token.map { !it.isNullOrBlank() }.stateIn(viewModelScope, SharingStarted.Eagerly, authManager.isLoggedIn)
    private val _user = MutableStateFlow<GitHubUser?>(null)
    val user = _user.asStateFlow()
    private val _repos = MutableStateFlow<List<GitHubRepo>>(emptyList())
    val repos = _repos.asStateFlow()
    private val _filteredRepos = MutableStateFlow<List<GitHubRepo>>(emptyList())
    val filteredRepos = _filteredRepos.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    private val _signOutReview = MutableStateFlow<GitHubAuthManager.SignOutReview?>(null)
    val signOutReview = _signOutReview.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    private val _error = MutableStateFlow<UiText?>(null)
    val error = combine(_error, authManager.sessionError) { request, session -> request ?: session }
        .stateIn(viewModelScope, SharingStarted.Eagerly, authManager.sessionError.value)
    private var loadJob: Job? = null
    private var signInJob: Job? = null
    private var request = 0L
    private var signInRequest = 0L
    private var publishingSignInToken: String? = null
    private var ownedSessionRevision: Long? = null
    private var dataToken: String? = null
    private var loading = false
    private var signingIn = false
    private fun busy() { _isLoading.value = loading || signingIn }
    private fun clearAccountData() {
        _user.value = null; _repos.value = emptyList(); _filteredRepos.value = emptyList(); _searchQuery.value = ""
    }
    init {
        val initialRevision = authManager.sessionRevision.value
        if (authManager.isLoggedIn) loadUserAndRepos()
        viewModelScope.launch {
            var observed = initialRevision
            authManager.sessionRevision.collect { revision ->
                if (observed != revision) {
                    _signOutReview.value = null
                    observed = revision
                    val token = authManager.getToken()
                    // A validated PAT publishes its own revision before finishing profile/repo loading.
                    // Every other revision invalidates both requests, even when the token is unchanged.
                    if (revision == ownedSessionRevision ||
                        (publishingSignInToken != null && token == publishingSignInToken)) return@collect
                    ownedSessionRevision = null
                    signInRequest++; signInJob?.cancel(); signingIn = false
                    request++; loadJob?.cancel(); loading = false
                    if (dataToken != token) { clearAccountData(); dataToken = token }
                    else {
                        // A reread may change the encrypted profile without changing its token.
                        _user.value = null
                        if (token != null) hydrate()
                    }
                    _error.value = null; busy()
                    if (token != null) loadUserAndRepos()
                }
            }
        }
    }
    fun generateOAuthUrl(): String? = authManager.createOAuthLoginUrl()
    fun browserLaunchFailed() { _error.value = UiText.res(R.string.account_browser_failed) }
    private fun failure(e: Exception, fallback: Int): UiText =
        if (e is GitHubApiException) UiText.res(if (e.isUnauthorized) R.string.account_token_invalid else R.string.account_api_error, e.status)
        else UiText.of(e, fallback)
    fun onOAuthSuccess() = loadUserAndRepos(forceRefresh = true)
    fun loadUserAndRepos(forceRefresh: Boolean = false) {
        val token = authManager.getToken() ?: return
        val revision = authManager.sessionRevision.value
        val id = ++request
        loadJob?.cancel()
        if (dataToken != token) { clearAccountData(); dataToken = token }
        if (!forceRefresh) {
            if (_repos.value.isNotEmpty() && !authManager.needsAccountVerification()) { hydrate(); loading = false; busy(); applyFilter(); return }
            authManager.getCachedRepos()?.let { cached ->
                _repos.value = cached; hydrate(); _error.value = null; loading = false; busy(); applyFilter()
                if (!authManager.needsAccountVerification()) return
            }
        }
        loading = true; busy(); _error.value = null
        loadJob = viewModelScope.launch {
            fun current() = id == request && authManager.getToken() == token && authManager.sessionRevision.value == revision
            try {
                val user = api.fetchUser(token)
                if (!current()) return@launch
                val email = user.email ?: api.fetchPrimaryEmail(token)
                if (!current()) return@launch
                if (!authManager.cacheUserProfile(user.login, user.name, user.avatarUrl, email, token, user.id)) return@launch
                _user.value = user.copy(email = email)
                val repos = api.fetchRepos(token)
                if (!current()) return@launch
                if (!authManager.cacheRepos(repos, token)) return@launch
                _repos.value = repos; applyFilter()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (current()) _error.value = failure(e, R.string.account_load_failed)
            } finally { if (id == request) { loading = false; busy() } }
        }
    }
    fun signInWithPAT(token: String) {
        if (signingIn) return
        if (token.isBlank()) { _error.value = UiText.res(R.string.account_token_empty); return }
        val id = ++signInRequest
        val startingRevision = authManager.sessionRevision.value
        signingIn = true; busy(); _error.value = null; authManager.clearSessionError()
        signInJob = viewModelScope.launch {
            var acceptedRevision: Long? = null
            fun current() = id == signInRequest && authManager.sessionRevision.value == (acceptedRevision ?: startingRevision)
            try {
                val user = api.fetchUser(token)
                if (!current()) return@launch
                publishingSignInToken = token
                try {
                    if (!authManager.saveValidatedToken(token, user)) {
                        if (current()) _error.value = UiText.res(R.string.account_save_sign_in_short)
                        return@launch
                    }
                    if (id != signInRequest || authManager.getToken() != token) return@launch
                    acceptedRevision = authManager.sessionRevision.value
                    ownedSessionRevision = acceptedRevision
                } finally { publishingSignInToken = null }
                if (!current() || authManager.getToken() != token) return@launch
                request++; loadJob?.cancel(); loading = false
                clearAccountData(); dataToken = token; _user.value = user
                val email = user.email ?: api.fetchPrimaryEmail(token)
                if (!current()) return@launch
                if (!authManager.cacheUserProfile(user.login, user.name, user.avatarUrl, email, token, user.id)) return@launch
                _user.value = user.copy(email = email)
                val repos = api.fetchRepos(token)
                if (!current()) return@launch
                if (!authManager.cacheRepos(repos, token)) return@launch
                _repos.value = repos; applyFilter()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (current()) _error.value = failure(e, R.string.account_sign_in_failed)
            } finally { if (id == signInRequest) { signingIn = false; busy() } }
        }
    }
    private fun hydrate() {
        val login = authManager.cachedLogin.value ?: return
        _user.value = GitHubUser(login, authManager.cachedName.value, authManager.cachedEmail.value, authManager.cachedAvatarUrl.value)
    }
    fun onSearchQueryChanged(query: String) { _searchQuery.value = query; applyFilter() }
    private fun applyFilter() {
        val query = _searchQuery.value
        _filteredRepos.value = _repos.value.filter { query.isBlank() || it.name.contains(query, true) || it.fullName.contains(query, true) || it.description?.contains(query, true) == true }
    }
    fun prepareSignOut(expectedRevision: Long) {
        val review = authManager.reviewSignOut(expectedRevision) ?: return
        _signOutReview.value = review
    }
    fun dismissSignOut(review: GitHubAuthManager.SignOutReview) {
        if (_signOutReview.value === review) _signOutReview.value = null
    }
    fun confirmSignOut(review: GitHubAuthManager.SignOutReview) {
        if (_signOutReview.value !== review) return
        _signOutReview.value = null
        // Reject stale consent without cancelling the current account's requests.
        if (authManager.signOut(review)) clearSignedOutRequests()
    }
    private fun clearSignedOutRequests() {
        ownedSessionRevision = null
        signInRequest++; request++; signInJob?.cancel(); loadJob?.cancel(); signingIn = false; loading = false; busy()
        clearAccountData(); dataToken = null; _error.value = null
    }
    /** Programmatic cancellation also supports a sign-in that has no connected account yet. */
    fun signOut() {
        _signOutReview.value = null
        ownedSessionRevision = null
        signInRequest++; request++; signInJob?.cancel(); loadJob?.cancel(); signingIn = false; loading = false; busy()
        if (!authManager.signOut()) return
        clearAccountData(); dataToken = null; _error.value = null
    }
    fun clearError() { _error.value = null; authManager.clearSessionError() }
}

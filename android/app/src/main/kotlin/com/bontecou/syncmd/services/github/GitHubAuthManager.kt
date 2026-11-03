package com.bontecou.syncmd.services.github

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.bontecou.syncmd.R
import com.bontecou.syncmd.security.*
import com.bontecou.syncmd.ui.util.UiText
import java.security.MessageDigest
import java.util.UUID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages GitHub OAuth token storage and authentication state.
 *
 * OAuth flow (browser-based):
 *   1. UI opens a Chrome Custom Tab to [SERVER_URL]/api/auth/login?state=XXX
 *   2. User authorises on github.com
 *   3. Server exchanges the code for a token and redirects to syncmd://auth?token=YYY
 *   4. Android resolves the deep link to MainActivity (singleTask)
 *   5. MainActivity calls [acceptOAuthCallback] to verify the returned state
 *   6. All collectors of [token] are notified — the app reacts immediately
 *
 * PAT flow (alternative):
 *   User pastes a GitHub Personal Access Token directly; the ViewModel validates
 *   it by calling the /user endpoint, then calls [saveValidatedToken] with the token.
 */
@Singleton
class GitHubAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val SERVER_URL = "https://oauth-server-beige.vercel.app"
        const val CALLBACK_SCHEME = "syncmd"
        const val CALLBACK_HOST = "auth"
        const val QUERY_TOKEN = "token"

        private const val PREF_FILE = "sync_md_prefs"
        private const val KEY_TOKEN = "github_token"
        private const val KEY_OWNER = "github_cache_owner"
        private val sessionLock = Any()
        private const val KEY_LOGIN = "github_login"
        private const val KEY_NAME  = "github_name"
        private const val KEY_AVATAR = "github_avatar_url"
        private const val KEY_EMAIL  = "github_email"
        private const val KEY_REPOS_JSON = "github_repos_json"
        private const val KEY_REPOS_CACHED_AT = "github_repos_cached_at"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    private val secure = GitHubSessionStore(context)
    /** Thrown messages reach repository screens through their error text. */
    private fun text(id: Int): String = context.getString(id).orEmpty()
    private val _sessionError = MutableStateFlow<UiText?>(null)
    /** Resolved at display time so the message follows the app language. */
    val sessionError: StateFlow<UiText?> = _sessionError.asStateFlow()
    private val _sessionRevision = MutableStateFlow(0L)
    private var signOutReadFailed = false
    val sessionRevision: StateFlow<Long> = _sessionRevision.asStateFlow()
    private var accountState: GitHubSessionStore.Session? = null
    private val _token = MutableStateFlow(loadSecureToken())
    private val _accounts = MutableStateFlow(accountSummaries())
    val accounts = _accounts.asStateFlow()
    /** Opaque, in-memory consent. Only the reviewed display name is exposed to the dialog. */
    class SignOutReview internal constructor(private val manager: GitHubAuthManager, private val revision: Long,
        private val session: GitHubSessionStore.Session?, val login: String?) {
        internal fun belongsTo(owner: GitHubAuthManager, currentRevision: Long, current: GitHubSessionStore.Session?) =
            manager === owner && revision == currentRevision && session == current
        internal fun matchesStored(current: GitHubSessionStore.Session?) = session == current?.normalized()
    }
    fun reviewSignOut(expectedRevision: Long): SignOutReview? = synchronized(sessionLock) {
        if (_sessionRevision.value != expectedRevision) return@synchronized null
        try {
            // A refreshed identity must be displayed before another click can review it.
            if (reconcilePersistedSession()) return@synchronized null
        } catch (e: Exception) {
            signOutReadFailed = true
            _sessionError.value = UiText.res(R.string.account_storage_attention)
            return@synchronized null
        }
        if (!isLoggedIn) return@synchronized null
        SignOutReview(this, expectedRevision, accountState, cached(KEY_LOGIN))
    }

    private class ChangedSignOutSession : IllegalStateException()
    private fun reconcilePersistedSession(): Boolean {
        val current = secure.read()?.normalized()
        if (signOutReadFailed) { _sessionError.value = null; signOutReadFailed = false }
        if (current == accountState) return false
        publishAccountState(current, true, resetCache = true)
        return true
    }

    /** Check consent again inside the store update, before its persistence callback can write. */
    fun signOut(review: SignOutReview): Boolean = synchronized(sessionLock) {
        if (!review.belongsTo(this, _sessionRevision.value, accountState)) return@synchronized false
        try {
            val next = secure.update { current ->
                if (!review.matchesStored(current)) throw ChangedSignOutSession()
                val state = current?.normalized() ?: GitHubSessionStore.Session()
                val id = state.activeAccountId
                state.copy(accounts = state.accounts.map { if (it.id == id) it.copy(token = null) else it },
                    token = null, activeAccountId = null, pendingState = null, createdAt = 0)
            }
            publishAccountState(next, true); true
        } catch (e: ChangedSignOutSession) {
            try { reconcilePersistedSession() } catch (_: Exception) {
                signOutReadFailed = true
                _sessionError.value = UiText.res(R.string.account_storage_attention)
            }
            false
        } catch (e: Exception) {
            _sessionError.value = UiText.res(R.string.account_sign_out_failed)
            false
        }
    }
    fun activeAccountId(): String? = synchronized(sessionLock) { accountState?.activeAccountId }
    fun needsAccountVerification(): Boolean = synchronized(sessionLock) { accountState?.activeAccountId?.startsWith("token:") == true }
    fun defaultAccountId(): String? = synchronized(sessionLock) { accountState?.defaultAccountId }
    private fun accountSummaries() = accountState?.accounts.orEmpty().map { GitHubAccountSummary(it.id, it.login, it.name, it.email, it.avatarUrl, it.token != null) }
    fun captureActiveAccount(): GitHubAccountSnapshot = synchronized(sessionLock) {
        val id = accountState?.activeAccountId ?: error("Select a GitHub account")
        GitHubAccountSnapshot(id, tokenForAccount(id))
    }
    fun accountSummary(id: String): GitHubAccountSummary? = synchronized(sessionLock) {
        val state = accountState ?: return@synchronized null
        val canonical = state.canonicalAccount(id)
        accountSummaries().firstOrNull { it.id == canonical }
    }
    fun tokenForAccount(id: String): String = synchronized(sessionLock) {
        val state = accountState ?: error(text(R.string.account_sign_in_repository_account))
        state.accounts.firstOrNull { it.id == state.canonicalAccount(id) }?.token
            ?: error(text(R.string.account_assigned_disconnected))
    }
    fun defaultToken(): String? = synchronized(sessionLock) { defaultAccountId()?.let(::tokenForAccount) }
    fun switchAccount(id: String): Boolean = synchronized(sessionLock) {
        try {
            val next = secure.update { current ->
                val state = (current ?: error("Sign in to GitHub")).normalized()
                val canonical = state.canonicalAccount(id)
                val account = state.accounts.firstOrNull { it.id == canonical && it.token != null } ?: error("Account is disconnected")
                state.copy(token = account.token, activeAccountId = account.id, pendingState = null, createdAt = 0)
            }
            publishAccountState(next, true); true
        } catch (e: Exception) { _sessionError.value = UiText.res(R.string.account_select_failed); false }
    }
    fun removeAccount(id: String): Boolean = synchronized(sessionLock) {
        try {
            val next = secure.update { current ->
                val state = (current ?: error("Account is unavailable")).normalized()
                val canonical = state.canonicalAccount(id)
                check(state.accounts.any { it.id == canonical })
                val removingActive = state.activeAccountId == canonical
                state.copy(accounts = state.accounts.map { if (it.id == canonical) it.copy(token = null) else it },
                    token = if (removingActive) null else state.token, activeAccountId = if (removingActive) null else state.activeAccountId,
                    pendingState = null, createdAt = 0)
            }
            publishAccountState(next, next.token != getToken()); true
        } catch (e: Exception) { _sessionError.value = UiText.res(R.string.account_remove_failed); false }
    }
    private fun publishAccountState(next: GitHubSessionStore.Session?, changed: Boolean, resetCache: Boolean = false) {
        accountState = next
        _accounts.value = accountSummaries()
        if (changed) publishToken(next?.token, resetCache)
    }
    val token: StateFlow<String?> = _token.asStateFlow()
    val isLoggedIn: Boolean get() = !_token.value.isNullOrBlank()
    fun getToken(): String? = _token.value
    private fun owner(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun ownsCache(token: String? = getToken()) = token != null && prefs.getString(KEY_OWNER, null) == owner(token)
    private fun cached(key: String): String? {
        val profile = accountState?.accounts?.firstOrNull { it.id == accountState?.activeAccountId }
        val stored = if (ownsCache()) prefs.getString(key, null) else null
        return stored ?: when (key) { KEY_LOGIN -> profile?.login; KEY_NAME -> profile?.name; KEY_EMAIL -> profile?.email; KEY_AVATAR -> profile?.avatarUrl; else -> null }
    }
    fun repositoryCredentials(path: String, url: String, store: RepositoryCredentialStore): com.bontecou.syncmd.data.models.Credentials? =
        store.resolve(path, url) { id -> id?.let(::tokenForAccount) ?: defaultToken() ?: error(text(R.string.account_select_repository_account)) }
    fun authorForAccount(id: String?): Pair<String, String> {
        val profile = id?.let(::accountSummary)
        return (profile?.name?.takeIf { it.isNotBlank() } ?: profile?.login ?: "Gitsync.md User") to
            (profile?.email?.takeIf { it.isNotBlank() } ?: "${profile?.login ?: "user"}@users.noreply.github.com")
    }

    /** Verify encrypted persistence before removing a legacy token; an existing secure record always wins. */
    private fun loadSecureToken(): String? = synchronized(sessionLock) {
        try {
            val existing = secure.read()
            val legacy = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
            val original = existing ?: if (legacy != null) GitHubSessionStore.Session(legacy) else null
            val session = original?.normalized()?.let { state ->
                val token = state.token
                val login = prefs.getString(KEY_LOGIN, null)?.takeIf { it.isNotBlank() }
                if (token != null && login != null && prefs.getString(KEY_OWNER, null) == owner(token)) {
                    state.copy(accounts = state.accounts.map { account ->
                        if (account.id == state.activeAccountId && account.login == null) account.copy(login = login,
                            name = prefs.getString(KEY_NAME, null), email = prefs.getString(KEY_EMAIL, null), avatarUrl = prefs.getString(KEY_AVATAR, null))
                        else account
                    })
                } else state
            }
            if (session != null && session != existing) secure.write(session)
            accountState = session
            if (legacy != null && session?.token == null && session?.pendingState != null) {
                _sessionError.value = UiText.res(R.string.account_finish_migration)
                return@synchronized null
            }
            if (legacy != null) {
                check(secure.read() == session) { "Secure GitHub migration could not be verified" }
                val editor = prefs.edit().remove(KEY_TOKEN)
                if (prefs.getString(KEY_OWNER, null) == null) {
                    // Old cache entries have no proven account owner. Re-fetch them.
                    editor.remove(KEY_LOGIN).remove(KEY_NAME).remove(KEY_AVATAR).remove(KEY_EMAIL)
                        .remove(KEY_REPOS_JSON).remove(KEY_REPOS_CACHED_AT)
                    session?.token?.let { editor.putString(KEY_OWNER, owner(it)) }
                }
                check(editor.commit()) { "Legacy GitHub credentials could not be removed. Retry secure storage." }
            }
            _sessionError.value = null
            session?.token
        } catch (e: Exception) {
            _sessionError.value = UiText.res(R.string.account_storage_attention)
            null
        }
    }

    // ─── Cached user profile ─────────────────────────────────────────────────

    private val _cachedLogin     = MutableStateFlow(cached(KEY_LOGIN))
    private val _cachedName      = MutableStateFlow(cached(KEY_NAME))
    private val _cachedAvatarUrl = MutableStateFlow(cached(KEY_AVATAR))
    private val _cachedEmail     = MutableStateFlow(cached(KEY_EMAIL))

    val cachedLogin: StateFlow<String?>     = _cachedLogin.asStateFlow()
    val cachedName: StateFlow<String?>      = _cachedName.asStateFlow()
    val cachedAvatarUrl: StateFlow<String?> = _cachedAvatarUrl.asStateFlow()
    val cachedEmail: StateFlow<String?>     = _cachedEmail.asStateFlow()

    /**
     * Best author name for git commits — full name preferred, login as fallback.
     */
    fun getAuthorName(): String =
        _cachedName.value?.takeIf { it.isNotBlank() }
            ?: _cachedLogin.value
            ?: "Gitsync.md User"

    /**
     * Best author email — primary email if known, otherwise the GitHub noreply address.
     */
    fun getAuthorEmail(): String =
        _cachedEmail.value?.takeIf { it.isNotBlank() }
            ?: "${_cachedLogin.value ?: "user"}@users.noreply.github.com"

    // ─── Public API ──────────────────────────────────────────────────────────

    /** The pending state is encrypted so a cold-start callback can be verified once. */
    fun createOAuthLoginUrl(now: Long = System.currentTimeMillis()): String? = synchronized(sessionLock) {
        try {
            val state = UUID.randomUUID().toString().replace("-", "")
            // Explicit sign-in can replace an unreadable session instead of trapping the user.
            val stored = runCatching { secure.read() }
            val recoverableLegacy = if (stored.isSuccess) prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } else null
            val current = stored.getOrNull() ?: GitHubSessionStore.Session(getToken() ?: recoverableLegacy,
                defaultAccountId = if (stored.isFailure) "unassigned" else null)
            secure.write(current.copy(pendingState = state, createdAt = now))
            _sessionError.value = null
            "$SERVER_URL/api/auth/login?state=$state"
        } catch (e: Exception) { _sessionError.value = UiText.res(R.string.account_prepare_sign_in_failed); null }
    }

    fun acceptOAuthCallback(uri: Uri, now: Long = System.currentTimeMillis()): Boolean = synchronized(sessionLock) {
        try {
            require(uri.isHierarchical && uri.scheme.equals(CALLBACK_SCHEME, true) && uri.host.equals(CALLBACK_HOST, true) &&
                uri.port == -1 && uri.userInfo == null && uri.path.isNullOrEmpty() && uri.fragment == null)
            val values = uri.encodedQuery.orEmpty().split('&').map { part ->
                Uri.decode(part.substringBefore('=')) to Uri.decode(part.substringAfter('=', ""))
            }
            require(values.all { it.first == "state" || it.first == QUERY_TOKEN })
            val states = values.filter { it.first == "state" }
            val tokens = values.filter { it.first == QUERY_TOKEN }
            require(states.size == 1 && tokens.size == 1)
            val returnedState = states.single().second
            val returnedToken = tokens.single().second
            require(returnedToken.isNotBlank() && returnedToken.length <= 16384)
            secure.update { current ->
                require(current?.pendingState != null && current.createdAt > 0 && now >= current.createdAt && now - current.createdAt <= 600_000 &&
                    MessageDigest.isEqual(returnedState.toByteArray(), current.pendingState.toByteArray()))
                current!!.withToken(returnedToken)
            }.also { publishAccountState(it, true) }
            true
        } catch (e: Exception) {
            _sessionError.value = UiText.res(R.string.account_sign_in_unverified)
            false
        }
    }

    /** Trusted PAT path: the caller must validate the token against GitHub before saving. */
    fun saveValidatedToken(token: String, user: GitHubUser? = null): Boolean = synchronized(sessionLock) {
        try {
            require(token.isNotBlank() && token.length <= 16384)
            val next = secure.update(recoverUnreadable = true) { (it ?: GitHubSessionStore.Session()).withToken(token, user) }
            publishAccountState(next, true)
            true
        } catch (e: Exception) { _sessionError.value = UiText.res(R.string.account_save_sign_in_failed); false }
    }

    private fun publishToken(token: String?, resetCache: Boolean = false) {
        val cleaned = if (_token.value != token || resetCache) {
            val result = prefs.edit().remove(KEY_TOKEN).remove(KEY_LOGIN).remove(KEY_NAME).remove(KEY_AVATAR).remove(KEY_EMAIL)
                .remove(KEY_REPOS_JSON).remove(KEY_REPOS_CACHED_AT).putString(KEY_OWNER, token?.let(::owner)).commit()
            _cachedLogin.value = null; _cachedName.value = null; _cachedAvatarUrl.value = null; _cachedEmail.value = null
            result
        } else prefs.edit().remove(KEY_TOKEN).commit()
        _sessionError.value = if (cleaned) null else UiText.res(R.string.account_cleanup_failed)
        _token.value = token
        _cachedLogin.value = cached(KEY_LOGIN); _cachedName.value = cached(KEY_NAME)
        _cachedEmail.value = cached(KEY_EMAIL); _cachedAvatarUrl.value = cached(KEY_AVATAR)
        _sessionRevision.value++
    }
    fun clearSessionError() { _sessionError.value = null }

    /** Persist basic profile info so the UI can show it immediately on next launch. */
    fun cacheUserProfile(
        login: String,
        name: String?,
        avatarUrl: String?,
        email: String? = null,
        expectedToken: String? = getToken(),
        githubId: Long? = null,
    ): Boolean = synchronized(sessionLock) {
        if (expectedToken == null || expectedToken != getToken()) return@synchronized false
        val next = secure.update { (it ?: GitHubSessionStore.Session(expectedToken)).withToken(expectedToken,
            GitHubUser(login, name, email, avatarUrl, githubId)) }
        publishAccountState(next, false)
        prefs.edit().putString(KEY_OWNER, owner(expectedToken))
            .putString(KEY_LOGIN, login)
            .putString(KEY_NAME, name)
            .putString(KEY_AVATAR, avatarUrl)
            .apply { if (email != null) putString(KEY_EMAIL, email) }
            .apply()
        _cachedLogin.value     = login
        _cachedName.value      = name
        _cachedAvatarUrl.value = avatarUrl
        if (email != null) _cachedEmail.value = email
        true
    }

    /** Persist repository list so RepoPicker can render instantly on next open. */
    fun cacheRepos(repos: List<GitHubRepo>, expectedToken: String? = getToken()): Boolean = synchronized(sessionLock) {
        if (expectedToken == null || expectedToken != getToken()) return@synchronized false
        val arr = JSONArray()
        repos.forEach { repo ->
            arr.put(
                JSONObject()
                    .put("id", repo.id)
                    .put("name", repo.name)
                    .put("fullName", repo.fullName)
                    .put("description", repo.description)
                    .put("isPrivate", repo.isPrivate)
                    .put("htmlUrl", repo.htmlUrl)
                    .put("defaultBranch", repo.defaultBranch)
                    .put("updatedAt", repo.updatedAt)
                    .put("ownerLogin", repo.ownerLogin)
                    .put("language", repo.language)
                    .put("stargazersCount", repo.stargazersCount)
            )
        }
        prefs.edit().putString(KEY_OWNER, owner(expectedToken))
            .putString(KEY_REPOS_JSON, arr.toString())
            .putLong(KEY_REPOS_CACHED_AT, System.currentTimeMillis())
            .apply()
        true
    }

    /** Return cached repos, or null when none are persisted / parse fails. */
    fun getCachedRepos(): List<GitHubRepo>? {
        if (!ownsCache()) return null
        val raw = prefs.getString(KEY_REPOS_JSON, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    add(
                        GitHubRepo(
                            id = j.getLong("id"),
                            name = j.getString("name"),
                            fullName = j.getString("fullName"),
                            description = j.optString("description").ifEmpty { null },
                            isPrivate = j.getBoolean("isPrivate"),
                            htmlUrl = j.getString("htmlUrl"),
                            defaultBranch = j.getString("defaultBranch"),
                            updatedAt = j.optString("updatedAt").ifEmpty { null },
                            ownerLogin = j.getString("ownerLogin"),
                            language = j.optString("language").ifEmpty { null },
                            stargazersCount = j.optInt("stargazersCount", 0)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Persist a signed-out tombstone before reporting success; stale callbacks cannot undo it. */
    fun signOut(): Boolean = synchronized(sessionLock) {
        val id = activeAccountId()
        if (id != null) removeAccount(id) else try {
            val next = secure.update { (it ?: GitHubSessionStore.Session()).copy(token = null, activeAccountId = null, pendingState = null, createdAt = 0) }
            publishAccountState(next, true); true
        } catch (e: Exception) { _sessionError.value = UiText.res(R.string.account_sign_out_failed); false }
    }
}

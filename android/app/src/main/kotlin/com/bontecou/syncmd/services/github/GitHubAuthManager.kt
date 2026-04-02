package com.bontecou.syncmd.services.github

import android.content.Context
import android.content.SharedPreferences
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
 *   5. MainActivity calls [handleCallback] with the extracted token
 *   6. All collectors of [token] are notified — the app reacts immediately
 *
 * PAT flow (alternative):
 *   User pastes a GitHub Personal Access Token directly; the ViewModel validates
 *   it by calling the /user endpoint, then calls [handleCallback] with the token.
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
        private const val KEY_LOGIN = "github_login"
        private const val KEY_NAME  = "github_name"
        private const val KEY_AVATAR = "github_avatar_url"
        private const val KEY_EMAIL  = "github_email"
        private const val KEY_REPOS_JSON = "github_repos_json"
        private const val KEY_REPOS_CACHED_AT = "github_repos_cached_at"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    private val _token = MutableStateFlow<String?>(prefs.getString(KEY_TOKEN, null))
    /** Emits the current access token, or null when signed out. */
    val token: StateFlow<String?> = _token.asStateFlow()

    val isLoggedIn: Boolean get() = !_token.value.isNullOrEmpty()

    fun getToken(): String? = _token.value

    // ─── Cached user profile ─────────────────────────────────────────────────

    private val _cachedLogin     = MutableStateFlow(prefs.getString(KEY_LOGIN, null))
    private val _cachedName      = MutableStateFlow(prefs.getString(KEY_NAME, null))
    private val _cachedAvatarUrl = MutableStateFlow(prefs.getString(KEY_AVATAR, null))
    private val _cachedEmail     = MutableStateFlow(prefs.getString(KEY_EMAIL, null))

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
            ?: "Sync.md User"

    /**
     * Best author email — primary email if known, otherwise the GitHub noreply address.
     */
    fun getAuthorEmail(): String =
        _cachedEmail.value?.takeIf { it.isNotBlank() }
            ?: "${_cachedLogin.value ?: "user"}@users.noreply.github.com"

    // ─── Public API ──────────────────────────────────────────────────────────

    /** Build the OAuth login URL for a given CSRF state value. */
    fun buildLoginUrl(state: String): String =
        "$SERVER_URL/api/auth/login?state=$state"

    /**
     * Called by MainActivity when the syncmd://auth deep link fires,
     * or by the ViewModel when a PAT has been validated.
     * Persists the token and notifies all observers.
     */
    fun handleCallback(token: String) {
        val oldToken = _token.value
        if (!oldToken.isNullOrBlank() && oldToken != token) {
            // Account/token switched — drop cached profile + repo list to avoid stale UI.
            prefs.edit()
                .remove(KEY_LOGIN)
                .remove(KEY_NAME)
                .remove(KEY_AVATAR)
                .remove(KEY_EMAIL)
                .remove(KEY_REPOS_JSON)
                .remove(KEY_REPOS_CACHED_AT)
                .apply()
            _cachedLogin.value = null
            _cachedName.value = null
            _cachedAvatarUrl.value = null
            _cachedEmail.value = null
        }

        prefs.edit().putString(KEY_TOKEN, token).apply()
        _token.value = token
    }

    /** Persist basic profile info so the UI can show it immediately on next launch. */
    fun cacheUserProfile(
        login: String,
        name: String?,
        avatarUrl: String?,
        email: String? = null
    ) {
        prefs.edit()
            .putString(KEY_LOGIN, login)
            .putString(KEY_NAME, name)
            .putString(KEY_AVATAR, avatarUrl)
            .apply { if (email != null) putString(KEY_EMAIL, email) }
            .apply()
        _cachedLogin.value     = login
        _cachedName.value      = name
        _cachedAvatarUrl.value = avatarUrl
        if (email != null) _cachedEmail.value = email
    }

    /** Persist repository list so RepoPicker can render instantly on next open. */
    fun cacheRepos(repos: List<GitHubRepo>) {
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
        prefs.edit()
            .putString(KEY_REPOS_JSON, arr.toString())
            .putLong(KEY_REPOS_CACHED_AT, System.currentTimeMillis())
            .apply()
    }

    /** Return cached repos, or null when none are persisted / parse fails. */
    fun getCachedRepos(): List<GitHubRepo>? {
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

    /** Remove all stored credentials, cached profile, and cached repo list. */
    fun signOut() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_LOGIN)
            .remove(KEY_NAME)
            .remove(KEY_AVATAR)
            .remove(KEY_EMAIL)
            .remove(KEY_REPOS_JSON)
            .remove(KEY_REPOS_CACHED_AT)
            .apply()
        _token.value          = null
        _cachedLogin.value    = null
        _cachedName.value     = null
        _cachedAvatarUrl.value = null
        _cachedEmail.value    = null
    }
}

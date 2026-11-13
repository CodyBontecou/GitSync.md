package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.security.GitHubSessionStore
import com.bontecou.syncmd.services.github.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** All preferences belong to an isolated fixture; actual account tokens are neither read nor changed. */
class GitHubSessionTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableSetOf<String>()
    private lateinit var context: Context
    private val prefs get() = context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE)
    @Before fun setup() {
        val prefix = "github-session-test-${UUID.randomUUID()}-"
        context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val key = prefix + name
                names += key
                return app.getSharedPreferences(key, mode)
            }
        }
    }
    @After fun cleanup() { names.forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() } }
    private fun callback(state: String, token: String = "oauth-fixture-token") = Uri.parse("syncmd://auth?state=$state&token=$token")
    private fun begin(auth: GitHubAuthManager, now: Long = 1_000_000L) = Uri.parse(auth.createOAuthLoginUrl(now)!!).getQueryParameter("state")!!
    private fun failOneSecureCommit(): Context {
        val real = context.getSharedPreferences("github_session", Context.MODE_PRIVATE)
        var failNext = true
        val failing = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val delegate = real.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                    override fun commit(): Boolean {
                        val result = delegate.commit()
                        if (failNext) { failNext = false; return false }
                        return result
                    }
                }
            }
        }
        return object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = if (name == "github_session") failing else super.getSharedPreferences(name, mode)
        }
    }

    @Test fun legacyMigrationVerifiesEncryptedStorageBeforeRemovingPlaintext() {
        prefs.edit().putString("github_token", "legacy-fixture-token").putString("github_login", "unverified-cache")
            .putString("github_repos_json", "[]").putInt("repository_count", 7).commit()
        val auth = GitHubAuthManager(context)
        assertEquals("legacy-fixture-token", auth.getToken())
        assertFalse(prefs.contains("github_token"))
        assertNull(auth.cachedLogin.value)
        assertEquals(7, prefs.getInt("repository_count", 0))
        val raw = context.getSharedPreferences("github_session", Context.MODE_PRIVATE).getString("session", "")!!
        assertFalse(raw.contains("legacy-fixture-token"))
        assertEquals("legacy-fixture-token", GitHubAuthManager(context).getToken())
    }
    @Test fun failedMigrationRetainsLegacyAndCanBeRetriedWithoutLosingTheToken() {
        prefs.edit().putString("github_token", "legacy-fixture-token").commit()
        val failed = GitHubAuthManager(failOneSecureCommit())
        assertNull(failed.getToken())
        assertNotNull(failed.sessionError.value)
        assertEquals("legacy-fixture-token", prefs.getString("github_token", null))
        assertNull(GitHubSessionStore(context).read())
        val state = begin(failed)
        assertEquals("legacy-fixture-token", GitHubSessionStore(context).read()!!.token)
        val restarted = GitHubAuthManager(context)
        assertEquals("legacy-fixture-token", restarted.getToken())
        assertFalse(prefs.contains("github_token"))
        assertTrue(restarted.acceptOAuthCallback(callback(state), 1_000_001L))
    }
    @Test fun secureSessionAndSignedOutTombstoneWinOverStaleLegacyTokens() {
        GitHubSessionStore(context).write(GitHubSessionStore.Session("new-fixture-token"))
        prefs.edit().putString("github_token", "old-fixture-token").putString("github_login", "old-user").commit()
        val auth = GitHubAuthManager(context)
        assertEquals("new-fixture-token", auth.getToken())
        assertNull(auth.cachedLogin.value)
        assertTrue(auth.signOut())
        prefs.edit().putString("github_token", "restored-stale-token").commit()
        assertNull(GitHubAuthManager(context).getToken())
        assertFalse(prefs.contains("github_token"))
    }
    @Test fun coldStartCallbackConsumesEncryptedStateOnce() {
        val state = begin(GitHubAuthManager(context))
        val raw = context.getSharedPreferences("github_session", Context.MODE_PRIVATE).getString("session", "")!!
        assertFalse(raw.contains(state))
        val restarted = GitHubAuthManager(context)
        assertTrue(restarted.acceptOAuthCallback(callback(state), 1_000_001L))
        assertEquals("oauth-fixture-token", restarted.getToken())
        val again = GitHubAuthManager(context)
        assertFalse(again.acceptOAuthCallback(callback(state, "replacement-fixture-token"), 1_000_002L))
        assertEquals("oauth-fixture-token", again.getToken())
        assertFalse(context.getSharedPreferences("github_session", Context.MODE_PRIVATE).getString("session", "")!!.contains("oauth-fixture-token"))
    }
    @Test fun malformedWrongAndDuplicateCallbacksDoNotConsumeTheValidPendingState() {
        val auth = GitHubAuthManager(context)
        assertTrue(auth.saveValidatedToken("existing-fixture-token"))
        val state = begin(auth)
        val urls = listOf("syncmd://auth?token=unrequested", "syncmd://auth?state=wrong&token=unrequested",
            "syncmd://auth/path?state=$state&token=x", "syncmd://auth?state=$state&token=x#fragment",
            "syncmd://other?state=$state&token=x", "syncmd://user@auth?state=$state&token=x",
            "syncmd://auth?state=$state&state=$state&token=x", "syncmd://auth?state=$state&token=x&%74oken=y")
        urls.forEach { assertFalse(auth.acceptOAuthCallback(Uri.parse(it), 1_000_001L)); assertEquals("existing-fixture-token", auth.getToken()) }
        assertTrue(auth.acceptOAuthCallback(callback(state), 1_000_002L))
    }
    @Test fun expiredFutureCancelledAndSupersededCallbacksCannotSignIn() {
        val auth = GitHubAuthManager(context)
        val expired = begin(auth)
        assertFalse(auth.acceptOAuthCallback(callback(expired), 1_600_001L))
        val future = begin(auth, 2_000_000L)
        assertFalse(auth.acceptOAuthCallback(callback(future), 1_999_999L))
        val old = begin(auth)
        val current = begin(auth)
        assertFalse(auth.acceptOAuthCallback(callback(old), 1_000_001L))
        assertTrue(auth.saveValidatedToken("pat-fixture-token"))
        assertFalse(auth.acceptOAuthCallback(callback(current), 1_000_001L))
        val signingOut = begin(auth)
        assertTrue(auth.signOut())
        assertFalse(GitHubAuthManager(context).acceptOAuthCallback(callback(signingOut), 1_000_001L))
        assertNull(GitHubAuthManager(context).getToken())
    }
    @Test fun failedTokenReplacementOrSignOutNeverPublishesSuccessOrDestroysTheOldSession() {
        assertTrue(GitHubAuthManager(context).saveValidatedToken("old-fixture-token"))
        val failed = GitHubAuthManager(failOneSecureCommit())
        assertFalse(failed.saveValidatedToken("new-fixture-token"))
        assertEquals("old-fixture-token", failed.getToken())
        assertEquals("old-fixture-token", GitHubAuthManager(context).getToken())
        val failedSignOut = GitHubAuthManager(failOneSecureCommit())
        assertFalse(failedSignOut.signOut())
        assertEquals("old-fixture-token", failedSignOut.getToken())
        assertEquals("old-fixture-token", GitHubAuthManager(context).getToken())
    }
    @Test fun staleProfileAndRepositoryResponsesCannotCrossAccountBoundaries() {
        val auth = GitHubAuthManager(context)
        auth.saveValidatedToken("alpha-fixture-token")
        assertTrue(auth.cacheUserProfile("alpha", "Alpha", null, "alpha@example.invalid", "alpha-fixture-token"))
        assertTrue(auth.cacheRepos(emptyList(), "alpha-fixture-token"))
        auth.saveValidatedToken("beta-fixture-token")
        assertNull(auth.cachedLogin.value)
        assertNull(auth.getCachedRepos())
        assertFalse(auth.cacheUserProfile("alpha", "Alpha", null, null, "alpha-fixture-token"))
        assertFalse(auth.cacheRepos(emptyList(), "alpha-fixture-token"))
        assertTrue(auth.cacheUserProfile("beta", "Beta", null, "beta@example.invalid", "beta-fixture-token"))
        assertEquals("beta", GitHubAuthManager(context).cachedLogin.value)
    }

    @Test fun explicitBrowserSignInCanRecoverAnUnreadableEncryptedSession() {
        context.getSharedPreferences("github_session", Context.MODE_PRIVATE).edit().putString("session", "broken-fixture-record").commit()
        prefs.edit().putString("github_token", "retained-legacy-fixture-token").commit()
        val auth = GitHubAuthManager(context)
        assertNull(auth.getToken())
        assertNotNull(auth.sessionError.value)
        val state = begin(auth)
        val restarted = GitHubAuthManager(context)
        assertNull(restarted.getToken())
        assertEquals("retained-legacy-fixture-token", prefs.getString("github_token", null))
        assertTrue(restarted.acceptOAuthCallback(callback(state), 1_000_001L))
        assertEquals("oauth-fixture-token", GitHubAuthManager(context).getToken())
        assertFalse(prefs.contains("github_token"))
        assertTrue(runCatching { GitHubAuthManager(context).defaultToken() }.isFailure)
    }
}

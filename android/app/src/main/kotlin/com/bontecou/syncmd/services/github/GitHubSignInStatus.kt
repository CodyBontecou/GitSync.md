package com.bontecou.syncmd.services.github

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers that GitHub rejected a saved sign-in (HTTP 401/403 or "not authorized" on a fetch or push).
 * Home shows "GitHub sign-in expired · 2h ago" and pauses the vaults that use that account. The record
 * is tied to the rejected token by a hash, so signing in again (a new token) ends the state by itself.
 * No secret is stored.
 */
@Singleton
class GitHubSignInStatus @Inject constructor(@ApplicationContext context: Context) {
    data class Expired(val tokenOwner: String, val accountId: String?, val at: Long)

    private val prefs = context.getSharedPreferences("github_sign_in_status", Context.MODE_PRIVATE)
    private val _expired = MutableStateFlow(read())
    val expired: StateFlow<Expired?> = _expired.asStateFlow()

    /** The expiry applies only while [token] is still the rejected one. */
    fun expiredFor(token: String?): Expired? = _expired.value?.takeIf { token != null && it.tokenOwner == owner(token) }

    fun markExpired(token: String, accountId: String?, at: Long = System.currentTimeMillis()) {
        val current = _expired.value
        if (current != null && current.tokenOwner == owner(token)) return // keep the first time it failed
        val next = Expired(owner(token), accountId, at)
        prefs.edit().putString(KEY_OWNER, next.tokenOwner).putString(KEY_ACCOUNT, accountId).putLong(KEY_AT, at).apply()
        _expired.value = next
    }

    /** A successful GitHub request with [token] proves it works again. */
    fun markWorking(token: String) {
        if (_expired.value?.tokenOwner != owner(token)) return
        prefs.edit().clear().apply()
        _expired.value = null
    }

    private fun read(): Expired? {
        val owner = prefs.getString(KEY_OWNER, null) ?: return null
        return Expired(owner, prefs.getString(KEY_ACCOUNT, null), prefs.getLong(KEY_AT, 0L))
    }

    companion object {
        private const val KEY_OWNER = "token_owner"
        private const val KEY_ACCOUNT = "account_id"
        private const val KEY_AT = "expired_at"
        fun owner(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(("sign-in-status:" + token).toByteArray()).joinToString("") { "%02x".format(it) }

        /** Git transport and GitHub API messages that mean the credential itself was refused. */
        fun isAuthenticationFailure(error: Throwable?): Boolean {
            var cause = error
            while (cause != null) {
                if (cause is GitHubApiException && cause.isUnauthorized) return true
                val text = cause.message.orEmpty().lowercase()
                if ("not authorized" in text || "authentication is required" in text || "authentication failed" in text ||
                    "invalid username or password" in text || "http 401" in text || " 401 " in "$text ") return true
                cause = cause.cause
            }
            return false
        }
    }
}

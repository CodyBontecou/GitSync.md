package com.bontecou.syncmd.security

import com.bontecou.syncmd.services.github.GitHubUser
import java.security.MessageDigest

/** Secrets remain inside the encrypted session; UI summaries deliberately omit tokens. */
data class StoredGitHubAccount(val id: String, val token: String?, val githubId: Long? = null,
    val login: String? = null, val name: String? = null, val email: String? = null, val avatarUrl: String? = null)
data class GitHubAccountSummary(val id: String, val login: String?, val name: String?, val email: String?, val avatarUrl: String?, val connected: Boolean)
data class GitHubAccountSnapshot(val id: String, val token: String)

internal fun GitHubSessionStore.Session.canonicalAccount(id: String): String {
    var current = id
    val seen = mutableSetOf<String>()
    while (aliases.containsKey(current)) { check(seen.add(current)) { "Account reference needs repair" }; current = aliases.getValue(current) }
    return current
}
internal fun GitHubSessionStore.Session.normalized(): GitHubSessionStore.Session {
    if (token == null) return copy(version = 2,
        defaultAccountId = defaultAccountId ?: if (version == 1 && pendingState == null) "unassigned" else null)
    val match = accounts.firstOrNull { it.token == token }
    val account = match ?: StoredGitHubAccount("token:" + MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }, token)
    return copy(accounts = if (match != null) accounts else accounts + account, activeAccountId = account.id,
        defaultAccountId = defaultAccountId ?: account.id, version = 2)
}
internal fun GitHubSessionStore.Session.withToken(value: String, user: GitHubUser? = null): GitHubSessionStore.Session {
    val base = normalized()
    val byToken = base.accounts.firstOrNull { it.token == value }
    val id = user?.id?.takeIf { it > 0 }?.let { "github:$it" } ?: byToken?.id ?: "token:" +
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    val existing = base.accounts.firstOrNull { it.id == id } ?: byToken
    val updated = StoredGitHubAccount(id, value, user?.id ?: existing?.githubId, user?.login ?: existing?.login,
        user?.name ?: existing?.name, user?.email ?: existing?.email, user?.avatarUrl ?: existing?.avatarUrl)
    val aliases = if (byToken != null && byToken.id != id) base.aliases + (byToken.id to id) else base.aliases
    return base.copy(token = value, accounts = base.accounts.filterNot { it.id == id || it.id == byToken?.id } + updated,
        activeAccountId = id, defaultAccountId = base.defaultAccountId ?: id, aliases = aliases, pendingState = null, createdAt = 0)
}

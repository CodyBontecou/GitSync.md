package com.bontecou.syncmd.security

import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.repositories.RepositoryIdentity
import javax.inject.Inject

/**
 * The author a commit in a vault uses: the vault's own `.git/config` user, else the app-wide
 * "Commit as" default from Settings, else the vault's GitHub account.
 */
class RepositoryAuthors(private val auth: GitHubAuthManager, private val credentials: RepositoryCredentialStore,
    private val defaults: DefaultAuthorSource) {
    @Inject constructor(auth: GitHubAuthManager, credentials: RepositoryCredentialStore, store: DefaultAuthorStore) :
        this(auth, credentials, store as DefaultAuthorSource)
    /** Without the app-wide default (tests): vault config, then the GitHub account. */
    constructor(auth: GitHubAuthManager, credentials: RepositoryCredentialStore) : this(auth, credentials, DefaultAuthorStore.None)

    suspend fun resolve(path: String): Pair<String, String> {
        val plan = RepositoryIdentity().inspect(path)
        if (!plan.name.isNullOrBlank() || !plan.email.isNullOrBlank())
            return RepositoryIdentity.validated(plan.name.orEmpty(), plan.email.orEmpty())
        return fallback(path)
    }

    /** What new commits use when the vault has no author of its own: the global default, then the account. */
    fun fallback(path: String?): Pair<String, String> {
        defaults.author.value?.let { return it.name to it.email }
        return accountAuthor(path)
    }

    /** The GitHub account author this vault (or, without a path, the default account) would use. */
    fun accountAuthor(path: String?): Pair<String, String> {
        val summary = path?.let { credentials.summary(it) }
        val id = if (summary?.usesGithubAccount == true) summary.accountId ?: auth.defaultAccountId() else auth.defaultAccountId()
        val fallback = auth.authorForAccount(id)
        return RepositoryIdentity.validated(fallback.first, fallback.second)
    }
}

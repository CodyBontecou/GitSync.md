package com.bontecou.syncmd.security

import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.repositories.RepositoryIdentity
import javax.inject.Inject

class RepositoryAuthors @Inject constructor(private val auth: GitHubAuthManager, private val credentials: RepositoryCredentialStore) {
    suspend fun resolve(path: String): Pair<String, String> {
        val plan = RepositoryIdentity().inspect(path)
        if (!plan.name.isNullOrBlank() || !plan.email.isNullOrBlank())
            return RepositoryIdentity.validated(plan.name.orEmpty(), plan.email.orEmpty())
        val summary = credentials.summary(path)
        val id = if (summary?.usesGithubAccount == true) summary.accountId ?: auth.defaultAccountId() else auth.defaultAccountId()
        val fallback = auth.authorForAccount(id)
        return RepositoryIdentity.validated(fallback.first, fallback.second)
    }
}

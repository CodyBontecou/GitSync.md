package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.services.repositories.RepositoryIdentity
import org.eclipse.jgit.api.Git

typealias RepositoryAuthorResolver = suspend (String) -> Pair<String, String>

/** Per-operation identity for JGit commands that obtain their signature from repository config. */
internal suspend fun Git.useAuthor(path: String, resolver: RepositoryAuthorResolver?) {
    val author = resolver?.invoke(path) ?: return
    val valid = RepositoryIdentity.validated(author.first, author.second)
    // Deliberately in memory: choosing a browsing account must never rewrite a repository's config.
    repository.config.setString("user", null, "name", valid.first)
    repository.config.setString("user", null, "email", valid.second)
}

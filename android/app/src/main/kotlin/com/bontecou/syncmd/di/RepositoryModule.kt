package com.bontecou.syncmd.di

import com.bontecou.syncmd.domain.repository.BranchRepository
import com.bontecou.syncmd.domain.repository.ConflictRepository
import com.bontecou.syncmd.domain.repository.DiffRepository
import com.bontecou.syncmd.domain.repository.GitRepository
import com.bontecou.syncmd.domain.repository.HistoryRepository
import com.bontecou.syncmd.domain.repository.PullRepository
import com.bontecou.syncmd.domain.repository.RemoteRepository
import com.bontecou.syncmd.services.git.LocalBranchRepository
import com.bontecou.syncmd.services.git.LocalConflictRepository
import com.bontecou.syncmd.services.git.LocalDiffRepository
import com.bontecou.syncmd.services.git.LocalGitRepository
import com.bontecou.syncmd.services.git.LocalHistoryRepository
import com.bontecou.syncmd.services.git.LocalPullRepository
import com.bontecou.syncmd.services.git.LocalRemoteRepository
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.security.RepositoryCredentialStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing repository implementations for dependency injection.
 * Repositories are stateless and operate on dynamic repository paths provided at runtime.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides
    @Singleton
    fun provideLfs(auth: GitHubAuthManager, credentials: RepositoryCredentialStore) =
        com.bontecou.syncmd.services.git.lfs.LfsRepository(tokenProvider = { auth.defaultToken() },
            credentialResolver = { path, url -> auth.repositoryCredentials(path, url, credentials) }, credentialRevision = credentials::recordVersion)

    @Provides
    @Singleton
    fun provideRebase(auth: GitHubAuthManager, credentials: RepositoryCredentialStore, authors: com.bontecou.syncmd.security.RepositoryAuthors) =
        com.bontecou.syncmd.services.git.RepositoryRebase(tokenProvider = { auth.defaultToken() },
            credentials = { path, url -> auth.repositoryCredentials(path, url, credentials) }, author = authors::resolve, credentialRevision = credentials::recordVersion)

    @Provides
    @Singleton
    fun provideRemoteMerge(auth: GitHubAuthManager, credentials: RepositoryCredentialStore, authors: com.bontecou.syncmd.security.RepositoryAuthors) =
        com.bontecou.syncmd.services.git.RepositoryRemoteMerge(tokenProvider = { auth.defaultToken() },
            credentials = { path, url -> auth.repositoryCredentials(path, url, credentials) }, authorResolver = authors::resolve,
            credentialRevision = { path -> credentials.recordVersion(path).orEmpty() + ":" +
                com.bontecou.syncmd.automation.RepositoryShortcutService.accountBinding(path, auth, credentials) })

    @Provides
    @Singleton
    fun provideGitRepository(authManager: GitHubAuthManager, credentials: RepositoryCredentialStore): GitRepository {
        return LocalGitRepository(tokenProvider = { authManager.defaultToken() }, credentialResolver = { path, url -> authManager.repositoryCredentials(path, url, credentials) })
    }

    @Provides
    @Singleton
    fun providePullRepository(authManager: GitHubAuthManager, credentials: RepositoryCredentialStore): PullRepository {
        return LocalPullRepository(tokenProvider = { authManager.defaultToken() },
            credentialResolver = { path, url -> authManager.repositoryCredentials(path, url, credentials) },
            credentialRevision = { path -> credentials.recordVersion(path).orEmpty() + ":" +
                com.bontecou.syncmd.automation.RepositoryShortcutService.accountBinding(path, authManager, credentials) })
    }

    @Provides
    @Singleton
    fun provideDiffRepository(): DiffRepository {
        return LocalDiffRepository()
    }

    @Provides
    @Singleton
    fun provideBranchRepository(authors: com.bontecou.syncmd.security.RepositoryAuthors, auth: GitHubAuthManager, credentials: RepositoryCredentialStore): BranchRepository {
        return LocalBranchRepository(authors::resolve, tokenProvider = { auth.defaultToken() },
            credentialResolver = { path, url -> auth.repositoryCredentials(path, url, credentials) })
    }

    @Provides
    @Singleton
    fun provideConflictRepository(authors: com.bontecou.syncmd.security.RepositoryAuthors, auth: GitHubAuthManager, credentials: RepositoryCredentialStore): ConflictRepository {
        return LocalConflictRepository(authors::resolve, tokenProvider = { auth.defaultToken() },
            credentialResolver = { path, url -> auth.repositoryCredentials(path, url, credentials) })
    }

    @Provides
    @Singleton
    fun provideHistoryRepository(authors: com.bontecou.syncmd.security.RepositoryAuthors): HistoryRepository {
        return LocalHistoryRepository(authors::resolve)
    }

    @Provides
    @Singleton
    fun provideRemoteRepository(authManager: GitHubAuthManager, credentials: RepositoryCredentialStore): RemoteRepository {
        return LocalRemoteRepository(tokenProvider = { authManager.defaultToken() }, credentialResolver = { path, url -> authManager.repositoryCredentials(path, url, credentials) })
    }
}

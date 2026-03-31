package com.bontecou.syncmd.di

import com.bontecou.syncmd.domain.repository.BranchRepository
import com.bontecou.syncmd.domain.repository.ConflictRepository
import com.bontecou.syncmd.domain.repository.DiffRepository
import com.bontecou.syncmd.domain.repository.HistoryRepository
import com.bontecou.syncmd.domain.repository.PullRepository
import com.bontecou.syncmd.services.git.LocalBranchRepository
import com.bontecou.syncmd.services.git.LocalConflictRepository
import com.bontecou.syncmd.services.git.LocalDiffRepository
import com.bontecou.syncmd.services.git.LocalHistoryRepository
import com.bontecou.syncmd.services.git.LocalPullRepository
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
    fun providePullRepository(): PullRepository {
        return LocalPullRepository()
    }

    @Provides
    @Singleton
    fun provideDiffRepository(): DiffRepository {
        return LocalDiffRepository()
    }

    @Provides
    @Singleton
    fun provideBranchRepository(): BranchRepository {
        return LocalBranchRepository()
    }

    @Provides
    @Singleton
    fun provideConflictRepository(): ConflictRepository {
        return LocalConflictRepository()
    }

    @Provides
    @Singleton
    fun provideHistoryRepository(): HistoryRepository {
        return LocalHistoryRepository()
    }
}

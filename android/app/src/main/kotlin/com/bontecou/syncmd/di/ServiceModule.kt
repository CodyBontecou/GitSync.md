package com.bontecou.syncmd.di

import com.bontecou.syncmd.domain.repository.BranchRepository
import com.bontecou.syncmd.domain.repository.ConflictRepository
import com.bontecou.syncmd.domain.repository.DiffRepository
import com.bontecou.syncmd.domain.repository.HistoryRepository
import com.bontecou.syncmd.domain.repository.PullRepository
import com.bontecou.syncmd.domain.repository.RemoteRepository
import com.bontecou.syncmd.services.git.BranchService
import com.bontecou.syncmd.services.git.ConflictService
import com.bontecou.syncmd.services.git.DiffService
import com.bontecou.syncmd.services.git.HistoryService
import com.bontecou.syncmd.services.git.PullService
import com.bontecou.syncmd.services.git.PushService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing service implementations for dependency injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun providePullService(pullRepository: PullRepository): PullService {
        return PullService(pullRepository)
    }

    @Provides
    @Singleton
    fun provideDiffService(diffRepository: DiffRepository): DiffService {
        return DiffService(diffRepository)
    }

    @Provides
    @Singleton
    fun provideBranchService(branchRepository: BranchRepository): BranchService {
        return BranchService(branchRepository)
    }

    @Provides
    @Singleton
    fun provideConflictService(conflictRepository: ConflictRepository): ConflictService {
        return ConflictService(conflictRepository)
    }

    @Provides
    @Singleton
    fun provideHistoryService(historyRepository: HistoryRepository): HistoryService {
        return HistoryService(historyRepository)
    }

    @Provides
    @Singleton
    fun providePushService(
        remoteRepository: RemoteRepository,
        branchRepository: BranchRepository,
        historyRepository: HistoryRepository
    ): PushService {
        return PushService(remoteRepository, branchRepository, historyRepository)
    }
}

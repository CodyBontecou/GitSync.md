package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.RepositoryStatus
import com.bontecou.syncmd.data.models.SafePullPlan
import com.bontecou.syncmd.data.models.SafePullResult
import com.bontecou.syncmd.domain.repository.PullRepository

/**
 * Service for high-level safe pull operations.
 * Provides rich status monitoring and safe pull planning.
 */
class PullService(private val pullRepository: PullRepository) {

    /**
     * Get rich repository status
     */
    suspend fun getStatus(repoPath: String): Result<RepositoryStatus> {
        return pullRepository.getStatus(repoPath)
    }

    /**
     * Plan a pull before executing
     */
    suspend fun planPull(repoPath: String): Result<SafePullPlan> {
        return pullRepository.planPull(repoPath)
    }

    /**
     * Check if repository is clean
     */
    suspend fun isClean(repoPath: String): Result<Boolean> {
        return pullRepository.getStatus(repoPath).map { it.isClean }
    }

    /**
     * Get number of uncommitted changes
     */
    suspend fun getChangeCount(repoPath: String): Result<Int> {
        return pullRepository.getStatus(repoPath).map { it.totalChanges }
    }

    /**
     * Execute a safe pull
     */
    suspend fun pull(repoPath: String): Result<SafePullResult> {
        return pullRepository.executePull(repoPath)
    }

    /**
     * Fetch from remote without merging (safe with dirty tree)
     */
    suspend fun fetch(repoPath: String): Result<Unit> {
        return pullRepository.fetch(repoPath)
    }

    /**
     * Check synchronization status with remote
     */
    suspend fun getSyncStatus(repoPath: String): Result<Pair<Int, Int>> {
        return try {
            val aheadResult = pullRepository.getCommitsAhead(repoPath)
            if (!aheadResult.isSuccess) return aheadResult.map { Pair(0, 0) }
            
            val behindResult = pullRepository.getCommitsBehind(repoPath)
            if (!behindResult.isSuccess) return behindResult.map { Pair(0, 0) }
            
            val ahead = aheadResult.getOrNull() ?: 0
            val behind = behindResult.getOrNull() ?: 0
            
            Result.success(Pair(ahead, behind))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Determine if pull is safe to execute
     */
    suspend fun canPull(repoPath: String): Result<Boolean> {
        return planPull(repoPath).map { it.canPull }
    }

    /**
     * Get reason why pull is blocked (if applicable)
     */
    suspend fun getPullBlockingReason(repoPath: String): Result<String?> {
        return planPull(repoPath).map { it.blockingReason }
    }
}

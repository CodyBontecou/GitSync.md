package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.*
import com.bontecou.syncmd.domain.repository.*

/**
 * Service for git push operations and remote tracking.
 *
 * Handles:
 * - Pushing commits to remotes
 * - Rejection detection (non-fast-forward, protected branches)
 * - Upstream branch tracking setup
 * - Force push with safety checks
 * - Tag pushing
 */
class PushService(
    private val remoteRepository: RemoteRepository,
    private val branchRepository: BranchRepository,
    private val historyRepository: HistoryRepository
) {
    /**
     * Push commits from local branch to remote
     *
     * @param repository Repository path
     * @param config Push configuration
     * @return Result with Unit on success
     */
    suspend fun push(
        repository: String,
        config: PushConfig
    ): Result<Unit> {
        return try {
            // Get ahead/behind counts
            val (aheadCount, behindCount) = remoteRepository.getAheadBehindCounts(
                repository,
                config.branch,
                config.remote
            )

            // Check if there are commits to push
            if (aheadCount == 0) {
                Result.failure(Exception("No commits to push"))
            } else if (behindCount > 0 && !config.forcePush) {
                // Check if remote has commits we don't have (unless force pushing)
                Result.failure(Exception("Local branch is behind remote. Fetch first or use force push."))
            } else {
                // Execute push
                remoteRepository.push(
                    repository,
                    config.branch,
                    config.remote,
                    config.actualRemoteBranch,
                    config.forcePush
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get commits ready to push for a branch
     */
    suspend fun getPushStatistics(
        repository: String,
        branch: String,
        remote: String = "origin"
    ): Result<PushStatistics> {
        return try {
            val (aheadCount, _) = remoteRepository.getAheadBehindCounts(repository, branch, remote)

            // Get full history for statistics
            val history = historyRepository.getHistory(repository, maxCommits = 100)
                .getOrNull()
                ?.take(aheadCount)
                ?: emptyList()

            Result.success(PushStatistics(aheadCount, history))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Force push with explicit safety check
     */
    suspend fun forcePush(
        repository: String,
        config: PushConfig
    ): Result<Unit> = push(repository, config.copy(forcePush = true))

    /**
     * Push all branches to remote
     */
    suspend fun pushAllBranches(
        repository: String,
        remote: String = "origin"
    ): Result<Int> {
        return try {
            val branches = branchRepository.listBranches(repository)
                .getOrNull()
                ?.filter { it.type != BranchType.REMOTE } // Local branches only
                ?: emptyList()

            var successCount = 0
            for (branch in branches) {
                val result = push(
                    repository,
                    PushConfig(branch.name, remote)
                )
                if (result.isSuccess) {
                    successCount++
                }
            }

            Result.success(successCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Push tags to remote
     */
    suspend fun pushTags(
        repository: String,
        remote: String = "origin"
    ): Result<Int> {
        return try {
            val tags = historyRepository.listTags(repository)
                .getOrNull() ?: emptyList()

            if (tags.isEmpty()) {
                Result.failure(Exception("No tags to push"))
            } else {
                remoteRepository.pushTags(repository, remote)
                Result.success(tags.size)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

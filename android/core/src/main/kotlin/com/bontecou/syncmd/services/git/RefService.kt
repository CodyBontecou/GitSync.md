package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.*
import com.bontecou.syncmd.domain.repository.*

/**
 * Service for managing git references (refs).
 *
 * Handles:
 * - Remote references (branches at remote)
 * - Local references
 * - Tracking branch information
 * - Ahead/behind commit counting
 * - Ref validation and manipulation
 */
class RefService(
    private val remoteRepository: RemoteRepository,
    private val branchRepository: BranchRepository
) {
    /**
     * Get all remote references for a repository
     */
    suspend fun getRemoteRefs(repository: String, remote: String = "origin"): Result<List<RemoteRef>> =
        remoteRepository.listRemoteRefs(repository, remote)

    /**
     * Get count of commits ahead and behind for a branch
     */
    suspend fun getAheadBehind(
        repository: String,
        localBranch: String,
        remote: String = "origin"
    ): Result<Pair<Int, Int>> = try {
        val counts = remoteRepository.getAheadBehindCounts(repository, localBranch, remote)
        Result.success(counts)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Check if a local branch can be pushed (has commits ahead)
     */
    suspend fun canPush(
        repository: String,
        localBranch: String,
        remote: String = "origin"
    ): Result<Boolean> = try {
        val (ahead, _) = remoteRepository.getAheadBehindCounts(repository, localBranch, remote)
        Result.success(ahead > 0)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Check if a local branch can be pulled (has commits behind)
     */
    suspend fun canPull(
        repository: String,
        localBranch: String,
        remote: String = "origin"
    ): Result<Boolean> = try {
        val (_, behind) = remoteRepository.getAheadBehindCounts(repository, localBranch, remote)
        Result.success(behind > 0)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Check if branch is up-to-date with remote
     */
    suspend fun isUpToDate(
        repository: String,
        localBranch: String,
        remote: String = "origin"
    ): Result<Boolean> = try {
        val (ahead, behind) = remoteRepository.getAheadBehindCounts(repository, localBranch, remote)
        Result.success(ahead == 0 && behind == 0)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Check if branches have diverged
     */
    suspend fun isDiverged(
        repository: String,
        localBranch: String,
        remote: String = "origin"
    ): Result<Boolean> = try {
        val (ahead, behind) = remoteRepository.getAheadBehindCounts(repository, localBranch, remote)
        Result.success(ahead > 0 && behind > 0)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Get detailed tracking status for a branch
     */
    suspend fun getTrackingStatus(
        repository: String,
        localBranch: String
    ): Result<UpstreamTracking?> = try {
        val upstream = remoteRepository.getUpstreamTracking(repository, localBranch)
        Result.success(upstream)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Verify a remote ref exists
     */
    suspend fun remoteRefExists(
        repository: String,
        remote: String,
        branch: String
    ): Result<Boolean> = remoteRepository.remoteRefExists(repository, remote, branch)

    /**
     * Validate if a ref exists (local or remote)
     */
    suspend fun refExists(
        repository: String,
        ref: String
    ): Result<Boolean> = try {
        val branches = branchRepository.listBranches(repository)
            .getOrNull() ?: emptyList()
        Result.success(branches.any { it.name == ref })
    } catch (e: Exception) {
        Result.failure(e)
    }
}

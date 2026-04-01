package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.*
import com.bontecou.syncmd.domain.repository.*

/**
 * Service for remote repository management.
 *
 * Handles:
 * - Listing and managing remotes (add, remove, update)
 * - Remote URL configuration
 * - Fetching from specific remotes
 * - Upstream branch tracking
 * - Remote branch analysis
 */
class RemoteService(
    private val remoteRepository: RemoteRepository,
    private val branchRepository: BranchRepository
) {
    /**
     * List all configured remotes
     */
    suspend fun listRemotes(repository: String): Result<List<Remote>> =
        remoteRepository.listRemotes(repository)

    /**
     * Get a specific remote by name
     */
    suspend fun getRemote(repository: String, name: String): Result<Remote?> = try {
        val remotes = remoteRepository.listRemotes(repository).getOrNull() ?: emptyList()
        Result.success(remotes.firstOrNull { it.name == name })
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Add a new remote
     */
    suspend fun addRemote(
        repository: String,
        name: String,
        url: String
    ): Result<Unit> = remoteRepository.addRemote(repository, name, url)

    /**
     * Remove a remote
     */
    suspend fun removeRemote(repository: String, name: String): Result<Unit> =
        remoteRepository.removeRemote(repository, name)

    /**
     * Get the URL of a remote
     */
    suspend fun getRemoteUrl(repository: String, name: String): Result<String?> =
        remoteRepository.getRemoteUrl(repository, name)

    /**
     * Update a remote's URL
     */
    suspend fun updateRemoteUrl(
        repository: String,
        name: String,
        newUrl: String
    ): Result<Unit> = try {
        remoteRepository.removeRemote(repository, name)
        remoteRepository.addRemote(repository, name, newUrl)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Fetch from a specific remote
     */
    suspend fun fetchRemote(
        repository: String,
        remote: String
    ): Result<Unit> = remoteRepository.fetch(repository, remote)

    /**
     * Get remote branches for a repository
     */
    suspend fun listRemoteBranches(
        repository: String,
        remote: String = "origin"
    ): Result<List<RemoteRef>> =
        remoteRepository.listRemoteRefs(repository, remote)

    /**
     * Get default remote (usually "origin")
     */
    suspend fun getDefaultRemote(repository: String): Result<Remote?> = try {
        val remotes = remoteRepository.listRemotes(repository).getOrNull() ?: emptyList()
        Result.success(remotes.firstOrNull { it.isDefault } ?: remotes.firstOrNull())
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Get upstream tracking status for a branch
     */
    suspend fun getUpstreamTracking(
        repository: String,
        branch: String
    ): Result<UpstreamTracking?> = try {
        val upstream = remoteRepository.getUpstreamTracking(repository, branch)
        Result.success(upstream)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Set upstream tracking for a branch
     * Note: Implementation deferred to Phase 7.2 (requires BranchRepository update)
     */
    suspend fun setUpstreamTracking(
        @Suppress("UNUSED_PARAMETER") repository: String,
        @Suppress("UNUSED_PARAMETER") localBranch: String,
        @Suppress("UNUSED_PARAMETER") remoteName: String,
        @Suppress("UNUSED_PARAMETER") remoteBranch: String
    ): Result<Unit> = Result.success(Unit)

    /**
     * Unset upstream tracking for a branch
     * Note: Implementation deferred to Phase 7.2 (requires BranchRepository update)
     */
    suspend fun unsetUpstreamTracking(
        @Suppress("UNUSED_PARAMETER") repository: String,
        @Suppress("UNUSED_PARAMETER") branch: String
    ): Result<Unit> = Result.success(Unit)

    /**
     * Get all tracking branches (local branches with upstream set)
     */
    suspend fun getTrackingBranches(repository: String): Result<List<UpstreamTracking>> = try {
        val branches = branchRepository.listBranches(repository)
            .getOrNull()
            ?.filter { it.type != BranchType.REMOTE }
            ?: emptyList()

        val trackingBranches = branches.mapNotNull { branch ->
            remoteRepository.getUpstreamTracking(repository, branch.name)
        }

        Result.success(trackingBranches)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

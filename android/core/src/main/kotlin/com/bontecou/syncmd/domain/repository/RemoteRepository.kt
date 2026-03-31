package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.*

/**
 * Protocol for remote repository operations.
 * Handles push, remote management, and remote tracking operations.
 */
interface RemoteRepository {

    /**
     * List all configured remotes (name, url pairs)
     */
    suspend fun listRemotes(repoPath: String): Result<List<Remote>>

    /**
     * Add a new remote
     */
    suspend fun addRemote(repoPath: String, name: String, url: String): Result<Unit>

    /**
     * Remove a remote
     */
    suspend fun removeRemote(repoPath: String, name: String): Result<Unit>

    /**
     * Get the URL of a remote
     */
    suspend fun getRemoteUrl(repoPath: String, name: String): Result<String?>

    /**
     * Update a remote's URL
     */
    suspend fun updateRemoteUrl(
        repoPath: String,
        name: String,
        newUrl: String
    ): Result<Unit>

    /**
     * Push commits from local branch to remote
     */
    suspend fun push(
        repoPath: String,
        branch: String,
        remote: String,
        remoteBranch: String,
        force: Boolean = false
    ): Result<Unit>

    /**
     * Push all tags to remote
     */
    suspend fun pushTags(repoPath: String, remote: String): Result<Unit>

    /**
     * Fetch updates from a specific remote
     */
    suspend fun fetch(repoPath: String, remote: String): Result<Unit>

    /**
     * Get the number of commits ahead and behind remote
     * Returns (ahead, behind) counts
     */
    suspend fun getAheadBehindCounts(
        repoPath: String,
        branch: String,
        remote: String = "origin"
    ): Pair<Int, Int>

    /**
     * Get upstream tracking information for a branch
     * Returns null if branch has no upstream set
     */
    suspend fun getUpstreamTracking(
        repoPath: String,
        branch: String
    ): UpstreamTracking?

    /**
     * List all remote branches (refs)
     */
    suspend fun listRemoteRefs(repoPath: String, remote: String): Result<List<RemoteRef>>

    /**
     * Check if a remote ref exists
     */
    suspend fun remoteRefExists(
        repoPath: String,
        remote: String,
        branch: String
    ): Result<Boolean>
}

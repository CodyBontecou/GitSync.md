package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.RepositoryStatus
import com.bontecou.syncmd.data.models.SafePullPlan
import com.bontecou.syncmd.data.models.SafePullResult

/**
 * Protocol for safe pull operations with rich status.
 * Provides repository status monitoring and safe pull planning before execution.
 */
interface PullRepository {

    /**
     * Get rich status of the repository
     * Includes working tree, index, and current branch information
     */
    suspend fun getStatus(repoPath: String): Result<RepositoryStatus>

    /**
     * Plan a pull operation before executing
     * Analyzes what would happen if we pulled: conflicts, merge type, etc.
     */
    suspend fun planPull(repoPath: String): Result<SafePullPlan>

    /**
     * Execute a safe pull operation
     * Will fail with DirtyRepoException if working tree is not clean
     */
    suspend fun executePull(repoPath: String): Result<SafePullResult>

    /**
     * Fetch updates from remote without merging
     * Safe to execute even with uncommitted changes
     */
    suspend fun fetch(repoPath: String): Result<Unit>

    /**
     * Get the number of commits ahead of remote
     */
    suspend fun getCommitsAhead(repoPath: String): Result<Int>

    /**
     * Get the number of commits behind remote
     */
    suspend fun getCommitsBehind(repoPath: String): Result<Int>
}

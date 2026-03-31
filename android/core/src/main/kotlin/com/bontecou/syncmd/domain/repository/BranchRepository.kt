package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.data.models.MergeResult
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.data.models.MergeType

/**
 * Protocol for branch and merge operations.
 * All implementations must provide branch listing, creation, switching, deletion, and merging.
 */
interface BranchRepository {

    /**
     * List all branches (local and remote)
     */
    suspend fun listBranches(repoPath: String): Result<List<Branch>>

    /**
     * Get the current (HEAD) branch
     */
    suspend fun getCurrentBranch(repoPath: String): Result<Branch>

    /**
     * Create a new branch starting from a point
     */
    suspend fun createBranch(
        repoPath: String,
        name: String,
        startPoint: String = "HEAD"
    ): Result<Unit>

    /**
     * Switch to a different branch
     * Fails if working tree has uncommitted changes
     */
    suspend fun switchBranch(repoPath: String, name: String): Result<Unit>

    /**
     * Delete a branch
     * Fails if trying to delete the current branch
     */
    suspend fun deleteBranch(repoPath: String, name: String, force: Boolean = false): Result<Unit>

    /**
     * Merge a source branch into the current branch
     */
    suspend fun merge(
        repoPath: String,
        sourceBranch: String,
        strategy: MergeStrategy = MergeStrategy.PREFER_FF
    ): Result<MergeResult>
}

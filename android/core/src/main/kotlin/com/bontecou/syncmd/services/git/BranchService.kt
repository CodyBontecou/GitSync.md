package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.data.models.MergeResult
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.domain.repository.BranchRepository

/**
 * Service for high-level branch and merge operations.
 * Provides a convenient API on top of BranchRepository.
 */
class BranchService(private val branchRepository: BranchRepository) {

    /**
     * Get list of all branches (local and remote)
     */
    suspend fun listBranches(repoPath: String): Result<List<Branch>> {
        return branchRepository.listBranches(repoPath)
    }

    /**
     * Get the current (HEAD) branch
     */
    suspend fun getCurrentBranch(repoPath: String): Result<Branch> {
        return branchRepository.getCurrentBranch(repoPath)
    }

    /**
     * Get only local branches
     */
    suspend fun getLocalBranches(repoPath: String): Result<List<Branch>> {
        return branchRepository.listBranches(repoPath).map { branches ->
            branches.filter { it.type.name == "LOCAL" || it.type.name == "TRACKING" }
        }
    }

    /**
     * Get only remote branches
     */
    suspend fun getRemoteBranches(repoPath: String): Result<List<Branch>> {
        return branchRepository.listBranches(repoPath).map { branches ->
            branches.filter { it.type.name == "REMOTE" }
        }
    }

    /**
     * Create a new branch
     */
    suspend fun createBranch(
        repoPath: String,
        name: String,
        startPoint: String = "HEAD"
    ): Result<Unit> {
        return branchRepository.createBranch(repoPath, name, startPoint)
    }

    /**
     * Switch to a different branch
     */
    suspend fun switchBranch(repoPath: String, name: String): Result<Unit> {
        return branchRepository.switchBranch(repoPath, name)
    }

    /**
     * Delete a branch
     */
    suspend fun deleteBranch(
        repoPath: String,
        name: String,
        force: Boolean = false
    ): Result<Unit> {
        return branchRepository.deleteBranch(repoPath, name, force)
    }

    /**
     * Merge a source branch into the current branch
     */
    suspend fun merge(
        repoPath: String,
        sourceBranch: String,
        strategy: MergeStrategy = MergeStrategy.PREFER_FF
    ): Result<MergeResult> {
        return branchRepository.merge(repoPath, sourceBranch, strategy)
    }

    /**
     * Check if a branch exists
     */
    suspend fun branchExists(repoPath: String, name: String): Result<Boolean> {
        return listBranches(repoPath).map { branches ->
            branches.any { it.name == name }
        }
    }

    /**
     * Get the default branch name for a new repository
     */
    suspend fun getDefaultBranch(repoPath: String): Result<String> {
        return getCurrentBranch(repoPath).map { it.name }
    }
}

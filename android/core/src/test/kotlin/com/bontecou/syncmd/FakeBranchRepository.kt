package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.data.models.BranchType
import com.bontecou.syncmd.data.models.DirtyRepoException
import com.bontecou.syncmd.data.models.MergeResult
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.data.models.MergeType
import java.io.File

/**
 * Fake implementation of BranchRepository for testing.
 * Tracks branches in memory with proper state management.
 */
class FakeBranchRepository : BranchRepository {
    
    // Track branches per repo: repoPath -> Set<branchName>
    private val branches: MutableMap<String, MutableSet<String>> = mutableMapOf()
    
    // Track current branch: repoPath -> branchName
    private val currentBranches: MutableMap<String, String> = mutableMapOf()
    
    // Track merge state: repoPath -> isMergeInProgress
    private val mergeInProgress: MutableMap<String, Boolean> = mutableMapOf()
    
    // Track dirty state: repoPath -> isDirty
    private val dirtyState: MutableMap<String, Boolean> = mutableMapOf()
    
    // Track tracking relationships: repoPath -> (localBranch -> remoteBranch)
    private val trackingBranches: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
    
    /**
     * Initialize a repository with master/main branch
     */
    fun initializeRepo(repoPath: String) {
        val repoDir = File(repoPath)
        if (repoDir.exists()) {
            val branchSet = mutableSetOf<String>()
            
            // Try to detect initial branch name
            val headFile = File(repoDir, ".git/HEAD")
            val defaultBranch = if (headFile.exists()) {
                val content = headFile.readText().trim()
                when {
                    content.contains("refs/heads/main") -> "main"
                    content.contains("refs/heads/master") -> "master"
                    else -> "master"
                }
            } else {
                "master"
            }
            
            branchSet.add(defaultBranch)
            branches[repoPath] = branchSet
            currentBranches[repoPath] = defaultBranch
            dirtyState[repoPath] = false
        }
    }
    
    /**
     * Set dirty state for testing
     */
    fun setDirtyState(repoPath: String, isDirty: Boolean) {
        dirtyState[repoPath] = isDirty
    }
    
    /**
     * Simulate a commit (for testing merge scenarios)
     */
    fun simulateCommit(repoPath: String) {
        // In a real scenario, this would update the commit hash
        // For now, just ensure the state is consistent
        val currentBranch = currentBranches[repoPath]
        if (currentBranch != null) {
            dirtyState[repoPath] = false
        }
    }
    
    override suspend fun listBranches(repoPath: String): Result<List<Branch>> {
        return try {
            if (!branches.containsKey(repoPath)) {
                initializeRepo(repoPath)
            }
            
            val branchSet = branches[repoPath] ?: emptySet()
            val currentBranch = currentBranches[repoPath] ?: ""
            val tracking = trackingBranches[repoPath] ?: emptyMap()
            
            val branchList = branchSet.map { name ->
                Branch(
                    name = name,
                    type = when {
                        name.startsWith("remotes/") -> BranchType.REMOTE
                        tracking.containsKey(name) -> BranchType.TRACKING
                        else -> BranchType.LOCAL
                    },
                    isHead = name == currentBranch,
                    trackingBranch = tracking[name],
                    lastCommit = "commit_hash_$name"
                )
            }
            
            Result.success(branchList)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getCurrentBranch(repoPath: String): Result<Branch> {
        return try {
            if (!branches.containsKey(repoPath)) {
                initializeRepo(repoPath)
            }
            
            val currentName = currentBranches[repoPath] ?: "master"
            val tracking = trackingBranches[repoPath] ?: emptyMap()
            
            val branch = Branch(
                name = currentName,
                type = when {
                    currentName.startsWith("remotes/") -> BranchType.REMOTE
                    tracking.containsKey(currentName) -> BranchType.TRACKING
                    else -> BranchType.LOCAL
                },
                isHead = true,
                trackingBranch = tracking[currentName],
                lastCommit = "commit_hash_$currentName"
            )
            
            Result.success(branch)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun createBranch(
        repoPath: String,
        name: String,
        startPoint: String
    ): Result<Unit> {
        return try {
            if (!branches.containsKey(repoPath)) {
                initializeRepo(repoPath)
            }
            
            val branchSet = branches[repoPath] ?: mutableSetOf()
            branchSet.add(name)
            branches[repoPath] = branchSet
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun switchBranch(repoPath: String, name: String): Result<Unit> {
        return try {
            if (!branches.containsKey(repoPath)) {
                initializeRepo(repoPath)
            }
            
            // Check if repo is dirty
            if (dirtyState[repoPath] == true) {
                return Result.failure(DirtyRepoException("Cannot switch branch with uncommitted changes"))
            }
            
            // Check if branch exists
            val branchSet = branches[repoPath] ?: return Result.failure(Exception("Branch not found: $name"))
            if (!branchSet.contains(name)) {
                return Result.failure(Exception("Branch not found: $name"))
            }
            
            currentBranches[repoPath] = name
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun deleteBranch(repoPath: String, name: String, force: Boolean): Result<Unit> {
        return try {
            if (!branches.containsKey(repoPath)) {
                initializeRepo(repoPath)
            }
            
            // Check if trying to delete current branch
            val currentBranch = currentBranches[repoPath]
            if (name == currentBranch && !force) {
                return Result.failure(Exception("Cannot delete current branch"))
            }
            
            val branchSet = branches[repoPath] ?: return Result.failure(Exception("Branch not found"))
            branchSet.remove(name)
            trackingBranches[repoPath]?.remove(name)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun merge(
        repoPath: String,
        sourceBranch: String,
        strategy: MergeStrategy
    ): Result<MergeResult> {
        return try {
            if (!branches.containsKey(repoPath)) {
                initializeRepo(repoPath)
            }
            
            // Check if merge is already in progress
            if (mergeInProgress[repoPath] == true) {
                return Result.failure(Exception("Merge already in progress"))
            }
            
            // Determine merge type based on strategy and state
            val mergeType = when (strategy) {
                MergeStrategy.FAST_FORWARD -> MergeType.FAST_FORWARD
                MergeStrategy.RECURSIVE -> MergeType.MERGE_COMMIT
                MergeStrategy.PREFER_FF -> MergeType.FAST_FORWARD // Simplified: always FF if possible
            }
            
            // Mark merge as in progress
            mergeInProgress[repoPath] = true
            
            // Simulate successful merge
            val result = MergeResult(
                success = true,
                mergeType = mergeType,
                message = "Merge succeeded"
            )
            
            // Clear merge state
            mergeInProgress[repoPath] = false
            
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

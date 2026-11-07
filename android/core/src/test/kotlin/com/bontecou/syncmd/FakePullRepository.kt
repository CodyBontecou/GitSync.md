package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.DirtyRepoException
import com.bontecou.syncmd.data.models.GitFileStatusKind
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.data.models.MergeType
import com.bontecou.syncmd.data.models.PullPlan
import com.bontecou.syncmd.data.models.RepositoryStatus
import com.bontecou.syncmd.data.models.SafePullPlan
import com.bontecou.syncmd.data.models.SafePullResult
import java.io.File

/**
 * Fake implementation of PullRepository for testing.
 * Tracks repository status and simulates pull operations.
 */
class FakePullRepository : PullRepository {
    
    // Track committed state per repo: repoPath -> Map<filePath -> content>
    private val committedState: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
    
    // Track working tree per repo: repoPath -> Map<filePath -> content>
    private val workingTree: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
    
    // Track staged files per repo: repoPath -> Set<filePath>
    private val stagedFiles: MutableMap<String, MutableSet<String>> = mutableMapOf()
    
    // Track commit counts: repoPath -> (ahead, behind)
    private val commitCounts: MutableMap<String, Pair<Int, Int>> = mutableMapOf()
    
    // Track current branch: repoPath -> branchName
    private val currentBranches: MutableMap<String, String> = mutableMapOf()
    
    /**
     * Initialize a clean repository
     */
    fun initializeRepo(repoPath: String) {
        val repoDir = File(repoPath)
        if (repoDir.exists()) {
            val state = mutableMapOf<String, String>()
            repoDir.walk()
                .filter { it.isFile && !it.path.contains("/.git") }
                .forEach { file ->
                    val relativePath = file.relativeTo(repoDir).path
                    state[relativePath] = file.readText()
                }
            committedState[repoPath] = state
            workingTree[repoPath] = state.toMutableMap()
            stagedFiles[repoPath] = mutableSetOf()
            commitCounts[repoPath] = Pair(0, 0)
            currentBranches[repoPath] = "master"
        }
    }
    
    /**
     * Simulate repository being N commits behind remote
     */
    fun simulateBehindRemote(repoPath: String, count: Int) {
        val (ahead, _) = commitCounts[repoPath] ?: Pair(0, 0)
        commitCounts[repoPath] = Pair(ahead, count)
    }
    
    /**
     * Simulate repository being N commits ahead of remote
     */
    fun simulateAheadOfRemote(repoPath: String, count: Int) {
        val (_, behind) = commitCounts[repoPath] ?: Pair(0, 0)
        commitCounts[repoPath] = Pair(count, behind)
    }
    
    override suspend fun getStatus(repoPath: String): Result<RepositoryStatus> {
        return try {
            if (!committedState.containsKey(repoPath)) {
                initializeRepo(repoPath)
            }
            
            val committed = committedState[repoPath] ?: emptyMap()
            val staged = stagedFiles[repoPath] ?: emptySet()
            val branch = currentBranches[repoPath] ?: "master"
            
            // Read current working tree from disk (not from cache)
            val repoDir = File(repoPath)
            val working = mutableMapOf<String, String>()
            repoDir.walk()
                .filter { it.isFile && !it.path.contains("/.git") }
                .forEach { file ->
                    val relativePath = file.relativeTo(repoDir).path
                    working[relativePath] = file.readText()
                }
            
            // Detect changes
            val modified = mutableListOf<GitStatusEntry>()
            val stagedEntries = mutableListOf<GitStatusEntry>()
            val untracked = mutableListOf<GitStatusEntry>()
            
            // Find modified files (in working tree but different from committed)
            working.forEach { (path, content) ->
                val committedContent = committed[path]
                if (committedContent != null && committedContent != content) {
                    if (staged.contains(path)) {
                        stagedEntries.add(GitStatusEntry(path, GitFileStatusKind.STAGED))
                    } else {
                        modified.add(GitStatusEntry(path, GitFileStatusKind.MODIFIED))
                    }
                }
            }
            
            // Find untracked files
            working.forEach { (path, _) ->
                if (!committed.containsKey(path) && !staged.contains(path)) {
                    untracked.add(GitStatusEntry(path, GitFileStatusKind.UNTRACKED))
                }
            }
            
            val allChanges = modified + stagedEntries + untracked
            val isClean = allChanges.isEmpty()
            
            val status = RepositoryStatus(
                repoPath = repoPath,
                currentBranch = branch,
                isClean = isClean,
                modifiedFiles = modified,
                stagedFiles = stagedEntries,
                untrackedFiles = untracked,
                allChanges = allChanges
            )
            
            Result.success(status)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun planPull(repoPath: String): Result<SafePullPlan> {
        return try {
            if (!committedState.containsKey(repoPath)) {
                initializeRepo(repoPath)
            }
            
            val statusResult = getStatus(repoPath)
            if (!statusResult.isSuccess) return statusResult.map { SafePullPlan("", "", "", PullPlan(MergeType.UP_TO_DATE, false, false)) }
            
            val status = statusResult.getOrNull()!!
            val (ahead, behind) = commitCounts[repoPath] ?: Pair(0, 0)
            
            // Determine merge type and blocking reason
            val (mergeType, canPull, blockingReason) = when {
                status.hasDirtyState -> Triple(MergeType.UP_TO_DATE, false, "Working tree has uncommitted changes")
                behind == 0 && ahead == 0 -> Triple(MergeType.UP_TO_DATE, true, null)
                behind > 0 && ahead == 0 -> Triple(MergeType.FAST_FORWARD, true, null)
                behind > 0 && ahead > 0 -> Triple(MergeType.MERGE_COMMIT, true, null)
                else -> Triple(MergeType.UP_TO_DATE, true, null)
            }
            
            val plan = SafePullPlan(
                repoPath = repoPath,
                currentBranch = status.currentBranch,
                remoteBranch = "origin/${status.currentBranch}",
                basePlan = PullPlan(
                    mergeType = mergeType,
                    fastForwardable = mergeType == MergeType.FAST_FORWARD,
                    conflictsExpected = mergeType == MergeType.MERGE_COMMIT,
                    commitsAhead = ahead,
                    commitsBehind = behind
                ),
                canPull = canPull,
                blockingReason = blockingReason
            )
            
            Result.success(plan)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun executePull(repoPath: String): Result<SafePullResult> {
        return try {
            val statusResult = getStatus(repoPath)
            if (!statusResult.isSuccess) return statusResult.map { SafePullResult(false, "Failed to get status") }
            
            val status = statusResult.getOrNull()!!
            if (status.hasDirtyState) {
                return Result.failure(DirtyRepoException("Cannot pull with uncommitted changes"))
            }
            
            val (ahead, behind) = commitCounts[repoPath] ?: Pair(0, 0)
            
            // Simulate successful pull
            if (behind > 0) {
                // Update working tree to simulate merged changes
                workingTree[repoPath] = committedState[repoPath]?.toMutableMap() ?: mutableMapOf()
                commitCounts[repoPath] = Pair(ahead, 0)
            }
            
            val result = SafePullResult(
                success = true,
                message = "Pull completed successfully",
                commitsApplied = behind,
                filesChanged = 0
            )
            
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun fetch(repoPath: String): Result<Unit> {
        return try {
            // Fetch doesn't require clean working tree
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getCommitsAhead(repoPath: String): Result<Int> {
        return try {
            val (ahead, _) = commitCounts[repoPath] ?: Pair(0, 0)
            Result.success(ahead)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getCommitsBehind(repoPath: String): Result<Int> {
        return try {
            val (_, behind) = commitCounts[repoPath] ?: Pair(0, 0)
            Result.success(behind)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

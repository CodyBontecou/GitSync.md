package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.ConflictResolution
import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.data.models.MergeState
import java.io.File

/**
 * Fake implementation of ConflictRepository for testing.
 * Tracks merge state and conflicts in memory.
 */
class FakeConflictRepository : ConflictRepository {
    
    // Track merge state per repo: repoPath -> MergeState
    private val mergeStates: MutableMap<String, MergeState> = mutableMapOf()
    
    // Track resolved conflicts: repoPath -> Set<filePath>
    private val resolvedConflicts: MutableMap<String, MutableSet<String>> = mutableMapOf()
    
    /**
     * Initialize a repository (no merge in progress initially)
     */
    fun initializeRepo(repoPath: String) {
        val repoDir = File(repoPath)
        if (repoDir.exists()) {
            mergeStates[repoPath] = MergeState(isMergeInProgress = false)
        }
    }
    
    /**
     * Simulate a merge with conflicts for testing
     */
    fun simulateMergeWithConflicts(
        repoPath: String,
        sourceBranch: String,
        conflictFiles: List<String>
    ) {
        val conflicts = conflictFiles.map { filePath ->
            Conflict(
                filePath = filePath,
                currentContent = "Our version of $filePath\n",
                incomingContent = "Their version of $filePath\n",
                baseContent = "Base version of $filePath\n"
            )
        }
        
        mergeStates[repoPath] = MergeState(
            isMergeInProgress = true,
            sourceBranch = sourceBranch,
            conflicts = conflicts,
            unmergedFiles = conflictFiles
        )
        resolvedConflicts[repoPath] = mutableSetOf()
    }
    
    override suspend fun getMergeState(repoPath: String): Result<MergeState> {
        return try {
            if (!mergeStates.containsKey(repoPath)) {
                initializeRepo(repoPath)
            }
            
            val state = mergeStates[repoPath] ?: MergeState(isMergeInProgress = false)
            Result.success(state)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getConflicts(repoPath: String): Result<List<Conflict>> {
        return try {
            val state = mergeStates[repoPath] ?: MergeState(isMergeInProgress = false)
            Result.success(state.conflicts)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getFileConflict(repoPath: String, filePath: String): Result<Conflict?> {
        return try {
            val state = mergeStates[repoPath] ?: return Result.success(null)
            val conflict = state.conflicts.find { it.filePath == filePath }
            Result.success(conflict)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun resolveConflict(
        repoPath: String,
        filePath: String,
        strategy: ConflictResolutionStrategy,
        customContent: String?
    ): Result<ConflictResolution> {
        return try {
            val resolved = mutableSetOf<String>()
            resolved.addAll(resolvedConflicts[repoPath] ?: emptySet())
            resolved.add(filePath)
            resolvedConflicts[repoPath] = resolved
            
            // Update merge state to remove this conflict
            val currentState = mergeStates[repoPath] ?: return Result.failure(Exception("No merge in progress"))
            val updatedConflicts = currentState.conflicts.filter { it.filePath != filePath }
            
            mergeStates[repoPath] = currentState.copy(
                conflicts = updatedConflicts,
                unmergedFiles = currentState.unmergedFiles.filter { it != filePath }
            )
            
            val result = ConflictResolution(
                filePath = filePath,
                strategy = strategy,
                resolved = true,
                message = "Resolved using $strategy"
            )
            
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun markAllResolved(repoPath: String): Result<Unit> {
        return try {
            val currentState = mergeStates[repoPath] ?: return Result.failure(Exception("No merge in progress"))
            
            // Clear all conflicts
            mergeStates[repoPath] = currentState.copy(
                conflicts = emptyList(),
                unmergedFiles = emptyList()
            )
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun completeMerge(repoPath: String, message: String): Result<Unit> {
        return try {
            val currentState = mergeStates[repoPath] ?: return Result.failure(Exception("No merge in progress"))
            
            // Check that all conflicts are resolved
            if (currentState.conflicts.isNotEmpty()) {
                return Result.failure(Exception("Cannot complete merge with unresolved conflicts"))
            }
            
            // Mark merge as complete
            mergeStates[repoPath] = MergeState(isMergeInProgress = false)
            resolvedConflicts[repoPath]?.clear()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun abortMerge(repoPath: String): Result<Unit> {
        return try {
            mergeStates[repoPath] = MergeState(isMergeInProgress = false)
            resolvedConflicts[repoPath]?.clear()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun parseConflictMarkers(content: String): Result<Conflict?> {
        return try {
            val lines = content.split("\n")
            
            // Find conflict markers
            val ourStart = lines.indexOfFirst { it.startsWith("<<<<<<< ") }
            val splitIdx = lines.indexOfFirst { it == "=======" }
            val theirEnd = lines.indexOfFirst { it.startsWith(">>>>>>> ") }
            
            if (ourStart == -1 || splitIdx == -1 || theirEnd == -1) {
                return Result.success(null)
            }
            
            val ourContent = lines.subList(ourStart + 1, splitIdx).joinToString("\n")
            val theirContent = lines.subList(splitIdx + 1, theirEnd).joinToString("\n")
            // val theirBranch = lines[theirEnd].substringAfter(">>>>>>> ")  // Could use for conflict tracking
            
            val conflict = Conflict(
                filePath = "unknown",  // Would need to be determined by caller
                currentContent = ourContent,
                incomingContent = theirContent,
                baseContent = null
            )
            
            Result.success(conflict)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

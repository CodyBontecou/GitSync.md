package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.ConflictResolution
import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.data.models.MergeState
import com.bontecou.syncmd.domain.repository.ConflictRepository

/**
 * Service for high-level conflict resolution operations.
 * Provides a convenient API on top of ConflictRepository.
 */
class ConflictService(private val conflictRepository: ConflictRepository) {

    /**
     * Get current merge state
     */
    suspend fun getMergeState(repoPath: String): Result<MergeState> {
        return conflictRepository.getMergeState(repoPath)
    }

    /**
     * Get list of all conflicts
     */
    suspend fun getConflicts(repoPath: String): Result<List<Conflict>> {
        return conflictRepository.getConflicts(repoPath)
    }

    /**
     * Get conflict for a specific file
     */
    suspend fun getFileConflict(repoPath: String, filePath: String): Result<Conflict?> {
        return conflictRepository.getFileConflict(repoPath, filePath)
    }

    /**
     * Resolve all conflicts using the same strategy
     */
    suspend fun resolveAllConflicts(
        repoPath: String,
        strategy: ConflictResolutionStrategy
    ): Result<List<ConflictResolution>> {
        return try {
            val conflictsResult = getConflicts(repoPath)
            if (!conflictsResult.isSuccess) return conflictsResult.map { emptyList() }
            
            val conflicts = conflictsResult.getOrNull() ?: emptyList()
            val resolutions = mutableListOf<ConflictResolution>()
            
            for (conflict in conflicts) {
                val resolutionResult = conflictRepository.resolveConflict(
                    repoPath,
                    conflict.filePath,
                    strategy
                )
                if (resolutionResult.isSuccess) {
                    resolutions.add(resolutionResult.getOrNull()!!)
                }
            }
            
            Result.success(resolutions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Resolve a single conflict
     */
    suspend fun resolveConflict(
        repoPath: String,
        filePath: String,
        strategy: ConflictResolutionStrategy,
        customContent: String? = null
    ): Result<ConflictResolution> {
        return conflictRepository.resolveConflict(repoPath, filePath, strategy, customContent)
    }

    /**
     * Mark all conflicts as resolved
     */
    suspend fun markAllResolved(repoPath: String): Result<Unit> {
        return conflictRepository.markAllResolved(repoPath)
    }

    /**
     * Complete merge after resolution
     */
    suspend fun completeMerge(repoPath: String, message: String): Result<Unit> {
        return conflictRepository.completeMerge(repoPath, message)
    }

    /**
     * Abort merge and return to pre-merge state
     */
    suspend fun abortMerge(repoPath: String): Result<Unit> {
        return conflictRepository.abortMerge(repoPath)
    }

    /**
     * Check if merge is in progress
     */
    suspend fun isMergeInProgress(repoPath: String): Result<Boolean> {
        return getMergeState(repoPath).map { it.isMergeInProgress }
    }

    /**
     * Get number of unresolved conflicts
     */
    suspend fun getUnresolvedCount(repoPath: String): Result<Int> {
        return getConflicts(repoPath).map { it.size }
    }

    /**
     * Resolve all conflicts interactively (requires custom content for each)
     */
    suspend fun resolveInteractively(
        repoPath: String,
        resolutions: Map<String, String>  // filePath -> customContent
    ): Result<List<ConflictResolution>> {
        return try {
            val results = mutableListOf<ConflictResolution>()
            
            for ((filePath, customContent) in resolutions) {
                val result = conflictRepository.resolveConflict(
                    repoPath,
                    filePath,
                    ConflictResolutionStrategy.MANUAL,
                    customContent
                )
                if (result.isSuccess) {
                    results.add(result.getOrNull()!!)
                }
            }
            
            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

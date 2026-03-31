package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.ConflictResolution
import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.data.models.MergeState

/**
 * Protocol for merge conflict detection and resolution.
 * Handles unmerged files, conflict markers, and resolution strategies.
 */
interface ConflictRepository {

    /**
     * Get current merge state (in-progress merge, conflicts, unmerged files)
     */
    suspend fun getMergeState(repoPath: String): Result<MergeState>

    /**
     * List all conflicts in the current merge
     */
    suspend fun getConflicts(repoPath: String): Result<List<Conflict>>

    /**
     * Get conflict details for a specific file
     */
    suspend fun getFileConflict(repoPath: String, filePath: String): Result<Conflict?>

    /**
     * Resolve a conflict using a specific strategy
     */
    suspend fun resolveConflict(
        repoPath: String,
        filePath: String,
        strategy: ConflictResolutionStrategy,
        customContent: String? = null  // For MANUAL strategy
    ): Result<ConflictResolution>

    /**
     * Mark all conflicts as resolved (before completing merge)
     */
    suspend fun markAllResolved(repoPath: String): Result<Unit>

    /**
     * Complete the merge after all conflicts are resolved
     */
    suspend fun completeMerge(repoPath: String, message: String): Result<Unit>

    /**
     * Abort the current merge and return to pre-merge state
     */
    suspend fun abortMerge(repoPath: String): Result<Unit>

    /**
     * Parse conflict markers from file content
     */
    suspend fun parseConflictMarkers(content: String): Result<Conflict?>
}

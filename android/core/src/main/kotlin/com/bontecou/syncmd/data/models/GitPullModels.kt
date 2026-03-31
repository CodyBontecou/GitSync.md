package com.bontecou.syncmd.data.models

/**
 * Rich status of a repository including working tree and index state
 */
data class RepositoryStatus(
    val repoPath: String,
    val currentBranch: String,
    val isClean: Boolean = false,
    val modifiedFiles: List<GitStatusEntry> = emptyList(),
    val stagedFiles: List<GitStatusEntry> = emptyList(),
    val untrackedFiles: List<GitStatusEntry> = emptyList(),
    val allChanges: List<GitStatusEntry> = emptyList()
) {
    /**
     * Total number of files with changes
     */
    val totalChanges: Int
        get() = allChanges.size
    
    /**
     * Whether repository has any uncommitted changes
     */
    val hasDirtyState: Boolean
        get() = !isClean && allChanges.isNotEmpty()
}

/**
 * Detailed pull plan with additional information
 */
data class SafePullPlan(
    val repoPath: String,
    val currentBranch: String,
    val remoteBranch: String,
    val basePlan: PullPlan,
    val canPull: Boolean = true,
    val blockingReason: String? = null,
    val expectedConflicts: List<String> = emptyList(),
    val autoMergeableFiles: Int = 0,
    val conflictingFiles: Int = 0
) {
    /**
     * Convenience properties for commit counts
     */
    val commitsAhead: Int
        get() = basePlan.commitsAhead
    
    val commitsBehind: Int
        get() = basePlan.commitsBehind
    
    /**
     * Checks if pull is safe to execute
     */
    fun isSafeToPull(): Boolean = canPull && blockingReason == null
}

/**
 * Result of a safe pull operation
 */
data class SafePullResult(
    val success: Boolean,
    val message: String,
    val commitsApplied: Int = 0,
    val filesChanged: Int = 0,
    val conflictsDetected: Int = 0,
    val mergeRequired: Boolean = false
)

/**
 * Status change event for tracking progress
 */
enum class StatusChangeType {
    REPOSITORY_CLEAN,           // Repository is clean
    UNCOMMITTED_CHANGES,        // Has uncommitted changes
    MERGE_IN_PROGRESS,          // Merge already in progress
    AHEAD_OF_REMOTE,            // Local has commits remote doesn't
    BEHIND_REMOTE,              // Remote has commits local doesn't
    DIVERGED,                   // Both have diverged commits
    UP_TO_DATE,                 // Synchronized with remote
}

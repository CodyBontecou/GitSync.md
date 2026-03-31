package com.bontecou.syncmd.data.models

/**
 * Represents a single branch in a repository
 */
data class Branch(
    val name: String,           // Branch name (without refs/ prefix)
    val type: BranchType,       // Local, remote, or tracking
    val isHead: Boolean = false, // Is this the current branch?
    val trackingBranch: String? = null, // If tracking, what remote branch?
    val lastCommit: String? = null,     // Latest commit hash
    val lastCommitMessage: String? = null  // Latest commit message
)

/**
 * Type of branch
 */
enum class BranchType {
    LOCAL,      // Local branch (refs/heads/*)
    REMOTE,     // Remote branch (refs/remotes/origin/*)
    TRACKING,   // Local branch tracking a remote (has upstream)
}

/**
 * Result of a merge operation
 */
data class MergeResult(
    val success: Boolean,
    val mergeType: MergeType,
    val message: String,
    val conflictCount: Int = 0
)

/**
 * Options for how to perform a merge
 */
enum class MergeStrategy {
    FAST_FORWARD,   // Only if source is ahead of target
    RECURSIVE,      // Always create a merge commit
    PREFER_FF,      // FF if possible, otherwise merge commit
}

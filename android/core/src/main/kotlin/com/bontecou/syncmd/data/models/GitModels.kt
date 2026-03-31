package com.bontecou.syncmd.data.models

/**
 * Represents a single file status in the working tree or index
 */
data class GitStatusEntry(
    val filePath: String,
    val kind: GitFileStatusKind
)

/**
 * Type of change for a file in git
 */
enum class GitFileStatusKind {
    MODIFIED,    // Changed but not staged
    STAGED,      // Added to index
    UNTRACKED,   // Not tracked by git
}

/**
 * Represents credentials for git operations (clone, push, etc.)
 */
sealed class Credentials {
    data class Pat(val token: String) : Credentials()
    data class Basic(val username: String, val password: String) : Credentials()
}

/**
 * Analysis of whether a pull would succeed and how
 */
data class PullPlan(
    val mergeType: MergeType,
    val fastForwardable: Boolean,
    val conflictsExpected: Boolean,
    val commitsAhead: Int = 0,
    val commitsBehind: Int = 0
)

/**
 * How a merge would be executed
 */
enum class MergeType {
    FAST_FORWARD,   // Can be fast-forwarded
    MERGE_COMMIT,   // Requires a merge commit
    CONFLICT,       // Will result in conflicts
    UP_TO_DATE,     // Already synchronized
}

/**
 * Result of a push operation
 */
data class PushResult(
    val success: Boolean,
    val message: String,
    val commitsSent: Int = 0
)

/**
 * Exception indicating the repository has uncommitted changes
 */
class DirtyRepoException(message: String = "Repository has uncommitted changes") : Exception(message)

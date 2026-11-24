package com.bontecou.syncmd.data.models

/**
 * Represents a single file status in the working tree or index
 */
data class GitStatusEntry(
    val filePath: String,
    val kind: GitFileStatusKind,
    val hasStagedChanges: Boolean = kind == GitFileStatusKind.STAGED,
    val hasUnstagedChanges: Boolean = kind != GitFileStatusKind.STAGED,
    val isDeleted: Boolean = false,
    val isConflicted: Boolean = false,
    /** A file Git doesn't have yet: untracked, or staged as added. */
    val isNew: Boolean = kind == GitFileStatusKind.UNTRACKED,
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
    object None : Credentials()
    data class Pat(val token: String) : Credentials()
    data class Basic(val username: String, val password: String) : Credentials()
    data class Ssh(val privateKey: String, val passphrase: String = "", val publicKey: String = "") : Credentials() {
        override fun toString() = "SSH credentials (redacted)"
    }
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
 * Exception indicating the repository has uncommitted changes. [facts] says, without any path,
 * what was in the way, so the debug log can tell one blocked pull from another.
 */
open class DirtyRepoException(
    message: String = "Repository has uncommitted changes",
    val facts: com.bontecou.syncmd.services.diagnostics.LocalChangesFacts? = null,
) : Exception(message)

/**
 * An update would overwrite an untracked or ignored local file at [path]. Committing can't clear
 * this (ignored files are never staged), so callers show the message with the path instead of
 * the "Resolve local changes" commit flow.
 */
class OverwriteBlockedException(val path: String, message: String) : DirtyRepoException(message)

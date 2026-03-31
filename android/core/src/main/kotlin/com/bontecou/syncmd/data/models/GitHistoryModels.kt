package com.bontecou.syncmd.data.models

/**
 * Represents a git commit in history
 */
data class Commit(
    val hash: String,                   // Full or short commit hash
    val shortHash: String = hash.take(7),
    val author: String,
    val email: String? = null,
    val message: String,
    val timestamp: Long = 0,            // Unix timestamp
    val parentHashes: List<String> = emptyList()
)

/**
 * Represents a git tag
 */
data class Tag(
    val name: String,
    val commitHash: String,
    val isAnnotated: Boolean = false,
    val message: String? = null,        // Annotated tag message
    val taggerEmail: String? = null,
    val taggerName: String? = null,
    val createdAt: Long = 0
)

/**
 * Represents a stashed change set
 */
data class Stash(
    val id: String,                     // stash@{n} identifier
    val name: String,                   // Display name like "WIP on branch"
    val commitHash: String,
    val timestamp: Long = 0,
    val message: String? = null
)

/**
 * Result of a revert operation
 */
data class RevertResult(
    val success: Boolean,
    val newCommitHash: String? = null,
    val message: String,
    val conflictsDetected: Int = 0
)

/**
 * Result of a stash operation
 */
data class StashResult(
    val success: Boolean,
    val stashId: String? = null,
    val message: String,
    val changeCount: Int = 0
)

/**
 * Options for reverting commits
 */
enum class RevertStrategy {
    CREATE_NEW_COMMIT,      // git revert (creates new commit)
    HARD_RESET,             // git reset --hard (destructive)
    SOFT_RESET,             // git reset --soft (keeps staged)
    MIXED_RESET,            // git reset --mixed (default, keeps working tree)
}

/**
 * Exception for history operations
 */
class HistoryException(message: String) : Exception(message)

/**
 * Exception when revert creates conflicts
 */
class RevertConflictException(
    val conflictingFiles: List<String>,
    message: String = "Revert created conflicts in ${conflictingFiles.size} file(s)"
) : Exception(message)

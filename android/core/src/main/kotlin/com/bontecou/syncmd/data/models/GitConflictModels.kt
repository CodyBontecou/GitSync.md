package com.bontecou.syncmd.data.models

data class ConflictSide(val path: String, val objectId: String, val mode: Int)

/**
 * Represents a single conflict within a file
 */
data class Conflict(
    val filePath: String,
    val currentContent: String,  // "ours" / HEAD
    val incomingContent: String, // "theirs" / incoming branch
    val baseContent: String? = null, // common ancestor (for 3-way merge)
    val isBinaryOrTooLarge: Boolean = false,
    val hasCurrentVersion: Boolean = true,
    val hasIncomingVersion: Boolean = true,
    val ancestor: ConflictSide? = null,
    val ours: ConflictSide? = null,
    val theirs: ConflictSide? = null,
    val workTreeVersions: Map<String, String?> = emptyMap(),
) {
    val isRenameRename: Boolean get() = ours != null && theirs != null && ours.path != theirs.path
    val allPaths: List<String> get() = listOfNotNull(ancestor?.path, ours?.path, theirs?.path).distinct().ifEmpty { listOf(filePath) }
}

/**
 * Represents the current merge state
 */
data class MergeState(
    val isMergeInProgress: Boolean,
    val sourceBranch: String? = null,
    val conflicts: List<Conflict> = emptyList(),
    val autoMergedFiles: List<String> = emptyList(),
    val unmergedFiles: List<String> = emptyList()
)

/**
 * Strategy for resolving a conflict
 */
enum class ConflictResolutionStrategy {
    OURS,       // Use our version (current branch)
    THEIRS,     // Use their version (incoming branch)
    MANUAL,     // Let user decide (edit file directly)
    ABORT,      // Abort the merge entirely
}

/**
 * Result of a conflict resolution operation
 */
data class ConflictResolution(
    val filePath: String,
    val strategy: ConflictResolutionStrategy,
    val resolved: Boolean,
    val message: String = ""
)

/**
 * Exception indicating there are unresolved conflicts
 */
class UnresolvedConflictsException(
    val conflicts: List<Conflict>,
    message: String = "Merge has ${conflicts.size} unresolved conflicts"
) : Exception(message)

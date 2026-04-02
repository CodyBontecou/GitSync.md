package com.bontecou.syncmd.data.models

/**
 * A single hunk in a unified diff (contiguous block of changes)
 */
data class DiffHunk(
    val oldStart: Int,      // Line number in original file
    val oldCount: Int,      // Number of lines in original
    val newStart: Int,      // Line number in new file
    val newCount: Int,      // Number of lines in new
    val lines: List<DiffLine>  // Actual diff lines with +/- markers
)

/**
 * A single line in a diff hunk
 */
data class DiffLine(
    val type: DiffLineType,
    val content: String
)

/**
 * Type of diff line: context, addition, or deletion
 */
enum class DiffLineType {
    CONTEXT,    // Line unchanged (space prefix)
    ADDITION,   // Line added (+ prefix)
    DELETION,   // Line removed (- prefix)
}

/**
 * Complete diff for a single file
 */
data class FileDiff(
    val filePath: String,
    val oldPath: String? = null,  // Different if file was renamed
    val status: DiffStatus,
    val hunks: List<DiffHunk>,
    val oldFileMode: String? = null,
    val newFileMode: String? = null,
    val isStaged: Boolean = false,  // true = file is in the git index (staged for commit)
)

/**
 * Type of change for a file in a diff
 */
enum class DiffStatus {
    ADDED,      // New file
    DELETED,    // File deleted
    MODIFIED,   // Content changed
    RENAMED,    // File renamed
    COPIED,     // File copied
    TYPE_CHANGE,// Type changed (e.g., symlink → regular)
    UNKNOWN,    // Unknown change
}

/**
 * Complete diff result for one or more files
 */
data class UnifiedDiffResult(
    val files: List<FileDiff>,
    val summary: DiffSummary
)

/**
 * Summary statistics about a diff
 */
data class DiffSummary(
    val filesChanged: Int,
    val insertions: Int,
    val deletions: Int
)

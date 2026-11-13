package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.UnifiedDiffResult

/**
 * Protocol for diff and staging operations.
 * All implementations must provide diff, stage, unstage, and commit operations.
 */
interface DiffRepository {
    suspend fun reviewDiscard(repoPath: String, filePath: String? = null): Result<com.bontecou.syncmd.services.git.DiscardReview> =
        Result.failure(UnsupportedOperationException("Reviewed discard is unavailable"))
    suspend fun discardReviewed(repoPath: String, review: com.bontecou.syncmd.services.git.DiscardReview): Result<Unit> =
        Result.failure(UnsupportedOperationException("Reviewed discard is unavailable"))

    /**
     * Get separate staged (HEAD to index) and unstaged (index to working tree) rows
     */
    suspend fun getDiff(repoPath: String): Result<UnifiedDiffResult>

    /**
     * Get both staged and unstaged rows for a specific file
     */
    suspend fun getDiff(repoPath: String, filePath: String): Result<UnifiedDiffResult>

    /**
     * Stage a file (add to index)
     */
    suspend fun stageFile(repoPath: String, filePath: String): Result<Unit>

    /** Stage the entire working tree, including additions and deletions. */
    suspend fun stageAll(repoPath: String): Result<Unit> = runCatching {
        getDiff(repoPath).getOrThrow().files.forEach { stageFile(repoPath, it.filePath).getOrThrow() }
    }

    /**
     * Unstage a file (remove from index)
     */
    suspend fun unstageFile(repoPath: String, filePath: String): Result<Unit>

    /**
     * Commit staged changes with a message
     * (Does NOT commit unstaged changes)
     */
    suspend fun commit(
        repoPath: String,
        message: String,
        authorName: String,
        authorEmail: String,
    ): Result<Unit>

    /**
     * Restore a single file to HEAD, discarding all local modifications.
     * Untracked (new) files are deleted from disk.
     */
    suspend fun discardFileChanges(repoPath: String, filePath: String): Result<Unit>

    /**
     * Restore tracked files and remove reviewed untracked files, matching iOS Revert All.
     * Ignored files are protected. On an unborn branch only staging is cleared.
     */
    suspend fun discardAllChanges(repoPath: String): Result<Unit>
}

package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.UnifiedDiffResult

/**
 * Protocol for diff and staging operations.
 * All implementations must provide diff, stage, unstage, and commit operations.
 */
interface DiffRepository {

    /**
     * Get unified diff for the entire repository (working tree vs index)
     */
    suspend fun getDiff(repoPath: String): Result<UnifiedDiffResult>

    /**
     * Get unified diff for a specific file (working tree vs index)
     */
    suspend fun getDiff(repoPath: String, filePath: String): Result<UnifiedDiffResult>

    /**
     * Stage a file (add to index)
     */
    suspend fun stageFile(repoPath: String, filePath: String): Result<Unit>

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
     * Restore every changed tracked file to HEAD (equivalent to `git reset --hard HEAD`).
     * Untracked files are left untouched.
     */
    suspend fun discardAllChanges(repoPath: String): Result<Unit>
}

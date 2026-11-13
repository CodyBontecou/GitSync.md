package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.Commit
import com.bontecou.syncmd.data.models.RevertResult
import com.bontecou.syncmd.data.models.RevertStrategy
import com.bontecou.syncmd.data.models.Stash
import com.bontecou.syncmd.data.models.StashResult
import com.bontecou.syncmd.data.models.Tag

/**
 * Protocol for history, stash, and tag operations.
 * Provides access to commit history, stash management, and tag operations.
 */
interface HistoryRepository {

    /**
     * Get commit history for current branch
     */
    suspend fun getHistory(repoPath: String, maxCommits: Int = 50): Result<List<Commit>>

    /**
     * Get a specific commit by hash
     */
    suspend fun getCommit(repoPath: String, commitHash: String): Result<Commit>

    /**
     * Revert a commit (create new commit or reset)
     */
    suspend fun revertCommit(
        repoPath: String,
        commitHash: String,
        strategy: RevertStrategy = RevertStrategy.CREATE_NEW_COMMIT
    ): Result<RevertResult>

    /**
     * List all stashes
     */
    suspend fun listStashes(repoPath: String): Result<List<Stash>>

    /**
     * Save current changes to stash
     */
    suspend fun stashSave(repoPath: String, message: String? = null): Result<StashResult>

    /**
     * Apply a stash (keeps it in stash list)
     */
    suspend fun stashApply(repoPath: String, stashId: String): Result<Unit>

    /**
     * Pop a stash (removes from list after applying)
     */
    suspend fun stashPop(repoPath: String, stashId: String): Result<Unit>

    /**
     * Drop a stash (delete without applying)
     */
    suspend fun stashDrop(repoPath: String, stashId: String): Result<Unit>

    /**
     * List all tags
     */
    suspend fun listTags(repoPath: String): Result<List<Tag>>

    /**
     * Create a new tag
     */
    suspend fun createTag(
        repoPath: String,
        name: String,
        commitHash: String = "HEAD",
        annotated: Boolean = false,
        message: String? = null
    ): Result<Unit>

    /**
     * Delete a tag
     */
    suspend fun deleteTag(repoPath: String, name: String): Result<Unit>

    /**
     * Get tag details
     */
    suspend fun getTag(repoPath: String, name: String): Result<Tag>
}

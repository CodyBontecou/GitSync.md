package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Commit
import com.bontecou.syncmd.data.models.RevertResult
import com.bontecou.syncmd.data.models.RevertStrategy
import com.bontecou.syncmd.data.models.Stash
import com.bontecou.syncmd.data.models.StashResult
import com.bontecou.syncmd.data.models.Tag
import com.bontecou.syncmd.domain.repository.HistoryRepository

/**
 * Service for high-level history, stash, and tag operations.
 * Provides convenient API on top of HistoryRepository.
 */
class HistoryService(private val historyRepository: HistoryRepository) {

    /**
     * Get commit history
     */
    suspend fun getHistory(repoPath: String, maxCommits: Int = 50): Result<List<Commit>> {
        return historyRepository.getHistory(repoPath, maxCommits)
    }

    /**
     * Get a specific commit
     */
    suspend fun getCommit(repoPath: String, commitHash: String): Result<Commit> {
        return historyRepository.getCommit(repoPath, commitHash)
    }

    /**
     * Revert a commit
     */
    suspend fun revertCommit(
        repoPath: String,
        commitHash: String,
        strategy: RevertStrategy = RevertStrategy.CREATE_NEW_COMMIT
    ): Result<RevertResult> {
        return historyRepository.revertCommit(repoPath, commitHash, strategy)
    }

    /**
     * Get the latest commit (HEAD)
     */
    suspend fun getLatestCommit(repoPath: String): Result<Commit> {
        return historyRepository.getHistory(repoPath, maxCommits = 1).mapCatching { commits ->
            commits.firstOrNull() ?: throw Exception("No commits found")
        }
    }

    /**
     * List all stashes
     */
    suspend fun listStashes(repoPath: String): Result<List<Stash>> {
        return historyRepository.listStashes(repoPath)
    }

    /**
     * Save current changes to stash
     */
    suspend fun stash(repoPath: String, message: String? = null): Result<StashResult> {
        return historyRepository.stashSave(repoPath, message)
    }

    /**
     * Apply a stash without removing it
     */
    suspend fun stashApply(repoPath: String, stashId: String): Result<Unit> {
        return historyRepository.stashApply(repoPath, stashId)
    }

    /**
     * Pop a stash (apply and remove)
     */
    suspend fun stashPop(repoPath: String, stashId: String): Result<Unit> {
        return historyRepository.stashPop(repoPath, stashId)
    }

    /**
     * Drop a stash without applying
     */
    suspend fun stashDrop(repoPath: String, stashId: String): Result<Unit> {
        return historyRepository.stashDrop(repoPath, stashId)
    }

    /**
     * Get number of stashes
     */
    suspend fun getStashCount(repoPath: String): Result<Int> {
        return listStashes(repoPath).map { it.size }
    }

    /**
     * List all tags
     */
    suspend fun listTags(repoPath: String): Result<List<Tag>> {
        return historyRepository.listTags(repoPath)
    }

    /**
     * Create a new tag
     */
    suspend fun createTag(
        repoPath: String,
        name: String,
        commitHash: String = "HEAD",
        annotated: Boolean = false,
        message: String? = null
    ): Result<Unit> {
        return historyRepository.createTag(repoPath, name, commitHash, annotated, message)
    }

    /**
     * Delete a tag
     */
    suspend fun deleteTag(repoPath: String, name: String): Result<Unit> {
        return historyRepository.deleteTag(repoPath, name)
    }

    /**
     * Get tag details
     */
    suspend fun getTag(repoPath: String, name: String): Result<Tag> {
        return historyRepository.getTag(repoPath, name)
    }

    /**
     * Check if tag exists
     */
    suspend fun tagExists(repoPath: String, name: String): Result<Boolean> {
        return try {
            val result = getTag(repoPath, name)
            Result.success(result.isSuccess)
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    /**
     * Get number of commits in history
     */
    suspend fun getCommitCount(repoPath: String): Result<Int> {
        return getHistory(repoPath, maxCommits = Int.MAX_VALUE).map { it.size }
    }
}

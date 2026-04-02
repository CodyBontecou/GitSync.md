package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.FileDiff
import com.bontecou.syncmd.data.models.UnifiedDiffResult
import com.bontecou.syncmd.domain.repository.DiffRepository

/**
 * Service for high-level diff and staging operations.
 * Provides a convenient API on top of DiffRepository.
 */
class DiffService(private val diffRepository: DiffRepository) {

    /**
     * Get diff for entire repository (working tree changes)
     */
    suspend fun getStatus(repoPath: String): Result<List<FileDiff>> {
        return diffRepository.getDiff(repoPath).map { it.files }
    }

    /**
     * Get diff for a specific file
     */
    suspend fun getFileDiff(repoPath: String, filePath: String): Result<UnifiedDiffResult> {
        return diffRepository.getDiff(repoPath, filePath)
    }

    /**
     * Stage a file for commit
     */
    suspend fun stageFile(repoPath: String, filePath: String): Result<Unit> {
        return diffRepository.stageFile(repoPath, filePath)
    }

    /**
     * Unstage a file (undo staging)
     */
    suspend fun unstageFile(repoPath: String, filePath: String): Result<Unit> {
        return diffRepository.unstageFile(repoPath, filePath)
    }

    /**
     * Commit all staged files with the given author identity.
     */
    suspend fun commit(
        repoPath: String,
        message: String,
        authorName: String,
        authorEmail: String,
    ): Result<Unit> {
        return diffRepository.commit(repoPath, message, authorName, authorEmail)
    }

    /**
     * Stage all modified files
     */
    suspend fun stageAll(repoPath: String): Result<Unit> {
        return try {
            val diffResult = diffRepository.getDiff(repoPath)
            if (!diffResult.isSuccess) return diffResult.map { }
            
            val files = diffResult.getOrNull()?.files ?: emptyList()
            files.forEach { fileDiff ->
                diffRepository.stageFile(repoPath, fileDiff.filePath)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Discard all local changes (reset --hard HEAD).
     */
    suspend fun discardAllChanges(repoPath: String): Result<Unit> {
        return diffRepository.discardAllChanges(repoPath)
    }

    /**
     * Discard local changes for a single file.
     */
    suspend fun discardFileChanges(repoPath: String, filePath: String): Result<Unit> {
        return diffRepository.discardFileChanges(repoPath, filePath)
    }

    /**
     * Unstage all files
     */
    suspend fun unstageAll(repoPath: String): Result<Unit> {
        return try {
            val diffResult = diffRepository.getDiff(repoPath)
            if (!diffResult.isSuccess) return diffResult.map { }
            
            val files = diffResult.getOrNull()?.files ?: emptyList()
            files.forEach { fileDiff ->
                diffRepository.unstageFile(repoPath, fileDiff.filePath)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

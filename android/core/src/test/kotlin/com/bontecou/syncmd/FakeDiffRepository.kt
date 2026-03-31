package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.DiffHunk
import com.bontecou.syncmd.data.models.DiffLine
import com.bontecou.syncmd.data.models.DiffLineType
import com.bontecou.syncmd.data.models.DiffStatus
import com.bontecou.syncmd.data.models.DiffSummary
import com.bontecou.syncmd.data.models.FileDiff
import com.bontecou.syncmd.data.models.UnifiedDiffResult
import java.io.File

/**
 * Fake implementation of DiffRepository for testing.
 * Simulates git staging and diff operations without actual git commands.
 */
class FakeDiffRepository : DiffRepository {
    
    // Track which files are staged: repoPath -> Set<filePath>
    private val stagedFiles: MutableMap<String, MutableSet<String>> = mutableMapOf()
    
    // Track "committed" state: repoPath -> (filePath -> content)
    // This is what was last committed or initialized
    private val committedState: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
    
    /**
     * Initialize the committed state of a repository
     * Call this before testing to set what was "committed"
     */
    fun initializeRepo(repoPath: String) {
        // Read the current working tree state as the committed state
        val repoDir = File(repoPath)
        if (repoDir.exists()) {
            val committed = mutableMapOf<String, String>()
            repoDir.walk()
                .filter { it.isFile && !it.path.contains("/.git") }
                .forEach { file ->
                    val relativePath = file.relativeTo(repoDir).path
                    committed[relativePath] = file.readText()
                }
            committedState[repoPath] = committed
        }
    }
    
    override suspend fun getDiff(repoPath: String): Result<UnifiedDiffResult> {
        return try {
            val repoDir = File(repoPath)
            if (!repoDir.exists()) {
                return Result.failure(Exception("Repository not found: $repoPath"))
            }
            
            // Ensure repo is initialized
            if (!committedState.containsKey(repoPath)) {
                initializeRepo(repoPath)
            }
            
            val files = mutableListOf<FileDiff>()
            var insertions = 0
            var deletions = 0
            
            // Find all modified files (not staged)
            val stagedSet = stagedFiles.getOrDefault(repoPath, emptySet())
            val committed = committedState[repoPath] ?: emptyMap()
            
            repoDir.walk()
                .filter { it.isFile && !it.path.contains("/.git") }
                .forEach { file ->
                    val relativePath = file.relativeTo(repoDir).path
                    if (!stagedSet.contains(relativePath)) {
                        val committedContent = committed[relativePath] ?: ""
                        val currentContent = file.readText()
                        if (committedContent != currentContent) {
                            val currentLines = currentContent.split("\n")
                            val committedLines = committedContent.split("\n")
                            
                            val diffLines = listOf(
                                DiffLine(DiffLineType.DELETION, "- ${committedLines.firstOrNull() ?: ""}"),
                                DiffLine(DiffLineType.ADDITION, "+ ${currentLines.firstOrNull() ?: ""}")
                            )
                            
                            val hunk = DiffHunk(
                                oldStart = 1,
                                oldCount = committedLines.size,
                                newStart = 1,
                                newCount = currentLines.size,
                                lines = diffLines
                            )
                            
                            files.add(FileDiff(
                                filePath = relativePath,
                                status = DiffStatus.MODIFIED,
                                hunks = listOf(hunk)
                            ))
                            
                            insertions += currentLines.size
                            deletions += committedLines.size
                        }
                    }
                }
            
            val result = UnifiedDiffResult(
                files = files,
                summary = DiffSummary(
                    filesChanged = files.size,
                    insertions = insertions,
                    deletions = deletions
                )
            )
            
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getDiff(repoPath: String, filePath: String): Result<UnifiedDiffResult> {
        return try {
            val repoDir = File(repoPath)
            
            // Ensure repo is initialized
            if (!committedState.containsKey(repoPath)) {
                initializeRepo(repoPath)
            }
            
            val file = File(repoDir, filePath)
            
            if (!file.exists()) {
                return Result.success(UnifiedDiffResult(emptyList(), DiffSummary(0, 0, 0)))
            }
            
            val committed = committedState[repoPath] ?: emptyMap()
            val committedContent = committed[filePath] ?: ""
            val currentContent = file.readText()
            
            if (committedContent == currentContent) {
                return Result.success(UnifiedDiffResult(emptyList(), DiffSummary(0, 0, 0)))
            }
            
            val currentLines = currentContent.split("\n")
            val committedLines = committedContent.split("\n")
            
            val diffLines = listOf(
                DiffLine(DiffLineType.DELETION, "- ${committedLines.firstOrNull() ?: ""}"),
                DiffLine(DiffLineType.ADDITION, "+ ${currentLines.firstOrNull() ?: ""}")
            )
            
            val hunk = DiffHunk(
                oldStart = 1,
                oldCount = committedLines.size,
                newStart = 1,
                newCount = currentLines.size,
                lines = diffLines
            )
            
            val fileDiff = FileDiff(
                filePath = filePath,
                status = DiffStatus.MODIFIED,
                hunks = listOf(hunk)
            )
            
            val result = UnifiedDiffResult(
                files = listOf(fileDiff),
                summary = DiffSummary(
                    filesChanged = 1,
                    insertions = currentLines.size,
                    deletions = committedLines.size
                )
            )
            
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun stageFile(repoPath: String, filePath: String): Result<Unit> {
        return try {
            stagedFiles.getOrPut(repoPath) { mutableSetOf() }.add(filePath)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun unstageFile(repoPath: String, filePath: String): Result<Unit> {
        return try {
            stagedFiles[repoPath]?.remove(filePath)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun commit(repoPath: String, message: String): Result<Unit> {
        return try {
            val stagedSet = stagedFiles[repoPath] ?: emptySet()
            val repoDir = File(repoPath)
            
            // After commit, update committed state for all staged files
            stagedSet.forEach { filePath ->
                val file = File(repoDir, filePath)
                if (file.exists()) {
                    committedState.getOrPut(repoPath) { mutableMapOf() }[filePath] = file.readText()
                }
            }
            
            // Clear the staged marker after commit
            stagedFiles[repoPath]?.clear()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.DiffHunk
import com.bontecou.syncmd.data.models.DiffLine
import com.bontecou.syncmd.data.models.DiffLineType
import com.bontecou.syncmd.data.models.DiffStatus
import com.bontecou.syncmd.data.models.DiffSummary
import com.bontecou.syncmd.data.models.FileDiff
import com.bontecou.syncmd.data.models.UnifiedDiffResult
import com.bontecou.syncmd.domain.repository.DiffRepository
import java.io.File

/**
 * Real implementation of DiffRepository that integrates with actual git commands.
 * Uses ProcessBuilder to execute git diff commands.
 */
class LocalDiffRepository : DiffRepository {

    override suspend fun getDiff(repoPath: String): Result<UnifiedDiffResult> {
        return try {
            // Get diff for all files
            val output = executeGit(repoPath, "diff", "HEAD")
            val fileDiffs = parseDiffOutput(output)
            
            var insertions = 0
            var deletions = 0
            fileDiffs.forEach { fileDiff ->
                fileDiff.hunks.forEach { hunk ->
                    hunk.lines.forEach { line ->
                        when (line.type) {
                            DiffLineType.ADDITION -> insertions++
                            DiffLineType.DELETION -> deletions++
                            else -> {}
                        }
                    }
                }
            }
            
            val result = UnifiedDiffResult(
                files = fileDiffs,
                summary = DiffSummary(
                    filesChanged = fileDiffs.size,
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
            // Get diff for specific file
            val output = executeGit(repoPath, "diff", "HEAD", "--", filePath)
            val fileDiffs = parseDiffOutput(output)
            
            var insertions = 0
            var deletions = 0
            fileDiffs.forEach { fileDiff ->
                fileDiff.hunks.forEach { hunk ->
                    hunk.lines.forEach { line ->
                        when (line.type) {
                            DiffLineType.ADDITION -> insertions++
                            DiffLineType.DELETION -> deletions++
                            else -> {}
                        }
                    }
                }
            }
            
            val result = UnifiedDiffResult(
                files = fileDiffs,
                summary = DiffSummary(
                    filesChanged = fileDiffs.size,
                    insertions = insertions,
                    deletions = deletions
                )
            )
            
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun stageFile(repoPath: String, filePath: String): Result<Unit> {
        return try {
            val output = executeGit(repoPath, "add", filePath)
            if (output.isEmpty() || checkGitSuccess(repoPath, "add", filePath)) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to stage file: $filePath"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun unstageFile(repoPath: String, filePath: String): Result<Unit> {
        return try {
            val output = executeGit(repoPath, "reset", "HEAD", filePath)
            if (output.isEmpty() || checkGitSuccess(repoPath, "reset", "HEAD", filePath)) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to unstage file: $filePath"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun commit(repoPath: String, message: String): Result<Unit> {
        return try {
            val output = executeGit(repoPath, "commit", "-m", message)
            if (output.isEmpty() || checkGitSuccess(repoPath, "commit", "-m", message)) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to commit: $output"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parse git diff output into FileDiff objects
     */
    private fun parseDiffOutput(output: String): List<FileDiff> {
        if (output.isEmpty()) return emptyList()
        
        val fileDiffs = mutableListOf<FileDiff>()
        val lines = output.lines()
        var i = 0
        
        while (i < lines.size) {
            val line = lines[i]
            
            // Look for diff --git line
            if (line.startsWith("diff --git")) {
                val parts = line.split(" ")
                if (parts.size >= 4) {
                    val aPath = parts[2].removePrefix("a/")
                    val bPath = parts[3].removePrefix("b/")
                    
                    // Skip ahead to find --- and +++ lines
                    i++
                    var oldFileMode: String? = null
                    var newFileMode: String? = null
                    var status = DiffStatus.MODIFIED
                    
                    while (i < lines.size && !lines[i].startsWith("---")) {
                        if (lines[i].startsWith("new file mode")) {
                            newFileMode = lines[i].substringAfter("mode ")
                            status = DiffStatus.ADDED
                        } else if (lines[i].startsWith("deleted file mode")) {
                            oldFileMode = lines[i].substringAfter("mode ")
                            status = DiffStatus.DELETED
                        } else if (lines[i].startsWith("rename from")) {
                            status = DiffStatus.RENAMED
                        }
                        i++
                    }
                    
                    // Skip --- and +++ lines
                    if (i < lines.size && lines[i].startsWith("---")) i++
                    if (i < lines.size && lines[i].startsWith("+++")) i++
                    
                    // Parse hunks for this file
                    val hunks = mutableListOf<DiffHunk>()
                    while (i < lines.size && lines[i].startsWith("@@")) {
                        val hunkLine = lines[i]
                        val hunkInfo = parseHunkHeader(hunkLine)
                        if (hunkInfo != null) {
                            val (oldStart, oldCount, newStart, newCount) = hunkInfo
                            i++
                            
                            val diffLines = mutableListOf<DiffLine>()
                            while (i < lines.size && !lines[i].startsWith("@@") && !lines[i].startsWith("diff --git")) {
                                val diffLine = lines[i]
                                when {
                                    diffLine.startsWith("+") && !diffLine.startsWith("+++") -> {
                                        diffLines.add(DiffLine(DiffLineType.ADDITION, diffLine.substring(1)))
                                    }
                                    diffLine.startsWith("-") && !diffLine.startsWith("---") -> {
                                        diffLines.add(DiffLine(DiffLineType.DELETION, diffLine.substring(1)))
                                    }
                                    diffLine.startsWith(" ") || diffLine.isEmpty() -> {
                                        diffLines.add(DiffLine(DiffLineType.CONTEXT, if (diffLine.length > 1) diffLine.substring(1) else ""))
                                    }
                                }
                                i++
                            }
                            
                            hunks.add(DiffHunk(oldStart, oldCount, newStart, newCount, diffLines))
                        } else {
                            i++
                        }
                    }
                    
                    fileDiffs.add(FileDiff(
                        filePath = bPath,
                        oldPath = if (status == DiffStatus.RENAMED) aPath else null,
                        status = status,
                        hunks = hunks,
                        oldFileMode = oldFileMode,
                        newFileMode = newFileMode
                    ))
                    continue
                }
            }
            
            i++
        }
        
        return fileDiffs
    }

    /**
     * Parse hunk header line like "@@ -10,3 +10,4 @@"
     * Returns (oldStart, oldCount, newStart, newCount) or null if parse fails
     */
    private fun parseHunkHeader(line: String): Quad<Int, Int, Int, Int>? {
        try {
            val pattern = """@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""".toRegex()
            val match = pattern.find(line) ?: return null
            
            val oldStart = match.groupValues[1].toInt()
            val oldCount = match.groupValues[2].ifEmpty { "1" }.toInt()
            val newStart = match.groupValues[3].toInt()
            val newCount = match.groupValues[4].ifEmpty { "1" }.toInt()
            
            return Quad(oldStart, oldCount, newStart, newCount)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Execute a git command and return output
     */
    private fun executeGit(repoPath: String, vararg args: String): String {
        return try {
            val command = listOf("git", "-C", repoPath) + args
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) output else ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Check if the last git command succeeded
     */
    private fun checkGitSuccess(repoPath: String, vararg args: String): Boolean {
        return try {
            val command = listOf("git", "-C", repoPath) + args
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Simple data class for 4-tuple (used for hunk header parsing)
     */
    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}

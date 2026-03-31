package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.ConflictResolution
import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.data.models.MergeState
import com.bontecou.syncmd.domain.repository.ConflictRepository
import java.io.File

/**
 * Real implementation of ConflictRepository that integrates with actual git commands.
 * Uses ProcessBuilder to execute git merge/conflict commands.
 */
class LocalConflictRepository : ConflictRepository {

    override suspend fun getMergeState(repoPath: String): Result<MergeState> {
        return try {
            val mergeHeadFile = File(repoPath, ".git/MERGE_HEAD")
            val isMergeInProgress = mergeHeadFile.exists()
            
            val sourceBranch = if (isMergeInProgress) {
                val mergeHeadContent = mergeHeadFile.readText().trim()
                // Try to get the branch name from MERGE_MSG
                val mergeMsgFile = File(repoPath, ".git/MERGE_MSG")
                if (mergeMsgFile.exists()) {
                    val msg = mergeMsgFile.readText()
                    val branchMatch = msg.split("\n").firstOrNull()?.substringAfterLast("'")?.removeSuffix("'")
                    branchMatch
                } else {
                    "unknown"
                }
            } else {
                null
            }
            
            val conflictsResult = getConflicts(repoPath)
            val conflicts = if (conflictsResult.isSuccess) conflictsResult.getOrNull() ?: emptyList() else emptyList()
            
            val unmergedFiles = executeGit(repoPath, "diff", "--name-only", "--diff-filter=U")
                .lines()
                .filter { it.isNotBlank() }
            
            val state = MergeState(
                isMergeInProgress = isMergeInProgress,
                sourceBranch = sourceBranch,
                conflicts = conflicts,
                autoMergedFiles = emptyList(),
                unmergedFiles = unmergedFiles
            )
            
            Result.success(state)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getConflicts(repoPath: String): Result<List<Conflict>> {
        return try {
            val unmergedFiles = executeGit(repoPath, "diff", "--name-only", "--diff-filter=U")
                .lines()
                .filter { it.isNotBlank() }
            
            val conflicts = mutableListOf<Conflict>()
            
            for (filePath in unmergedFiles) {
                val fileConflictResult = getFileConflict(repoPath, filePath)
                if (fileConflictResult.isSuccess) {
                    val conflict = fileConflictResult.getOrNull()
                    if (conflict != null) {
                        conflicts.add(conflict)
                    }
                }
            }
            
            Result.success(conflicts)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getFileConflict(repoPath: String, filePath: String): Result<Conflict?> {
        return try {
            val file = File(repoPath, filePath)
            if (!file.exists()) {
                return Result.success(null)
            }
            
            val content = file.readText()
            val conflict = parseConflictMarkers(content).getOrNull()
            
            Result.success(conflict)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun resolveConflict(
        repoPath: String,
        filePath: String,
        strategy: ConflictResolutionStrategy,
        customContent: String?
    ): Result<ConflictResolution> {
        return try {
            when (strategy) {
                ConflictResolutionStrategy.OURS -> {
                    val output = executeGit(repoPath, "checkout", "--ours", filePath)
                    if (checkGitSuccess(repoPath, "checkout", "--ours", filePath)) {
                        val addOutput = executeGit(repoPath, "add", filePath)
                        if (checkGitSuccess(repoPath, "add", filePath)) {
                            Result.success(ConflictResolution(filePath, strategy, true))
                        } else {
                            Result.failure(Exception("Failed to stage resolved file"))
                        }
                    } else {
                        Result.failure(Exception("Failed to resolve conflict with OURS"))
                    }
                }
                ConflictResolutionStrategy.THEIRS -> {
                    val output = executeGit(repoPath, "checkout", "--theirs", filePath)
                    if (checkGitSuccess(repoPath, "checkout", "--theirs", filePath)) {
                        val addOutput = executeGit(repoPath, "add", filePath)
                        if (checkGitSuccess(repoPath, "add", filePath)) {
                            Result.success(ConflictResolution(filePath, strategy, true))
                        } else {
                            Result.failure(Exception("Failed to stage resolved file"))
                        }
                    } else {
                        Result.failure(Exception("Failed to resolve conflict with THEIRS"))
                    }
                }
                ConflictResolutionStrategy.MANUAL -> {
                    if (customContent != null) {
                        val file = File(repoPath, filePath)
                        file.writeText(customContent)
                        
                        val addOutput = executeGit(repoPath, "add", filePath)
                        if (checkGitSuccess(repoPath, "add", filePath)) {
                            Result.success(ConflictResolution(filePath, strategy, true))
                        } else {
                            Result.failure(Exception("Failed to stage manually resolved file"))
                        }
                    } else {
                        Result.failure(Exception("MANUAL strategy requires customContent"))
                    }
                }
                ConflictResolutionStrategy.ABORT -> {
                    abortMerge(repoPath).map {
                        ConflictResolution(filePath, strategy, true, "Merge aborted")
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markAllResolved(repoPath: String): Result<Unit> {
        return try {
            val unmergedFiles = executeGit(repoPath, "diff", "--name-only", "--diff-filter=U")
                .lines()
                .filter { it.isNotBlank() }
            
            for (filePath in unmergedFiles) {
                executeGit(repoPath, "add", filePath)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun completeMerge(repoPath: String, message: String): Result<Unit> {
        return try {
            // Use the existing MERGE_MSG or provide our own
            val output = executeGit(repoPath, "commit", "--no-edit", "-m", message)
            
            if (checkGitSuccess(repoPath, "commit", "--no-edit", "-m", message)) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to complete merge"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun abortMerge(repoPath: String): Result<Unit> {
        return try {
            val output = executeGit(repoPath, "merge", "--abort")
            
            if (checkGitSuccess(repoPath, "merge", "--abort")) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to abort merge"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun parseConflictMarkers(content: String): Result<Conflict?> {
        return try {
            val result = parseConflictMarkersInternal(content)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parse standard git conflict markers from file content
     * Format:
     * <<<<<<< HEAD
     * ... current/ours content ...
     * =======
     * ... incoming/theirs content ...
     * >>>>>>> branch-name
     */
    private fun parseConflictMarkersInternal(content: String): Conflict? {
        val lines = content.split("\n")
        var i = 0
        
        while (i < lines.size) {
            if (lines[i].startsWith("<<<<<<<")) {
                // Found conflict marker start
                val currentLines = mutableListOf<String>()
                val incomingLines = mutableListOf<String>()
                var inCurrent = true
                i++
                
                while (i < lines.size) {
                    when {
                        lines[i].startsWith("=======") -> {
                            inCurrent = false
                            i++
                        }
                        lines[i].startsWith(">>>>>>>") -> {
                            // End of conflict
                            return Conflict(
                                filePath = "",  // Not set by parser
                                currentContent = currentLines.joinToString("\n"),
                                incomingContent = incomingLines.joinToString("\n")
                            )
                        }
                        else -> {
                            if (inCurrent) {
                                currentLines.add(lines[i])
                            } else {
                                incomingLines.add(lines[i])
                            }
                            i++
                        }
                    }
                }
            }
            i++
        }
        
        return null
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
}

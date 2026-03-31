package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Commit
import com.bontecou.syncmd.data.models.HistoryException
import com.bontecou.syncmd.data.models.RevertConflictException
import com.bontecou.syncmd.data.models.RevertResult
import com.bontecou.syncmd.data.models.RevertStrategy
import com.bontecou.syncmd.data.models.Stash
import com.bontecou.syncmd.data.models.StashResult
import com.bontecou.syncmd.data.models.Tag
import com.bontecou.syncmd.domain.repository.HistoryRepository

/**
 * Real implementation of HistoryRepository that integrates with actual git commands.
 * Uses ProcessBuilder to execute git history, stash, and tag commands.
 */
class LocalHistoryRepository : HistoryRepository {

    override suspend fun getHistory(repoPath: String, maxCommits: Int): Result<List<Commit>> {
        return try {
            val format = "%H|%an|%ae|%at|%B%n---END_COMMIT---"
            val output = executeGit(repoPath, "log", "-$maxCommits", "--format=$format")
            
            val commits = parseCommitLog(output)
            Result.success(commits)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCommit(repoPath: String, commitHash: String): Result<Commit> {
        return try {
            val format = "%H|%an|%ae|%at|%B%n---END_COMMIT---"
            val output = executeGit(repoPath, "show", "-s", "--format=$format", commitHash)
            
            val commits = parseCommitLog(output)
            if (commits.isNotEmpty()) {
                Result.success(commits[0])
            } else {
                Result.failure(HistoryException("Commit not found: $commitHash"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun revertCommit(
        repoPath: String,
        commitHash: String,
        strategy: RevertStrategy
    ): Result<RevertResult> {
        return try {
            val result = when (strategy) {
                RevertStrategy.CREATE_NEW_COMMIT -> {
                    val output = executeGit(repoPath, "revert", "--no-edit", commitHash)
                    if (checkGitSuccess(repoPath, "revert", "--no-edit", commitHash)) {
                        val newCommitOutput = executeGit(repoPath, "rev-parse", "HEAD")
                        val newCommitHash = newCommitOutput.trim()
                        RevertResult(true, newCommitHash, output, 0)
                    } else {
                        val conflictCount = executeGit(repoPath, "diff", "--name-only", "--diff-filter=U").lines().size
                        if (conflictCount > 0) {
                            RevertResult(false, null, "Revert created conflicts", conflictCount)
                        } else {
                            RevertResult(false, null, "Revert failed", 0)
                        }
                    }
                }
                RevertStrategy.HARD_RESET -> {
                    val output = executeGit(repoPath, "reset", "--hard", commitHash)
                    if (checkGitSuccess(repoPath, "reset", "--hard", commitHash)) {
                        RevertResult(true, commitHash, output, 0)
                    } else {
                        RevertResult(false, null, "Hard reset failed", 0)
                    }
                }
                RevertStrategy.SOFT_RESET -> {
                    val output = executeGit(repoPath, "reset", "--soft", commitHash)
                    if (checkGitSuccess(repoPath, "reset", "--soft", commitHash)) {
                        RevertResult(true, commitHash, output, 0)
                    } else {
                        RevertResult(false, null, "Soft reset failed", 0)
                    }
                }
                RevertStrategy.MIXED_RESET -> {
                    val output = executeGit(repoPath, "reset", "--mixed", commitHash)
                    if (checkGitSuccess(repoPath, "reset", "--mixed", commitHash)) {
                        RevertResult(true, commitHash, output, 0)
                    } else {
                        RevertResult(false, null, "Mixed reset failed", 0)
                    }
                }
            }
            
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun listStashes(repoPath: String): Result<List<Stash>> {
        return try {
            val output = executeGit(repoPath, "stash", "list", "--format=%gd|%gs|%H")
            
            val stashes = output.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size >= 3) {
                        Stash(
                            id = parts[0],
                            name = parts[1],
                            commitHash = parts[2],
                            message = parts.getOrNull(1)
                        )
                    } else {
                        null
                    }
                }
            
            Result.success(stashes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun stashSave(repoPath: String, message: String?): Result<StashResult> {
        return try {
            val args = if (message != null) {
                listOf("stash", "push", "-m", message)
            } else {
                listOf("stash", "push")
            }
            
            val output = executeGit(repoPath, *args.toTypedArray())
            
            if (checkGitSuccess(repoPath, *args.toTypedArray())) {
                // Get the new stash ID
                val listOutput = executeGit(repoPath, "stash", "list", "--format=%gd")
                val stashId = listOutput.lines().firstOrNull()
                
                Result.success(StashResult(
                    success = true,
                    stashId = stashId,
                    message = output.trim(),
                    changeCount = 0
                ))
            } else {
                Result.failure(Exception("Failed to stash changes"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun stashApply(repoPath: String, stashId: String): Result<Unit> {
        return try {
            val output = executeGit(repoPath, "stash", "apply", stashId)
            
            if (checkGitSuccess(repoPath, "stash", "apply", stashId)) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to apply stash: $stashId"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun stashPop(repoPath: String, stashId: String): Result<Unit> {
        return try {
            val output = executeGit(repoPath, "stash", "pop", stashId)
            
            if (checkGitSuccess(repoPath, "stash", "pop", stashId)) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to pop stash: $stashId"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun stashDrop(repoPath: String, stashId: String): Result<Unit> {
        return try {
            val output = executeGit(repoPath, "stash", "drop", stashId)
            
            if (checkGitSuccess(repoPath, "stash", "drop", stashId)) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to drop stash: $stashId"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun listTags(repoPath: String): Result<List<Tag>> {
        return try {
            val output = executeGit(repoPath, "tag", "-l", "-n1")
            
            val tags = output.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    val parts = line.split(Regex("\\s+"), 2)
                    val name = parts[0]
                    val message = parts.getOrNull(1)
                    
                    Tag(
                        name = name,
                        commitHash = "", // Would need additional command to get commit hash
                        isAnnotated = message != null && message.isNotEmpty(),
                        message = message
                    )
                }
            
            Result.success(tags)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createTag(
        repoPath: String,
        name: String,
        commitHash: String,
        annotated: Boolean,
        message: String?
    ): Result<Unit> {
        return try {
            val args = if (annotated) {
                if (message != null) {
                    listOf("tag", "-a", name, "-m", message, commitHash)
                } else {
                    listOf("tag", "-a", name, commitHash)
                }
            } else {
                listOf("tag", name, commitHash)
            }
            
            val output = executeGit(repoPath, *args.toTypedArray())
            
            if (checkGitSuccess(repoPath, *args.toTypedArray())) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to create tag: $name"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteTag(repoPath: String, name: String): Result<Unit> {
        return try {
            val output = executeGit(repoPath, "tag", "-d", name)
            
            if (checkGitSuccess(repoPath, "tag", "-d", name)) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete tag: $name"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getTag(repoPath: String, name: String): Result<Tag> {
        return try {
            val output = executeGit(repoPath, "show", name)
            
            if (output.isNotEmpty()) {
                val lines = output.lines()
                val message = lines.drop(1).joinToString("\n").trim()
                
                Result.success(Tag(
                    name = name,
                    commitHash = "",
                    isAnnotated = true,
                    message = message
                ))
            } else {
                Result.failure(HistoryException("Tag not found: $name"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parse git log output into Commit objects
     */
    private fun parseCommitLog(output: String): List<Commit> {
        if (output.isEmpty()) return emptyList()
        
        val commits = mutableListOf<Commit>()
        val commitBlocks = output.split("---END_COMMIT---").filter { it.isNotBlank() }
        
        for (block in commitBlocks) {
            val lines = block.trim().split("\n")
            if (lines.isEmpty()) continue
            
            val firstLine = lines[0]
            val parts = firstLine.split("|")
            
            if (parts.size >= 5) {
                val hash = parts[0]
                val author = parts[1]
                val email = parts[2]
                val timestamp = parts[3].toLongOrNull() ?: 0L
                val message = lines.drop(1).joinToString("\n").trim()
                
                commits.add(Commit(
                    hash = hash,
                    shortHash = hash.take(7),
                    author = author,
                    email = email,
                    message = message,
                    timestamp = timestamp * 1000, // Convert to milliseconds
                    parentHashes = emptyList()
                ))
            }
        }
        
        return commits
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

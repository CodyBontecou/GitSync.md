package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.DirtyRepoException
import com.bontecou.syncmd.data.models.GitFileStatusKind
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.data.models.MergeType
import com.bontecou.syncmd.data.models.PullPlan
import com.bontecou.syncmd.data.models.RepositoryStatus
import com.bontecou.syncmd.data.models.SafePullPlan
import com.bontecou.syncmd.data.models.SafePullResult
import com.bontecou.syncmd.domain.repository.PullRepository
import java.io.File

/**
 * Real implementation of PullRepository that integrates with actual git commands.
 * Uses ProcessBuilder to execute git commands via the shell.
 */
class LocalPullRepository : PullRepository {

    override suspend fun getStatus(repoPath: String): Result<RepositoryStatus> {
        return try {
            val currentBranch = getCurrentBranch(repoPath)
                .getOrElse { return Result.failure(it) }
            
            val statusOutput = executeGit(repoPath, "status", "--porcelain")
            
            val modifiedFiles = mutableListOf<GitStatusEntry>()
            val stagedFiles = mutableListOf<GitStatusEntry>()
            val untrackedFiles = mutableListOf<GitStatusEntry>()
            
            statusOutput.lines()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    // git status --porcelain format: XY filename
                    // X = staged, Y = unstaged
                    if (line.length < 3) return@forEach
                    
                    val stagedChar = line[0]
                    val unstagedChar = line[1]
                    val filePath = line.substring(3)
                    
                    when {
                        unstagedChar == '?' -> {
                            // Untracked
                            untrackedFiles.add(GitStatusEntry(filePath, GitFileStatusKind.UNTRACKED))
                        }
                        stagedChar != ' ' -> {
                            // Staged
                            stagedFiles.add(GitStatusEntry(filePath, GitFileStatusKind.STAGED))
                        }
                        unstagedChar != ' ' -> {
                            // Modified but not staged
                            modifiedFiles.add(GitStatusEntry(filePath, GitFileStatusKind.MODIFIED))
                        }
                    }
                }
            
            val allChanges = modifiedFiles + stagedFiles + untrackedFiles
            val isClean = allChanges.isEmpty()
            
            val status = RepositoryStatus(
                repoPath = repoPath,
                currentBranch = currentBranch,
                isClean = isClean,
                modifiedFiles = modifiedFiles,
                stagedFiles = stagedFiles,
                untrackedFiles = untrackedFiles,
                allChanges = allChanges
            )
            
            Result.success(status)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun planPull(repoPath: String): Result<SafePullPlan> {
        return try {
            val statusResult = getStatus(repoPath)
            if (!statusResult.isSuccess) {
                return statusResult.map { SafePullPlan("", "", "", PullPlan(MergeType.UP_TO_DATE, false, false)) }
            }
            
            val status = statusResult.getOrNull() ?: return Result.failure(Exception("Status is null"))
            
            // Fetch to update remote tracking branches
            fetch(repoPath).getOrNull() // Ignore fetch errors for plan
            
            val ahead = getCommitsAhead(repoPath).getOrNull() ?: 0
            val behind = getCommitsBehind(repoPath).getOrNull() ?: 0
            
            // Determine merge type and blocking reason
            val (mergeType, canPull, blockingReason) = when {
                status.hasDirtyState -> {
                    Triple(MergeType.UP_TO_DATE, false, "Working tree has uncommitted changes")
                }
                behind == 0 && ahead == 0 -> {
                    Triple(MergeType.UP_TO_DATE, true, null)
                }
                behind > 0 && ahead == 0 -> {
                    Triple(MergeType.FAST_FORWARD, true, null)
                }
                behind > 0 && ahead > 0 -> {
                    Triple(MergeType.MERGE_COMMIT, true, null)
                }
                else -> {
                    Triple(MergeType.UP_TO_DATE, true, null)
                }
            }
            
            val plan = SafePullPlan(
                repoPath = repoPath,
                currentBranch = status.currentBranch,
                remoteBranch = "origin/${status.currentBranch}",
                basePlan = PullPlan(
                    mergeType = mergeType,
                    fastForwardable = mergeType == MergeType.FAST_FORWARD,
                    conflictsExpected = mergeType == MergeType.MERGE_COMMIT,
                    commitsAhead = ahead,
                    commitsBehind = behind
                ),
                canPull = canPull,
                blockingReason = blockingReason
            )
            
            Result.success(plan)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun executePull(repoPath: String): Result<SafePullResult> {
        return try {
            val statusResult = getStatus(repoPath)
            if (!statusResult.isSuccess) {
                return statusResult.map { SafePullResult(false, "Failed to get status") }
            }
            
            val status = statusResult.getOrNull() ?: return Result.failure(Exception("Status is null"))
            
            if (status.hasDirtyState) {
                return Result.failure(DirtyRepoException("Cannot pull with uncommitted changes"))
            }
            
            val beforeBehind = getCommitsBehind(repoPath).getOrNull() ?: 0
            
            // Execute pull
            val pullOutput = executeGit(repoPath, "pull")
            if (pullOutput.isEmpty() && !checkGitSuccess(repoPath, "pull")) {
                return Result.failure(Exception("Pull command failed"))
            }
            
            val afterBehind = getCommitsBehind(repoPath).getOrNull() ?: 0
            val commitsApplied = beforeBehind - afterBehind
            
            val result = SafePullResult(
                success = true,
                message = "Pull completed successfully",
                commitsApplied = commitsApplied,
                filesChanged = 0
            )
            
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetch(repoPath: String): Result<Unit> {
        return try {
            val output = executeGit(repoPath, "fetch")
            // Fetch doesn't require clean working tree
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCommitsAhead(repoPath: String): Result<Int> {
        return try {
            val output = executeGit(repoPath, "rev-list", "--count", "origin/HEAD..HEAD")
            val count = output.trim().toIntOrNull() ?: 0
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCommitsBehind(repoPath: String): Result<Int> {
        return try {
            val output = executeGit(repoPath, "rev-list", "--count", "HEAD..origin/HEAD")
            val count = output.trim().toIntOrNull() ?: 0
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get the current branch name
     */
    private fun getCurrentBranch(repoPath: String): Result<String> {
        return try {
            val output = executeGit(repoPath, "rev-parse", "--abbrev-ref", "HEAD")
            val branch = output.trim()
            if (branch.isEmpty()) {
                Result.failure(Exception("Could not determine current branch"))
            } else {
                Result.success(branch)
            }
        } catch (e: Exception) {
            Result.failure(e)
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
}

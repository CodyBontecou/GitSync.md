package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.data.models.BranchType
import com.bontecou.syncmd.data.models.DirtyRepoException
import com.bontecou.syncmd.data.models.GitFileStatusKind
import com.bontecou.syncmd.data.models.MergeResult
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.data.models.MergeType
import com.bontecou.syncmd.domain.repository.BranchRepository

/**
 * Real implementation of BranchRepository that integrates with actual git commands.
 * Uses ProcessBuilder to execute git branch and merge commands.
 */
class LocalBranchRepository : BranchRepository {

    override suspend fun listBranches(repoPath: String): Result<List<Branch>> {
        return try {
            val output = executeGit(repoPath, "branch", "-a", "-v", "--no-color")
            val branches = parseBranchOutput(output)
            Result.success(branches)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCurrentBranch(repoPath: String): Result<Branch> {
        return try {
            val branchName = executeGit(repoPath, "rev-parse", "--abbrev-ref", "HEAD").trim()
            if (branchName.isEmpty()) {
                return Result.failure(Exception("Could not determine current branch"))
            }
            
            val branchesResult = listBranches(repoPath)
            if (!branchesResult.isSuccess) {
                return branchesResult.map { Branch("", BranchType.LOCAL) }
            }
            
            val currentBranch = branchesResult.getOrNull()?.find { it.name == branchName }
            if (currentBranch != null) {
                Result.success(currentBranch)
            } else {
                Result.failure(Exception("Current branch not found: $branchName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createBranch(
        repoPath: String,
        name: String,
        startPoint: String
    ): Result<Unit> {
        return try {
            val args = listOf("branch", name, startPoint)
            executeGit(repoPath, *args.toTypedArray())
            
            if (checkGitSuccess(repoPath, *args.toTypedArray())) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to create branch: $name"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun switchBranch(repoPath: String, name: String): Result<Unit> {
        return try {
            // Check if working tree is clean
            val statusOutput = executeGit(repoPath, "status", "--porcelain")
            if (statusOutput.isNotEmpty()) {
                return Result.failure(DirtyRepoException("Cannot switch branch with uncommitted changes"))
            }
            
            executeGit(repoPath, "checkout", name)
            if (checkGitSuccess(repoPath, "checkout", name)) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to switch to branch: $name"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteBranch(repoPath: String, name: String, force: Boolean): Result<Unit> {
        return try {
            val flag = if (force) "-D" else "-d"
            executeGit(repoPath, "branch", flag, name)
            
            if (checkGitSuccess(repoPath, "branch", flag, name)) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete branch: $name"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun merge(
        repoPath: String,
        sourceBranch: String,
        strategy: MergeStrategy
    ): Result<MergeResult> {
        return try {
            val strategyArg = when (strategy) {
                MergeStrategy.FAST_FORWARD -> "--ff-only"
                MergeStrategy.RECURSIVE -> "--no-ff"
                MergeStrategy.PREFER_FF -> "--ff"
            }
            
            val output = executeGit(repoPath, "merge", strategyArg, sourceBranch)
            
            val mergeType = when {
                output.contains("Fast-forward") -> MergeType.FAST_FORWARD
                output.contains("Merge made by") -> MergeType.MERGE_COMMIT
                output.contains("CONFLICT") -> MergeType.CONFLICT
                output.contains("Already up to date") -> MergeType.UP_TO_DATE
                else -> MergeType.MERGE_COMMIT
            }
            
            val success = checkGitSuccess(repoPath, "merge", strategyArg, sourceBranch) || 
                         mergeType == MergeType.UP_TO_DATE
            
            val conflictCount = output.lines().count { it.contains("CONFLICT") }
            
            val result = MergeResult(
                success = success,
                mergeType = mergeType,
                message = output.trim(),
                conflictCount = conflictCount
            )
            
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parse git branch output into Branch objects
     */
    private fun parseBranchOutput(output: String): List<Branch> {
        return output.lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val isHead = line.startsWith("*")
                val parts = line.removePrefix("*").trim().split(Regex("\\s+"), 2)
                
                if (parts.isEmpty()) return@map null
                
                val branchName = parts[0]
                val type = when {
                    branchName.startsWith("remotes/") -> {
                        BranchType.REMOTE
                    }
                    else -> BranchType.LOCAL
                }
                
                val displayName = if (branchName.startsWith("remotes/origin/")) {
                    branchName.removePrefix("remotes/origin/")
                } else {
                    branchName
                }
                
                Branch(
                    name = displayName,
                    type = type,
                    isHead = isHead,
                    trackingBranch = null,
                    lastCommit = parts.getOrNull(1)?.substring(0, 7),
                    lastCommitMessage = null
                )
            }
            .filterNotNull()
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

package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.data.models.BranchType
import com.bontecou.syncmd.data.models.DirtyRepoException
import com.bontecou.syncmd.data.models.MergeResult
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.data.models.MergeType
import com.bontecou.syncmd.domain.repository.BranchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeCommand
import java.io.File

/**
 * JGit-backed implementation of [BranchRepository].
 *
 * Uses the pure-Java JGit library — no system `git` binary required.
 * ProcessBuilder("git") fails on Android with "No such file or directory";
 * JGit works everywhere.
 */
class LocalBranchRepository : BranchRepository {

    override suspend fun listBranches(repoPath: String): Result<List<Branch>> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val headBranch = git.repository.branch

                    val refs = git.branchList()
                        .setListMode(ListBranchCommand.ListMode.ALL)
                        .call()

                    val branches = refs.mapNotNull { ref ->
                        val refName = ref.name
                        val isRemote = refName.startsWith("refs/remotes/")
                        val isLocal  = refName.startsWith("refs/heads/")
                        if (!isRemote && !isLocal) return@mapNotNull null

                        // Strip "refs/remotes/origin/" or "refs/remotes/" or "refs/heads/"
                        val displayName = when {
                            refName.startsWith("refs/remotes/origin/") ->
                                refName.removePrefix("refs/remotes/origin/")
                            refName.startsWith("refs/remotes/") ->
                                refName.removePrefix("refs/remotes/")
                            else ->
                                refName.removePrefix("refs/heads/")
                        }

                        // Skip "HEAD" remote pointer
                        if (displayName == "HEAD") return@mapNotNull null

                        Branch(
                            name       = displayName,
                            type       = if (isRemote) BranchType.REMOTE else BranchType.LOCAL,
                            isHead     = isLocal && displayName == headBranch,
                            lastCommit = ref.objectId?.abbreviate(7)?.name(),
                        )
                    }

                    Result.success(branches)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getCurrentBranch(repoPath: String): Result<Branch> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val branchName = git.repository.branch.takeIf { it.isNotEmpty() }
                        ?: return@withContext Result.failure(Exception("Could not determine current branch"))

                    // Look up the ref to get the latest commit hash
                    val ref = git.repository.findRef("refs/heads/$branchName")

                    Result.success(
                        Branch(
                            name       = branchName,
                            type       = BranchType.LOCAL,
                            isHead     = true,
                            lastCommit = ref?.objectId?.abbreviate(7)?.name(),
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun createBranch(
        repoPath: String,
        name: String,
        startPoint: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Git.open(File(repoPath)).use { git ->
                val cmd = git.branchCreate().setName(name)
                if (startPoint != "HEAD") cmd.setStartPoint(startPoint)
                cmd.call()
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun switchBranch(repoPath: String, name: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val status = git.status().call()
                    if (!status.isClean) {
                        return@withContext Result.failure(
                            DirtyRepoException("Cannot switch branch with uncommitted changes")
                        )
                    }
                    git.checkout().setName(name).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun deleteBranch(
        repoPath: String,
        name: String,
        force: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Git.open(File(repoPath)).use { git ->
                git.branchDelete()
                    .setBranchNames(name)
                    .setForce(force)
                    .call()
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun merge(
        repoPath: String,
        sourceBranch: String,
        strategy: MergeStrategy,
    ): Result<MergeResult> = withContext(Dispatchers.IO) {
        try {
            Git.open(File(repoPath)).use { git ->
                val srcRef = git.repository.resolve("refs/heads/$sourceBranch")
                    ?: git.repository.resolve(sourceBranch)
                    ?: return@withContext Result.failure(Exception("Cannot resolve branch: $sourceBranch"))

                val ffMode = when (strategy) {
                    MergeStrategy.FAST_FORWARD -> MergeCommand.FastForwardMode.FF_ONLY
                    MergeStrategy.RECURSIVE    -> MergeCommand.FastForwardMode.NO_FF
                    MergeStrategy.PREFER_FF    -> MergeCommand.FastForwardMode.FF
                }

                val result = git.merge()
                    .include(srcRef)
                    .setFastForward(ffMode)
                    .call()

                val mergeType = when (result.mergeStatus) {
                    org.eclipse.jgit.api.MergeResult.MergeStatus.FAST_FORWARD    -> MergeType.FAST_FORWARD
                    org.eclipse.jgit.api.MergeResult.MergeStatus.MERGED          -> MergeType.MERGE_COMMIT
                    org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING     -> MergeType.CONFLICT
                    org.eclipse.jgit.api.MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> MergeType.UP_TO_DATE
                    else -> MergeType.MERGE_COMMIT
                }

                Result.success(
                    MergeResult(
                        success       = result.mergeStatus.isSuccessful,
                        mergeType     = mergeType,
                        message       = result.mergeStatus.toString(),
                        conflictCount = result.conflicts?.size ?: 0,
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

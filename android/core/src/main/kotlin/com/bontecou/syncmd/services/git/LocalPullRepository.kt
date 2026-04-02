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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/**
 * JGit-backed implementation of [PullRepository].
 *
 * Uses the pure-Java JGit library — no system `git` binary required.
 * ProcessBuilder("git") fails on Android with "No such file or directory";
 * JGit works everywhere.
 */
class LocalPullRepository(
    private val tokenProvider: (() -> String?)? = null,
) : PullRepository {

    override suspend fun getStatus(repoPath: String): Result<RepositoryStatus> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val branch = git.repository.branch.takeIf { it.isNotEmpty() }
                        ?: return@withContext Result.failure(Exception("Could not determine current branch"))

                    val jgitStatus = git.status().call()

                    val modifiedFiles = jgitStatus.modified
                        .map { GitStatusEntry(it, GitFileStatusKind.MODIFIED) }
                    val stagedFiles = (jgitStatus.changed + jgitStatus.added + jgitStatus.removed)
                        .map { GitStatusEntry(it, GitFileStatusKind.STAGED) }
                    val untrackedFiles = jgitStatus.untracked
                        .map { GitStatusEntry(it, GitFileStatusKind.UNTRACKED) }
                    val allChanges = modifiedFiles + stagedFiles + untrackedFiles

                    Result.success(
                        RepositoryStatus(
                            repoPath      = repoPath,
                            currentBranch = branch,
                            isClean       = jgitStatus.isClean,
                            modifiedFiles = modifiedFiles,
                            stagedFiles   = stagedFiles,
                            untrackedFiles = untrackedFiles,
                            allChanges    = allChanges,
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun planPull(repoPath: String): Result<SafePullPlan> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val branch = git.repository.branch.takeIf { it.isNotEmpty() }
                        ?: return@withContext Result.failure(Exception("Could not determine current branch"))

                    val jgitStatus = git.status().call()
                    val isDirty = !jgitStatus.isClean

                    // BranchTrackingStatus reads from the fetched remote-tracking refs;
                    // it may be null if there is no upstream configured yet.
                    val tracking = BranchTrackingStatus.of(git.repository, branch)
                    val ahead  = tracking?.aheadCount  ?: 0
                    val behind = tracking?.behindCount ?: 0

                    val (mergeType, canPull, blockingReason) = when {
                        isDirty          -> Triple(MergeType.UP_TO_DATE, false, "Working tree has uncommitted changes")
                        behind == 0 && ahead == 0 -> Triple(MergeType.UP_TO_DATE, true, null)
                        behind > 0 && ahead == 0  -> Triple(MergeType.FAST_FORWARD, true, null)
                        behind > 0 && ahead > 0   -> Triple(MergeType.MERGE_COMMIT, true, null)
                        else             -> Triple(MergeType.UP_TO_DATE, true, null)
                    }

                    Result.success(
                        SafePullPlan(
                            repoPath      = repoPath,
                            currentBranch = branch,
                            remoteBranch  = "origin/$branch",
                            basePlan = PullPlan(
                                mergeType         = mergeType,
                                fastForwardable   = mergeType == MergeType.FAST_FORWARD,
                                conflictsExpected = mergeType == MergeType.MERGE_COMMIT,
                                commitsAhead      = ahead,
                                commitsBehind     = behind,
                            ),
                            canPull       = canPull,
                            blockingReason = blockingReason,
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun executePull(repoPath: String): Result<SafePullResult> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val jgitStatus = git.status().call()
                    if (!jgitStatus.isClean) {
                        return@withContext Result.failure(
                            DirtyRepoException("Cannot pull with uncommitted changes")
                        )
                    }

                    val pullCommand = git.pull()
                    credentialsProvider()?.let { pullCommand.setCredentialsProvider(it) }
                    val pullResult = pullCommand.call()
                    Result.success(
                        SafePullResult(
                            success        = pullResult.isSuccessful,
                            message        = if (pullResult.isSuccessful) "Pull completed successfully" else "Pull failed",
                            mergeRequired  = pullResult.mergeResult != null,
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun fetch(repoPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val fetchCommand = git.fetch()
                    credentialsProvider()?.let { fetchCommand.setCredentialsProvider(it) }
                    fetchCommand.call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getCommitsAhead(repoPath: String): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val branch = git.repository.branch.takeIf { it.isNotEmpty() }
                        ?: return@withContext Result.success(0)
                    val tracking = BranchTrackingStatus.of(git.repository, branch)
                    Result.success(tracking?.aheadCount ?: 0)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getCommitsBehind(repoPath: String): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val branch = git.repository.branch.takeIf { it.isNotEmpty() }
                        ?: return@withContext Result.success(0)
                    val tracking = BranchTrackingStatus.of(git.repository, branch)
                    Result.success(tracking?.behindCount ?: 0)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun credentialsProvider(): UsernamePasswordCredentialsProvider? {
        val token = tokenProvider?.invoke()?.takeIf { it.isNotBlank() } ?: return null
        return UsernamePasswordCredentialsProvider("x-access-token", token)
    }
}

package com.bontecou.syncmd

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.data.models.DirtyRepoException
import com.bontecou.syncmd.data.models.GitFileStatusKind
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.data.models.MergeType
import com.bontecou.syncmd.data.models.PullPlan
import com.bontecou.syncmd.data.models.PushResult
import com.bontecou.syncmd.domain.repository.GitRepository

/**
 * Fake implementation of GitRepository for unit tests.
 * Allows setting up deterministic scenarios (clean repos, dirty repos, conflicts, etc.)
 */
class FakeGitRepository(
    private val simulateNetworkError: Boolean = false,
    private val simulateLocalError: Boolean = false
) : GitRepository {

    private var dirtyState: List<GitStatusEntry> = emptyList()
    private var mergeType: MergeType = MergeType.FAST_FORWARD
    private var clonedRepos: MutableSet<String> = mutableSetOf()

    fun setupDirtyRepo(entries: List<GitStatusEntry>) {
        dirtyState = entries
    }

    fun setupCleanRepo() {
        dirtyState = emptyList()
    }

    fun setupMergeType(type: MergeType) {
        mergeType = type
    }

    override suspend fun clone(
        url: String,
        path: String,
        creds: Credentials
    ): Result<Unit> = when {
        simulateNetworkError -> Result.failure(Exception("Network error"))
        simulateLocalError -> Result.failure(Exception("Local error"))
        else -> {
            clonedRepos.add(path)
            Result.success(Unit)
        }
    }

    override suspend fun getStatus(repoPath: String): Result<List<GitStatusEntry>> = when {
        simulateLocalError -> Result.failure(Exception("Local error"))
        else -> Result.success(dirtyState)
    }

    override suspend fun pull(repoPath: String): Result<PullPlan> = when {
        dirtyState.isNotEmpty() -> Result.failure(DirtyRepoException())
        simulateNetworkError -> Result.failure(Exception("Network error"))
        else -> Result.success(
            PullPlan(
                mergeType = mergeType,
                fastForwardable = mergeType == MergeType.FAST_FORWARD,
                conflictsExpected = false
            )
        )
    }

    override suspend fun push(
        repoPath: String,
        message: String
    ): Result<PushResult> = when {
        dirtyState.isNotEmpty() -> Result.failure(DirtyRepoException())
        simulateNetworkError -> Result.failure(Exception("Network error"))
        else -> Result.success(PushResult(success = true, message = "Pushed successfully"))
    }
}

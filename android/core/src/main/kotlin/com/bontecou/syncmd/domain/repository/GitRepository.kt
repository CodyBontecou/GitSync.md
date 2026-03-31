package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.data.models.PullPlan
import com.bontecou.syncmd.data.models.PushResult

/**
 * Protocol for git operations. All implementations must provide clone, pull, push, and status.
 * Implementations can be real (using libgit2) or fake (for testing).
 */
interface GitRepository {

    /**
     * Clone a repository from a remote URL to a local path
     */
    suspend fun clone(
        url: String,
        path: String,
        creds: Credentials
    ): Result<Unit>

    /**
     * Get status of all files in the repository (modified, staged, untracked)
     */
    suspend fun getStatus(repoPath: String): Result<List<GitStatusEntry>>

    /**
     * Analyze what a pull would do (fetch + merge plan)
     */
    suspend fun pull(repoPath: String): Result<PullPlan>

    /**
     * Push commits to remote
     */
    suspend fun push(
        repoPath: String,
        message: String
    ): Result<PushResult>
}

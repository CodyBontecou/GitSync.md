package com.bontecou.syncmd.domain.repository

import com.bontecou.syncmd.services.git.GitUntrackResult
import com.bontecou.syncmd.services.git.GitUntrackTarget

/** "Stop tracking": remove a file, folder or glob from Git while keeping it on disk (issue #149). */
interface UntrackRepository {
    /** Index paths (tracked or staged) that [target] would stop tracking. */
    suspend fun trackedPaths(repoPath: String, target: GitUntrackTarget): Result<List<String>>

    /** Removes [target] from the index, ignores it in the root `.gitignore` and commits only those two changes. */
    suspend fun stopTracking(repoPath: String, target: GitUntrackTarget, authorName: String, authorEmail: String): Result<GitUntrackResult>
}

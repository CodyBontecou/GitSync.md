package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.services.git.lfs.GitLfs
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RepositoryState
import java.io.File

/**
 * What Home can say about one vault without touching the network: where the branch stands against
 * its last-fetched upstream (↑ commits to push, ↓ commits to pull), how many files are edited, when
 * the remote was last checked (the last fetch) and when the last sync completed.
 */
data class RepositorySyncStatus(
    val branch: String,
    val headCommit: String?,
    /** Commits on this phone that the upstream doesn't have yet. */
    val ahead: Int,
    /** Commits fetched from the upstream that this branch doesn't have yet. */
    val behind: Int,
    /** Edited, added, deleted or untracked files in the working tree. */
    val changes: Int,
    /** Null when the branch has no upstream (detached HEAD, never pushed, or a local-only vault). */
    val upstream: Upstream?,
    /** False while a merge, rebase, cherry-pick or revert is in progress. */
    val safe: Boolean,
    /** Last fetch from the remote (FETCH_HEAD), in epoch millis. */
    val checkedAt: Long?,
    /** Last completed sync (clone, commit, pull, push…), in epoch millis. */
    val lastSyncAt: Long?,
    /** Bytes of Git LFS content stored for this vault on the phone. */
    val lfsBytes: Long,
    /** The upstream remote's URL (or origin's when there is no upstream). */
    val remoteUrl: String?,
) {
    data class Upstream(val remote: String, val remoteBranch: String)

    val inSync: Boolean get() = safe && ahead == 0 && behind == 0 && changes == 0

    companion object {
        /** Holds the repository lease briefly; never fetches, writes or asks for credentials. */
        suspend fun read(path: String): RepositorySyncStatus = RepositoryOperationCoordinator.withRepository(path) {
            GitLfs.open(File(path)).use { git ->
                val repository = git.repository
                check(!repository.isBare && repository.workTree.canonicalFile == File(path).canonicalFile) {
                    "Choose the working copy's root folder"
                }
                repository.refDatabase.refresh()
                val full = repository.fullBranch
                val branch = repository.branch
                val head = repository.resolve(Constants.HEAD)?.name
                val local = full?.takeIf { it.startsWith(Constants.R_HEADS) }?.removePrefix(Constants.R_HEADS)
                val remote = local?.let { repository.config.getString("branch", it, "remote") }?.takeIf { it.isNotBlank() && it != "." }
                val merge = local?.let { repository.config.getString("branch", it, "merge") }?.takeIf { it.startsWith(Constants.R_HEADS) }
                val upstream = if (remote != null && merge != null) Upstream(remote, merge.removePrefix(Constants.R_HEADS)) else null
                val tracking = if (upstream != null && head != null) runCatching { BranchTrackingStatus.of(repository, local) }.getOrNull() else null
                val changes = git.status().call().let { status ->
                    (status.added + status.changed + status.removed + status.missing + status.modified + status.untracked + status.conflicting).size
                }
                val fetchHead = File(repository.directory, "FETCH_HEAD")
                RepositorySyncStatus(
                    branch = branch ?: "",
                    headCommit = head,
                    ahead = tracking?.aheadCount ?: 0,
                    behind = tracking?.behindCount ?: 0,
                    changes = changes,
                    upstream = upstream,
                    safe = repository.repositoryState == RepositoryState.SAFE,
                    checkedAt = fetchHead.takeIf { it.isFile }?.lastModified()?.takeIf { it > 0 },
                    lastSyncAt = RepositoryActivity.read(repository),
                    lfsBytes = directorySize(File(repository.directory, "lfs/objects")),
                    remoteUrl = repository.config.getString("remote", upstream?.remote ?: "origin", "url"),
                )
            }
        }

        private fun directorySize(root: File): Long {
            if (!root.isDirectory) return 0
            return root.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .filter { it.isFile }.sumOf { it.length() }
        }
    }
}

package com.bontecou.syncmd.services.automation

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

/**
 * What a widget or notification shows for one vault, read locally (no network): the branch, how
 * many changes are waiting to go up (uncommitted files plus commits ahead of the upstream), and
 * when it last synced (the last fetch, else the last commit).
 */
data class VaultStatus(val branch: String?, val changedFiles: Int, val ahead: Int, val behind: Int, val lastSync: Long?) {
    val toPush: Int get() = changedFiles + ahead
}

/** The commits a pull brought in and who wrote the newest of them. */
data class VaultIncoming(val commits: Int, val author: String?)

object VaultStatusReader {
    private fun open(path: String) = FileRepositoryBuilder().setWorkTree(File(path)).setMustExist(true).build()

    fun read(path: String): VaultStatus? = runCatching {
        open(path).use { repo ->
            val branch = repo.branch?.takeIf { repo.fullBranch?.startsWith("refs/heads/") == true }
            val tracking = branch?.let { runCatching { BranchTrackingStatus.of(repo, it) }.getOrNull() }
            val status = Git(repo).status().call()
            val changed = (status.added + status.changed + status.modified + status.missing + status.removed + status.untracked + status.conflicting).size
            val fetched = File(repo.directory, "FETCH_HEAD").takeIf { it.isFile }?.lastModified()?.takeIf { it > 0 }
            val committed = repo.resolve("HEAD")?.let { head -> RevWalk(repo).use { it.parseCommit(head).commitTime * 1000L } }
            VaultStatus(branch, changed, tracking?.aheadCount ?: 0, tracking?.behindCount ?: 0, fetched ?: committed)
        }
    }.getOrNull()

    fun head(path: String): String? = runCatching { open(path).use { it.resolve("HEAD")?.name } }.getOrNull()

    /** Commits reachable from [after] but not [before]; [before] null counts nothing (an unknown baseline). */
    fun incoming(path: String, before: String?, after: String?): VaultIncoming = runCatching {
        if (before == null || after == null || before == after) return VaultIncoming(0, null)
        open(path).use { repo ->
            RevWalk(repo).use { walk ->
                val start = walk.parseCommit(ObjectId.fromString(after))
                walk.markStart(start)
                walk.markUninteresting(walk.parseCommit(ObjectId.fromString(before)))
                val count = walk.count().coerceAtMost(100_000)
                VaultIncoming(count, start.authorIdent?.name?.takeIf { it.isNotBlank() })
            }
        }
    }.getOrDefault(VaultIncoming(0, null))
}

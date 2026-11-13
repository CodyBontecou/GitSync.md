package com.bontecou.syncmd.services.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File

/** Read-only history counts for summaries (Background Sync "notes ↓2"). */
object CommitCount {
    /** Commits reachable from [to] but not from [from], capped at [limit]; null when the history can't be read. */
    fun between(path: String, from: String?, to: String, limit: Int = 1000): Int? = runCatching {
        Git.open(File(path)).use { git ->
            val repository = git.repository
            RevWalk(repository).use { walk ->
                walk.markStart(walk.parseCommit(repository.resolve(to)))
                if (from != null) repository.resolve(from)?.let { walk.markUninteresting(walk.parseCommit(it)) }
                walk.asSequence().take(limit).count()
            }
        }
    }.getOrNull()
}

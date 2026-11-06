package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.GitFileStatusKind
import com.bontecou.syncmd.data.models.GitStatusEntry
import org.eclipse.jgit.api.Status

/** One path can have both index and worktree changes; never count it twice or omit deletions. */
internal fun Status.toStatusEntries(): List<GitStatusEntry> {
    val staged = changed + added + removed
    val unstaged = modified + missing + untracked + conflicting
    return (staged + unstaged).sorted().map { path ->
        GitStatusEntry(path, when {
            path in staged -> GitFileStatusKind.STAGED
            path in untracked -> GitFileStatusKind.UNTRACKED
            else -> GitFileStatusKind.MODIFIED
        }, hasStagedChanges = path in staged, hasUnstagedChanges = path in unstaged,
            isDeleted = path in removed || path in missing, isConflicted = path in conflicting)
    }
}

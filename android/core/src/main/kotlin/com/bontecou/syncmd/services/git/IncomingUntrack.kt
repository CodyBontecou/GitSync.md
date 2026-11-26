package com.bontecou.syncmd.services.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.ignore.IgnoreNode
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File

/**
 * Local edits to files another device stopped tracking (issue #137).
 *
 * When a device stops tracking a file it pushes a commit that deletes the path and adds it to the
 * root `.gitignore`. Obsidian rewrites files like `.obsidian/workspace-mobile.json` constantly, so a
 * receiving device almost always has a local edit to exactly that path. That edit never needs a
 * commit: it must not block a fast-forward, and committing it to resolve the block would end in a
 * modify/delete conflict that re-tracks the file. Instead the fast-forward sets the edit aside,
 * updates, and writes it back as an ignored, untracked file.
 */
internal object IncomingUntrack {
    class Carry(val path: String, val bytes: ByteArray?)

    /** Changed paths, as JGit's `Status.isClean` counts them. */
    fun changedPaths(status: Status): Set<String> =
        status.added + status.changed + status.removed + status.missing + status.modified +
            status.untracked + status.untrackedFolders + status.conflicting

    /**
     * Local edits (modified or deleted, staged or not) to paths that `target` deletes relative to
     * HEAD and that `target`'s root `.gitignore` ignores.
     */
    fun carriedPaths(git: Git, status: Status, target: ObjectId): Set<String> {
        val edited = (status.changed + status.removed + status.missing + status.modified) -
            (status.added + status.untracked + status.conflicting)
        if (edited.isEmpty()) return emptySet()
        val repository = git.repository
        val head = repository.resolve(Constants.HEAD) ?: return emptySet()
        RevWalk(repository).use { walk ->
            val headTree = walk.parseCommit(head).tree
            val targetTree = walk.parseCommit(target).tree
            val rules = TreeWalk.forPath(repository, Constants.DOT_GIT_IGNORE, targetTree)?.use { entry ->
                IgnoreNode().apply { repository.open(entry.getObjectId(0)).openStream().use { parse(it) } }
            } ?: return emptySet()
            return edited.filterTo(mutableSetOf()) { path ->
                TreeWalk.forPath(repository, path, headTree)?.use { true } == true &&
                    TreeWalk.forPath(repository, path, targetTree) == null &&
                    isIgnored(rules, path)
            }
        }
    }

    /** Git semantics: a path is ignored when it or any parent folder matches. */
    private fun isIgnored(rules: IgnoreNode, path: String): Boolean {
        val parts = path.split('/')
        for (depth in 1 until parts.size) {
            if (rules.checkIgnored(parts.take(depth).joinToString("/"), true) == true) return true
        }
        return rules.checkIgnored(path, false) == true
    }

    /** True when something other than carried edits is in the way of a fast-forward to [target]. */
    fun hasBlockingChanges(git: Git, target: ObjectId?): Boolean {
        val status = git.status().call()
        if (status.isClean) return false
        if (target == null) return true
        val carried = carriedPaths(git, status, target)
        return (changedPaths(status) - carried).isNotEmpty()
    }

    /**
     * Saves and resets the carried edits when they are the only local changes. Returns what it set
     * aside; always pass the result to [restore], on success and on failure.
     */
    fun setAside(git: Git, target: ObjectId): List<Carry> {
        val status = git.status().call()
        if (status.isClean) return emptyList()
        val carried = carriedPaths(git, status, target)
        if (carried.isEmpty() || (changedPaths(status) - carried).isNotEmpty()) return emptyList()
        val workTree = git.repository.workTree
        val saved = carried.sorted().map { path ->
            val file = File(workTree, path)
            Carry(path, if (file.isFile) file.readBytes() else null)
        }
        try {
            git.reset().apply { carried.forEach { addPath(it) } }.call()
            git.checkout().apply { carried.forEach { addPath(it) } }.call()
        } catch (failure: Exception) {
            restore(workTree, saved)
            throw failure
        }
        return saved
    }

    /** Writes set-aside edits back, or re-deletes files that were deleted locally. */
    fun restore(workTree: File, carried: List<Carry>) {
        for (item in carried) {
            val file = File(workTree, item.path)
            val bytes = item.bytes
            if (bytes != null) {
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            } else if (file.exists()) {
                file.delete()
            }
        }
    }
}

package com.bontecou.syncmd.services.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import org.eclipse.jgit.treewalk.filter.TreeFilter
import java.io.File

/**
 * HEAD is ahead of the index or working tree (issue #130).
 *
 * Another Git client (Obsidian Git <= 2.41 on mobile) can move the branch and then fail its
 * checkout. The vault then holds pre-update files that look like local edits; committing them
 * silently reverts the incoming changes on the remote.
 */
data class PendingCheckoutReport(
    val baseCommit: String,
    val headCommit: String,
    /** Files whose bytes on disk still equal the pre-update version (missing counts when the update added it). */
    val staleWorktreePaths: List<String>,
    /** Index entries that still hold the pre-update blob; committing them would undo the update. */
    val staleIndexPaths: List<String>,
    /** Files the update changed that were also changed on this device; never overwritten. */
    val localEditPaths: List<String>,
) {
    val needsFinishing: Boolean get() = staleWorktreePaths.isNotEmpty() || staleIndexPaths.isNotEmpty()
    val stalePaths: List<String> get() = (staleWorktreePaths + staleIndexPaths).distinct().sorted()
}

data class PendingCheckoutCompletion(
    val restoredPaths: List<String>,
    val keptLocalPaths: List<String>,
    val remainingStalePaths: List<String>,
)

/** Thrown instead of a pull or commit while a previous update has not been checked out. */
class PullNotFinishedException(val report: PendingCheckoutReport) :
    IllegalStateException(
        "The last pull didn't finish updating ${report.stalePaths.size} " +
            (if (report.stalePaths.size == 1) "file" else "files") +
            ". Finish the update before pulling or committing.",
    )

object PendingCheckoutInspector {
    const val VERIFIED_FILE = "gitsync-checkout-verified"
    private val UPDATE_REFLOG_PREFIXES = listOf("pull", "merge", "reset: moving to", "rebase", "fast-forward", "fast forward")
    private const val ANCESTOR_SEARCH_LIMIT = 200

    /**
     * Returns a report when HEAD is ahead of the index or working tree, or null when the checkout is
     * coherent. Only paths changed by the most recent branch update are read, and a verified HEAD is
     * cached, so routine pulls do not rescan the vault.
     */
    fun inspect(repository: Repository, useCache: Boolean = true): PendingCheckoutReport? {
        val fullBranch = repository.fullBranch?.takeIf { it.startsWith("refs/heads/") } ?: return null
        val head = repository.exactRef(fullBranch)?.objectId ?: return null
        val verified = File(repository.directory, VERIFIED_FILE)
        if (useCache && runCatching { verified.readText().trim() }.getOrNull() == head.name) return null

        val candidates = linkedSetOf<ObjectId>()
        lastUpdateReflogBase(repository, fullBranch, head)?.let(candidates::add)
        ancestorMatchingIndexTree(repository, head)?.let(candidates::add)

        for (base in candidates) {
            if (base == head || !isAncestor(repository, base, head)) continue
            val report = classify(repository, base, head)
            if (report.needsFinishing) return report
        }
        runCatching { verified.writeText(head.name + "\n") }
        return null
    }

    /** Compares disk and index state of every path changed between [base] and [head] against both versions. */
    fun classify(repository: Repository, base: ObjectId, head: ObjectId): PendingCheckoutReport {
        val staleWorktree = mutableListOf<String>()
        val staleIndex = mutableListOf<String>()
        val localEdits = mutableListOf<String>()
        RevWalk(repository).use { revWalk ->
            val baseTree = revWalk.parseCommit(base).tree
            val headTree = revWalk.parseCommit(head).tree
            val cache = DirCache.read(repository)
            TreeWalk(repository).use { walk ->
                walk.addTree(baseTree)
                walk.addTree(headTree)
                walk.addTree(DirCacheIterator(cache))
                // No DirCacheIterator is attached to the file iterator, so every candidate is hashed
                // (with clean filters) instead of trusting the index stat cache.
                walk.addTree(FileTreeIterator(repository))
                walk.isRecursive = true
                walk.filter = ChangedBetweenFirstTwo
                while (walk.next()) {
                    val baseMode = walk.getRawMode(0)
                    val headMode = walk.getRawMode(1)
                    if (isSpecial(baseMode) || isSpecial(headMode)) continue
                    val baseId = if (baseMode == 0) null else walk.getObjectId(0)
                    val headId = if (headMode == 0) null else walk.getObjectId(1)
                    if (baseId == headId) continue
                    val path = walk.pathString
                    val disk = walk.getTree(3, FileTreeIterator::class.java)
                    val diskId: ObjectId? = when {
                        disk == null -> null
                        FileMode.REGULAR_FILE.equals(disk.entryRawMode) || FileMode.EXECUTABLE_FILE.equals(disk.entryRawMode) ->
                            runCatching { disk.entryObjectId.copy() }.getOrElse { UNREADABLE }
                        else -> UNREADABLE
                    }
                    if (diskId != headId) {
                        if (diskId == baseId) staleWorktree += path else localEdits += path
                    }
                    val indexId = walk.getTree(2, DirCacheIterator::class.java)?.entryObjectId?.copy()
                    if (indexId == baseId && indexId != headId) staleIndex += path
                }
            }
        }
        return PendingCheckoutReport(base.name, head.name, staleWorktree.sorted(), staleIndex.sorted(), localEdits.sorted())
    }

    /**
     * Brings stale paths up to date with HEAD without touching local edits. Stale index entries are
     * reset to HEAD; each stale file is re-checked immediately before it is replaced, so a file
     * edited since the inspection is kept.
     */
    fun finish(git: Git): PendingCheckoutCompletion {
        val repository = git.repository
        val report = inspect(repository, useCache = false)
            ?: return PendingCheckoutCompletion(emptyList(), emptyList(), emptyList())
        val base = ObjectId.fromString(report.baseCommit)
        val head = ObjectId.fromString(report.headCommit)

        if (report.staleIndexPaths.isNotEmpty()) {
            val reset = git.reset().setRef(head.name)
            report.staleIndexPaths.forEach(reset::addPath)
            reset.call()
        }

        if (report.staleWorktreePaths.isNotEmpty()) {
            // Re-verify right before writing: only files still holding the pre-update bytes are replaced.
            val recheck = classify(repository, base, head)
            val stillStale = report.staleWorktreePaths.filter { it in recheck.staleWorktreePaths }
            val headIds = treeIds(repository, head, stillStale)
            val present = stillStale.filter { headIds[it] != null }
            val removed = stillStale.filter { headIds[it] == null }
            if (present.isNotEmpty()) {
                val checkout = git.checkout().setStartPoint(head.name)
                present.forEach(checkout::addPath)
                checkout.call()
            }
            for (path in removed) {
                File(repository.workTree, path).delete()
                if (DirCache.read(repository).getEntry(path) != null) git.rm().setCached(true).addFilepattern(path).call()
            }
        }

        val remaining = inspect(repository, useCache = false)?.stalePaths.orEmpty()
        return PendingCheckoutCompletion(
            restoredPaths = report.stalePaths.filterNot { it in remaining },
            keptLocalPaths = report.localEditPaths,
            remainingStalePaths = remaining,
        )
    }

    /** The value the branch had before its latest update, when that update should have rewritten files. */
    private fun lastUpdateReflogBase(repository: Repository, fullBranch: String, head: ObjectId): ObjectId? {
        val entry = runCatching { repository.getReflogReader(fullBranch)?.lastEntry }.getOrNull() ?: return null
        if (entry.newId != head || entry.oldId == ObjectId.zeroId()) return null
        val comment = entry.comment.orEmpty().lowercase()
        return entry.oldId.takeIf { UPDATE_REFLOG_PREFIXES.any(comment::startsWith) }
    }

    /** An ancestor of HEAD whose tree exactly equals the index: a client moved the branch without updating the index. */
    private fun ancestorMatchingIndexTree(repository: Repository, head: ObjectId): ObjectId? {
        val cache = DirCache.read(repository)
        if (cache.hasUnmergedPaths()) return null
        // Like `git write-tree`. In the stale case these trees already exist in the object store.
        val indexTree = runCatching {
            repository.newObjectInserter().use { inserter -> cache.writeTree(inserter).also { inserter.flush() } }
        }.getOrNull() ?: return null
        RevWalk(repository).use { walk ->
            var commit = walk.parseCommit(head)
            if (commit.tree.id == indexTree) return null
            repeat(ANCESTOR_SEARCH_LIMIT) {
                if (commit.parentCount == 0) return null
                commit = walk.parseCommit(commit.getParent(0))
                if (commit.tree.id == indexTree) return commit.id
            }
        }
        return null
    }

    private fun isAncestor(repository: Repository, ancestor: ObjectId, head: ObjectId): Boolean =
        RevWalk(repository).use { it.isMergedInto(it.parseCommit(ancestor), it.parseCommit(head)) }

    private fun treeIds(repository: Repository, commit: ObjectId, paths: List<String>): Map<String, ObjectId?> {
        if (paths.isEmpty()) return emptyMap()
        val result = paths.associateWith<String, ObjectId?> { null }.toMutableMap()
        RevWalk(repository).use { revWalk ->
            TreeWalk(repository).use { walk ->
                walk.addTree(revWalk.parseCommit(commit).tree)
                walk.isRecursive = true
                walk.filter = PathFilterGroup.createFromStrings(paths)
                while (walk.next()) if (walk.pathString in result) result[walk.pathString] = walk.getObjectId(0)
            }
        }
        return result
    }

    private fun isSpecial(mode: Int) = mode != 0 && (FileMode.GITLINK.equals(mode) || FileMode.SYMLINK.equals(mode))

    /** Sentinel for a disk entry that is neither version (directory, symlink, unreadable). */
    private val UNREADABLE: ObjectId = ObjectId.fromString("ffffffffffffffffffffffffffffffffffffffff")

    /** Walks only entries (and subtrees) that differ between trees 0 and 1. */
    private object ChangedBetweenFirstTwo : TreeFilter() {
        override fun include(walker: TreeWalk): Boolean =
            walker.getRawMode(0) != walker.getRawMode(1) || !walker.idEqual(0, 1)
        override fun shouldBeRecursive() = true
        override fun clone(): TreeFilter = this
    }
}

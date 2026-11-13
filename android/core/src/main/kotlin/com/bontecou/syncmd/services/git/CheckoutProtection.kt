package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.DirtyRepoException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.merge.ResolveMerger
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.TreeSet

/** Checks local content against the paths a checkout can write, without changing the real index/worktree. */
internal object CheckoutProtection {
    data class Snapshot(
        val branch: String?, val head: String?, val worktree: String, val metadata: String,
        val marker: String, val config: String?, val index: String?,
    )
    fun snapshot(git: Git): Snapshot {
        val repository = git.repository
        repository.refDatabase.refresh()
        val marker = File(repository.workTree, ".git").toPath()
        val attributes = Files.readAttributes(marker, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val markerIdentity = attributes.fileKey()?.toString() ?: error("Could not verify Git metadata identity")
        val markerContents = when {
            attributes.isDirectory -> "directory"
            attributes.isSymbolicLink -> "link:${Files.readSymbolicLink(marker)}"
            else -> digest(marker.toFile()).orEmpty()
        }
        return Snapshot(repository.fullBranch, repository.resolve("HEAD")?.name,
            directoryIdentity(repository.workTree), directoryIdentity(repository.directory), "$markerIdentity:$markerContents",
            digest(File(repository.directory, "config")), digest(File(repository.directory, "index")))
    }
    fun verify(git: Git, expected: Snapshot) {
        check(snapshot(git) == expected) { "Branch, commit, index, folder or configuration changed. Refresh and review again." }
    }
    private fun directoryIdentity(directory: File): String {
        val path = directory.toPath().toRealPath()
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        check(attributes.isDirectory) { "Repository folder changed. Refresh and review again." }
        return "$path:${attributes.fileKey() ?: error("Could not verify repository folder identity") }"
    }
    private fun digest(file: File): String? {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }
    fun requireClean(git: Git, action: String) {
        check(git.repository.repositoryState == RepositoryState.SAFE) { "Finish or abort the current Git operation before changing branches." }
        if (!git.status().call().isClean) throw DirtyRepoException("Cannot $action with uncommitted changes")
    }
    fun protectTree(git: Git, target: ObjectId) {
        val status = git.status().call()
        if (status.untracked.isEmpty() && status.ignoredNotInIndex.isEmpty()) return
        val paths = mutableSetOf<String>()
        RevWalk(git.repository).use { walk ->
            TreeWalk(git.repository).use { tree ->
                tree.addTree(walk.parseCommit(target).tree); tree.isRecursive = true
                while (tree.next()) paths += tree.pathString
            }
        }
        protectPaths(git, paths)
    }
    fun protectMerge(git: Git, target: ObjectId, fastForwardOnly: Boolean = false) {
        val status = git.status().call()
        if (status.untracked.isEmpty() && status.ignoredNotInIndex.isEmpty()) return
        val head = requireNotNull(git.repository.resolve("HEAD")) { "Commit a file before merging branches." }
        RevWalk(git.repository).use { walk ->
            val ours = walk.parseCommit(head); val theirs = walk.parseCommit(target)
            if (walk.isMergedInto(theirs, ours)) return // No checkout for an up-to-date merge.
            walk.reset()
            if (walk.isMergedInto(ours, theirs)) { protectTree(git, target); return }
            if (fastForwardOnly) return // The merge command will reject divergence without checkout.
        }
        // The incoming tree can contain paths deleted by ours that the merge will leave deleted.
        // Use the actual recursive merge's prospective index, including unresolved stages, instead.
        val index = DirCache.newInCore()
        val merger = RenameAwareMergeStrategy.newMerger(git.repository, true) as ResolveMerger
        merger.setDirCache(index)
        val merged = merger.merge(head, target)
        check(merged || merger.unmergedPaths.isNotEmpty()) { "Could not verify the merge checkout. All working files were kept." }
        val paths = (0 until index.entryCount).mapTo(mutableSetOf()) { index.getEntry(it).pathString }
        paths += merger.toBeCheckedOut.keys
        protectPaths(git, paths)
    }
    fun protectIgnoredPaths(git: Git, paths: Collection<String>) = protectPaths(git, paths, ignoredOnly = true)
    private fun protectPaths(git: Git, paths: Collection<String>, ignoredOnly: Boolean = false) {
        val writes = TreeSet(paths)
        val status = git.status().call()
        fun protect(path: String) {
            val pieces = path.split('/')
            for (last in pieces.indices) if (writes.contains(pieces.take(last + 1).joinToString("/"))) collision(path)
            val prefix = "$path/"
            if (writes.ceiling(prefix)?.startsWith(prefix) != true) return
            val file = File(git.repository.workTree, path)
            if (!Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) collision(path)
            val children = file.listFiles() ?: error("Could not verify untracked files; all work was kept")
            children.forEach { protect("$path/${it.name}") }
        }
        (if (ignoredOnly) status.ignoredNotInIndex else status.untracked + status.ignoredNotInIndex).forEach { protect(it.trimEnd('/')) }
    }
    private fun collision(path: String): Nothing = throw DirtyRepoException(
        "Untracked or ignored files would be overwritten. Move them to a safe location before updating this branch.\n\nPath: $path"
    )

}

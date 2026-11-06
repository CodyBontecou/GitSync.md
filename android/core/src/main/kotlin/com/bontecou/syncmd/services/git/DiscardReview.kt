package com.bontecou.syncmd.services.git

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCacheCheckout
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections

/** Immutable, in-memory authority for exactly one reviewed repository state. */
class DiscardReview internal constructor(
    internal val repositoryPath: String,
    val filePath: String?,
    paths: List<String>,
    val keepsWorkingFiles: Boolean,
    val abandonsOperation: Boolean,
    internal val identity: CheckoutProtection.Snapshot,
    internal val revision: String,
) {
    val paths: List<String> = Collections.unmodifiableList(ArrayList(paths))
}

/** Reviews and executes discard under the shared lease; never takes fresh, unreviewed deletion scope. */
internal object ReviewedDiscard {
    private val operationFiles = listOf("MERGE_HEAD", "MERGE_MSG", "MERGE_MODE", "CHERRY_PICK_HEAD", "REVERT_HEAD", "SQUASH_MSG", "ORIG_HEAD", "info/attributes", "info/exclude")
    private val allowedStates = setOf(RepositoryState.SAFE, RepositoryState.MERGING, RepositoryState.MERGING_RESOLVED,
        RepositoryState.CHERRY_PICKING, RepositoryState.CHERRY_PICKING_RESOLVED, RepositoryState.REVERTING, RepositoryState.REVERTING_RESOLVED)

    suspend fun review(repoPath: String, filePath: String?): DiscardReview {
        val context = currentCoroutineContext()
        val root = File(repoPath).canonicalFile
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(root).use { git ->
            check(git.repository.workTree.canonicalFile == root) { "Open the original repository folder before discarding changes." }
            check(git.repository.repositoryState in allowedStates) { "Finish or abort the active Git operation before discarding changes." }
            if (filePath != null) validatePath(root, filePath)
            val identity = CheckoutProtection.snapshot(git)
            val unborn = filePath == null && identity.head == null
            val paths = paths(git, filePath, unborn)
            val revision = revision(git, filePath) { context.ensureActive() }
            CheckoutProtection.verify(git, identity)
            check(paths == paths(git, filePath, unborn)) { "Local changes changed. Review the discard again." }
            preflight(git, filePath, paths, unborn)
            return DiscardReview(root.path, filePath, paths, unborn,
                filePath == null && git.repository.repositoryState != RepositoryState.SAFE, identity, revision)
        }
    }

    suspend fun execute(repoPath: String, review: DiscardReview) {
        val context = currentCoroutineContext()
        val root = File(repoPath).canonicalFile
        check(root.path == review.repositoryPath) { "The selected repository changed. Review the discard again." }
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(root).use { git ->
            // Acquire Git's real index lock before either validation or a worktree write.
            // A foreign lock is never deleted, and a failed/stale review never changes the index.
            val guard = git.repository.lockDirCache()
            val preparedIndex = File(git.repository.directory, "gitsync-discard-${java.util.UUID.randomUUID()}.index")
            var ownsPreparedIndex = false
            try {
                CheckoutProtection.verify(git, review.identity)
                check(revision(git, review.filePath) { context.ensureActive() } == review.revision &&
                    paths(git, review.filePath, review.keepsWorkingFiles) == review.paths) {
                    "Local files or Git operation changed. All changes were kept; review the discard again."
                }
                preflight(git, review.filePath, review.paths, review.keepsWorkingFiles)
                val realIndex = File(git.repository.directory, "index")
                if (Files.exists(realIndex.toPath(), NOFOLLOW_LINKS)) {
                    Files.copy(realIndex.toPath(), preparedIndex.toPath())
                    ownsPreparedIndex = true
                }
                val cache = DirCache.lock(preparedIndex, git.repository.fs)
                ownsPreparedIndex = true
                try {
                    CheckoutProtection.verify(git, review.identity)
                    context.ensureActive()
                    if (review.keepsWorkingFiles) {
                        cache.builder().finish()
                        cache.write()
                        check(cache.commit()) { "Staging could not be cleared. Review the repository before retrying." }
                    } else if (review.filePath == null) {
                        val tree = requireNotNull(git.repository.resolve("HEAD^{tree}"))
                        // Hard reset restores tracked files but leaves ordinary untracked files.
                        // iOS Revert All also removes those explicitly listed in the review.
                        // checkout() may release its private index lock; the real lock stays held.
                        val headPaths = headEntries(git).keys
                        review.paths.filterNot { it in headPaths }.sortedByDescending { it.count { c -> c == '/' } }.forEach { path ->
                            val target = validatePath(root, path)
                            if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
                                Files.delete(target.toPath())
                            }
                        }
                        val checkout = DirCacheCheckout(git.repository, cache, tree)
                        checkout.setForce(true)
                        checkout.setFailOnConflict(false)
                        check(checkout.checkout()) { "Some reviewed files could not be restored. Review the repository before retrying." }
                    } else {
                        val path = review.filePath
                        val target = validatePath(root, path)
                        val replacement = headEntries(git)[path]
                        val retained = (0 until cache.entryCount).map(cache::getEntry).filterNot { it.pathString == path }
                        val builder = cache.builder()
                        ConflictIndex.ordered(retained + listOfNotNull(replacement)).forEach(builder::add)
                        builder.finish()
                        // Serialize before changing the file. checkoutEntry deliberately cannot recursively delete a folder.
                        cache.write()
                        if (replacement == null) Files.deleteIfExists(target.toPath())
                        else git.repository.newObjectReader().use { reader ->
                            TreeWalk(git.repository).use { walk ->
                                walk.addTree(requireNotNull(git.repository.resolve("HEAD^{tree}")))
                                walk.addTree(DirCacheIterator(cache)); walk.addTree(FileTreeIterator(git.repository))
                                walk.isRecursive = true
                                walk.setOperationType(TreeWalk.OperationType.CHECKOUT_OP)
                                walk.filter = org.eclipse.jgit.treewalk.filter.PathFilter.create(path)
                                check(walk.next() && walk.pathString == path) { "The reviewed file is no longer in HEAD." }
                                val metadata = DirCacheCheckout.CheckoutMetadata(walk.getEolStreamType(TreeWalk.OperationType.CHECKOUT_OP), walk.getFilterCommand("smudge"))
                                DirCacheCheckout.checkoutEntry(git.repository, replacement, reader, false, metadata)
                            }
                        }
                        check(cache.commit()) { "The file was restored, but staging failed. Review the repository before retrying." }
                    }
                    // Publish the prepared index without renaming/removing index.lock. Git clients
                    // cannot acquire it between index publication and operation-metadata cleanup.
                    java.io.FileOutputStream(preparedIndex, true).use { it.fd.sync() }
                    Files.move(preparedIndex.toPath(), realIndex.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    if (review.abandonsOperation) {
                        git.repository.writeMergeHeads(null); git.repository.writeMergeCommitMsg(null)
                        git.repository.writeCherryPickHead(null); git.repository.writeRevertHead(null)
                        git.repository.writeSquashCommitMsg(null)
                        Files.deleteIfExists(File(git.repository.directory, "MERGE_MODE").toPath())
                    }
                } finally { cache.unlock() }
            } finally {
                try { if (ownsPreparedIndex) Files.deleteIfExists(preparedIndex.toPath()) }
                finally { guard.unlock() }
            }
        }
    }

    private fun paths(git: Git, selected: String?, unborn: Boolean): List<String> {
        if (selected != null) return listOf(selected)
        if (unborn) {
            val index = git.repository.readDirCache()
            return (0 until index.entryCount).map { index.getEntry(it).pathString }.distinct().sorted()
        }
        val s = git.status().call()
        return (s.added + s.changed + s.removed + s.modified + s.missing + s.conflicting + s.untracked).sorted()
    }

    private fun headEntries(git: Git): Map<String, DirCacheEntry> {
        val tree = git.repository.resolve("HEAD^{tree}") ?: return emptyMap()
        return TreeWalk(git.repository).use { walk ->
            walk.addTree(tree); walk.isRecursive = true
            buildMap { while (walk.next()) put(walk.pathString, DirCacheEntry(walk.pathString).apply {
                fileMode = walk.getFileMode(0); setObjectId(walk.getObjectId(0))
            }) }
        }
    }

    private fun preflight(git: Git, selected: String?, paths: List<String>, unborn: Boolean) {
        check(git.repository.repositoryState in allowedStates) { "Finish or abort the active Git operation before discarding changes." }
        if (unborn) return
        val head = headEntries(git)
        val writes = if (selected == null) head.keys else setOf(selected)
        check(head.filterKeys { it in writes }.values.none { it.fileMode == FileMode.GITLINK }) { "Discard submodule changes with a Git client that supports submodules." }
        paths.forEach { validatePath(git.repository.workTree, it) }
        if (selected != null) check(!Files.isDirectory(File(git.repository.workTree, selected).toPath(), NOFOLLOW_LINKS)) { "Choose a file, not a folder, to discard." }
        else CheckoutProtection.protectIgnoredPaths(git, writes + paths)
        // Resolve object headers before changing files. Body I/O can still fail during checkout.
        git.repository.newObjectReader().use { reader -> head.filterKeys { it in writes }.values.forEach { reader.open(it.objectId).size } }
    }

    private fun validatePath(root: File, path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute && path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.equals(".git", true) }) { "Choose a file inside the working tree." }
        val target = File(root, path)
        require(target.parentFile.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "The file's parent leaves the working tree." }
        var parent = target.parentFile
        while (parent != root && parent != null) {
            check(!Files.isSymbolicLink(parent.toPath())) { "A file's parent is a symbolic link. Review it before discarding." }
            parent = parent.parentFile
        }
        return target
    }

    /** Streaming content/identity revision. Does not follow symlinks or hash Git's object database. */
    private fun revision(git: Git, selected: String?, checkActive: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
        fun file(path: Path, attributes: BasicFileAttributes) {
            checkActive()
            add(attributes.fileKey()?.toString().orEmpty()); add(attributes.size().toString()); add(attributes.lastModifiedTime().toString())
            add(path.toFile().canExecute().toString())
            when {
                attributes.isSymbolicLink -> add("link:${Files.readSymbolicLink(path)}")
                attributes.isRegularFile -> Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) { checkActive(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                else -> error("A special file needs manual review before discarding changes.")
            }
            val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            check(after.fileKey() == attributes.fileKey() && after.size() == attributes.size() &&
                after.lastModifiedTime() == attributes.lastModifiedTime() && after.isSymbolicLink == attributes.isSymbolicLink) { "A local file changed during review. Try again." }
            digest.update(0.toByte())
        }
        val root = git.repository.workTree.toPath()
        val entries = linkedSetOf<Path>()
        if (selected != null) {
            val target = root.resolve(selected)
            if (Files.exists(target, NOFOLLOW_LINKS)) entries.add(target) else add("missing-file:$selected")
            var folder: Path? = target.parent
            while (folder != null && folder.startsWith(root)) {
                checkActive()
                if (Files.exists(folder, NOFOLLOW_LINKS)) entries.add(folder) else add("missing-parent:${root.relativize(folder)}")
                val attributes = folder.resolve(".gitattributes")
                if (Files.exists(attributes, NOFOLLOW_LINKS)) entries.add(attributes) else add("missing-attributes:${root.relativize(attributes)}")
                if (folder == root) break
                folder = folder.parent
            }
        } else Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkActive()
                if (dir == root.resolve(".git")) return FileVisitResult.SKIP_SUBTREE
                check(dir == root || dir.fileName.toString() != ".git") { "A nested Git repository needs manual review before discarding changes." }
                entries.add(dir)
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (path != root.resolve(".git")) {
                    check(path.fileName.toString() != ".git") { "A nested Git repository needs manual review before discarding changes." }
                    entries.add(path)
                }
                return FileVisitResult.CONTINUE
            }
        })
        entries.sortedBy { root.relativize(it).toString() }.forEach { path ->
            checkActive(); add(root.relativize(path).toString())
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (attrs.isDirectory) { add("directory"); add(attrs.fileKey()?.toString().orEmpty()) } else file(path, attrs)
        }
        add(git.repository.repositoryState.name)
        operationFiles.forEach { name ->
            add(name); val path = File(git.repository.directory, name).toPath()
            if (Files.exists(path, NOFOLLOW_LINKS)) file(path, Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)) else add("absent")
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

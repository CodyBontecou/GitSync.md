package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.ConflictSide
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

/** The index has paths and stages, not rename relationships. Group only graph-verified pairs. */
internal object ConflictIndex {
    fun read(repository: Repository): List<Conflict> {
        val cache = repository.readDirCache()
        val unmerged = (0 until cache.entryCount).map(cache::getEntry).filter { it.stage > 0 }
        val groups = mutableListOf<List<DirCacheEntry>>()
        val used = mutableSetOf<Pair<String, Int>>()
        if (unmerged.any { base -> base.stage == 1 &&
                unmerged.none { it.pathString == base.pathString && it.stage == 2 } }) RevWalk(repository).use { walk ->
            val trees = groupingTrees(repository,walk)
            if (trees != null) {
                val (base,ours,theirs) = trees
                fun renames(tree: org.eclipse.jgit.revwalk.RevTree): Map<String, String> =
                    DiffFormatter(DisabledOutputStream.INSTANCE).use { diff ->
                        diff.setRepository(repository); diff.isDetectRenames = true
                        diff.renameDetector.renameLimit = 200
                        diff.scan(base, tree).filter { it.changeType == DiffEntry.ChangeType.RENAME }
                            .associate { it.oldPath to it.newPath }
                    }
                val oursNames = renames(ours); val theirsNames = renames(theirs)
                fun matches(entry: DirCacheEntry, tree: org.eclipse.jgit.revwalk.RevTree): Boolean =
                    TreeWalk.forPath(repository, entry.pathString, tree)?.use {
                        it.getObjectId(0) == entry.objectId && it.getFileMode(0) == entry.fileMode
                    } == true
                unmerged.filter { it.stage == 1 }.forEach { ancestor ->
                    if (unmerged.count { it.stage == 1 && it.objectId == ancestor.objectId } != 1) return@forEach
                    val a = oursNames[ancestor.pathString] ?: ancestor.pathString
                    val b = theirsNames[ancestor.pathString] ?: ancestor.pathString
                    if (a == b) return@forEach
                    val current = unmerged.singleOrNull { it.pathString == a && it.stage == 2 } ?: return@forEach
                    val next = unmerged.singleOrNull { it.pathString == b && it.stage == 3 } ?: return@forEach
                    val candidate = listOf(ancestor, current, next)
                    val paths = candidate.map { it.pathString }.toSet()
                    if (candidate.any { (it.pathString to it.stage) in used } ||
                        unmerged.filter { it.pathString in paths }.size != candidate.size ||
                        !matches(ancestor, base) || !matches(current, ours) || !matches(next, theirs)) return@forEach
                    groups += candidate; used += candidate.map { it.pathString to it.stage }
                }
            }
        }
        groups += unmerged.filterNot { (it.pathString to it.stage) in used }.groupBy { it.pathString }.values
        return repository.newObjectReader().use { reader ->
            groups.map { entries ->
                var unsupported = false
                fun side(stage: Int) = entries.singleOrNull { it.stage == stage }?.let {
                    val bytes = if (it.fileMode.objectType == org.eclipse.jgit.lib.Constants.OBJ_BLOB)
                        reader.open(it.objectId, org.eclipse.jgit.lib.Constants.OBJ_BLOB).size else null
                    ConflictSide(it.pathString, it.objectId.name, it.rawMode, bytes)
                }
                fun content(stage: Int): String? {
                    val entry = entries.singleOrNull { it.stage == stage } ?: return null
                    if (entry.fileMode != org.eclipse.jgit.lib.FileMode.REGULAR_FILE && entry.fileMode != org.eclipse.jgit.lib.FileMode.EXECUTABLE_FILE) {
                        unsupported = true; return null
                    }
                    val loader = reader.open(entry.objectId)
                    if (loader.size > 2 * 1024 * 1024) { unsupported = true; return null }
                    val bytes = loader.bytes
                    if (com.bontecou.syncmd.services.git.lfs.LfsPointer.parse(bytes) != null || org.eclipse.jgit.diff.RawText.isBinary(bytes)) {
                        unsupported = true; return null
                    }
                    return try { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    } catch (_: java.nio.charset.CharacterCodingException) { unsupported = true; null }
                }
                val base = content(1); val current = content(2); val incomingContent = content(3)
                val ancestor = side(1); val ours = side(2); val theirs = side(3)
                val paths = entries.map { it.pathString }.distinct()
                Conflict(ancestor?.path ?: ours?.path ?: requireNotNull(theirs).path,
                    current.orEmpty(), incomingContent.orEmpty(), base, unsupported, ours != null, theirs != null,
                    ancestor, ours, theirs, paths.associateWith { runCatching { revision(repository.workTree, it) }.getOrDefault("unavailable") })
            }.sortedBy { it.filePath }
        }
    }

    private data class GroupingTrees(val base: RevTree, val ours: RevTree, val theirs: RevTree)

    /** Replay stages use the picked commit's parent, not the current graph merge base. */
    private fun groupingTrees(repository: Repository, walk: RevWalk): GroupingTrees? {
        val head = repository.resolve("HEAD") ?: return null
        val ours = walk.parseCommit(head)
        val mergeHeads = repository.readMergeHeads()
        if (mergeHeads != null) {
            val theirs = walk.parseCommit(mergeHeads.singleOrNull() ?: return null)
            walk.revFilter = RevFilter.MERGE_BASE; walk.markStart(ours); walk.markStart(theirs)
            val base = walk.next() ?: return null
            val effective = if(walk.next()==null)base.tree else
                RecursiveMergeBase.tree(repository,ours,theirs)?.let(walk::parseTree) ?: return null
            return GroupingTrees(effective,ours.tree,theirs.tree)
        }
        val picked = if (repository.repositoryState.name.startsWith("REBASING")) {
            val folder = File(repository.directory,"rebase-merge")
            val stopped = File(folder,"stopped-sha")
            if (!Files.isDirectory(folder.toPath(),NOFOLLOW_LINKS) || Files.isSymbolicLink(folder.toPath()) ||
                !Files.isRegularFile(stopped.toPath(),NOFOLLOW_LINKS) || Files.isSymbolicLink(stopped.toPath()) || stopped.length() > 41) return null
            val text = Files.newInputStream(stopped.toPath(),NOFOLLOW_LINKS).use { input ->
                val bytes = ByteArray(42); var size = 0
                while (size < bytes.size) {
                    val count = input.read(bytes,size,bytes.size-size)
                    if (count < 0) break
                    size += count
                }
                if (size > 41) return null
                String(bytes,0,size,Charsets.US_ASCII).trim()
            }
            if (!text.matches(Regex("[0-9a-fA-F]{7,40}"))) return null
            repository.newObjectReader().use { reader ->
                reader.resolve(AbbreviatedObjectId.fromString(text)).singleOrNull()
            }
        } else repository.readCherryPickHead()
        val theirs = picked?.let { walk.parseCommit(it) } ?: return null
        // A paused external mainline pick does not identify which parent was used.
        if (theirs.parentCount != 1) return null
        val base = walk.parseCommit(theirs.getParent(0))
        return GroupingTrees(base.tree,ours.tree,theirs.tree)
    }

    fun ordered(entries: List<DirCacheEntry>): List<DirCacheEntry> = entries.sortedWith { a, b ->
        val left = a.rawPath; val right = b.rawPath
        var result = 0
        for (i in 0 until minOf(left.size, right.size)) { result = (left[i].toInt() and 255) - (right[i].toInt() and 255); if (result != 0) break }
        if (result != 0) result else if (left.size != right.size) left.size - right.size else a.stage - b.stage
    }

    /** Safe validation never follows a symlink, including any parent component. */
    fun target(root: File, path: String): File {
        require(path.isNotEmpty() && !File(path).isAbsolute && path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.equals(".git", true) } && !path.contains('\\')) {
            "Conflict path is outside the working tree"
        }
        var file = root.canonicalFile
        path.split('/').forEach { part ->
            file = File(file, part)
            check(!Files.isSymbolicLink(file.toPath())) { "A conflict path is a symbolic link. Resolve it with a Git tool that supports links." }
        }
        check(!Files.exists(file.toPath(), NOFOLLOW_LINKS) || Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "A conflict path is no longer a regular file" }
        return file
    }
    fun revision(root: File, path: String): String? {
        val file = target(root, path)
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val buffer = ByteArray(8192); while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

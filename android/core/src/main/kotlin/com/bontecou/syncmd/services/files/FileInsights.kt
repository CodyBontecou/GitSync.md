package com.bontecou.syncmd.services.files

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.lfs.GitLfs
import com.bontecou.syncmd.services.git.lfs.LfsPointer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.LfsFactory
import java.io.File
import java.nio.file.Files

/** What the file browser shows beside a name: age, folder size, LFS tracking and un-fetched pointers. */
data class FileEntryInsight(
    val path: String,
    val lastModified: Long,
    /** Visible children of a folder; null for files. */
    val childCount: Int? = null,
    /** The path matches a `filter=lfs` rule in .gitattributes. */
    val lfsTracked: Boolean = false,
    /** The working copy still holds the LFS pointer, so the content lives only on the remote. */
    val lfsPointer: LfsPointer? = null,
)

/** The vault's top-bar identity: the checked-out branch and the origin's owner/name. */
data class VaultHeader(val branch: String?, val remoteSlug: String?)

/** One commit in a file's history. */
data class FileCommit(val hash: String, val shortHash: String, val message: String, val author: String, val time: Long)

/** How this phone compares with the remote: uncommitted files and commits not yet pushed. */
data class VaultLocalState(val changedFiles: Int, val ahead: Int?) {
    /** Matches the vault hero: commits ahead when there are any, otherwise uncommitted files. */
    val toPush: Int get() = if ((ahead ?: 0) > 0) ahead!! else changedFiles
}

enum class WorkingLineKind { CONTEXT, ADDED, REMOVED, HUNK }

/**
 * One displayed diff row. Numbers are 1-based. A [WorkingLineKind.HUNK] row carries the nearest
 * markdown heading above the hunk in [text] (may be empty) and the hunk's first new line in [newNumber].
 */
data class WorkingLine(val kind: WorkingLineKind, val oldNumber: Int?, val newNumber: Int?, val text: String)

/** Which side the comparison ends at: the working copy (default) or the staged index. */
enum class DiffTarget { WORKTREE, INDEX }

/** A file compared with its last commit. */
data class WorkingFileDiff(
    val path: String,
    /** Short hash of HEAD, or null on an unborn branch. */
    val headShortHash: String?,
    val added: Int,
    val removed: Int,
    /** Changed hunks with three lines of context. */
    val changes: List<WorkingLine>,
    /** Every line of the file, with the changes in place. */
    val whole: List<WorkingLine>,
    /** New-side line numbers that were added or rewritten. */
    val changedLines: Set<Int>,
    /** The new side as text, for previews; null when deleted or not text. */
    val newText: String?,
    val binary: Boolean = false,
    val deleted: Boolean = false,
) {
    /** "N lines changed": a rewritten line counts once. */
    val linesChanged: Int get() = maxOf(added, removed)
}

/** Read-only Git facts for the file screens. Every repository read shares Git's path lease. */
class FileInsights {
    companion object {
        private const val MAX_DIFF_BYTES = RepositoryFiles.MAX_EDITOR_BYTES
        private const val CONTEXT = 3

        /** Compares two texts line by line (histogram diff, as Git does). Pure; safe on any thread. */
        fun compare(path: String, old: String?, new: String?, headShortHash: String? = null): WorkingFileDiff {
            val a = RawText((old ?: "").toByteArray(Charsets.UTF_8))
            val b = RawText((new ?: "").toByteArray(Charsets.UTF_8))
            return build(path, a, b, headShortHash, new, deleted = new == null)
        }

        private fun build(path: String, a: RawText, b: RawText, head: String?, newText: String?, deleted: Boolean): WorkingFileDiff {
            val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM).diff(RawTextComparator.DEFAULT, a, b)
            val whole = mutableListOf<WorkingLine>()
            val changed = mutableSetOf<Int>()
            var added = 0
            var removed = 0
            var oldIndex = 0
            var newIndex = 0
            fun context(until: Int) {
                while (newIndex < until) {
                    whole += WorkingLine(WorkingLineKind.CONTEXT, oldIndex + 1, newIndex + 1, b.getString(newIndex))
                    oldIndex++; newIndex++
                }
            }
            for (edit in edits) {
                context(edit.beginB)
                for (i in edit.beginA until edit.endA) { whole += WorkingLine(WorkingLineKind.REMOVED, i + 1, null, a.getString(i)); removed++ }
                for (i in edit.beginB until edit.endB) { whole += WorkingLine(WorkingLineKind.ADDED, null, i + 1, b.getString(i)); added++; changed += i + 1 }
                oldIndex = edit.endA; newIndex = edit.endB
            }
            context(b.size())
            return WorkingFileDiff(path, head, added, removed, hunks(edits, a, b), whole, changed, newText, deleted = deleted)
        }

        private fun hunks(edits: List<Edit>, a: RawText, b: RawText): List<WorkingLine> {
            if (edits.isEmpty()) return emptyList()
            // Group edits whose context windows touch, like `git diff -U3`.
            val groups = mutableListOf<MutableList<Edit>>()
            for (edit in edits) {
                val last = groups.lastOrNull()?.last()
                if (last != null && edit.beginB - last.endB <= CONTEXT * 2) groups.last() += edit else groups += mutableListOf(edit)
            }
            val headings = (0 until b.size()).map { b.getString(it) }
            val rows = mutableListOf<WorkingLine>()
            for (group in groups) {
                val startB = (group.first().beginB - CONTEXT).coerceAtLeast(0)
                val startA = group.first().beginA - (group.first().beginB - startB)
                val heading = ((group.first().beginB - 1).coerceAtLeast(0) downTo 0).firstOrNull { it < headings.size && headings[it].trimStart().startsWith("#") }
                    ?.let { headings[it].trim() }.orEmpty()
                rows += WorkingLine(WorkingLineKind.HUNK, startA + 1, startB + 1, heading)
                var oldIndex = startA
                var newIndex = startB
                for (edit in group) {
                    while (newIndex < edit.beginB) { rows += WorkingLine(WorkingLineKind.CONTEXT, oldIndex + 1, newIndex + 1, b.getString(newIndex)); oldIndex++; newIndex++ }
                    for (i in edit.beginA until edit.endA) rows += WorkingLine(WorkingLineKind.REMOVED, i + 1, null, a.getString(i))
                    for (i in edit.beginB until edit.endB) rows += WorkingLine(WorkingLineKind.ADDED, null, i + 1, b.getString(i))
                    oldIndex = edit.endA; newIndex = edit.endB
                }
                val end = (group.last().endB + CONTEXT).coerceAtMost(b.size())
                while (newIndex < end) { rows += WorkingLine(WorkingLineKind.CONTEXT, oldIndex + 1, newIndex + 1, b.getString(newIndex)); oldIndex++; newIndex++ }
            }
            return rows
        }

        private fun headBytes(repository: Repository, path: String): ByteArray? {
            val head = repository.resolve("HEAD^{tree}") ?: return null
            TreeWalk.forPath(repository, path, head)?.use { walk ->
                val loader = repository.open(walk.getObjectId(0), Constants.OBJ_BLOB)
                if (loader.size > MAX_DIFF_BYTES) throw TooLarge()
                return loader.getBytes(MAX_DIFF_BYTES)
            }
            return null
        }

        private class TooLarge : RuntimeException()

        private fun shortHead(repository: Repository): String? = repository.resolve("HEAD")?.let { repository.newObjectReader().use { reader -> reader.abbreviate(it, 7).name() } }

        private fun decode(bytes: ByteArray): String? = runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
    }

    /** The file as of the last commit, as text; null when it is new, binary or too large. */
    suspend fun headText(rootPath: String, path: String): HeadText = io(rootPath) {
        GitLfs.open(File(rootPath)).use { git ->
            val bytes = try { headBytes(git.repository, path) } catch (_: TooLarge) { return@use HeadText(shortHead(git.repository), null, tracked = true, text = false) }
            val text = bytes?.takeIf { !RawText.isBinary(it) && LfsPointer.parse(it) == null }?.let(::decode)
            HeadText(shortHead(git.repository), text, tracked = bytes != null, text = bytes == null || text != null)
        }
    }

    /** HEAD → working copy (or index) for one file. */
    suspend fun diff(rootPath: String, path: String, target: DiffTarget = DiffTarget.WORKTREE): WorkingFileDiff = io(rootPath) {
        GitLfs.open(File(rootPath)).use { git ->
            val repository = git.repository
            val head = shortHead(repository)
            val binary = WorkingFileDiff(path, head, 0, 0, emptyList(), emptyList(), emptySet(), null, binary = true)
            val old = try { headBytes(repository, path) } catch (_: TooLarge) { return@use binary }
            val new: ByteArray? = when (target) {
                DiffTarget.INDEX -> repository.readDirCache().getEntry(path)?.let { entry ->
                    val loader = repository.open(entry.objectId, Constants.OBJ_BLOB)
                    if (loader.size > MAX_DIFF_BYTES) return@use binary
                    loader.getBytes(MAX_DIFF_BYTES)
                }
                DiffTarget.WORKTREE -> {
                    val file = File(repository.workTree, path)
                    if (!file.isFile || Files.isSymbolicLink(file.toPath())) null
                    else if (file.length() > MAX_DIFF_BYTES) return@use binary
                    else file.readBytes()
                }
            }
            val isBinary = listOfNotNull(old, new).any { RawText.isBinary(it) || LfsPointer.parse(it) != null }
            if (isBinary) return@use binary.copy(deleted = new == null)
            build(path, RawText(old ?: ByteArray(0)), RawText(new ?: ByteArray(0)), head, new?.let(::decode), deleted = new == null)
        }
    }

    /** Ages, folder sizes and LFS state for the entries of one folder. */
    suspend fun entries(rootPath: String, entries: List<RepositoryFile>, showHidden: Boolean): Map<String, FileEntryInsight> = io(rootPath) {
        val root = File(rootPath).canonicalFile
        val lfsRules = runCatching { GitLfs.open(root) }.getOrNull()
        try {
            entries.associate { entry ->
                val file = File(root, entry.path)
                val children = if (entry.isDirectory) file.listFiles()?.count { child ->
                    !child.name.equals(".git", ignoreCase = true) && (showHidden || !child.name.startsWith(".")) && !Files.isSymbolicLink(child.toPath())
                } else null
                val tracked = !entry.isDirectory && lfsRules != null &&
                    runCatching { LfsFactory.getAttributesForPath(lfsRules.repository, entry.path).getValue("filter") == "lfs" }.getOrDefault(false)
                val pointer = if (!entry.isDirectory && file.isFile && file.length() <= LfsPointer.MAX_BYTES)
                    runCatching { LfsPointer.parse(file.readBytes()) }.getOrNull() else null
                entry.path to FileEntryInsight(entry.path, file.lastModified(), children, tracked || pointer != null, pointer)
            }
        } finally { lfsRules?.close() }
    }

    /** Branch and owner/name for the vault top bar. */
    suspend fun header(rootPath: String): VaultHeader = io(rootPath) {
        runCatching {
            GitLfs.open(File(rootPath)).use { git ->
                val repository = git.repository
                val url = repository.config.getString("remote", "origin", "url")
                VaultHeader(repository.branch, url?.let(::remoteSlug))
            }
        }.getOrDefault(VaultHeader(null, null))
    }

    /** Uncommitted files and commits ahead of the upstream ("Vault ↑N"). */
    suspend fun localState(rootPath: String): VaultLocalState = io(rootPath) {
        GitLfs.open(File(rootPath)).use { git ->
            val status = git.status().call()
            val changed = (status.untracked + status.added + status.changed + status.removed + status.missing + status.modified + status.conflicting).size
            val ahead = runCatching { git.repository.fullBranch?.let { BranchTrackingStatus.of(git.repository, it) }?.aheadCount }.getOrNull()
            VaultLocalState(changed, ahead)
        }
    }

    /** Commits that touched [path] (a file or folder), newest first. */
    suspend fun history(rootPath: String, path: String, max: Int = 200): List<FileCommit> = io(rootPath) {
        GitLfs.open(File(rootPath)).use { git ->
            if (git.repository.resolve("HEAD") == null) return@use emptyList()
            val reader = git.repository.newObjectReader()
            reader.use {
                git.log().addPath(path).setMaxCount(max).call().map { commit ->
                    FileCommit(commit.name, reader.abbreviate(commit, 7).name(), commit.shortMessage, commit.authorIdent.name,
                        commit.commitTime * 1000L)
                }
            }
        }
    }

    private suspend fun <T> io(rootPath: String, block: suspend () -> T): T =
        RepositoryOperationCoordinator.withRepository(rootPath) { withContext(Dispatchers.IO) { block() } }
}

/** The committed text of a file. [tracked] is false for a new file; [text] is false for binary or LFS content. */
data class HeadText(val headShortHash: String?, val content: String?, val tracked: Boolean, val text: Boolean)

/** "owner/name" from an https, ssh or scp-style Git URL. */
fun remoteSlug(url: String): String? {
    val trimmed = url.trim().removeSuffix("/").removeSuffix(".git")
    val path = when {
        "://" in trimmed -> trimmed.substringAfter("://").substringAfter('/', "")
        ':' in trimmed -> trimmed.substringAfter(':')
        else -> return null
    }
    val parts = path.split('/').filter { it.isNotBlank() }
    return if (parts.size >= 2) parts.takeLast(2).joinToString("/") else null
}

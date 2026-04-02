package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.DiffHunk
import com.bontecou.syncmd.data.models.DiffLine
import com.bontecou.syncmd.data.models.DiffLineType
import com.bontecou.syncmd.data.models.DiffStatus
import com.bontecou.syncmd.data.models.DiffSummary
import com.bontecou.syncmd.data.models.FileDiff
import com.bontecou.syncmd.data.models.UnifiedDiffResult
import com.bontecou.syncmd.domain.repository.DiffRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * JGit-backed implementation of [DiffRepository].
 *
 * Uses the pure-Java JGit library — no system `git` binary required.
 * ProcessBuilder("git") fails on Android with "No such file or directory";
 * JGit works everywhere.
 */
class LocalDiffRepository : DiffRepository {

    override suspend fun getDiff(repoPath: String): Result<UnifiedDiffResult> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    // Use git.status() to know what is actually staged in the index
                    val status = git.status().call()
                    val stagedPaths: Set<String> =
                        status.changed + status.added + status.removed

                    // Files that exist in the index but NOT on disk — these are files whose
                    // names contain characters illegal on this filesystem (e.g. '?' on FUSE
                    // storage). The checkout code intentionally keeps them in the index to
                    // avoid staged-deletion noise, so we must exclude them from the diff UI
                    // ourselves; they are not real local changes the user made.
                    val missingPaths: Set<String> = status.missing

                    // HEAD-vs-working-tree diff gives us hunk content for all changes
                    val diffText = captureDiff(repoPath, pathFilter = null)
                    val parsed  = parseDiffOutput(diffText)

                    // Enrich parsed files with real isStaged flag; drop missing-only files
                    val enriched = parsed
                        .filter { fd -> fd.filePath !in missingPaths }
                        .map    { fd -> fd.copy(isStaged = fd.filePath in stagedPaths) }

                    // Files staged for deletion may not appear in the working-tree diff
                    // (they're gone from disk) — add them explicitly
                    val parsedPaths = parsed.map { it.filePath }.toSet()
                    val stagedDeletions = status.removed
                        .filter { it !in parsedPaths && it !in missingPaths }
                        .map { path ->
                            FileDiff(
                                filePath = path,
                                status   = DiffStatus.DELETED,
                                hunks    = emptyList(),
                                isStaged = true,
                            )
                        }

                    buildResult(enriched + stagedDeletions)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getDiff(repoPath: String, filePath: String): Result<UnifiedDiffResult> =
        withContext(Dispatchers.IO) {
            try {
                // Reuse the full repo diff (which correctly handles deleted files, staged
                // deletions, and path filters with spaces) and pick out the matching file.
                // Calling captureDiff with PathFilter directly fails for staged deletions
                // because the file no longer exists in the working tree, so the formatter
                // finds nothing to compare against.
                val fullResult = getDiff(repoPath).getOrElse { return@withContext Result.failure(it) }
                val fileDiff   = fullResult.files.firstOrNull { it.filePath == filePath }
                    ?: return@withContext Result.success(
                        UnifiedDiffResult(emptyList(), com.bontecou.syncmd.data.models.DiffSummary(0, 0, 0))
                    )
                buildResult(listOf(fileDiff))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun stageFile(repoPath: String, filePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    if (File(repoPath, filePath).exists()) {
                        // Modified or new file — add to index
                        git.add().addFilepattern(filePath).call()
                    } else {
                        // File deleted from disk — stage the deletion
                        git.rm().addFilepattern(filePath).call()
                    }
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun unstageFile(repoPath: String, filePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    git.reset().addPath(filePath).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun commit(
        repoPath: String,
        message: String,
        authorName: String,
        authorEmail: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val status = git.status().call()
                    println("[LocalDiffRepo] commit: index state → changed=${status.changed}, added=${status.added}, removed=${status.removed}, missing=${status.missing}, modified=${status.modified}, untracked=${status.untracked}")
                    git.commit()
                        .setMessage(message)
                        .setAuthor(authorName, authorEmail)
                        .setCommitter(authorName, authorEmail)
                        // commits what is explicitly staged in the index
                        .call()
                    println("[LocalDiffRepo] commit: success")
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                println("[LocalDiffRepo] commit failed: ${e.message}")
                Result.failure(e)
            }
        }

    override suspend fun discardFileChanges(repoPath: String, filePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    // Reset index (no-op if not staged)
                    git.reset().addPath(filePath).call()
                    // Check if the file exists in HEAD
                    val headId = git.repository.resolve("HEAD^{tree}")
                    val existsInHead = if (headId != null) {
                        RevWalk(git.repository).use { rw ->
                            TreeWalk.forPath(git.repository, filePath, rw.parseTree(headId)) != null
                        }
                    } else false

                    if (existsInHead) {
                        // Restore working-tree file to HEAD version
                        git.checkout().addPath(filePath).call()
                    } else {
                        // New file not in HEAD — delete it from disk
                        File(repoPath, filePath).delete()
                    }
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun discardAllChanges(repoPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    git.reset()
                        .setMode(ResetCommand.ResetType.HARD)
                        .setRef("HEAD")
                        .call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Run a HEAD-vs-working-tree diff (equivalent to `git diff HEAD`) and return
     * the unified diff text so the existing [parseDiffOutput] parser can consume it.
     * Pass a [pathFilter] to limit to a single file.
     */
    private fun captureDiff(repoPath: String, pathFilter: String?): String {
        Git.open(File(repoPath)).use { git ->
            val out = ByteArrayOutputStream()
            DiffFormatter(out).use { formatter ->
                formatter.setRepository(git.repository)
                formatter.isDetectRenames = true

                if (pathFilter != null) {
                    formatter.setPathFilter(PathFilter.create(pathFilter))
                }

                // Build the "old" tree: HEAD^{tree}, or empty tree for a brand-new repo
                val headId = git.repository.resolve("HEAD^{tree}")
                val oldTree = if (headId != null) {
                    CanonicalTreeParser().also { parser ->
                        git.repository.newObjectReader().use { parser.reset(it, headId) }
                    }
                } else {
                    EmptyTreeIterator()
                }

                val newTree = FileTreeIterator(git.repository)
                val entries = formatter.scan(oldTree, newTree)
                formatter.format(entries)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    private fun buildResult(fileDiffs: List<FileDiff>): Result<UnifiedDiffResult> {
        var insertions = 0
        var deletions  = 0
        fileDiffs.forEach { fd ->
            fd.hunks.forEach { hunk ->
                hunk.lines.forEach { line ->
                    when (line.type) {
                        DiffLineType.ADDITION -> insertions++
                        DiffLineType.DELETION -> deletions++
                        else -> {}
                    }
                }
            }
        }
        return Result.success(
            UnifiedDiffResult(
                files   = fileDiffs,
                summary = DiffSummary(
                    filesChanged = fileDiffs.size,
                    insertions   = insertions,
                    deletions    = deletions,
                )
            )
        )
    }

    // ─── Diff text parser (unchanged from original) ───────────────────────────

    private fun parseDiffOutput(output: String): List<FileDiff> {
        if (output.isEmpty()) return emptyList()

        val fileDiffs = mutableListOf<FileDiff>()
        val lines     = output.lines()
        var i         = 0

        while (i < lines.size) {
            val line = lines[i]

            if (line.startsWith("diff --git")) {
                val parts = line.split(" ")
                if (parts.size >= 4) {
                    // Initial path guesses from the "diff --git" header — wrong for filenames
                    // with spaces (e.g. "diff --git a/My Notes.md b/My Notes.md" splits
                    // into parts[2]="a/My", parts[3]="Notes.md"). We override these below
                    // using the unambiguous "--- a/<path>" / "+++ b/<path>" lines.
                    var aPath = parts[2].removePrefix("a/")
                    var bPath = parts[3].removePrefix("b/")

                    i++
                    var oldFileMode: String? = null
                    var newFileMode: String? = null
                    var status = DiffStatus.MODIFIED

                    // Advance past index/mode/rename lines; stop at --- or next diff block
                    while (i < lines.size &&
                        !lines[i].startsWith("---") &&
                        !lines[i].startsWith("diff --git")
                    ) {
                        when {
                            lines[i].startsWith("new file mode")    -> { newFileMode = lines[i].substringAfter("mode "); status = DiffStatus.ADDED }
                            lines[i].startsWith("deleted file mode") -> { oldFileMode = lines[i].substringAfter("mode "); status = DiffStatus.DELETED }
                            lines[i].startsWith("rename from")      -> { status = DiffStatus.RENAMED }
                        }
                        i++
                    }

                    // Extract correct full paths from --- / +++ lines (handles spaces and
                    // git-quoted paths, e.g. "a/Ralph Wiggum\342\200\246.md" → path)
                    if (i < lines.size && lines[i].startsWith("---")) {
                        val raw = lines[i].removePrefix("--- ")
                        if (raw != "/dev/null") aPath = stripGitPathPrefix(raw, "a/")
                        i++
                    }
                    if (i < lines.size && lines[i].startsWith("+++")) {
                        val raw = lines[i].removePrefix("+++ ")
                        // Deleted files have "+++ /dev/null" — fall back to aPath in that case
                        bPath = if (raw != "/dev/null") stripGitPathPrefix(raw, "b/") else aPath
                        i++
                    }

                    val hunks = mutableListOf<DiffHunk>()
                    while (i < lines.size && lines[i].startsWith("@@")) {
                        val hunkInfo = parseHunkHeader(lines[i])
                        if (hunkInfo != null) {
                            val (oldStart, oldCount, newStart, newCount) = hunkInfo
                            i++
                            val diffLines = mutableListOf<DiffLine>()
                            while (i < lines.size &&
                                !lines[i].startsWith("@@") &&
                                !lines[i].startsWith("diff --git")
                            ) {
                                val dl = lines[i]
                                when {
                                    dl.startsWith("+") && !dl.startsWith("+++") ->
                                        diffLines += DiffLine(DiffLineType.ADDITION, dl.substring(1))
                                    dl.startsWith("-") && !dl.startsWith("---") ->
                                        diffLines += DiffLine(DiffLineType.DELETION, dl.substring(1))
                                    dl.startsWith(" ") || dl.isEmpty() ->
                                        diffLines += DiffLine(DiffLineType.CONTEXT, if (dl.length > 1) dl.substring(1) else "")
                                }
                                i++
                            }
                            hunks += DiffHunk(oldStart, oldCount, newStart, newCount, diffLines)
                        } else {
                            i++
                        }
                    }

                    fileDiffs += FileDiff(
                        filePath    = bPath,
                        oldPath     = if (status == DiffStatus.RENAMED) aPath else null,
                        status      = status,
                        hunks       = hunks,
                        oldFileMode = oldFileMode,
                        newFileMode = newFileMode,
                    )
                    continue
                }
            }
            i++
        }

        return fileDiffs
    }

    private fun parseHunkHeader(line: String): Quad<Int, Int, Int, Int>? {
        return try {
            val m = Regex("""@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""").find(line) ?: return null
            Quad(
                m.groupValues[1].toInt(),
                m.groupValues[2].ifEmpty { "1" }.toInt(),
                m.groupValues[3].toInt(),
                m.groupValues[4].ifEmpty { "1" }.toInt(),
            )
        } catch (_: Exception) { null }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /**
     * Strip the git path prefix (e.g. "a/" or "b/") from a path token on a ---/+++ line.
     * Git quotes paths that contain special or non-ASCII characters:
     *   unquoted: a/Clippings/file.md
     *   quoted:   "a/Clippings/Ralph Wiggum as a \"software engineer\".md"
     *   quoted:   "a/Clippings/How Did Hendrix\342\200\246.md"  (octal UTF-8 bytes)
     * We strip the outer quotes, unescape the C-string content, then strip the prefix.
     */
    private fun stripGitPathPrefix(raw: String, prefix: String): String {
        val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"")) {
            unescapeGitPath(raw.substring(1, raw.length - 1))
        } else {
            raw
        }
        return unquoted.removePrefix(prefix)
    }

    /**
     * Unescape a git C-string path (content between the outer double-quotes).
     * Git uses C-style escapes: `\"` `\\` `\n` `\t` plus `\ooo` octal for non-ASCII bytes.
     * Octal bytes are collected and decoded together as UTF-8 so multi-byte sequences
     * (e.g. `\342\200\246` = U+2026 HORIZONTAL ELLIPSIS) round-trip correctly.
     */
    private fun unescapeGitPath(s: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"'  -> { bytes.add('"'.code.toByte());  i += 2 }
                    '\\' -> { bytes.add('\\'.code.toByte()); i += 2 }
                    'n'  -> { bytes.add('\n'.code.toByte()); i += 2 }
                    't'  -> { bytes.add('\t'.code.toByte()); i += 2 }
                    'r'  -> { bytes.add('\r'.code.toByte()); i += 2 }
                    'a'  -> { bytes.add(7.toByte());           i += 2 }
                    'b'  -> { bytes.add(8.toByte());           i += 2 }
                    'f'  -> { bytes.add(12.toByte());          i += 2 }
                    'v'  -> { bytes.add(11.toByte());          i += 2 }
                    else -> {
                        // Octal escape \ooo — always exactly 3 digits in git output
                        if (i + 3 < s.length &&
                            s[i + 1].isDigit() && s[i + 2].isDigit() && s[i + 3].isDigit()
                        ) {
                            bytes.add(s.substring(i + 1, i + 4).toInt(8).toByte())
                            i += 4
                        } else {
                            bytes.add(s[i].code.toByte()); i++
                        }
                    }
                }
            } else {
                bytes.add(s[i].code.toByte())
                i++
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}

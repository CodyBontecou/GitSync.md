package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.DiffHunk
import com.bontecou.syncmd.data.models.DiffLine
import com.bontecou.syncmd.data.models.DiffLineType
import com.bontecou.syncmd.data.models.DiffStatus
import com.bontecou.syncmd.data.models.DiffSummary
import com.bontecou.syncmd.data.models.FileDiff
import com.bontecou.syncmd.data.models.UnifiedDiffResult
import com.bontecou.syncmd.domain.repository.DiffRepository
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.patch.FileHeader
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
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
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val status = git.status().call()
                    val staged = status.changed + status.added + status.removed
                    val unstaged = status.modified + status.missing + status.untracked + status.conflicting
                    buildResult(captureDiff(git, staged, true) + captureDiff(git, unstaged, false))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getDiff(repoPath: String, filePath: String): Result<UnifiedDiffResult> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                // Keep both index/worktree layers, including renamed/deleted paths.
                val fullResult = getDiff(repoPath).getOrElse { return@withRepository Result.failure(it) }
                buildResult(fullResult.files.filter { it.filePath == filePath || it.oldPath == filePath })
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun stageFile(repoPath: String, filePath: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.STAGE) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    validatePath(repoPath, filePath)
                    com.bontecou.syncmd.services.git.lfs.LfsTracking.requireReviewed(git.repository, listOf(filePath))
                    com.bontecou.syncmd.services.git.lfs.GitLfs.prepare(git.repository, listOf(filePath))
                    val oldPath = getDiff(repoPath).getOrThrow().files.firstOrNull { !it.isStaged && it.filePath == filePath }?.oldPath
                    for (path in listOfNotNull(oldPath, filePath).distinct()) {
                        validatePath(repoPath, path)
                        if (java.nio.file.Files.exists(File(repoPath, path).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                            git.add().addFilepattern(path).call()
                        } else git.add().setUpdate(true).addFilepattern(path).call()
                    }
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun stageAll(repoPath: String): Result<Unit> = RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.STAGE) {
        runCatching {
            com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                check(git.status().call().conflicting.isEmpty()) { "Resolve conflicts before staging all files" }
                val changes = git.status().call()
                com.bontecou.syncmd.services.git.lfs.LfsTracking.requireReviewed(git.repository, (changes.modified + changes.untracked + changes.added).toList())
                com.bontecou.syncmd.services.git.lfs.GitLfs.prepare(git.repository, (changes.modified + changes.untracked + changes.added).toList())
                git.add().addFilepattern(".").call()
                git.add().addFilepattern(".").setUpdate(true).call()
                Unit
            }
        }
    }

    override suspend fun unstageFile(repoPath: String, filePath: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.UNSTAGE) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    validatePath(repoPath, filePath)
                    val oldPath = getDiff(repoPath).getOrThrow().files.firstOrNull { it.isStaged && it.filePath == filePath }?.oldPath
                    for (path in listOfNotNull(oldPath, filePath).distinct()) {
                        if (git.repository.resolve("HEAD") == null) {
                            git.rm().setCached(true).addFilepattern(path).call()
                        } else git.reset().addPath(path).call()
                    }
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
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.COMMIT) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    require(message.isNotBlank()) { "Commit message cannot be empty" }
                    val status = git.status().call()
                    check(git.repository.repositoryState == org.eclipse.jgit.lib.RepositoryState.SAFE) {
                        "Finish the active Git operation before committing"
                    }
                    check(status.conflicting.isEmpty()) { "Resolve conflicts before committing" }
                    check((status.changed + status.added + status.removed).isNotEmpty()) { "Stage at least one change before committing" }
                    com.bontecou.syncmd.services.git.lfs.LfsTracking.requireReviewed(git.repository, status.changed + status.added)
                    com.bontecou.syncmd.services.git.lfs.LfsTracking.validateIndexSizes(git.repository)
                    git.commit()
                        .setMessage(message)
                        .setAuthor(authorName, authorEmail)
                        .setCommitter(authorName, authorEmail)
                        // commits what is explicitly staged in the index
                        .call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun reviewDiscard(repoPath: String, filePath: String?): Result<DiscardReview> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try { Result.success(ReviewedDiscard.review(repoPath, filePath)) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
        }

    override suspend fun discardReviewed(repoPath: String, review: DiscardReview): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.DISCARD) {
            try { ReviewedDiscard.execute(repoPath, review); Result.success(Unit) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
        }

    override suspend fun discardFileChanges(repoPath: String, filePath: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            reviewDiscard(repoPath, filePath).fold({ discardReviewed(repoPath, it) }, { Result.failure(it) })
        }

    override suspend fun discardAllChanges(repoPath: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            reviewDiscard(repoPath).fold({ discardReviewed(repoPath, it) }, { Result.failure(it) })
        }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun validatePath(repoPath: String, path: String) {
        require(path.isNotBlank() && !File(path).isAbsolute && path.split('/').none { it == "." || it == ".." || it == ".git" || it.isEmpty() }) {
            "Choose a file inside the working tree"
        }
        val root = File(repoPath).canonicalFile.toPath()
        require(File(repoPath, path).parentFile.canonicalFile.toPath().startsWith(root)) { "The file's parent leaves the working tree" }
    }

    /** Each row compares exactly the two trees that its Stage/Unstage action controls. */
    private fun captureDiff(git: Git, paths: Set<String>, staged: Boolean): List<FileDiff> {
        if (paths.isEmpty()) return emptyList()
        val out = ByteArrayOutputStream()
        DiffFormatter(out).use { formatter ->
            formatter.setRepository(git.repository)
            formatter.isDetectRenames = true
            val headId = git.repository.resolve("HEAD^{tree}")
            val head = if (headId == null) EmptyTreeIterator() else CanonicalTreeParser().also { parser ->
                git.repository.newObjectReader().use { parser.reset(it, headId) }
            }
            val entries = if (staged) formatter.scan(head, DirCacheIterator(git.repository.readDirCache()))
                else formatter.scan(DirCacheIterator(git.repository.readDirCache()), FileTreeIterator(git.repository))
            return entries.filter { it.newPath in paths || it.oldPath in paths }.map { entry ->
                out.reset()
                formatter.format(entry)
                formatter.flush()
                val header = formatter.toFileHeader(entry)
                FileDiff(
                    filePath = if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath,
                    oldPath = if (entry.changeType == DiffEntry.ChangeType.RENAME || entry.changeType == DiffEntry.ChangeType.COPY) entry.oldPath else null,
                    status = when (entry.changeType) {
                        DiffEntry.ChangeType.ADD -> DiffStatus.ADDED
                        DiffEntry.ChangeType.DELETE -> DiffStatus.DELETED
                        DiffEntry.ChangeType.RENAME -> DiffStatus.RENAMED
                        DiffEntry.ChangeType.COPY -> DiffStatus.COPIED
                        else -> if ((entry.oldMode.bits and 0xF000) != (entry.newMode.bits and 0xF000)) DiffStatus.TYPE_CHANGE else DiffStatus.MODIFIED
                    },
                    hunks = parseDiffOutput(out.toString(Charsets.UTF_8.name())).firstOrNull()?.hunks.orEmpty(),
                    oldFileMode = entry.oldMode.toString(),
                    newFileMode = entry.newMode.toString(),
                    isStaged = staged,
                    isBinary = header.patchType == FileHeader.PatchType.BINARY,
                )
            }
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
                    filesChanged = fileDiffs.map { it.filePath }.distinct().size,
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
                                    dl.startsWith(" ") ->
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

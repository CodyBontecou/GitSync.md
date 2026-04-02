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

                    // HEAD-vs-working-tree diff gives us hunk content for all changes
                    val diffText = captureDiff(repoPath, pathFilter = null)
                    val parsed  = parseDiffOutput(diffText)

                    // Enrich parsed files with real isStaged flag
                    val enriched = parsed.map { fd ->
                        fd.copy(isStaged = fd.filePath in stagedPaths)
                    }

                    // Files staged for deletion may not appear in the working-tree diff
                    // (they're gone from disk) — add them explicitly
                    val parsedPaths = parsed.map { it.filePath }.toSet()
                    val stagedDeletions = status.removed
                        .filter { it !in parsedPaths }
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
                Git.open(File(repoPath)).use { git ->
                    val status     = git.status().addPath(filePath).call()
                    val isStaged   = filePath in status.changed ||
                                     filePath in status.added   ||
                                     filePath in status.removed
                    val diffText   = captureDiff(repoPath, pathFilter = filePath)
                    val parsed     = parseDiffOutput(diffText)
                    buildResult(parsed.map { it.copy(isStaged = isStaged) })
                }
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
                    val aPath = parts[2].removePrefix("a/")
                    val bPath = parts[3].removePrefix("b/")

                    i++
                    var oldFileMode: String? = null
                    var newFileMode: String? = null
                    var status = DiffStatus.MODIFIED

                    while (i < lines.size && !lines[i].startsWith("---")) {
                        when {
                            lines[i].startsWith("new file mode")    -> { newFileMode = lines[i].substringAfter("mode "); status = DiffStatus.ADDED }
                            lines[i].startsWith("deleted file mode") -> { oldFileMode = lines[i].substringAfter("mode "); status = DiffStatus.DELETED }
                            lines[i].startsWith("rename from")      -> { status = DiffStatus.RENAMED }
                        }
                        i++
                    }

                    if (i < lines.size && lines[i].startsWith("---")) i++
                    if (i < lines.size && lines[i].startsWith("+++")) i++

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
}

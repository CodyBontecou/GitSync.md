package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.ConflictResolution
import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.data.models.MergeState
import com.bontecou.syncmd.domain.repository.ConflictRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import java.io.File

/**
 * JGit-backed implementation of [ConflictRepository].
 *
 * Uses the pure-Java JGit library — no system `git` binary required.
 * ProcessBuilder("git") fails on Android with "No such file or directory";
 * JGit works everywhere.
 */
class LocalConflictRepository : ConflictRepository {

    override suspend fun getMergeState(repoPath: String): Result<MergeState> =
        withContext(Dispatchers.IO) {
            try {
                val mergeHeadFile     = File(repoPath, ".git/MERGE_HEAD")
                val isMergeInProgress = mergeHeadFile.exists()

                val sourceBranch = if (isMergeInProgress) {
                    val mergeMsgFile = File(repoPath, ".git/MERGE_MSG")
                    if (mergeMsgFile.exists()) {
                        mergeMsgFile.readText()
                            .lineSequence()
                            .firstOrNull()
                            ?.substringAfterLast("'")
                            ?.removeSuffix("'")
                    } else "unknown"
                } else null

                val conflictsResult = getConflicts(repoPath)
                val conflicts = conflictsResult.getOrElse { emptyList() }

                // Unmerged files via JGit status
                val unmergedFiles = Git.open(File(repoPath)).use { git ->
                    git.status().call().conflicting.toList()
                }

                Result.success(
                    MergeState(
                        isMergeInProgress = isMergeInProgress,
                        sourceBranch      = sourceBranch,
                        conflicts         = conflicts,
                        autoMergedFiles   = emptyList(),
                        unmergedFiles     = unmergedFiles,
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getConflicts(repoPath: String): Result<List<Conflict>> =
        withContext(Dispatchers.IO) {
            try {
                val conflictingPaths = Git.open(File(repoPath)).use { git ->
                    git.status().call().conflicting.toList()
                }

                val conflicts = conflictingPaths.mapNotNull { path ->
                    getFileConflict(repoPath, path).getOrNull()
                        ?.takeIf { it.filePath.isNotEmpty() }
                        ?: run {
                            // Conflicting file but no parseable markers yet — include as empty conflict
                            Conflict(filePath = path, currentContent = "", incomingContent = "")
                        }
                }

                Result.success(conflicts)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getFileConflict(repoPath: String, filePath: String): Result<Conflict?> =
        withContext(Dispatchers.IO) {
            try {
                val file = File(repoPath, filePath)
                if (!file.exists()) return@withContext Result.success(null)
                val content  = file.readText()
                val conflict = parseConflictMarkersInternal(content)
                    ?.copy(filePath = filePath)
                Result.success(conflict)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun resolveConflict(
        repoPath: String,
        filePath: String,
        strategy: ConflictResolutionStrategy,
        customContent: String?,
    ): Result<ConflictResolution> = withContext(Dispatchers.IO) {
        try {
            when (strategy) {
                ConflictResolutionStrategy.OURS, ConflictResolutionStrategy.THEIRS -> {
                    val stage = if (strategy == ConflictResolutionStrategy.OURS)
                        CheckoutCommand.Stage.OURS
                    else
                        CheckoutCommand.Stage.THEIRS

                    Git.open(File(repoPath)).use { git ->
                        git.checkout().setStage(stage).addPath(filePath).call()
                        git.add().addFilepattern(filePath).call()
                    }
                    Result.success(ConflictResolution(filePath, strategy, true))
                }

                ConflictResolutionStrategy.MANUAL -> {
                    if (customContent == null) {
                        return@withContext Result.failure(Exception("MANUAL strategy requires customContent"))
                    }
                    File(repoPath, filePath).writeText(customContent)
                    Git.open(File(repoPath)).use { git ->
                        git.add().addFilepattern(filePath).call()
                    }
                    Result.success(ConflictResolution(filePath, strategy, true))
                }

                ConflictResolutionStrategy.ABORT -> {
                    abortMerge(repoPath).map {
                        ConflictResolution(filePath, strategy, true, "Merge aborted")
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markAllResolved(repoPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val unmerged = git.status().call().conflicting
                    for (path in unmerged) {
                        git.add().addFilepattern(path).call()
                    }
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun completeMerge(repoPath: String, message: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    // JGit automatically reads MERGE_HEAD and creates a merge commit
                    git.commit().setMessage(message).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun abortMerge(repoPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    // Hard-reset to HEAD clears the merge state (equivalent to git merge --abort)
                    git.reset().setMode(ResetCommand.ResetType.HARD).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun parseConflictMarkers(content: String): Result<Conflict?> =
        Result.success(parseConflictMarkersInternal(content))

    // ─── Parser (unchanged from original) ────────────────────────────────────

    private fun parseConflictMarkersInternal(content: String): Conflict? {
        val lines = content.split("\n")
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("<<<<<<<")) {
                val currentLines  = mutableListOf<String>()
                val incomingLines = mutableListOf<String>()
                var inCurrent = true
                i++
                while (i < lines.size) {
                    when {
                        lines[i].startsWith("=======") -> { inCurrent = false; i++ }
                        lines[i].startsWith(">>>>>>>") -> {
                            return Conflict(
                                filePath        = "",
                                currentContent  = currentLines.joinToString("\n"),
                                incomingContent = incomingLines.joinToString("\n"),
                            )
                        }
                        else -> {
                            if (inCurrent) currentLines += lines[i] else incomingLines += lines[i]
                            i++
                        }
                    }
                }
            }
            i++
        }
        return null
    }
}

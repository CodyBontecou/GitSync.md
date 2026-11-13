package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.ConflictResolution
import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.data.models.MergeState
import com.bontecou.syncmd.domain.repository.ConflictRepository
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
class LocalConflictRepository(private val authorResolver: RepositoryAuthorResolver? = null,
    private val tokenProvider: (() -> String?)? = null,
    private val credentialResolver: ((String, String) -> com.bontecou.syncmd.data.models.Credentials?)? = null) : ConflictRepository {

    override suspend fun getMergeState(repoPath: String): Result<MergeState> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
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
                val unmergedFiles = com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
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
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                val conflicts = com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git -> ConflictIndex.read(git.repository) }

                Result.success(conflicts)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getFileConflict(repoPath: String, filePath: String): Result<Conflict?> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                val conflict = com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    ConflictIndex.read(git.repository).singleOrNull { filePath in it.allPaths }
                }
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
    ): Result<ConflictResolution> = RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.CONFLICT) {
        try {
            val detail = getFileConflict(repoPath, filePath).getOrThrow()
            if (strategy != ConflictResolutionStrategy.ABORT) {
                check(detail != null) { "Conflict is no longer active; refresh and retry" }
                detail.allPaths.forEach { ConflictIndex.target(File(repoPath), it) }
            }
            if (detail?.isRenameRename == true && strategy != ConflictResolutionStrategy.ABORT) {
                return@withRepository resolveRenamed(repoPath, detail, strategy, customContent, detail.ours?.path)
            }
            when (strategy) {
                ConflictResolutionStrategy.OURS, ConflictResolutionStrategy.THEIRS -> {
                    val stage = if (strategy == ConflictResolutionStrategy.OURS)
                        CheckoutCommand.Stage.OURS
                    else
                        CheckoutCommand.Stage.THEIRS

                    val notice = com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                        val cache = git.repository.readDirCache()
                        val entries = (0 until cache.entryCount).map { cache.getEntry(it) }.filter { it.pathString == filePath && it.stage > 0 }
                        check(entries.isNotEmpty()) { "Conflict is no longer active; refresh and retry" }
                        val number = if (strategy == ConflictResolutionStrategy.OURS) 2 else 3
                        if (entries.none { it.stage == number }) git.rm().addFilepattern(filePath).call()
                        else {
                            git.checkout().setStage(stage).addPath(filePath).call()
                            git.add().addFilepattern(filePath).call()
                        }
                        com.bontecou.syncmd.services.git.lfs.LfsCheckout.finish(git.repository, "Conflict resolved and staged", tokenProvider, credentialResolver,
                            remote = rebaseRemote(git), paths = setOf(filePath))
                    }
                    Result.success(ConflictResolution(filePath, strategy, true, notice.orEmpty()))
                }

                ConflictResolutionStrategy.MANUAL -> {
                    if (customContent == null) {
                        return@withRepository Result.failure(Exception("MANUAL strategy requires customContent"))
                    }
                    val detail = getFileConflict(repoPath, filePath).getOrThrow()
                        ?: error("Conflict is no longer active; refresh and retry")
                    check(!detail.isBinaryOrTooLarge) { "Binary or large conflicts must use ours or theirs" }
                    val root = File(repoPath).canonicalFile
                    val target = ConflictIndex.target(root, filePath)
                    val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(customContent))
                    check(encoded.remaining() <= 2 * 1024 * 1024) { "Resolved text is too large for the built-in editor" }
                    val bytes = ByteArray(encoded.remaining()).also(encoded::get)
                    check(target.parentFile.mkdirs() || target.parentFile.isDirectory) { "The conflict folder is unavailable" }
                    val temporary = java.nio.file.Files.createTempFile(target.parentFile.toPath(), ".gitsync-conflict-", ".tmp")
                    try {
                        java.io.FileOutputStream(temporary.toFile()).use { output -> output.write(bytes);output.fd.sync() }
                        val permissions = runCatching { java.nio.file.Files.getPosixFilePermissions(target.toPath()) }.getOrNull()
                        if (permissions != null) java.nio.file.Files.setPosixFilePermissions(temporary, permissions)
                        ConflictIndex.target(root, filePath)
                        check(ConflictIndex.revision(root,filePath) == detail.workTreeVersions[filePath]) { "The conflict file changed. Your draft was kept; refresh before resolving." }
                        try { java.nio.file.Files.move(temporary,target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                        catch (_:java.nio.file.AtomicMoveNotSupportedException) { java.nio.file.Files.move(temporary,target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                    } finally { java.nio.file.Files.deleteIfExists(temporary) }
                    com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
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

    override suspend fun resolveConflictWithPath(repoPath: String, filePath: String, keepPath: String, content: String): Result<ConflictResolution> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.CONFLICT) {
            try {
                val detail = getFileConflict(repoPath, filePath).getOrThrow() ?: error("Conflict is no longer active; refresh and retry")
                if (!detail.isRenameRename) {
                    require(keepPath == filePath) { "Choose the reviewed conflict filename" }
                    resolveConflict(repoPath, filePath, ConflictResolutionStrategy.MANUAL, content)
                } else resolveRenamed(repoPath, detail, ConflictResolutionStrategy.MANUAL, content, keepPath)
            } catch (failure: Exception) { Result.failure(failure) }
        }

    private fun resolveRenamed(repoPath: String, detail: Conflict, strategy: ConflictResolutionStrategy, content: String?, keepPath: String?): Result<ConflictResolution> =
        runCatching {
            com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                val cleanup = RenameConflictResolution.resolve(git.repository, detail, strategy, content, keepPath)
                val hydration = com.bontecou.syncmd.services.git.lfs.LfsCheckout.finish(git.repository, "Renamed conflict resolved and staged", tokenProvider, credentialResolver,
                    remote = rebaseRemote(git), paths = detail.allPaths.toSet())
                ConflictResolution(detail.filePath, strategy, true, listOfNotNull(cleanup, hydration).joinToString("\n\n"))
            }
        }

    override suspend fun markAllResolved(repoPath: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
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
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.MERGE) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    // JGit automatically reads MERGE_HEAD and creates a merge commit
                    git.useAuthor(repoPath, authorResolver)
                    git.commit().setMessage(message).call()
                    com.bontecou.syncmd.services.git.lfs.LfsCheckout.finish(git.repository, "Merge committed", tokenProvider, credentialResolver)?.let {
                        throw com.bontecou.syncmd.services.git.lfs.LfsCheckoutIncompleteException(it)
                    }
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun abortMerge(repoPath: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.MERGE) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    // Hard-reset to HEAD clears the merge state (equivalent to git merge --abort)
                    git.reset().setMode(ResetCommand.ResetType.HARD).call()
                    com.bontecou.syncmd.services.git.lfs.LfsCheckout.finish(git.repository, "Merge aborted", tokenProvider, credentialResolver)?.let {
                        throw com.bontecou.syncmd.services.git.lfs.LfsCheckoutIncompleteException(it)
                    }
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun rebaseRemote(git: Git): String? {
        if (!git.repository.repositoryState.name.startsWith("REBASING")) return null
        val branch = listOf("rebase-merge/head-name", "rebase-apply/head-name").map { File(git.repository.directory, it) }
            .firstOrNull { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }?.readText()?.trim()
            ?.takeIf { it.startsWith("refs/heads/") }?.removePrefix("refs/heads/") ?: return null
        return org.eclipse.jgit.lib.BranchConfig(git.repository.config, branch).remote?.takeUnless { it == "." }
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

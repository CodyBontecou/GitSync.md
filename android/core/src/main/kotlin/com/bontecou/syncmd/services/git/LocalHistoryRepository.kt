package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Commit
import com.bontecou.syncmd.data.models.HistoryException
import com.bontecou.syncmd.data.models.DirtyRepoException
import com.bontecou.syncmd.data.models.RevertResult
import com.bontecou.syncmd.data.models.RevertStrategy
import com.bontecou.syncmd.data.models.Stash
import com.bontecou.syncmd.data.models.StashResult
import com.bontecou.syncmd.data.models.Tag
import com.bontecou.syncmd.domain.repository.HistoryRepository
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.lib.RepositoryState
import java.io.File

/**
 * JGit-backed implementation of [HistoryRepository].
 *
 * Uses the pure-Java JGit library — no system `git` binary required.
 * ProcessBuilder("git") fails on Android with "No such file or directory";
 * JGit works everywhere.
 */
class LocalHistoryRepository(private val authorResolver: RepositoryAuthorResolver? = null) : HistoryRepository {

    override suspend fun getHistory(repoPath: String, maxCommits: Int): Result<List<Commit>> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val commits = git.log().setMaxCount(maxCommits).call()
                        .map { it.toCommit() }
                    Result.success(commits)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getCommit(repoPath: String, commitHash: String): Result<Commit> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val objectId = git.repository.resolve(commitHash)
                        ?: return@withRepository Result.failure(HistoryException("Commit not found: $commitHash"))
                    RevWalk(git.repository).use { walk ->
                        val revCommit = walk.parseCommit(objectId)
                        Result.success(revCommit.toCommit())
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun revertCommit(
        repoPath: String,
        commitHash: String,
        strategy: RevertStrategy,
    ): Result<RevertResult> = RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.REVERT) {
        try {
            com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                val objectId = git.repository.resolve(commitHash)
                    ?: return@withRepository Result.failure(HistoryException("Commit not found: $commitHash"))

                when (strategy) {
                    RevertStrategy.CREATE_NEW_COMMIT -> {
                        requireRevertReady(git)
                        val reviewed = CheckoutProtection.snapshot(git)
                        git.useAuthor(repoPath, authorResolver)
                        CheckoutProtection.verify(git, reviewed)
                        requireRevertReady(git)
                        RevWalk(git.repository).use { walk ->
                            val revCommit = walk.parseCommit(objectId)
                            val revertResult = git.revert().include(revCommit).call()
                            val newHead = git.repository.resolve("HEAD")?.name
                            Result.success(
                                RevertResult(
                                    success         = revertResult != null,
                                    newCommitHash   = newHead,
                                    message         = if (revertResult != null) "Reverted successfully" else "Revert failed",
                                    conflictsDetected = git.status().call().conflicting.size,
                                )
                            )
                        }
                    }
                    RevertStrategy.HARD_RESET -> {
                        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(commitHash).call()
                        Result.success(RevertResult(true, commitHash, "Hard reset to $commitHash"))
                    }
                    RevertStrategy.SOFT_RESET -> {
                        git.reset().setMode(ResetCommand.ResetType.SOFT).setRef(commitHash).call()
                        Result.success(RevertResult(true, commitHash, "Soft reset to $commitHash"))
                    }
                    RevertStrategy.MIXED_RESET -> {
                        git.reset().setMode(ResetCommand.ResetType.MIXED).setRef(commitHash).call()
                        Result.success(RevertResult(true, commitHash, "Mixed reset to $commitHash"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun requireRevertReady(git: Git) {
        check(git.repository.repositoryState == RepositoryState.SAFE) {
            "Finish or abort the current Git operation before reverting a commit."
        }
        if (!git.status().call().isClean) throw DirtyRepoException(
            "Commit or stash local changes before reverting a commit."
        )
    }

    override suspend fun listStashes(repoPath: String): Result<List<Stash>> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val stashes = git.stashList().call()
                        .mapIndexed { index, revCommit ->
                            Stash(
                                id        = "stash@{$index}",
                                name      = revCommit.shortMessage,
                                commitHash = revCommit.name,
                                timestamp  = revCommit.authorIdent.`when`.time,
                                message    = revCommit.fullMessage.trim(),
                            )
                        }
                    Result.success(stashes)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun stashSave(repoPath: String, message: String?): Result<StashResult> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.STASH) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    git.useAuthor(repoPath, authorResolver)
                    val cmd = git.stashCreate()
                    if (message != null) cmd.setWorkingDirectoryMessage(message)
                    val stashedCommit = cmd.call()

                    if (stashedCommit != null) {
                        // stash@{0} is always the newest entry
                        Result.success(
                            StashResult(
                                success  = true,
                                stashId  = "stash@{0}",
                                message  = message ?: stashedCommit.shortMessage,
                            )
                        )
                    } else {
                        Result.failure(Exception("Nothing to stash"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun stashApply(repoPath: String, stashId: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.STASH) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val (_, commit) = requireStash(git, stashId)
                    git.stashApply().setStashRef(commit).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) { Result.failure(e) }
        }

    override suspend fun stashPop(repoPath: String, stashId: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.STASH) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val (index, commit) = requireStash(git, stashId)
                    git.stashApply().setStashRef(commit).call()
                    check(git.stashList().call().elementAtOrNull(index)?.name == commit) {
                        "Stash list changed after applying. Changes were applied; the stash was retained. Review before removing it."
                    }
                    git.stashDrop().setStashRef(index).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) { Result.failure(e) }
        }

    override suspend fun stashDrop(repoPath: String, stashId: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.STASH) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val (index, _) = requireStash(git, stashId)
                    git.stashDrop().setStashRef(index).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) { Result.failure(e) }
        }

    private fun requireStash(git: Git, id: String): Pair<Int, String> {
        val text = Regex("stash@\\{(0|[1-9][0-9]*)\\}").matchEntire(id)?.groupValues?.get(1)
            ?: throw HistoryException("Invalid stash identifier")
        val index = text.toIntOrNull() ?: throw HistoryException("Invalid stash index")
        val commit = git.stashList().call().elementAtOrNull(index) ?: throw HistoryException("Stash no longer exists")
        return index to commit.name
    }

    override suspend fun listTags(repoPath: String): Result<List<Tag>> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val tags = git.tagList().call().mapNotNull { ref ->
                        val name = ref.name.removePrefix("refs/tags/")
                        RevWalk(git.repository).use { walk ->
                            try {
                                // Peel to get the tagged commit
                                val peeled = walk.peel(walk.parseAny(ref.objectId))
                                val isAnnotated = ref.objectId != peeled.id
                                val message = if (isAnnotated) {
                                    // Annotated tag: parse the tag object for its message
                                    runCatching {
                                        walk.parseTag(ref.objectId).fullMessage.trim()
                                    }.getOrNull()
                                } else null

                                Tag(
                                    name        = name,
                                    commitHash  = peeled.name,
                                    isAnnotated = isAnnotated,
                                    message     = message,
                                    refObjectId = ref.objectId.name,
                                )
                            } catch (_: Exception) { null }
                        }
                    }
                    Result.success(tags)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun createTag(
        repoPath: String,
        name: String,
        commitHash: String,
        annotated: Boolean,
        message: String?,
    ): Result<Unit> = RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.TAG) {
        try {
            com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                val objectId = git.repository.resolve(commitHash.ifBlank { "HEAD" })
                    ?: return@withRepository Result.failure(Exception("Cannot resolve: $commitHash"))

                RevWalk(git.repository).use { walk ->
                    val revObject = walk.parseAny(objectId)
                    if (annotated) git.useAuthor(repoPath, authorResolver)
                    val cmd = git.tag()
                        .setName(name)
                        .setObjectId(revObject)
                        .setAnnotated(annotated)
                    if (annotated && message != null) cmd.setMessage(message)
                    cmd.call()
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteTag(repoPath: String, name: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.TAG) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    git.tagDelete().setTags(name).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getTag(repoPath: String, name: String): Result<Tag> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val ref = git.repository.findRef("refs/tags/$name")
                        ?: return@withRepository Result.failure(HistoryException("Tag not found: $name"))

                    RevWalk(git.repository).use { walk ->
                        val peeled    = walk.peel(walk.parseAny(ref.objectId))
                        val isAnnotated = ref.objectId != peeled.id
                        val message = if (isAnnotated) {
                            runCatching { walk.parseTag(ref.objectId).fullMessage.trim() }.getOrNull()
                        } else null

                        Result.success(
                            Tag(
                                name        = name,
                                commitHash  = peeled.name,
                                isAnnotated = isAnnotated,
                                message     = message,
                                    refObjectId = ref.objectId.name,
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private fun org.eclipse.jgit.revwalk.RevCommit.toCommit() = Commit(
        hash         = name,
        shortHash    = name.take(7),
        author       = authorIdent.name,
        email        = authorIdent.emailAddress,
        message      = fullMessage.trim(),
        timestamp    = authorIdent.`when`.time,
        parentHashes = parents.map { it.name },
    )
}

package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Commit
import com.bontecou.syncmd.data.models.HistoryException
import com.bontecou.syncmd.data.models.RevertResult
import com.bontecou.syncmd.data.models.RevertStrategy
import com.bontecou.syncmd.data.models.Stash
import com.bontecou.syncmd.data.models.StashResult
import com.bontecou.syncmd.data.models.Tag
import com.bontecou.syncmd.domain.repository.HistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File

/**
 * JGit-backed implementation of [HistoryRepository].
 *
 * Uses the pure-Java JGit library — no system `git` binary required.
 * ProcessBuilder("git") fails on Android with "No such file or directory";
 * JGit works everywhere.
 */
class LocalHistoryRepository : HistoryRepository {

    override suspend fun getHistory(repoPath: String, maxCommits: Int): Result<List<Commit>> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val commits = git.log().setMaxCount(maxCommits).call()
                        .map { it.toCommit() }
                    Result.success(commits)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getCommit(repoPath: String, commitHash: String): Result<Commit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val objectId = git.repository.resolve(commitHash)
                        ?: return@withContext Result.failure(HistoryException("Commit not found: $commitHash"))
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
    ): Result<RevertResult> = withContext(Dispatchers.IO) {
        try {
            Git.open(File(repoPath)).use { git ->
                val objectId = git.repository.resolve(commitHash)
                    ?: return@withContext Result.failure(HistoryException("Commit not found: $commitHash"))

                when (strategy) {
                    RevertStrategy.CREATE_NEW_COMMIT -> {
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

    override suspend fun listStashes(repoPath: String): Result<List<Stash>> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
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
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
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
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    git.stashApply().setStashRef(stashId).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun stashPop(repoPath: String, stashId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    git.stashApply().setStashRef(stashId).call()
                    val index = stashId.removePrefix("stash@{").removeSuffix("}").toIntOrNull() ?: 0
                    git.stashDrop().setStashRef(index).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun stashDrop(repoPath: String, stashId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val index = stashId.removePrefix("stash@{").removeSuffix("}").toIntOrNull() ?: 0
                    git.stashDrop().setStashRef(index).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun listTags(repoPath: String): Result<List<Tag>> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Git.open(File(repoPath)).use { git ->
                val objectId = git.repository.resolve(commitHash.ifBlank { "HEAD" })
                    ?: return@withContext Result.failure(Exception("Cannot resolve: $commitHash"))

                RevWalk(git.repository).use { walk ->
                    val revObject = walk.parseAny(objectId)
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
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    git.tagDelete().setTags(name).call()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getTag(repoPath: String, name: String): Result<Tag> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val ref = git.repository.findRef("refs/tags/$name")
                        ?: return@withContext Result.failure(HistoryException("Tag not found: $name"))

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

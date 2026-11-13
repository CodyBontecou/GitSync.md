package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.DirtyRepoException
import com.bontecou.syncmd.data.models.GitFileStatusKind
import com.bontecou.syncmd.data.models.MergeType
import com.bontecou.syncmd.data.models.PullPlan
import com.bontecou.syncmd.data.models.RepositoryStatus
import com.bontecou.syncmd.data.models.SafePullPlan
import com.bontecou.syncmd.data.models.SafePullResult
import com.bontecou.syncmd.domain.repository.PullRepository
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.BranchTrackingStatus
import java.io.File
import kotlinx.coroutines.ensureActive
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.TagOpt
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * JGit-backed implementation of [PullRepository].
 *
 * Uses the pure-Java JGit library — no system `git` binary required.
 * ProcessBuilder("git") fails on Android with "No such file or directory";
 * JGit works everywhere.
 */
class LocalPullRepository(
    private val tokenProvider: (() -> String?)? = null,
    private val credentialResolver: ((String, String) -> com.bontecou.syncmd.data.models.Credentials?)? = null,
    private val credentialRevision: ((String) -> String?)? = null,
) : PullRepository {

    override suspend fun getStatus(repoPath: String): Result<RepositoryStatus> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                open(repoPath).use { git ->
                    val head = git.repository.resolve("HEAD")?.name
                    val fullBranch = git.repository.fullBranch
                    val branch = git.repository.branch.takeIf { it.isNotEmpty() }
                        ?: return@withRepository Result.failure(Exception("Could not determine current branch"))

                    val jgitStatus = git.status().call()

                    val allChanges = jgitStatus.toStatusEntries()
                    val modifiedFiles = allChanges.filter { it.hasUnstagedChanges && it.kind != GitFileStatusKind.UNTRACKED }
                    val stagedFiles = allChanges.filter { it.hasStagedChanges }
                    val untrackedFiles = allChanges.filter { it.kind == GitFileStatusKind.UNTRACKED }
                    git.repository.refDatabase.refresh()
                    check(git.repository.fullBranch == fullBranch && git.repository.resolve("HEAD")?.name == head) {
                        "Repository changed while reading status. Refresh again."
                    }

                    Result.success(
                        RepositoryStatus(
                            repoPath      = repoPath,
                            currentBranch = branch,
                            isClean       = jgitStatus.isClean,
                            modifiedFiles = modifiedFiles,
                            stagedFiles   = stagedFiles,
                            untrackedFiles = untrackedFiles,
                            allChanges    = allChanges,
                            headCommit    = head,
                            lastSyncAt    = RepositoryActivity.read(git.repository),
                            lastFetchAt   = RepositoryFetchActivity.read(git.repository),
                        )
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        }

    override suspend fun planPull(repoPath: String): Result<SafePullPlan> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                open(repoPath).use { git ->
                    val before = snapshot(git)
                    val auth = RemoteAuthentication.provider(git.repository, before.remote, false, tokenProvider, credentialResolver)
                    val target = fetchTarget(git, before, auth)
                    val isDirty = !git.status().call().isClean
                    val ahead = count(git, before.head, target)
                    val behind = count(git, target, before.head)
                    val checkoutBlock = if (!isDirty && ahead == 0 && behind > 0)
                        runCatching { protectUntracked(git, target) }.exceptionOrNull()?.message else null

                    val (mergeType, canPull, blockingReason) = when {
                        isDirty          -> Triple(MergeType.UP_TO_DATE, false, "Working tree has uncommitted changes")
                        checkoutBlock != null -> Triple(MergeType.FAST_FORWARD, false, checkoutBlock)
                        behind == 0 && ahead == 0 -> Triple(MergeType.UP_TO_DATE, true, null)
                        behind > 0 && ahead == 0  -> Triple(MergeType.FAST_FORWARD, true, null)
                        behind > 0 && ahead > 0   -> Triple(MergeType.MERGE_COMMIT, false, "Branches have diverged. Choose an explicit merge or rebase.")
                        else             -> Triple(MergeType.UP_TO_DATE, true, null)
                    }

                    Result.success(
                        SafePullPlan(
                            repoPath      = repoPath,
                            currentBranch = before.branch.removePrefix("refs/heads/"),
                            remoteBranch  = before.remote + "/" + before.remoteBranch.removePrefix("refs/heads/"),
                            basePlan = PullPlan(
                                mergeType         = mergeType,
                                fastForwardable   = mergeType == MergeType.FAST_FORWARD,
                                conflictsExpected = mergeType == MergeType.MERGE_COMMIT,
                                commitsAhead      = ahead,
                                commitsBehind     = behind,
                            ),
                            canPull       = canPull,
                            blockingReason = blockingReason,
                        )
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        }

    override suspend fun executePull(repoPath: String): Result<SafePullResult> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.PULL) {
            try {
                open(repoPath).use { git ->
                    requireClean(git)
                    val before = snapshot(git)
                    val auth = RemoteAuthentication.provider(git.repository, before.remote, false, tokenProvider, credentialResolver)
                    val target = fetchTarget(git, before, auth)
                    requireClean(git)
                    val ahead = count(git, before.head, target)
                    val behind = count(git, target, before.head)
                    check(ahead == 0 || behind == 0) { "Branches have diverged. Choose an explicit merge or rebase; no changes were applied." }
                    if (behind > 0) {
                        protectUntracked(git, target)
                        verifySnapshot(git, before)
                        requireClean(git)
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val merged = git.merge().include(target).setFastForward(MergeCommand.FastForwardMode.FF_ONLY).call()
                        check(merged.mergeStatus.isSuccessful) { "Pull could not fast-forward; refresh repository status before retrying" }
                    }
                    val lfsAttention = try {
                        com.bontecou.syncmd.services.git.lfs.LfsRepository.hydrate(git.repository, auth)
                        null
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException || e is java.io.InterruptedIOException) throw e
                        "Pull completed, but some LFS contents still need download. Open Git LFS to retry. Current pointer files and local edits were kept."
                    }
                    Result.success(
                        SafePullResult(
                            success        = true,
                            message        = if (behind > 0) "Pull completed successfully" else "Already up to date",
                            commitsApplied = behind,
                            mergeRequired  = false,
                            attention = lfsAttention,
                        )
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        }

    override suspend fun fetch(repoPath: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.FETCH) {
            try {
                open(repoPath).use { git ->
                    val remote = BranchConfig(git.repository.config, git.repository.branch).remote ?: "origin"
                    require(remote != ".") { "Configure a remote before fetching" }
                    val fetchCommand = git.fetch().setRemote(remote)
                    val auth = RemoteAuthentication.provider(git.repository, remote, false, tokenProvider, credentialResolver)
                    fetchCommand.setCredentialsProvider(auth).setTransportConfigCallback(auth)
                    fetchCommand.setProgressMonitor(currentTransferMonitor())
                    fetchCommand.call()
                    RepositoryFetchActivity.record(git.repository)
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        }

    override suspend fun getCommitsAhead(repoPath: String): Result<Int> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                open(repoPath).use { git ->
                    val branch = git.repository.branch.takeIf { it.isNotEmpty() }
                        ?: return@withRepository Result.success(0)
                    val tracking = BranchTrackingStatus.of(git.repository, branch)
                    Result.success(tracking?.aheadCount ?: 0)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        }

    override suspend fun getCommitsBehind(repoPath: String): Result<Int> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                open(repoPath).use { git ->
                    val branch = git.repository.branch.takeIf { it.isNotEmpty() }
                        ?: return@withRepository Result.success(0)
                    val tracking = BranchTrackingStatus.of(git.repository, branch)
                    Result.success(tracking?.behindCount ?: 0)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
        }

    internal data class PullSnapshot(val branch: String, val head: ObjectId, val remote: String,
        val remoteBranch: String, val tracking: String, val config: String, val folder: String,
        val metadata: String, val credentials: String?)

    internal fun open(path: String): Git {
        val root = File(path).canonicalFile
        val metadata = File(root, ".git")
        require(metadata.isDirectory && !Files.isSymbolicLink(metadata.toPath())) { "Select the working copy root before continuing" }
        require(listOf("config", "objects", "refs").none { Files.isSymbolicLink(File(metadata, it).toPath()) }) {
            "Linked Git metadata needs manual review before continuing"
        }
        val git = com.bontecou.syncmd.services.git.lfs.GitLfs.open(root)
        try {
            require(git.repository.workTree.canonicalFile == root && git.repository.directory.canonicalFile == metadata &&
                git.repository.config.getString("core", null, "worktree") == null &&
                listOf("commondir", "objects/info/alternates").none { File(metadata, it).exists() }) {
                "Shared or external Git metadata needs manual review before continuing"
            }
            return git
        } catch (failure: Exception) { git.close(); throw failure }
    }

    internal fun snapshot(git: Git): PullSnapshot {
        val repository = git.repository
        repository.config.load()
        require(repository.repositoryState == RepositoryState.SAFE) { "Finish the active Git operation before updating this branch" }
        val branch = repository.fullBranch?.takeIf { it.startsWith("refs/heads/") && Repository.isValidRefName(it) }
            ?: error("Switch to a local branch before continuing")
        val head = repository.exactRef(branch)?.objectId ?: error("Create a commit before continuing")
        val config = BranchConfig(repository.config, branch.removePrefix("refs/heads/"))
        val remote = config.remote?.takeIf { it != "." && Repository.isValidRefName("refs/remotes/$it/branch") }
            ?: error("Configure a remote upstream before continuing")
        val merges = repository.config.getStringList("branch", branch.removePrefix("refs/heads/"), "merge")
        val remoteBranch = merges.singleOrNull()?.takeIf { it.startsWith("refs/heads/") && Repository.isValidRefName(it) }
            ?: error("Configure one upstream branch before continuing")
        val tracking = config.trackingBranch?.takeIf { it.startsWith("refs/remotes/$remote/") && Repository.isValidRefName(it) }
            ?: error("Configure the upstream fetch mapping before continuing")
        val path = repository.workTree.canonicalPath
        return PullSnapshot(branch, head, remote, remoteBranch, tracking,
            MessageDigest.getInstance("SHA-256").digest(File(repository.directory, "config").readBytes()).joinToString("") { "%02x".format(it) },
            directoryIdentity(repository.workTree), directoryIdentity(repository.directory), credentialRevision?.invoke(path))
    }
    private fun directoryIdentity(file: File): String {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isDirectory && !attributes.isSymbolicLink) { "Repository folder changed; refresh before continuing" }
        return attributes.fileKey()?.toString() ?: error("Could not verify repository folder identity")
    }
    internal fun verifySnapshot(git: Git, before: PullSnapshot) {
        check(snapshot(git) == before) { "Branch, commit, folder or connection changed during fetch. Review the pull again; no working changes were applied." }
    }
    internal suspend fun fetchTarget(git: Git, before: PullSnapshot, auth: ScopedCredentialsProvider): ObjectId {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        verifySnapshot(git, before)
        val fetched = git.fetch().setRemote(before.remote).setRefSpecs(RefSpec("+${before.remoteBranch}:${before.tracking}"))
            .setTagOpt(TagOpt.NO_TAGS).setTimeout(30).setCredentialsProvider(auth).setTransportConfigCallback(auth)
            .setProgressMonitor(currentTransferMonitor()).call()
        RepositoryFetchActivity.record(git.repository)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        verifySnapshot(git, before)
        val target = fetched.getAdvertisedRef(before.remoteBranch)?.objectId ?: error("The upstream branch no longer exists")
        check(git.repository.exactRef(before.tracking)?.objectId == target) { "The upstream changed during fetch. Refresh before continuing." }
        return target
    }
    internal fun requireClean(git: Git) {
        if (!git.status().call().isClean) throw DirtyRepoException("Cannot update this branch with uncommitted changes")
    }
    internal fun count(git: Git, from: ObjectId, excluding: ObjectId) = git.log().add(from).not(excluding).call().count()

    /** Shared path protection for the exact fast-forward target tree. */
    internal fun protectUntracked(git: Git, target: ObjectId) = CheckoutProtection.protectTree(git, target)



}

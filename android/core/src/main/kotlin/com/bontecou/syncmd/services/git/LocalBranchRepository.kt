package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.data.models.BranchType
import com.bontecou.syncmd.data.models.MergeResult
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.data.models.MergeType
import com.bontecou.syncmd.domain.repository.BranchRepository
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeCommand
import java.io.File

/**
 * JGit-backed implementation of [BranchRepository].
 *
 * Uses the pure-Java JGit library — no system `git` binary required.
 * ProcessBuilder("git") fails on Android with "No such file or directory";
 * JGit works everywhere.
 */
class LocalBranchRepository(private val authorResolver: RepositoryAuthorResolver? = null,
    private val tokenProvider: (() -> String?)? = null,
    private val credentialResolver: ((String, String) -> com.bontecou.syncmd.data.models.Credentials?)? = null) : BranchRepository {

    override suspend fun listBranches(repoPath: String): Result<List<Branch>> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val headBranch = git.repository.branch

                    val refs = git.branchList()
                        .setListMode(ListBranchCommand.ListMode.ALL)
                        .call()

                    val branches = refs.mapNotNull { ref ->
                        val refName = ref.name
                        val isRemote = refName.startsWith("refs/remotes/")
                        val isLocal  = refName.startsWith("refs/heads/")
                        if (!isRemote && !isLocal) return@mapNotNull null

                        // Strip "refs/remotes/origin/" or "refs/remotes/" or "refs/heads/"
                        val displayName = when {
                            refName.startsWith("refs/remotes/origin/") ->
                                refName.removePrefix("refs/remotes/origin/")
                            refName.startsWith("refs/remotes/") ->
                                refName.removePrefix("refs/remotes/")
                            else ->
                                refName.removePrefix("refs/heads/")
                        }

                        // Skip "HEAD" remote pointer
                        if (displayName == "HEAD") return@mapNotNull null

                        Branch(
                            name       = displayName,
                            type       = if (isRemote) BranchType.REMOTE else BranchType.LOCAL,
                            isHead     = isLocal && displayName == headBranch,
                            lastCommit = ref.objectId?.name,
                        )
                    }

                    Result.success(branches)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getCurrentBranch(repoPath: String): Result<Branch> =
        RepositoryOperationCoordinator.withRepository(repoPath) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    val branchName = git.repository.branch.takeIf { it.isNotEmpty() }
                        ?: return@withRepository Result.failure(Exception("Could not determine current branch"))

                    // Look up the ref to get the latest commit hash
                    val head = git.repository.resolve("HEAD")

                    Result.success(
                        Branch(
                            name       = branchName,
                            type       = BranchType.LOCAL,
                            isHead     = true,
                            lastCommit = head?.name,
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun createBranch(
        repoPath: String,
        name: String,
        startPoint: String,
    ): Result<Unit> = RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.BRANCH) {
        try {
            com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                val cmd = git.branchCreate().setName(name)
                if (startPoint != "HEAD") cmd.setStartPoint(startPoint)
                cmd.call()
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun switchBranch(repoPath: String, name: String): Result<Unit> =
        RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.BRANCH) {
            try {
                com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                    CheckoutProtection.requireClean(git, "switch branch")
                    val before = CheckoutProtection.snapshot(git)
                    val target = git.repository.resolve("refs/heads/$name") ?: git.repository.resolve(name)
                        ?: error("Cannot resolve branch: $name")
                    CheckoutProtection.protectTree(git, target)
                    CheckoutProtection.verify(git, before)
                    CheckoutProtection.requireClean(git, "switch branch")
                    check((git.repository.resolve("refs/heads/$name") ?: git.repository.resolve(name)) == target) { "Target branch changed. Refresh and review again." }
                    git.checkout().setName(name).call()
                    com.bontecou.syncmd.services.git.lfs.LfsCheckout.finish(git.repository, "Branch switched", tokenProvider, credentialResolver)?.let {
                        throw com.bontecou.syncmd.services.git.lfs.LfsCheckoutIncompleteException(it)
                    }
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun deleteBranch(
        repoPath: String,
        name: String,
        force: Boolean,
    ): Result<Unit> = RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.BRANCH) {
        try {
            com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                git.branchDelete()
                    .setBranchNames(name)
                    .setForce(force)
                    .call()
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun merge(
        repoPath: String,
        sourceBranch: String,
        strategy: MergeStrategy,
    ): Result<MergeResult> = RepositoryOperationCoordinator.withRepository(repoPath, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.MERGE) {
        try {
            com.bontecou.syncmd.services.git.lfs.GitLfs.open(File(repoPath)).use { git ->
                CheckoutProtection.requireClean(git, "merge branches")
                val before = CheckoutProtection.snapshot(git)
                val source = git.repository.exactRef("refs/heads/$sourceBranch") ?: git.repository.findRef(sourceBranch)
                val srcRef = git.repository.resolve("refs/heads/$sourceBranch")
                    ?: git.repository.resolve(sourceBranch)
                    ?: return@withRepository Result.failure(Exception("Cannot resolve branch: $sourceBranch"))

                val ffMode = when (strategy) {
                    MergeStrategy.FAST_FORWARD -> MergeCommand.FastForwardMode.FF_ONLY
                    MergeStrategy.RECURSIVE    -> MergeCommand.FastForwardMode.NO_FF
                    MergeStrategy.PREFER_FF    -> MergeCommand.FastForwardMode.FF
                }

                git.useAuthor(repoPath, authorResolver)
                CheckoutProtection.verify(git, before)
                CheckoutProtection.requireClean(git, "merge branches")
                if (source != null) check(git.repository.exactRef(source.name)?.objectId == source.objectId) { "Source branch changed. Refresh and review again." }
                CheckoutProtection.protectMerge(git, srcRef, strategy == MergeStrategy.FAST_FORWARD)
                CheckoutProtection.verify(git, before)
                CheckoutProtection.requireClean(git, "merge branches")
                if (source != null) check(git.repository.exactRef(source.name)?.objectId == source.objectId) { "Source branch changed. Refresh and review again." }
                val result = git.merge().setStrategy(RenameAwareMergeStrategy)
                    .include(srcRef)
                    .setFastForward(ffMode)
                    .call()

                val mergeType = when (result.mergeStatus) {
                    org.eclipse.jgit.api.MergeResult.MergeStatus.FAST_FORWARD    -> MergeType.FAST_FORWARD
                    org.eclipse.jgit.api.MergeResult.MergeStatus.MERGED          -> MergeType.MERGE_COMMIT
                    org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING     -> MergeType.CONFLICT
                    org.eclipse.jgit.api.MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> MergeType.UP_TO_DATE
                    else -> MergeType.MERGE_COMMIT
                }

                val lfsNotice = if (result.mergeStatus.isSuccessful || result.mergeStatus == org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING)
                    com.bontecou.syncmd.services.git.lfs.LfsCheckout.finish(git.repository,
                        if (result.mergeStatus.isSuccessful) "Merge completed" else "Merge stopped for conflicts", tokenProvider, credentialResolver)
                    else null

                Result.success(
                    MergeResult(
                        success       = result.mergeStatus.isSuccessful,
                        mergeType     = mergeType,
                        message       = lfsNotice ?: result.mergeStatus.toString(),
                        conflictCount = result.conflicts?.size ?: 0,
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

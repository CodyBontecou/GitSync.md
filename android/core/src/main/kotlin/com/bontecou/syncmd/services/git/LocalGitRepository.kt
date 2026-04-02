package com.bontecou.syncmd.services.git

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.data.models.GitFileStatusKind
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.data.models.MergeType
import com.bontecou.syncmd.data.models.PullPlan
import com.bontecou.syncmd.data.models.PushResult
import com.bontecou.syncmd.domain.repository.GitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.util.Locale

/**
 * JGit-backed implementation of [GitRepository].
 *
 * Uses the pure-Java JGit library — no system `git` binary required.
 * This is the correct approach for Android where ProcessBuilder("git")
 * would fail with "No such file or directory".
 *
 * Primary operation wired to the add-repo flow is [clone].
 * The remaining methods (getStatus, pull, push) are also JGit-based so
 * the full interface is functional on-device without any shell dependency.
 */
class LocalGitRepository(
    private val tokenProvider: (() -> String?)? = null,
) : GitRepository {

    // ─── Clone ────────────────────────────────────────────────────────────────

    override suspend fun clone(
        url: String,
        path: String,
        creds: Credentials,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val target = File(path)
        target.parentFile?.mkdirs()
        val cp = creds.toJGit()

        try {
            Git.cloneRepository()
                .setURI(url)
                .setDirectory(target)
                .setCredentialsProvider(cp)
                .call()
                .use { /* close the Git handle */ }

            Result.success(Unit)
        } catch (e: Exception) {
            // External/shared Android storage can reject symlink/executable metadata writes.
            // Retry with no-checkout + core.symlinks=false and perform checkout separately.
            if (isSymlinkPermissionFailure(e)) {
                runCatching { target.deleteRecursively() }
                return@withContext cloneWithoutSymlinks(url, target, cp)
            }

            if (e is GitAPIException) {
                Result.failure(Exception(e.message?.sanitize() ?: "Clone failed", e))
            } else {
                Result.failure(Exception(e.message?.sanitize() ?: "Clone failed", e))
            }
        }
    }

    // ─── Status ───────────────────────────────────────────────────────────────

    override suspend fun getStatus(repoPath: String): Result<List<GitStatusEntry>> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val status = git.status().call()
                    val entries = mutableListOf<GitStatusEntry>()

                    status.modified.forEach  { entries += GitStatusEntry(it, GitFileStatusKind.MODIFIED)  }
                    status.changed.forEach   { entries += GitStatusEntry(it, GitFileStatusKind.STAGED)    }
                    status.added.forEach     { entries += GitStatusEntry(it, GitFileStatusKind.STAGED)    }
                    status.untracked.forEach { entries += GitStatusEntry(it, GitFileStatusKind.UNTRACKED) }

                    Result.success(entries)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ─── Pull (analysis — returns a plan, does not fast-forward) ─────────────

    override suspend fun pull(repoPath: String): Result<PullPlan> =
        withContext(Dispatchers.IO) {
            try {
                Git.open(File(repoPath)).use { git ->
                    val status = git.status().call()
                    val isDirty = !status.isClean

                    val mergeType = when {
                        isDirty -> MergeType.UP_TO_DATE  // blocked
                        else    -> MergeType.FAST_FORWARD
                    }

                    Result.success(
                        PullPlan(
                            mergeType         = mergeType,
                            fastForwardable   = !isDirty,
                            conflictsExpected = false,
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ─── Push ─────────────────────────────────────────────────────────────────

    override suspend fun push(
        repoPath: String,
        message: String,
    ): Result<PushResult> = withContext(Dispatchers.IO) {
        try {
            Git.open(File(repoPath)).use { git ->
                val pushCommand = git.push()
                credentialsFromTokenProvider()?.let { pushCommand.setCredentialsProvider(it) }
                pushCommand.call()
                Result.success(PushResult(success = true, message = "Pushed successfully"))
            }
        } catch (e: GitAPIException) {
            Result.failure(Exception(e.message?.sanitize() ?: "Push failed", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Build a JGit [UsernamePasswordCredentialsProvider] from our sealed [Credentials].
     *
     * GitHub accepts a PAT as the password with any non-empty username.
     * Using "x-access-token" matches GitHub's own documentation for PAT auth.
     */
    private fun Credentials.toJGit(): UsernamePasswordCredentialsProvider =
        when (this) {
            is Credentials.Pat   -> UsernamePasswordCredentialsProvider("x-access-token", token)
            is Credentials.Basic -> UsernamePasswordCredentialsProvider(username, password)
        }

    private fun cloneWithoutSymlinks(
        url: String,
        target: File,
        cp: UsernamePasswordCredentialsProvider,
    ): Result<Unit> {
        return try {
            Git.cloneRepository()
                .setURI(url)
                .setDirectory(target)
                .setCredentialsProvider(cp)
                .setNoCheckout(true)
                .call()
                .use { /* close */ }

            // Re-open repository after writing config so checkout uses fresh options.
            Git.open(target).use { git ->
                val config = git.repository.config
                config.setBoolean("core", null, "symlinks", false)
                config.setBoolean("core", null, "filemode", false)
                config.save()
                config.load()
            }

            Git.open(target).use { git ->
                checkoutAfterNoCheckoutClone(git)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(e.message?.sanitize() ?: "Clone failed", e))
        }
    }

    /**
     * Complete checkout after a no-checkout clone with storage-safe settings.
     *
     * Some Android filesystems fail on `reset --hard` with EPERM, so we avoid reset
     * and checkout HEAD/remote branch directly.
     */
    private fun checkoutAfterNoCheckoutClone(git: Git) {
        val repo = git.repository

        checkoutWithConflictRecovery(git, checkoutName = "HEAD").getOrElse { headCheckoutError ->
            val fallbackBranches = buildCheckoutBranchCandidates(repo)
            var lastError: Throwable = headCheckoutError

            for (branch in fallbackBranches) {
                val branchCheckout = runCatching {
                    checkoutBranchOrTrackRemote(git = git, branch = branch)
                }

                val didCheckout = branchCheckout.getOrNull() == true
                if (didCheckout) return
                lastError = branchCheckout.exceptionOrNull() ?: lastError
            }

            throw lastError
        }
    }

    private fun checkoutBranchOrTrackRemote(git: Git, branch: String): Boolean {
        val repo = git.repository
        val localRef = repo.findRef("refs/heads/$branch")

        if (localRef != null) {
            checkoutWithConflictRecovery(git, checkoutName = branch).getOrThrow()
            return true
        }

        val remoteRef = "refs/remotes/origin/$branch"
        if (repo.findRef(remoteRef) == null) return false

        checkoutCreateTrackingBranchWithRecovery(git, branch = branch, remoteRef = remoteRef).getOrThrow()
        return true
    }

    private fun checkoutWithConflictRecovery(git: Git, checkoutName: String): Result<Unit> {
        return runCatching {
            git.checkout()
                .setName(checkoutName)
                .setForced(true)
                .call()
            Unit
        }.recoverCatching { firstError ->
            val recovered = removeConflictingWorkingTreePath(git.repository, firstError)
            if (!recovered) throw firstError

            git.checkout()
                .setName(checkoutName)
                .setForced(true)
                .call()
            Unit
        }
    }

    private fun checkoutCreateTrackingBranchWithRecovery(
        git: Git,
        branch: String,
        remoteRef: String,
    ): Result<Unit> {
        return runCatching {
            git.checkout()
                .setCreateBranch(true)
                .setName(branch)
                .setStartPoint(remoteRef)
                .setForced(true)
                .call()
            Unit
        }.recoverCatching { firstError ->
            val recovered = removeConflictingWorkingTreePath(git.repository, firstError)
            if (!recovered) throw firstError

            git.checkout()
                .setCreateBranch(true)
                .setName(branch)
                .setStartPoint(remoteRef)
                .setForced(true)
                .call()
            Unit
        }
    }

    private fun removeConflictingWorkingTreePath(
        gitRepository: org.eclipse.jgit.lib.Repository,
        error: Throwable,
    ): Boolean {
        val relativePath = extractCannotDeletePath(error) ?: return false
        val conflicted = File(gitRepository.workTree, relativePath)

        return runCatching {
            if (!conflicted.exists()) return@runCatching false
            if (conflicted.isDirectory) conflicted.deleteRecursively() else conflicted.delete()
        }.getOrDefault(false)
    }

    private fun extractCannotDeletePath(error: Throwable): String? {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")

        val marker = "cannot delete file:"
        val index = message.lowercase(Locale.US).indexOf(marker)
        if (index == -1) return null

        return message.substring(index + marker.length)
            .substringBefore("|")
            .trim()
            .ifBlank { null }
    }

    private fun buildCheckoutBranchCandidates(gitRepository: org.eclipse.jgit.lib.Repository): List<String> {
        val fromRemoteHead = gitRepository.findRef("refs/remotes/origin/HEAD")
            ?.target
            ?.name
            ?.takeIf { it.startsWith("refs/remotes/origin/") }
            ?.removePrefix("refs/remotes/origin/")

        val fromCurrentHead = runCatching { gitRepository.fullBranch }.getOrNull()
            ?.takeIf { it.startsWith("refs/heads/") }
            ?.removePrefix("refs/heads/")

        val localBranches = runCatching {
            gitRepository.refDatabase.getRefsByPrefix("refs/heads/")
                .map { it.name.removePrefix("refs/heads/") }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

        val remoteBranches = runCatching {
            gitRepository.refDatabase.getRefsByPrefix("refs/remotes/origin/")
                .map { it.name.removePrefix("refs/remotes/origin/") }
                .filter { it.isNotBlank() && !it.equals("HEAD", ignoreCase = true) }
        }.getOrDefault(emptyList())

        return (listOfNotNull(fromRemoteHead, fromCurrentHead, "main", "master") + localBranches + remoteBranches)
            .distinct()
    }

    private fun isSymlinkPermissionFailure(error: Throwable): Boolean {
        val msg = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
            .lowercase()
        return msg.contains("operation not permitted") ||
            msg.contains("permission denied") ||
            msg.contains("lnk_file") ||
            msg.contains("symlink")
    }

    private fun credentialsFromTokenProvider(): UsernamePasswordCredentialsProvider? {
        val token = tokenProvider?.invoke()?.takeIf { it.isNotBlank() } ?: return null
        return UsernamePasswordCredentialsProvider("x-access-token", token)
    }

    /**
     * Strip any embedded credentials from error messages before surfacing to the UI.
     */
    private fun String.sanitize(): String =
        replace(Regex("https://[^@]+@"), "https://***@")
}

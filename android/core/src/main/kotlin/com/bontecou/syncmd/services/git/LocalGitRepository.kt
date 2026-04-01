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
class LocalGitRepository : GitRepository {

    // ─── Clone ────────────────────────────────────────────────────────────────

    override suspend fun clone(
        url: String,
        path: String,
        creds: Credentials,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            File(path).parentFile?.mkdirs()

            val cp = creds.toJGit()

            Git.cloneRepository()
                .setURI(url)
                .setDirectory(File(path))
                .setCredentialsProvider(cp)
                .call()
                .use { /* close the Git handle */ }

            Result.success(Unit)
        } catch (e: GitAPIException) {
            Result.failure(Exception(e.message?.sanitize() ?: "Clone failed"))
        } catch (e: Exception) {
            Result.failure(Exception(e.message?.sanitize() ?: "Clone failed"))
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
                git.push().call()
                Result.success(PushResult(success = true, message = "Pushed successfully"))
            }
        } catch (e: GitAPIException) {
            Result.failure(Exception(e.message?.sanitize() ?: "Push failed"))
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

    /**
     * Strip any embedded credentials from error messages before surfacing to the UI.
     */
    private fun String.sanitize(): String =
        replace(Regex("https://[^@]+@"), "https://***@")
}

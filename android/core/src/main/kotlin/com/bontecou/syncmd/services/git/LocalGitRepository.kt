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
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.io.FileOutputStream
import java.util.logging.Level
import java.util.logging.Logger
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

    companion object {
        private val log = Logger.getLogger("SyncMdClone")
    }

    // ─── Clone ────────────────────────────────────────────────────────────────

    override suspend fun clone(
        url: String,
        path: String,
        creds: Credentials,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val target = File(path)
        target.parentFile?.mkdirs()
        val cp = creds.toJGit()

        // Always use no-checkout clone on Android. The normal Git.cloneRepository()
        // performs checkout in a single pass with no recovery — Android's MediaProvider
        // can scan and lock files mid-checkout (especially .obsidian/app.json), causing
        // unrecoverable "Cannot delete file" errors. The no-checkout path separates
        // fetch from checkout and has per-file conflict recovery with retries.
        log.warning("clone() starting no-checkout clone to $path")
        cloneWithoutSymlinks(url, target, cp)
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

            // Place .nomedia before checkout to suppress MediaProvider scanning
            // that can race with JGit file renames/deletes during checkout.
            runCatching { File(target, ".nomedia").apply { if (!exists()) createNewFile() } }

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
     * Complete checkout after a no-checkout clone.
     *
     * Bypasses JGit's Checkout / DirCacheCheckout entirely because those classes
     * use File.createTempFile() internally — Android's FUSE layer on Android/media
     * storage doesn't support the O_EXCL flag that createFileExclusively0() needs,
     * causing "Operation not permitted" IOException on every checkout attempt.
     *
     * Instead we walk the commit tree with TreeWalk, read each blob with
     * ObjectReader, and write directly via FileOutputStream (which works fine).
     * Then we build the DirCache (index) manually and set up the local branch.
     */
    private fun checkoutAfterNoCheckoutClone(git: Git) {
        val repo = git.repository

        // Determine default branch
        val branch = findDefaultBranch(repo)
            ?: throw Exception("No branch found to checkout")
        val remoteRef = repo.findRef("refs/remotes/origin/$branch")
            ?: throw Exception("Remote ref not found: refs/remotes/origin/$branch")
        val commitId = remoteRef.objectId

        log.warning("manualCheckout: branch=$branch commit=${commitId.name}")

        // 1. Write every file from the tree directly (no temp files)
        manualCheckoutTree(git, commitId)

        // 2. Create local branch pointing at the commit
        val refUpdate = repo.updateRef("refs/heads/$branch")
        refUpdate.setNewObjectId(commitId)
        refUpdate.update()

        // 3. Point HEAD at the local branch
        val headUpdate = repo.updateRef(Constants.HEAD)
        headUpdate.link("refs/heads/$branch")

        // 4. Set up tracking config
        val config = repo.config
        config.setString("branch", branch, "remote", "origin")
        config.setString("branch", branch, "merge", "refs/heads/$branch")
        config.save()

        log.warning("manualCheckout: HEAD -> refs/heads/$branch (${commitId.abbreviate(7).name()})")
    }

    /**
     * Walk the tree of [commitId] and write every blob to the working tree.
     * Also builds a matching DirCache (index) so JGit sees a clean state.
     */
    private fun manualCheckoutTree(git: Git, commitId: ObjectId) {
        val repo = git.repository
        val reader = repo.newObjectReader()

        try {
            val revWalk = RevWalk(reader)
            val commit = revWalk.parseCommit(commitId)
            val tree = commit.tree

            val treeWalk = TreeWalk(reader)
            treeWalk.addTree(tree)
            treeWalk.isRecursive = true

            // Build a fresh index
            val dc = repo.lockDirCache()
            try {
                val builder = dc.builder()
                var fileCount = 0
                val skippedFiles = mutableListOf<String>()

                while (treeWalk.next()) {
                    val path = treeWalk.pathString
                    val mode = treeWalk.getFileMode(0)
                    val objectId = treeWalk.getObjectId(0)

                    // Skip symlinks and submodules
                    if (mode == FileMode.SYMLINK || mode == FileMode.GITLINK) {
                        log.warning("manualCheckout: skipping $path (mode=$mode)")
                        continue
                    }

                    // Skip tree entries (shouldn't appear with recursive=true, but guard)
                    if (mode == FileMode.TREE) continue

                    // Write blob to working tree.
                    // Android's FUSE layer on Android/media rejects filenames with
                    // characters like ? * " < > | etc. Catch and skip those files
                    // rather than aborting the entire checkout.
                    val targetFile = File(repo.workTree, path)
                    try {
                        targetFile.parentFile?.mkdirs()

                        val loader = reader.open(objectId, Constants.OBJ_BLOB)
                        FileOutputStream(targetFile).use { fos ->
                            loader.copyTo(fos)
                        }

                        if (mode == FileMode.EXECUTABLE_FILE) {
                            targetFile.setExecutable(true)
                        }

                        // Add matching index entry
                        val entry = DirCacheEntry(path)
                        entry.fileMode = mode
                        entry.setObjectId(objectId)
                        entry.setLength(targetFile.length())
                        entry.setLastModified(
                            java.nio.file.Files.getLastModifiedTime(targetFile.toPath()).toInstant()
                        )
                        builder.add(entry)

                        fileCount++
                    } catch (e: Exception) {
                        // File could not be written (likely illegal filename chars on FUSE).
                        // Log it and continue — better to have a partial checkout than none.
                        log.warning("manualCheckout: skipping file (write failed): $path — ${e.message}")
                        skippedFiles.add(path)
                        // Clean up partial file if it was created
                        runCatching { if (targetFile.exists()) targetFile.delete() }
                    }
                }

                builder.finish()
                dc.write()
                dc.commit()

                log.warning("manualCheckout: wrote $fileCount files, skipped ${skippedFiles.size}")
                if (skippedFiles.isNotEmpty()) {
                    log.warning("manualCheckout: skipped files: ${skippedFiles.joinToString(", ")}")
                }
            } catch (e: Exception) {
                dc.unlock()
                throw e
            }

            treeWalk.close()
            revWalk.close()
        } finally {
            reader.close()
        }
    }

    /**
     * Find the default branch name from remote refs.
     */
    private fun findDefaultBranch(repo: org.eclipse.jgit.lib.Repository): String? {
        // Check origin/HEAD symbolic ref
        val originHead = repo.findRef("refs/remotes/origin/HEAD")
        if (originHead?.target != null) {
            val target = originHead.target.name
            if (target.startsWith("refs/remotes/origin/")) {
                return target.removePrefix("refs/remotes/origin/")
            }
        }

        // Fallback: try common names
        for (name in listOf("main", "master")) {
            if (repo.findRef("refs/remotes/origin/$name") != null) return name
        }

        // Last resort: first remote branch that isn't HEAD
        return repo.refDatabase.getRefsByPrefix("refs/remotes/origin/")
            .map { it.name.removePrefix("refs/remotes/origin/") }
            .firstOrNull { it != "HEAD" && it.isNotBlank() }
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

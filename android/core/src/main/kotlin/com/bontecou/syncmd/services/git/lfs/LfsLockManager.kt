package com.bontecou.syncmd.services.git.lfs

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.repositories.GitTrackingRemoval
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.util.LfsFactory
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

data class LfsLockContext(val path: String, val branch: String, val head: String?, val remote: String, val revision: String, val writeAccess: Boolean = true)
data class LfsLockReview(val context: LfsLockContext, val endpoint: String, val ours: List<LfsLock>, val theirs: List<LfsLock>,
    val listed: List<LfsLock>, val verified: Boolean, val supported: Boolean, val candidates: List<String>, val credentialRevision: String? = null)
data class LfsLockChange(val review: LfsLockReview?, val notice: String)
class LfsLockReviewChanged : IOException("Repository settings, credentials, branch, files or lock ownership changed. Refresh server locks before making a change.")
class LfsLockManagementException(message: String) : IOException(message)

/** Reviewed remote writes; local Git/index/worktree content is never modified by lock management. */
internal class LfsLockManager(private val token: (() -> String?)?, private val resolver: ((String, String) -> Credentials?)?,
    private val credentialRevision: ((String) -> String?)?) {
    suspend fun review(path: String, verifyOwnership: Boolean): LfsLockReview = RepositoryOperationCoordinator.withRepository(path) {
        GitLfs.open(File(path)).use { git -> collect(git.repository, context(git.repository, verifyOwnership), verifyOwnership) }
    }
    suspend fun create(review: LfsLockReview, file: String): LfsLockChange = change(review, file, null, false)
    suspend fun release(review: LfsLockReview, lock: LfsLock, force: Boolean): LfsLockChange = change(review, lock.path, lock, force)
    private suspend fun change(review: LfsLockReview, file: String, selected: LfsLock?, force: Boolean): LfsLockChange =
        RepositoryOperationCoordinator.withRepository(review.context.path) {
            check(review.verified && review.supported && review.context.writeAccess) { "Check lock ownership with push access before changing locks." }
            GitLfs.open(File(review.context.path)).use { git ->
                val repo = git.repository
                unchanged(repo, review.context)
                val current = collect(repo, review.context, true, review.endpoint, review.credentialRevision)
                if (!current.supported) throw LfsLockReviewChanged()
                if (selected == null) {
                    if (force || file !in review.candidates || file !in current.candidates || (current.ours + current.theirs).any { it.path == file }) throw LfsLockReviewChanged()
                } else {
                    val before = if (force) review.theirs else review.ours
                    val now = if (force) current.theirs else current.ours
                    if (selected !in before || selected !in now) throw LfsLockReviewChanged()
                }
                val operation = currentCoroutineContext()
                val locks = client(repo, review.context, true, review.endpoint, review.credentialRevision) {
                    operation.ensureActive()
                    if (selected == null && file !in candidates(repo)) throw LfsLockReviewChanged()
                }
                val result = try {
                    if (selected == null) locks.create(file, review.context.branch) else locks.unlock(selected.id, force, review.context.branch)
                } catch (failure: Exception) {
                    when (failure) {
                        is kotlinx.coroutines.CancellationException, is LfsLockReviewChanged, is LfsEndpointReviewCancelled,
                        is LfsEndpointApprovalFailed, is LfsEndpointApprovalRequired -> throw failure
                        is LfsHttpStatusException -> throw LfsLockManagementException(when (failure.status) {
                            409 -> "The lock changed while it was being reviewed. Refresh server locks before retrying."
                            401, 403 -> "The server rejected this lock change. Check push access and ownership, then refresh server locks."
                            else -> "The server did not confirm the lock change. It may have applied. Refresh server locks before trying again."
                        })
                        else -> throw LfsLockManagementException("The server did not confirm the lock change. It may have applied. Refresh server locks before trying again.")
                    }
                }
                if (result == null) return@use LfsLockChange(null, "The server did not confirm support for this lock change. Refresh server locks before another change.")
                if (result.path != file) throw LfsLockManagementException("The server returned a different lock. Check server locks before making another change.")
                val notice = if (selected == null) "Locked $file." else "Released the lock on $file."
                val refreshed = try { collect(repo, review.context, true, review.endpoint, review.credentialRevision) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { null }
                LfsLockChange(refreshed, notice + if (refreshed == null) " The change was confirmed, but the lock list could not be refreshed. Refresh before another change." else " Local files and commits were kept.")
            }
        }
    private suspend fun collect(repo: Repository, expected: LfsLockContext, verified: Boolean, endpoint: String? = null, credentialRevision: String? = null): LfsLockReview {
        val operation = currentCoroutineContext()
        operation.ensureActive()
        unchanged(repo, expected)
        val client = client(repo, expected, verified, endpoint, credentialRevision) { operation.ensureActive() }
        val ours = mutableListOf<LfsLock>(); val theirs = mutableListOf<LfsLock>(); val listed = mutableListOf<LfsLock>()
        val ids = mutableSetOf<String>(); val paths = mutableSetOf<String>(); val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var supported = true
        var pages = 0
        do {
            operation.ensureActive()
            check(++pages <= 1000) { "Too many server lock pages. No lock change was made." }
            val next = if (verified) {
                val page = client.verify(cursor, expected.branch); supported = page.supported
                ours += page.ours; theirs += page.theirs
                (page.ours + page.theirs) to page.nextCursor
            } else {
                val page = client.list(cursor, expected.branch); supported = page.supported
                listed += page.locks
                page.locks to page.nextCursor
            }
            check(supported || pages == 1) { "Server lock support changed while loading. Refresh before making changes." }
            next.first.forEach { check(ids.add(it.id) && paths.add(it.path) && ids.size <= 100000) { "The server returned repeated or excessive locks. No change was made." } }
            cursor = next.second
            check(cursor == null || cursors.add(cursor)) { "The server repeated a lock page. No change was made." }
        } while (supported && cursor != null)
        operation.ensureActive()
        val available = candidates(repo)
        unchanged(repo, expected)
        return LfsLockReview(expected, client.lastEndpoint ?: error("No LFS lock endpoint"), ours.sortedBy { it.path }, theirs.sortedBy { it.path },
            listed.sortedBy { it.path }, verified, supported, available, client.lastCredentialRevision)
    }
    private fun client(repo: Repository, expected: LfsLockContext, write: Boolean, endpoint: String?, credentialRevision: String?, beforeRequest: () -> Unit = {}): LfsLocks {
        val auth = RemoteAuthentication.provider(repo, "origin", write, token, resolver)
        val (url, credentials) = auth.lfsConnection()
        var destination = endpoint
        var revision = credentialRevision
        var reviewed = endpoint != null
        return LfsLocks(repo, url, credentials, auth.lfsRemoteName(), beforeRequest = { actual, actualRevision ->
            unchanged(repo, expected)
            beforeRequest()
            if (reviewed && (destination != actual || revision != actualRevision)) throw LfsLockReviewChanged()
            destination = actual; revision = actualRevision; reviewed = true
        })
    }
    private fun unchanged(repo: Repository, expected: LfsLockContext) { if (context(repo, expected.writeAccess) != expected) throw LfsLockReviewChanged() }
    private fun context(repo: Repository, write: Boolean): LfsLockContext {
        val root = repo.workTree.canonicalFile
        require(!Files.isSymbolicLink(repo.directory.toPath()) && repo.directory.canonicalFile == File(root, ".git")) { "Lock management requires an ordinary working copy" }
        val branch = repo.fullBranch
        require(branch.startsWith("refs/heads/") && Repository.isValidRefName(branch)) { "Switch to a local branch before reviewing server locks" }
        val urls = (if (write) repo.config.getStringList("remote", "origin", "pushurl").toList() else emptyList())
            .ifEmpty { repo.config.getStringList("remote", "origin", "url").toList() }
        require(urls.size == 1) { "Configure one origin before reviewing server locks" }
        val remote = runCatching { GitRemote.parse(urls.single()).url }.getOrElse { throw LfsLockManagementException("Configure an HTTPS or SSH origin before reviewing server locks.") }
        val digest = MessageDigest.getInstance("SHA-256")
        fun append(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes) }
        // A worktree can be replaced while its original .git directory is retained.
        // Its own identity must remain part of the reviewed authority for remote writes.
        val worktree = Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(worktree.isDirectory && !worktree.isSymbolicLink) { "Could not verify working folder identity" }
        append(requireNotNull(worktree.fileKey()) { "Could not verify working folder identity" }.toString())
        append(requireNotNull(GitTrackingRemoval.metadataIdentity(root.path)) { "Could not verify Git metadata identity" })
        append(credentialRevision?.invoke(root.path).orEmpty())
        // Only the digest is retained. Renewal or replacement of resolved credentials invalidates a review too.
        val (_, credentials) = RemoteAuthentication.provider(repo, "origin", write, token, resolver).lfsConnection()
        when (credentials) {
            Credentials.None -> append("none")
            is Credentials.Pat -> { append("pat"); append(credentials.token) }
            is Credentials.Basic -> { append("basic"); append(credentials.username); append(credentials.password) }
            is Credentials.Ssh -> { append("ssh"); append(credentials.privateKey); append(credentials.passphrase); append(credentials.publicKey) }
        }
        val attributes = sortedSetOf(".gitattributes")
        val index = repo.readDirCache()
        check(index.entryCount <= 100000) { "Too many files to review locks in one working copy" }
        for (i in 0 until index.entryCount) {
            val path = index.getEntry(i).pathString
            if (path.endsWith(".gitattributes")) attributes += path
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) { attributes += "$parent/.gitattributes"; parent = parent.substringBeforeLast('/', "") }
        }
        val globalAttributes = repo.config.getString("core", null, "attributesFile")?.let {
            if (it.startsWith("~/")) File(org.eclipse.jgit.util.FS.DETECTED.userHome(), it.removePrefix("~/")) else File(it).let { path -> if (path.isAbsolute) path else File(root, it) }
        }
        val files = listOfNotNull(File(repo.directory, "config"), File(repo.directory, "index"), File(repo.directory, "info/attributes"), File(root, ".lfsconfig"), globalAttributes) + attributes.map { File(root, it) }
        files.forEach { file ->
            require(file.absoluteFile == file.canonicalFile && !Files.isSymbolicLink(file.toPath())) { "Lock review cannot follow symbolic links" }
            append(file.path)
            if (!file.exists()) append("absent") else {
                require(file.isFile && file.length() <= 256L * 1024 * 1024) { "Repository metadata is too large to review locks" }
                append(file.length().toString())
                file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count == -1) break; digest.update(buffer, 0, count) } }
                digest.update(0)
            }
        }
        return LfsLockContext(root.path, branch, repo.resolve("HEAD")?.name, remote, digest.digest().joinToString("") { "%02x".format(it) }, write)
    }
    private fun candidates(repo: Repository): List<String> {
        val index = repo.readDirCache()
        return (0 until index.entryCount).mapNotNull { i ->
            val entry = index.getEntry(i)
            if (entry.stage != 0 || entry.fileMode !in listOf(FileMode.REGULAR_FILE, FileMode.EXECUTABLE_FILE)) return@mapNotNull null
            val file = File(repo.workTree, entry.pathString)
            if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.canonicalFile != file.absoluteFile) return@mapNotNull null
            if (LfsFactory.getAttributesForPath(repo, entry.pathString).isSet("lockable")) entry.pathString else null
        }.distinct().sorted()
    }
}

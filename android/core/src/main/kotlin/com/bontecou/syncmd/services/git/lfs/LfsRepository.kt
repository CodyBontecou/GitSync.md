package com.bontecou.syncmd.services.git.lfs

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.RemoteAuthentication
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.ScopedCredentialsProvider
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.ObjectWalk
import org.eclipse.jgit.util.LfsFactory
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class LfsWorktreeChangedException : IllegalStateException("An LFS pointer, repository branch or index changed during download. Your current files were kept. Review them and retry.")
data class LfsHydrationResult(val pointers: Int, val downloaded: Int, val hydrated: Int)
/** Bytes still to download for the pointers being restored; [done] grows as objects arrive. */
data class LfsDownloadProgress(val files: Int, val done: Long, val total: Long)
/** Live download progress per repository path, published while [LfsRepository.hydrate] transfers objects. */
object LfsDownloads {
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow<Map<String, LfsDownloadProgress>>(emptyMap())
    val progress: kotlinx.coroutines.flow.StateFlow<Map<String, LfsDownloadProgress>> = mutable
    fun publish(path: String, value: LfsDownloadProgress?) {
        while (true) {
            val old = mutable.value
            val next = if (value == null) old - path else old + (path to value)
            if (mutable.compareAndSet(old, next)) return
        }
    }
}
data class LfsFileInfo(val path: String, val pointer: LfsPointer, val hydrated: Boolean, val cached: Boolean, val modified: Boolean, val lockable: Boolean)

/** Repository leases serialize app operations; exact bytes and index IDs protect downloaded replacements. */
class LfsRepository(private val tokenProvider: (() -> String?)? = null,
    private val credentialResolver: ((String, String) -> Credentials?)? = null,
    credentialRevision: ((String) -> String?)? = null) {
    private val lockManager = LfsLockManager(tokenProvider, credentialResolver, credentialRevision)
    suspend fun reviewLocks(path: String, verifyOwnership: Boolean = true) = lockManager.review(path, verifyOwnership)
    suspend fun createReviewedLock(review: LfsLockReview, file: String) = lockManager.create(review, file)
    suspend fun releaseReviewedLock(review: LfsLockReview, lock: LfsLock, force: Boolean = false) = lockManager.release(review, lock, force)
    suspend fun listLocks(path: String, cursor: String? = null, ref: String? = null, file: String? = null, id: String? = null, limit: Int = 100): LfsLockPage = withLocks(path, false) { it.list(cursor, ref, file, id, limit) }
    suspend fun verifyLocks(path: String, cursor: String? = null, ref: String? = null, limit: Int = 100): LfsLockVerification = withLocks(path, true) { it.verify(cursor, ref, limit) }
    suspend fun createLock(path: String, file: String, ref: String? = null): LfsLock? = withLocks(path, true) { it.create(file, ref) }
    suspend fun unlock(path: String, id: String, force: Boolean = false, ref: String? = null): LfsLock? = withLocks(path, true) { it.unlock(id, force, ref) }
    private suspend fun <T> withLocks(path: String, push: Boolean, action: (LfsLocks) -> T): T = RepositoryOperationCoordinator.withRepository(path) {
        GitLfs.open(File(path)).use { git ->
            val auth = RemoteAuthentication.provider(git.repository, "origin", push, tokenProvider, credentialResolver)
            val (url, credentials) = auth.lfsConnection()
            action(LfsLocks(git.repository, url, credentials, auth.lfsRemoteName()))
        }
    }
    suspend fun inspect(path: String): List<LfsFileInfo> = RepositoryOperationCoordinator.withRepository(path) {
        GitLfs.open(File(path)).use { git -> inspect(git.repository) }
    }
    suspend fun hydrate(path: String): LfsHydrationResult = RepositoryOperationCoordinator.withRepository(path, com.bontecou.syncmd.services.diagnostics.DiagnosticOperation.LFS) {
        GitLfs.open(File(path)).use { git ->
            val auth = RemoteAuthentication.provider(git.repository, "origin", false, tokenProvider, credentialResolver)
            try { hydrate(git.repository, { auth }, onProgress = { LfsDownloads.publish(path, it) }) }
            finally { LfsDownloads.publish(path, null) }
        }
    }
    companion object {
        private data class PointerFile(val path: String, val pointer: LfsPointer, val indexId: ObjectId)
        private fun pointers(repository: Repository): List<PointerFile> {
            val index = repository.readDirCache()
            return (0 until index.entryCount).mapNotNull { i ->
                val entry = index.getEntry(i)
                if (entry.stage != 0 || entry.fileMode !in listOf(FileMode.REGULAR_FILE, FileMode.EXECUTABLE_FILE)) return@mapNotNull null
                val blob = repository.open(entry.objectId, Constants.OBJ_BLOB)
                if (blob.size > LfsPointer.MAX_BYTES) return@mapNotNull null
                val pointer = LfsPointer.parse(blob.getBytes(LfsPointer.MAX_BYTES)) ?: return@mapNotNull null
                PointerFile(entry.pathString, pointer, entry.objectId.copy())
            }
        }
        private fun file(repository: Repository, path: String): File {
            require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.equals(".git", true) }) { "Unsafe Git LFS worktree path" }
            val root = repository.workTree.canonicalFile
            val candidate = File(root, path)
            require(candidate.canonicalFile == candidate.absoluteFile && !Files.isSymbolicLink(candidate.toPath())) { "Git LFS cannot follow a symbolic link" }
            return candidate
        }
        private fun readPointer(file: File): ByteArray? = if (file.isFile && file.length() <= LfsPointer.MAX_BYTES) file.readBytes().takeIf { it.size <= LfsPointer.MAX_BYTES } else null
        fun inspect(repository: Repository): List<LfsFileInfo> {
            val cache = LfsObjectStore(repository)
            return pointers(repository).map { item ->
                val target = file(repository, item.path)
                val present = target.isFile && LfsObjectStore.hash(target) == item.pointer
                val placeholder = readPointer(target)?.let { LfsPointer.parse(it) == item.pointer } == true
                val attributes = runCatching { LfsFactory.getAttributesForPath(repository, item.path) }.getOrNull()
                LfsFileInfo(item.path, item.pointer, present, runCatching { cache.contains(item.pointer) }.getOrDefault(false), !present && !placeholder,
                    attributes?.isSet("lockable") == true)
            }
        }
        fun hydrate(repository: Repository, auth: ScopedCredentialsProvider): LfsHydrationResult = hydrate(repository, { auth })

        /** Resolve credentials only if a missing object actually needs a network request. */
        internal fun hydrate(repository: Repository, auth: () -> ScopedCredentialsProvider, paths: Set<String>? = null,
            onProgress: (LfsDownloadProgress) -> Unit = {}): LfsHydrationResult {
            val cache = LfsObjectStore(repository)
            val head = repository.resolve("HEAD")?.copy()
            val branch = repository.fullBranch
            val indexed = pointers(repository).filter { paths == null || it.path in paths }
            for (item in indexed) {
                if (!runCatching { cache.contains(item.pointer) }.getOrDefault(false)) {
                    val target = file(repository, item.path)
                    if (target.isFile && LfsObjectStore.hash(target) == item.pointer) target.inputStream().use { cache.put(it, item.pointer) }
                }
            }
            val discovered = indexed.mapNotNull { item ->
                val target = file(repository, item.path)
                val bytes = readPointer(target) ?: return@mapNotNull null
                if (LfsPointer.parse(bytes) != item.pointer || LfsFactory.getAttributesForPath(repository, item.path).getValue("filter") != "lfs") null
                else Triple(item, target, bytes)
            }
            if (discovered.isEmpty()) return LfsHydrationResult(0, 0, 0)
            val missing = discovered.map { it.first.pointer }.distinct().filter { !runCatching { cache.contains(it) }.getOrDefault(false) }
            val downloaded = if (missing.isEmpty()) 0 else {
                val connection = auth()
                val (url, credentials) = connection.lfsConnection()
                val files = discovered.count { it.first.pointer in missing }
                val total = missing.sumOf { it.size }
                var done = 0L
                onProgress(LfsDownloadProgress(files, 0, total))
                LfsTransfers(repository, url, credentials, remoteName = connection.lfsRemoteName()).download(missing) { read ->
                    done = minOf(total, done + read); onProgress(LfsDownloadProgress(files, done, total))
                }
            }
            GitLfs.enable(repository)
            var hydrated = 0
            for ((item, target, observed) in discovered) {
                val source = cache.verified(item.pointer) ?: error("Verified Git LFS object is missing after download")
                val temporary = Files.createTempFile(target.parentFile.toPath(), ".gitsync-lfs-", ".tmp").toFile()
                try {
                    FileOutputStream(temporary).use { output -> source.inputStream().use { it.copyTo(output, 128 * 1024) }; output.fd.sync() }
                    check(LfsObjectStore.hash(temporary) == item.pointer) { "Git LFS cached object changed during checkout" }
                    val permissions = runCatching { Files.getPosixFilePermissions(target.toPath()) }.getOrNull()
                    if (permissions != null) Files.setPosixFilePermissions(temporary.toPath(), permissions)
                    val index = repository.lockDirCache()
                    try {
                        val current = index.getEntry(item.path)
                        if (repository.resolve("HEAD") != head || repository.fullBranch != branch || current == null || current.stage != 0 || current.objectId != item.indexId ||
                            file(repository, item.path) != target || readPointer(target)?.contentEquals(observed) != true) throw LfsWorktreeChangedException()
                        try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                        // A replay may leave the pointer's byte length in the index. Invalidate only
                        // this stat hint so JGit compares filtered content rather than flagging the
                        // expanded size as an edit. Keep the object ID, mode, stage and flags.
                        current.setLength(0)
                        index.write()
                        check(index.commit()) { "LFS file contents were downloaded, but the Git index needs refresh" }
                    } finally { index.unlock() }
                    hydrated++
                } finally { Files.deleteIfExists(temporary.toPath()) }
            }
            return LfsHydrationResult(discovered.size, downloaded, hydrated)
        }
        /** Walk every object reachable from the exact refs being published, including annotated tags/history. */
        fun uploadBeforePush(repository: Repository, ids: Collection<ObjectId>, auth: ScopedCredentialsProvider, ref: String? = null): Int {
            val found = linkedSetOf<LfsPointer>()
            ObjectWalk(repository).use { walk ->
                ids.distinct().forEach { walk.markStart(walk.parseAny(it)) }
                while (walk.next() != null) { }
                while (true) {
                    val item = walk.nextObject() ?: break
                    if (item.type != Constants.OBJ_BLOB) continue
                    val blob = repository.open(item, Constants.OBJ_BLOB)
                    if (blob.size <= LfsPointer.MAX_BYTES) LfsPointer.parse(blob.getBytes(LfsPointer.MAX_BYTES))?.let { found += it }
                    check(found.size <= 100000) { "Push contains too many Git LFS objects. Publish a smaller set of refs." }
                }
            }
            if (found.isEmpty()) return 0
            val cache = LfsObjectStore(repository)
            // An imported working copy may already hold the exact object without a cache.
            pointers(repository).filter { it.pointer in found }.forEach { item ->
                if (!runCatching { cache.contains(item.pointer) }.getOrDefault(false)) {
                    val target = file(repository, item.path)
                    if (target.isFile && LfsObjectStore.hash(target) == item.pointer) target.inputStream().use { cache.put(it, item.pointer) }
                }
            }
            val (url, credentials) = auth.lfsConnection()
            return LfsTransfers(repository, url, credentials, remoteName = auth.lfsRemoteName()).upload(found, ref)
        }
    }
}

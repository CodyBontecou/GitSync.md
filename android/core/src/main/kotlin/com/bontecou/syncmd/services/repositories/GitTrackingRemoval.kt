package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryState
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class MetadataEntry(val directory: Boolean, val bytes: Long, val digest: String, val executable: Boolean)
data class GitTrackingRemovalPlan internal constructor(
    val path: String,
    val branch: String?,
    val remoteUrl: String?,
    val metadataBytes: Long,
    val operationId: String,
    internal val rootKey: String,
    internal val gitKey: String,
    internal val entries: Map<String, MetadataEntry>,
) { val metadataIdentity: String get() = gitKey }
data class GitTrackingRemovalResult(val backupDirectory: String?, val backupArchive: String?)
class GitTrackingRemovalException(
    message: String,
    val detached: Boolean = false,
    val recoveryPath: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Removes only a reviewed repository's own metadata. Working files are never copied or deleted. */
class GitTrackingRemoval(
    private val sameVolume: (File, File) -> Boolean = { a, b -> Files.getFileStore(a.toPath()) == Files.getFileStore(b.toPath()) },
    private val moveMetadata: (File, File) -> Unit = { a, b -> Files.move(a.toPath(), b.toPath()); Unit },
) {
    suspend fun prepare(path: String): GitTrackingRemovalPlan = RepositoryOperationCoordinator.withRepository(path) {
        val root = File(path).canonicalFile
        val git = File(root, ".git")
        require(git.isDirectory && !Files.isSymbolicLink(git.toPath())) { "Choose a folder with its own .git directory; linked worktrees are not supported" }
        require(!hasRepositoryAncestor(root.parentFile)) { "This repository is inside another Git repository. Review the parent repository first." }
        require(listOf("commondir", "objects/info/alternates", "modules", "worktrees").none { File(git, it).exists() }) {
            "This repository uses shared metadata, submodules, or external objects and cannot be detached safely"
        }
        val info = RepositoryDiscovery().inspect(root.path)
        requireSafeState(root)
        val entries = snapshot(git)
        check(entries == snapshot(git)) { "Git metadata changed during review; try again" }
        GitTrackingRemovalPlan(root.path, info.branch, info.remoteUrl, entries.values.sumOf { it.bytes }, UUID.randomUUID().toString(), key(root), key(git), entries)
    }

    suspend fun remove(plan: GitTrackingRemovalPlan, backupRoot: String?): GitTrackingRemovalResult =
        RepositoryOperationCoordinator.withRepository(plan.path) {
            val root = File(plan.path)
            val metadata = File(root, ".git")
            validate(plan)
            val parent = backupRoot?.let { File(it).canonicalFile } ?: root.parentFile
            require(parent != root && !parent.path.startsWith(root.path + File.separator)) { "Choose a backup folder outside the repository" }
            require(!hasRepositoryAncestor(parent)) { "Backups must be outside every Git repository" }
            check(parent.mkdirs() || parent.isDirectory) { "Cannot create the backup folder" }
            require(sameVolume(parent, metadata)) { "Choose a backup folder on the same storage volume" }
            val owner = File(parent, "${if (backupRoot == null) ".gitsync-detach" else "gitsync-backup"}-${plan.operationId}")
            check(owner.mkdir()) { "Cannot create a private metadata destination" }
            val relocated = File(owner, "original-git-metadata")
            var moved = false
            var archive: File? = null
            try {
                if (backupRoot != null) {
                    val copy = File(owner, "verified-git-copy")
                    copyMetadata(metadata, copy, plan.entries)
                    check(snapshot(copy) == plan.entries) { "Backup verification failed; original Git metadata remains in place" }
                    archive = File(owner, "git-metadata.zip")
                    writeArchive(copy, archive, plan.entries)
                    verifyArchive(archive, plan.entries)
                }
                validate(plan)
                require(!hasRepositoryAncestor(parent)) { "The backup location is now inside a Git repository; choose another folder" }
                currentCoroutineContext().ensureActive()
                moveMetadata(metadata, relocated)
                moved = true
                try {
                    check(key(root) == plan.rootKey && key(relocated) == plan.gitKey && snapshot(relocated) == plan.entries) {
                        "Git metadata changed while being detached"
                    }
                } catch (e: Exception) {
                    if (!Files.exists(metadata.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        try { moveMetadata(relocated, metadata); moved = false
                        } catch (restore: Exception) {
                            throw GitTrackingRemovalException("Git metadata could not be restored. Your files are intact; the original metadata remains at ${relocated.path}. Keep this folder disconnected.", true, relocated.path, restore)
                        }
                    }
                    if (moved) throw GitTrackingRemovalException("A different .git item appeared. Your files and original metadata at ${relocated.path} were kept. Keep this folder disconnected.", true, relocated.path, e)
                    throw e
                }
                if (backupRoot == null) {
                    try { deleteWithoutFollowingLinks(relocated); Files.delete(owner.toPath())
                    } catch (e: Exception) {
                        throw GitTrackingRemovalException("Git tracking was removed, but temporary metadata at ${relocated.path} could not be fully deleted. Working files are intact.", true, relocated.path, e)
                    }
                    GitTrackingRemovalResult(null, null)
                } else GitTrackingRemovalResult(owner.path, archive!!.path)
            } finally {
                if (!moved && owner.exists()) deleteWithoutFollowingLinks(owner)
            }
        }

    private suspend fun validate(plan: GitTrackingRemovalPlan) {
        val root = File(plan.path)
        val metadata = File(root, ".git")
        check(key(root) == plan.rootKey && key(metadata) == plan.gitKey) { "Repository identity changed after review; review it again" }
        require(!hasRepositoryAncestor(root.parentFile)) { "The repository is now inside another Git repository; review the parent first" }
        requireSafeState(root)
        check(snapshot(metadata) == plan.entries) { "Git metadata changed after review; review the folder again" }
    }

    private fun requireSafeState(root: File) {
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(root).use { git -> require(git.repository.repositoryState == RepositoryState.SAFE) { "Finish or abort the active Git operation before removing tracking" } }
    }

    private suspend fun snapshot(directory: File): Map<String, MetadataEntry> {
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "Metadata must be an ordinary directory" }
        val entries = linkedMapOf<String, MetadataEntry>()
        val queue = java.util.ArrayDeque<File>()
        queue.add(directory)
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val current = queue.removeFirst()
            val children = current.listFiles() ?: error("Cannot read Git metadata")
            for (child in children.sortedBy { it.name }) {
                require(!Files.isSymbolicLink(child.toPath())) { "Git metadata contains symbolic links; no metadata was removed" }
                require(!child.name.endsWith(".lock")) { "Git is busy or a lock file remains at ${child.name}" }
                require('\\' !in child.name) { "Unsupported metadata filename" }
                val relative = child.relativeTo(directory).invariantSeparatorsPath
                if (child.isDirectory) {
                    entries[relative] = MetadataEntry(true, 0, "", child.canExecute())
                    queue.add(child)
                } else {
                    require(child.isFile) { "Unsupported metadata item" }
                    entries[relative] = MetadataEntry(false, child.length(), digest(child), child.canExecute())
                }
            }
        }
        return entries
    }

    private suspend fun copyMetadata(source: File, target: File, entries: Map<String, MetadataEntry>) {
        check(target.mkdir())
        for ((path, entry) in entries) {
            currentCoroutineContext().ensureActive()
            val from = File(source, path)
            val to = File(target, path)
            require(!Files.isSymbolicLink(from.toPath())) { "Metadata changed during backup" }
            if (entry.directory) Files.createDirectory(to.toPath())
            else Files.copy(from.toPath(), to.toPath(), StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
        }
    }

    private suspend fun writeArchive(source: File, archive: File, entries: Map<String, MetadataEntry>) {
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for ((path, entry) in entries) {
                currentCoroutineContext().ensureActive()
                zip.putNextEntry(ZipEntry(".git/$path${if (entry.directory) "/" else ""}"))
                if (!entry.directory) File(source, path).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private suspend fun verifyArchive(archive: File, entries: Map<String, MetadataEntry>) {
        val seen = mutableSetOf<String>()
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                val path = entry.name.removePrefix(".git/").removeSuffix("/")
                val expected = entries[path] ?: error("Unexpected file in backup archive")
                check(seen.add(path) && entry.isDirectory == expected.directory) { "Backup archive verification failed" }
                if (!entry.isDirectory) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    var length = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        length += count
                        digest.update(buffer, 0, count)
                    }
                    check(length == expected.bytes && digest.digest().hex() == expected.digest) { "Backup archive verification failed" }
                }
            }
        }
        check(seen == entries.keys) { "Backup archive is incomplete" }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun key(file: File): String {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isDirectory && !attrs.isSymbolicLink) { "Repository identity changed" }
        return attrs.fileKey()?.toString() ?: error("This storage provider cannot identify directories safely")
    }
    private fun hasRepositoryAncestor(folder: File?): Boolean {
        var current = folder
        while (current != null) { if (File(current, ".git").exists()) return true; current = current.parentFile }
        return false
    }

    companion object {
        fun metadataIdentity(path: String): String? = runCatching {
            val attrs = Files.readAttributes(File(path, ".git").toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(attrs.isDirectory && !attrs.isSymbolicLink)
            attrs.fileKey()?.toString()
        }.getOrNull()

        /** Used only on a freshly allocated owned backup/quarantine, or an explicitly approved managed clone. */
        fun deleteWithoutFollowingLinks(directory: File) {
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }
}

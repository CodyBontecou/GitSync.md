package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.google.gson.Gson
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryState
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

data class RepositoryMovePlan(val id: String, val source: String, val destination: String, val recoveryBase: String,
    val sourceKey: String, val gitKey: String, val parentKey: String, val fingerprint: String,
    val bytes: Long, val files: Int, val copyRequired: Boolean, val trackModes: Boolean) {
    val stagingOwner: File get() = File(File(destination).parentFile, ".gitsync-move-$id")
    val staging: File get() = File(stagingOwner, "worktree")
    val recoveryOwner: File get() = File(recoveryBase, id)
    val recovery: File get() = File(recoveryOwner, "worktree")
    val paths: List<String> get() = listOf(source, destination, staging.path, recovery.path)
}
enum class RepositoryMovePhase { PREPARED, COPY_READY, SOURCE_RETAINED, PUBLISHED, COMMITTED, DISCARDING_RECOVERY }
data class RepositoryMoveJournal(val plan: RepositoryMovePlan, val payload: String,
    val phase: RepositoryMovePhase = RepositoryMovePhase.PREPARED, val version: Int = 1,
    val stagingOwnerKey: String? = null, val stagingKey: String? = null, val stagingGitKey: String? = null,
    val recoveryOwnerKey: String? = null)
data class RepositoryMoveSnapshot(val fingerprint: String, val bytes: Long, val files: Int)

/** Durable, forward-recoverable moves. Cross-filesystem originals survive as explicit recovery copies. */
class RepositoryMove(private val journalDirectory: File,
    private val sameVolume: (File, File) -> Boolean = { a, b -> Files.getFileStore(a.toPath()) == Files.getFileStore(b.toPath()) },
    private val rename: (File, File) -> Unit = { a, b -> Files.move(a.toPath(), b.toPath()); Unit },
    private val syncDirectory: (File) -> Unit = { folder -> FileChannel.open(folder.toPath(), StandardOpenOption.READ).use { it.force(true) } },
    private val checkpoint: (RepositoryMoveJournal) -> Unit = {}) {
    private val gson = Gson()

    suspend fun prepare(source: String, parent: String, recoveryBase: String, folderName: String = File(source).name): RepositoryMovePlan {
        require(folderName.isNotBlank() && folderName !in listOf(".", "..") && folderName.none { it == '/' || it == '\\' || it == '\u0000' }) { "Choose a valid destination folder name" }
        val from = File(source).canonicalFile
        val toParent = File(parent).canonicalFile
        val destination = File(toParent, folderName)
        val recoveries = File(recoveryBase).canonicalFile
        val id = UUID.randomUUID().toString()
        val retained = File(File(recoveries, id), "worktree")
        return RepositoryOperationCoordinator.withRepositories(listOf(from.path, destination.path)) {
            validateSource(from)
            require(toParent.isDirectory && !Files.isSymbolicLink(toParent.toPath())) { "Choose an available destination folder" }
            require(!related(from, destination) && !related(from, retained) && !related(destination, retained)) { "Choose separate source, destination and recovery folders" }
            require(!hasGitAncestor(toParent)) { "Choose a destination outside another Git repository" }
            requireAbsent(destination)
            check(recoveries.isDirectory || recoveries.mkdirs()) { "Cannot create the app's move recovery folder" }
            require(!hasGitAncestor(recoveries)) { "Recovery copies must be outside Git working copies" }
            val trackModes = com.bontecou.syncmd.services.git.lfs.GitLfs.open(from).use { it.repository.config.getBoolean("core", null, "filemode", true) }
            val snapshot = snapshot(from, trackModes)
            RepositoryMovePlan(id, from.path, destination.path, recoveries.path,
                key(from), GitTrackingRemoval.metadataIdentity(from.path) ?: error("Cannot identify Git metadata"), key(toParent),
                snapshot.fingerprint, snapshot.bytes, snapshot.files, !sameVolume(from, toParent), trackModes)
        }
    }

    /** Once confirmed, finish the filesystem/catalog critical section even if its UI scope is cancelled. */
    suspend fun execute(plan: RepositoryMovePlan, payload: String, beforeMove: () -> Unit = {}, commit: (RepositoryMoveJournal) -> Unit): RepositoryMoveJournal =
        withContext(NonCancellable) {
            RepositoryOperationCoordinator.withRepositories(plan.paths) {
                require(!file(plan.id).exists()) { "This move already has recovery state. Resume it instead." }
                validatePlan(plan)
                validateSource(File(plan.source))
                requireIdentity(File(plan.source), plan.sourceKey, plan.gitKey)
                require(snapshot(File(plan.source), plan.trackModes).fingerprint == plan.fingerprint) { "The working copy changed after review. Review the move again." }
                requireParent(plan); requireAbsent(File(plan.destination))
                beforeMove()
                val journal = RepositoryMoveJournal(plan, payload)
                save(journal)
                resumeLocked(journal, commit)
            }
        }

    suspend fun resume(id: String, beforeMove: (RepositoryMoveJournal) -> Unit = {}, commit: (RepositoryMoveJournal) -> Unit): RepositoryMoveJournal = withContext(NonCancellable) {
        val journal = read(id)
        RepositoryOperationCoordinator.withRepositories(journal.plan.paths) {
            val current = read(id)
            if (current.phase !in listOf(RepositoryMovePhase.COMMITTED, RepositoryMovePhase.DISCARDING_RECOVERY) && !isPublished(current)) beforeMove(current)
            resumeLocked(current, commit)
        }
    }

    fun journals(): List<RepositoryMoveJournal> = journalDirectory.listFiles().orEmpty()
        .filter { it.name.matches(Regex("[a-f0-9-]{36}\\.json")) }.sortedBy { it.name }.map { read(it.name.removeSuffix(".json")) }

    /** Cancellation never deletes a published destination or an unrelated folder at either path. */
    suspend fun cancel(id: String) = withContext(NonCancellable) {
        val initial = read(id)
        RepositoryOperationCoordinator.withRepositories(initial.plan.paths) {
            val journal = read(id); val plan = journal.plan
            require(!isPublished(journal)) { "The working copy is already at its destination. Finish recovery instead." }
            if (matches(File(plan.source), plan.sourceKey, plan.gitKey)) {
                cleanupStaging(journal)
            } else {
                requireIdentity(plan.recovery, plan.sourceKey, plan.gitKey)
                requireAbsent(File(plan.source))
                cleanupStaging(journal)
                rename(plan.recovery, File(plan.source))
            }
            cleanupRecoveryOwner(journal)
            removeJournal(plan.id)
        }
    }

    /** Only an unchanged retained original may be discarded, after checking the destination is usable. */
    suspend fun discardRecovery(id: String, afterDelete: () -> Unit = {}) = withContext(NonCancellable) {
        val journal = read(id)
        require(journal.phase in listOf(RepositoryMovePhase.COMMITTED, RepositoryMovePhase.DISCARDING_RECOVERY) && journal.plan.copyRequired) { "Finish the move before removing its recovery copy" }
        RepositoryOperationCoordinator.withRepositories(listOf(journal.plan.recovery.path, journal.plan.destination)) {
            require(isPublished(journal)) { "The destination changed. Keep the recovery copy and review the working copy." }
            RepositoryDiscovery().inspect(journal.plan.destination)
            if (journal.phase == RepositoryMovePhase.COMMITTED) {
                requireIdentity(journal.plan.recovery, journal.plan.sourceKey, journal.plan.gitKey)
                require(snapshot(journal.plan.recovery, journal.plan.trackModes).fingerprint == journal.plan.fingerprint) { "The recovery copy has changed. Keep it until its edits are reviewed." }
                save(journal.copy(phase = RepositoryMovePhase.DISCARDING_RECOVERY))
            }
            if (journal.plan.recovery.exists()) {
                require(key(journal.plan.recovery) == journal.plan.sourceKey) { "An unrelated recovery folder was kept" }
                GitTrackingRemoval.deleteWithoutFollowingLinks(journal.plan.recovery)
            }
            cleanupRecoveryOwner(journal)
            afterDelete()
            removeJournal(journal.plan.id)
        }
    }

    /** Called after application-level completion/consumption cleanup has also succeeded. */
    suspend fun acknowledge(id: String) {
        val journal = read(id)
        require(journal.phase == RepositoryMovePhase.COMMITTED)
        if (journal.plan.stagingOwner.exists()) RepositoryOperationCoordinator.withRepository(journal.plan.staging.path) { cleanupStaging(journal) }
        if (!journal.plan.copyRequired) removeJournal(id)
    }

    fun finishConsumedRecovery(id: String, replacement: RepositoryMoveJournal) {
        val old = read(id)
        require(old.phase == RepositoryMovePhase.COMMITTED && old.plan.copyRequired && replacement.phase == RepositoryMovePhase.COMMITTED)
        require(replacement.plan.source == old.plan.recovery.path && replacement.plan.sourceKey == old.plan.sourceKey && replacement.plan.gitKey == old.plan.gitKey)
        require(isPublished(replacement)) { "The restored copy cannot be identified" }
        require(!Files.exists(old.plan.recovery.toPath(), LinkOption.NOFOLLOW_LINKS)) { "An unrelated folder appeared at the old recovery path; it was kept" }
        cleanupRecoveryOwner(old)
        removeJournal(id)
    }

    fun read(id: String): RepositoryMoveJournal {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid move identity" }
        val f = file(id)
        require(!Files.isSymbolicLink(f.toPath())) { "Move recovery state cannot be a symbolic link" }
        val journal = gson.fromJson(f.readText(), RepositoryMoveJournal::class.java)
        require(journal.version == 1 && journal.plan.id == id) { "Unsupported move recovery state" }
        validatePlan(journal.plan)
        return journal
    }

    private suspend fun resumeLocked(initial: RepositoryMoveJournal, commit: (RepositoryMoveJournal) -> Unit): RepositoryMoveJournal {
        var journal = initial
        val plan = journal.plan
        if (journal.phase == RepositoryMovePhase.COMMITTED) { cleanupStaging(journal); return journal }
        require(journal.phase != RepositoryMovePhase.DISCARDING_RECOVERY) { "Resume recovery cleanup instead" }
        requireParent(plan)
        if (!isPublished(journal)) {
            requireAbsent(File(plan.destination))
            if (!plan.copyRequired) {
                requireIdentity(File(plan.source), plan.sourceKey, plan.gitKey)
                validateSource(File(plan.source))
                require(snapshot(File(plan.source), plan.trackModes).fingerprint == plan.fingerprint) { "The original changed. Cancel recovery and review the move again." }
                rename(File(plan.source), File(plan.destination))
            } else {
                if (matches(File(plan.source), plan.sourceKey, plan.gitKey)) {
                    validateSource(File(plan.source))
                    require(snapshot(File(plan.source), plan.trackModes).fingerprint == plan.fingerprint) { "The original changed. Cancel recovery and review the move again." }
                    cleanupStaging(journal)
                    Files.createDirectory(plan.stagingOwner.toPath())
                    Files.createDirectory(plan.staging.toPath())
                    journal = journal.copy(stagingOwnerKey = key(plan.stagingOwner), stagingKey = key(plan.staging), stagingGitKey = null)
                    save(journal)
                    copyTree(File(plan.source), plan.staging)
                    require(snapshot(plan.staging, plan.trackModes).fingerprint == plan.fingerprint &&
                        snapshot(File(plan.source), plan.trackModes).fingerprint == plan.fingerprint) { "Files changed or the destination cannot preserve them. The original was kept." }
                    journal = journal.copy(phase = RepositoryMovePhase.COPY_READY, stagingGitKey = GitTrackingRemoval.metadataIdentity(plan.staging.path))
                    save(journal)
                    if (!plan.recoveryOwner.exists()) Files.createDirectory(plan.recoveryOwner.toPath())
                    require(plan.recoveryOwner.listFiles().orEmpty().isEmpty()) { "The recovery folder is occupied; no files were replaced" }
                    journal = journal.copy(recoveryOwnerKey = key(plan.recoveryOwner))
                    save(journal)
                    requireParent(plan); requireAbsent(File(plan.destination)); requireAbsent(plan.recovery)
                    requireIdentity(File(plan.source), plan.sourceKey, plan.gitKey)
                    rename(File(plan.source), plan.recovery)
                    journal = journal.copy(phase = RepositoryMovePhase.SOURCE_RETAINED)
                    save(journal)
                }
                requireIdentity(plan.recovery, plan.sourceKey, plan.gitKey)
                requireIdentity(plan.staging, journal.stagingKey ?: error("Prepared copy is unavailable"), journal.stagingGitKey ?: error("Prepared Git metadata is unavailable"))
                require(snapshot(plan.recovery, plan.trackModes).fingerprint == plan.fingerprint && snapshot(plan.staging, plan.trackModes).fingerprint == plan.fingerprint) {
                    "A prepared copy changed. Both copies were kept; restore or review the original before continuing."
                }
                requireParent(plan); requireAbsent(File(plan.destination))
                rename(plan.staging, File(plan.destination))
            }
        }
        require(isPublished(journal)) { "The moved folder could not be identified; retained copies were kept" }
        journal = journal.copy(phase = RepositoryMovePhase.PUBLISHED)
        save(journal)
        commit(journal)
        journal = journal.copy(phase = RepositoryMovePhase.COMMITTED)
        save(journal)
        cleanupStaging(journal)
        return journal
    }

    private fun isPublished(journal: RepositoryMoveJournal): Boolean = if (journal.plan.copyRequired)
        journal.stagingKey != null && journal.stagingGitKey != null && matches(File(journal.plan.destination), journal.stagingKey, journal.stagingGitKey)
        else matches(File(journal.plan.destination), journal.plan.sourceKey, journal.plan.gitKey)

    private suspend fun cleanupStaging(journal: RepositoryMoveJournal) {
        val owner = journal.plan.stagingOwner
        if (!owner.exists()) return
        require(journal.stagingOwnerKey != null && key(owner) == journal.stagingOwnerKey) { "An unrecognized prepared folder was kept for review" }
        require(owner.listFiles().orEmpty().all { it.name == "worktree" }) { "Unexpected files in the prepared folder were kept" }
        if (journal.plan.staging.exists()) {
            require(journal.stagingKey != null && key(journal.plan.staging) == journal.stagingKey)
            if (journal.phase != RepositoryMovePhase.PREPARED) require(snapshot(journal.plan.staging, journal.plan.trackModes).fingerprint == journal.plan.fingerprint) { "The prepared copy has edits; it was kept for review" }
            GitTrackingRemoval.deleteWithoutFollowingLinks(journal.plan.staging)
        }
        check(owner.delete()) { "Could not remove the empty prepared folder" }
    }
    private fun cleanupRecoveryOwner(journal: RepositoryMoveJournal) {
        val owner = journal.plan.recoveryOwner
        if (!owner.exists()) return
        require(journal.recoveryOwnerKey != null && key(owner) == journal.recoveryOwnerKey)
        require(owner.listFiles().orEmpty().isEmpty()) { "Recovery files were kept" }
        check(owner.delete())
    }
    private fun validatePlan(plan: RepositoryMovePlan) {
        require(plan.id.matches(Regex("[a-f0-9-]{36}")))
        val source = File(plan.source); val target = File(plan.destination); val recovery = File(plan.recoveryBase)
        require(source.isAbsolute && target.isAbsolute && recovery.isAbsolute && target.name.isNotBlank())
        require(source.path == source.canonicalPath && target.path == target.canonicalPath && recovery.path == recovery.canonicalPath)
        require(!related(source, target) && !related(source, plan.recovery) && !related(target, plan.recovery)) { "Move locations overlap" }
    }
    private fun requireParent(plan: RepositoryMovePlan) {
        val parent = File(plan.destination).parentFile
        require(key(parent) == plan.parentKey && !hasGitAncestor(parent)) { "The destination folder changed. Choose it again." }
    }
    private suspend fun validateSource(source: File) {
        require(!Files.isSymbolicLink(source.toPath()) && !hasGitAncestor(source.parentFile)) { "Move a top-level working copy, not a linked or nested repository" }
        RepositoryDiscovery().inspect(source.path)
        com.bontecou.syncmd.services.git.lfs.GitLfs.open(source).use { git ->
            require(git.repository.repositoryState == RepositoryState.SAFE) { "Finish the active Git operation before moving this repository" }
            require(git.repository.config.getString("core", null, "worktree") == null &&
                listOf("commondir", "objects/info/alternates", "worktrees").none { File(git.repository.directory, it).exists() }) { "This working copy uses external Git metadata and cannot be moved safely" }
        }
    }
    private fun matches(folder: File, rootKey: String, gitKey: String) = runCatching { key(folder) == rootKey && GitTrackingRemoval.metadataIdentity(folder.path) == gitKey }.getOrDefault(false)
    private fun requireIdentity(folder: File, rootKey: String, gitKey: String) = require(matches(folder, rootKey, gitKey)) { "The working copy was replaced or is unavailable. Existing files were kept." }
    private fun requireAbsent(file: File) = require(!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "The destination already exists. Choose another folder; no existing files were replaced." }
    private fun key(folder: File): String {
        val attrs = Files.readAttributes(folder.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isDirectory && !attrs.isSymbolicLink) { "The folder was replaced or linked" }
        return attrs.fileKey()?.toString() ?: error("This filesystem cannot identify folders safely")
    }
    private fun related(a: File, b: File) = a.path == b.path || a.path.startsWith(b.path + "/") || b.path.startsWith(a.path + "/")
    private fun hasGitAncestor(folder: File?): Boolean {
        var current = folder
        while (current != null) { if (File(current, ".git").exists()) return true; current = current.parentFile }
        return false
    }
    private fun file(id: String) = File(journalDirectory, "$id.json")
    private fun save(journal: RepositoryMoveJournal) {
        check(journalDirectory.isDirectory || journalDirectory.mkdirs())
        require(!Files.isSymbolicLink(journalDirectory.toPath()))
        val temporary = Files.createTempFile(journalDirectory.toPath(), ".move-", ".tmp").toFile()
        try {
            FileOutputStream(temporary).use { it.write(gson.toJson(journal).toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temporary.toPath(), file(journal.plan.id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(journalDirectory)
        } finally { temporary.delete() }
        checkpoint(journal)
    }
    private fun removeJournal(id: String) { check(file(id).delete()); syncDirectory(journalDirectory) }

    companion object {
        suspend fun snapshot(root: File, trackModes: Boolean): RepositoryMoveSnapshot {
            val context = currentCoroutineContext()
            val entries = mutableListOf<Pair<String, String>>()
            var bytes = 0L; var count = 0
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive(); require(!attrs.isSymbolicLink)
                    entries += root.toPath().relativize(dir).toString() to "D"
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    val path = root.toPath().relativize(file).toString()
                    require(!(path.startsWith(".git/") && (path.endsWith(".lock") || path == ".git/gc.pid"))) { "Git is busy. Retry after its other operation finishes." }
                    val value = if (attrs.isSymbolicLink) "L:${Files.readSymbolicLink(file)}" else {
                        require(attrs.isRegularFile) { "This working copy contains a special file that cannot be moved safely" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                            val buffer = ByteBuffer.allocate(64 * 1024)
                            while (input.read(buffer) != -1) { context.ensureActive(); buffer.flip(); digest.update(buffer); buffer.clear() }
                        }
                        bytes = Math.addExact(bytes, attrs.size())
                        "F:${attrs.size()}:${digest.digest().hex()}:${trackModes && Files.isExecutable(file)}"
                    }
                    entries += path to value
                    count++
                    return FileVisitResult.CONTINUE
                }
            })
            val digest = MessageDigest.getInstance("SHA-256")
            entries.sortedBy { it.first }.forEach { (path, value) -> digest.update((path + "\u0000" + value + "\u0000").toByteArray(Charsets.UTF_8)) }
            return RepositoryMoveSnapshot(digest.digest().hex(), bytes, count)
        }
        private suspend fun copyTree(source: File, target: File) {
            val context = currentCoroutineContext()
            Files.walkFileTree(source.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    if (dir != source.toPath()) Files.createDirectory(target.toPath().resolve(source.toPath().relativize(dir)))
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    context.ensureActive()
                    val destination = target.toPath().resolve(source.toPath().relativize(file))
                    if (attrs.isSymbolicLink) Files.createSymbolicLink(destination, Files.readSymbolicLink(file)) else {
                        require(attrs.isRegularFile)
                        FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                            FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                                val buffer = ByteBuffer.allocate(64 * 1024)
                                while (input.read(buffer) != -1) {
                                    context.ensureActive(); buffer.flip()
                                    while (buffer.hasRemaining()) output.write(buffer)
                                    buffer.clear()
                                }
                                output.force(true)
                            }
                        }
                        destination.toFile().setExecutable(Files.isExecutable(file), false)
                        runCatching { Files.setLastModifiedTime(destination, attrs.lastModifiedTime()) }
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}

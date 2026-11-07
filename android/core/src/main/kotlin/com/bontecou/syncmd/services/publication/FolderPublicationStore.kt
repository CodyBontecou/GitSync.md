package com.bontecou.syncmd.services.publication

import com.bontecou.syncmd.services.repositories.RepositoryIdentity
import com.google.gson.*
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.*
import java.util.UUID

enum class FolderPublicationPhase {
    REVIEW, PREPARING, PREPARED, CREATING_REMOTE, CREATION_UNKNOWN,
    REMOTE_CREATED, PUSHING, PUSH_UNKNOWN, PUBLISHED, COMPLETED
}

/** Private app progress. Tokens and server error bodies must never be added to this record. */
data class FolderPublicationRecord(
    val input: FolderPublicationInput,
    val folderUri: String?,
    val account: PublicationAccount,
    val repositoryName: String,
    val isPrivate: Boolean = true,
    val phase: FolderPublicationPhase = FolderPublicationPhase.REVIEW,
    val prepared: FolderPreparedCommit? = null,
    val remote: PublicationRemote? = null,
    val problem: PublicationApiProblem? = null
) {
    val id: String get() = input.workflow
    // Reading interrupted progress is deliberately conservative and never schedules a mutation.
    val recoveryPhase: FolderPublicationPhase get() = when (phase) {
        FolderPublicationPhase.CREATING_REMOTE -> FolderPublicationPhase.CREATION_UNKNOWN
        FolderPublicationPhase.PUSHING -> FolderPublicationPhase.PUSH_UNKNOWN
        else -> phase
    }
}

class FolderPublicationJournalException : IOException("Publication progress is unreadable or incompatible. The saved file was preserved.")
class FolderPublicationStale : IOException("Publication progress changed. Reopen the saved progress before continuing.")
class FolderPublicationOverlap : IOException("This folder overlaps an unfinished publication. Resume that publication first.")

/**
 * Place this store in app-private no-backup storage, outside every selected working folder.
 * Process and OS locks serialize snapshots across instances; expected-record replacement prevents
 * an old screen from overwriting an uncertain network result. Corruption never becomes an empty list.
 */
class FolderPublicationStore(private val directory: File,
    private val syncDirectory: (File) -> Unit = { folder -> FileChannel.open(folder.toPath(), READ).use { it.force(true) } }
) {
    internal val operationKey: String get() = directory.canonicalPath
    private data class Envelope(val schema: Int, val records: List<FolderPublicationRecord>)
    private val gson = GsonBuilder().serializeNulls().create()
    private val file get() = File(directory, "progress.json")

    fun load(): List<FolderPublicationRecord> = locked { read() }

    fun insert(record: FolderPublicationRecord) = locked {
        require(record.phase == FolderPublicationPhase.REVIEW)
        val records = read()
        if (records.any { it.id == record.id }) throw FolderPublicationStale()
        write(records + record)
    }

    fun replace(expected: FolderPublicationRecord, replacement: FolderPublicationRecord) = locked {
        val records = read()
        if (expected.id != replacement.id || records.singleOrNull { it.id == expected.id } != expected) throw FolderPublicationStale()
        transition(expected, replacement)
        write(records.map { if (it.id == expected.id) replacement else it })
    }

    /** Call only after access and owned-commit validation under the selected folder's lease. */
    fun rebind(expected: FolderPublicationRecord, folderUri: String?,
        path: String = expected.input.review.path, identity: String = expected.input.review.identity) = locked {
        val records = read()
        if (records.singleOrNull { it.id == expected.id } != expected) throw FolderPublicationStale()
        require(expected.phase != FolderPublicationPhase.COMPLETED)
        if (path != expected.input.review.path || identity != expected.input.review.identity) {
            require(expected.prepared != null) { "Prepare the original folder before reconnecting to another directory" }
        }
        val next = expected.copy(folderUri = folderUri,
            input = expected.input.copy(review = expected.input.review.copy(path = path, identity = identity)))
        write(records.map { if (it.id == expected.id) next else it })
    }

    /** REVIEW precedes every owned Git/network mutation, so discarding it needs no filesystem rollback. */
    fun discardReview(expected: FolderPublicationRecord) = locked {
        val records = read()
        if (records.singleOrNull { it.id == expected.id } != expected) throw FolderPublicationStale()
        require(expected.phase == FolderPublicationPhase.REVIEW)
        write(records.filterNot { it.id == expected.id })
    }

    /** For import/move/remove guards. Callers must retain their operation lease through the action. */
    fun requireAvailable(path: String, exceptWorkflow: String? = null) = locked {
        val canonical = File(path).canonicalPath
        if (read().any { it.phase != FolderPublicationPhase.COMPLETED && it.id != exceptWorkflow && overlaps(canonical, it.input.review.path) }) throw FolderPublicationOverlap()
    }

    private fun transition(old: FolderPublicationRecord, next: FolderPublicationRecord) {
        val allowed = when (old.phase) {
            FolderPublicationPhase.REVIEW -> setOf(FolderPublicationPhase.REVIEW, FolderPublicationPhase.PREPARING)
            FolderPublicationPhase.PREPARING -> setOf(FolderPublicationPhase.PREPARING, FolderPublicationPhase.PREPARED)
            FolderPublicationPhase.PREPARED -> setOf(FolderPublicationPhase.PREPARED, FolderPublicationPhase.CREATING_REMOTE)
            FolderPublicationPhase.CREATING_REMOTE -> setOf(FolderPublicationPhase.CREATION_UNKNOWN, FolderPublicationPhase.REMOTE_CREATED, FolderPublicationPhase.PREPARED)
            FolderPublicationPhase.CREATION_UNKNOWN -> setOf(FolderPublicationPhase.CREATION_UNKNOWN, FolderPublicationPhase.REMOTE_CREATED)
            FolderPublicationPhase.REMOTE_CREATED -> setOf(FolderPublicationPhase.REMOTE_CREATED, FolderPublicationPhase.PUSHING)
            FolderPublicationPhase.PUSHING -> setOf(FolderPublicationPhase.PUSH_UNKNOWN, FolderPublicationPhase.PUBLISHED)
            FolderPublicationPhase.PUSH_UNKNOWN -> setOf(FolderPublicationPhase.PUSH_UNKNOWN, FolderPublicationPhase.PUSHING, FolderPublicationPhase.PUBLISHED)
            FolderPublicationPhase.PUBLISHED -> setOf(FolderPublicationPhase.PUBLISHED, FolderPublicationPhase.COMPLETED)
            FolderPublicationPhase.COMPLETED -> setOf(FolderPublicationPhase.COMPLETED)
        }
        require(next.phase in allowed)
        require(old.input.review.path == next.input.review.path && old.input.workflow == next.input.workflow)
        if (old.phase != FolderPublicationPhase.REVIEW) {
            require(old.input == next.input && old.account == next.account)
            if (old.phase != FolderPublicationPhase.PREPARED || next.phase != FolderPublicationPhase.PREPARED) {
                require(old.repositoryName == next.repositoryName && old.isPrivate == next.isPrivate)
            }
            require(old.folderUri == next.folderUri) // Reconnection needs a separately validated binding operation.
        }
        if (old.prepared != null) require(next.prepared == old.prepared)
        if (old.remote != null) require(next.remote == old.remote)
        if (old.phase == FolderPublicationPhase.CREATING_REMOTE && next.phase == FolderPublicationPhase.PREPARED) {
            require(next.problem in setOf(PublicationApiProblem.AUTHENTICATION, PublicationApiProblem.ACCOUNT,
                PublicationApiProblem.FORBIDDEN, PublicationApiProblem.RATE_LIMIT, PublicationApiProblem.VALIDATION, PublicationApiProblem.UNAVAILABLE))
        }
    }

    private fun read(): List<FolderPublicationRecord> {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return emptyList()
        try {
            regular(file)
            val bytes = Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(output.size() <= MAX_BYTES - count)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val tree = PublicationJson.parse(bytes, MAX_BYTES, 2_000_000)
            val envelope = gson.fromJson(tree, Envelope::class.java)
            // Gson alone permits coercions, absent fields and unknown fields. Reject all of them.
            exactTypes(tree, gson.toJsonTree(envelope))
            require(envelope.schema == 1)
            validate(envelope.records)
            return envelope.records
        } catch (_: Exception) { throw FolderPublicationJournalException() }
    }

    private fun write(records: List<FolderPublicationRecord>) {
        validate(records)
        val bytes = gson.toJson(Envelope(1, records)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Publication progress exceeds the private storage limit" }
        PublicationJson.parse(bytes, MAX_BYTES, 2_000_000)
        val temporary = Files.createTempFile(directory.toPath(), ".progress-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) regular(file)
            Files.move(temporary, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            // Persist the name change as well as its contents on supported app-private filesystems.
            syncDirectory(directory)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun validate(records: List<FolderPublicationRecord>) {
        require(records.size <= 100 && records.map { it.id }.distinct().size == records.size)
        records.forEach { record ->
            val input = record.input; val review = input.review
            require(UUID.fromString(input.workflow).toString() == input.workflow)
            require(File(review.path).isAbsolute && File(review.path).canonicalPath == review.path && review.path != File.separator)
            require(review.identity.isNotBlank() && review.identity.length <= 4096)
            require(review.files.size <= FolderPublicationLimits.INSPECTED_FILES && review.files.map { it.path }.distinct().size == review.files.size)
            review.files.forEach { entry ->
                FolderPublicationFiles.path(entry.path)
                require(entry.size >= 0 && entry.digest.matches(Regex("[a-f0-9]{64}")))
                require(entry.issue == null || entry.issue.length <= 4096)
            }
            require(review.warnings.size <= 100 && review.warnings.all { it.length <= 4096 })
            val eligible = review.files.filter { it.eligible }.map { it.path }.toSet()
            require(input.selected.all { it in eligible })
            require(input.authorName.length <= 4096 && input.authorEmail.length <= 4096 && input.message.length <= 65536)
            require(record.account.id > 0); segment(record.account.login); segment(record.repositoryName)
            record.folderUri?.let { require(it.length <= 16384 && URI(it).scheme == "content" && it.none(Char::isISOControl)) }
            if (record.phase != FolderPublicationPhase.REVIEW) {
                FolderPublicationLimits.estimate(review.files.filter { it.path in input.selected })
                RepositoryIdentity.validated(input.authorName, input.authorEmail)
                require(input.message.isNotBlank() && '\u0000' !in input.message)
            }
            val needsCommit = record.phase !in setOf(FolderPublicationPhase.REVIEW, FolderPublicationPhase.PREPARING)
            require((record.prepared != null) == needsCommit)
            record.prepared?.let { require(it.commit.matches(Regex("[a-f0-9]{40}")) && it.tree.matches(Regex("[a-f0-9]{40}"))) }
            val needsRemote = record.phase in setOf(FolderPublicationPhase.REMOTE_CREATED, FolderPublicationPhase.PUSHING,
                FolderPublicationPhase.PUSH_UNKNOWN, FolderPublicationPhase.PUBLISHED, FolderPublicationPhase.COMPLETED)
            require((record.remote != null) == needsRemote)
            record.remote?.let { remote ->
                require(remote.id > 0 && remote.owner.id == record.account.id && remote.owner.login.equals(record.account.login, true) &&
                    remote.name.equals(record.repositoryName, true) && remote.isPrivate == record.isPrivate)
                segment(remote.owner.login); segment(remote.name)
                for ((url, suffix) in listOf(remote.htmlUrl to "", remote.cloneUrl to ".git")) {
                    val uri = URI(url)
                    require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.rawUserInfo == null &&
                        uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.equals("/${remote.owner.login}/${remote.name}$suffix", true))
                }
            }
            // Private progress must never be stored inside a selected folder (including completed ones).
            require(!overlaps(directory.canonicalPath, review.path))
        }
        val active = records.filter { it.phase != FolderPublicationPhase.COMPLETED }
        active.forEachIndexed { index, a -> active.drop(index + 1).forEach { b ->
            if (overlaps(a.input.review.path, b.input.review.path)) throw FolderPublicationOverlap()
        } }
    }

    private fun exactTypes(actual: JsonElement, encoded: JsonElement) {
        when {
            encoded.isJsonNull -> require(actual.isJsonNull)
            encoded.isJsonObject -> {
                require(actual.isJsonObject && actual.asJsonObject.keySet() == encoded.asJsonObject.keySet())
                encoded.asJsonObject.entrySet().forEach { (key, value) -> exactTypes(actual.asJsonObject.get(key), value) }
            }
            encoded.isJsonArray -> {
                require(actual.isJsonArray && actual.asJsonArray.size() == encoded.asJsonArray.size())
                encoded.asJsonArray.forEachIndexed { index, value -> exactTypes(actual.asJsonArray[index], value) }
            }
            else -> {
                val expected = encoded.asJsonPrimitive
                when {
                    expected.isString -> require(PublicationJson.text(actual) == expected.asString)
                    expected.isBoolean -> require(PublicationJson.bool(actual) == expected.asBoolean)
                    else -> require(PublicationJson.integer(actual) == expected.asLong)
                }
            }
        }
    }

    private fun regular(candidate: File) { require(Files.isRegularFile(candidate.toPath(), NOFOLLOW_LINKS)) }
    private fun segment(value: String) { require(value !in setOf(".", "..") && value.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) }
    private fun overlaps(a: String, b: String) = a == b || a.startsWith(b + File.separator) || b.startsWith(a + File.separator)
    private fun <T> locked(block: () -> T): T = synchronized(processLock) {
        require(directory.isAbsolute && directory.canonicalFile == directory.absoluteFile) { "Use private canonical publication storage" }
        Files.createDirectories(directory.toPath())
        require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
        val lock = File(directory, "progress.lock")
        FileChannel.open(lock.toPath(), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { block() }
        }
    }
    companion object {
        private val processLock = Any()
        private const val MAX_BYTES = 64 * 1024 * 1024
    }
}

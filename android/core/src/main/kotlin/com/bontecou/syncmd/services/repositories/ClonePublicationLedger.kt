package com.bontecou.syncmd.services.repositories

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Metadata only: credentials stay in the host's encrypted store. */
data class ClonePublication(val id: String, val staging: String, val parentIdentity: String, val completed: CompletedClone,
    val name: String, val alias: String, val history: String?, val created: Long, val registeredDate: Long? = null)

/** A review token pins private files, never authority inferred from damaged contents. */
data class CloneRecovery(val key: String, val id: String?, val publication: ClonePublication?, val damaged: Boolean,
    val dismissible: Boolean, val files: List<String>, val stamp: String)

/** Private no-backup receipts, written before the final rename. Never deletes a working copy. */
class ClonePublicationLedger(private val directory: File) {
    companion object { private val lock = Any(); private val active = mutableSetOf<String>() }
    private fun activeKey(id: String) = directory.canonicalPath + "/" + id
    fun activate(id: String) = synchronized(lock) { validId(id); check(active.add(activeKey(id))) { "Clone publication is already running" } }
    fun deactivate(id: String) = synchronized(lock) { active.remove(activeKey(id)); Unit }
    fun isActive(id: String) = synchronized(lock) { activeKey(id) in active }
    fun records(): List<ClonePublication> = inspect().mapNotNull { it.publication }
    /** Isolate a damaged entry so unrelated valid clones and ordinary settings can still recover. */
    fun inspect(): List<CloneRecovery> = synchronized(lock) {
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) return@synchronized emptyList()
        checkDirectory()
        val parent = key(directory)
        val files = directory.listFiles() ?: error("Clone recovery is unavailable")
        require(files.size <= 256) { "Clone recovery storage needs review" }
        files.sortedBy { it.name }.groupBy { file -> receiptId(file.name) ?: "unreadable-" + digest(file.name.toByteArray()) }.map { (group, entries) ->
            val id = receiptId(entries.first().name)
            val before = entries.map(::snapshot)
            val parsed = entries.map { runCatching { readFile(it) }.getOrNull() }
            val primary = if (id == null) 0 else entries.indexOfFirst { it.name == "$id.json" }.takeIf { it >= 0 } ?: 0
            val after = entries.map(::snapshot)
            val stable = before == after
            val record = parsed[primary].takeIf { stable && before[primary].second && after[primary].second }
            CloneRecovery(group, id, record, !stable || parsed.any { it == null }, stable && after.all { it.second },
                entries.map { it.name }, digest((parent + after.joinToString { it.first }).toByteArray()))
        }
    }
    fun hasReceipt(id: String): Boolean = synchronized(lock) {
        validId(id)
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) return@synchronized false
        checkDirectory()
        Files.exists(File(directory, "$id.json").toPath(), NOFOLLOW_LINKS) || Files.exists(File(directory, ".write-$id.tmp").toPath(), NOFOLLOW_LINKS)
    }
    /** Delete only the reviewed private metadata. Repository folders and credentials are never touched. */
    fun dismiss(review: CloneRecovery, beforeDelete: () -> Unit = {}) = synchronized(lock) {
        val current = inspect().singleOrNull { it.key == review.key }
        require(current == review && review.dismissible) { "Clone recovery changed. Review it again; files were kept." }
        require(review.id == null || !isActive(review.id)) { "This clone is still running" }
        beforeDelete()
        review.files.forEach { name -> Files.delete(File(directory, name).toPath()) }
    }
    private fun receiptId(name: String): String? {
        val id = when {
            name.endsWith(".json") -> name.removeSuffix(".json")
            name.startsWith(".write-") && name.endsWith(".tmp") -> name.removePrefix(".write-").removeSuffix(".tmp")
            else -> return null
        }
        return id.takeIf { runCatching { validId(it) }.isSuccess }
    }
    private fun digest(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun snapshot(file: File): Pair<String, Boolean> = try {
        val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val identity = requireNotNull(a.fileKey()).toString()
        val attributes = "${file.name}:$identity:${a.size()}:${a.lastModifiedTime()}:${a.isSymbolicLink}:${a.isDirectory}"
        if (a.isSymbolicLink) attributes to true
        else if (!a.isRegularFile || a.size() > 65536) attributes to false
        else {
            val bytes = java.nio.ByteBuffer.allocate(a.size().toInt())
            java.nio.channels.FileChannel.open(file.toPath(), setOf(java.nio.file.StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
                while (bytes.hasRemaining()) require(channel.read(bytes) > 0)
                require(channel.read(java.nio.ByteBuffer.allocate(1)) == -1)
            }
            val latest = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(latest.fileKey() == a.fileKey() && latest.size() == a.size() && latest.lastModifiedTime() == a.lastModifiedTime() && latest.isRegularFile)
            (attributes + ":" + digest(bytes.array())) to true
        }
    } catch (_: Exception) { (file.name + ":unavailable") to false }
    fun get(id: String): ClonePublication? = synchronized(lock) {
        validId(id); val file = File(directory, "$id.json")
        val candidate = if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) file else File(directory, ".write-$id.tmp")
        if (!Files.exists(candidate.toPath(), NOFOLLOW_LINKS)) null else { checkDirectory(); readFile(candidate) }
    }
    fun reserve(id: String, staging: String, destination: String, credentialRevision: String?, name: String, alias: String,
        history: String? = null): ClonePublication = synchronized(lock) {
        validId(id); require(get(id) == null) { "Review the previous clone recovery before retrying" }
        val target = File(destination).canonicalFile; val source = File(staging).canonicalFile
        require(source.path == staging && source.parentFile == target.parentFile && source.name.startsWith(".gitsync-clone-"))
        require(!Files.exists(target.toPath(), NOFOLLOW_LINKS))
        val parent = key(target.parentFile)
        val snapshot = CompletedClone.capture(source.path, credentialRevision).copy(path = target.path)
        val record = ClonePublication(id, source.path, parent, snapshot, name, alias, history, System.currentTimeMillis())
        validate(record)
        val inventory = inspect()
        val previous = inventory.mapNotNull { it.publication }
        if (inventory.size >= 128) {
            previous.filter { it.registeredDate != null && !isActive(it.id) }.sortedBy { it.created }.take(inventory.size - 127).forEach { forget(it.id) }
            require(inspect().size < 128) { "Review pending clone recoveries before starting another clone" }
        }
        write(record); record
    }
    /** Must be called under the destination repository lease and the host's credential checks. */
    fun publish(record: ClonePublication, credentialRevision: String?): CompletedClone = synchronized(lock) {
        require(get(record.id) == record && record.completed.credentialRevision == credentialRevision) { "Clone recovery changed; files were kept" }
        val target = File(record.completed.path)
        require(target.canonicalPath == target.path && key(target.parentFile) == record.parentIdentity) { "The clone destination changed" }
        if (!Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
            require(record.registeredDate == null)
            require(File(record.staging).canonicalPath == record.staging && !Files.isSymbolicLink(File(record.staging).toPath())) { "The staged clone location changed; files were kept" }
            require(CompletedClone.capture(record.staging, credentialRevision).copy(path = target.path) == record.completed) { "The staged clone changed; files were kept" }
            require(!Files.exists(target.toPath(), NOFOLLOW_LINKS))
            Files.move(File(record.staging).toPath(), target.toPath())
        }
        require(CompletedClone.capture(target.path, credentialRevision) == record.completed) { "The completed clone changed; files were kept" }
        record.completed
    }
    fun registered(id: String, date: Long) = synchronized(lock) {
        val record = requireNotNull(get(id)); require(date > 0 && (record.registeredDate == null || record.registeredDate == date))
        write(record.copy(registeredDate = date))
    }
    fun forget(id: String) = synchronized(lock) {
        validId(id)
        if (directory.exists()) {
            checkDirectory()
            Files.deleteIfExists(File(directory, "$id.json").toPath())
            Files.deleteIfExists(File(directory, ".write-$id.tmp").toPath())
        }
    }
    private fun key(file: File): String {
        require(file.canonicalPath == file.path)
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attributes.isDirectory && !attributes.isSymbolicLink)
        return requireNotNull(attributes.fileKey()).toString()
    }
    private fun checkDirectory() { require(directory.canonicalPath == directory.path); key(directory) }
    private fun validId(id: String) { require(UUID.fromString(id).toString() == id) }
    private fun validate(r: ClonePublication) {
        validId(r.id); require(r.created > 0 && (r.registeredDate == null || r.registeredDate > 0))
        require(r.name.isNotBlank() && r.name.length <= 120 && r.alias.length <= 8192 && (r.history?.length ?: 0) <= 65536)
        require(r.completed.path.length <= 8192 && File(r.completed.path).isAbsolute && File(r.completed.path).toPath().normalize().toString() == r.completed.path)
        require(File(r.staging).isAbsolute && File(r.staging).toPath().normalize().toString() == r.staging && File(r.staging).parentFile == File(r.completed.path).parentFile &&
            File(r.staging).name.startsWith(".gitsync-clone-"))
        require(listOf(r.parentIdentity, r.completed.rootIdentity, r.completed.gitIdentity).all { it.isNotBlank() && it.length <= 1024 })
    }
    private fun json(r: ClonePublication) = JsonObject().apply {
        addProperty("schema", 1); addProperty("id", r.id); addProperty("staging", r.staging); addProperty("parentIdentity", r.parentIdentity)
        addProperty("path", r.completed.path); addProperty("rootIdentity", r.completed.rootIdentity); addProperty("gitIdentity", r.completed.gitIdentity)
        addProperty("remote", r.completed.remote); addProperty("branch", r.completed.branch); addProperty("credentialRevision", r.completed.credentialRevision)
        addProperty("name", r.name); addProperty("alias", r.alias); addProperty("history", r.history); addProperty("created", r.created); addProperty("registeredDate", r.registeredDate)
    }.toString()
    private fun readFile(file: File): ClonePublication {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() <= 65536)
        val text = com.bontecou.syncmd.services.obsidian.ObsidianHandoff.read(file) // bounded, strict UTF-8, no-follow
        val j = JsonParser.parseString(text).asJsonObject
        require(j.get("schema").asInt == 1)
        fun string(name: String): String = j.get(name).also { require(it.isJsonPrimitive && it.asJsonPrimitive.isString) }.asString
        fun nullable(name: String): String? = if (j.get(name).isJsonNull) null else string(name)
        fun number(name: String): Long = j.get(name).also { require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString.matches(Regex("[0-9]+"))) }.asLong
        val record = ClonePublication(string("id"), string("staging"), string("parentIdentity"), CompletedClone(string("path"), string("rootIdentity"), string("gitIdentity"),
            nullable("remote"), nullable("branch"), nullable("credentialRevision")), string("name"), string("alias"), nullable("history"), number("created"),
            if (j.get("registeredDate").isJsonNull) null else number("registeredDate"))
        validate(record); require((file.name == "${record.id}.json" || file.name == ".write-${record.id}.tmp") && json(record) == text); return record
    }
    private fun write(record: ClonePublication) {
        validate(record)
        if (!directory.exists()) check(directory.mkdirs())
        checkDirectory()
        val text = json(record).toByteArray(Charsets.UTF_8); require(text.size <= 65536)
        val target = File(directory, "${record.id}.json")
        if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) readFile(target)
        val temp = File(directory, ".write-${record.id}.tmp").toPath()
        try {
            java.nio.channels.FileChannel.open(temp, setOf(java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, NOFOLLOW_LINKS)).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(text); while (bytes.hasRemaining()) channel.write(bytes); channel.force(true)
            }
            checkDirectory(); Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
}

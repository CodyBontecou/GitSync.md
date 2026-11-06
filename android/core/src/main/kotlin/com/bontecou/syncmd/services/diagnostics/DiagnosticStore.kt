package com.bontecou.syncmd.services.diagnostics

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/** One process-owned, bounded atomic file. Invalid files remain untouched until an explicit clear. */
class DiagnosticStore(private val directory: File, private val limit: Int = 500, private val byteLimit: Int = 256 * 1024) {
    init { require(limit in 1..500 && byteLimit in 1024..256 * 1024) }
    /** Copy of [export] limited to the given entries (Debug log › "Copy visible entries"). */
    fun export(entries: List<DiagnosticEntry>): String = entries.joinToString("\n") { line(it) }
    private fun line(it: DiagnosticEntry) = "[${Instant.ofEpochMilli(it.time)}] [${it.level}]${if (it.background) " [BG]" else ""} [${it.operation}] ${it.outcome}" +
        if (it.reason == DiagnosticReason.NONE) "" else " · ${it.reason}"
    private val file get() = File(directory, "events.json")
    private var loaded = false
    private var unreadable = false
    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries = _entries.asStateFlow()
    private val _storageProblem = MutableStateFlow(false)
    val storageProblem = _storageProblem.asStateFlow()

    @Synchronized fun load() {
        if (loaded) return
        loaded = true
        try {
            safeDirectory()
            // A killed writer cannot execute its finally block. This directory belongs only
            // to this single-process store; remove regular files in its temporary namespace.
            directory.listFiles()?.filter { it.name.startsWith(".events-") && it.name.endsWith(".tmp") &&
                Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }?.forEach { Files.deleteIfExists(it.toPath()) }
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
            require(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() <= byteLimit)
            val root = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
            require(root.get("version").asInt == 1)
            val rows = root.getAsJsonArray("entries")
            require(rows.size() <= limit)
            val ids = mutableSetOf<String>()
            _entries.value = rows.map { raw ->
                val row = raw.asJsonObject
                val id = row.get("id").asString
                require(UUID.fromString(id).toString() == id && ids.add(id))
                val time = row.get("time").asLong
                require(time in 0..253402300799999L)
                val vault = row.get("vault")?.takeIf { !it.isJsonNull }?.asString
                require(vault == null || vault.matches(Regex("[0-9a-f]{16}")))
                DiagnosticEntry(id, time, DiagnosticOperation.valueOf(row.get("operation").asString),
                    DiagnosticOutcome.valueOf(row.get("outcome").asString), DiagnosticReason.valueOf(row.get("reason").asString),
                    vault, row.get("background")?.takeIf { !it.isJsonNull }?.asBoolean ?: false)
            }
        } catch (_: Exception) { unreadable = true; _storageProblem.value = true }
    }
    @Synchronized fun append(entry: DiagnosticEntry) {
        load()
        if (unreadable) throw IOException("Stored diagnostics cannot be read. Clear the log to restart recording.")
        require(UUID.fromString(entry.id).toString() == entry.id && entry.time in 0..253402300799999L)
        val next = (_entries.value.filterNot { it.id == entry.id } + entry).takeLast(limit).toMutableList()
        while (encode(next).size > byteLimit && next.isNotEmpty()) next.removeAt(0)
        persist(next)
    }
    @Synchronized fun clear() { persist(emptyList()); unreadable = false; loaded = true }
    fun export(level: DiagnosticLevel? = null): String = entries.value.filter { level == null || it.level == level }
        .joinToString("\n") { "[${Instant.ofEpochMilli(it.time)}] [${it.level}]${if (it.background) " [BG]" else ""} [${it.operation}] ${it.outcome}" +
            if (it.reason == DiagnosticReason.NONE) "" else " · ${it.reason}" }

    private fun safeDirectory() {
        require(directory.absoluteFile == directory.canonicalFile && !Files.isSymbolicLink(directory.toPath()))
        if (!directory.exists()) check(directory.mkdirs()) { "Could not create diagnostics storage" }
        require(directory.isDirectory)
    }
    private fun encode(entries: List<DiagnosticEntry>): ByteArray = JsonObject().apply {
        addProperty("version", 1)
        add("entries", JsonArray().apply { entries.forEach { entry -> add(JsonObject().apply {
            addProperty("id", entry.id); addProperty("time", entry.time); addProperty("operation", entry.operation.name)
            addProperty("outcome", entry.outcome.name); addProperty("reason", entry.reason.name)
            entry.vault?.let { addProperty("vault", it) }
            if (entry.background) addProperty("background", true)
        }) } })
    }.toString().toByteArray(Charsets.UTF_8)
    private fun persist(next: List<DiagnosticEntry>) {
        var temporary: File? = null
        try {
            safeDirectory()
            require(!Files.isSymbolicLink(file.toPath()))
            val bytes = encode(next); check(bytes.size <= byteLimit)
            temporary = Files.createTempFile(directory.toPath(), ".events-", ".tmp").toFile()
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            _entries.value = next.toList(); _storageProblem.value = false
        } catch (failure: Exception) { _storageProblem.value = true; throw IOException("Diagnostics could not be saved. Existing records were kept.", failure) }
        finally { temporary?.let { Files.deleteIfExists(it.toPath()) } }
    }
}

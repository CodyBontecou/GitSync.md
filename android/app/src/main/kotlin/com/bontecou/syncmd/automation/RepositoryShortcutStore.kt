package com.bontecou.syncmd.automation

import android.content.Context
import com.bontecou.syncmd.services.automation.*
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

data class RepositoryShortcutRecord(val id: String, val created: Long, val plan: RepositoryActionPlan,
    val action: RepositoryAction, val message: String, val account: String, val enabled: Boolean = true, val background: Boolean = false)

/** Private authorization, never launcher extras or backup. Canonical schema checks preserve corrupt records. */
class RepositoryShortcutStore(context: Context) {
    private val prefs = context.getSharedPreferences("repository_shortcuts", Context.MODE_PRIVATE)
    fun records(): List<RepositoryShortcutRecord> = synchronized(lock) { read().values.map(::decode) }
    fun get(id: String): RepositoryShortcutRecord = synchronized(lock) {
        validId(id); decode(read()[id] ?: error("Shortcut authorization is unavailable"))
    }
    fun create(plan: RepositoryActionPlan, action: RepositoryAction, message: String, account: String, background: Boolean = false): RepositoryShortcutRecord = synchronized(lock) {
        val values = read(); val frozen = plan.copy(head = null)
        values.values.map(::decode).firstOrNull { it.enabled && it.plan == frozen && it.action == action && it.message == message && it.account == account && it.background == background }
            ?: RepositoryShortcutRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), frozen, action, message, account, background = background).also {
                require(values.size < 256) { "Remove unused shortcut authorizations before adding more" }
                save(values + (it.id to encode(it)))
            }
    }
    fun disable(id: String) = synchronized(lock) {
        val values = read(); val old = decode(values[id] ?: error("Shortcut authorization is unavailable"))
        save(values + (id to encode(old.copy(enabled = false))))
    }
    fun forget(id: String) = synchronized(lock) {
        val values = read(); val old = decode(values[id] ?: error("Shortcut authorization is unavailable"))
        require(!old.enabled); save(values - id)
    }
    /** Called only from the verified move's catalog commit, after credential relocation. Idempotent on recovery. */
    fun relocate(source: String, destination: String, sourceKey: String, sourceRegistration: String, destinationRegistration: String,
        sourceCredential: String?, destinationCredential: String?,
        destinationAccount: String) = synchronized(lock) {
        val values = read()
        val key = Files.readAttributes(File(destination).toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString()
            ?: error("Cannot identify the moved repository")
        val next = values.mapValues { (_, raw) ->
            val old = decode(raw)
            val accountMatches = old.account == destinationAccount || old.account == "saved:${sourceCredential.orEmpty()}" && destinationAccount == "saved:${destinationCredential.orEmpty()}"
            if (old.enabled && old.plan.target.path == source && old.plan.identity == sourceKey &&
                old.plan.target.registration == sourceRegistration &&
                old.plan.credentialRevision == sourceCredential && accountMatches) {
                encode(old.copy(plan = old.plan.copy(target = old.plan.target.copy(path = destination, registration = destinationRegistration), identity = key,
                    credentialRevision = destinationCredential), account = destinationAccount))
            } else raw
        }
        if (next != values) save(next)
    }
    private fun read(): Map<String, String> {
        val values = prefs.all
        require(values.size <= 256)
        return values.mapValues { (id, value) -> validId(id); require(value is String && value.length <= 256 * 1024); require(decode(value).id == id); value }
    }
    private fun save(values: Map<String, String>) {
        values.forEach { (id, raw) -> require(decode(raw).id == id) }
        val before = prefs.all
        fun write(map: Map<String, *>) = prefs.edit().clear().also { editor -> map.forEach { (id, raw) -> require(raw is String); editor.putString(id, raw) } }.commit()
        if (!write(values)) {
            check(write(before)) { "Shortcut recovery could not be saved" }
            error("Could not save shortcut authorization")
        }
    }
    internal fun encode(record: RepositoryShortcutRecord): String = JSONObject().apply {
        put("schema", if (record.background) 2 else 1); if (record.background) put("background", true); put("id", record.id); put("created", record.created); put("enabled", record.enabled)
        put("action", record.action.name); put("message", record.message); put("account", record.account)
        val p = record.plan
        put("name", p.target.name); put("path", p.target.path); put("registration", p.target.registration); put("identity", p.identity)
        put("branch", p.branch ?: JSONObject.NULL); put("remote", p.remote ?: JSONObject.NULL); put("remoteBranch", p.remoteBranch ?: JSONObject.NULL)
        put("fetchUrl", p.fetchUrl ?: JSONObject.NULL); put("pushUrl", p.pushUrl ?: JSONObject.NULL); put("credentialRevision", p.credentialRevision ?: JSONObject.NULL)
    }.toString()
    internal fun decode(raw: String): RepositoryShortcutRecord {
        require(raw.length <= 256 * 1024)
        val j = JSONObject(raw)
        require(j.get("schema") in listOf(1, 2) && j.get("enabled") is Boolean)
        val background = j.get("schema") == 2
        if (background) require(j.get("background") == true)
        fun string(key: String): String = j.get(key).let { require(it is String && it.length <= 65536); it }
        fun nullable(key: String): String? = if (j.get(key) === JSONObject.NULL) null else string(key)
        val id = string("id"); validId(id)
        val created = j.get("created"); require(created is Number && (created is Long || created is Int) && created.toLong() > 0)
        val path = string("path"); require(File(path).isAbsolute && File(path).canonicalPath == path)
        val name = string("name"); val identity = string("identity"); val account = string("account")
        require(name.isNotBlank() && identity.isNotBlank() && account.isNotBlank())
        val message = string("message"); require(message.isNotBlank() && '\u0000' !in message)
        val p = RepositoryActionPlan(PullAllTarget(path, name, string("registration")), identity, nullable("branch"), null,
            nullable("remote"), nullable("remoteBranch"), nullable("fetchUrl"), nullable("pushUrl"), nullable("credentialRevision"))
        val record = RepositoryShortcutRecord(id, created.toLong(), p, RepositoryAction.valueOf(string("action")), message, account, j.getBoolean("enabled"), background)
        require(encode(record) == raw) { "Shortcut authorization is incompatible; its saved record was preserved" }
        return record
    }
    private fun validId(id: String) { require(UUID.fromString(id).toString() == id) }
    companion object { private val lock = Any() }
}

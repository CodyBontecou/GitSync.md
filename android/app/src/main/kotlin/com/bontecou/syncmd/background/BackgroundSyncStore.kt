package com.bontecou.syncmd.background

import android.content.Context
import android.content.SharedPreferences
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.background.*
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Private, backup-excluded automatic-write consent. No credential value is stored here. */
@Singleton
class BackgroundSyncStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("background_sync", Context.MODE_PRIVATE)
    val changes get() = changed
    fun read(): BackgroundConfiguration = synchronized(lock) {
        val raw = prefs.getString("state", null) ?: return@synchronized BackgroundConfiguration()
        require(raw.length <= 1024 * 1024)
        val json = JSONObject(raw); require(json.getInt("schema") == 1)
        val entries = json.getJSONArray("policies"); require(entries.length() <= 1000)
        val policies = (0 until entries.length()).map { index ->
            val p = entries.getJSONObject(index); val h = p.getJSONObject("health")
            fun optional(j: JSONObject, name: String) = if (j.isNull(name)) null else j.getString(name)
            val health = BackgroundHealth(BackgroundHealthKind.valueOf(h.getString("kind")), h.getLong("attempt"), h.getLong("success"), h.getLong("transfer"),
                optional(h, "head"), optional(h, "outcome")?.let(RepositoryActionOutcome::valueOf), optional(h, "problem")?.let(RepositoryActionProblem::valueOf))
            require(listOf(health.attemptedAt, health.succeededAt, health.transferredAt).all { it >= 0 })
            BackgroundPolicy(p.getString("id"), PullAllTarget(p.getString("path"), p.getString("name"), p.getString("registration")),
                p.getString("identity"), p.getString("branch"), p.getString("remote"), p.getString("upstream"), p.getString("fetch"), p.getString("push"),
                optional(p, "credentialRevision"), p.getBoolean("included"), p.getBoolean("wifi"), p.getBoolean("power"), health)
        }
        require(policies.map { it.target.path }.distinct().size == policies.size)
        BackgroundConfiguration(json.getLong("generation"), json.getBoolean("pull"), json.getBoolean("push"), policies.associateBy { it.target.path })
    }
    fun configure(expectedGeneration: Long, change: (BackgroundConfiguration) -> BackgroundConfiguration): BackgroundConfiguration = synchronized(lock) {
        val before = read(); require(before.generation == expectedGeneration) { "Background Sync settings changed" }
        change(before).copy(generation = Math.addExact(before.generation, 1)).also(::write)
    }
    fun record(generation: Long, policy: BackgroundPolicy, health: BackgroundHealth) = synchronized(lock) {
        val current = read(); val old = current.policies[policy.target.path]
        if (generation == current.generation && old?.id == policy.id) write(current.copy(policies = current.policies + (policy.target.path to old.copy(health = health))))
    }
    fun recoveryVersion(): String? = synchronized(lock) {
        if (runCatching { read() }.isSuccess) null else fingerprint()
    }
    fun resetCorrupt(expectedVersion: String) = synchronized(lock) {
        require(recoveryVersion() == expectedVersion) { "Background Sync data changed. Reload first." }
        val before = prefs.all.toMap()
        if (!prefs.edit().clear().commit()) { restore(before); error("Could not reset Background Sync settings") }
        changed.value = changed.value + 1
    }
    private fun fingerprint(): String {
        val values = JSONObject()
        prefs.all.toSortedMap().forEach { (name, value) -> values.put(name, JSONObject().put("type", value?.javaClass?.name)
            .put("value", if (value is Set<*>) org.json.JSONArray(value.map { it.toString() }.sorted()) else value)) }
        return MessageDigest.getInstance("SHA-256").digest(values.toString().toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun write(value: BackgroundConfiguration) {
        val json = JSONObject().put("schema", 1).put("generation", value.generation).put("pull", value.pull).put("push", value.push)
        json.put("policies", org.json.JSONArray().apply { value.policies.values.sortedBy { it.target.path }.forEach { p -> put(JSONObject().apply {
            put("id", p.id); put("path", p.target.path); put("name", p.target.name); put("registration", p.target.registration); put("identity", p.identity)
            put("branch", p.branch); put("remote", p.remote); put("upstream", p.upstream); put("fetch", p.fetchUrl); put("push", p.pushUrl)
            put("credentialRevision", p.credentialRevision ?: JSONObject.NULL); put("included", p.included); put("wifi", p.wifiOnly); put("power", p.externalPowerOnly)
            put("health", JSONObject().apply {
                put("kind", p.health.kind.name); put("attempt", p.health.attemptedAt); put("success", p.health.succeededAt); put("transfer", p.health.transferredAt)
                put("head", p.health.head ?: JSONObject.NULL); put("outcome", p.health.outcome?.name ?: JSONObject.NULL); put("problem", p.health.problem?.name ?: JSONObject.NULL)
            })
        }) } })
        val encoded = json.toString(); require(encoded.length <= 1024 * 1024)
        val before = prefs.all.toMap()
        if (!prefs.edit().putString("state", encoded).commit()) { restore(before); error("Could not save Background Sync settings") }
        changed.value = changed.value + 1
    }
    private fun restore(values: Map<String, *>) {
        val edit = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value); is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value)
            is Boolean -> edit.putBoolean(key, value); is Float -> edit.putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
            else -> error("Invalid preferences value")
        } }
        check(edit.commit()) { "Background Sync settings need storage recovery" }
    }
    companion object { private val lock = Any(); private val changed = kotlinx.coroutines.flow.MutableStateFlow(0L) }
}

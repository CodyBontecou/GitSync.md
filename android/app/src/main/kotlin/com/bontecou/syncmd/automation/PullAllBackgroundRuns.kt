package com.bontecou.syncmd.automation

import android.content.Context
import com.bontecou.syncmd.services.automation.PullAllOutcome
import com.bontecou.syncmd.services.automation.PullAllResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class BackgroundPullState { QUEUED, RUNNING, COMPLETE, FAILED, INTERRUPTED }
data class BackgroundPullAttention(val name: String, val outcome: PullAllOutcome)
data class BackgroundPullRun(val id: String, val created: Long, val state: BackgroundPullState,
    val total: Int = 0, val counts: Map<PullAllOutcome, Int> = emptyMap(), val attention: List<BackgroundPullAttention> = emptyList()) {
    val checked get() = counts.values.sum()
    val busy get() = state == BackgroundPullState.QUEUED || state == BackgroundPullState.RUNNING
}

/** Latest bounded summary only: counts and at most three attention names, never paths or credentials.
 * Process-local admission is consumed before Git. Restoring preferences cannot resume a run. */
class PullAllBackgroundRuns(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    fun reserve(): BackgroundPullRun = synchronized(lock) {
        check(pending == null && active == null) { "Pull All is already running" }
        val old = read()
        val run = BackgroundPullRun(UUID.randomUUID().toString(), maxOf(System.currentTimeMillis(), (old?.created ?: 0L) + 1), BackgroundPullState.QUEUED)
        save(run); pending = run.id; changed(); run
    }
    fun claim(id: String): Boolean = synchronized(lock) {
        val old = read() ?: return@synchronized false
        if (pending != id || active != null || old.id != id || old.state != BackgroundPullState.QUEUED) return@synchronized false
        save(old.copy(state = BackgroundPullState.RUNNING)); pending = null; active = id; changed(); true
    }
    fun targets(id: String, total: Int) = synchronized(lock) {
        require(total in 0..10_000)
        val old = requireActive(id); require(old.total == 0 && old.checked == 0)
        save(old.copy(total = total)); changed()
    }
    fun result(id: String, result: PullAllResult) = synchronized(lock) {
        val old = requireActive(id); require(old.checked < old.total)
        val counts = old.counts + (result.outcome to ((old.counts[result.outcome] ?: 0) + 1))
        val attention = if (result.outcome in setOf(PullAllOutcome.ATTENTION, PullAllOutcome.BLOCKED, PullAllOutcome.FAILED) && old.attention.size < 3) {
            val name = result.target.name
            val clipped = name.substring(0, name.offsetByCodePoints(0, minOf(512, name.codePointCount(0, name.length))))
            old.attention + BackgroundPullAttention(clipped, result.outcome)
        } else old.attention
        save(old.copy(counts = counts, attention = attention)); changed()
    }
    fun finish(id: String, state: BackgroundPullState, retainOwnership: Boolean = false) = synchronized(lock) {
        require(state in setOf(BackgroundPullState.COMPLETE, BackgroundPullState.FAILED, BackgroundPullState.INTERRUPTED))
        try {
            val old = requireNotNull(read()); require(old.id == id)
            if (old.busy) {
                if (state == BackgroundPullState.COMPLETE) require(old.checked == old.total && active == id)
                save(old.copy(state = state))
            }
        } finally {
            if (pending == id) pending = null
            if (active == id && !retainOwnership) active = null
            changed()
        }
    }
    /** A service retains its slot until its job and notification cleanup have actually ended. */
    fun release(id: String) = synchronized(lock) {
        if (active == id) { active = null; changed() }
    }
    fun latest(): BackgroundPullRun? = synchronized(lock) {
        read()?.let { if (it.busy && it.id != pending && it.id != active) it.copy(state = BackgroundPullState.INTERRUPTED) else it }
    }
    fun clear() = synchronized(lock) {
        check(active == null && pending == null) { "Stop Pull All before clearing its summary" }
        save(null); changed()
    }
    private fun requireActive(id: String): BackgroundPullRun = requireNotNull(read()).also {
        require(active == id && it.id == id && it.state == BackgroundPullState.RUNNING)
    }
    private fun read(): BackgroundPullRun? {
        val all = prefs.all
        if (all.isEmpty()) return null
        require(all.keys == setOf("latest"))
        val raw = all["latest"]; require(raw is String && raw.length <= 32_768)
        val j = JSONObject(raw)
        fun string(key: String) = j.get(key).let { require(it is String); it }
        val counts = j.getJSONObject("counts").keys().asSequence().associate { key ->
            val outcome = PullAllOutcome.valueOf(key); val count = j.getJSONObject("counts").get(key)
            require(count is Int && count in 1..10_000); outcome to count
        }
        val list = j.getJSONArray("attention")
        require(list.length() <= 3)
        val attention = (0 until list.length()).map { i ->
            val item = list.getJSONObject(i); val name = item.get("name"); require(name is String && name.length <= 1024)
            val outcome = PullAllOutcome.valueOf(item.getString("outcome"))
            require(outcome in setOf(PullAllOutcome.ATTENTION, PullAllOutcome.BLOCKED, PullAllOutcome.FAILED))
            BackgroundPullAttention(name, outcome)
        }
        val total = j.get("total"); val created = j.get("created")
        require(total is Int && total in 0..10_000 && created is Number && created.toLong() > 0)
        val run = BackgroundPullRun(string("id"), created.toLong(), BackgroundPullState.valueOf(string("state")), total, counts, attention)
        require(UUID.fromString(run.id).toString() == run.id && run.checked <= total)
        require(run.state != BackgroundPullState.QUEUED || total == 0 && counts.isEmpty() && attention.isEmpty())
        require(run.state != BackgroundPullState.COMPLETE || run.checked == total)
        require(attention.groupingBy { it.outcome }.eachCount().all { (outcome, count) -> count <= (counts[outcome] ?: 0) })
        require(encode(run) == raw) { "Saved Pull All summary is incompatible and was preserved" }
        return run
    }
    private fun save(run: BackgroundPullRun?) {
        val before = prefs.all
        val edit = prefs.edit().clear(); if (run != null) edit.putString("latest", encode(run))
        if (!edit.commit()) {
            val restore = prefs.edit().clear()
            before.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value); is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value); is Float -> restore.putFloat(key, value); is Boolean -> restore.putBoolean(key, value)
                is Set<*> -> restore.putStringSet(key, value.map { requireNotNull(it as? String) }.toSet())
                else -> error("Unsupported summary value")
            } }
            check(restore.commit()); error("Could not save Pull All summary")
        }
    }
    private fun encode(run: BackgroundPullRun) = JSONObject().apply {
        put("schema", 1); put("id", run.id); put("created", run.created); put("state", run.state.name); put("total", run.total)
        put("counts", JSONObject().apply { run.counts.toSortedMap(compareBy { it.name }).forEach { (outcome, count) -> put(outcome.name, count) } })
        put("attention", JSONArray().apply { run.attention.forEach { put(JSONObject().put("name", it.name).put("outcome", it.outcome.name)) } })
    }.toString()
    companion object {
        const val PREFERENCES = "pull_all_background_runs"
        private val lock = Any(); private var pending: String? = null; private var active: String? = null
        private val mutable = MutableStateFlow(0L); val changes = mutable.asStateFlow()
        private fun changed() { mutable.value++ }
        fun isActive() = synchronized(lock) { pending != null || active != null }
    }
}

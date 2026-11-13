package com.bontecou.syncmd.automation

import android.content.Context
import com.bontecou.syncmd.services.automation.RepositoryActionOutcome
import com.bontecou.syncmd.services.automation.CallbackProblem
import org.json.JSONObject
import java.util.UUID

enum class ShortcutRunState { QUEUED, RUNNING, COMPLETE, ATTENTION, INTERRUPTED }
data class ShortcutRun(val id: String, val shortcut: String, val created: Long, val state: ShortcutRunState,
    val outcome: RepositoryActionOutcome? = null, val callbackProblem: CallbackProblem? = null)

/** Bounded private receipts contain only opaque IDs and result enums, never paths or credentials.
 * A claimed receipt cannot be claimed again, including after process death. */
class RepositoryShortcutRuns(context: Context) {
    private val prefs = context.getSharedPreferences("repository_shortcut_runs", 0)
    fun reserve(shortcut: String): ShortcutRun = synchronized(lock) {
        uuid(shortcut)
        val records = read()
        val run = ShortcutRun(UUID.randomUUID().toString(), shortcut, maxOf(System.currentTimeMillis(), (records.maxOfOrNull { it.created } ?: 0L) + 1), ShortcutRunState.QUEUED)
        val running = records.filter { it.id in active }
        val keep = running + records.filter { it.id !in active }.sortedByDescending { it.created }.take(63 - running.size)
        val edit = prefs.edit().clear()
        (keep + run).forEach { edit.putString(it.id, encode(it)) }
        check(edit.commit()) { "Could not save shortcut execution" }
        pending.retainAll(keep.map { it.id }.toSet()); pending += run.id
        run
    }
    fun claim(id: String, shortcut: String): Boolean = synchronized(lock) {
        uuid(id); uuid(shortcut)
        val old = read().singleOrNull { it.id == id } ?: return@synchronized false
        if (id !in pending || old.shortcut != shortcut || old.state != ShortcutRunState.QUEUED) return@synchronized false
        check(prefs.edit().putString(id, encode(old.copy(state = ShortcutRunState.RUNNING))).commit())
        pending -= id; active += id
        true
    }
    fun finish(id: String, state: ShortcutRunState, outcome: RepositoryActionOutcome? = null,
        callbackProblem: CallbackProblem? = null, retainOwnership: Boolean = false) = synchronized(lock) {
        require(state in setOf(ShortcutRunState.COMPLETE, ShortcutRunState.ATTENTION, ShortcutRunState.INTERRUPTED))
        if (callbackProblem != null) require(outcome == null && callbackProblem in setOf(CallbackProblem.BUSY, CallbackProblem.FAILED, CallbackProblem.INTERRUPTED) &&
            state == if (callbackProblem == CallbackProblem.INTERRUPTED) ShortcutRunState.INTERRUPTED else ShortcutRunState.ATTENTION)
        try {
            val old = read().singleOrNull { it.id == id } ?: error("Shortcut execution is unavailable")
            require(old.state in setOf(ShortcutRunState.QUEUED, ShortcutRunState.RUNNING))
            check(prefs.edit().putString(id, encode(old.copy(state = state, outcome = outcome, callbackProblem = callbackProblem))).commit())
        } finally { if (!retainOwnership) active -= id; pending -= id }
    }
    fun release(id: String) = synchronized(lock) { active -= id; pending -= id }
    fun isActive(id: String) = synchronized(lock) { id in active || id in pending }
    fun latest(shortcut: String): ShortcutRun? = synchronized(lock) {
        read().filter { it.shortcut == shortcut }.maxByOrNull { it.created }?.let { run ->
            if (run.state == ShortcutRunState.RUNNING && run.id !in active || run.state == ShortcutRunState.QUEUED && run.id !in pending)
                run.copy(state = ShortcutRunState.INTERRUPTED) else run
        }
    }
    fun clearResults() = synchronized(lock) {
        check(active.isEmpty()) { "A shortcut is still running" }
        check(prefs.edit().clear().commit()); pending.clear()
    }
    private fun read(): List<ShortcutRun> {
        val all = prefs.all; require(all.size <= 64)
        return all.map { (id, value) ->
            uuid(id); require(value is String && value.length <= 2048)
            val j = JSONObject(value)
            val run = ShortcutRun(j.getString("id"), j.getString("shortcut"), j.getLong("created"),
                ShortcutRunState.valueOf(j.getString("state")), if (j.isNull("outcome")) null else RepositoryActionOutcome.valueOf(j.getString("outcome")),
                if (j.has("callbackProblem")) CallbackProblem.valueOf(j.getString("callbackProblem")) else null)
            run.callbackProblem?.let { problem -> require(run.outcome == null && problem in setOf(CallbackProblem.BUSY, CallbackProblem.FAILED, CallbackProblem.INTERRUPTED) &&
                run.state == if (problem == CallbackProblem.INTERRUPTED) ShortcutRunState.INTERRUPTED else ShortcutRunState.ATTENTION) }
            uuid(run.id); uuid(run.shortcut); require(id == run.id && run.created > 0 && encode(run) == value)
            run
        }
    }
    private fun encode(run: ShortcutRun) = JSONObject().put("schema", if (run.callbackProblem == null) 1 else 2).put("id", run.id).put("shortcut", run.shortcut)
        .put("created", run.created).put("state", run.state.name).put("outcome", run.outcome?.name ?: JSONObject.NULL).apply { run.callbackProblem?.let { put("callbackProblem", it.name) } }.toString()
    private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
    companion object { private val lock = Any(); private val active = mutableSetOf<String>(); private val pending = mutableSetOf<String>() }
}

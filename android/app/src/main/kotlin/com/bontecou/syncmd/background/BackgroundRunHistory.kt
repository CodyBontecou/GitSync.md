package com.bontecou.syncmd.background

import android.content.Context
import com.bontecou.syncmd.services.automation.RepositoryActionOutcome
import com.bontecou.syncmd.services.automation.RepositoryActionProblem
import com.bontecou.syncmd.services.background.BackgroundHealthKind
import com.bontecou.syncmd.services.background.BackgroundRunResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One vault in one Background Sync pass ("notes ↓2", "VoxVault skipped: local edits"). */
data class BackgroundRunVault(val path: String, val name: String, val kind: BackgroundHealthKind,
    val outcome: RepositoryActionOutcome?, val problem: RepositoryActionProblem?, val pulled: Int = 0, val pushed: Boolean = false)

/** One Background Sync pass: when it ran and what each included vault did. */
data class BackgroundRun(val time: Long, val vaults: List<BackgroundRunVault>)

/**
 * Background Sync › Recent runs. Private app preferences, newest first, at most [LIMIT] passes.
 * Names and paths stay on this phone; nothing here is exported with diagnostics.
 */
class BackgroundRunHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("background_runs", Context.MODE_PRIVATE)

    fun runs(): List<BackgroundRun> = synchronized(lock) {
        val raw = prefs.getString("runs", null) ?: return@synchronized emptyList()
        runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val run = array.getJSONObject(index)
                val vaults = run.getJSONArray("vaults")
                BackgroundRun(run.getLong("time"), (0 until vaults.length()).map { v ->
                    val o = vaults.getJSONObject(v)
                    BackgroundRunVault(o.getString("path"), o.getString("name"), BackgroundHealthKind.valueOf(o.getString("kind")),
                        o.optString("outcome").takeIf { it.isNotEmpty() }?.let(RepositoryActionOutcome::valueOf),
                        o.optString("problem").takeIf { it.isNotEmpty() }?.let(RepositoryActionProblem::valueOf),
                        o.optInt("pulled", 0), o.optBoolean("pushed", false))
                })
            }
        }.getOrDefault(emptyList())
    }

    fun record(run: BackgroundRun) = synchronized(lock) {
        if (run.vaults.isEmpty()) return@synchronized
        val next = (listOf(run) + runs()).take(LIMIT)
        val array = JSONArray()
        next.forEach { r ->
            array.put(JSONObject().put("time", r.time).put("vaults", JSONArray().apply {
                r.vaults.forEach { v ->
                    put(JSONObject().put("path", v.path).put("name", v.name).put("kind", v.kind.name)
                        .put("outcome", v.outcome?.name ?: "").put("problem", v.problem?.name ?: "")
                        .put("pulled", v.pulled).put("pushed", v.pushed))
                }
            }))
        }
        prefs.edit().putString("runs", array.toString()).apply()
        changed.value = changed.value + 1
    }

    fun clear() = synchronized(lock) { prefs.edit().remove("runs").apply(); changed.value = changed.value + 1 }

    companion object {
        const val LIMIT = 20
        private val lock = Any()
        private val changed = MutableStateFlow(0L)
        /** Bumps whenever a pass is recorded. */
        val changes: StateFlow<Long> = changed.asStateFlow()

        /** Builds the record for a finished pass; [previousHeads] are the HEADs the vaults had before it. */
        fun summarize(results: List<BackgroundRunResult>, previousHeads: Map<String, String?>, now: Long = System.currentTimeMillis()) =
            BackgroundRun(now, results.map { result ->
                val path = result.policy.target.path
                val outcome = result.health.outcome
                val pulledHead = outcome in setOf(RepositoryActionOutcome.PULLED, RepositoryActionOutcome.PULLED_AND_PUSHED)
                BackgroundRunVault(path, result.policy.target.name, result.health.kind, outcome, result.health.problem,
                    pulled = if (pulledHead) commitsBetween(path, previousHeads[path], result.health.head) else 0,
                    pushed = outcome in setOf(RepositoryActionOutcome.PUSHED, RepositoryActionOutcome.PULLED_AND_PUSHED))
            })

        /** Commits reachable from [to] but not from [from] (1 when the history can't be read). */
        private fun commitsBetween(path: String, from: String?, to: String?): Int {
            if (to == null || to == from) return 0
            return (com.bontecou.syncmd.services.git.CommitCount.between(path, from, to) ?: 1).coerceAtLeast(1)
        }
    }
}

/**
 * Background Sync › "Only when": one Wi-Fi and one Charging switch for every included vault.
 * The per-vault conditions from before this setting existed migrate on first read: if any
 * included vault waited for Wi-Fi (or charging), the global switch starts on.
 */
class BackgroundConditionDefaults(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("background_sync_globals", Context.MODE_PRIVATE)
    data class Values(val wifiOnly: Boolean, val chargingOnly: Boolean)

    /** Null until set or migrated. */
    fun stored(): Values? = if (prefs.contains("wifi")) Values(prefs.getBoolean("wifi", false), prefs.getBoolean("power", false)) else null

    fun save(values: Values) {
        check(prefs.edit().putBoolean("wifi", values.wifiOnly).putBoolean("power", values.chargingOnly).commit()) { "Could not save Background Sync conditions" }
    }

    /** The current global conditions; migrates from [store]'s per-vault policies the first time. */
    fun read(store: BackgroundSyncStore): Values = stored() ?: run {
        val included = runCatching { store.read().policies.values.filter { it.included } }.getOrDefault(emptyList())
        Values(included.any { it.wifiOnly }, included.any { it.externalPowerOnly }).also(::save)
    }
}

/** Runs [BackgroundSyncBackend.run] and records the pass in [BackgroundRunHistory]. */
internal suspend fun BackgroundSyncBackend.runAndRecord(history: BackgroundRunHistory): List<BackgroundRunResult> {
    val before = runCatching { store.read().policies.mapValues { it.value.health.head } }.getOrDefault(emptyMap())
    val results = kotlinx.coroutines.withContext(com.bontecou.syncmd.services.diagnostics.DiagnosticScope(background = true)) { run() }
    runCatching { history.record(BackgroundRunHistory.summarize(results, before)) }
    return results
}

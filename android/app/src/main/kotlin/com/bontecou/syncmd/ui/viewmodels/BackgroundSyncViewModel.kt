package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bontecou.syncmd.background.*
import com.bontecou.syncmd.services.automation.*
import com.bontecou.syncmd.services.background.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class BackgroundSyncViewModel(val backend: BackgroundSyncBackend, private val reschedule: () -> Unit,
    private val enqueueNow: () -> Unit, private val cancelRunning: () -> Unit, work: Flow<List<WorkInfo>> = flowOf(emptyList())) : ViewModel() {
    @Inject constructor(backend: BackgroundSyncBackend, @ApplicationContext context: Context) : this(backend,
        { BackgroundSyncJobs.preferencesChanged(context) }, { BackgroundSyncJobs.now(context) }, { BackgroundSyncJobs.stop(context) },
        combine(WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(BackgroundSyncJobs.NOW),
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(BackgroundSyncJobs.PERIODIC)) { a, b -> a + b })
    data class Row(val target: PullAllTarget, val policy: BackgroundPolicy?, val available: Boolean)
    data class State(val ready: Boolean = false, val busy: Boolean = false, val configuration: BackgroundConfiguration = BackgroundConfiguration(),
        val rows: List<Row> = emptyList(), val review: RepositoryActionPlan? = null, val reviewGeneration: Long = 0,
        val recovery: String? = null, val failed: Boolean = false, val running: Boolean = false, val queued: Boolean = false)
    private val mutable = MutableStateFlow(State()); val state = mutable.asStateFlow()
    private var operation: Job? = null
    private var reviewing = false
    init {
        reload()
        viewModelScope.launch { backend.store.changes.drop(1).collectLatest { mutable.filter { !it.busy }.first(); reload() } }
        viewModelScope.launch { work.collect { jobs -> mutable.value = mutable.value.copy(running = jobs.any { it.state == WorkInfo.State.RUNNING },
            queued = jobs.any { it.periodicityInfo == null && it.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) }) } }
    }
    fun reload() = launch { load() }
    private suspend fun load() {
        val (configuration, rows) = withContext(Dispatchers.IO) {
            val configuration = backend.store.read(); val targets = backend.targets()
            configuration to (targets.map { Row(it, configuration.policies[it.path], true) } +
                configuration.policies.values.filter { p -> targets.none { it.path == p.target.path } }.map { Row(it.target, it, false) })
        }
        mutable.value = mutable.value.copy(configuration = configuration, rows = rows, recovery = null)
    }
    fun modes(pull: Boolean, push: Boolean, generation: Long) = launch {
        withContext(Dispatchers.IO) { backend.modes(pull, push, generation) }; reschedule(); load()
    }
    fun review(row: Row) = launch(cancelOnBackground = true) {
        val generation = mutable.value.configuration.generation
        val plan = withContext(Dispatchers.IO) { backend.review(row.target) }
        mutable.value = mutable.value.copy(review = plan, reviewGeneration = generation)
    }
    fun dismissReview() { mutable.value = mutable.value.copy(review = null) }
    fun include(wifiOnly: Boolean, powerOnly: Boolean) = launch {
        val plan = requireNotNull(mutable.value.review); val generation = mutable.value.reviewGeneration
        withContext(Dispatchers.IO) { backend.include(plan, generation, wifiOnly, powerOnly) }
        mutable.value = mutable.value.copy(review = null); reschedule(); load()
    }
    fun exclude(row: Row) = launch {
        withContext(Dispatchers.IO) { backend.exclude(row.target.path, mutable.value.configuration.generation) }; reschedule(); load()
    }
    fun forget(row: Row) = launch {
        withContext(Dispatchers.IO) { backend.forget(row.target.path, mutable.value.configuration.generation) }; reschedule(); load()
    }
    fun runNow() = launch { enqueueNow(); load() }
    fun stopWork() = launch { cancelRunning(); load() }
    fun reset(version: String) = launch {
        withContext(Dispatchers.IO) { backend.store.resetCorrupt(version) }; reschedule(); load()
    }
    fun stopReview() { if (reviewing) operation?.cancel(); dismissReview() }
    private fun launch(cancelOnBackground: Boolean = false, action: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, failed = false)
        reviewing = cancelOnBackground
        operation = viewModelScope.launch {
            try { action() }
            catch (_: CancellationException) { mutable.value = mutable.value.copy(review = null) }
            catch (_: Exception) {
                val recovery = withContext(Dispatchers.IO) { runCatching { backend.store.recoveryVersion() }.getOrNull() }
                mutable.value = mutable.value.copy(failed = true, recovery = recovery)
            }
            finally { reviewing = false; mutable.value = mutable.value.copy(ready = true, busy = false) }
        }
    }
}

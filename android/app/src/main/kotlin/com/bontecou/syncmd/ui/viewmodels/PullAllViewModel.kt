package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.automation.PullAllCatalog
import com.bontecou.syncmd.automation.PullAllBackgroundRuns
import com.bontecou.syncmd.automation.BackgroundPullRun
import com.bontecou.syncmd.automation.PullAllRunService
import kotlinx.coroutines.flow.collectLatest
import com.bontecou.syncmd.domain.repository.PullRepository
import com.bontecou.syncmd.services.automation.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class PullAllViewModel(private val snapshot: () -> List<PullAllTarget>, private val runner: PullAllRunner,
    private val background: (() -> BackgroundPullRun?)? = null, private val cancelBackground: (String) -> Unit = {},
    private val clearBackground: () -> Unit = {}, private val details: () -> Map<String, String> = { emptyMap() }) : ViewModel() {
    @Inject constructor(@ApplicationContext context: Context, pull: PullRepository) : this(
        PullAllCatalog(context)::snapshot, PullAllRunner(pull, PullAllCatalog(context)::requireCurrent),
        PullAllBackgroundRuns(context)::latest, { id -> context.startService(PullAllRunService.stopIntent(context, id)); Unit },
        PullAllBackgroundRuns(context)::clear, PullAllCatalog(context)::details)
    data class State(val busy: Boolean = false, val started: Boolean = false, val targets: List<PullAllTarget> = emptyList(),
        val current: String? = null, val results: List<PullAllResult> = emptyList(), val stopped: Boolean = false,
        val stopping: Boolean = false, val failed: Boolean = false, val background: BackgroundPullRun? = null,
        val backgroundUnavailable: Boolean = false, val backgroundControlFailed: Boolean = false,
        /** Vaults a run would pull right now, for "Pull all · 5 vaults"; null until read. */
        val vaults: Int? = null,
        /** "main · CodyBontecou/VoxVault" per vault path. */
        val details: Map<String, String> = emptyMap())
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var opening: Job? = null
    private var backgroundRead = 0L
    private data class BackgroundRead(val revision: Long, val result: Result<BackgroundPullRun?>)
    init {
        if (background != null) viewModelScope.launch { PullAllBackgroundRuns.changes.collectLatest { loadBackground() } }
        refreshCatalog()
    }
    /** Re-read how many vaults Pull All covers and their branch/remote lines. */
    fun refreshCatalog() { viewModelScope.launch {
        val read = withContext(Dispatchers.IO) { runCatching { snapshot().size to details() } }.getOrNull() ?: return@launch
        mutable.value = mutable.value.copy(vaults = read.first, details = read.second)
    } }
    private suspend fun loadBackground(): BackgroundRead {
        val source = background ?: return BackgroundRead(backgroundRead, Result.success(null))
        val generation = ++backgroundRead
        val result = withContext(Dispatchers.IO) { runCatching { source() } }
        if (generation == backgroundRead) mutable.value = mutable.value.copy(background = result.getOrNull(), backgroundUnavailable = result.isFailure)
        return BackgroundRead(generation, result)
    }
    fun refreshBackground() { viewModelScope.launch { loadBackground() } }
    /** Home opens the latest background summary for review; another pull remains an explicit button tap. */
    fun open(): Job {
        val mayStart = !mutable.value.busy
        opening?.cancel()
        return viewModelScope.launch {
            // Reviewing active work must not invalidate its pending summary read.
            if (!mayStart) return@launch
            val current = loadBackground()
            if (current.revision == backgroundRead && current.result.isSuccess && current.result.getOrNull() == null) {
                begin(requireEmptySummary = true)
            }
        }.also { opening = it }
    }
    fun stopBackground() {
        val run = mutable.value.background?.takeIf { it.busy } ?: return
        mutable.value = mutable.value.copy(backgroundControlFailed = runCatching { cancelBackground(run.id) }.isFailure)
    }
    fun clearBackgroundSummary() { viewModelScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { clearBackground() } }
        mutable.value = mutable.value.copy(backgroundControlFailed = result.isFailure)
        loadBackground()
    } }
    fun start() {
        opening?.cancel(); opening = null
        begin(requireEmptySummary = false)
    }
    private fun begin(requireEmptySummary: Boolean) {
        if (mutable.value.busy || mutable.value.background?.busy == true) return
        mutable.value = mutable.value.copy(busy = true, started = true, targets = emptyList(), results = emptyList(),
            current = null, stopped = false, stopping = false, failed = false)
        job = viewModelScope.launch {
            try {
                val read = loadBackground()
                if (backgroundBlocksStart(read, requireEmptySummary)) {
                    mutable.value = mutable.value.copy(started = false)
                    return@launch
                }
                val targets = withContext(Dispatchers.IO) { snapshot() }
                // Catalog I/O can suspend while a newer background result is published.
                if (backgroundBlocksStart(read, requireEmptySummary)) {
                    mutable.value = mutable.value.copy(started = false)
                    return@launch
                }
                val lines = withContext(Dispatchers.IO) { runCatching(details).getOrDefault(mutable.value.details) }
                mutable.value = mutable.value.copy(targets = targets, vaults = targets.size, details = lines)
                runner.run(targets, onStart = { target ->
                    mutable.value = mutable.value.copy(current = target.path)
                }, onResult = { result ->
                    mutable.value = mutable.value.copy(results = mutable.value.results + result, current = null)
                })
            } catch (_: CancellationException) { mutable.value = mutable.value.copy(stopped = true)
            } catch (_: Exception) { mutable.value = mutable.value.copy(failed = true)
            } finally { mutable.value = mutable.value.copy(busy = false, stopping = false, current = null) }
        }
    }
    private fun backgroundBlocksStart(read: BackgroundRead, requireEmptySummary: Boolean): Boolean =
        read.result.getOrNull()?.busy == true || mutable.value.background?.busy == true ||
            (requireEmptySummary && (read.revision != backgroundRead || read.result.isFailure || read.result.getOrNull() != null))

    fun stop() {
        opening?.cancel(); opening = null
        if (mutable.value.busy) { mutable.value = mutable.value.copy(stopping = true); job?.cancel() }
    }
}

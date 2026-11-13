package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.automation.PullAllCatalog
import com.bontecou.syncmd.automation.RepositoryShortcutService
import com.bontecou.syncmd.automation.RepositoryShortcutItem
import com.bontecou.syncmd.automation.RepositoryShortcutRecord
import com.bontecou.syncmd.automation.RepositoryCallbackGrant
import com.bontecou.syncmd.automation.RepositoryCallbackGrantService
import com.bontecou.syncmd.services.automation.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class RepositoryActionsViewModel(private val catalog: () -> List<PullAllTarget>, private val runner: RepositoryActionRunner,
    private val shortcuts: RepositoryShortcutService? = null, private val callbackGrants: RepositoryCallbackGrantService? = null) : ViewModel() {
    @Inject constructor(@ApplicationContext context: Context, runner: RepositoryActionRunner, shortcuts: RepositoryShortcutService, grants: RepositoryCallbackGrantService) : this(PullAllCatalog(context)::snapshot, runner, shortcuts, grants)
    data class State(val busy: Boolean = false, val stopping: Boolean = false, val stopped: Boolean = false,
        val targets: List<PullAllTarget> = emptyList(), val plan: RepositoryActionPlan? = null,
        val result: RepositoryActionResult? = null, val failed: Boolean = false, val ready: Boolean = false,
        val shortcutItems: List<RepositoryShortcutItem> = emptyList(), val shortcut: RepositoryShortcutRecord? = null,
        val shortcutNotice: String? = null, val pinSupported: Boolean = false, val shortcutGeneration: Long = 0,
        val shortcutRequestId: String? = null,
        val callbackGrants: List<RepositoryCallbackGrant> = emptyList(), val callbackGrantFailed: Boolean = false,
        val callbackGrantRecoveryVersion: String? = null,
        val callbackRuns: Map<String, com.bontecou.syncmd.automation.ShortcutRun> = emptyMap(), val callbackResultsFailed: Boolean = false)
    /** Dialogs retain the review that was visible when opened, including any shortcut handoff. */
    data class Confirmation(val plan: RepositoryActionPlan, val action: RepositoryAction, val message: String,
        val shortcutGeneration: Long) {
        private val consumed = java.util.concurrent.atomic.AtomicBoolean(false)
        internal fun consume(): Boolean = consumed.compareAndSet(false, true)
    }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    init { reload() }
    fun reload() = launch {
        val targets = withContext(Dispatchers.IO) { catalog() }
        mutable.value = mutable.value.copy(targets = targets, plan = null, result = null, shortcut = null)
        refreshShortcutItems()
    }
    fun select(target: PullAllTarget) = launch {
        mutable.value = mutable.value.copy(plan = null, result = null, shortcut = null)
        val plan = runner.review(target)
        mutable.value = mutable.value.copy(plan = plan)
    }
    fun run(confirmation: Confirmation) {
        if (!accept(confirmation)) return
        launch {
            mutable.value = mutable.value.copy(result = null)
            val result = runner.run(confirmation.plan, confirmation.action, confirmation.message)
            mutable.value = mutable.value.copy(result = result)
            // Never silently refresh a plan and repeat a write. A new review is an explicit action.
        }
    }
    fun pin(confirmation: Confirmation, background: Boolean = false) {
        if (!accept(confirmation)) return
        val service = shortcuts ?: return
        launch {
            val record = service.prepare(confirmation.plan, confirmation.action, confirmation.message.ifBlank { "Update from GitSync.md" }, background)
            val requested = service.requestPin(record)
            mutable.value = mutable.value.copy(shortcutNotice = if (requested) "requested" else "unsupported")
            refreshShortcutItems()
        }
    }
    private fun accept(confirmation: Confirmation): Boolean {
        val current = mutable.value
        val unused = confirmation.consume()
        if (current.busy) return false
        if (unused && current.plan == confirmation.plan && current.shortcutGeneration == confirmation.shortcutGeneration) return true
        mutable.value = current.copy(result = RepositoryActionResult(confirmation.action, RepositoryActionOutcome.BLOCKED,
            confirmation.plan.branch, null, null, RepositoryActionProblem.STALE), failed = false, stopped = false, shortcutNotice = null)
        return false
    }
    fun runShortcut(id: String) {
        if (mutable.value.busy) { mutable.value = mutable.value.copy(shortcutNotice = "busy"); return }
        launch {
            mutable.value = mutable.value.copy(plan = null, result = null, shortcut = null, shortcutGeneration = mutable.value.shortcutGeneration + 1,
                shortcutRequestId = java.util.UUID.randomUUID().toString())
            val executed = requireNotNull(shortcuts).execute(id) { record, plan ->
                withContext(Dispatchers.Main.immediate) { mutable.value = mutable.value.copy(plan = plan, shortcut = record) }
            }
            mutable.value = mutable.value.copy(plan = executed.plan, result = executed.result, shortcut = executed.record)
            refreshShortcutItems()
        }
    }
    fun clearShortcutResults() = launch { requireNotNull(shortcuts).clearResults(); refreshShortcutItems() }
    fun refreshShortcuts() { if (shortcuts != null) launch { refreshShortcutItems() } }
    fun revokeShortcut(id: String) = launch {
        requireNotNull(shortcuts).revoke(id); refreshShortcutItems()
        mutable.value = mutable.value.copy(shortcutNotice = "revoked")
    }
    fun forgetShortcut(id: String) = launch { requireNotNull(shortcuts).forget(id); refreshShortcutItems() }
    fun refreshGrants() { if (callbackGrants != null) launch { refreshGrantItems() } }
    fun revokeGrant(id: String) = launch { withContext(Dispatchers.IO) { requireNotNull(callbackGrants).store.revoke(id) }; refreshGrantItems() }
    fun forgetGrant(id: String) = launch { withContext(Dispatchers.IO) { requireNotNull(callbackGrants).store.forget(id) }; refreshGrantItems() }
    fun resetUnreadableGrants(version: String) = launch { withContext(Dispatchers.IO) { requireNotNull(callbackGrants).store.resetUnreadable(version) }; refreshGrantItems() }
    private suspend fun refreshShortcutItems() {
        shortcuts?.let { service ->
            val items = withContext(Dispatchers.IO) { service.items() }
            mutable.value = mutable.value.copy(shortcutItems = items, pinSupported = service.supported())
        }
        refreshGrantItems()
    }
    private suspend fun refreshGrantItems() {
        val service = callbackGrants ?: return
        try {
            val records = withContext(Dispatchers.IO) { service.store.records() }
            val results = withContext(Dispatchers.IO) { runCatching { records.mapNotNull { grant ->
                service.store.latestRun(grant.scope.id)?.let { grant.scope.id to it }
            }.toMap() } }
            mutable.value = mutable.value.copy(callbackGrants = records, callbackGrantFailed = false, callbackGrantRecoveryVersion = null,
                callbackRuns = results.getOrDefault(emptyMap()), callbackResultsFailed = results.isFailure)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            val version = withContext(Dispatchers.IO) { service.store.recoveryVersion() }
            mutable.value = mutable.value.copy(callbackGrants = emptyList(), callbackGrantFailed = true, callbackGrantRecoveryVersion = version)
        }
    }
    fun stop() {
        if (mutable.value.busy) { mutable.value = mutable.value.copy(stopping = true); job?.cancel() }
    }
    private fun launch(action: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, failed = false, stopped = false, stopping = false, shortcutNotice = null)
        job = viewModelScope.launch {
            try { action() }
            catch (_: CancellationException) { mutable.value = mutable.value.copy(stopped = true)
            } catch (_: Exception) { mutable.value = mutable.value.copy(failed = true)
            } finally { mutable.value = mutable.value.copy(busy = false, stopping = false, ready = true) }
        }
    }
}

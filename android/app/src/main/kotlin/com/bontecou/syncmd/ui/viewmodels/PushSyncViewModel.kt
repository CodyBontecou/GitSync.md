package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.push.*
import com.bontecou.syncmd.services.automation.RepositoryActionOutcome
import com.bontecou.syncmd.services.push.PushInstallation
import com.bontecou.syncmd.services.push.PushHintResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class PushSyncViewModel(val backend: PushSyncBackend, private val browser: (String) -> Unit, private val schedule: () -> Unit,
    private val cancelEvents: () -> Unit) : ViewModel() {
    @Inject constructor(backend: PushSyncBackend, @ApplicationContext context: Context) : this(backend,
        { url -> CustomTabsIntent.Builder().setShareState(CustomTabsIntent.SHARE_STATE_OFF).build().also { it.intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }.launchUrl(context, Uri.parse(url)) },
        { PushSyncJobs.maintain(context) }, { PushSyncJobs.cancelEvents(context) })
    data class State(val ready: Boolean = false, val busy: Boolean = false, val enabled: Boolean = false, val configured: Boolean = false,
        val allowed: Boolean = true, val registeredAt: Long = 0, val installations: List<PushInstallation> = emptyList(), val connectionsChecked: Boolean = false, val error: String? = null,
        val pulling: Boolean = false, val checked: Int? = null, val updated: Int = 0, val attention: Int = 0, val results: List<PushHintResult> = emptyList())
    private val mutable = MutableStateFlow(State()); val state = mutable.asStateFlow()
    private var job: Job? = null; private var callbackID: String? = null; private var tapID: String? = null
    init { run { refresh(false) } }
    fun reload() = run {
        val snapshot = refresh(false)
        if (snapshot.enabled && backend.allowed()) {
            val items = withContext(Dispatchers.IO) { backend.status(snapshot.generation) }
            mutable.value = mutable.value.copy(installations = items, connectionsChecked = true)
        } else if (snapshot.enabled || snapshot.unregisterPending) refreshRemote()
    }
    fun refresh() = run { refresh(true) }
    private suspend fun refresh(remote: Boolean): PushState {
        val snapshot = withContext(Dispatchers.IO) { backend.store.read() }
        mutable.value = mutable.value.copy(enabled = snapshot.enabled, configured = backend.configured(), allowed = backend.allowed(), registeredAt = snapshot.registeredAt,
            installations = if (snapshot.enabled) mutable.value.installations else emptyList(),
            connectionsChecked = snapshot.enabled && mutable.value.connectionsChecked)
        if (remote && (snapshot.enabled || snapshot.unregisterPending)) refreshRemote()
        return snapshot
    }
    private suspend fun refreshRemote() {
        val items = try { withContext(Dispatchers.IO) { backend.maintain() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: PushSyncBackend.RegistrationFailure) {
            try {
                val known = withContext(Dispatchers.IO) { backend.status(failure.generation) }
                mutable.value = mutable.value.copy(installations = known, connectionsChecked = true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* Keep the last verified status when the independent read also fails. */ }
            throw failure
        }
        mutable.value = mutable.value.copy(installations = items, connectionsChecked = true)
    }
    fun permissionDenied() { mutable.value = mutable.value.copy(error = "permission", allowed = false) }
    fun openFailed() { mutable.value = mutable.value.copy(error = "open") }
    fun setEnabled(value: Boolean) = run {
        withContext(Dispatchers.IO) { if (value) backend.enable() else backend.disable() }
        if (!value) { cancelEvents(); mutable.value = mutable.value.copy(installations = emptyList(), connectionsChecked = false) }
        refresh(false); schedule()
        refreshRemote()
    }
    fun link() = run { val url = withContext(Dispatchers.IO) { backend.beginLink() }; browser(url) }
    fun linkExisting(account: String) = run { val url = withContext(Dispatchers.IO) { backend.beginLink(account) }; browser(url) }
    fun acceptCallback(callback: PushLinkCallback) {
        if (callbackID == callback.id || mutable.value.busy) return
        callbackID = callback.id
        run { val items = withContext(Dispatchers.IO) { backend.completeLink(callback.raw) }; mutable.value = mutable.value.copy(installations = items, connectionsChecked = true) }
    }
    fun unlink(id: Long) = run { val items = withContext(Dispatchers.IO) { backend.unlink(id) }; mutable.value = mutable.value.copy(installations = items, connectionsChecked = true) }
    fun pull(tap: PushTap) {
        if (tapID == tap.id || mutable.value.busy) return
        tapID = tap.id
        run {
            mutable.value = mutable.value.copy(pulling = true, checked = null, results = emptyList())
            val results = withContext(Dispatchers.IO) { backend.reconcile(tap.event, explicit = true) }
            mutable.value = mutable.value.copy(checked = results.size, results = results, error = if (results.isEmpty()) "no_match" else null, updated = results.count { it.result.pullUpdated },
                attention = results.count { it.result.outcome !in setOf(RepositoryActionOutcome.PULLED, RepositoryActionOutcome.UP_TO_DATE) })
        }
    }
    fun stopPull() { if (mutable.value.pulling) job?.cancel() }
    fun stop() { job?.cancel() }
    private fun run(action: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        job = viewModelScope.launch {
            try { action() }
            catch (_: CancellationException) { mutable.value = mutable.value.copy(error = "stopped") }
            catch (error: Exception) { mutable.value = mutable.value.copy(error = when (error.message) {
                "account_owner_required" -> "owner"
                "cancelled" -> "cancelled"
                else -> "failed"
            }) }
            finally {
                val snapshot = if (currentCoroutineContext().isActive) runCatching { withContext(Dispatchers.IO) { backend.store.read() } }.getOrNull() else null
                mutable.value = mutable.value.copy(busy = false, ready = true, pulling = false, enabled = snapshot?.enabled ?: mutable.value.enabled,
                    registeredAt = snapshot?.registeredAt ?: mutable.value.registeredAt, allowed = backend.allowed(), configured = backend.configured(),
                    installations = if (snapshot?.enabled ?: mutable.value.enabled) mutable.value.installations else emptyList(),
                    connectionsChecked = (snapshot?.enabled ?: mutable.value.enabled) && mutable.value.connectionsChecked)
            }
        }
    }
}

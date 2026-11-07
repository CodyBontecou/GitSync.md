package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.obsidian.*
import com.bontecou.syncmd.services.automation.PullAllTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ObsidianViewModel(val connections: ObsidianConnections, private val deliver: (String) -> Unit) : ViewModel() {
    @Inject constructor(connections: ObsidianConnections, @ApplicationContext context: Context) : this(connections, { url ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    })
    data class State(val busy: Boolean = false, val ready: Boolean = false, val targets: List<PullAllTarget> = emptyList(), val notice: String? = null,
        val destination: String? = null, val returned: Boolean = false, val deliveryFailed: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var incomingID: String? = null
    private var foreground = false
    init { reload() }
    fun foreground(value: Boolean) { foreground = value }
    fun reload() = run { val targets = withContext(Dispatchers.IO) { connections.catalog.snapshot() }; mutable.value = mutable.value.copy(targets = targets) }
    fun prepare(target: PullAllTarget) = run {
        withContext(Dispatchers.IO) {
            connections.catalog.requireCurrent(target)
            connections.prepare(target.path)
        }
        mutable.value = mutable.value.copy(notice = "prepared")
    }
    fun forget(target: PullAllTarget) = run {
        withContext(Dispatchers.IO) { connections.catalog.requireCurrent(target); connections.forget(target.path) }
        mutable.value = mutable.value.copy(notice = "forgotten")
    }
    fun accept(incoming: ObsidianIncoming) {
        if (incoming.id == incomingID || mutable.value.busy) return
        incomingID = incoming.id
        run {
            val destination = try { withContext(Dispatchers.IO) { connections.handle(incoming) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { incoming.destination("error") }
            mutable.value = mutable.value.copy(destination = destination, returned = false,
                notice = if (Uri.parse(destination).getQueryParameter("status") == "error") "error" else if (incoming.prepare) "prepared" else "ready")
            if (foreground) returnToObsidian()
        }
    }
    fun returnToObsidian() {
        val before = mutable.value
        if (before.returned || before.destination == null) return
        mutable.value = before.copy(returned = true, deliveryFailed = false)
        try { deliver(before.destination) }
        catch (_: Exception) { mutable.value = mutable.value.copy(returned = false, deliveryFailed = true) }
    }
    fun stop() { job?.cancel(); if (mutable.value.busy) mutable.value = mutable.value.copy(notice = "interrupted", destination = null) }
    private fun run(action: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, notice = null, destination = null, returned = false, deliveryFailed = false)
        job = viewModelScope.launch {
            try { action() }
            catch (_: CancellationException) { mutable.value = mutable.value.copy(notice = "interrupted", destination = null) }
            catch (_: Exception) { mutable.value = mutable.value.copy(notice = "error") }
            finally { mutable.value = mutable.value.copy(busy = false, ready = true) }
        }
    }
}

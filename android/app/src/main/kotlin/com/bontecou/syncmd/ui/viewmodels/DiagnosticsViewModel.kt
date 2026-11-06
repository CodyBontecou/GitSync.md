package com.bontecou.syncmd.ui.viewmodels

import com.bontecou.syncmd.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.diagnostics.AppDiagnostics
import com.bontecou.syncmd.services.diagnostics.DiagnosticLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(logs: AppDiagnostics) : ViewModel() {
    val store = logs.store
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    // Resolve feedback in composition so a retained model follows app-language changes.
    private val _error = MutableStateFlow<Int?>(null)
    val error = _error.asStateFlow()
    private val _notice = MutableStateFlow<Int?>(null)
    val notice = _notice.asStateFlow()
    init { run { store.load() } }
    fun clear() = run { store.clear(); _notice.value = R.string.diagnostics_cleared }
    fun export(level: DiagnosticLevel?, deliver: (String) -> Unit) = run {
        val text = store.export(level)
        withContext(Dispatchers.Main) { deliver(text) }
    }
    fun copied() { _notice.value = R.string.diagnostics_copied }
    private fun run(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true; _error.value = null
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
            catch (_: Exception) { _error.value = R.string.diagnostics_action_failed }
            finally { _busy.value = false }
        }
    }
}

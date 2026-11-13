package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.onboarding.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class AppGuideViewModel @Inject constructor(private val store: AppGuideStore) : ViewModel() {
    data class State(val ready: Boolean = false, val progress: GuideProgress = GuideProgress(), val busy: Boolean = false, val error: Boolean = false)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    init { load() }
    fun load() = run { store.load() }
    fun page(value: Int) = run(fallback = _state.value.progress.copy(page = value.coerceIn(0, 3))) { store.page(value) }
    fun complete(target: GuideTarget) = run(fallback = GuideProgress(completed = true, pending = target)) { store.complete(target) }
    fun consumeTarget() = run(fallback = _state.value.progress.copy(pending = null)) { store.consumeTarget() }
    fun notesSeen() = run(fallback = _state.value.progress.copy(showNotes = false)) { store.notesSeen() }
    private fun run(fallback: GuideProgress? = null, action: () -> GuideProgress) {
        if (_state.value.busy) return
        val before = _state.value.progress
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val progress = withContext(Dispatchers.IO) { action() }
                // After a failed completion write, later successful bookkeeping must not
                // re-open the first-run gate in this session. A restart can retry saving.
                val incompleteSave = before.completed && !progress.completed
                _state.value = State(ready = true,
                    progress = if (incompleteSave) before.copy(pending = progress.pending, showNotes = false) else progress,
                    error = incompleteSave)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.value = _state.value.copy(ready = true, progress = fallback ?: _state.value.progress, busy = false, error = true) }
        }
    }
}

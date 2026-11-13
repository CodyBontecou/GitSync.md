package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.services.files.DiffTarget
import com.bontecou.syncmd.services.files.FileInsights
import com.bontecou.syncmd.services.files.WorkingFileDiff
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One file compared with its last commit (board 07: "+8 −3 vs last commit a41c9e2"). */
@HiltViewModel
class WorkingDiffViewModel @Inject constructor() : ViewModel() {
    data class State(val path: String? = null, val diff: WorkingFileDiff? = null, val loading: Boolean = false, val failed: Boolean = false)

    private val insights = FileInsights()
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var job: Job? = null

    /** [staged] = true compares the last commit with the index; otherwise with the working copy. */
    fun load(repositoryPath: String, filePath: String, staged: Boolean? = null) {
        if (repositoryPath.isBlank() || filePath.isBlank()) return
        job?.cancel()
        val keep = _state.value.path == filePath
        _state.value = State(filePath, if (keep) _state.value.diff else null, loading = true)
        job = viewModelScope.launch {
            try {
                val diff = insights.diff(repositoryPath, filePath, if (staged == true) DiffTarget.INDEX else DiffTarget.WORKTREE)
                if (_state.value.path == filePath) _state.value = State(filePath, diff)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (_state.value.path == filePath) _state.value = State(filePath, null, failed = true)
            }
        }
    }
}

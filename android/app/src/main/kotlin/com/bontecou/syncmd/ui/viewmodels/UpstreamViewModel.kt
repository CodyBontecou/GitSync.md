package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.repositories.RepositoryUpstream
import com.bontecou.syncmd.services.repositories.RepositoryUpstreamPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class UpstreamViewModel @Inject constructor(): ViewModel() {
    data class State(val busy: Boolean = true, val plan: RepositoryUpstreamPlan? = null, val saved: Boolean = false, val error: String? = null,
        val requestId: Long = 0)
    private val service = RepositoryUpstream()
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    @Volatile private var generation = 0L
    fun load(path: String) {
        val request = ++generation
        _state.value = State(requestId = request)
        viewModelScope.launch {
            try {
                val plan = withContext(Dispatchers.IO) { service.inspect(path) }
                if(generation == request) _state.value = State(busy = false,plan = plan,requestId = request)
            } catch(cancelled: CancellationException) { throw cancelled
            } catch(e: Exception) { if(generation == request) _state.value = State(busy = false,error = e.message ?: "Could not load the upstream branch",requestId = request) }
        }
    }
    fun save(review: State, remote: String, branch: String) {
        if (_state.value !== review) return
        val current = review; val plan = current.plan ?: return
        if(current.busy || current.saved || !plan.editable) return
        val request = generation
        _state.value = current.copy(busy = true,error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { RepositoryOperationCoordinator.withRepository(plan.path) {
                    if(generation == request) service.save(plan,remote,branch)
                } }
                if(generation == request) _state.value = current.copy(busy = false,saved = true)
            } catch(cancelled: CancellationException) { throw cancelled
            } catch(e: Exception) { if(generation == request) _state.value = current.copy(busy = false,error = e.message ?: "Could not save the upstream branch") }
        }
    }
}

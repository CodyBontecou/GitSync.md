package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.security.RepositoryAuthors
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.repositories.RepositoryIdentity
import com.bontecou.syncmd.services.repositories.RepositoryIdentityPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AuthorViewModel @Inject constructor(private val authors: RepositoryAuthors) : ViewModel() {
    data class State(val busy: Boolean = true, val plan: RepositoryIdentityPlan? = null,
        val name: String = "", val email: String = "", val error: String? = null, val saved: Boolean = false,
        val requestId: Long = 0)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    @Volatile private var generation = 0L
    fun load(path: String) {
        val request = ++generation
        _state.value = State(requestId = request)
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val plan = RepositoryIdentity().inspect(path)
                    val fallback = if (plan.name.isNullOrBlank() && plan.email.isNullOrBlank()) authors.resolve(path) else null
                    State(false, plan, plan.name ?: fallback?.first.orEmpty(), plan.email ?: fallback?.second.orEmpty(), requestId = request)
                }
                if (generation == request) _state.value = loaded
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (generation == request) _state.value = State(busy = false, error = e.message ?: "Could not load author identity", requestId = request) }
        }
    }
    fun save(review: State, name: String, email: String) {
        if (_state.value !== review) return
        val current = review
        val plan = current.plan ?: return
        if (current.busy || current.saved) return
        val request = generation
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RepositoryOperationCoordinator.withRepository(plan.path) {
                        // A reload or repository switch can supersede this draft while it waits.
                        // Recheck under the same lease that protects the config write.
                        if (generation == request) RepositoryIdentity().save(plan, name, email)
                    }
                }
                if (generation == request) _state.value = current.copy(busy = false, saved = true)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (generation == request) _state.value = current.copy(busy = false, error = e.message ?: "Could not save author identity") }
        }
    }
}

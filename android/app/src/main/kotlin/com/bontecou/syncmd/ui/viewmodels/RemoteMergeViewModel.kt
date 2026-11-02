package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.services.git.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class RemoteMergeViewModel @Inject constructor(private val merge: RepositoryRemoteMerge) : ViewModel() {
    data class State(val path: String = "", val busy: Boolean = false, val mutating: Boolean = false,
        val plan: RemoteMergePlan? = null, val outcome: RemoteMergeOutcome? = null, val retryPlan: RemoteMergePlan? = null,
        val conflicts: Boolean = false, val error: String? = null)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    fun open(path: String) {
        if (mutable.value.mutating) return
        if (mutable.value.path != path) {
            job?.cancel(); generation++; mutable.value = State(path = path)
        }
        refresh()
    }
    fun refresh() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(plan = null)
        run(false) { it.copy(conflicts = merge.hasConflicts(it.path)) }
    }
    fun review() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(plan = null, outcome = null, retryPlan = null)
        run(false) { it.copy(plan = merge.plan(it.path), conflicts = false) }
    }
    fun begin(expected: RemoteMergePlan, publish: Boolean) {
        if (mutable.value.plan !== expected) return
        run(true) { before ->
            val result = merge.begin(expected, publish)
            before.copy(plan = null, outcome = result, conflicts = result.kind == RemoteMergeKind.CONFLICTS,
                retryPlan = expected.takeIf { result.push == RemoteMergePush.UNCONFIRMED })
        }
    }
    fun retryPush(expected: RemoteMergeOutcome) {
        val plan = mutable.value.retryPlan ?: return
        if (mutable.value.outcome !== expected) return
        run(true) { before ->
            val result = merge.retryPush(plan, expected)
            before.copy(outcome = result, retryPlan = plan.takeIf { result.push == RemoteMergePush.UNCONFIRMED })
        }
    }
    fun cancelReview() {
        if (mutable.value.mutating) return
        job?.cancel(); generation++
        mutable.value = mutable.value.copy(busy = false, plan = null)
    }
    private fun run(mutation: Boolean, action: suspend (State) -> State) {
        val before = mutable.value
        if (before.busy || before.path.isBlank()) return
        val request = generation
        mutable.value = before.copy(busy = true, mutating = mutation, error = null)
        job = viewModelScope.launch {
            try {
                val after = withContext(Dispatchers.IO) { action(before) }
                if (request == generation) mutable.value = after.copy(busy = false, mutating = false, error = null)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                val conflicts = if (mutation) withContext(Dispatchers.IO) { runCatching { merge.hasConflicts(before.path) }.getOrDefault(before.conflicts) } else before.conflicts
                val safe = if (failure is IllegalStateException || failure is IllegalArgumentException || failure is com.bontecou.syncmd.data.models.DirtyRepoException) failure.message else null
                if (request == generation) mutable.value = before.copy(plan = null, conflicts = conflicts,
                    error = safe ?: "Git could not complete this action. Check the connection, then review the current repository state before retrying.")
            } finally {
                if (request == generation) mutable.value = mutable.value.copy(busy = false, mutating = false)
            }
        }
    }
}

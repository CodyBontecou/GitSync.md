package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.domain.repository.ConflictRepository
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.ui.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class RebaseViewModel @Inject constructor(private val rebase: RepositoryRebase, private val conflicts: ConflictRepository) : ViewModel() {
    data class State(val path: String = "", val busy: Boolean = false, val mutating: Boolean = false, val plan: RebasePlan? = null,
        val session: RebaseSession? = null, val selected: Conflict? = null, val draft: String = "", val error: UiText? = null, val message: String? = null,
        val resolutionPath: String? = null, val status: String? = null, val problem: RebaseProblem? = null)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var generation = 0L
    fun open(path: String) {
        if (_state.value.path != path) {
            if (_state.value.mutating) return
            generation++; _state.value = State(path = path)
        }
        refresh()
    }
    fun refresh() { if (_state.value.selected == null) run(false) { it.copy(session = rebase.session(it.path), plan = null) } }
    fun review() = run(false) { it.copy(plan = rebase.plan(it.path), session = null, message = null, status = null) }
    fun begin() = run(true) { before ->
        val outcome = rebase.begin(before.plan ?: gitOpsError(R.string.gitops_rebase_review_first))
        before.copy(plan = null, session = outcome.session, message = outcome.notice, status = outcome.status)
    }
    fun continueRebase() = advance { rebase.continueRebase(it) }
    fun abort() = advance { rebase.abort(it) }
    fun skip() = advance { rebase.skip(it) }
    fun retryCheckout() = advance { rebase.retryCheckout(it) }
    private fun advance(action: suspend (RebaseSession) -> RebaseOutcome) = run(true) { before ->
        val outcome = action(before.session ?: gitOpsError(R.string.gitops_rebase_refresh_session))
        before.copy(plan = null, session = outcome.session, selected = null, draft = "", resolutionPath = null, message = outcome.notice, status = outcome.status)
    }
    fun edit(conflict: Conflict) {
        if (!_state.value.busy && !conflict.isBinaryOrTooLarge && _state.value.session?.conflicts?.any { it == conflict } == true)
            _state.value = _state.value.copy(selected = conflict, draft = conflict.incomingContent, resolutionPath = conflict.theirs?.path ?: conflict.filePath)
    }
    fun resolutionPath(value: String) {
        val current = _state.value; val conflict = current.selected ?: return
        if (!current.busy && conflict.isRenameRename && value in listOfNotNull(conflict.ours?.path,conflict.theirs?.path))
            _state.value = current.copy(resolutionPath = value)
    }
    fun draft(value: String) { if (!_state.value.busy) _state.value = _state.value.copy(draft = value) }
    fun closeEditor() { if (!_state.value.busy) _state.value = _state.value.copy(selected = null, draft = "", resolutionPath = null) }
    fun resolve(path: String, strategy: ConflictResolutionStrategy) = run(true) { before ->
        withContext(NonCancellable) {
            RepositoryOperationCoordinator.withRepository(before.path) {
                val expected = before.session ?: gitOpsError(R.string.gitops_rebase_refresh_session)
                gitOpsCheck(rebase.session(before.path)?.revision == expected.revision, R.string.gitops_rebase_conflict_changed)
                val resolution = if (strategy == ConflictResolutionStrategy.MANUAL) {
                    val selected = before.selected ?: gitOpsError(R.string.gitops_rebase_open_editor)
                    gitOpsCheck(selected.filePath == path, R.string.gitops_rebase_choose_conflict)
                    conflicts.resolveConflictWithPath(before.path,path,before.resolutionPath ?: gitOpsError(R.string.gitops_rebase_choose_filename),before.draft)
                } else conflicts.resolveConflict(before.path,path,strategy,null)
                before.copy(session = rebase.session(before.path), selected = null, draft = "", resolutionPath = null, error = null, status = null, message = resolution.getOrThrow().message.takeIf { it.isNotBlank() })
            }
        }
    }
    private fun run(mutation: Boolean, action: suspend (State) -> State) {
        val before = _state.value
        if (before.busy || before.path.isBlank()) return
        val request = generation
        _state.value = before.copy(busy = true, mutating = mutation, error = null, problem = null, message = if(mutation)null else before.message, status = if(mutation)null else before.status)
        viewModelScope.launch {
            try {
                val after = withContext(Dispatchers.IO) { action(before) }
                if (request == generation) _state.value = after.copy(busy = false, mutating = false, error = null, problem = null)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                val safe = when (e) {
                    is GitOpsFailure -> e.text
                    is IllegalArgumentException, is IllegalStateException, is com.bontecou.syncmd.data.models.DirtyRepoException ->
                        e.message?.let { UiText.Raw(it) } ?: UiText.res(R.string.gitops_rebase_needs_review)
                    else -> UiText.res(R.string.gitops_rebase_failed)
                }
                val current = if (mutation && before.selected == null) withContext(Dispatchers.IO) { runCatching { rebase.session(before.path) }.getOrNull() } else before.session
                if (request == generation) _state.value = before.copy(session = current, error = safe, problem = (e as? RebaseFailure)?.problem, message = if(mutation)null else before.message, status = if(mutation)null else before.status)
            } finally { if (request == generation) _state.value = _state.value.copy(busy = false, mutating = false) }
        }
    }
}

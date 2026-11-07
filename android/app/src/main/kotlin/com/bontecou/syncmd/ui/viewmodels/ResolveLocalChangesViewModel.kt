package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.security.RepositoryAuthors
import com.bontecou.syncmd.services.git.DiffService
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.ui.util.RepositorySelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Thrown when the screen that asked for the commit no longer owns the repository selection. */
class LocalChangesRequestSupersededException : IllegalStateException("The selected repository changed. Review local changes again.")

/**
 * iOS's "Resolve" for a pull blocked by local edits: commit every working-tree change locally.
 * The caller then opens the reviewed remote merge, so merging and publishing still need explicit consent.
 */
class LocalChangesCommit(private val diff: DiffService, private val author: suspend (String) -> Pair<String, String>) {
    suspend fun commitAll(path: String, message: String, isCurrent: () -> Boolean) {
        require(message.isNotBlank()) { "Commit message cannot be empty" }
        // One lease covers ownership, staging and the commit, so a queued request cannot act on a replaced selection.
        RepositoryOperationCoordinator.withRepository(path) {
            if (!isCurrent()) throw LocalChangesRequestSupersededException()
            diff.stageAll(path).getOrThrow()
            val (name, email) = author(path)
            diff.commit(path, message, name, email).getOrThrow()
        }
    }
}

@HiltViewModel
class ResolveLocalChangesViewModel @Inject constructor(diffService: DiffService, authors: RepositoryAuthors) : ViewModel() {
    private val commit = LocalChangesCommit(diffService) { authors.resolve(it) }
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _failure = MutableStateFlow<Exception?>(null)
    val failure = _failure.asStateFlow()
    @Volatile private var owner: RepositorySelection? = null

    /** Binds requests to the screen's current selection; replacing it invalidates queued work. */
    fun bind(selection: RepositorySelection?) { if (owner !== selection) { owner = selection; _failure.value = null } }
    fun clearFailure() { _failure.value = null }

    fun commitAll(selection: RepositorySelection, message: String, onCommitted: () -> Unit) {
        if (_busy.value || owner !== selection) return
        _busy.value = true; _failure.value = null
        viewModelScope.launch {
            try {
                commit.commitAll(selection.path, message.trim()) { owner === selection }
                if (owner === selection) onCommitted()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (owner === selection) _failure.value = e
            } finally { _busy.value = false }
        }
    }
}

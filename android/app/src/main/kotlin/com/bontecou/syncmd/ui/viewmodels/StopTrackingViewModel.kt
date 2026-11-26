package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.GitFileStatusKind
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.domain.repository.UntrackRepository
import com.bontecou.syncmd.security.RepositoryAuthors
import com.bontecou.syncmd.services.git.GitIgnoreFile
import com.bontecou.syncmd.services.git.GitUntrackException
import com.bontecou.syncmd.services.git.GitUntrackResult
import com.bontecou.syncmd.services.git.GitUntrackTarget
import com.bontecou.syncmd.ui.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Files Git already tracks or has staged; untracked and conflicted files have nothing to stop
 * tracking, and `.gitignore` itself can't be ignored (same rule as iOS `VaultView.canStopTracking`).
 */
fun canStopTracking(entry: GitStatusEntry): Boolean =
    !entry.isConflicted && entry.kind != GitFileStatusKind.UNTRACKED && entry.filePath != GitIgnoreFile.NAME

/**
 * The same rule for the diff screen, from its staged and unstaged rows: a path with only an unstaged
 * "added" row is untracked. Conflicts are refused by the service with their own message.
 */
fun canStopTracking(files: List<com.bontecou.syncmd.data.models.FileDiff>, path: String): Boolean {
    val rows = files.filter { it.filePath == path }
    if (rows.isEmpty() || path == GitIgnoreFile.NAME) return false
    return rows.any { it.isStaged } || rows.any { it.status != com.bontecou.syncmd.data.models.DiffStatus.ADDED }
}

/** Text for a failed stop-tracking request; typed failures get their own copy. */
fun stopTrackingErrorText(failure: Throwable): UiText = when (failure) {
    is GitUntrackException.InvalidPath -> UiText.res(R.string.stop_tracking_error_invalid, failure.displayName)
    is GitUntrackException.NotTracked -> UiText.res(R.string.stop_tracking_error_not_tracked, failure.displayName)
    is GitUntrackException.OperationInProgress -> UiText.res(R.string.stop_tracking_error_busy)
    is GitUntrackException.Conflicts -> UiText.res(R.string.stop_tracking_error_conflicts)
    else -> UiText.of(failure, R.string.gitops_operation_failed)
}

/**
 * "Stop tracking in Git?" (issue #149): the choices for one changed file (the file, its folder, the
 * Obsidian workspace pattern) or, from the Git tab suggestion, the workspace pattern alone. Also
 * counts tracked workspace files for that suggestion.
 */
@HiltViewModel
class StopTrackingViewModel internal constructor(
    private val repository: UntrackRepository,
    private val author: suspend (String) -> Pair<String, String>,
) : ViewModel() {
    @Inject constructor(repository: UntrackRepository, authors: RepositoryAuthors) : this(repository, { authors.resolve(it) })

    data class State(
        val repoPath: String? = null,
        /** The changed file the sheet was opened from, or null for the workspace suggestion. */
        val path: String? = null,
        val options: List<GitUntrackTarget> = emptyList(),
        val choice: GitUntrackTarget? = null,
        /** Tracked paths per option other than the single file, once counted. */
        val counts: Map<GitUntrackTarget, Int> = emptyMap(),
        val working: Boolean = false,
        val error: UiText? = null,
    ) {
        val isOpen: Boolean get() = repoPath != null
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    /** Tracked `.obsidian/workspace*.json` files in [suggestionPath], for the Git tab suggestion card. */
    private val _workspaceFiles = MutableStateFlow(0)
    val workspaceFiles = _workspaceFiles.asStateFlow()
    private var suggestionPath: String? = null
    private var countJob: Job? = null

    fun open(repoPath: String, path: String?) {
        if (_state.value.working || repoPath.isBlank()) return
        val options = GitUntrackTarget.options(path)
        _state.value = State(repoPath, path, options, GitUntrackTarget.defaultChoice(path))
        countJob?.cancel()
        countJob = viewModelScope.launch {
            for (option in options) {
                if (path != null && option == GitUntrackTarget.File(path)) continue
                val count = repository.trackedPaths(repoPath, option).getOrNull()?.size ?: continue
                val current = _state.value
                if (current.repoPath == repoPath && current.path == path) _state.value = current.copy(counts = current.counts + (option to count))
            }
        }
    }

    fun choose(target: GitUntrackTarget) {
        val current = _state.value
        if (!current.working && target in current.options) _state.value = current.copy(choice = target, error = null)
    }

    fun dismiss() {
        if (_state.value.working) return
        countJob?.cancel()
        _state.value = State()
    }

    /** Commits the stop-tracking change; [onDone] runs after the sheet has closed. */
    fun confirm(onDone: (GitUntrackResult) -> Unit = {}) {
        val current = _state.value
        val repoPath = current.repoPath ?: return
        val target = current.choice ?: return
        if (current.working) return
        _state.value = current.copy(working = true, error = null)
        viewModelScope.launch {
            try {
                val (name, email) = author(repoPath)
                val result = repository.stopTracking(repoPath, target, name, email).getOrThrow()
                _state.value = State()
                if (repoPath == suggestionPath) refreshWorkspaceFiles(repoPath)
                onDone(result)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(working = false, error = stopTrackingErrorText(e))
            }
        }
    }

    fun loadWorkspaceSuggestion(repoPath: String) {
        if (repoPath.isBlank()) return
        if (suggestionPath != repoPath) _workspaceFiles.value = 0
        suggestionPath = repoPath
        refreshWorkspaceFiles(repoPath)
    }

    private fun refreshWorkspaceFiles(repoPath: String) {
        viewModelScope.launch {
            val count = repository.trackedPaths(repoPath, GitUntrackTarget.ObsidianWorkspace).getOrNull()?.size ?: 0
            if (suggestionPath == repoPath) _workspaceFiles.value = count
        }
    }
}

package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.security.RepositoryAuthors
import com.bontecou.syncmd.services.git.HistoryMarks
import com.bontecou.syncmd.services.git.VaultInsights
import com.bontecou.syncmd.services.git.VaultOverview
import com.bontecou.syncmd.sync.VaultSyncRun
import com.bontecou.syncmd.sync.VaultSyncRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The vault's sync state for the Changes tab, the commit sheet and History: local facts from
 * [VaultInsights] (per-file +/− counts, ahead/behind since the last fetch, when that fetch was,
 * the latest commit) and the app-wide [VaultSyncRunner] run for this vault.
 */
@HiltViewModel
class VaultSyncViewModel @Inject constructor(
    private val runner: VaultSyncRunner,
    private val authors: RepositoryAuthors,
) : ViewModel() {
    private val path = MutableStateFlow("")
    private val _overview = MutableStateFlow<VaultOverview?>(null)
    /** Null until read; always for [repositoryPath]. */
    val overview: StateFlow<VaultOverview?> = _overview.asStateFlow()
    private val _authorName = MutableStateFlow<String?>(null)
    val authorName: StateFlow<String?> = _authorName.asStateFlow()
    private val _marks = MutableStateFlow<HistoryMarks?>(null)
    val historyMarks: StateFlow<HistoryMarks?> = _marks.asStateFlow()
    val repositoryPath: StateFlow<String> = path.asStateFlow()

    /** This vault's sync run, live while it runs and kept after it ends until dismissed. */
    val run: StateFlow<VaultSyncRun?> = combine(path, runner.runs) { p, runs -> runs[p] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var readJob: Job? = null
    private var marksJob: Job? = null
    private var marksFor: List<String> = emptyList()

    fun setRepositoryPath(repoPath: String) {
        if (path.value != repoPath) {
            path.value = repoPath
            _overview.value = null; _authorName.value = null; _marks.value = null; marksFor = emptyList()
        }
        refresh()
    }

    fun refresh() {
        val repo = path.value.takeIf { it.isNotBlank() } ?: return
        readJob?.cancel()
        readJob = viewModelScope.launch {
            try {
                val read = withContext(Dispatchers.IO) { VaultInsights.overview(repo) }
                if (path.value == repo) _overview.value = read
                if (_authorName.value == null) {
                    val name = withContext(Dispatchers.IO) { runCatching { authors.resolve(repo).first }.getOrNull() }
                    if (path.value == repo) _authorName.value = name
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                // Optional decorations: the status read owns error reporting.
            }
        }
        if (marksFor.isNotEmpty()) loadHistoryMarks(marksFor, force = true)
    }

    /** Reads unpushed commits, the upstream tip, tags and per-commit file counts for History. */
    fun loadHistoryMarks(commits: List<String>, force: Boolean = false) {
        val repo = path.value.takeIf { it.isNotBlank() } ?: return
        if (!force && commits == marksFor && _marks.value != null) return
        marksFor = commits
        marksJob?.cancel()
        marksJob = viewModelScope.launch {
            try {
                val marks = withContext(Dispatchers.IO) { VaultInsights.historyMarks(repo, commits) }
                if (path.value == repo && marksFor == commits) _marks.value = marks
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { }
        }
    }

    fun commitAndPush(message: String, files: Int) = runner.commitAndPush(path.value, message, files)
    fun push() = runner.push(path.value)
    fun pull() = runner.pull(path.value)
    fun fetchAndFastForward() = runner.fetchAndFastForward(path.value)
    fun pullThenPush() = runner.pullThenPush(path.value)
    fun pushToBranch() = runner.pushToBranch(path.value)
    fun cancel() = runner.cancel(path.value)
    fun dismiss() = runner.dismiss(path.value)
}

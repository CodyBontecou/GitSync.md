package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.services.git.GitCommitLine
import com.bontecou.syncmd.services.git.GitConflictProvenance
import com.bontecou.syncmd.services.git.GitRebaseProgress
import com.bontecou.syncmd.services.git.GitStashDetail
import com.bontecou.syncmd.services.git.GitTagDetail
import com.bontecou.syncmd.services.git.GitWorkspaceDetails
import com.bontecou.syncmd.services.git.GitWorkspaceSummary
import com.bontecou.syncmd.services.git.lfs.LfsRepository
import com.bontecou.syncmd.security.RepositoryAuthors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Read-only facts behind the Material 3 Git tab and its deeper screens: branch counts and upstream
 * ahead/behind, stash/tag details, LFS size and locks, the commit author, conflict provenance and
 * rebase progress. Mutations stay in the existing Git view models; this one only re-reads.
 */
@HiltViewModel
class GitWorkspaceViewModel @Inject constructor(
    private val lfs: LfsRepository,
    private val authors: RepositoryAuthors,
) : ViewModel() {
    /** LFS usage: bytes of every LFS object in the index, and server locks when they could be listed. */
    data class LfsUsage(val files: Int, val bytes: Long, val locked: Int?)

    data class State(
        val path: String = "",
        val loading: Boolean = false,
        val summary: GitWorkspaceSummary? = null,
        val stashes: List<GitStashDetail> = emptyList(),
        val commitTimes: Map<String, Long> = emptyMap(),
        val tags: List<GitTagDetail> = emptyList(),
        val lfs: LfsUsage? = null,
        val author: String? = null,
        val provenance: Map<String, GitConflictProvenance> = emptyMap(),
        val rebase: GitRebaseProgress? = null,
        val ranges: Map<String, List<GitCommitLine>> = emptyMap(),
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var lockedFor: String? = null

    /** Re-read the summary for [path]. [tagTargets] are commit IDs whose times the Tags screen shows. */
    fun load(path: String, tagTargets: Collection<String> = emptyList(), stashes: Boolean = false, tags: Boolean = false) {
        if (path.isBlank()) return
        val request = if (_state.value.path != path) {
            job?.cancel(); lockedFor = null
            _state.value = State(path = path)
            ++generation
        } else generation
        _state.value = _state.value.copy(loading = true)
        job = viewModelScope.launch {
            try {
                val (summary, stashList, times) = withContext(Dispatchers.IO) {
                    Triple(
                        GitWorkspaceDetails.summary(path),
                        if (stashes) GitWorkspaceDetails.stashes(path) else null,
                        if (tagTargets.isNotEmpty()) GitWorkspaceDetails.commitTimes(path, tagTargets) else null,
                    )
                }
                val tagList = if (tags) withContext(Dispatchers.IO) { GitWorkspaceDetails.tags(path) } else null
                if (request != generation) return@launch
                _state.value = _state.value.copy(summary = summary, stashes = stashList ?: _state.value.stashes, tags = tagList ?: _state.value.tags,
                    commitTimes = _state.value.commitTimes + times.orEmpty())
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { /* The screens keep their last facts; the owning view models report errors. */ }
            finally { if (request == generation) _state.value = _state.value.copy(loading = false) }
        }
        loadAuthor(path, request)
        loadLfs(path, request)
    }

    private fun loadAuthor(path: String, request: Long) = viewModelScope.launch {
        val name = runCatching { withContext(Dispatchers.IO) { authors.resolve(path).first } }.getOrNull()
        if (request == generation && name != null) _state.value = _state.value.copy(author = name.ifBlank { null })
    }

    /** Size is local. Locks are listed once per visit, best effort, and never block the size. */
    private fun loadLfs(path: String, request: Long) = viewModelScope.launch {
        val files = runCatching { withContext(Dispatchers.IO) { lfs.inspect(path) } }.getOrNull() ?: return@launch
        if (request != generation) return@launch
        val previous = _state.value.lfs
        _state.value = _state.value.copy(lfs = LfsUsage(files.size, files.sumOf { it.pointer.size }, previous?.locked))
        if (files.isEmpty() || lockedFor == path) return@launch
        lockedFor = path
        val locked = runCatching {
            withContext(Dispatchers.IO) { withTimeoutOrNull(8_000) { lfs.listLocks(path).locks.size } }
        }.getOrNull() ?: return@launch
        if (request == generation) _state.value = _state.value.copy(lfs = _state.value.lfs?.copy(locked = locked))
    }

    fun loadProvenance(path: String, filePath: String) = viewModelScope.launch {
        val request = generation
        val value = runCatching { withContext(Dispatchers.IO) { GitWorkspaceDetails.conflictProvenance(path, filePath) } }.getOrNull() ?: return@launch
        if (request == generation && _state.value.path == path) _state.value = _state.value.copy(provenance = _state.value.provenance + (filePath to value))
    }

    fun loadRebase(path: String) = viewModelScope.launch {
        if (_state.value.path != path) load(path)
        val request = generation
        val value = runCatching { withContext(Dispatchers.IO) { GitWorkspaceDetails.rebaseProgress(path) } }.getOrNull()
        if (request == generation) _state.value = _state.value.copy(rebase = value)
    }

    /** Commits in `exclude..tip`, keyed "tip..exclude" in [State.ranges]. */
    fun loadRange(path: String, tip: String, exclude: String?) = viewModelScope.launch {
        val request = generation
        val value = runCatching { withContext(Dispatchers.IO) { GitWorkspaceDetails.commits(path, tip, exclude) } }.getOrNull() ?: return@launch
        if (request == generation) _state.value = _state.value.copy(ranges = _state.value.ranges + (rangeKey(tip, exclude) to value))
    }

    companion object {
        fun rangeKey(tip: String, exclude: String?) = "$tip..${exclude.orEmpty()}"
    }
}

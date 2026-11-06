package com.bontecou.syncmd.ui.adaptive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.domain.repository.PullRepository
import com.bontecou.syncmd.observation.RepositoryObservations
import com.bontecou.syncmd.services.git.DiffService
import com.bontecou.syncmd.services.git.FileLineCount
import com.bontecou.syncmd.services.git.VaultInsights
import com.bontecou.syncmd.storage.AutoCommitPreferences
import com.bontecou.syncmd.sync.VaultSyncRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * When the split-screen panel may commit on its own: auto-commit is on for the vault, there are
 * changes and no conflicts, no sync is running, nothing changed for [PAUSE_MS], and this exact
 * set of edits hasn't been auto-committed already.
 */
object AutoCommitPolicy {
    const val PAUSE_MS = 2 * 60_000L
    /** A file written this recently reads "typing…". */
    const val TYPING_MS = 5_000L

    fun isTyping(modifiedAt: Long?, now: Long): Boolean = modifiedAt != null && now - modifiedAt < TYPING_MS

    fun shouldCommit(
        enabled: Boolean,
        changes: Int,
        conflicted: Boolean,
        running: Boolean,
        lastActivity: Long?,
        now: Long,
        signature: Long,
        lastCommitted: Long?,
    ): Boolean = enabled && changes > 0 && !conflicted && !running && lastActivity != null &&
        now - lastActivity >= PAUSE_MS && signature != lastCommitted
}

/**
 * The live vault behind the split-screen panel (board FoldSplit): the changed files with their
 * +/− counts and modification times ("typing…"), the commits waiting to push, and the
 * debounced auto-commit that stages everything and runs Commit & push after a 2-minute pause.
 * The watch only runs while the panel is on screen ([watch] is called from its effect).
 */
@HiltViewModel
class SplitWatchViewModel @Inject constructor(
    private val preferences: AutoCommitPreferences,
    private val diffService: DiffService,
    private val runner: VaultSyncRunner,
    private val pull: PullRepository,
) : ViewModel() {
    data class FileUi(val entry: GitStatusEntry, val count: FileLineCount?, val modifiedAt: Long?)
    data class State(
        val path: String = "",
        val branch: String? = null,
        val ahead: Int = 0,
        val files: List<FileUi> = emptyList(),
        val now: Long = System.currentTimeMillis(),
        /** Latest change seen: a file's modification time or the moment the change set last moved. */
        val lastActivity: Long? = null,
    ) {
        val typing: Int get() = files.count { AutoCommitPolicy.isTyping(it.modifiedAt, now) }
        val conflicted: Boolean get() = files.any { it.entry.isConflicted }
        val signature: Long get() = files.fold(17L) { acc, f -> acc * 31 + f.entry.filePath.hashCode() * 7 + (f.modifiedAt ?: 0L) }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val path = MutableStateFlow("")
    val autoCommit: StateFlow<Boolean> = combine(path, preferences.enabled) { p, on -> p in on }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private var lastCommitted: Long? = null

    fun setAutoCommit(on: Boolean) { path.value.takeIf { it.isNotBlank() }?.let { preferences.setEnabled(it, on) } }

    /** Watches [repoPath] until cancelled; [message] names an auto-commit. */
    suspend fun watch(repoPath: String, message: () -> String) {
        if (repoPath.isBlank()) return
        if (path.value != repoPath) { path.value = repoPath; _state.value = State(repoPath); lastCommitted = null }
        coroutineScope {
            launch { RepositoryObservations.observe(repoPath).collect { read(repoPath) } }
            read(repoPath)
            while (isActive) {
                delay(2_000)
                touch(repoPath)
                maybeCommit(repoPath, message)
            }
        }
    }

    private suspend fun read(repoPath: String) {
        try {
            val status = withContext(Dispatchers.IO) { pull.getStatus(repoPath).getOrNull() } ?: return
            val overview = withContext(Dispatchers.IO) { runCatching { VaultInsights.overview(repoPath) }.getOrNull() }
            val files = withContext(Dispatchers.IO) {
                status.allChanges.distinctBy { it.filePath }.sortedBy { it.filePath }.map { entry ->
                    FileUi(entry, overview?.lineCounts?.get(entry.filePath), modified(repoPath, entry.filePath))
                }
            }
            publish(repoPath, State(repoPath, overview?.branch ?: status.currentBranch, overview?.ahead ?: 0, files))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { }
    }

    /** Re-reads modification times between observations so "typing…" fades and the pause is measured. */
    private suspend fun touch(repoPath: String) {
        val current = _state.value.takeIf { it.path == repoPath } ?: return
        val files = withContext(Dispatchers.IO) { current.files.map { it.copy(modifiedAt = modified(repoPath, it.entry.filePath)) } }
        publish(repoPath, current.copy(files = files))
    }

    private fun publish(repoPath: String, next: State) {
        val previous = _state.value
        val now = System.currentTimeMillis()
        val newest = next.files.mapNotNull { it.modifiedAt }.maxOrNull()
        // A changed set of edits (or a file whose time moved) counts as activity now, even if mtimes lag.
        val moved = previous.path != repoPath || previous.signature != next.signature
        val activity = listOfNotNull(newest, if (moved) now else previous.lastActivity).maxOrNull()
        _state.value = next.copy(now = now, lastActivity = activity)
    }

    private fun maybeCommit(repoPath: String, message: () -> String) {
        val s = _state.value.takeIf { it.path == repoPath } ?: return
        val running = runner.runs.value[repoPath]?.running == true
        if (!AutoCommitPolicy.shouldCommit(autoCommit.value, s.files.size, s.conflicted, running, s.lastActivity, s.now, s.signature, lastCommitted)) return
        lastCommitted = s.signature
        val files = s.files.size
        val text = message()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { diffService.stageAll(repoPath).getOrThrow() }
                runner.commitAndPush(repoPath, text, files)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                // Staging failed (a lock, a vanished file): try again after the next pause.
                lastCommitted = null
            }
        }
    }

    private fun modified(repoPath: String, filePath: String): Long? =
        File(repoPath, filePath).takeIf { it.isFile }?.lastModified()?.takeIf { it > 0 }
}

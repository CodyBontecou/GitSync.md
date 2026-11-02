package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.publication.*
import com.bontecou.syncmd.storage.AndroidFolderPublications
import com.bontecou.syncmd.storage.RepositoryLocations
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

enum class PublicationWork { LOAD, INSPECT, SAVE, REFRESH, PREPARE, CREATE, UPLOAD, REGISTER, RECONCILE, ADOPT, RECONNECT }

@HiltViewModel
class FolderPublicationViewModel(
    private val runtime: AndroidFolderPublications,
    val auth: GitHubAuthManager,
    private val saved: SavedStateHandle,
    private val locations: RepositoryLocations
) : ViewModel() {
    @Inject constructor(@ApplicationContext context: Context, auth: GitHubAuthManager, saved: SavedStateHandle) :
        this(AndroidFolderPublications(context, auth), auth, saved, RepositoryLocations(context))

    data class State(val records: List<FolderPublicationRecord> = emptyList(), val selected: String? = null,
        val work: PublicationWork? = null, val critical: Boolean = false, val stopping: Boolean = false,
        val error: String? = null, val loadFailed: Boolean = false, val candidate: PublicationRemote? = null,
        val editorVersion: Int = 0) {
        val busy get() = work != null
        val record get() = records.singleOrNull { it.id == selected }
    }
    private val mutable = MutableStateFlow(State(selected = saved["publication.id"]))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    init { reload() }

    fun reload() = run(PublicationWork.LOAD) { mutable.value = mutable.value.copy(editorVersion = mutable.value.editorVersion + 1) }
    fun select(id: String?) {
        if (mutable.value.busy) return
        require(id == null || mutable.value.records.any { it.id == id })
        saved["publication.id"] = id
        mutable.value = mutable.value.copy(selected = id, candidate = null, error = null, editorVersion = mutable.value.editorVersion + 1)
    }
    fun error(message: String) { if (!mutable.value.busy) mutable.value = mutable.value.copy(error = message) }
    fun clearCandidate() { if (!mutable.value.busy) mutable.value = mutable.value.copy(candidate = null) }
    fun cancel() { if (mutable.value.busy) { mutable.value = mutable.value.copy(stopping = true); job?.cancel() } }

    fun chooseFolder(uri: Uri, accountId: String) = run(PublicationWork.INSPECT) {
        val folder = withContext(Dispatchers.IO) { locations.rememberPublicationFolder(uri) }
        val record = runtime.begin(folder, accountId)
        saved["publication.id"] = record.id
        mutable.value = mutable.value.copy(selected = record.id, editorVersion = mutable.value.editorVersion + 1)
    }
    fun reconnect(uri: Uri) {
        val expected = mutable.value.record ?: return
        run(PublicationWork.RECONNECT) {
            val folder = withContext(Dispatchers.IO) { locations.rememberPublicationFolder(uri) }
            runtime.reconnect(expected, folder)
        }
    }
    fun toggle(path: String) {
        val expected = mutable.value.record ?: return
        if (expected.phase != FolderPublicationPhase.REVIEW) return
        val file = expected.input.review.files.singleOrNull { it.path == path && it.eligible } ?: return
        val selected = if (file.path in expected.input.selected) expected.input.selected - path else expected.input.selected + path
        run(PublicationWork.SAVE) { runtime.coordinator.updateReview(expected, expected.copy(input = expected.input.copy(selected = selected))) }
    }
    fun saveReview(replacement: FolderPublicationRecord, onSaved: () -> Unit = {}) {
        val expected = mutable.value.record ?: return
        run(PublicationWork.SAVE, onSuccess = onSaved) { runtime.coordinator.updateReview(expected, replacement) }
    }
    fun updateDestination(name: String, isPrivate: Boolean, onSaved: () -> Unit = {}) {
        val expected = mutable.value.record ?: return
        run(PublicationWork.SAVE, onSuccess = onSaved) { runtime.coordinator.updateDestination(expected, name, isPrivate) }
    }
    fun refresh() {
        val expected = mutable.value.record ?: return
        run(PublicationWork.REFRESH) { runtime.coordinator.refreshReview(expected) }
    }
    fun discardReview() {
        val expected = mutable.value.record ?: return
        run(PublicationWork.SAVE) {
            runtime.coordinator.discardReview(expected)
            saved["publication.id"] = null
            mutable.value = mutable.value.copy(selected = null, editorVersion = mutable.value.editorVersion + 1)
        }
    }
    fun prepare(replacement: FolderPublicationRecord) {
        val expected = mutable.value.record ?: return
        run(PublicationWork.PREPARE, critical = true) {
            val reviewed = if (expected.phase == FolderPublicationPhase.REVIEW) runtime.coordinator.updateReview(expected, replacement) else expected
            runtime.coordinator.prepare(reviewed, runtime.token(reviewed))
        }
    }
    fun reconcile() {
        val expected = mutable.value.record ?: return
        run(PublicationWork.RECONCILE) {
            val candidate = runtime.coordinator.candidate(expected, runtime.token(expected))
            mutable.value = mutable.value.copy(candidate = candidate)
        }
    }
    fun adopt() {
        val expected = mutable.value.record ?: return
        val candidate = mutable.value.candidate ?: return
        run(PublicationWork.ADOPT) { runtime.coordinator.adopt(expected, candidate, runtime.token(expected)) }
    }
    fun publish(register: suspend (FolderPublicationRecord) -> Unit, onReady: (String) -> Unit) {
        val expected = mutable.value.record ?: return
        var path: String? = null
        val work = when(expected.phase) {
            FolderPublicationPhase.PREPARED -> PublicationWork.CREATE
            FolderPublicationPhase.REMOTE_CREATED, FolderPublicationPhase.PUSH_UNKNOWN -> PublicationWork.UPLOAD
            else -> PublicationWork.REGISTER
        }
        run(work, critical = true, onSuccess = { path?.let(onReady) }) {
            var record = expected
            fun step(work: PublicationWork) { mutable.value = mutable.value.copy(work = work) }
            if (record.phase == FolderPublicationPhase.PREPARED) record = runtime.coordinator.create(record, runtime.token(record))
            if (record.phase in setOf(FolderPublicationPhase.REMOTE_CREATED, FolderPublicationPhase.PUSH_UNKNOWN)) {
                step(PublicationWork.UPLOAD); record = runtime.coordinator.push(record, runtime.token(record))
            }
            require(record.phase in setOf(FolderPublicationPhase.PUBLISHED, FolderPublicationPhase.COMPLETED))
            step(PublicationWork.REGISTER)
            record = runtime.coordinator.complete(record, if (record.phase == FolderPublicationPhase.COMPLETED) "" else runtime.token(record), register)
            path = record.input.review.path
        }
    }

    /** Gate before launch; catch cancellation only after the coordinator has retained uncertain intent. */
    private fun run(work: PublicationWork, critical: Boolean = false, onSuccess: () -> Unit = {}, action: suspend () -> Unit) {
        if (mutable.value.busy || mutable.value.loadFailed && work != PublicationWork.LOAD) return
        val request = ++generation
        mutable.value = mutable.value.copy(work = work, critical = critical, stopping = false, error = null, candidate = null)
        job = viewModelScope.launch {
            var failure: String? = null
            var cancelled = false
            try { action() }
            catch (_: CancellationException) { cancelled = true; failure = "Stopped. Saved progress is ready to review; the last network result may be uncertain." }
            catch (error: Exception) { failure = error.message ?: "Publication needs attention. Saved files were kept." }
            finally {
                // Keep the return from IO inside this scope too: returning directly into a cancelled
                // caller would discard the loaded records through prompt cancellation.
                withContext(NonCancellable) {
                    var loadFailed = false
                    val records = try { withContext(Dispatchers.IO) { runtime.coordinator.recover() } }
                        catch (error: Exception) { loadFailed = true; failure = error.message ?: "Saved publication progress is unavailable"; mutable.value.records }
                    withContext(Dispatchers.Main.immediate) {
                        if (generation == request) mutable.value = mutable.value.copy(records = records,
                            selected = mutable.value.selected?.takeIf { id -> records.any { it.id == id } },
                            work = null, critical = false, stopping = false, error = failure, loadFailed = loadFailed)
                    }
                }
            }
            if (!cancelled && failure == null && generation == request) onSuccess()
        }
    }
}

package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.services.files.FileDocument
import com.bontecou.syncmd.services.files.FileEntryInsight
import com.bontecou.syncmd.services.files.FileInsights
import com.bontecou.syncmd.services.files.HeadText
import com.bontecou.syncmd.services.files.RepositoryFile
import com.bontecou.syncmd.services.files.RepositoryFiles
import com.bontecou.syncmd.services.files.RepositoryFileIssue
import com.bontecou.syncmd.services.files.VaultHeader
import com.bontecou.syncmd.services.files.repositoryFileIssue
import com.bontecou.syncmd.services.git.lfs.LfsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** A note template from the vault's `templates/` folder, or a built-in one when there is none. */
data class NoteTemplate(val name: String, val content: String, val builtIn: Boolean = false)

data class FileBrowserState(
    val entries: List<RepositoryFile> = emptyList(),
    val isLoading: Boolean = true,
    val isMutating: Boolean = false,
    val showHidden: Boolean = false,
    val error: RepositoryFileIssue? = null,
    /** Age, folder size and LFS state per entry path. */
    val insights: Map<String, FileEntryInsight> = emptyMap(),
    val header: VaultHeader = VaultHeader(null, null),
    /** Vault templates; empty means "use the built-in ones". */
    val templates: List<NoteTemplate> = emptyList(),
    /** LFS pointers being downloaded. */
    val fetching: Set<String> = emptySet(),
    val fetchFailed: Boolean = false,
    /** Name of the file a Fetch just downloaded, for a one-off confirmation. */
    val fetched: String? = null,
    /** Uncommitted files in the vault, for the Changes tab badge. */
    val changes: Int? = null,
)

@HiltViewModel
class FilesViewModel @Inject constructor(private val lfs: LfsRepository) : ViewModel() {
    /** Tests and previews: LFS without stored credentials. */
    constructor() : this(LfsRepository())

    private val files = RepositoryFiles()
    private val insights = FileInsights()
    private val _state = MutableStateFlow(FileBrowserState())
    val state = _state.asStateFlow()
    private var root = ""
    private var directory = ""
    private var loadJob: Job? = null

    fun open(repositoryPath: String, relativeDirectory: String) {
        val vaultChanged = root != repositoryPath
        root = repositoryPath
        directory = relativeDirectory
        refresh()
        if (vaultChanged) viewModelScope.launch {
            val header = runCatching { insights.header(repositoryPath) }.getOrNull() ?: return@launch
            if (root == repositoryPath) _state.update { it.copy(header = header) }
        }
    }

    fun refresh() {
        if (root.isBlank() || _state.value.isMutating) return
        loadJob?.cancel()
        val path = root
        val folder = directory
        val showHidden = _state.value.showHidden
        _state.update { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val entries = files.list(path, folder, showHidden)
                _state.update { it.copy(entries = entries, isLoading = false) }
                // Ages, folder sizes and LFS state follow without holding up the list.
                val details = runCatching { insights.entries(path, entries, showHidden) }.getOrDefault(emptyMap())
                val templates = runCatching { loadTemplates(path) }.getOrDefault(emptyList())
                val changes = runCatching { insights.localState(path).changedFiles }.getOrNull()
                _state.update { it.copy(insights = details, templates = templates, changes = changes) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = repositoryFileIssue(e, RepositoryFileIssue.READ_FAILED)) }
            }
        }
    }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        refresh()
    }

    /** True when [name] is already taken in this folder (any case, hidden files included). */
    fun exists(name: String): Boolean {
        if (root.isBlank() || name.isBlank()) return false
        val folder = File(root, directory)
        return folder.listFiles()?.any { it.name.equals(name, ignoreCase = true) } == true
    }

    /** Creates a note (optionally with [content]) or a folder, then hands its path to [onCreated]. */
    fun create(name: String, isDirectory: Boolean, content: String, onCreated: (String) -> Unit) = mutate {
        val path = files.create(root, directory, name, isDirectory)
        if (!isDirectory && content.isNotEmpty()) {
            try {
                files.save(root, files.read(root, path), content)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                // Don't leave an empty note behind: the dialog would then report
                // "already exists" for the name and the real error would be lost.
                runCatching { files.delete(root, files.read(root, path)) }
                throw e
            }
        }
        withContext(Dispatchers.Main.immediate) { onCreated(path) }
    }

    /** Create without content; [onCreated] runs for files only. */
    fun create(name: String, isDirectory: Boolean, onCreated: (String) -> Unit) =
        create(name, isDirectory, "") { path -> if (!isDirectory) onCreated(path) }

    fun rename(path: String, name: String) = mutate { files.rename(root, path, name); Unit }

    fun delete(entry: RepositoryFile) = mutate {
        if (entry.isDirectory) files.deleteEmptyFolder(root, entry.path)
        else files.delete(root, files.read(root, entry.path))
    }

    /** Downloads one LFS object and checks it out in place of its pointer. */
    fun fetch(path: String) {
        val repository = root
        if (repository.isBlank() || path in _state.value.fetching) return
        _state.update { it.copy(fetching = it.fetching + path, fetchFailed = false, fetched = null) }
        viewModelScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { lfs.hydrate(repository, setOf(path)) }
                true
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { false }
            _state.update { it.copy(fetching = it.fetching - path, fetchFailed = !ok, fetched = if (ok) path.substringAfterLast('/') else null) }
            if (ok && root == repository) refresh()
        }
    }

    fun clearError() { _state.update { it.copy(error = null, fetchFailed = false) } }
    fun clearFetched() { _state.update { it.copy(fetched = null) } }

    private fun mutate(operation: suspend () -> Unit) {
        if (_state.value.isMutating) return
        loadJob?.cancel()
        _state.update { it.copy(isMutating = true, isLoading = false, error = null) }
        viewModelScope.launch {
            try {
                operation()
                _state.update { it.copy(isMutating = false) }
                refresh()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _state.update { it.copy(isMutating = false, error = repositoryFileIssue(e, RepositoryFileIssue.OPERATION_FAILED)) }
            }
        }
    }

    private suspend fun loadTemplates(repositoryPath: String): List<NoteTemplate> = withContext(Dispatchers.IO) {
        val folder = File(repositoryPath).listFiles()?.firstOrNull { it.isDirectory && it.name.equals("templates", ignoreCase = true) }
            ?: return@withContext emptyList()
        folder.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("md", ignoreCase = true) && it.length() <= 64 * 1024 && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .sortedBy { it.name.lowercase() }
            .take(12)
            .mapNotNull { file -> runCatching { NoteTemplate(file.nameWithoutExtension, file.readText()) }.getOrNull() }
    }
}

enum class FileEditorMessage { SAVED, RENAMED }

data class FileEditorState(
    val document: FileDocument? = null,
    val draft: String = "",
    val isLoading: Boolean = true,
    /** A blocking operation (rename, delete, a save the user waits on). */
    val isSaving: Boolean = false,
    val error: RepositoryFileIssue? = null,
    val message: FileEditorMessage? = null,
    val deleted: Boolean = false,
    /** An autosave is scheduled or writing; editing continues meanwhile. */
    val autosaving: Boolean = false,
    /** The last save failed; autosave waits until the user saves or reloads. */
    val saveFailed: Boolean = false,
    /** The file as of the last commit. */
    val head: HeadText? = null,
    /** Lines that differ from the last commit, and the new-side line numbers to mark. */
    val linesChanged: Int = 0,
    val changedLines: Set<Int> = emptySet(),
    /** "Vault ↑N": what this phone has to push. */
    val toPush: Int? = null,
) {
    val isDirty: Boolean get() = document?.content != null && draft != document.content
}

@HiltViewModel
class FileEditorViewModel @Inject constructor(private val savedState: androidx.lifecycle.SavedStateHandle) : ViewModel() {
    companion object { const val AUTOSAVE_DELAY_MS = 800L }

    private val files = RepositoryFiles()
    private val insights = FileInsights()
    private val _state = MutableStateFlow(FileEditorState())
    val state = _state.asStateFlow()
    private var root = ""
    private var path = ""
    private var requestedPath = ""
    private var autosaveJob: Job? = null
    private var statsJob: Job? = null
    private var writing = false

    fun open(repositoryPath: String, relativePath: String) {
        if (root == repositoryPath && requestedPath == relativePath && _state.value.document != null) return
        root = repositoryPath
        requestedPath = relativePath
        path = if (savedState.get<String>("sourceRoot") == repositoryPath && savedState.get<String>("sourcePath") == relativePath) {
            savedState.get<String>("currentPath") ?: relativePath
        } else relativePath
        savedState["sourceRoot"] = repositoryPath
        savedState["sourcePath"] = relativePath
        reload()
    }

    fun reload() {
        if (_state.value.isSaving || root.isBlank()) return
        autosaveJob?.cancel()
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val document = files.read(root, path)
                val head = runCatching { insights.headText(root, path) }.getOrNull()
                val toPush = _state.value.toPush
                _state.value = FileEditorState(document = document, draft = document.content.orEmpty(), isLoading = false, head = head, toPush = toPush)
                updateStats()
                refreshLocalState()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = repositoryFileIssue(e, RepositoryFileIssue.READ_FAILED)) }
            }
        }
    }

    fun updateDraft(value: String) {
        val current = _state.value
        if (current.isSaving || current.isLoading || current.document?.content == null || value == current.draft) return
        _state.update { it.copy(draft = value, message = null) }
        updateStats()
        if (!current.saveFailed) scheduleAutosave()
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        _state.update { it.copy(autosaving = true) }
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            write()
        }
    }

    /** Writes the current draft. Edits made during the write stay in the draft and are saved next. */
    private suspend fun write(): Boolean {
        val snapshot = _state.value
        val document = snapshot.document ?: return false
        if (!snapshot.isDirty) { _state.update { it.copy(autosaving = false) }; return true }
        if (writing) return false
        writing = true
        return try {
            val saved = files.save(root, document, snapshot.draft)
            _state.update { it.copy(document = saved, saveFailed = false) }
            if (_state.value.isDirty) scheduleAutosave() else _state.update { it.copy(autosaving = false) }
            refreshLocalState()
            true
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            _state.update { it.copy(autosaving = false, saveFailed = true, error = repositoryFileIssue(e, RepositoryFileIssue.OPERATION_FAILED)) }
            false
        } finally { writing = false }
    }

    /** Saves now (Back, Commit, "Save now"); [onSaved] runs only once the draft is safely on disk. */
    fun save(onSaved: () -> Unit = {}) {
        val snapshot = _state.value
        if (snapshot.document == null || snapshot.isSaving) return
        if (!snapshot.isDirty) { onSaved(); return }
        autosaveJob?.cancel()
        _state.update { it.copy(isSaving = true, error = null, message = null) }
        viewModelScope.launch {
            while (writing) delay(20)
            val ok = write()
            autosaveJob?.cancel()
            _state.update { it.copy(isSaving = false, autosaving = false, message = if (ok) FileEditorMessage.SAVED else null) }
            if (ok && !_state.value.isDirty) onSaved()
        }
    }

    fun rename(name: String) {
        val snapshot = _state.value
        val original = snapshot.document ?: return
        autosaveJob?.cancel()
        mutate {
            path = files.rename(root, path, name)
            savedState["currentPath"] = path
            if (snapshot.isDirty) {
                // Renaming changes the path, not the unsaved draft or its base revision.
                // A later save still detects any external changes to the disk content.
                _state.update { it.copy(document = original.copy(path = path), message = FileEditorMessage.RENAMED) }
                scheduleAutosave()
            } else {
                val document = files.read(root, path)
                _state.update { it.copy(document = document, draft = document.content.orEmpty(), message = FileEditorMessage.RENAMED) }
            }
            val head = runCatching { insights.headText(root, path) }.getOrNull()
            _state.update { it.copy(head = head) }
            updateStats()
        }
    }

    fun delete() {
        val document = _state.value.document ?: return
        autosaveJob?.cancel()
        mutate { files.delete(root, document); _state.update { it.copy(deleted = true) } }
    }

    fun clearError() { _state.update { it.copy(error = null) } }

    /** Re-reads "Vault ↑N" (after the commit sheet closes, for example). */
    fun refreshLocalState() {
        val repository = root
        if (repository.isBlank()) return
        viewModelScope.launch {
            val local = runCatching { insights.localState(repository) }.getOrNull() ?: return@launch
            val head = runCatching { insights.headText(repository, path) }.getOrNull()
            if (root == repository) {
                val headChanged = head != null && head != _state.value.head
                _state.update { it.copy(toPush = local.toPush, head = head ?: it.head) }
                if (headChanged) updateStats()
            }
        }
    }

    private fun updateStats() {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            delay(150)
            val current = _state.value
            val head = current.head ?: return@launch
            if (!head.text || current.document?.content == null) return@launch
            val draft = current.draft
            val diff = withContext(Dispatchers.Default) { FileInsights.compare(path, if (head.tracked) head.content.orEmpty() else null, draft) }
            if (_state.value.draft == draft) _state.update { it.copy(linesChanged = diff.linesChanged, changedLines = diff.changedLines) }
        }
    }

    private fun mutate(operation: suspend () -> Unit) {
        if (_state.value.isSaving || _state.value.isLoading) return
        _state.update { it.copy(isSaving = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                while (writing) delay(20)
                operation()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = repositoryFileIssue(e, RepositoryFileIssue.OPERATION_FAILED)) }
            } finally {
                _state.update { it.copy(isSaving = false) }
            }
        }
    }
}

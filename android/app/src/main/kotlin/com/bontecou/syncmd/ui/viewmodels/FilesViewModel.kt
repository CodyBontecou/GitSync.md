package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.services.files.FileDocument
import com.bontecou.syncmd.services.files.RepositoryFile
import com.bontecou.syncmd.services.files.RepositoryFiles
import com.bontecou.syncmd.services.files.RepositoryFileIssue
import com.bontecou.syncmd.services.files.repositoryFileIssue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FileBrowserState(
    val entries: List<RepositoryFile> = emptyList(),
    val isLoading: Boolean = true,
    val isMutating: Boolean = false,
    val showHidden: Boolean = false,
    val error: RepositoryFileIssue? = null,
)

@HiltViewModel
class FilesViewModel @Inject constructor() : ViewModel() {
    private val files = RepositoryFiles()
    private val _state = MutableStateFlow(FileBrowserState())
    val state = _state.asStateFlow()
    private var root = ""
    private var directory = ""
    private var loadJob: Job? = null

    fun open(repositoryPath: String, relativeDirectory: String) {
        root = repositoryPath
        directory = relativeDirectory
        refresh()
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

    fun create(name: String, isDirectory: Boolean, onCreated: (String) -> Unit) = mutate {
        val path = files.create(root, directory, name, isDirectory)
        if (!isDirectory) onCreated(path)
    }

    fun rename(path: String, name: String) = mutate { files.rename(root, path, name); Unit }
    fun clearError() { _state.update { it.copy(error = null) } }

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
}

enum class FileEditorMessage { SAVED, RENAMED }

data class FileEditorState(
    val document: FileDocument? = null,
    val draft: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: RepositoryFileIssue? = null,
    val message: FileEditorMessage? = null,
    val deleted: Boolean = false,
) {
    val isDirty: Boolean get() = document?.content != null && draft != document.content
}

@HiltViewModel
class FileEditorViewModel @Inject constructor(private val savedState: androidx.lifecycle.SavedStateHandle) : ViewModel() {
    private val files = RepositoryFiles()
    private val _state = MutableStateFlow(FileEditorState())
    val state = _state.asStateFlow()
    private var root = ""
    private var path = ""
    private var requestedPath = ""

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
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val document = files.read(root, path)
                _state.value = FileEditorState(document = document, draft = document.content.orEmpty(), isLoading = false)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = repositoryFileIssue(e, RepositoryFileIssue.READ_FAILED)) }
            }
        }
    }

    fun updateDraft(value: String) {
        if (!_state.value.isSaving && !_state.value.isLoading) _state.update { it.copy(draft = value, message = null) }
    }

    fun save(onSaved: () -> Unit = {}) {
        val snapshot = _state.value
        val document = snapshot.document ?: return
        if (!snapshot.isDirty || snapshot.isSaving) return
        mutate {
            val saved = files.save(root, document, snapshot.draft)
            _state.update { it.copy(document = saved, draft = saved.content.orEmpty(), message = FileEditorMessage.SAVED) }
            onSaved()
        }
    }

    fun rename(name: String) {
        val snapshot = _state.value
        val original = snapshot.document ?: return
        mutate {
            path = files.rename(root, path, name)
            savedState["currentPath"] = path
            if (snapshot.isDirty) {
                // Renaming changes the path, not the unsaved draft or its base revision.
                // A later save still detects any external changes to the disk content.
                _state.update { it.copy(document = original.copy(path = path), draft = snapshot.draft, message = FileEditorMessage.RENAMED) }
            } else {
                val document = files.read(root, path)
                _state.update { it.copy(document = document, draft = document.content.orEmpty(), message = FileEditorMessage.RENAMED) }
            }
        }
    }

    fun delete() {
        val document = _state.value.document ?: return
        mutate { files.delete(root, document); _state.update { it.copy(deleted = true) } }
    }

    fun clearError() { _state.update { it.copy(error = null) } }

    private fun mutate(operation: suspend () -> Unit) {
        if (_state.value.isSaving || _state.value.isLoading) return
        _state.update { it.copy(isSaving = true, error = null, message = null) }
        viewModelScope.launch {
            try { operation()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = repositoryFileIssue(e, RepositoryFileIssue.OPERATION_FAILED)) }
            } finally {
                _state.update { it.copy(isSaving = false) }
            }
        }
    }
}

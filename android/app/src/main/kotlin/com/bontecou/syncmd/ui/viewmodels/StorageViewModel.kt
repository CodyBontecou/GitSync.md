package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.storage.RepositoryFolder
import com.bontecou.syncmd.storage.RepositoryLocations
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class StorageViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {
    enum class Message { DEFAULT_SAVED, APP_STORAGE_SAVED }
    data class State(val busy: Boolean = false, val current: RepositoryFolder? = null, val chosen: RepositoryFolder? = null, val error: String? = null, val message: Message? = null)
    private val locations = RepositoryLocations(context)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    init { reload() }
    fun reload() = operation { _state.value = _state.value.copy(current = locations.summary()) }
    fun choose(uri: Uri) = operation { _state.value = _state.value.copy(chosen = locations.rememberFolder(uri)) }
    fun useDefault() = operation {
        val chosen = _state.value.chosen ?: error("Choose a folder first")
        locations.setDefault(chosen.uri)
        _state.value = _state.value.copy(current = chosen, message = Message.DEFAULT_SAVED)
    }
    fun useAppStorage() = operation {
        locations.setDefault(RepositoryLocations.APP)
        _state.value = _state.value.copy(current = locations.summary(), message = Message.APP_STORAGE_SAVED)
    }
    fun clearError() { _state.value = _state.value.copy(error = null) }
    fun showError(message: String) { _state.value = _state.value.copy(error = message) }
    private fun operation(action: () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _state.value = _state.value.copy(error = e.message ?: "Storage needs attention")
            } finally { _state.value = _state.value.copy(busy = false) }
        }
    }
}

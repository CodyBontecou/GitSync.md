package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.domain.repository.GitRepository
import com.bontecou.syncmd.services.github.GitHubAuthManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Manages the clone-at-add-time flow.
 *
 * Mirrors the iOS pattern from AppState.clone():
 *   1. Validate token is present.
 *   2. Build the HTTPS clone URL from the repo's full name.
 *   3. Determine the local target path inside the app's private files dir.
 *   4. Delegate to [GitRepository.clone] and expose progress via [CloneState].
 */
@HiltViewModel
class CloneViewModel @Inject constructor(
    private val gitRepository: GitRepository,
    val authManager: GitHubAuthManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE)

    sealed class CloneState {
        /** No clone has been initiated yet. */
        object Idle : CloneState()
        /** Clone is in progress. */
        object Cloning : CloneState()
        /**
         * Clone succeeded.
         * @param localPath The absolute path of the cloned repo on device.
         */
        data class Success(val localPath: String) : CloneState()
        /** Clone failed with a human-readable [message]. */
        data class Error(val message: String) : CloneState()
    }

    private val _cloneState = MutableStateFlow<CloneState>(CloneState.Idle)
    val cloneState: StateFlow<CloneState> = _cloneState.asStateFlow()

    /**
     * Start cloning [repoFullName] (e.g. "owner/repo") to the app's private storage.
     * Safe to call again after an [CloneState.Error] to retry.
     */
    fun startClone(repoFullName: String) {
        val token = authManager.getToken()
        if (token.isNullOrBlank()) {
            _cloneState.value = CloneState.Error("Not authenticated — please sign in first.")
            return
        }

        // Use the user-configured clone base directory, falling back to app-private storage.
        val customDir = sharedPrefs.getString("default_clone_dir", "")?.trim()?.takeIf { it.isNotBlank() }
        val baseDir = customDir ?: "${context.filesDir.absolutePath}/repos"
        val localPath = "$baseDir/$repoFullName"
        val cloneUrl  = "https://github.com/$repoFullName.git"

        viewModelScope.launch(Dispatchers.IO) {
            _cloneState.value = CloneState.Cloning

            val result = gitRepository.clone(
                url   = cloneUrl,
                path  = localPath,
                creds = Credentials.Pat(token),
            )

            _cloneState.value = if (result.isSuccess) {
                CloneState.Success(localPath)
            } else {
                CloneState.Error(
                    result.exceptionOrNull()?.message ?: "Clone failed — please try again."
                )
            }
        }
    }

    fun reset() {
        _cloneState.value = CloneState.Idle
    }
}

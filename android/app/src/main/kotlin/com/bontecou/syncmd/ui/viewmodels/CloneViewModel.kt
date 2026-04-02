package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.domain.repository.GitRepository
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.storage.CloneStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Manages the clone-at-add-time flow.
 *
 * Mirrors the iOS pattern from AppState.clone():
 *   1. Validate token is present.
 *   2. Build the HTTPS clone URL from the repo's full name.
 *   3. Determine the local target path inside app-managed writable storage.
 *   4. Delegate to [GitRepository.clone] and expose progress via [CloneState].
 */
@HiltViewModel
class CloneViewModel @Inject constructor(
    private val gitRepository: GitRepository,
    val authManager: GitHubAuthManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "CloneFlow"
    }

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
     * Start cloning [repoFullName] (e.g. "owner/repo") to app-managed writable storage.
     * Safe to call again after an [CloneState.Error] to retry.
     */
    fun startClone(repoFullName: String) {
        val token = authManager.getToken()
        if (token.isNullOrBlank()) {
            _cloneState.value = CloneState.Error("Not authenticated — please sign in first.")
            return
        }

        val baseDir = CloneStorage.obsidianCompatibleCloneBaseDir(context)
        if (baseDir.isNullOrBlank()) {
            _cloneState.value = CloneState.Error(
                "Obsidian-compatible storage is unavailable.\n\nPlease reconnect storage and retry."
            )
            return
        }

        // Clone location is fixed to Android/media for Obsidian compatibility.
        val localPath = "$baseDir/$repoFullName"
        val cloneUrl = "https://github.com/$repoFullName.git"
        val debugLoggingEnabled = sharedPrefs.getBoolean("show_debug_info", false)

        viewModelScope.launch(Dispatchers.IO) {
            _cloneState.value = CloneState.Cloning

            debugLog(
                enabled = debugLoggingEnabled,
                message = "Starting clone repo=$repoFullName url=$cloneUrl path=$localPath"
            )

            val result = cloneWithRetry(
                cloneUrl = cloneUrl,
                localPath = localPath,
                token = token,
                debugLoggingEnabled = debugLoggingEnabled,
            )

            _cloneState.value = if (result.isSuccess) {
                debugLog(
                    enabled = debugLoggingEnabled,
                    message = "Clone succeeded repo=$repoFullName path=$localPath"
                )
                CloneState.Success(localPath)
            } else {
                val error = result.exceptionOrNull()
                debugLog(
                    enabled = debugLoggingEnabled,
                    message = "Clone failed repo=$repoFullName path=$localPath message=${error?.message}",
                    throwable = error,
                )
                CloneState.Error(friendlyCloneErrorMessage(error))
            }
        }
    }

    fun reset() {
        _cloneState.value = CloneState.Idle
    }

    private suspend fun cloneWithRetry(
        cloneUrl: String,
        localPath: String,
        token: String,
        debugLoggingEnabled: Boolean,
    ): Result<Unit> {
        val targetDir = File(localPath)
        val existedBeforeAttempt = targetDir.exists()
        val maxAttempts = 2
        var lastError: Throwable? = null

        repeat(maxAttempts) { index ->
            val attempt = index + 1
            debugLog(
                enabled = debugLoggingEnabled,
                message = "Clone attempt $attempt/$maxAttempts path=$localPath"
            )

            val result = gitRepository.clone(
                url = cloneUrl,
                path = localPath,
                creds = Credentials.Pat(token),
            )
            if (result.isSuccess) return result

            val error = result.exceptionOrNull()
            lastError = error

            cleanupPartialCloneDirectory(
                targetDir = targetDir,
                existedBeforeAttempt = existedBeforeAttempt,
                debugLoggingEnabled = debugLoggingEnabled,
            )

            val shouldRetry = attempt < maxAttempts && isRetryableCloneFailure(error)
            if (shouldRetry) {
                debugLog(
                    enabled = debugLoggingEnabled,
                    message = "Retrying clone after transient failure: ${error?.message}"
                )
            } else {
                return Result.failure(error ?: Exception("Clone failed — please try again."))
            }
        }

        return Result.failure(lastError ?: Exception("Clone failed — please try again."))
    }

    /**
     * Removes a failed clone directory to prevent stale partial repos from blocking retries.
     *
     * Safety guard:
     *  - If the directory existed before clone began, we only delete when it looks like an
     *    incomplete git repo (contains .git but missing required git metadata files).
     */
    private fun cleanupPartialCloneDirectory(
        targetDir: File,
        existedBeforeAttempt: Boolean,
        debugLoggingEnabled: Boolean,
    ) {
        if (!targetDir.exists()) return

        val gitDir = File(targetDir, ".git")
        val looksLikeHealthyRepo =
            gitDir.isDirectory && File(gitDir, "HEAD").isFile && File(gitDir, "config").isFile

        val shouldDelete = when {
            !existedBeforeAttempt -> true
            gitDir.exists() && !looksLikeHealthyRepo -> true
            else -> false
        }

        if (!shouldDelete) {
            debugLog(
                enabled = debugLoggingEnabled,
                message = "Skipping clone-dir cleanup for existing non-partial path=${targetDir.absolutePath}"
            )
            return
        }

        val deleted = runCatching { targetDir.deleteRecursively() }.getOrDefault(false)
        debugLog(
            enabled = debugLoggingEnabled,
            message = "Cleanup ${if (deleted) "removed" else "failed to remove"} path=${targetDir.absolutePath}"
        )
    }

    private fun isRetryableCloneFailure(error: Throwable?): Boolean {
        if (error == null) return false
        val message = buildMessageChain(error).lowercase()
        return RETRYABLE_FAILURE_HINTS.any { hint -> message.contains(hint) }
    }

    private fun isStorageCompatibilityFailure(error: Throwable?): Boolean {
        if (error == null) return false
        val message = buildMessageChain(error).lowercase()
        return STORAGE_COMPATIBILITY_HINTS.any { hint -> message.contains(hint) }
    }

    private fun friendlyCloneErrorMessage(error: Throwable?): String {
        val raw = error?.message ?: "Clone failed — please try again."
        if (!isStorageCompatibilityFailure(error)) return raw
        return "$raw\n\nClone location is fixed to Android/media app storage for Obsidian compatibility."
    }

    private fun buildMessageChain(error: Throwable): String =
        generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")

    private fun debugLog(
        enabled: Boolean,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (!enabled) return
        if (throwable == null) {
            Log.w(TAG, message)
        } else {
            Log.w(TAG, message, throwable)
        }
    }

    private val RETRYABLE_FAILURE_HINTS = listOf(
        "inflater has been closed",
        "unexpected end",
        "early eof",
        "connection reset",
        "connection timed out",
        "timed out",
        "stream closed",
        "connection closed",
        "packfile",
    )

    private val STORAGE_COMPATIBILITY_HINTS = listOf(
        "operation not permitted",
        "permission denied",
        "read-only file system",
    )
}

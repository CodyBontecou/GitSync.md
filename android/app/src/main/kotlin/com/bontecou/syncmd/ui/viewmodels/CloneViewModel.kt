package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.domain.repository.GitRepository
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.storage.CloneStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import com.bontecou.syncmd.services.repositories.CompletedClone
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
    private val repositoryCredentials: com.bontecou.syncmd.security.RepositoryCredentialStore,
    private val saved: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle(),
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
        data class Success(val localPath: String, val repository: String, val completed: CompletedClone) : CloneState()
        /** Clone failed with a human-readable [message]. */
        data class Error(val message: String) : CloneState()
    }

    private val _cloneState = MutableStateFlow<CloneState>(CloneState.Idle)
    val cloneState: StateFlow<CloneState> = _cloneState.asStateFlow()

    /** GitHub's facts about the repository for the hero ("main · private · 4.0 MB"); null until read. */
    data class RepoInfo(val branch: String?, val isPrivate: Boolean?, val sizeBytes: Long?)
    private val _info = MutableStateFlow<RepoInfo?>(null)
    val info: StateFlow<RepoInfo?> = _info.asStateFlow()
    /** The running clone's JGit progress plus the bytes received so far. */
    private val _progress = MutableStateFlow<com.bontecou.syncmd.services.git.TransferProgressUpdate?>(null)
    val progress: StateFlow<com.bontecou.syncmd.services.git.TransferProgressUpdate?> = _progress.asStateFlow()
    /** The finished working copy ("main · 214 files · 4.0 MB"), counted without `.git`. */
    data class ClonedStats(val branch: String?, val files: Int, val bytes: Long)
    private val _stats = MutableStateFlow<ClonedStats?>(null)
    val stats: StateFlow<ClonedStats?> = _stats.asStateFlow()
    /** Where the clone goes; set when it starts. */
    private val _destination = MutableStateFlow<String?>(null)
    val destination: StateFlow<String?> = _destination.asStateFlow()

    /** Reads branch, visibility and size from GitHub (metadata only). */
    fun loadInfo(repoFullName: String) {
        if (_info.value != null) return
        val parts = repoFullName.split('/')
        if (parts.size != 2) return
        val token = runCatching { authManager.captureActiveAccount() }.getOrNull()?.token ?: return
        viewModelScope.launch {
            runCatching { com.bontecou.syncmd.services.github.GitHubApiService.repoAccess(token, parts[0], parts[1]) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()?.let { _info.value = RepoInfo(it.defaultBranch, it.isPrivate, it.sizeKilobytes?.times(1024)) }
        }
    }

    private fun clonedStats(path: String): ClonedStats {
        val root = File(path)
        var files = 0; var bytes = 0L
        root.walkTopDown().onEnter { it == root || it.name != ".git" && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile }.forEach { files++; bytes += it.length() }
        val head = runCatching { File(root, ".git/HEAD").readText().trim() }.getOrNull()
        return ClonedStats(head?.takeIf { it.startsWith("ref: refs/heads/") }?.removePrefix("ref: refs/heads/"), files, bytes)
    }

    private val publications = com.bontecou.syncmd.storage.clonePublications(context)
    @Volatile private var ownedPublication: String? = null
    private fun claimPublication(id: String): Boolean = try {
        if (ownedPublication != id) { publications.activate(id); ownedPublication = id }
        true
    } catch (_: Exception) {
        _cloneState.value = CloneState.Error(context.getString(R.string.account_clone_in_use)); false
    }
    private fun releasePublication(id: String) {
        publications.deactivate(id); if (ownedPublication == id) ownedPublication = null
    }
    private fun restorePublication(id: String) {
        if (!claimPublication(id)) return
        val epoch = generation.get()
        _cloneState.value = CloneState.Cloning
        cloneJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val selected = requireNotNull(publications.get(id)) { "Cloning was interrupted. Retry or reconnect its folder." }
                val completed = com.bontecou.syncmd.services.git.RepositoryOperationCoordinator.withRepository(selected.completed.path) {
                    val current = requireNotNull(publications.get(id))
                    require(current.alias == saved.get<String>("clone_repository"))
                    publications.publish(current, repositoryCredentials.recordVersion(current.completed.path))
                }
                publish(epoch, CloneState.Success(completed.path, selected.alias, completed))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish(epoch, CloneState.Error(context.getString(R.string.account_clone_interrupted))) }
        }
    }
    override fun onCleared() {
        generation.incrementAndGet()
        // Owner disposal is not an explicit cancellation of durable recovery.
        ownedPublication?.let(::releasePublication)
        super.onCleared()
    }

    /**
     * Start cloning [repoFullName] (e.g. "owner/repo") to app-managed writable storage.
     * Safe to call again after an [CloneState.Error] to retry.
     */
    private var cloneJob: Job? = null
    private val generation = java.util.concurrent.atomic.AtomicLong()
    init { saved.get<String>("clone_publication_id")?.let(::restorePublication) }
    private suspend fun publish(id: Long, state: CloneState) = withContext(Dispatchers.Main.immediate) {
        if (generation.get() == id) _cloneState.value = state
    }
    /** The free vault check's input for a GitHub repository, with the token the clone will use. */
    fun vaultTarget(repoFullName: String): com.bontecou.syncmd.billing.VaultTarget? {
        val parts = repoFullName.split('/')
        if (parts.size != 2 || parts.any { it == "." || it == ".." || !it.matches(Regex("[A-Za-z0-9_.-]+")) }) {
            _cloneState.value = CloneState.Error(context.getString(R.string.account_invalid_repository_name)); return null
        }
        val token = runCatching { authManager.captureActiveAccount() }.getOrNull()?.token
        if (token.isNullOrBlank()) { _cloneState.value = CloneState.Error(context.getString(R.string.account_not_authenticated)); return null }
        return com.bontecou.syncmd.billing.VaultTarget(parts[1], "https://github.com/$repoFullName.git", null,
            com.bontecou.syncmd.data.models.Credentials.Pat(token), token)
    }

    fun startClone(repoFullName: String, location: String? = null) {
        if (_cloneState.value == CloneState.Cloning || _cloneState.value is CloneState.Success) return
        val prior = saved.get<String>("clone_publication_id")
        if (prior != null && runCatching { publications.hasReceipt(prior) }.getOrDefault(true)) { restorePublication(prior); return }
        val parts = repoFullName.split('/')
        if (parts.size != 2 || parts.any { it == "." || it == ".." || !it.matches(Regex("[A-Za-z0-9_.-]+")) }) {
            _cloneState.value = CloneState.Error(context.getString(R.string.account_invalid_repository_name))
            return
        }
        val account = runCatching { authManager.captureActiveAccount() }.getOrNull()
        val token = account?.token
        if (token.isNullOrBlank()) {
            _cloneState.value = CloneState.Error(context.getString(R.string.account_not_authenticated))
            return
        }

        val publicationId = java.util.UUID.randomUUID().toString()
        saved["clone_publication_id"] = publicationId; saved["clone_repository"] = repoFullName
        val id = generation.incrementAndGet()
        _cloneState.value = CloneState.Cloning
        if (!claimPublication(publicationId)) return
        cloneJob = viewModelScope.launch(Dispatchers.IO) {
            try {
            val baseDir = try { com.bontecou.syncmd.storage.RepositoryLocations(context).resolve(location).path }
                catch (e: Exception) { publish(id, CloneState.Error(e.message ?: context.getString(R.string.account_choose_location_again))); return@launch }
            val localPath = "$baseDir/$repoFullName"
            val cloneUrl = "https://github.com/$repoFullName.git"
            val debugLoggingEnabled = sharedPrefs.getBoolean("show_debug_info", false)

            debugLog(
                enabled = debugLoggingEnabled,
                message = "Starting clone repo=$repoFullName url=$cloneUrl path=$localPath"
            )

            // A retained or imported working copy is user data, never a stale clone.
            // Reconnection is offered by local repository discovery instead.
            val existingDir = File(localPath)
            if (existingDir.exists()) {
                publish(id, CloneState.Error(context.getString(R.string.account_destination_exists)))
                return@launch
            }

            _destination.value = localPath; _progress.value = null
            val result = withContext(com.bontecou.syncmd.services.git.TransferProgress { _progress.value = it }) { cloneWithRetry(
                cloneUrl = cloneUrl,
                localPath = localPath,
                token = token,
                accountId = account!!.id,
                debugLoggingEnabled = debugLoggingEnabled,
                requireCurrent = { check(generation.get() == id) { "Clone request changed" } },
                publicationId = publicationId, repository = repoFullName,
            ) }
            if (result.isSuccess) runCatching { _stats.value = clonedStats(result.getOrThrow().path) }

            publish(id, if (result.isSuccess) {
                debugLog(
                    enabled = debugLoggingEnabled,
                    message = "Clone succeeded repo=$repoFullName path=$localPath"
                )
                val completed = result.getOrThrow()
                CloneState.Success(completed.path, repoFullName, completed)
            } else {
                val error = result.exceptionOrNull()
                debugLog(
                    enabled = debugLoggingEnabled,
                    message = "Clone failed repo=$repoFullName path=$localPath message=${error?.message}",
                    throwable = error,
                )
                CloneState.Error(friendlyCloneErrorMessage(error))
            })
            } finally { if (runCatching { !publications.hasReceipt(publicationId) }.getOrDefault(false)) releasePublication(publicationId) }
        }
    }

    fun reset(): Boolean {
        generation.incrementAndGet(); cloneJob?.cancel(); cloneJob = null
        try {
            ownedPublication?.let { publications.forget(it); releasePublication(it) }
        } catch (_: Exception) {
            _cloneState.value = CloneState.Error(context.getString(com.bontecou.syncmd.R.string.clone_recovery_cancel_failed)); return false
        }
        saved.remove<String>("clone_publication_id"); saved.remove<String>("clone_repository")
        _cloneState.value = CloneState.Idle
        return true
    }

    private suspend fun cloneWithRetry(
        cloneUrl: String,
        localPath: String,
        token: String,
        accountId: String,
        debugLoggingEnabled: Boolean,
        requireCurrent: () -> Unit,
        publicationId: String, repository: String,
    ): Result<CompletedClone> {
        val maxAttempts = 2
        var lastError: Throwable? = null
        val credentialRevision = repositoryCredentials.recordVersion(localPath)

        repeat(maxAttempts) { index ->
            val attempt = index + 1
            debugLog(
                enabled = debugLoggingEnabled,
                message = "Clone attempt $attempt/$maxAttempts path=$localPath"
            )

            var completed: CompletedClone? = null
            val result = try {
                com.bontecou.syncmd.services.repositories.SafeCloneService(gitRepository).clone(
                    url = cloneUrl,
                    destination = localPath,
                    credentials = Credentials.Pat(token),
                    beforePublication = { staging ->
                        requireCurrent()
                        publications.reserve(publicationId, staging, localPath, repositoryCredentials.recordVersion(localPath), repository.substringAfterLast('/'), repository)
                    },
                    retainStagingOnFailure = { publications.hasReceipt(publicationId) },
                    withPublication = { move ->
                        requireCurrent()
                        check(repositoryCredentials.recordVersion(localPath) == credentialRevision) { context.getString(R.string.account_clone_credentials_changed) }
                        repositoryCredentials.updateCloneConnection(localPath, cloneUrl,
                            credentialRevision, accountId, publications, publicationId) {
                            requireCurrent(); move()
                            completed = CompletedClone.capture(localPath, repositoryCredentials.recordVersion(localPath))
                        }
                    },
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Result.failure(t)
            }
            if (result.isSuccess) return Result.success(requireNotNull(completed))

            val error = result.exceptionOrNull()
            lastError = error

            val shouldRetry = attempt < maxAttempts &&
                runCatching { !publications.hasReceipt(publicationId) }.getOrDefault(false) && isRetryableCloneFailure(error)
            if (shouldRetry) {
                debugLog(
                    enabled = debugLoggingEnabled,
                    message = "Retrying clone after transient failure: ${error?.message}"
                )
            } else {
                return Result.failure(error ?: Exception(context.getString(R.string.account_clone_failed_retry)))
            }
        }

        return Result.failure(lastError ?: Exception(context.getString(R.string.account_clone_failed_retry)))
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
        val raw = error?.message ?: context.getString(R.string.account_clone_failed_retry)
        if (!isStorageCompatibilityFailure(error)) return raw
        return context.getString(R.string.account_clone_storage_note, raw)
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
        "cannot delete file",
        "checkout conflict",
    )

    private val STORAGE_COMPATIBILITY_HINTS = listOf(
        "operation not permitted",
        "permission denied",
        "read-only file system",
    )
}

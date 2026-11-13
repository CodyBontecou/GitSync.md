package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.domain.repository.GitRepository
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.git.GitRemote
import com.bontecou.syncmd.services.git.SshKeys
import com.bontecou.syncmd.services.repositories.SafeCloneService
import com.bontecou.syncmd.storage.CloneStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class RemoteCloneViewModel @Inject constructor(private val git: GitRepository,
    private val credentials: RepositoryCredentialStore, @ApplicationContext private val context: Context,
    private val github: com.bontecou.syncmd.services.github.GitHubAuthManager,
    private val savedState: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle(),
    private val deviceKeys: com.bontecou.syncmd.security.DeviceSshKeyStore = com.bontecou.syncmd.security.DeviceSshKeyStore(context)) : ViewModel() {
    /** "This phone's key" (board 14): generated once, kept Keystore-encrypted; null until [ensureDeviceKey] finishes. */
    private val _deviceKey = MutableStateFlow<com.bontecou.syncmd.services.git.GeneratedSshKey?>(null)
    val deviceKey = _deviceKey.asStateFlow()
    private val _deviceKeyFailed = MutableStateFlow(false)
    val deviceKeyFailed = _deviceKeyFailed.asStateFlow()
    fun ensureDeviceKey() {
        if (_deviceKey.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val comment = (github.accountSummary(github.activeAccountId() ?: "")?.login?.lowercase()?.let { "$it@" + deviceKeys.defaultComment().substringAfter('@') })
                    ?: deviceKeys.defaultComment()
                _deviceKey.value = deviceKeys.getOrCreate(comment); _deviceKeyFailed.value = false
            } catch (e: CancellationException) { throw e } catch (_: Exception) { _deviceKeyFailed.value = true }
        }
    }
    /** The running clone's transfer progress (bytes received into the staging folder). */
    private val _progress = MutableStateFlow<com.bontecou.syncmd.services.git.TransferProgressUpdate?>(null)
    val progress = _progress.asStateFlow()

    /** Why "Test & clone" stopped before cloning; null while testing or once it passed. */
    sealed interface TestResult {
        data object Passed : TestResult
        /** The host key needs review; the clone asks for it. */
        data object HostKeyReview : TestResult
        data class Failed(val message: com.bontecou.syncmd.ui.util.UiText) : TestResult
    }
    private val _testing = MutableStateFlow(false)
    val testing = _testing.asStateFlow()

    /** `git ls-remote` with the credentials the clone will use; nothing is written. */
    suspend fun test(target: com.bontecou.syncmd.billing.VaultTarget): TestResult {
        _testing.value = true
        try {
            val reach = kotlinx.coroutines.withContext(Dispatchers.IO) { com.bontecou.syncmd.services.git.RemoteRefs.list(target.url, target.credentials) }
            return when (reach) {
                is com.bontecou.syncmd.services.git.RemoteReach.Reachable -> {
                    val branch = target.branch
                    if (branch != null && reach.branches.isNotEmpty() && branch !in reach.branches)
                        TestResult.Failed(com.bontecou.syncmd.ui.util.UiText.res(R.string.add_test_failed_branch, branch)) else TestResult.Passed
                }
                com.bontecou.syncmd.services.git.RemoteReach.HostKeyUnconfirmed -> TestResult.HostKeyReview
                is com.bontecou.syncmd.services.git.RemoteReach.Unreachable -> {
                    val text = generateSequence(reach.cause) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
                    TestResult.Failed(com.bontecou.syncmd.ui.util.UiText.res(when {
                        "not authorized" in text || "auth" in text || "401" in text || "403" in text || "publickey" in text || "ssh connection failed" in text -> R.string.add_test_failed_auth
                        "not found" in text || "404" in text || "does not appear to be a git repository" in text -> R.string.add_test_failed_missing
                        else -> R.string.add_test_failed_network
                    }))
                }
            }
        } finally { _testing.value = false }
    }
    data class State(val busy: Boolean = false, val error: String? = null, val path: String? = null, val name: String = "", val completed: com.bontecou.syncmd.services.repositories.CompletedClone? = null)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    val accounts = github.accounts
    enum class HistoryAuth { PUBLIC, SAVED, GITHUB, REVIEW }
    data class HistoryDefaults(val source: String, val name: String, val url: String, val branch: String,
        val auth: HistoryAuth, val accountId: String?, val credentialVersion: String?)
    data class HistoryState(val busy: Boolean = false, val defaults: HistoryDefaults? = null, val error: String? = null, val notice: String? = null)
    private val _history = MutableStateFlow(HistoryState())
    val history = _history.asStateFlow()
    private val publications = com.bontecou.syncmd.storage.clonePublications(context)
    private var selectedHistory: RepositoryHistoryEntry? = null
    @Volatile private var ownedPublication: String? = null
    private fun claimPublication(id: String): Boolean = try {
        if (ownedPublication != id) { publications.activate(id); ownedPublication = id }
        true
    } catch (_: Exception) {
        _state.value = State(error = context.getString(R.string.account_clone_in_use)); false
    }
    private fun releasePublication(id: String) {
        publications.deactivate(id); if (ownedPublication == id) ownedPublication = null
    }
    init {
        savedState.get<String>("clone_publication_id")?.let { id ->
            if (!claimPublication(id)) return@let
            _state.value = State(busy = true)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val record = requireNotNull(publications.get(id))
                    val completed = com.bontecou.syncmd.services.git.RepositoryOperationCoordinator.withRepository(record.completed.path) {
                        val current = requireNotNull(publications.get(id))
                        publications.publish(current, credentials.recordVersion(current.completed.path))
                    }
                    _state.value = State(path = completed.path, name = record.name, completed = completed)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { _state.value = State(error = context.getString(R.string.account_clone_interrupted)) }
            }
        }
    }
    fun cancelRecovery(): Boolean {
        try { ownedPublication?.let { publications.forget(it); releasePublication(it) } }
        catch (_: Exception) { _state.value = _state.value.copy(error = context.getString(com.bontecou.syncmd.R.string.clone_recovery_cancel_failed)); return false }
        savedState.remove<String>("clone_publication_id")
        return true
    }
    override fun onCleared() {
        // Keep durable metadata when navigation/lifecycle releases this screen owner.
        ownedPublication?.let(::releasePublication)
        super.onCleared()
    }
    fun loadHistory(entry: RepositoryHistoryEntry) {
        if (_history.value.busy || _history.value.defaults != null || _state.value.busy) return
        selectedHistory = entry
        _history.value = HistoryState(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val repo = entry.repository
                val summary = credentials.summary(repo.path)
                val inferred = repo.remoteUrl == null && summary == null
                val address = repo.remoteUrl ?: summary?.url ?: repo.alias.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) }
                    ?.let { "https://github.com/$it.git" } ?: error("No saved remote address")
                val url = GitRemote.parse(address).url
                // A changed endpoint must never inherit a formerly saved secret/account.
                if (summary != null && GitRemote.parse(summary.url).url != url) error("Saved connection changed")
                val account = if (summary?.isPublicAccess == true) null else if (summary?.usesGithubAccount == true) summary.accountId ?: github.defaultAccountId()
                    else if ((summary == null || summary.usesLegacySelection) && GitRemote.parse(url).let { it.isHttps && it.host == "github.com" && it.port == 443 }) github.defaultAccountId() else null
                val auth = when {
                    inferred -> HistoryAuth.REVIEW
                    summary?.isPublicAccess == true -> HistoryAuth.PUBLIC
                    summary?.usesGithubAccount == true || account != null -> HistoryAuth.GITHUB
                    summary?.hasSecret == true -> HistoryAuth.SAVED
                    summary != null -> HistoryAuth.PUBLIC
                    else -> HistoryAuth.REVIEW
                }
                _history.value = HistoryState(defaults = HistoryDefaults(repo.path, repo.name, url, repo.branch.orEmpty(), auth,
                    account?.let { github.accountSummary(it)?.id ?: it }, credentials.recordVersion(repo.path)),
                    notice = if (inferred) context.getString(R.string.account_previous_address_missing) else null)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _history.value = HistoryState(error = context.getString(R.string.account_previous_connection_unavailable)) }
        }
    }
    fun activeAccountId() = github.activeAccountId()
    /** The form's typed secrets: kept across configuration changes, never in saved state. */
    val secretDrafts = com.bontecou.syncmd.ui.util.SecretDrafts()

    /** A validated clone request; the vault check and the clone both start from it. */
    private class Request(val remote: GitRemote, val saved: HistoryDefaults?, val account: com.bontecou.syncmd.security.GitHubAccountSnapshot?)

    /** Validates the form exactly as the clone will, reporting the first problem in [state]. */
    private fun validate(url: String, name: String, branch: String, authenticated: Boolean, username: String, secret: String, accountId: String?,
        useSavedHistoryConnection: Boolean, ssh: Credentials.Ssh?): Request? {
        val remote = try { GitRemote.parseInput(url) } catch (e: Exception) { _state.value = State(error = e.message ?: context.getString(R.string.account_invalid_repository_url)); return null }
        if (remote.usesNoNetworkCredentials && (authenticated || accountId != null || ssh != null || useSavedHistoryConnection)) {
            _state.value = State(error = context.getString(if (remote.isLocalFile) com.bontecou.syncmd.R.string.file_clone_guidance else com.bontecou.syncmd.R.string.git_native_public_only)); return null
        }
        val saved = if (useSavedHistoryConnection) _history.value.defaults else null
        if (useSavedHistoryConnection && (authenticated || accountId != null || ssh != null || saved == null || saved.auth != HistoryAuth.SAVED || saved.url != remote.url ||
                saved.credentialVersion != credentials.recordVersion(saved.source))) {
            _state.value = State(error = context.getString(R.string.account_saved_connection_changed)); return null
        }
        com.bontecou.syncmd.ui.screens.remoteCloneNameIssue(name)?.let { _state.value = State(error = context.getString(it)); return null }
        if (branch.isNotBlank() && !com.bontecou.syncmd.services.git.RemoteAuthentication.isValidBranch(branch.trim())) {
            _state.value = State(error = context.getString(R.string.account_valid_branch)); return null
        }
        if (authenticated && (username.isBlank() || secret.isEmpty())) { _state.value = State(error = context.getString(R.string.account_enter_username_secret)); return null }
        if (remote.isSsh && (authenticated || accountId != null || ssh == null && saved == null) || !remote.isSsh && ssh != null) {
            _state.value = State(error = context.getString(R.string.account_choose_ssh_or_https)); return null
        }
        val account = if (accountId != null) try {
            require(!authenticated && remote.isHttps && remote.host == "github.com" && remote.port == 443)
            com.bontecou.syncmd.security.GitHubAccountSnapshot(accountId, github.tokenForAccount(accountId))
        } catch (e: Exception) { _state.value = State(error = context.getString(R.string.account_select_connected_account)); return null } else null
        return Request(remote, saved, account)
    }

    /** The credentials the clone will present; reads the saved connection, so call off the main thread. */
    private fun credentialsFor(request: Request, authenticated: Boolean, username: String, secret: String, ssh: Credentials.Ssh?): Credentials =
        request.saved?.let {
            check(credentials.recordVersion(it.source) == it.credentialVersion)
            val connection = credentials.resolve(it.source, request.remote.url) ?: error("Saved connection is unavailable")
            check(credentials.recordVersion(it.source) == it.credentialVersion)
            connection
        } ?: ssh?.also { SshKeys.validate(it) } ?: request.account?.let { Credentials.Pat(it.token) }
            ?: if (authenticated) Credentials.Basic(username.trim(), secret) else Credentials.None

    /**
     * The free vault check's input for this form, or null (with [state] explaining why).
     * A GitHub token is passed only for a github.com account, as on iOS.
     */
    suspend fun vaultTarget(url: String, name: String, branch: String, authenticated: Boolean, username: String, secret: String, accountId: String? = null,
        useSavedHistoryConnection: Boolean = false, ssh: Credentials.Ssh? = null): com.bontecou.syncmd.billing.VaultTarget? {
        if (_state.value.busy || _state.value.path != null) return null
        val request = validate(url, name, branch, authenticated, username, secret, accountId, useSavedHistoryConnection, ssh) ?: return null
        return try {
            val auth = kotlinx.coroutines.withContext(Dispatchers.IO) { credentialsFor(request, authenticated, username, secret, ssh) }
            com.bontecou.syncmd.billing.VaultTarget(name.trim(), request.remote.url, branch.trim().ifEmpty { null }, auth, request.account?.token)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { _state.value = State(error = context.getString(R.string.account_saved_connection_changed)); null }
    }

    fun clone(url: String, name: String, branch: String, authenticated: Boolean, username: String, secret: String, accountId: String? = null, location: String? = null,
        useSavedHistoryConnection: Boolean = false, ssh: Credentials.Ssh? = null) {
        if (_state.value.busy || _state.value.path != null) return
        if (savedState.get<String>("clone_publication_id")?.let { runCatching { publications.hasReceipt(it) }.getOrDefault(true) } == true) {
            _state.value = State(error = context.getString(R.string.account_review_interrupted_clone)); return
        }
        val request = validate(url, name, branch, authenticated, username, secret, accountId, useSavedHistoryConnection, ssh) ?: return
        val remote = request.remote; val saved = request.saved; val account = request.account
        val publicationId = UUID.randomUUID().toString()
        savedState["clone_publication_id"] = publicationId
        _state.value = State(busy = true)
        if (!claimPublication(publicationId)) return
        viewModelScope.launch(Dispatchers.IO) {
            var target: File? = null
            var ownedCredentialVersion: String? = null
            try {
                if (remote.isLocalFile) com.bontecou.syncmd.services.git.RepositoryOperationCoordinator.requireAccess(com.bontecou.syncmd.services.git.FileRemote.parse(remote.url).path)
                val base = com.bontecou.syncmd.storage.RepositoryLocations(context).resolve(location).path
                // Display names never form a path. Every attempt owns a fresh destination.
                target = File(base, "custom/${UUID.randomUUID()}")
                val auth = credentialsFor(request, authenticated, username, secret, ssh)
                if (account == null) ownedCredentialVersion = credentials.saveWithVersion(target.path, remote.url, auth)
                val targetCredentialRevision = ownedCredentialVersion ?: credentials.recordVersion(target.path)
                var completed: com.bontecou.syncmd.services.repositories.CompletedClone? = null
                _progress.value = null
                kotlinx.coroutines.withContext(com.bontecou.syncmd.services.git.TransferProgress { _progress.value = it }) {
                SafeCloneService(git).clone(remote.url, target.path, auth, branch.trim().ifEmpty { null }, beforePublication = { staging ->
                    publications.reserve(publicationId, staging, target.path, credentials.recordVersion(target.path), name.trim(), selectedHistory?.repository?.alias.orEmpty(),
                        selectedHistory?.let { com.bontecou.syncmd.storage.cloneHistoryReview(it) })
                }, retainStagingOnFailure = { publications.hasReceipt(publicationId) }, withPublication = { move ->
                    check(credentials.recordVersion(target.path) == targetCredentialRevision) { "Saved credentials changed during cloning" }
                    check(saved == null || credentials.recordVersion(saved.source) == saved.credentialVersion) { "Saved connection changed during cloning" }
                    if (account != null) credentials.updateCloneConnection(target.path, remote.url,
                        targetCredentialRevision, account.id, publications, publicationId) {
                        move(); completed = com.bontecou.syncmd.services.repositories.CompletedClone.capture(target.path, credentials.recordVersion(target.path))
                    } else {
                        move(); completed = com.bontecou.syncmd.services.repositories.CompletedClone.capture(target.path, credentials.recordVersion(target.path))
                    }
                }).getOrThrow()
                }
                _state.value = State(path = requireNotNull(completed).path, name = name.trim(), completed = completed)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                // Do not echo a server response, which may contain a credential or URL userinfo.
                _state.value = State(error = if (remote.isLocalFile) context.getString(com.bontecou.syncmd.R.string.file_clone_failure) else context.getString(R.string.account_clone_failed_network))
            } finally {
                if (runCatching { !publications.hasReceipt(publicationId) }.getOrDefault(false)) releasePublication(publicationId)
                target?.takeIf { !it.exists() && runCatching { !publications.hasReceipt(publicationId) }.getOrDefault(false) }?.let { abandoned -> ownedCredentialVersion?.let { version ->
                    runCatching { credentials.removeIfCurrent(abandoned.path, version) }
                } }
            }
        }
    }
}

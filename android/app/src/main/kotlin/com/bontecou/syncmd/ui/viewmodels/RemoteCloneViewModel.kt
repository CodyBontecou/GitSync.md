package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val savedState: androidx.lifecycle.SavedStateHandle = androidx.lifecycle.SavedStateHandle()) : ViewModel() {
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
        _state.value = State(error = "This clone is already open or needs review in Local repositories."); false
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
                catch (_: Exception) { _state.value = State(error = "The interrupted clone needs review. Its files were kept; reconnect it from Local repositories.") }
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
                    notice = if (inferred) "The previous remote address was not recorded. Verify this suggested GitHub URL and choose authentication before cloning." else null)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _history.value = HistoryState(error = "The previous connection cannot be restored. Review the URL and choose authentication explicitly; existing files and saved credentials were kept.") }
        }
    }
    fun activeAccountId() = github.activeAccountId()
    fun clone(url: String, name: String, branch: String, authenticated: Boolean, username: String, secret: String, accountId: String? = null, location: String? = null,
        useSavedHistoryConnection: Boolean = false, ssh: Credentials.Ssh? = null) {
        if (_state.value.busy || _state.value.path != null) return
        if (savedState.get<String>("clone_publication_id")?.let { runCatching { publications.hasReceipt(it) }.getOrDefault(true) } == true) {
            _state.value = State(error = "Review the interrupted clone before starting another one. Its files were kept."); return
        }
        val remote = try { GitRemote.parseInput(url) } catch (e: Exception) { _state.value = State(error = e.message ?: "Invalid repository URL"); return }
        if (remote.usesNoNetworkCredentials && (authenticated || accountId != null || ssh != null || useSavedHistoryConnection)) {
            _state.value = State(error = context.getString(if (remote.isLocalFile) com.bontecou.syncmd.R.string.file_clone_guidance else com.bontecou.syncmd.R.string.git_native_public_only)); return
        }
        val saved = if (useSavedHistoryConnection) _history.value.defaults else null
        if (useSavedHistoryConnection && (authenticated || accountId != null || ssh != null || saved == null || saved.auth != HistoryAuth.SAVED || saved.url != remote.url ||
                saved.credentialVersion != credentials.recordVersion(saved.source))) {
            _state.value = State(error = "The saved connection changed. Review authentication before cloning again."); return
        }
        if (name.isBlank() || name.length > 120) { _state.value = State(error = "Enter a repository name of 1–120 characters"); return }
        if (branch.isNotBlank() && !com.bontecou.syncmd.services.git.RemoteAuthentication.isValidBranch(branch.trim())) {
            _state.value = State(error = "Enter a valid branch name"); return
        }
        if (authenticated && (username.isBlank() || secret.isEmpty())) { _state.value = State(error = "Enter a username and token or password"); return }
        if (remote.isSsh && (authenticated || accountId != null || ssh == null && saved == null) || !remote.isSsh && ssh != null) {
            _state.value = State(error = "Choose an SSH private key for an SSH address, or HTTPS authentication for an HTTPS address."); return
        }
        val account = if (accountId != null) try {
            require(!authenticated && remote.isHttps && remote.host == "github.com" && remote.port == 443)
            com.bontecou.syncmd.security.GitHubAccountSnapshot(accountId, github.tokenForAccount(accountId))
        } catch (e: Exception) { _state.value = State(error = "Select a connected GitHub account and a github.com HTTPS address"); return } else null
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
                val auth = saved?.let {
                    check(credentials.recordVersion(it.source) == it.credentialVersion)
                    val connection = credentials.resolve(it.source, remote.url) ?: error("Saved connection is unavailable")
                    check(credentials.recordVersion(it.source) == it.credentialVersion)
                    connection
                }
                    ?: ssh?.also { SshKeys.validate(it) } ?: account?.let { Credentials.Pat(it.token) } ?: if (authenticated) Credentials.Basic(username.trim(), secret) else Credentials.None
                if (account == null) ownedCredentialVersion = credentials.saveWithVersion(target.path, remote.url, auth)
                val targetCredentialRevision = ownedCredentialVersion ?: credentials.recordVersion(target.path)
                var completed: com.bontecou.syncmd.services.repositories.CompletedClone? = null
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
                _state.value = State(path = requireNotNull(completed).path, name = name.trim(), completed = completed)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                // Do not echo a server response, which may contain a credential or URL userinfo.
                _state.value = State(error = if (remote.isLocalFile) context.getString(com.bontecou.syncmd.R.string.file_clone_failure) else "Could not clone the repository. Check its URL, access credentials, branch and network connection. Existing folders were kept.")
            } finally {
                if (runCatching { !publications.hasReceipt(publicationId) }.getOrDefault(false)) releasePublication(publicationId)
                target?.takeIf { !it.exists() && runCatching { !publications.hasReceipt(publicationId) }.getOrDefault(false) }?.let { abandoned -> ownedCredentialVersion?.let { version ->
                    runCatching { credentials.removeIfCurrent(abandoned.path, version) }
                } }
            }
        }
    }
}

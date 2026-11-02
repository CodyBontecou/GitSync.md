package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.git.GitRemote
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.repositories.RepositoryConnection
import com.bontecou.syncmd.services.repositories.RepositoryConnectionPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(private val credentials: RepositoryCredentialStore,
    private val auth: GitHubAuthManager) : ViewModel() {
    data class State(val busy: Boolean = true, val plan: RepositoryConnectionPlan? = null,
        val summary: RepositoryCredentialStore.Summary? = null, val warning: String? = null,
        val error: String? = null, val credentialVersion: String? = null, val saved: Boolean = false, val githubAvailable: Boolean = false,
        val migration: com.bontecou.syncmd.services.repositories.RemoteCredentialMigrationPlan? = null,
        val savedEndpoints: List<RepositoryCredentialStore.Summary> = emptyList(),
        // Equality-based UI collectors must distinguish otherwise-identical reloads.
        val requestId: Long = 0)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    val accounts = auth.accounts
    fun activeAccountId() = auth.activeAccountId()
    fun canonicalAccountId(id: String?) = id?.let { auth.accountSummary(it)?.id ?: it }
    fun defaultAccountId() = auth.defaultAccountId()
    private val connection = RepositoryConnection()
    private val migration = com.bontecou.syncmd.services.repositories.RemoteCredentialMigration()
    @Volatile private var generation = 0L
    private var requestedPath: String? = null
    private var completingSave: State? = null
    private var deliveredSave: State? = null
    fun load(path: String) {
        requestedPath = path
        val request = ++generation
        _state.value = State(requestId = request)
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    RepositoryOperationCoordinator.withRepository(path) {
                        val plan = connection.inspect(path)
                        val summary = runCatching { credentials.summary(plan.path) }
                        State(busy = false, plan = plan, summary = summary.getOrNull(), credentialVersion = credentials.recordVersion(plan.path),
                            warning = if (summary.isFailure) "Saved credentials cannot be opened. Choose public access or enter new credentials." else null,
                            githubAvailable = auth.isLoggedIn, migration = migration.inspect(plan.path),
                            savedEndpoints = runCatching { credentials.summaries(plan.path) }.getOrDefault(emptyList()), requestId = request)
                    }
                }
                if (generation == request) _state.value = loaded
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (generation == request) _state.value = State(busy = false, error = e.message ?: "Could not open the connection", requestId = request) }
        }
    }
    fun save(review: State, url: String, choice: RepositoryCredentialStore.Choice, username: String, secret: String, accountId: String? = auth.activeAccountId(), ssh: Credentials.Ssh? = null) {
        if (_state.value !== review) return
        val current = review
        val plan = current.plan ?: return
        if (current.busy || current.saved) return
        val endpoint = try { GitRemote.parseInput(url) } catch (e: Exception) {
            _state.value = current.copy(error = "Enter a valid repository URL, with credentials in the separate fields."); return
        }
        if (endpoint.usesNoNetworkCredentials && choice !in listOf(RepositoryCredentialStore.Choice.PUBLIC, RepositoryCredentialStore.Choice.PUBLIC_KEEP)) {
            _state.value = current.copy(error = "Choose access without network credentials for this address"); return
        }
        if (endpoint.isSsh && choice !in listOf(RepositoryCredentialStore.Choice.KEEP, RepositoryCredentialStore.Choice.PUBLIC_KEEP, RepositoryCredentialStore.Choice.SSH) ||
            !endpoint.isSsh && choice == RepositoryCredentialStore.Choice.SSH) {
            _state.value = current.copy(error = "Choose an SSH private key for an SSH address, or HTTPS authentication for an HTTPS address."); return
        }
        if (choice == RepositoryCredentialStore.Choice.GITHUB && (accountId == null || auth.accountSummary(accountId)?.connected != true)) {
            _state.value = current.copy(error = "Sign in to GitHub before selecting account access"); return
        }
        if (choice == RepositoryCredentialStore.Choice.REPLACE && (username.isBlank() || secret.isEmpty())) {
            _state.value = current.copy(error = "Enter a username and token or password"); return
        }
        perform(current) {
            connection.save(plan, endpoint.url) { commit ->
                credentials.updateConnection(plan.path, endpoint.url, choice,
                    if (choice == RepositoryCredentialStore.Choice.REPLACE) Credentials.Basic(username.trim(), secret) else if (choice == RepositoryCredentialStore.Choice.SSH) ssh else null, current.credentialVersion, accountId, commit)
            }
        }
    }
    fun migrateUrlCredentials(current: State) {
        if (_state.value !== current) return
        val review = current.migration ?: return
        if (current.busy || current.saved || !review.canMigrate) return
        perform(current) {
            migration.migrate(review) { bindings, urls, publish ->
                credentials.migrateEmbedded(review.path, review.primaryUrl, bindings, urls, current.credentialVersion, publish)
            }
        }
    }
    /** Forgetting a token does not rewrite fetch/push URLs or re-enable implicit account access. */
    fun forgetCredentials(review: State) {
        if (_state.value !== review) return
        val current = review
        val plan = current.plan ?: return
        if (current.busy || current.saved) return
        perform(current) {
            RepositoryOperationCoordinator.withRepository(plan.path) {
                val fresh = connection.inspect(plan.path)
                check(fresh.revision == plan.revision && fresh.metadataIdentity == plan.metadataIdentity) { "Repository configuration changed. Reload before removing credentials." }
                credentials.updateConnection(plan.path, plan.fetchUrl ?: error("No remote is configured"), RepositoryCredentialStore.Choice.PUBLIC, null, current.credentialVersion) {}
            }
        }
    }
    /** Called by the resumed screen, so leaving it cancels refresh and delivery together. */
    suspend fun finishSaved(review: State, path: String, refreshMetadata: suspend (String) -> Boolean, onReady: () -> Unit) {
        if (_state.value !== review || requestedPath != path || !review.saved || review.busy ||
            completingSave === review || deliveredSave === review) return
        val repositoryPath = review.plan?.path ?: return
        completingSave = review
        try {
            if (!refreshMetadata(repositoryPath)) return
            // Persistence may finish after cancellation, a reload, or another repository selection.
            currentCoroutineContext().ensureActive()
            if (_state.value !== review || requestedPath != path || deliveredSave === review) return
            deliveredSave = review
            onReady()
        } finally {
            if (completingSave === review) completingSave = null
        }
    }

    private fun perform(current: State, action: suspend () -> Unit) {
        if (_state.value !== current || current.busy || current.saved) return
        val request = generation
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RepositoryOperationCoordinator.withRepository(requireNotNull(current.plan).path) {
                        if (generation == request && _state.value.plan?.path == current.plan.path) action()
                    }
                }
                if (request == generation) _state.value = current.copy(busy = false, saved = true)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (request == generation) _state.value = current.copy(busy = false, error = e.message ?: "Could not save the connection")
            }
        }
    }
}

package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.git.GitRemote
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.repositories.RepositoryConnection
import com.bontecou.syncmd.services.repositories.RepositoryConnectionPlan
import com.bontecou.syncmd.ui.util.UiText
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
        val summary: RepositoryCredentialStore.Summary? = null, val warning: UiText? = null,
        val error: UiText? = null, val credentialVersion: String? = null, val saved: Boolean = false, val githubAvailable: Boolean = false,
        val migration: com.bontecou.syncmd.services.repositories.RemoteCredentialMigrationPlan? = null,
        val savedEndpoints: List<RepositoryCredentialStore.Summary> = emptyList(),
        // Equality-based UI collectors must distinguish otherwise-identical reloads.
        val requestId: Long = 0)
    /** A refusal raised by this model; its copy resolves in the screen's language. */
    private class Refusal(val text: Int) : IllegalStateException()
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
                            warning = if (summary.isFailure) UiText.res(R.string.account_saved_credentials_unreadable) else null,
                            githubAvailable = auth.isLoggedIn, migration = migration.inspect(plan.path),
                            savedEndpoints = runCatching { credentials.summaries(plan.path) }.getOrDefault(emptyList()), requestId = request)
                    }
                }
                if (generation == request) _state.value = loaded
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (generation == request) _state.value = State(busy = false, error = UiText.of(e, R.string.account_open_connection_failed), requestId = request) }
        }
    }
    fun save(review: State, url: String, choice: RepositoryCredentialStore.Choice, username: String, secret: String, accountId: String? = auth.activeAccountId(), ssh: Credentials.Ssh? = null) {
        if (_state.value !== review) return
        val current = review
        val plan = current.plan ?: return
        if (current.busy || current.saved) return
        val endpoint = try { GitRemote.parseInput(url) } catch (e: Exception) {
            _state.value = current.copy(error = UiText.res(R.string.account_enter_valid_url)); return
        }
        if (endpoint.usesNoNetworkCredentials && choice !in listOf(RepositoryCredentialStore.Choice.PUBLIC, RepositoryCredentialStore.Choice.PUBLIC_KEEP)) {
            _state.value = current.copy(error = UiText.res(R.string.account_choose_public_access)); return
        }
        if (endpoint.isSsh && choice !in listOf(RepositoryCredentialStore.Choice.KEEP, RepositoryCredentialStore.Choice.PUBLIC_KEEP, RepositoryCredentialStore.Choice.SSH) ||
            !endpoint.isSsh && choice == RepositoryCredentialStore.Choice.SSH) {
            _state.value = current.copy(error = UiText.res(R.string.account_choose_ssh_or_https)); return
        }
        if (choice == RepositoryCredentialStore.Choice.GITHUB && (accountId == null || auth.accountSummary(accountId)?.connected != true)) {
            _state.value = current.copy(error = UiText.res(R.string.account_sign_in_before_account_access)); return
        }
        if (choice == RepositoryCredentialStore.Choice.REPLACE && (username.isBlank() || secret.isEmpty())) {
            _state.value = current.copy(error = UiText.res(R.string.account_enter_username_secret)); return
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
                if (fresh.revision != plan.revision || fresh.metadataIdentity != plan.metadataIdentity) throw Refusal(R.string.account_configuration_changed)
                credentials.updateConnection(plan.path, plan.fetchUrl ?: throw Refusal(R.string.account_no_remote), RepositoryCredentialStore.Choice.PUBLIC, null, current.credentialVersion) {}
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
                if (request == generation) _state.value = current.copy(busy = false, error = (e as? Refusal)?.let { UiText.res(it.text) } ?: UiText.of(e, R.string.account_save_connection_failed))
            }
        }
    }
}

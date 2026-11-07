package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.automation.PullAllCatalog
import com.bontecou.syncmd.domain.repository.PullRepository
import com.bontecou.syncmd.domain.repository.RemoteRepository
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.ConnectivityMonitor
import com.bontecou.syncmd.services.automation.PullAllBusy
import com.bontecou.syncmd.services.automation.PullAllOutcome
import com.bontecou.syncmd.services.automation.PullAllRunner
import com.bontecou.syncmd.services.automation.PullAllTarget
import com.bontecou.syncmd.services.git.RepositorySyncStatus
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.github.GitHubSignInStatus
import com.bontecou.syncmd.storage.VaultPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Home's own state: pins ([preferences]), connectivity, the sign-in-expired record and "Sync all".
 *
 * Sync all reuses the Pull All runner (fetch and fast-forward every vault, never touching edits), then
 * pushes each vault that still has commits to push and is not behind its upstream. Edits that aren't
 * committed are never staged or committed here.
 */
@HiltViewModel
class HomeViewModel(
    val preferences: VaultPreferences,
    private val connectivity: ConnectivityMonitor?,
    private val credentials: (String) -> RepositoryCredentialStore.Summary?,
    val signInStatus: GitHubSignInStatus?,
    private val snapshot: () -> List<PullAllTarget>,
    private val runner: PullAllRunner,
    private val push: suspend (path: String, branch: String, remote: String, remoteBranch: String) -> Result<Unit>,
    private val tokenFor: (accountId: String?) -> String?,
    private val activeAccountId: () -> String?,
    private val read: suspend (String) -> RepositorySyncStatus = { RepositorySyncStatus.read(it) },
) : ViewModel() {
    @Inject constructor(
        @ApplicationContext context: Context,
        preferences: VaultPreferences,
        connectivity: ConnectivityMonitor,
        credentialStore: RepositoryCredentialStore,
        signInStatus: GitHubSignInStatus,
        pull: PullRepository,
        remote: RemoteRepository,
        auth: GitHubAuthManager,
    ) : this(
        preferences, connectivity, { path -> credentialStore.summary(path) }, signInStatus,
        PullAllCatalog(context)::snapshot, PullAllRunner(pull, PullAllCatalog(context)::requireCurrent),
        { path, branch, name, remoteBranch -> remote.push(path, branch, name, remoteBranch, false) },
        { id -> kotlin.runCatching { id?.let(auth::tokenForAccount) ?: auth.defaultToken() }.getOrNull() },
        auth::activeAccountId,
    )

    /** One vault's Sync all result. [skipped] = Pull All left it alone (edits, no upstream, a merge in progress). */
    data class Outcome(val pulled: Boolean = false, val pushed: Boolean = false, val failed: Boolean = false, val skipped: Boolean = false)

    data class SyncAll(
        val busy: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val current: String? = null,
        val results: Map<String, Outcome> = emptyMap(),
        /** Set when a run ends, so Home can report it once. */
        val finished: Summary? = null,
        val failedToStart: Boolean = false,
        val alreadyRunning: Boolean = false,
    )
    data class Summary(val pulled: Int, val pushed: Int, val failed: List<String>)

    private val _syncAll = MutableStateFlow(SyncAll())
    val syncAll: StateFlow<SyncAll> = _syncAll.asStateFlow()
    val online: StateFlow<Boolean> = connectivity?.online ?: MutableStateFlow(true)
    private var job: Job? = null

    /** The saved credential for a vault ("SSH key", GitHub account…); null when none is saved or it can't be read. */
    suspend fun credential(path: String): RepositoryCredentialStore.Summary? =
        withContext(Dispatchers.IO) { runCatching { credentials(path) }.getOrNull() }

    fun togglePinned(path: String, pinned: Boolean) = runCatching { preferences.setPinned(path, pinned) }.isSuccess

    fun forget(path: String) { runCatching { preferences.forget(path) } }

    fun consumeFinished() { _syncAll.value = _syncAll.value.copy(finished = null, failedToStart = false, alreadyRunning = false) }

    fun syncAll() {
        if (_syncAll.value.busy || !online.value) return
        _syncAll.value = SyncAll(busy = true)
        job = viewModelScope.launch {
            val results = linkedMapOf<String, Outcome>()
            val names = mutableMapOf<String, String>()
            try {
                val targets = withContext(Dispatchers.IO) { snapshot() }
                targets.forEach { names[it.path] = it.name }
                _syncAll.value = _syncAll.value.copy(total = targets.size)
                runner.run(targets, onStart = { target ->
                    _syncAll.value = _syncAll.value.copy(current = target.path)
                }, onResult = { result ->
                    val path = result.target.path
                    var outcome = when (result.outcome) {
                        PullAllOutcome.UPDATED -> Outcome(pulled = true)
                        PullAllOutcome.UP_TO_DATE -> Outcome()
                        PullAllOutcome.BLOCKED -> Outcome(skipped = true)
                        PullAllOutcome.ATTENTION, PullAllOutcome.FAILED -> Outcome(failed = true)
                    }
                    if (!outcome.failed) when (pushIfAhead(path)) {
                        true -> outcome = outcome.copy(pushed = true, skipped = false)
                        false -> outcome = outcome.copy(failed = true)
                        null -> Unit
                    }
                    results[path] = outcome
                    _syncAll.value = _syncAll.value.copy(done = results.size, current = null, results = results.toMap())
                })
                _syncAll.value = _syncAll.value.copy(finished = summary(results, names))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: PullAllBusy) { _syncAll.value = _syncAll.value.copy(alreadyRunning = true)
            } catch (_: Exception) { _syncAll.value = _syncAll.value.copy(failedToStart = true, finished = summary(results, names))
            } finally { _syncAll.value = _syncAll.value.copy(busy = false, current = null) }
        }
    }

    /** Pushes when the branch tracks an upstream, has commits to push and isn't behind it. Null = nothing to push. */
    private suspend fun pushIfAhead(path: String): Boolean? {
        val status = withContext(Dispatchers.IO) { runCatching { read(path) }.getOrNull() } ?: return null
        val upstream = status.upstream ?: return null
        if (!status.safe || status.ahead == 0 || status.behind > 0 || status.branch.isBlank()) return null
        val result = withContext(Dispatchers.IO) { push(path, status.branch, upstream.remote, upstream.remoteBranch) }
        result.exceptionOrNull()?.let { error ->
            if (GitHubSignInStatus.isAuthenticationFailure(error)) markGitHubExpired(path)
            return false
        }
        return true
    }

    private suspend fun markGitHubExpired(path: String) {
        val summary = credential(path)?.takeIf { it.usesGithubAccount } ?: return
        val accountId = summary.accountId ?: activeAccountId()
        val token = withContext(Dispatchers.IO) { tokenFor(summary.accountId) } ?: return
        signInStatus?.markExpired(token, accountId)
    }

    private fun summary(results: Map<String, Outcome>, names: Map<String, String>) = Summary(
        pulled = results.values.count { it.pulled },
        pushed = results.values.count { it.pushed },
        failed = results.filterValues { it.failed }.keys.map { names[it] ?: it },
    )

    override fun onCleared() { job?.cancel() }
}

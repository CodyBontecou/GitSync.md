package com.bontecou.syncmd.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.R
import com.bontecou.syncmd.security.DefaultAuthorStore
import com.bontecou.syncmd.security.RepositoryAuthors
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.repositories.RepositoryIdentity
import com.bontecou.syncmd.services.repositories.RepositoryIdentityPlan
import com.bontecou.syncmd.ui.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * "Commit as" (board CommitAs): a vault's own author in `.git/config`, or, with [loadGlobal], the
 * app-wide default every vault without its own author falls back to.
 */
@HiltViewModel
class AuthorViewModel(private val authors: RepositoryAuthors, private val defaults: DefaultAuthorStore? = null,
    private val login: () -> String? = { null }) : ViewModel() {
    @Inject constructor(authors: RepositoryAuthors, defaults: DefaultAuthorStore, auth: GitHubAuthManager) :
        this(authors, defaults, { auth.cachedLogin.value })
    /**
     * [overrides]: the vault has its own author (or, globally, a saved default) instead of the
     * account's. [changed]: the author changed outside GitSync.md since it was read; reload first.
     */
    data class State(val busy: Boolean = true, val plan: RepositoryIdentityPlan? = null,
        val name: String = "", val email: String = "", val error: UiText? = null, val saved: Boolean = false,
        val requestId: Long = 0, val global: Boolean = false, val overrides: Boolean = false, val login: String? = null,
        val changed: Boolean = false, val accountName: String = "", val accountEmail: String = "")
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    @Volatile private var generation = 0L
    fun load(path: String) {
        val request = ++generation
        _state.value = State(requestId = request, login = login())
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val plan = RepositoryIdentity().inspect(path)
                    val own = !plan.name.isNullOrBlank() || !plan.email.isNullOrBlank()
                    val fallback = if (!own) authors.fallback(path) else null
                    val account = runCatching { authors.accountAuthor(path) }.getOrNull()
                    State(false, plan, plan.name ?: fallback?.first.orEmpty(), plan.email ?: fallback?.second.orEmpty(), requestId = request,
                        overrides = own, login = login(), accountName = account?.first.orEmpty(), accountEmail = account?.second.orEmpty())
                }
                if (generation == request) _state.value = loaded
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (generation == request) _state.value = State(busy = false, error = UiText.of(e, R.string.account_load_author_failed), requestId = request) }
        }
    }
    /** Re-reads the config on return; a different revision means someone else (Git on a Mac) changed it. */
    fun verify(path: String) {
        val current = _state.value
        val plan = current.plan ?: return
        if (current.busy || current.saved || current.global) return
        val request = generation
        viewModelScope.launch {
            val fresh = runCatching { withContext(Dispatchers.IO) { RepositoryIdentity().inspect(path) } }.getOrNull() ?: return@launch
            if (generation == request && _state.value.plan === plan && fresh.revision != plan.revision) _state.value = _state.value.copy(changed = true)
        }
    }
    fun save(review: State, name: String, email: String) {
        if (_state.value !== review) return
        val current = review
        if (current.global) { saveGlobal(current, name, email); return }
        val plan = current.plan ?: return
        if (current.busy || current.saved || current.changed) return
        val request = generation
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RepositoryOperationCoordinator.withRepository(plan.path) {
                        // A reload or repository switch can supersede this draft while it waits.
                        // Recheck under the same lease that protects the config write.
                        if (generation == request) RepositoryIdentity().save(plan, name, email)
                    }
                }
                if (generation == request) _state.value = current.copy(busy = false, saved = true)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (generation == request) {
                    val stale = e is IllegalStateException && e.message.orEmpty().contains("changed", ignoreCase = true)
                    _state.value = current.copy(busy = false, changed = stale, error = UiText.of(e, R.string.account_save_author_failed))
                }
            }
        }
    }

    // ─── App-wide default ("Settings › Commit as") ──────────────────────────
    fun loadGlobal() {
        val request = ++generation
        val store = defaults
        _state.value = State(requestId = request, global = true, login = login())
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val saved = store?.author?.value
                val account = runCatching { authors.accountAuthor(null) }.getOrNull()
                State(busy = false, name = saved?.name ?: account?.first.orEmpty(), email = saved?.email ?: account?.second.orEmpty(),
                    requestId = request, global = true, overrides = saved != null, login = login(),
                    accountName = account?.first.orEmpty(), accountEmail = account?.second.orEmpty())
            }
            if (generation == request) _state.value = loaded
        }
    }
    private fun saveGlobal(current: State, name: String, email: String) {
        val store = defaults ?: return
        if (current.busy || current.saved) return
        val request = generation
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Saving the account's own author is the same as having no default.
                    if (name.trim() == current.accountName && email.trim() == current.accountEmail) store.clear() else store.save(name, email)
                }
                if (generation == request) _state.value = current.copy(busy = false, saved = true)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (generation == request) _state.value = current.copy(busy = false, error = UiText.of(e, R.string.account_save_author_failed)) }
        }
    }
    /** "Use my GitHub account" for the app-wide default. */
    fun clearGlobal() {
        val store = defaults ?: return
        if (_state.value.busy) return
        runCatching { store.clear() }
        loadGlobal()
    }
}

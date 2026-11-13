package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.github.GitHubRepo
import com.bontecou.syncmd.services.github.GitHubRepoInsights
import com.bontecou.syncmd.services.github.RepoNoteSignalCache
import com.bontecou.syncmd.services.github.RepoNoteSignals
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/** Owner chips of the repo picker (board "13 · Pick a repo"). */
enum class RepoOwnerFilter { YOURS, ORGS, STARRED }

/**
 * The picker's extra reads: starred repositories, and the "Looks like notes" signals per repository
 * (contents API, cached until the repository is pushed again). Repository lists come from [GitHubViewModel].
 */
@HiltViewModel
class RepoPickerViewModel @Inject constructor(private val auth: GitHubAuthManager, @ApplicationContext context: Context) : ViewModel() {
    private val cache = RepoNoteSignalCache(context)
    private val _signals = MutableStateFlow<Map<String, RepoNoteSignals>>(emptyMap())
    val signals = _signals.asStateFlow()
    private val _filter = MutableStateFlow(RepoOwnerFilter.YOURS)
    val filter = _filter.asStateFlow()
    private val _starred = MutableStateFlow<List<GitHubRepo>?>(null)
    val starred = _starred.asStateFlow()
    private val _starredLoading = MutableStateFlow(false)
    val starredLoading = _starredLoading.asStateFlow()
    private val _starredError = MutableStateFlow(false)
    val starredError = _starredError.asStateFlow()
    private var starredToken: String? = null
    private var detectJob: Job? = null
    private val pending = mutableSetOf<String>()

    fun select(filter: RepoOwnerFilter) {
        _filter.value = filter
        if (filter == RepoOwnerFilter.STARRED) loadStarred()
    }

    fun loadStarred(force: Boolean = false) {
        val token = auth.getToken() ?: return
        if (!force && starredToken == token && (_starred.value != null || _starredLoading.value)) return
        starredToken = token; _starredLoading.value = true; _starredError.value = false
        viewModelScope.launch {
            try { _starred.value = GitHubRepoInsights.fetchStarred(token) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _starredError.value = true }
            finally { _starredLoading.value = false }
        }
    }

    /** Reads cached signals at once, then asks GitHub (4 at a time) for the repositories not seen since their last push. */
    fun detect(repos: List<GitHubRepo>, force: Boolean = false) {
        val token = auth.getToken() ?: return
        if (force) { cache.clear(); pending.clear(); _signals.value = emptyMap() }
        val known = _signals.value.toMutableMap()
        val missing = repos.filter { repo ->
            if (repo.fullName in known || repo.fullName in pending) false
            else cache.get(repo)?.let { known[repo.fullName] = it; false } ?: true
        }
        _signals.value = known
        if (missing.isEmpty()) return
        pending += missing.map { it.fullName }
        val permits = Semaphore(4)
        val previous = detectJob
        detectJob = viewModelScope.launch {
            previous?.join()
            missing.map { repo ->
                async {
                    permits.withPermit {
                        if (auth.getToken() != token) return@withPermit
                        try {
                            val found = GitHubRepoInsights.signals(token, repo)
                            cache.put(repo, found)
                            _signals.value = _signals.value + (repo.fullName to found)
                        } catch (e: CancellationException) { throw e
                        } catch (_: Exception) { /* Shown under "Everything else"; tried again next time the picker opens. */
                        } finally { pending -= repo.fullName }
                    }
                }
            }.awaitAll()
        }
    }
}

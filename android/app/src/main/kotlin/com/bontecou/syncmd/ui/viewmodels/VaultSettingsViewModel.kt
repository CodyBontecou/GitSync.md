package com.bontecou.syncmd.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.background.BackgroundConditionDefaults
import com.bontecou.syncmd.background.BackgroundSyncStore
import com.bontecou.syncmd.diagnostics.AppDiagnostics
import com.bontecou.syncmd.services.automation.RepositoryActionPlan
import com.bontecou.syncmd.services.background.BackgroundHealth
import com.bontecou.syncmd.services.diagnostics.DiagnosticLevel
import com.bontecou.syncmd.services.diagnostics.Diagnostics
import com.bontecou.syncmd.storage.VaultPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Board 16 · Vault settings & health. The health card is real data: files in the working tree,
 * bytes of downloaded LFS objects, ERROR entries for this vault in the debug log over 7 days, and
 * Background Sync's last check.
 */
@HiltViewModel
class VaultSettingsViewModel @Inject constructor(@ApplicationContext private val context: Context, val preferences: VaultPreferences,
    private val diagnostics: AppDiagnostics, private val background: BackgroundSyncStore) : ViewModel() {
    data class Health(val files: Int? = null, val lfsBytes: Long? = null, val errors7d: Int = 0, val lastCheck: BackgroundHealth? = null) {
        val good get() = errors7d == 0 && lastCheck?.kind !in setOf(
            com.bontecou.syncmd.services.background.BackgroundHealthKind.FAILED, com.bontecou.syncmd.services.background.BackgroundHealthKind.UNAVAILABLE,
            com.bontecou.syncmd.services.background.BackgroundHealthKind.ATTENTION, com.bontecou.syncmd.services.background.BackgroundHealthKind.CHANGED,
            com.bontecou.syncmd.services.background.BackgroundHealthKind.WRONG_BRANCH)
    }
    /** [binding] is "branch main, tracking origin/main" for the Background Sync subtitle. */
    data class State(val path: String = "", val health: Health = Health(), val binding: Triple<String, String, String>? = null,
        val review: RepositoryActionPlan? = null, val busy: Boolean = false, val error: String? = null)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var watcher: Job? = null

    fun open(path: String) {
        if (mutable.value.path == path && watcher != null) return
        mutable.value = State(path = path)
        watcher?.cancel()
        watcher = viewModelScope.launch {
            launch(Dispatchers.IO) { runCatching { diagnostics.store.load() } }
            launch {
                val counts = withContext(Dispatchers.IO) { countFiles(File(path)) to directorySize(File(path, ".git/lfs/objects")) }
                mutable.value = mutable.value.copy(health = mutable.value.health.copy(files = counts.first, lfsBytes = counts.second))
            }
            val key = Diagnostics.vaultKey(path)
            combine(diagnostics.store.entries, background.changes) { entries, _ -> entries }.collectLatest { entries ->
                val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                val errors = entries.count { it.vault == key && it.level == DiagnosticLevel.ERROR && it.time >= weekAgo }
                val policy = withContext(Dispatchers.IO) { runCatching { background.read().policies[path] }.getOrNull() }
                mutable.value = mutable.value.copy(health = mutable.value.health.copy(errors7d = errors, lastCheck = policy?.health?.takeIf { it.attemptedAt > 0 }),
                    binding = policy?.let { Triple(it.branch, it.remote, it.upstream) } ?: mutable.value.binding)
            }
        }
    }

    /** Include switch on: review first, then [include]. Off: exclude straight away. */
    fun setIncluded(on: Boolean) = run {
        val path = mutable.value.path
        if (on) {
            val plan = preferences.reviewBackgroundSync(path)
            mutable.value = mutable.value.copy(review = plan, binding = Triple(plan.branch.orEmpty(), plan.remote.orEmpty(), plan.remoteBranch.orEmpty()))
        } else preferences.excludeFromBackgroundSync(path)
    }
    fun include() = run {
        val plan = mutable.value.review ?: return@run
        val conditions = withContext(Dispatchers.IO) { BackgroundConditionDefaults(context).read(background) }
        preferences.includeInBackgroundSync(plan, conditions.wifiOnly, conditions.chargingOnly)
        mutable.value = mutable.value.copy(review = null)
    }
    fun dismissReview() { mutable.value = mutable.value.copy(review = null) }
    fun setPinned(on: Boolean) = run { withContext(Dispatchers.IO) { preferences.setPinned(mutable.value.path, on) } }
    fun setDisplayName(name: String) = run { withContext(Dispatchers.IO) { preferences.setDisplayName(mutable.value.path, name) } }
    fun clearError() { mutable.value = mutable.value.copy(error = null) }

    private fun run(action: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(error = e.message ?: e.javaClass.simpleName) }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    companion object {
        /** Files in the working tree, not counting Git's own metadata; null if the folder can't be read. */
        fun countFiles(root: File): Int? = runCatching {
            require(root.isDirectory)
            root.walkTopDown().onEnter { it == root || it.name != ".git" }.count { it.isFile }
        }.getOrNull()
        fun directorySize(root: File): Long = runCatching { if (!root.isDirectory) 0L else root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
    }
}

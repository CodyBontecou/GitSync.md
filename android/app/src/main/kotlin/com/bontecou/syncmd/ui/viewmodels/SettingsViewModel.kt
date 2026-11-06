package com.bontecou.syncmd.ui.viewmodels

import com.bontecou.syncmd.services.repositories.CloneCatalogProvenance
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.git.SampleRepository
import com.bontecou.syncmd.services.repositories.*
import com.bontecou.syncmd.storage.CloneStorage
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject

data class AppSettings(
    val selectedRepository: String = "",
    val autoFetchInterval: Long = 3600000L,
    val isDarkTheme: Boolean = false,
    val showDebugInfo: Boolean = false,
)
data class SavedRepository(
    val name: String,
    val path: String,
    val alias: String = "",
    val dateAdded: Long = System.currentTimeMillis(),
    val managedStorage: Boolean = false,
    val remoteUrl: String? = null,
    val branch: String? = null,
)
data class RepositoryHistoryEntry(
    val repository: SavedRepository,
    val removedAt: Long = System.currentTimeMillis(),
    val filesDeleted: Boolean = false,
    val trackingRemoved: Boolean = false,
    val backupDirectory: String? = null,
    val backupArchive: String? = null,
    val expectedMetadataIdentity: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    companion object { private val persistence = Mutex() }
    private val prefs = context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val initialLoad = CompletableDeferred<Unit>()
    private val discovery = RepositoryDiscovery()
    private val tracking = com.bontecou.syncmd.storage.AndroidRepositoryServices.trackingRemoval()
    private val moves = com.bontecou.syncmd.storage.AndroidRepositoryServices.moves(context)
    private val clonePublications = com.bontecou.syncmd.storage.clonePublications(context)
    private val credentials = com.bontecou.syncmd.security.RepositoryCredentialStore(context)
    private val locations = com.bontecou.syncmd.storage.RepositoryLocations(context)
    private val moveReviews = MoveReviewState()
    val moveReview = moveReviews.review
    val moveReviewError = moveReviews.error
    internal val moveCompletion = moveReviews.completion
    private val _moveJournals = MutableStateFlow<List<RepositoryMoveJournal>>(emptyList())
    val moveJournals = _moveJournals.asStateFlow()
    private val _selectedRepository = MutableStateFlow("")
    val selectedRepository = _selectedRepository.asStateFlow()
    private val _allRepositories = MutableStateFlow<List<SavedRepository>>(emptyList())
    val allRepositories = _allRepositories.asStateFlow()
    private val _repositoryHistory = MutableStateFlow<List<RepositoryHistoryEntry>>(emptyList())
    val repositoryHistory = _repositoryHistory.asStateFlow()
    private val _appSettings = MutableStateFlow(AppSettings())
    val appSettings = _appSettings.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    private val _destructiveBusy = MutableStateFlow(false)
    val destructiveBusy = _destructiveBusy.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()
    private val _isOpeningSample = MutableStateFlow(false)
    val isOpeningSample = _isOpeningSample.asStateFlow()
    private val _sampleError = MutableStateFlow<String?>(null)
    val sampleError = _sampleError.asStateFlow()
    private val _discoveryResult = MutableStateFlow<DiscoveryResult?>(null)
    val discoveryResult = _discoveryResult.asStateFlow()
    private val _deletionReview = MutableStateFlow<RepositoryDeletionReview?>(null)
    val deletionReview = _deletionReview.asStateFlow()
    @Volatile private var deletionReviewOwner: Any = Any()
    private val _trackingPlan = MutableStateFlow<GitTrackingRemovalPlan?>(null)
    val trackingPlan = _trackingPlan.asStateFlow()
    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage = _operationMessage.asStateFlow()

    private val _cloneRecoveries = MutableStateFlow<List<com.bontecou.syncmd.services.repositories.CloneRecovery>>(emptyList())
    val cloneRecoveries = _cloneRecoveries.asStateFlow()
    private val _cloneRecoveryUnavailable = MutableStateFlow(false)
    val cloneRecoveryUnavailable = _cloneRecoveryUnavailable.asStateFlow()
    private fun readCloneRecoveries() {
        try {
            _cloneRecoveries.value = clonePublications.inspect().filter { review ->
                (review.id?.let { !clonePublications.isActive(it) } != false) &&
                    (review.damaged || review.publication?.registeredDate == null)
            }
            _cloneRecoveryUnavailable.value = false
        } catch (_: Exception) {
            _cloneRecoveries.value = emptyList(); _cloneRecoveryUnavailable.value = true
        }
    }
    fun refreshCloneRecoveries() = mutate({ readCloneRecoveries() })
    fun dismissCloneRecovery(review: com.bontecou.syncmd.services.repositories.CloneRecovery) = mutate({
        try {
            // Catalog/recovery mutations share persistence; the ledger additionally guards live owners.
            // Dismissing private metadata must also work when the repository folder is inaccessible.
            clonePublications.dismiss(review) {
                review.publication?.let { record ->
                    persist(_allRepositories.value, _repositoryHistory.value, _selectedRepository.value,
                        cloneReceipt = CloneCatalogProvenance.revocation(record.id, record.completed,
                            _allRepositories.value.singleOrNull { it.path == record.completed.path }?.dateAdded))
                }
            }
            _operationMessage.value = context.getString(com.bontecou.syncmd.R.string.clone_recovery_dismissed)
        } finally { readCloneRecoveries() }
    }, destructive = true)
    fun retryCloneRecovery(review: com.bontecou.syncmd.services.repositories.CloneRecovery) = mutate({
        try {
            require(clonePublications.inspect().singleOrNull { it.key == review.key } == review) { text(com.bontecou.syncmd.R.string.vault_l10n_clone_recovery_changed_review) }
            val record = requireNotNull(review.publication)
            require(record.registeredDate == null && !clonePublications.isActive(record.id)) { text(com.bontecou.syncmd.R.string.vault_l10n_clone_not_awaiting) }
            recoverClonePublication(record)
            _operationMessage.value = context.getString(com.bontecou.syncmd.R.string.clone_recovery_added)
        } finally { readCloneRecoveries() }
    }, destructive = true)

    init { loadSettings() }

    fun loadSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            persistence.withLock {
                _isLoading.value = true
                try {
                    _errorMessage.value = null
                    readState()
                    recoverMoves()
                    recoverInterruptedRemovals()
                    recoverClonePublications()
                    if (!com.bontecou.syncmd.obsidian.ObsidianConnections(context,
                        com.bontecou.syncmd.services.github.GitHubAuthManager(context), credentials).recoverPendingPreparations() && _errorMessage.value == null) {
                        _errorMessage.value = text(com.bontecou.syncmd.R.string.vault_l10n_obsidian_review)
                    }
                    _appSettings.value = AppSettings(_selectedRepository.value,
                        prefs.getLong("auto_fetch_interval", 3600000L), prefs.getBoolean("is_dark_theme", false), prefs.getBoolean("show_debug_info", false))
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { _errorMessage.value = text(com.bontecou.syncmd.R.string.vault_l10n_settings_load_failed, e.message.toString())
                } finally { readCloneRecoveries(); _isLoading.value = false; initialLoad.complete(Unit) }
            }
        }
    }

    private fun readState() {
        _allRepositories.value = (0 until prefs.getInt("repository_count", 0)).mapNotNull { index ->
            val name = prefs.getString("repo_${index}_name", null) ?: return@mapNotNull null
            val rawPath = prefs.getString("repo_${index}_path", null) ?: return@mapNotNull null
            val path = canonical(rawPath)
            SavedRepository(name, path, prefs.getString("repo_${index}_alias", "").orEmpty(), prefs.getLong("repo_${index}_date", System.currentTimeMillis()),
                prefs.getBoolean("repo_${index}_managed", CloneStorage.isManagedClonePath(context, path)),
                prefs.getString("repo_${index}_remote", null)?.let(RepositoryDiscovery::redactRemoteUrl), prefs.getString("repo_${index}_branch", null))
        }.distinctBy { it.path }
        _selectedRepository.value = prefs.getString("selected_repository", "").orEmpty().let { if (it.isBlank()) "" else canonical(it) }
        _repositoryHistory.value = readHistory("repo_history_json")
    }

    private fun readHistory(key: String): List<RepositoryHistoryEntry> = runCatching {
        gson.fromJson(prefs.getString(key, "[]"), Array<RepositoryHistoryEntry>::class.java).orEmpty().filter {
            runCatching { it.repository.path.isNotBlank() && it.repository.name.isNotBlank() }.getOrDefault(false)
        }
    }.getOrDefault(emptyList())

    private fun persist(repos: List<SavedRepository>, history: List<RepositoryHistoryEntry>, selected: String, pending: List<RepositoryHistoryEntry>? = null,
        publicationReceipt: Pair<String, String>? = null, cloneReceipt: Pair<String, String>? = null) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.matches(Regex("repo_\\d+_(name|path|alias|date|managed|remote|branch)")) }.forEach { editor.remove(it) }
        editor.putInt("repository_count", repos.size).putString("selected_repository", selected)
            .putString("repo_history_json", gson.toJson(history.take(100)))
        repos.forEachIndexed { index, repo ->
            editor.putString("repo_${index}_name", repo.name).putString("repo_${index}_path", repo.path)
                .putString("repo_${index}_alias", repo.alias).putLong("repo_${index}_date", repo.dateAdded)
                .putBoolean("repo_${index}_managed", repo.managedStorage).putString("repo_${index}_remote", repo.remoteUrl)
                .putString("repo_${index}_branch", repo.branch)
        }
        if (pending != null) editor.putString("pending_tracking_removals", gson.toJson(pending))
        publicationReceipt?.let { (key, value) -> editor.putString(key, value) }
        val liveClones = runCatching { clonePublications.inspect().mapNotNull { it.id }.toSet() }.getOrNull()
        CloneCatalogProvenance.plan(prefs.all, repos.associate { it.path to it.dateAdded }, liveClones, cloneReceipt).forEach { (key, value) ->
            if (value == null) editor.remove(key) else editor.putString(key, value)
        }
        check(editor.commit()) { text(com.bontecou.syncmd.R.string.vault_l10n_repository_settings_save_failed) }
        _allRepositories.value = repos
        _repositoryHistory.value = history.take(100)
        _selectedRepository.value = selected
        _appSettings.value = _appSettings.value.copy(selectedRepository = selected)
    }

    private fun recoverInterruptedRemovals() {
        val pending = readHistory("pending_tracking_removals")
        if (pending.isEmpty()) return
        var repos = _allRepositories.value
        var history = _repositoryHistory.value
        var selected = _selectedRepository.value
        val deferred = mutableListOf<RepositoryHistoryEntry>()
        for (entry in pending) {
            if (runCatching { com.bontecou.syncmd.storage.RepositoryLocations(context).requireRepositoryAccess(entry.repository.path) }.isFailure) {
                deferred += entry
                continue
            }
            if (GitTrackingRemoval.metadataIdentity(entry.repository.path) != entry.expectedMetadataIdentity || entry.expectedMetadataIdentity == null) {
                repos = repos.filterNot { canonical(it.path) == canonical(entry.repository.path) }
                history = listOf(entry) + history.filterNot { canonical(it.repository.path) == canonical(entry.repository.path) }
                if (canonical(selected) == canonical(entry.repository.path)) selected = repos.firstOrNull()?.path.orEmpty()
            }
        }
        // Commit disconnection, history and journal cleanup before publishing recovery results.
        persist(repos, history, selected, deferred)
        if (deferred.isNotEmpty()) _errorMessage.value = text(com.bontecou.syncmd.R.string.vault_l10n_recovery_needs_access)
    }

    private fun finishRemoval(entry: RepositoryHistoryEntry, pending: List<RepositoryHistoryEntry>? = null) {
        val repos = _allRepositories.value.filterNot { it.path == entry.repository.path }
        val history = listOf(entry) + _repositoryHistory.value.filterNot { it.repository.path == entry.repository.path }
        val selected = if (_selectedRepository.value == entry.repository.path) repos.firstOrNull()?.path.orEmpty() else _selectedRepository.value
        persist(repos, history, selected, pending)
    }

    private fun mutate(action: suspend () -> Unit, onSuccess: () -> Unit = {}, destructive: Boolean = false, onFinally: () -> Unit = {}, onError: ((Exception) -> Unit)? = null) {
        if (destructive && _destructiveBusy.value) return
        if (destructive) _destructiveBusy.value = true
        viewModelScope.launch {
            try {
                initialLoad.await()
                withContext(Dispatchers.IO) {
                    persistence.withLock {
                        _isLoading.value = true
                        if (onError == null) _errorMessage.value = null
                        try { readState(); action() } finally { _isLoading.value = false }
                    }
                }
                onSuccess()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (onError != null) onError(e) else _errorMessage.value = userMessage(e)
            } finally {
                if (destructive) _destructiveBusy.value = false
                onFinally()
            }
        }
    }

    private fun text(id: Int, vararg args: Any): String = context.getString(id, *args)
    /** Typed failures get localized copy; other exceptions keep their diagnostic message. */
    private fun userMessage(e: Exception): String = when (e) {
        is com.bontecou.syncmd.storage.StaleMoveReviewException -> text(com.bontecou.syncmd.R.string.move_error_stale_review)
        is StaleTrackingRemovalException -> text(com.bontecou.syncmd.R.string.vault_l10n_tracking_review_changed)
        is StaleRepositoryDeletionException -> text(com.bontecou.syncmd.R.string.vault_l10n_deletion_review_changed)
        is com.bontecou.syncmd.storage.MoveDestinationExistsException -> text(com.bontecou.syncmd.R.string.vault_l10n_move_destination_exists)
        else -> e.message ?: text(com.bontecou.syncmd.R.string.ui_repository_operation_failed_f1152917)
    }

    fun setRepositoryPath(path: String, onSelected: () -> Unit = {}) = mutate({
        val info = discovery.inspect(path)
        persist(_allRepositories.value, _repositoryHistory.value, info.path)
    }, onSelected)

    fun addRepository(name: String, path: String, alias: String = "", managedStorage: Boolean = false, prepareObsidian: Boolean = false, onAdded: () -> Unit = {}) = mutate({
        addNow(name, path, alias, managedStorage)
        if (prepareObsidian) prepareObsidianConnection(path)
    }, onAdded)

    /** Retry the same completed clone without cloning again or adopting a replacement folder. */
    suspend fun registerCompletedClone(completed: com.bontecou.syncmd.services.repositories.CompletedClone, name: String, alias: String = "",
        historyEntry: RepositoryHistoryEntry? = null) {
        initialLoad.await()
        withContext(Dispatchers.IO) { persistence.withLock { registerCompletedCloneNow(completed, name, alias, historyEntry) } }
    }
    private suspend fun registerCompletedCloneNow(completed: com.bontecou.syncmd.services.repositories.CompletedClone, name: String, alias: String,
        historyEntry: RepositoryHistoryEntry?) = RepositoryOperationCoordinator.withRepository(completed.path) {
            readState()
            require(com.bontecou.syncmd.services.repositories.CompletedClone.capture(completed.path, credentials.recordVersion(completed.path)) == completed) {
                text(com.bontecou.syncmd.R.string.vault_l10n_clone_changed_reconnect)
            }
            require(name.isNotBlank())
            val previous = historyEntry?.let { selected -> _repositoryHistory.value.singleOrNull {
                canonical(it.repository.path) == canonical(selected.repository.path)
            } }
            val expectedAlias = historyEntry?.repository?.alias ?: alias
            val publication = requireNotNull(clonePublications.records().singleOrNull { it.completed == completed && it.name == name && it.alias == expectedAlias }) {
                text(com.bontecou.syncmd.R.string.vault_l10n_clone_recovery_unavailable)
            }
            val managed = CloneStorage.isManagedClonePath(context, completed.path)
            val existing = _allRepositories.value.singleOrNull { it.path == completed.path }
            CloneCatalogProvenance.requireAllowed(prefs.all, publication.id, completed, existing?.dateAdded)
            if (publication.registeredDate != null) require(existing?.dateAdded == publication.registeredDate) { text(com.bontecou.syncmd.R.string.vault_l10n_clone_removed_or_registered) }
            if (existing != null) require(existing.name == name && existing.alias == expectedAlias && existing.managedStorage == managed &&
                existing.remoteUrl == completed.remote && existing.branch == completed.branch) { text(com.bontecou.syncmd.R.string.vault_l10n_folder_registered_differently_review) }
            if (historyEntry != null) require(previous == historyEntry || previous == null && existing != null) { text(com.bontecou.syncmd.R.string.vault_l10n_history_changed_clone_kept) }
            val entry = existing ?: SavedRepository(name, completed.path, expectedAlias, managedStorage = managed,
                remoteUrl = completed.remote, branch = completed.branch)
            val pendingKey = CloneCatalogProvenance.PENDING_PREFIX + CloneCatalogProvenance.fingerprint(completed)
            val pending = existing == null || prefs.getString(pendingKey, null) == "pending"
            // Always commit, including retries after SharedPreferences changed memory but failed to save to disk.
            persist(if (existing == null) _allRepositories.value + entry else _allRepositories.value,
                _repositoryHistory.value.filterNot { canonical(it.repository.path) == completed.path || historyEntry != null &&
                    canonical(it.repository.path) == canonical(historyEntry.repository.path) }, _selectedRepository.value.ifBlank { completed.path },
                publicationReceipt = if (pending) pendingKey to "pending" else null,
                cloneReceipt = CloneCatalogProvenance.registration(publication.id, completed, entry.dateAdded))
            if (pending) {
                // Retain existing public connection metadata, including after an explicit Forget.
                if (!java.nio.file.Files.exists(File(completed.path, ".git/gitsync-obsidian/connection.json").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    prepareObsidianConnection(completed.path)
                }
                check(prefs.edit().remove(pendingKey).commit()) { text(com.bontecou.syncmd.R.string.vault_l10n_clone_finish_save_failed) }
            }
            clonePublications.registered(publication.id, entry.dateAdded)
    }
    private suspend fun recoverClonePublication(selected: com.bontecou.syncmd.services.repositories.ClonePublication) {
        RepositoryOperationCoordinator.withRepository(selected.completed.path) {
            val current = clonePublications.get(selected.id) ?: error(text(com.bontecou.syncmd.R.string.vault_l10n_clone_recovery_dismissed_error))
            require(current == selected && current.registeredDate == null && !clonePublications.isActive(current.id)) { text(com.bontecou.syncmd.R.string.vault_l10n_clone_recovery_changed) }
            readState()
            CloneCatalogProvenance.requireAllowed(prefs.all, current.id, current.completed,
                _allRepositories.value.singleOrNull { it.path == current.completed.path }?.dateAdded)
            // Review history before publishing into the final destination.
            val history = current.history?.let { raw ->
                val review = org.json.JSONObject(raw)
                val entry = _repositoryHistory.value.singleOrNull { canonical(it.repository.path) == canonical(review.getString("path")) }
                if (entry != null) require(com.bontecou.syncmd.storage.cloneHistoryReview(entry) == raw) { text(com.bontecou.syncmd.R.string.vault_l10n_clone_history_changed) }
                else require(_allRepositories.value.any { it.path == current.completed.path }) { text(com.bontecou.syncmd.R.string.vault_l10n_clone_history_removed) }
                entry
            }
            val completed = clonePublications.publish(current, credentials.recordVersion(current.completed.path))
            require(clonePublications.get(current.id) == current) { text(com.bontecou.syncmd.R.string.vault_l10n_clone_recovery_cancelled) }
            registerCompletedCloneNow(completed, current.name, current.alias, history)
        }
    }
    private suspend fun recoverClonePublications() {
        val inventory = clonePublications.inspect()
        var needsReview = false
        for (selected in inventory.mapNotNull { it.publication }.filter { it.registeredDate == null }) {
            if (clonePublications.isActive(selected.id)) continue
            try { recoverClonePublication(selected) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { needsReview = true }
        }
        needsReview = needsReview || clonePublications.inspect().any { it.damaged }
        if (needsReview && _errorMessage.value == null) _errorMessage.value = context.getString(com.bontecou.syncmd.R.string.clone_recovery_review_needed)
    }

    fun addHistoryClone(name: String, path: String, historyPath: String, onAdded: () -> Unit = {}) = mutate({
        val previous = _repositoryHistory.value.firstOrNull { canonical(it.repository.path) == canonical(historyPath) }
            ?: error(text(com.bontecou.syncmd.R.string.vault_l10n_history_changed_clone_kept))
        addNow(name, path, previous.repository.alias, CloneStorage.isManagedClonePath(context, path), previous.repository.path)
        prepareObsidianConnection(path)
    }, onAdded)

    private suspend fun prepareObsidianConnection(path: String) {
        try {
            val summary = credentials.summary(path)
            if (summary?.isPublicAccess != true && summary?.usesGithubAccount == true && com.bontecou.syncmd.services.obsidian.ObsidianHandoff.isGitHubHTTPS(summary.url)) {
                com.bontecou.syncmd.obsidian.ObsidianConnections(context,
                    com.bontecou.syncmd.services.github.GitHubAuthManager(context), credentials).prepare(java.io.File(path).canonicalPath)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _errorMessage.value = text(com.bontecou.syncmd.R.string.vault_l10n_obsidian_prepare_failed) }
    }

    private suspend fun addNow(name: String, path: String, alias: String = "", managedStorage: Boolean = false, historySource: String? = null) {
        require(name.isNotBlank()) { text(com.bontecou.syncmd.R.string.vault_l10n_repository_name_required) }
        val info = discovery.inspect(path)
        require(_allRepositories.value.none { canonical(it.path) == info.path }) { text(com.bontecou.syncmd.R.string.vault_l10n_repository_already_added) }
        require(!managedStorage || CloneStorage.isManagedClonePath(context, info.path)) { text(com.bontecou.syncmd.R.string.vault_l10n_not_managed_location) }
        val repo = SavedRepository(name, info.path, alias, managedStorage = managedStorage, remoteUrl = info.remoteUrl, branch = info.branch)
        persist(_allRepositories.value + repo, _repositoryHistory.value.filterNot { canonical(it.repository.path) == info.path || it.repository.path == historySource },
            _selectedRepository.value.ifBlank { info.path })
    }

    /** Called inside the publication coordinator's guard/lease, before its COMPLETED record is saved. */
    suspend fun registerPublication(record: com.bontecou.syncmd.services.publication.FolderPublicationRecord, accountId: String) {
        initialLoad.await()
        persistence.withLock {
            require(record.phase == com.bontecou.syncmd.services.publication.FolderPublicationPhase.PUBLISHED)
            require(accountId == "github:${record.account.id}") { text(com.bontecou.syncmd.R.string.vault_l10n_publication_account_changed) }
            readState()
            val remote = requireNotNull(record.remote); val path = record.input.review.path
            val info = discovery.inspect(path)
            require(info.path == path && info.remoteUrl == remote.cloneUrl && info.branch == "main") { text(com.bontecou.syncmd.R.string.vault_l10n_publication_settings_changed) }
            val receiptKey = "publication_${record.id}"
            val receipt = org.json.JSONObject().put("workflow", record.id).put("path", path).put("remote", remote.cloneUrl)
                .put("accountId", accountId).put("repositoryId", remote.id).toString()
            val existing = _allRepositories.value.singleOrNull { it.path == path }
            val previous = prefs.getString(receiptKey, null)
            require(previous == null || previous == receipt) { text(com.bontecou.syncmd.R.string.vault_l10n_publication_other_destination) }
            require(existing == null && previous == null || existing != null && previous == receipt &&
                existing.remoteUrl == remote.cloneUrl && existing.branch == "main" && existing.name == remote.name) {
                text(com.bontecou.syncmd.R.string.vault_l10n_folder_registered_differently_kept)
            }
            credentials.bindPublication(record.id, path, remote.cloneUrl, accountId)
            val added = existing ?: SavedRepository(remote.name, path, managedStorage = CloneStorage.isManagedClonePath(context, path),
                remoteUrl = remote.cloneUrl, branch = "main")
            persist(if (existing == null) _allRepositories.value + added else _allRepositories.value,
                _repositoryHistory.value.filterNot { it.repository.path == path }, _selectedRepository.value.ifBlank { path },
                publicationReceipt = receiptKey to receipt)
        }
    }

    fun reconnect(path: String, onReady: () -> Unit = {}) = mutate({
        val previous = _repositoryHistory.value.firstOrNull { it.repository.path == path } ?: error(text(com.bontecou.syncmd.R.string.repository_history_unavailable))
        require(!previous.trackingRemoved) { text(com.bontecou.syncmd.R.string.repository_restore_backup_first) }
        val repo = previous.repository
        addNow(repo.name, repo.path, repo.alias, repo.managedStorage)
    }, onReady)

    fun prepareRepositoryDeletion(path: String) {
        if (_destructiveBusy.value) return
        val owner = Any()
        deletionReviewOwner = owner
        _deletionReview.value = null
        mutate({
            val repo = _allRepositories.value.firstOrNull { it.path == path } ?: error(text(com.bontecou.syncmd.R.string.repository_no_longer_added))
            require(repo.managedStorage) { text(com.bontecou.syncmd.R.string.repository_imported_files_kept) }
            val plan = ManagedRepositoryDeletion().prepare(path, CloneStorage.managedCloneRoots(context))
            if (deletionReviewOwner === owner) _deletionReview.value = RepositoryDeletionReview(repo, plan)
        })
    }
    fun clearRepositoryDeletion() {
        if (!_destructiveBusy.value) { deletionReviewOwner = Any(); _deletionReview.value = null }
    }
    fun removeRepository(path: String) = removeRepositoryNow(path, null)
    fun deleteRepository(review: RepositoryDeletionReview) {
        if (_deletionReview.value !== review) return
        removeRepositoryNow(review.repository.path, review)
    }
    private fun removeRepositoryNow(path: String, deletion: RepositoryDeletionReview?) = mutate({
        suspend fun remove(repo: SavedRepository) {
            val info = runCatching { discovery.inspect(path) }.getOrNull()
            val saved = repo.copy(remoteUrl = info?.remoteUrl ?: repo.remoteUrl, branch = info?.branch ?: repo.branch)
            if (deletion != null) {
                require(repo.managedStorage) { text(com.bontecou.syncmd.R.string.repository_imported_files_kept) }
                ManagedRepositoryDeletion().delete(deletion.plan, CloneStorage.managedCloneRoots(context))
            }
            finishRemoval(RepositoryHistoryEntry(saved, filesDeleted = deletion != null))
            if (deletion != null) _deletionReview.value = null
        }
        if (deletion != null) {
            withRepositoryDeletionReview(deletion, { _deletionReview.value },
                { _allRepositories.value.firstOrNull { it.path == path } }) { repo, _ -> remove(repo) }
        } else RepositoryOperationCoordinator.withRepository(path) {
            remove(_allRepositories.value.firstOrNull { it.path == path } ?: error(text(com.bontecou.syncmd.R.string.repository_no_longer_added)))
        }
        _operationMessage.value = text(if (deletion != null) com.bontecou.syncmd.R.string.repository_clone_deleted else com.bontecou.syncmd.R.string.repository_removed_files_kept)
    }, destructive = deletion != null)

    /** The visible caller owns cancellation and navigation; persistence never retains a route callback. */
    suspend fun refreshRepositoryMetadata(path: String): Boolean {
        initialLoad.await()
        return try {
            withContext(Dispatchers.IO) {
                persistence.withLock {
                    _isLoading.value = true
                    _errorMessage.value = null
                    try {
                        readState()
                        val info = discovery.inspect(path)
                        val repos = _allRepositories.value.map { repo ->
                            if (canonical(repo.path) == info.path) repo.copy(remoteUrl = info.remoteUrl, branch = info.branch) else repo
                        }
                        require(repos.any { canonical(it.path) == info.path }) { text(com.bontecou.syncmd.R.string.repository_no_longer_added) }
                        persist(repos, _repositoryHistory.value, _selectedRepository.value)
                    } finally { _isLoading.value = false }
                }
            }
            true
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            _errorMessage.value = userMessage(e)
            false
        }
    }

    fun scanLocalRepositories() = mutate({ _discoveryResult.value = discovery.scan(CloneStorage.discoveryRoots(context)) })
    fun scanChosenFolder(uri: android.net.Uri) = mutate({
        val folder = com.bontecou.syncmd.storage.RepositoryLocations(context).rememberFolder(uri)
        recoverInterruptedRemovals()
        _discoveryResult.value = discovery.scan(listOf(folder.path))
        _operationMessage.value = text(com.bontecou.syncmd.R.string.discovery_scanned, folder.name)
    })
    fun reportStorageError(message: String) { _errorMessage.value = message }

    fun prepareMove(path: String, selection: String?, screenOwner: Any? = null) {
        if (_destructiveBusy.value) return
        val owner = moveReviews.begin(screenOwner) ?: return
        _errorMessage.value = null
        mutate({
            if (!moveReviews.isRequestCurrent(owner)) return@mutate
            val original = _allRepositories.value.firstOrNull { it.path == canonical(path) } ?: error(text(com.bontecou.syncmd.R.string.repository_no_longer_added))
            prepareMoveNow(original, selection, null, owner)
        }, onError = { moveReviews.fail(owner, userMessage(it)) })
    }
    fun prepareRecoveryRestore(id: String, selection: String?, screenOwner: Any? = null) {
        if (_destructiveBusy.value) return
        val owner = moveReviews.begin(screenOwner) ?: return
        _errorMessage.value = null
        mutate({
            if (!moveReviews.isRequestCurrent(owner)) return@mutate
            val journal = moves.read(id)
            require(journal.phase == RepositoryMovePhase.COMMITTED && journal.plan.copyRequired) { text(com.bontecou.syncmd.R.string.move_error_finish_pending) }
            require(_allRepositories.value.none { it.path == journal.plan.recovery.path }) { text(com.bontecou.syncmd.R.string.move_error_recovery_added) }
            val previous = moveMetadata(journal).original
            val restored = previous.copy(name = text(com.bontecou.syncmd.R.string.move_recovered_name, previous.name), alias = previous.alias.takeIf { it.isNotBlank() }?.let { text(com.bontecou.syncmd.R.string.move_recovered_name, it) }.orEmpty(),
                path = journal.plan.recovery.path, dateAdded = System.currentTimeMillis(), managedStorage = false)
            prepareMoveNow(restored, selection, id, owner, File(journal.plan.source).name)
        }, onError = { moveReviews.fail(owner, userMessage(it)) })
    }
    private suspend fun prepareMoveNow(original: SavedRepository, selection: String?, restoringId: String?, owner: Any, folderName: String = File(original.path).name) {
        require(moves.journals().none { it.phase !in listOf(RepositoryMovePhase.COMMITTED, RepositoryMovePhase.DISCARDING_RECOVERY) &&
            (it.plan.source == original.path || it.plan.destination == original.path) }) { text(com.bontecou.syncmd.R.string.move_error_finish_pending_repository) }
        val parent = locations.resolve(selection).path
        val plan = moves.prepare(original.path, parent, CloneStorage.moveRecoveryRoot(context, original.path), folderName)
        require(_allRepositories.value.none { it.path == plan.destination }) { text(com.bontecou.syncmd.R.string.move_error_destination_registered) }
        val info = discovery.inspect(original.path)
        credentials.summary(original.path)
        val metadata = com.bontecou.syncmd.storage.RepositoryMoveMetadata(original, info.remoteUrl, info.branch,
            credentials.recordVersion(original.path), credentials.recordVersion(plan.destination),
            if (plan.copyRequired) credentials.recordVersion(plan.recovery.path) else null, restoringId)
        moveReviews.publish(owner, com.bontecou.syncmd.storage.RepositoryMoveReview(plan, metadata))
    }
    fun openMoveReview(owner: Any, routeId: String) { moveReviews.open(owner, routeId) }
    internal suspend fun deliverMoveCompletion(owner: Any, lifecycle: androidx.lifecycle.Lifecycle, onReady: () -> Unit) =
        moveReviews.deliver(owner, lifecycle, onReady)
    fun closeMoveReview(owner: Any) { moveReviews.close(owner) }
    fun clearMoveReview(owner: Any? = null) { moveReviews.clear(owner) }
    fun moveReviewed(review: com.bontecou.syncmd.storage.RepositoryMoveReview, screenOwner: Any? = null, onReady: () -> Unit = {}) {
        if (_destructiveBusy.value || !moveReviews.claim(review, screenOwner)) return
        mutate({
            try {
                com.bontecou.syncmd.storage.requireCurrentMoveReview(review, moveReview.value)
                validateMoveCatalog(review.plan, review.metadata)
                val done = moves.execute(review.plan, gson.toJson(review.metadata), beforeMove = {
                    com.bontecou.syncmd.storage.requireCurrentMoveReview(review, moveReview.value)
                    checkMoveCredentials(review.plan, review.metadata)
                }, commit = ::commitMove)
                finishMove(done)
                _operationMessage.value = text(if (done.plan.copyRequired) com.bontecou.syncmd.R.string.move_done_retained else com.bontecou.syncmd.R.string.move_done)
            } finally {
                reloadMoveJournals()
            }
        }, {
            if (screenOwner == null) onReady() else moveReviews.complete(review)
        }, destructive = true,
            onFinally = { moveReviews.finish(review) })
    }
    fun resumeMove(id: String) = mutate({
        try {
            val journal = moves.read(id)
            if (journal.phase == RepositoryMovePhase.DISCARDING_RECOVERY) moves.discardRecovery(id) { credentials.remove(journal.plan.recovery.path) }
            else if (journal.phase == RepositoryMovePhase.COMMITTED) finishMove(journal)
            else finishMove(moves.resume(id, beforeMove = { checkMoveCredentials(it.plan, moveMetadata(it)) }, commit = ::commitMove))
            _operationMessage.value = text(com.bontecou.syncmd.R.string.move_recovery_done)
        } finally { reloadMoveJournals() }
    }, destructive = true)
    fun cancelPendingMove(id: String) = mutate({
        try { moves.cancel(id); _operationMessage.value = text(com.bontecou.syncmd.R.string.move_cancelled) }
        finally { reloadMoveJournals() }
    }, destructive = true)
    fun discardMoveRecovery(id: String) = mutate({
        val journal = moves.read(id)
        try { moves.discardRecovery(id) { credentials.remove(journal.plan.recovery.path) }; _operationMessage.value = text(com.bontecou.syncmd.R.string.move_recovery_removed) }
        finally { reloadMoveJournals() }
    }, destructive = true)

    private fun moveMetadata(journal: RepositoryMoveJournal): com.bontecou.syncmd.storage.RepositoryMoveMetadata {
        val metadata = gson.fromJson(journal.payload, com.bontecou.syncmd.storage.RepositoryMoveMetadata::class.java)
        require(metadata.version == 1 && metadata.original.path == journal.plan.source) { text(com.bontecou.syncmd.R.string.move_error_settings_review) }
        return metadata
    }
    private fun validateMoveCatalog(plan: RepositoryMovePlan, metadata: com.bontecou.syncmd.storage.RepositoryMoveMetadata) {
        val source = _allRepositories.value.firstOrNull { it.path == plan.source }
        val target = _allRepositories.value.firstOrNull { it.path == plan.destination }
        val expected = movedRepository(plan, metadata)
        if (metadata.restoringRecoveryId == null) require(source == metadata.original || source == null && target == expected) { text(com.bontecou.syncmd.R.string.move_error_settings_changed) }
        else require(source == null && (target == null || target == expected)) { text(com.bontecou.syncmd.R.string.move_error_restored_registered) }
        require(target == null || target == expected) { text(com.bontecou.syncmd.R.string.move_error_destination_other) }
    }
    private fun checkMoveCredentials(plan: RepositoryMovePlan, metadata: com.bontecou.syncmd.storage.RepositoryMoveMetadata) = credentials.checkRelocation(
        plan.source, plan.destination, if (plan.copyRequired) plan.recovery.path else null,
        metadata.sourceCredentialVersion, metadata.destinationCredentialVersion, metadata.recoveryCredentialVersion)
    private fun movedRepository(plan: RepositoryMovePlan, metadata: com.bontecou.syncmd.storage.RepositoryMoveMetadata) = metadata.original.copy(
        path = plan.destination, managedStorage = CloneStorage.isManagedClonePath(context, plan.destination), remoteUrl = metadata.remote, branch = metadata.branch)
    private fun commitMove(journal: RepositoryMoveJournal) {
        val metadata = moveMetadata(journal)
        readState()
        validateMoveCatalog(journal.plan, metadata)
        credentials.relocate(journal.plan.id, journal.plan.source, journal.plan.destination, if (journal.plan.copyRequired) journal.plan.recovery.path else null,
            metadata.sourceCredentialVersion, metadata.destinationCredentialVersion, metadata.recoveryCredentialVersion)
        val moved = movedRepository(journal.plan, metadata)
        val repos = if (metadata.restoringRecoveryId == null) _allRepositories.value.map { if (it.path == journal.plan.source || it.path == moved.path) moved else it }.distinctBy { it.path }
            else (_allRepositories.value.filterNot { it.path == moved.path } + moved)
        val selected = if (_selectedRepository.value == journal.plan.source) moved.path else _selectedRepository.value
        // Even an already-updated in-memory preferences map must commit successfully before acknowledgement.
        persist(repos, _repositoryHistory.value, selected)
    }
    private suspend fun finishMove(journal: RepositoryMoveJournal) {
        val metadata = moveMetadata(journal)
        val shortcutStore = com.bontecou.syncmd.automation.RepositoryShortcutStore(context)
        if (shortcutStore.records().any { it.enabled && it.plan.target.path == journal.plan.source }) {
        val original = metadata.original
        val sourceRegistration = listOf(original.dateAdded, original.remoteUrl, original.branch).joinToString("\u0000") { it?.toString().orEmpty() }
        val destination = com.bontecou.syncmd.automation.PullAllCatalog(context).snapshot().single { it.path == journal.plan.destination }
        shortcutStore.relocate(journal.plan.source, journal.plan.destination,
            journal.plan.sourceKey, sourceRegistration, destination.registration, metadata.sourceCredentialVersion,
            credentials.recordVersion(journal.plan.destination), com.bontecou.syncmd.automation.RepositoryShortcutService.accountBinding(
                journal.plan.destination, com.bontecou.syncmd.services.github.GitHubAuthManager(context), credentials))
        }
        val callbackStore = com.bontecou.syncmd.automation.RepositoryCallbackGrantStore(context)
        if (callbackStore.records().any { it.scope.enabled && it.scope.plan.target.path == journal.plan.source }) {
            val original = metadata.original
            val sourceRegistration = listOf(original.dateAdded, original.remoteUrl, original.branch).joinToString("\u0000") { it?.toString().orEmpty() }
            val destination = com.bontecou.syncmd.automation.PullAllCatalog(context).snapshot().single { it.path == journal.plan.destination }
            callbackStore.relocate(journal.plan.source, journal.plan.destination, journal.plan.sourceKey, sourceRegistration,
                destination.registration, metadata.sourceCredentialVersion, credentials.recordVersion(journal.plan.destination),
                com.bontecou.syncmd.automation.RepositoryShortcutService.accountBinding(journal.plan.destination,
                    com.bontecou.syncmd.services.github.GitHubAuthManager(context), credentials))
        }
        val original = metadata.original
        val sourceRegistration = listOf(original.dateAdded, original.remoteUrl, original.branch).joinToString("\u0000") { it?.toString().orEmpty() }
        com.bontecou.syncmd.obsidian.ObsidianConnections(context,
            com.bontecou.syncmd.services.github.GitHubAuthManager(context), credentials)
            .relocateAfterMove(journal, sourceRegistration, metadata.sourceCredentialVersion)
        metadata.restoringRecoveryId?.let { id -> if (moves.journals().any { it.plan.id == id }) moves.finishConsumedRecovery(id, journal) }
        credentials.finishRelocation(journal.plan.id)
        moves.acknowledge(journal.plan.id)
    }
    private fun reloadMoveJournals() { _moveJournals.value = moves.journals() }
    private suspend fun recoverMoves() {
        val errors = mutableListOf<String>()
        val pending = moves.journals().sortedBy { if (it.phase == RepositoryMovePhase.COMMITTED) 1 else 0 }
        for (journal in pending) {
            if (moves.journals().none { it.plan.id == journal.plan.id }) continue
            try {
                if (journal.phase == RepositoryMovePhase.DISCARDING_RECOVERY) moves.discardRecovery(journal.plan.id) { credentials.remove(journal.plan.recovery.path) }
                else if (journal.phase == RepositoryMovePhase.COMMITTED) finishMove(journal)
                else finishMove(moves.resume(journal.plan.id, beforeMove = { checkMoveCredentials(it.plan, moveMetadata(it)) }, commit = ::commitMove))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { errors += userMessage(e) }
        }
        reloadMoveJournals()
        if (errors.isNotEmpty()) _errorMessage.value = text(com.bontecou.syncmd.R.string.move_recovery_attention, errors.first())
    }
    fun scanDirectory(path: String) = mutate({ _discoveryResult.value = discovery.scan(listOf(path)) })

    fun addDiscoveredRepositories(paths: List<String>) = mutate({
        val infos = paths.distinct().map { discovery.inspect(it) }
        val added = infos.filter { info -> _allRepositories.value.none { canonical(it.path) == info.path } }.map { info ->
            val previous = _repositoryHistory.value.firstOrNull { canonical(it.repository.path) == info.path }
            SavedRepository(previous?.repository?.name ?: info.name, info.path, previous?.repository?.alias.orEmpty(),
                managedStorage = previous?.repository?.managedStorage ?: CloneStorage.isManagedClonePath(context, info.path), remoteUrl = info.remoteUrl, branch = info.branch)
        }
        require(added.isNotEmpty()) { text(com.bontecou.syncmd.R.string.repository_add_none_new) }
        val addedPaths = added.map { it.path }.toSet()
        persist(_allRepositories.value + added, _repositoryHistory.value.filterNot { canonical(it.repository.path) in addedPaths },
            _selectedRepository.value.ifBlank { added.first().path })
        _operationMessage.value = context.resources.getQuantityString(com.bontecou.syncmd.R.plurals.repositories_added, added.size, added.size)
    })

    fun prepareTrackingRemoval(path: String) = mutate({ _trackingPlan.value = tracking.prepare(path) })
    fun clearTrackingPlan() { if (!_isLoading.value) _trackingPlan.value = null }

    fun removeTracking(request: TrackingRemovalRequest) {
        if (_trackingPlan.value !== request.plan) return
        mutate({
            withTrackingRemovalRequest(request, { _trackingPlan.value }) { plan, keepBackup ->
                val repo = _allRepositories.value.firstOrNull { it.path == plan.path } ?: error(text(com.bontecou.syncmd.R.string.repository_no_longer_added))
                val backupRoot = if (keepBackup) CloneStorage.backupRoot(context, plan.path) else null
                val predictedBackup = backupRoot?.let { File(it, "gitsync-backup-${plan.operationId}").path }
                val pending = RepositoryHistoryEntry(repo, trackingRemoved = true, backupDirectory = predictedBackup,
                    backupArchive = predictedBackup?.let { File(it, "git-metadata.zip").path }, expectedMetadataIdentity = plan.metadataIdentity)
                persist(_allRepositories.value, _repositoryHistory.value, _selectedRepository.value, listOf(pending))
                try {
                    val result = tracking.remove(plan, backupRoot)
                    finishRemoval(pending.copy(backupDirectory = result.backupDirectory, backupArchive = result.backupArchive), emptyList())
                    _trackingPlan.value = null
                    _operationMessage.value = result.backupDirectory?.let { text(com.bontecou.syncmd.R.string.tracking_removed_backup, it) }
                        ?: text(com.bontecou.syncmd.R.string.tracking_removed_files_kept)
                } catch (e: Exception) {
                    if ((e is GitTrackingRemovalException && e.detached) || !File(plan.path, ".git").isDirectory) {
                        finishRemoval(pending, emptyList())
                        _trackingPlan.value = null
                    } else persist(_allRepositories.value, _repositoryHistory.value, _selectedRepository.value, emptyList())
                    throw e
                }
            }
        }, destructive = true)
    }

    fun openSampleRepository(onReady: () -> Unit) {
        if (_isOpeningSample.value) return
        _isOpeningSample.value = true
        _sampleError.value = null
        viewModelScope.launch {
            initialLoad.await()
            try {
                withContext(Dispatchers.IO) { persistence.withLock {
                    readState()
                    val sample = SampleRepository.openOrCreate(File(context.filesDir, "sample-workspace"))
                    if (_allRepositories.value.none { canonical(it.path) == sample.canonicalPath }) addNow(text(com.bontecou.syncmd.R.string.vault_l10n_sample_repository_name), sample.path)
                    persist(_allRepositories.value, _repositoryHistory.value, sample.canonicalPath)
                } }
                onReady()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _sampleError.value = text(com.bontecou.syncmd.R.string.vault_l10n_sample_open_failed, e.message.toString())
                _errorMessage.value = _sampleError.value
            } finally { _isOpeningSample.value = false }
        }
    }

    fun updateSettings(settings: AppSettings) = mutate({
        check(prefs.edit().putLong("auto_fetch_interval", settings.autoFetchInterval).putBoolean("is_dark_theme", settings.isDarkTheme)
            .putBoolean("show_debug_info", settings.showDebugInfo).commit()) { text(com.bontecou.syncmd.R.string.vault_l10n_settings_save_failed) }
        _appSettings.value = settings.copy(selectedRepository = _selectedRepository.value)
    })
    fun reportError(message: String) { _errorMessage.value = message }
    fun clearError() { _errorMessage.value = null }
    fun clearOperationMessage() { _operationMessage.value = null }
    private fun canonical(path: String) = if (path.startsWith("github://")) path else runCatching { File(path).canonicalPath }.getOrDefault(path)
}

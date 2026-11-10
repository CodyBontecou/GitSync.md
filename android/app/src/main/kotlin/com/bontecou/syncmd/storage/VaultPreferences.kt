package com.bontecou.syncmd.storage

import com.bontecou.syncmd.ui.util.UiTextFailure
import com.bontecou.syncmd.R
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.bontecou.syncmd.background.BackgroundSyncBackend
import com.bontecou.syncmd.background.BackgroundSyncJobs
import com.bontecou.syncmd.services.automation.RepositoryActionPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-vault presentation preferences. The repository id is the vault's canonical
 * folder path ([com.bontecou.syncmd.ui.viewmodels.SavedRepository.path]), the same
 * key Background Sync uses for its policies.
 */
data class VaultPreference(
    /** Shown in the Home "Pinned" card and sorted first. */
    val pinned: Boolean = false,
    /** User-chosen name; null falls back to the saved alias, then the repository name. */
    val displayName: String? = null,
    /** Mirrors Background Sync's own per-repository policy; never stored twice. */
    val includeInBackgroundSync: Boolean = false,
) {
    /** The name to show: [displayName], else a non-blank alias, else the repository name. */
    fun resolvedName(alias: String, name: String): String = displayName ?: alias.ifBlank { name }
}

/** Key-value persistence behind [VaultPreferences]; a null value removes the key. */
interface VaultPreferenceStorage {
    fun all(): Map<String, *>
    fun edit(changes: Map<String, Any?>): Boolean
}

/** Background Sync's per-repository inclusion, as [VaultPreferences] reads and changes it. */
interface VaultBackgroundInclusion {
    /** Emits whenever Background Sync settings change (the current value first). */
    val changes: Flow<*>
    /** Repository ids currently included; empty when the settings can't be read. */
    fun included(): Set<String>
    suspend fun review(repositoryId: String): RepositoryActionPlan
    suspend fun include(plan: RepositoryActionPlan, wifiOnly: Boolean?, externalPowerOnly: Boolean?)
    suspend fun exclude(repositoryId: String)
}

@Singleton
class VaultPreferences(
    private val storage: VaultPreferenceStorage,
    private val background: VaultBackgroundInclusion,
) {
    @Inject constructor(@ApplicationContext context: Context, backend: BackgroundSyncBackend) :
        this(SharedPreferencesStorage(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)), BackendInclusion(context, backend))

    private val local = MutableStateFlow(decode(storage.all()))
    private val included: Flow<Set<String>> = background.changes
        .map { runCatching { background.included() }.getOrDefault(emptySet()) }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

    /** Every vault with a stored preference or a Background Sync inclusion, keyed by repository id. */
    val all: Flow<Map<String, VaultPreference>> = combine(local, included) { stored, inBackground ->
        (stored.keys + inBackground).associateWith { id ->
            (stored[id] ?: VaultPreference()).copy(includeInBackgroundSync = id in inBackground)
        }
    }.distinctUntilChanged()

    /** One vault's preference; defaults when nothing is stored. */
    fun preference(repositoryId: String): Flow<VaultPreference> =
        all.map { it[repositoryId] ?: VaultPreference() }.distinctUntilChanged()

    /** Repository ids pinned to the top of Home. */
    val pinned: Flow<Set<String>> = local.map { stored -> stored.filterValues { it.pinned }.keys }.distinctUntilChanged()

    /** Synchronous snapshot of the stored part (pinned, display name); inclusion is read from disk. */
    fun current(repositoryId: String): VaultPreference = (local.value[repositoryId] ?: VaultPreference())
        .copy(includeInBackgroundSync = repositoryId in runCatching { background.included() }.getOrDefault(emptySet()))

    fun setPinned(repositoryId: String, pinned: Boolean) =
        write(mapOf(pinnedKey(repositoryId) to if (pinned) true else null))

    /** Blank or null clears the override, so the vault shows its alias or repository name again. */
    fun setDisplayName(repositoryId: String, displayName: String?) =
        write(mapOf(nameKey(repositoryId) to displayName?.trim()?.takeIf { it.isNotEmpty() }))

    /**
     * Step 1 of including a vault: Background Sync reviews the branch and remotes it would use
     * ("Only branch main, tracking origin/main"). Show the plan, then call [includeInBackgroundSync].
     */
    suspend fun reviewBackgroundSync(repositoryId: String): RepositoryActionPlan = background.review(repositoryId)

    /** Step 2: include the reviewed vault. Null conditions keep the vault's previous Wi-Fi/charging choice. */
    suspend fun includeInBackgroundSync(plan: RepositoryActionPlan, wifiOnly: Boolean? = null, externalPowerOnly: Boolean? = null) =
        background.include(plan, wifiOnly, externalPowerOnly)

    suspend fun excludeFromBackgroundSync(repositoryId: String) = background.exclude(repositoryId)

    /** Carries pinned and display name to a vault's new folder after a move. */
    fun move(fromId: String, toId: String) {
        if (fromId == toId) return
        val old = local.value[fromId] ?: return
        val changes = mapOf(
            pinnedKey(fromId) to null, nameKey(fromId) to null,
            pinnedKey(toId) to if (old.pinned) true else null, nameKey(toId) to old.displayName,
        )
        write(changes)
    }

    /** Drops stored preferences when a vault is removed (Background Sync keeps its own policy). */
    fun forget(repositoryId: String) = write(mapOf(pinnedKey(repositoryId) to null, nameKey(repositoryId) to null))

    private fun write(changes: Map<String, Any?>) {
        if (!storage.edit(changes)) throw UiTextFailure(R.string.l10n_save_vault_preferences)
        local.value = decode(storage.all())
    }

    companion object {
        const val PREFERENCES = "vault_preferences"
        private const val PINNED = "pinned:"
        private const val NAME = "name:"
        internal fun pinnedKey(id: String) = PINNED + id
        internal fun nameKey(id: String) = NAME + id

        internal fun decode(values: Map<String, *>): Map<String, VaultPreference> {
            val result = mutableMapOf<String, VaultPreference>()
            values.forEach { (key, value) ->
                when {
                    key.startsWith(PINNED) && value == true -> key.removePrefix(PINNED).let { id ->
                        result[id] = (result[id] ?: VaultPreference()).copy(pinned = true)
                    }
                    key.startsWith(NAME) && value is String && value.isNotBlank() -> key.removePrefix(NAME).let { id ->
                        result[id] = (result[id] ?: VaultPreference()).copy(displayName = value)
                    }
                }
            }
            return result
        }
    }

    private class SharedPreferencesStorage(private val prefs: SharedPreferences) : VaultPreferenceStorage {
        override fun all(): Map<String, *> = prefs.all
        override fun edit(changes: Map<String, Any?>): Boolean = prefs.edit().apply {
            changes.forEach { (key, value) ->
                when (value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    else -> throw UiTextFailure(R.string.l10n_unsupported_vault_preference)
                }
            }
        }.commit()
    }

    /** Wraps [BackgroundSyncBackend]: inclusion lives only in its policy store. */
    private class BackendInclusion(private val context: Context, private val backend: BackgroundSyncBackend) : VaultBackgroundInclusion {
        override val changes: Flow<*> get() = backend.store.changes
        override fun included(): Set<String> = backend.store.read().policies.values.filter { it.included }.map { it.target.path }.toSet()
        override suspend fun review(repositoryId: String): RepositoryActionPlan = withContext(Dispatchers.IO) {
            val target = requireNotNull(backend.targets().singleOrNull { it.path == repositoryId }) {
                "This vault can't join Background Sync yet. Reconnect its folder first."
            }
            backend.review(target)
        }
        override suspend fun include(plan: RepositoryActionPlan, wifiOnly: Boolean?, externalPowerOnly: Boolean?) = withContext(Dispatchers.IO) {
            val configuration = backend.store.read()
            val previous = configuration.policies[plan.target.path]
            backend.include(plan, configuration.generation, wifiOnly ?: previous?.wifiOnly ?: false,
                externalPowerOnly ?: previous?.externalPowerOnly ?: false)
            BackgroundSyncJobs.preferencesChanged(context)
        }
        override suspend fun exclude(repositoryId: String) = withContext(Dispatchers.IO) {
            val configuration = backend.store.read()
            if (configuration.policies[repositoryId]?.included == true) {
                backend.exclude(repositoryId, configuration.generation)
                BackgroundSyncJobs.preferencesChanged(context)
            }
        }
    }
}

/** Compose helper: one vault's preference as State (Home pin, Vault settings, Background Sync row). */
@Composable
fun VaultPreferences.collectPreference(repositoryId: String): State<VaultPreference> {
    val flow = remember(this, repositoryId) { preference(repositoryId) }
    return flow.collectAsState(initial = remember(this, repositoryId) { current(repositoryId) })
}

/** Compose helper: every vault's preference as State. */
@Composable
fun VaultPreferences.collectAll(): State<Map<String, VaultPreference>> = all.collectAsState(initial = emptyMap())

/** A no-op inclusion source for previews and tests. */
object NoBackgroundInclusion : VaultBackgroundInclusion {
    override val changes: Flow<*> = flowOf(Unit)
    override fun included(): Set<String> = emptySet()
    override suspend fun review(repositoryId: String): RepositoryActionPlan = throw UiTextFailure(R.string.l10n_background_unavailable)
    override suspend fun include(plan: RepositoryActionPlan, wifiOnly: Boolean?, externalPowerOnly: Boolean?) = Unit
    override suspend fun exclude(repositoryId: String) = Unit
}

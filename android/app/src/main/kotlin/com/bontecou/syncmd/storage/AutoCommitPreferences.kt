package com.bontecou.syncmd.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where [AutoCommitPreferences] keeps its set of vault ids. */
interface AutoCommitStore {
    fun read(): Set<String>
    fun write(ids: Set<String>)
}

/**
 * Per-vault "Auto-commit when I pause for 2 min" (board FoldSplit). The id is the vault's
 * SavedRepository.path, as in [VaultPreferences]. Off unless the user turns it on.
 */
@Singleton
class AutoCommitPreferences(private val store: AutoCommitStore) {
    @Inject constructor(@ApplicationContext context: Context) : this(SharedPreferencesAutoCommitStore(context))

    private val _enabled = MutableStateFlow(runCatching { store.read() }.getOrDefault(emptySet()))
    /** Vault ids with auto-commit on. */
    val enabled: StateFlow<Set<String>> = _enabled.asStateFlow()

    fun isEnabled(id: String): Boolean = id in _enabled.value

    fun setEnabled(id: String, on: Boolean) {
        val next = if (on) _enabled.value + id else _enabled.value - id
        if (next == _enabled.value) return
        store.write(next)
        _enabled.value = next
    }

    /** A vault was removed from the app. */
    fun forget(id: String) = setEnabled(id, false)
}

private class SharedPreferencesAutoCommitStore(context: Context) : AutoCommitStore {
    private val prefs = context.applicationContext.getSharedPreferences("auto_commit", Context.MODE_PRIVATE)
    override fun read(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()
    override fun write(ids: Set<String>) { prefs.edit().putStringSet(KEY, ids.toSet()).apply() }
    private companion object { const val KEY = "vaults" }
}

package com.bontecou.syncmd.ui.util

import androidx.lifecycle.SavedStateHandle
import com.bontecou.syncmd.ui.viewmodels.RepositoryHistoryEntry
import com.google.gson.Gson

/** Keep the reviewed route alive when its own successful registration consumes the history row.
 * The registration service still checks the current catalog/history before committing.
 */
fun reviewedHistoryClone(saved: SavedStateHandle, sourcePath: String, history: List<RepositoryHistoryEntry>): RepositoryHistoryEntry? {
    val key = "reviewed_clone_history"
    val raw = saved.get<String>(key)
    if (raw != null) return runCatching {
        require(raw.length <= 65536)
        Gson().fromJson(raw, RepositoryHistoryEntry::class.java).also { require(it.repository.path == sourcePath) }
    }.getOrNull()
    return history.singleOrNull { it.repository.path == sourcePath }?.also { saved[key] = Gson().toJson(it) }
}

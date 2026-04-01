package com.bontecou.syncmd.billing

import android.content.SharedPreferences
import org.json.JSONArray

/**
 * [RepoTrackerStorage] backed by [SharedPreferences].
 *
 * The preferences file is included in Android Auto Backup, so the seen-repo set
 * survives app deletion + reinstall on the same Google account — preventing
 * free-tier bypass via uninstall/reinstall or delete/re-clone.
 */
class SharedPreferencesRepoTrackerStorage(
    private val prefs: SharedPreferences,
    private val key: String,
) : RepoTrackerStorage {

    override fun load(): Set<String> {
        val json = prefs.getString(key, null) ?: return emptySet()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapTo(HashSet()) { array.getString(it) }
        } catch (_: Exception) {
            emptySet()
        }
    }

    override fun save(ids: Set<String>) {
        prefs.edit()
            .putString(key, JSONArray(ids.toList()).toString())
            .apply()
    }
}

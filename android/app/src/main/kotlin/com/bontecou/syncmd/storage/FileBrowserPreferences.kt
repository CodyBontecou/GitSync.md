package com.bontecou.syncmd.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide "show hidden files" choice for the file browser (issue #65). Each folder is its own
 * navigation entry with its own view model, so the toggle must live here to carry into
 * `.obsidian/` and survive app restarts.
 */
object FileBrowserPreferences {
    private const val PREFS = "file_browser"
    private const val KEY_SHOW_HIDDEN = "show_hidden"
    private val showHidden = MutableStateFlow<Boolean?>(null)

    fun showHidden(context: Context): StateFlow<Boolean?> {
        if (showHidden.value == null) {
            showHidden.value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_HIDDEN, false)
        }
        return showHidden.asStateFlow()
    }

    fun setShowHidden(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()
        showHidden.value = value
    }
}

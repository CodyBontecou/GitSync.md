package com.bontecou.syncmd.ui.theme

import com.bontecou.syncmd.R
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings › Theme: follow the system, or force light or dark. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Persisted theme choice. The old "Dark theme" switch stored `is_dark_theme` in the app
 * preferences; on first read a stored `true` becomes [ThemeMode.DARK] and a stored `false`
 * [ThemeMode.LIGHT], so nobody's appearance changes after the update. No stored value means
 * [ThemeMode.SYSTEM].
 */
object AppAppearance {
    private const val PREFS = "appearance"
    private const val KEY = "theme_mode"
    private const val LEGACY_PREFS = "sync_md_prefs"
    private const val LEGACY_KEY = "is_dark_theme"
    private val mode = MutableStateFlow<ThemeMode?>(null)

    fun mode(context: Context): StateFlow<ThemeMode?> {
        if (mode.value == null) mode.value = read(context.applicationContext)
        return mode.asStateFlow()
    }

    fun current(context: Context): ThemeMode = mode(context).value ?: ThemeMode.SYSTEM

    fun set(context: Context, value: ThemeMode) {
        val app = context.applicationContext
        check(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.name).commit()) { app.getString(R.string.l10n_save_theme) }
        mode.value = value
    }

    private fun read(context: Context): ThemeMode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { stored -> runCatching { ThemeMode.valueOf(stored) }.getOrNull()?.let { return it } }
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val migrated = when {
            !legacy.contains(LEGACY_KEY) -> ThemeMode.SYSTEM
            legacy.getBoolean(LEGACY_KEY, false) -> ThemeMode.DARK
            else -> ThemeMode.LIGHT
        }
        prefs.edit().putString(KEY, migrated.name).apply()
        return migrated
    }
}

/** Whether the app should draw dark right now: the saved [ThemeMode], resolved against the system setting. */
@Composable
fun rememberAppDarkTheme(context: Context): Boolean {
    val mode by AppAppearance.mode(context).collectAsState()
    val system = isSystemInDarkTheme()
    return when (mode ?: ThemeMode.SYSTEM) {
        ThemeMode.SYSTEM -> system
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

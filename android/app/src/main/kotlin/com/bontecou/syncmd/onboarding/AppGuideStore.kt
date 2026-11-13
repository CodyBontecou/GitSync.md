package com.bontecou.syncmd.onboarding

import android.content.Context
import android.content.SharedPreferences
import com.bontecou.syncmd.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class GuideTarget { HOME, LOGIN, URL, SAMPLE, STORAGE }
data class GuideProgress(val completed: Boolean = false, val page: Int = 0, val pending: GuideTarget? = null, val showNotes: Boolean = false)

/** Tour preferences contain no account data; first adoption preserves established installations. */
@Singleton
class AppGuideStore(private val prefs: SharedPreferences, private val currentRevision: Int, private val hasExistingData: () -> Boolean) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences("app_guide", Context.MODE_PRIVATE), BuildConfig.VERSION_CODE,
        { listOf("sync_md_prefs", "github_session", "repository_credentials", "repository_locations").any {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).all.isNotEmpty()
        } })
    @Synchronized fun load(): GuideProgress {
        if (!prefs.contains("initialized")) save(prefs.edit().putBoolean("initialized", true).putBoolean("completed", hasExistingData())
            .putInt("page", 0).putInt("notes_seen", currentRevision))
        return read()
    }
    @Synchronized fun page(value: Int): GuideProgress {
        require(value in 0..3)
        check(!load().completed)
        save(prefs.edit().putInt("page", value)); return read()
    }
    @Synchronized fun complete(target: GuideTarget): GuideProgress {
        save(prefs.edit().putBoolean("initialized", true).putBoolean("completed", true).putInt("page", 0)
            .putInt("notes_seen", currentRevision).putString("pending", target.name))
        return read()
    }
    @Synchronized fun consumeTarget(): GuideProgress { save(prefs.edit().remove("pending")); return read() }
    @Synchronized fun notesSeen(): GuideProgress { save(prefs.edit().putInt("notes_seen", currentRevision)); return read() }
    private fun read() = GuideProgress(prefs.getBoolean("completed", false), prefs.getInt("page", 0).coerceIn(0, 3),
        prefs.getString("pending", null)?.let { runCatching { GuideTarget.valueOf(it) }.getOrNull() },
        prefs.getBoolean("completed", false) && prefs.getInt("notes_seen", currentRevision) < currentRevision)
    private fun save(editor: SharedPreferences.Editor) { check(editor.commit()) { "Could not save app guide progress" } }
}

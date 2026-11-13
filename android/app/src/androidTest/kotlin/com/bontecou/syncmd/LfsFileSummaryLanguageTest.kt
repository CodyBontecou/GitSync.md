package com.bontecou.syncmd

import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.git.lfs.LfsFileInfo
import com.bontecou.syncmd.services.git.lfs.LfsPointer
import com.bontecou.syncmd.ui.screens.LfsFileSummary
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Component-local recomposition only; does not change system language, app preferences or files. */
class LfsFileSummaryLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private data class Frame(val language: String, val state: Int, val cached: Boolean)
    private val sizes = listOf(0L, 1_234_567L, Long.MAX_VALUE)
    private val states = listOf("lfs_file_pointer", "lfs_file_available", "lfs_file_local_edits")

    private fun fits(tag: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("Missing layout: $tag", results.isNotEmpty())
        assertTrue("Overflow: $tag", results.none { it.hasVisualOverflow })
    }

    @Test fun availabilityCacheAndNativeSizesFollowAllLocalesWhenTheSameComponentRecomposesAtLargeText() {
        val expected = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("localization_expected.json")
            .bufferedReader().use { it.readText() }).getJSONObject("locales")
        val languages = expected.keys().asSequence().toList().sorted()
        assertEquals(25, languages.size)
        fun text(language: String, key: String): String {
            val rows = expected.getJSONArray(language)
            return (0 until rows.length()).map { rows.getJSONObject(it) }
                .single { it.getString("name") == key }.getString("source")
        }
        val frame = mutableStateOf(Frame(languages.first(), 0, false))
        ActivityScenario.launch<ComponentActivity>(Intent(app, ComponentActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val current = frame.value
                val locale = Locale.forLanguageTag(current.language)
                val configuration = Configuration(app.resources.configuration).apply {
                    setLocales(LocaleList(locale)); setLayoutDirection(locale)
                }
                val localized = app.createConfigurationContext(configuration)
                val density = LocalDensity.current
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalLayoutDirection provides if (current.language in listOf("ar", "he")) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(density.density, 1.6f)) {
                    SyncMdTheme(darkTheme = current.language in listOf("ar", "he", "ja")) {
                        Column(Modifier.width(240.dp)) {
                            LfsFileSummary(LfsFileInfo("notes_日本語.pdf", LfsPointer("a".repeat(64), sizes[current.state]),
                                hydrated = current.state == 1, cached = current.cached, modified = current.state == 2, lockable = false))
                        }
                    }
                }
            } }
            for (language in languages) for (state in states.indices) for (cached in listOf(false, true)) {
                compose.runOnIdle { frame.value = Frame(language, state, cached) }
                compose.waitForIdle()
                compose.onNodeWithTag("lfs-file-state").assertTextEquals(text(language, states[state]))
                val configuration = Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(language)) }
                val size = Formatter.formatFileSize(app.createConfigurationContext(configuration), sizes[state])
                val cache = text(language, if (cached) "lfs_cache_verified" else "lfs_cache_missing")
                compose.onNodeWithTag("lfs-file-storage").assertTextEquals("$size · $cache")
                compose.onNodeWithText("notes_日本語.pdf").assertExists()
                fits("lfs-file-state"); fits("lfs-file-storage")
            }
        }
    }
}

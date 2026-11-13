package com.bontecou.syncmd

import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
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
import com.bontecou.syncmd.ui.screens.LfsOperationNotice
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.LfsNotice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Recompose retained results; no LFS actions, account access or actual language-setting changes. */
class LfsOperationNoticeLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private data class Frame(val language: String, val index: Int)

    @Test fun retainedCompletionResultsFollowAllLocalesWithPluralCountsAtLargeText() {
        val expected = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("localization_expected.json")
            .bufferedReader().use { it.readText() })
        val locales = expected.getJSONObject("locales")
        val languages = locales.keys().asSequence().toList().sorted()
        assertEquals(25, languages.size)
        fun text(language: String, key: String): String {
            val rows = locales.getJSONArray(language)
            return (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("name") == key }.getString("source")
        }
        fun quantity(language: String, name: String, value: Int): String {
            val locale = Locale.forLanguageTag(language)
            val forms = expected.getJSONObject("plurals").getJSONObject("locales").getJSONObject(language).getJSONObject(name)
            val category = android.icu.text.PluralRules.forLocale(locale).select(value.toDouble())
            assertTrue("Missing $language / $name / $category", forms.has(category))
            return String.format(locale, forms.getString(category), value)
        }
        val quantities = listOf(0, 1, 2, 3, 11, 21, 1_000_000)
        val notices = listOf(LfsNotice.TrackingUpdated, LfsNotice.ExtensionsUpdated, LfsNotice.LockListRefreshed(true), LfsNotice.LockListRefreshed(false)) +
            quantities.mapIndexed { index, files -> LfsNotice.Hydrated(files, quantities[(index + 1) % quantities.size]) } +
            LfsNotice.Plain("Generated legacy notice retained verbatim")
        assertEquals(12, notices.size)
        val frame = mutableStateOf(Frame(languages.first(), 0))
        ActivityScenario.launch<ComponentActivity>(Intent(app, ComponentActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val current = frame.value
                val configuration = Configuration(app.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(current.language)); setLayoutDirection(Locale.forLanguageTag(current.language))
                }
                val localized = app.createConfigurationContext(configuration)
                val density = LocalDensity.current
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalLayoutDirection provides if (current.language in listOf("ar", "he")) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(density.density, 1.6f)) {
                    SyncMdTheme(darkTheme = current.language in listOf("ar", "he", "ja")) {
                        Column(Modifier.width(240.dp)) { LfsOperationNotice(notices[current.index]) }
                    }
                }
            } }
            // The very same result object stays in place while every locale is visited.
            for ((index, notice) in notices.withIndex()) for (language in languages) {
                compose.runOnIdle { frame.value = Frame(language, index) }
                val rendered = when (notice) {
                    LfsNotice.TrackingUpdated -> text(language, "lfs_tracking_updated")
                    LfsNotice.ExtensionsUpdated -> text(language, "lfs_extensions_updated")
                    is LfsNotice.Hydrated -> listOf(quantity(language, "lfs_restored_files", notice.files),
                        quantity(language, "lfs_downloaded_objects", notice.objects), text(language, "lfs_local_edits_kept")).joinToString(" ")
                    is LfsNotice.LockListRefreshed -> text(language, if (notice.supported) "lfs_locks_refreshed" else "lfs_locks_unsupported")
                    is LfsNotice.Plain -> notice.text
                }
                compose.onNodeWithTag("lfs-notice").assertTextEquals(rendered).assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag("lfs-notice").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Missing layout: $language / $index", layouts.isNotEmpty())
                assertTrue("Overflow: $language / $index", layouts.none { it.hasVisualOverflow })
            }
        }
    }
}

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
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.ui.screens.RepositoryDiscoverySummary
import com.bontecou.syncmd.ui.screens.RepositoryHistorySummary
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Retained metadata only; does not scan, register, reconnect, delete or change language settings. */
class RepositoryLifecycleSummaryLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private data class Frame(val language: String, val variant: Int)
    private fun fits(tag: String, ltr: Boolean = false) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("No layout: $tag", layouts.isNotEmpty())
        assertTrue("Overflow: $tag", layouts.none { it.hasVisualOverflow })
        if (ltr) assertTrue(layouts.all { it.layoutInput.style.textDirection == TextDirection.Ltr })
    }
    @Test fun discoveryAndHistoryMetadataFollowLocaleWithoutReloadingOrChangingTheData() {
        val expected = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("localization_expected.json")
            .bufferedReader().use { it.readText() }).getJSONObject("locales")
        val languages = expected.keys().asSequence().toList().sorted(); assertEquals(25, languages.size)
        fun text(language: String, name: String): String {
            val rows = expected.getJSONArray(language)
            return (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("name") == name }.getString("source")
        }
        val branch = "feature/long-reviewed-branch-日本語"
        val frame = mutableStateOf(Frame(languages.first(), 0))
        ActivityScenario.launch<ComponentActivity>(Intent(app, ComponentActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val current = frame.value
                val configuration = Configuration(app.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(current.language)); setLayoutDirection(Locale.forLanguageTag(current.language))
                }
                val localized = app.createConfigurationContext(configuration); val density = LocalDensity.current
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalLayoutDirection provides if (current.language in listOf("ar", "he")) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(density.density, 1.6f)) {
                    SyncMdTheme(darkTheme = current.language in listOf("ar", "he", "ja")) {
                        Column(Modifier.width(240.dp)) {
                            if (current.variant < 4) RepositoryDiscoverySummary(if (current.variant < 2) branch else null, current.variant % 2 == 1)
                            else RepositoryHistorySummary(trackingRemoved = current.variant >= 6, filesDeleted = current.variant % 2 == 1)
                        }
                    }
                }
            } }
            for (variant in 0..7) for (language in languages) {
                compose.runOnIdle { frame.value = Frame(language, variant) }
                if (variant < 4) {
                    compose.onNodeWithTag("repository-discovery-branch").assertTextEquals(if (variant < 2) branch else text(language, "repository_discovery_branch_unknown"))
                    compose.onNodeWithTag("repository-discovery-status").assertTextEquals(text(language, if (variant % 2 == 1) "repository_discovery_dirty" else "repository_discovery_clean"))
                    fits("repository-discovery-branch", variant < 2); fits("repository-discovery-status")
                    compose.onNodeWithTag("repository-history-status").assertDoesNotExist()
                } else {
                    val key = when (variant) {
                        4 -> "repository_history_files_kept"
                        5 -> "repository_history_files_deleted"
                        else -> "repository_history_tracking_removed"
                    }
                    compose.onNodeWithTag("repository-history-status").assertTextEquals(text(language, key)); fits("repository-history-status")
                    compose.onNodeWithTag("repository-discovery-status").assertDoesNotExist()
                }
            }
        }
    }
}

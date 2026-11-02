package com.bontecou.syncmd

import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import com.bontecou.syncmd.ui.screens.LfsTrackingConfirmation
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated dialog callbacks only: no settings, Git writes, network or app-language changes. */
class LfsTrackingConfirmationLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private data class Frame(val language: String, val lfs: Boolean, val count: Int, val enabled: Boolean = false, val visible: Boolean = true)
    private fun fits(tag: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing layout: $tag", layouts.isNotEmpty())
        assertTrue("Overflow: $tag", layouts.none { it.hasVisualOverflow })
    }
    @Test fun bothChoicesRecomposeAcrossAllLocalesWithPluralCountsFullPathsAndStaleReviewGating() {
        val expected = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("localization_expected.json")
            .bufferedReader().use { it.readText() }).getJSONObject("locales")
        val languages = expected.keys().asSequence().toList().sorted()
        assertEquals(25, languages.size)
        fun text(language: String, name: String): String {
            val rows = expected.getJSONArray(language)
            return (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("name") == name }.getString("source")
        }
        fun paths(count: Int) = (1..count).map { "section-${it.toString().padStart(2, '0')}/${"folder-with-a-long-name/".repeat(3)}notes_日本語 [draft].pdf" }.toSet()
        val frame = mutableStateOf(Frame(languages.first(), true, 1))
        var confirmed = 0; var cancelled = 0
        ActivityScenario.launch<ComponentActivity>(Intent(app, ComponentActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val current = frame.value
                val configuration = Configuration(app.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(current.language))
                    setLayoutDirection(java.util.Locale.forLanguageTag(current.language))
                }
                val localized = app.createConfigurationContext(configuration)
                val density = LocalDensity.current
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalLayoutDirection provides if (current.language in listOf("ar", "he")) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(density.density, 1.6f)) {
                    SyncMdTheme(darkTheme = current.language in listOf("ar", "he", "ja")) {
                        if (current.visible) LfsTrackingConfirmation(paths(current.count), current.lfs, current.enabled,
                            onCancel = { cancelled++; frame.value = frame.value.copy(visible = false) },
                            onConfirm = { confirmed++; frame.value = frame.value.copy(visible = false) })
                    }
                }
            } }
            for (language in languages) for (lfs in listOf(true, false)) for (count in listOf(1, 2, 35)) {
                compose.runOnIdle { frame.value = Frame(language, lfs, count) }
                val mode = if (lfs) "lfs" else "git"
                compose.onNodeWithTag("lfs-tracking-title").assertTextEquals(text(language, "lfs_tracking_${mode}_title")); fits("lfs-tracking-title")
                compose.onNodeWithTag("lfs-tracking-review").performScrollToNode(hasTestTag("lfs-tracking-count"))
                val config = Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(language)) }
                val localized = app.createConfigurationContext(config)
                compose.onNodeWithTag("lfs-tracking-count").assertTextEquals(localized.resources.getQuantityString(R.plurals.lfs_tracking_files, count, count))
                compose.onNodeWithTag("lfs-tracking-scope").assertTextEquals(text(language, "lfs_tracking_${mode}_body"))
                val last = paths(count).sorted().last()
                compose.onNodeWithTag("lfs-tracking-review").performScrollToNode(hasTestTag("lfs-tracking-path:$last"))
                compose.onNodeWithTag("lfs-tracking-path:$last").assertTextEquals(last).assertIsDisplayed(); fits("lfs-tracking-path:$last")
                compose.onNodeWithTag("lfs-confirm-tracking").assertTextEquals(text(language, "ui_apply_tracking_choice_47a2e187"))
                    .assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
                compose.onNodeWithTag("lfs-cancel-tracking").assertTextEquals(text(language, "publication_cancel"))
                val before = confirmed
                compose.onNodeWithTag("lfs-confirm-tracking").performClick()
                compose.runOnIdle { assertEquals(before, confirmed); frame.value = frame.value.copy(enabled = true) }
                compose.onNodeWithTag("lfs-confirm-tracking").assertIsEnabled()
                if (count == 35) {
                    compose.onNodeWithTag("lfs-cancel-tracking").assertHeightIsAtLeast(48.dp).performClick()
                    compose.runOnIdle { assertEquals(before, confirmed) }
                } else {
                    compose.onNodeWithTag("lfs-confirm-tracking").performClick()
                    compose.runOnIdle { assertEquals(before + 1, confirmed) }
                }
                compose.onNodeWithTag("lfs-tracking-review").assertDoesNotExist()
            }
            compose.runOnIdle { assertEquals(100, confirmed); assertEquals(50, cancelled) }
        }
    }
}

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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.git.lfs.*
import com.bontecou.syncmd.ui.screens.LfsLockConfirmation
import com.bontecou.syncmd.ui.screens.LockConfirmation
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Isolated callbacks, no server requests, Git writes or actual app/system language changes. */
class LfsLockConsentLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private data class Frame(val language: String, val index: Int, val valid: Boolean = true, val busy: Boolean = false, val showing: Boolean = true)
    private fun layout(tag: String, ltr: Boolean = false) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("No layout: $tag", results.isNotEmpty())
        assertTrue("Overflow: $tag", results.none { it.hasVisualOverflow })
        if (ltr) assertTrue("Identifier direction: $tag", results.all { it.layoutInput.style.textDirection == TextDirection.Ltr })
    }
    @Test fun createReleaseAndForceConsentStayLocalizedAndGatedAcrossAllLocales() {
        val expected = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("localization_expected.json")
            .bufferedReader().use { it.readText() }).getJSONObject("locales")
        val languages = expected.keys().asSequence().toList().sorted(); assertEquals(25, languages.size)
        fun text(language: String, name: String): String {
            val rows = expected.getJSONArray(language)
            return (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("name") == name }.getString("source")
        }
        val path = "docs/${"long-reviewed-folder/".repeat(3)}guide_日本語 [draft].pdf"
        val review = LfsLockReview(LfsLockContext("/generated/lock-consent", "refs/heads/review_日本語", "a".repeat(40),
            "https://example.invalid/repo.git", "generated"), "https://lfs.example.invalid/team/notes/info/lfs",
            emptyList(), emptyList(), emptyList(), true, true, listOf(path))
        val choices = listOf(LockConfirmation(review, path)) + listOf(false, true).flatMap { force ->
            listOf("reviewer_日本語_${"long-owner-".repeat(3)}", null).mapIndexed { index, owner ->
                LockConfirmation(review, path, LfsLock("generated-$force-$index", path, owner, null), force)
            }
        }
        assertEquals(5, choices.size)
        val frame = mutableStateOf(Frame(languages.first(), 0))
        var confirmed = 0; var cancelled = 0
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
                        if (current.showing) LfsLockConfirmation(choices[current.index], current.valid, current.busy,
                            onCancel = { cancelled++; frame.value = frame.value.copy(showing = false) },
                            onConfirm = { confirmed++; frame.value = frame.value.copy(showing = false) })
                    }
                }
            } }
            for (language in languages) for ((index, choice) in choices.withIndex()) {
                compose.runOnIdle { frame.value = Frame(language, index) }
                val mode = if (choice.force) "force" else if (choice.lock != null) "release" else "create"
                compose.onNodeWithTag("lfs-lock-title").assertTextEquals(text(language, "lfs_lock_${mode}_title")); layout("lfs-lock-title")
                for ((tag, value) in listOf("lfs-lock-path" to path, "lfs-lock-branch" to review.context.branch, "lfs-lock-endpoint" to review.endpoint)) {
                    compose.onNodeWithTag(tag).performScrollTo().assertTextEquals(value).assertIsDisplayed(); layout(tag, true)
                }
                if (choice.lock != null) {
                    val owner = choice.lock.owner ?: text(language, "lfs_lock_owner_missing")
                    compose.onNodeWithTag("lfs-lock-owner").performScrollTo()
                        .assertTextEquals(String.format(Locale.forLanguageTag(language), text(language, "ui_owner_1_s_599ca9ce"), owner))
                    layout("lfs-lock-owner")
                }
                compose.onNodeWithTag("lfs-lock-confirm").assertTextEquals(text(language, "lfs_lock_${mode}_action")).assertHeightIsAtLeast(48.dp)
                compose.onNodeWithTag("lfs-lock-cancel").assertTextEquals(text(language, "publication_cancel")).assertHeightIsAtLeast(48.dp)
                if (choice.force) {
                    compose.onNodeWithTag("lfs-lock-confirm").assertIsNotEnabled()
                    compose.onNodeWithTag("lfs-lock-warning").performScrollTo().assertTextEquals(text(language, "ui_this_removes_another_connection_s_lock_coo_880defbf"))
                    compose.onNodeWithTag("lfs-lock-force-ack").performScrollTo().assertIsOff().performClick()
                    compose.onNodeWithTag("lfs-lock-force-ack").assertTextEquals(text(language, "ui_i_understand_that_another_owner_may_still_b4a12b2d"))
                }
                compose.onNodeWithTag("lfs-lock-confirm").assertIsEnabled()
                val before = confirmed
                compose.runOnIdle { frame.value = frame.value.copy(busy = true) }
                compose.onNodeWithTag("lfs-lock-confirm").assertIsNotEnabled().performClick()
                compose.runOnIdle { frame.value = frame.value.copy(busy = false, valid = false) }
                compose.onNodeWithTag("lfs-lock-stale").performScrollTo().assertTextEquals(text(language, "ui_the_review_changed_cancel_and_refresh_serv_c7ee804c"))
                compose.onNodeWithTag("lfs-lock-confirm").assertIsNotEnabled().performClick()
                compose.runOnIdle { assertEquals(before, confirmed) }
                compose.onNodeWithTag("lfs-lock-cancel").performClick()
                compose.onNodeWithTag("lfs-lock-title").assertDoesNotExist()
                // A newly opened force dialog must require acknowledgement again.
                compose.runOnIdle { frame.value = Frame(language, index) }
                if (choice.force) {
                    compose.onNodeWithTag("lfs-lock-confirm").assertIsNotEnabled()
                    compose.onNodeWithTag("lfs-lock-force-ack").performScrollTo().assertIsOff().performClick()
                }
                compose.onNodeWithTag("lfs-lock-confirm").assertIsEnabled().performClick()
                compose.onNodeWithTag("lfs-lock-title").assertDoesNotExist()
                compose.runOnIdle { assertEquals(before + 1, confirmed) }
            }
            compose.runOnIdle { assertEquals(125, confirmed); assertEquals(125, cancelled) }
        }
    }
}

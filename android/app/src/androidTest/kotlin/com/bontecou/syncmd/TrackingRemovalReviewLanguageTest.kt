package com.bontecou.syncmd

import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.repositories.GitTrackingRemoval
import com.bontecou.syncmd.ui.screens.TrackingRemovalReview
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.TrackingRemovalRequest
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID

/** Review rendering/callbacks only; never removes tracking or changes actual app/system language. */
class TrackingRemovalReviewLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private data class Frame(val language: String, val backup: Boolean, val busy: Boolean = false, val showing: Boolean = true)
    @Test fun backupWarningsSizeAndBusyControlsFollowAllLocalesWithoutChangingRepositoryBytes() {
        val root = File(app.filesDir, "tracking-review-${UUID.randomUUID()}").canonicalFile
        fun contents() = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }
        try {
            Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
                File(root, "draft.md").writeText("Keep owned fixture\n"); git.add().addFilepattern(".").call()
                git.commit().setMessage("Fixture").setAuthor("Fixture", "fixture@example.invalid").call()
            }
            val plan = runBlocking { GitTrackingRemoval().prepare(root.path) }; val before = contents()
            val expected = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
            val languages = expected.keys().asSequence().toList().sorted(); assertEquals(25, languages.size)
            fun text(language: String, key: String): String {
                val rows = expected.getJSONArray(language)
                return (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("name") == key }.getString("source")
            }
            val frame = mutableStateOf(Frame(languages.first(), true))
            val continued = mutableListOf<TrackingRemovalRequest>(); var cancelled = 0; var toggles = 0
            ActivityScenario.launch<ComponentActivity>(Intent(app, ComponentActivity::class.java)).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val current = frame.value
                    val configuration = Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(current.language)); setLayoutDirection(Locale.forLanguageTag(current.language)) }
                    val localized = app.createConfigurationContext(configuration); val density = LocalDensity.current
                    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                        LocalLayoutDirection provides if (current.language in listOf("ar", "he")) LayoutDirection.Rtl else LayoutDirection.Ltr,
                        LocalDensity provides Density(density.density, 1.6f)) {
                        SyncMdTheme(darkTheme = current.language in listOf("ar", "he", "ja")) {
                            if (current.showing) TrackingRemovalReview(plan, current.backup, current.busy,
                                onKeepBackup = { toggles++; frame.value = frame.value.copy(backup = it) },
                                onContinue = { continued += TrackingRemovalRequest(plan, current.backup); frame.value = frame.value.copy(showing = false) },
                                onCancel = { cancelled++; frame.value = frame.value.copy(showing = false) })
                        }
                    }
                } }
                for (language in languages) for (backup in listOf(true, false)) {
                    compose.runOnIdle { frame.value = Frame(language, backup) }
                    compose.onNodeWithTag("tracking-review-title").assertTextEquals(text(language, "ui_remove_git_tracking_1f8255d9"))
                    compose.onNodeWithTag("tracking-review-path").performScrollTo().assertTextEquals(root.path)
                    val config = Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(language)) }
                    val size = Formatter.formatFileSize(app.createConfigurationContext(config), plan.metadataBytes)
                    compose.onNodeWithTag("tracking-review-size").performScrollTo().assertTextEquals(String.format(Locale.forLanguageTag(language), text(language, "tracking_metadata_size"), size))
                    compose.onNodeWithTag("tracking-review-scope").performScrollTo().assertTextEquals(text(language, "ui_working_files_stay_in_place_local_branches_a0748350"))
                    compose.onNodeWithTag("tracking-review-backup").performScrollTo().assertTextEquals(text(language, "ui_keep_a_verified_metadata_backup_5e821b62"))
                    val toggleBefore = toggles
                    val checkbox = compose.onNodeWithTag("tracking-review-backup").performScrollTo().assertHeightIsAtLeast(48.dp)
                    if (backup) checkbox.assertIsOn() else checkbox.assertIsOff()
                    checkbox.performClick()
                    if (backup) checkbox.assertIsOff() else checkbox.assertIsOn()
                    if (backup) compose.onNodeWithTag("tracking-review-warning").performScrollTo().assertTextEquals(text(language, "ui_without_a_backup_local_git_history_is_perm_bcb5c492"))
                    else compose.onNodeWithTag("tracking-review-warning").assertDoesNotExist()
                    compose.runOnIdle { assertEquals(toggleBefore + 1, toggles); frame.value = frame.value.copy(busy = true) }
                    compose.onNodeWithTag("tracking-review-backup").performScrollTo().assertIsNotEnabled().performClick()
                    compose.onNodeWithTag("tracking-review-continue").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
                    compose.onNodeWithTag("tracking-review-cancel").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
                    compose.runOnIdle { assertEquals(toggleBefore + 1, toggles); frame.value = frame.value.copy(busy = false) }
                    compose.onNodeWithTag("tracking-review-cancel").performClick()
                    compose.onNodeWithTag("tracking-review-title").assertDoesNotExist()
                    compose.runOnIdle { frame.value = Frame(language, backup) }
                    compose.onNodeWithTag("tracking-review-continue").assertIsEnabled().performClick()
                    compose.onNodeWithTag("tracking-review-title").assertDoesNotExist()
                    compose.runOnIdle { assertSame(plan, continued.last().plan); assertEquals(backup, continued.last().keepBackup) }
                    assertEquals(before, contents())
                }
                compose.runOnIdle { assertEquals(50, continued.size); assertEquals(50, cancelled) }
            }
        } finally { root.deleteRecursively() }
    }
}

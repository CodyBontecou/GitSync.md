package com.bontecou.syncmd

import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
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
import com.bontecou.syncmd.ui.screens.TrackingRemovalConfirmation
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

/** Final-consent UI only: callbacks never detach Git or change catalog/settings. */
class TrackingRemovalConsentLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private data class Frame(val language: String, val backup: Boolean, val valid: Boolean = true, val busy: Boolean = false, val showing: Boolean = true)
    @Test fun reviewedPathAndBackupChoiceStayVisibleAndGatedAcrossAllLocales() {
        val root = File(app.filesDir, "tracking-consent-${UUID.randomUUID()}").canonicalFile
        fun contents() = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readBytes().toList() }
        try {
            Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
                File(root, "draft.md").writeText("Keep owned fixture\n")
                git.add().addFilepattern(".").call(); git.commit().setMessage("Fixture").setAuthor("Fixture", "fixture@example.invalid").call()
            }
            val plan = runBlocking { GitTrackingRemoval().prepare(root.path) }
            val before = contents()
            val expected = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
            val languages = expected.keys().asSequence().toList().sorted(); assertEquals(25, languages.size)
            // The rendered copy is the localized resource for the locale (new Material 3 strings are English-only and
            // fall back); where the catalog expectation lists the key, the resource must also match the catalog.
            fun text(language: String, id: Int): String {
                val configuration = Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(language)) }
                val resource = app.createConfigurationContext(configuration).getString(id)
                val rows = expected.getJSONArray(language); val key = app.resources.getResourceEntryName(id)
                (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("name") == key }
                    ?.let { assertEquals("$language/$key", it.getString("source"), resource) }
                return resource
            }
            val frame = mutableStateOf(Frame(languages.first(), true))
            val confirmed = mutableListOf<TrackingRemovalRequest>(); var cancelled = 0
            ActivityScenario.launch<ComponentActivity>(Intent(app, ComponentActivity::class.java)).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val current = frame.value
                    val request = TrackingRemovalRequest(plan, current.backup)
                    val configuration = Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(current.language)); setLayoutDirection(Locale.forLanguageTag(current.language)) }
                    val localized = app.createConfigurationContext(configuration); val density = LocalDensity.current
                    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                        LocalLayoutDirection provides if (current.language in listOf("ar", "he")) LayoutDirection.Rtl else LayoutDirection.Ltr,
                        LocalDensity provides Density(density.density, 1.6f)) {
                        SyncMdTheme(darkTheme = current.language in listOf("ar", "he", "ja")) {
                            if (current.showing) TrackingRemovalConfirmation(request, current.valid, current.busy,
                                onCancel = { cancelled++; frame.value = frame.value.copy(showing = false) },
                                onConfirm = { confirmed += request; frame.value = frame.value.copy(showing = false) })
                        }
                    }
                } }
                for (language in languages) for (backup in listOf(true, false)) {
                    compose.runOnIdle { frame.value = Frame(language, backup) }
                    compose.onNodeWithTag("tracking-final-title").assertTextEquals(text(language,
                        if (backup) R.string.git_m3_tracking_backup_title else R.string.tracking_remove_permanent_title))
                    compose.onNodeWithTag("tracking-final-path").performScrollTo().assertTextEquals(root.path)
                    compose.onNodeWithTag("tracking-final-scope").performScrollTo().assertTextEquals(text(language,
                        if (backup) R.string.git_m3_tracking_backup_body else R.string.git_m3_tracking_permanent_body))
                    compose.onNodeWithTag("tracking-final-confirm").assertTextEquals(text(language, R.string.ui_remove_tracking_4b40d72c)).assertIsEnabled().assertHeightIsAtLeast(48.dp)
                    val prior = confirmed.size
                    compose.runOnIdle { frame.value = frame.value.copy(valid = false) }
                    compose.onNodeWithTag("tracking-final-stale").performScrollTo().assertTextEquals(text(language, R.string.git_m3_tracking_stale))
                    compose.onNodeWithTag("tracking-final-confirm").assertIsNotEnabled().performClick()
                    compose.runOnIdle { frame.value = frame.value.copy(valid = true, busy = true) }
                    compose.onNodeWithTag("tracking-final-confirm").assertIsNotEnabled().performClick()
                    compose.onNodeWithTag("tracking-final-cancel").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
                    compose.runOnIdle { assertEquals(prior, confirmed.size); frame.value = frame.value.copy(busy = false) }
                    compose.onNodeWithTag("tracking-final-cancel").performClick()
                    compose.onNodeWithTag("tracking-final-title").assertDoesNotExist()
                    compose.runOnIdle { frame.value = Frame(language, backup) }
                    compose.onNodeWithTag("tracking-final-confirm").performClick()
                    compose.onNodeWithTag("tracking-final-title").assertDoesNotExist()
                    compose.runOnIdle { assertEquals(prior + 1, confirmed.size); assertSame(plan, confirmed.last().plan); assertEquals(backup, confirmed.last().keepBackup) }
                    assertEquals(before, contents())
                }
                compose.runOnIdle { assertEquals(50, confirmed.size); assertEquals(50, cancelled) }
            }
        } finally { root.deleteRecursively() }
    }
}

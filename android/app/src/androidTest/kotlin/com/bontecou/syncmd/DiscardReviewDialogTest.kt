package com.bontecou.syncmd

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
import com.bontecou.syncmd.services.git.LocalDiffRepository
import com.bontecou.syncmd.ui.components.RevertConfirmDialog
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Component-local languages and 160% text; no system setting, user repository or remote is changed. */
class DiscardReviewDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fits(tag: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing text layout: $tag", layouts.isNotEmpty())
        assertTrue("Overflow: $tag", layouts.all { !it.hasVisualOverflow })
    }

    @Test fun everyLocaleCanReviewAllFullPathsAndCancelAtLargeTextWithoutChangingFilesOrIndex() {
        val root = File(app.filesDir, "discard-dialog-${UUID.randomUUID()}").canonicalFile
        var scenario: ActivityScenario<ComponentActivity>? = null
        try {
            Git.init().setDirectory(root).call().use { git ->
                File(root, "tracked.md").writeText("Committed\n")
                git.add().addFilepattern(".").call(); git.commit().setMessage("Owned fixture").setAuthor("Fixture", "fixture@example.invalid").call()
            }
            File(root, "tracked.md").writeText("Keep draft\n")
            val paths = (1..35).map { "section-$it/${"long-reviewed-folder-".repeat(5)}/notes.md" }
            paths.forEach { File(root, it).apply { parentFile!!.mkdirs(); writeText("Keep $it\n") } }
            val review = runBlocking { LocalDiffRepository().reviewDiscard(root.path).getOrThrow() }
            val index = File(root, ".git/index").readBytes()
            val expected = org.json.JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
            val languages = expected.keys().asSequence().toList().sorted(); assertEquals(25, languages.size)
            for (language in languages) {
                val config = Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(language)) }
                val localized = app.createConfigurationContext(config)
                val showing = mutableStateOf(true); var cancelled = 0; var confirmed = 0
                scenario = ActivityScenario.launch(android.content.Intent(app, ComponentActivity::class.java))
                scenario.onActivity { activity -> activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                        LocalLayoutDirection provides if (language in listOf("ar", "he")) LayoutDirection.Rtl else LayoutDirection.Ltr,
                        LocalDensity provides Density(density.density, 1.6f)) {
                        SyncMdTheme(darkTheme = language in listOf("ar", "he", "ja")) {
                            if (showing.value) RevertConfirmDialog(review,
                                onConfirm = { confirmed++; showing.value = false }, onDismiss = { cancelled++; showing.value = false })
                        }
                    }
                } }
                compose.onNodeWithTag("discard-review-title").assertIsDisplayed(); fits("discard-review-title")
                compose.onNodeWithTag("discard-review-count").assertTextEquals(localized.resources.getQuantityString(R.plurals.discard_review_files, review.paths.size, review.paths.size))
                compose.onNodeWithTag("discard-review-scope").assertTextEquals(localized.getString(R.string.discard_review_all))
                val last = paths.last()
                compose.onNodeWithTag("discard-review").performScrollToNode(hasTestTag("discard-review-path:$last"))
                compose.onNodeWithTag("discard-review-path:$last").assertTextEquals(last).assertIsDisplayed(); fits("discard-review-path:$last")
                compose.onNodeWithTag("discard-review").performScrollToNode(hasTestTag("discard-review-confirm"))
                compose.onNodeWithTag("discard-review-confirm").assertIsEnabled().assertHeightIsAtLeast(48.dp)
                compose.onNodeWithTag("discard-review-cancel").assertHeightIsAtLeast(48.dp).performClick()
                compose.onNodeWithTag("discard-review").assertDoesNotExist()
                assertEquals(1, cancelled); assertEquals(0, confirmed)
                assertArrayEquals(index, File(root, ".git/index").readBytes())
                assertEquals("Keep draft\n", File(root, "tracked.md").readText())
                paths.forEach { assertEquals("Keep $it\n", File(root, it).readText()) }
                scenario.close(); scenario = null
            }
        } finally { scenario?.close(); root.deleteRecursively() }
    }

    @Test fun unbornConfirmationSaysClearStagingAndPreservesEveryWorkingFile() {
        val root = File(app.filesDir, "discard-unborn-${UUID.randomUUID()}").canonicalFile
        var scenario: ActivityScenario<ComponentActivity>? = null
        try {
            Git.init().setDirectory(root).call().close(); File(root, "new.md").writeText("Keep new\n")
            val service = LocalDiffRepository()
            runBlocking { service.stageAll(root.path).getOrThrow() }
            val review = runBlocking { service.reviewDiscard(root.path).getOrThrow() }
            assertTrue(review.keepsWorkingFiles)
            var confirmed = false
            scenario = ActivityScenario.launch(android.content.Intent(app, ComponentActivity::class.java))
            scenario.onActivity { activity -> activity.setContent { SyncMdTheme {
                RevertConfirmDialog(review, onConfirm = { confirmed = true }, onDismiss = {})
            } } }
            compose.onNodeWithTag("discard-review-scope").assertTextEquals(app.getString(R.string.discard_review_unborn))
            compose.onNodeWithTag("discard-review").performScrollToNode(hasTestTag("discard-review-confirm"))
            compose.onNodeWithTag("discard-review-confirm").assert(hasText(app.getString(R.string.discard_clear_staging))).assertIsDisplayed().performClick()
            assertTrue(confirmed)
            runBlocking { service.discardReviewed(root.path, review).getOrThrow() }
            assertEquals("Keep new\n", File(root, "new.md").readText())
            Git.open(root).use { assertEquals(0, it.repository.readDirCache().entryCount); assertNull(it.repository.resolve("HEAD")) }
        } finally { scenario?.close(); root.deleteRecursively() }
    }
}

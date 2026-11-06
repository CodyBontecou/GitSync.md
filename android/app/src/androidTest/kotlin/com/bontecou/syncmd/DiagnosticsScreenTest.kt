package com.bontecou.syncmd

import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.bontecou.syncmd.diagnostics.AppDiagnostics
import com.bontecou.syncmd.services.diagnostics.*
import com.bontecou.syncmd.ui.screens.DiagnosticsScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.DiagnosticsViewModel
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.Locale

/** Scoped logs; never clears the installed app's diagnostic history. */
class DiagnosticsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var root: File
    private lateinit var logs: AppDiagnostics
    private lateinit var vm: DiagnosticsViewModel
    private val dark = mutableStateOf(false)
    private val reviewLocale = mutableStateOf<String?>(null)
    @Before fun setup() {
        root = File(context.noBackupFilesDir, "diagnostics-ui-${UUID.randomUUID()}").canonicalFile
        logs = AppDiagnostics(root)
    }
    @After fun cleanup() {
        if (::vm.isInitialized) instrumentation.runOnMainSync { ViewModelStore().apply { put("diagnostics", vm); clear() } }
        root.deleteRecursively()
    }
    /** INFO pull, WARNING branch, ERROR push. */
    private data class Seeded(val pull: DiagnosticEntry, val branch: DiagnosticEntry, val push: DiagnosticEntry)
    private fun seed(): Seeded {
        val seeded = Seeded(DiagnosticEntry(operation = DiagnosticOperation.PULL, outcome = DiagnosticOutcome.COMPLETED),
            DiagnosticEntry(operation = DiagnosticOperation.BRANCH, outcome = DiagnosticOutcome.ATTENTION, reason = DiagnosticReason.LFS_CONTENTS),
            DiagnosticEntry(operation = DiagnosticOperation.PUSH, outcome = DiagnosticOutcome.STOPPED, reason = DiagnosticReason.LFS_LOCK))
        logs.store.append(seeded.pull); logs.store.append(seeded.branch); logs.store.append(seeded.push)
        return seeded
    }
    private fun row(entry: DiagnosticEntry) = compose.onNodeWithTag("diagnostic-entry-${entry.id}", useUnmergedTree = true)
    /** The top bar subtitle replaced the old "N shown · M stored" line: "3 entries · 1 error in 24h". */
    private fun assertStored(stored: Int, errorsToday: Int, resources: android.content.res.Resources = context.resources) {
        val subtitle = resources.getQuantityString(R.plurals.settings_m3_entries, stored, stored) + " · " +
            resources.getQuantityString(R.plurals.settings_m3_debug_errors_day, errorsToday, errorsToday)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(subtitle) and hasAnyAncestor(hasTestTag("diagnostics-title")), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty() }
    }
    private fun show() {
        vm = DiagnosticsViewModel(logs)
        compose.setContent {
            val density = LocalDensity.current
            val original = LocalContext.current
            val tag = reviewLocale.value
            val localized = remember(original, tag) {
                if (tag == null) original else {
                    val config = Configuration(original.resources.configuration).apply {
                        setLocales(LocaleList.forLanguageTags(tag)); setLayoutDirection(Locale.forLanguageTag(tag))
                    }
                    val resources = original.createConfigurationContext(config).resources
                    object : ContextWrapper(original) { override fun getResources() = resources }
                }
            }
            val direction = if (tag == null) LocalLayoutDirection.current else if (
                android.text.TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag(tag)) == android.view.View.LAYOUT_DIRECTION_RTL
            ) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                LocalLayoutDirection provides direction, LocalDensity provides Density(density.density, if (dark.value) 1.6f else 1f)) {
                SyncMdTheme(darkTheme = dark.value) { DiagnosticsScreen({}, vm) }
            }
        }
        compose.waitUntil(10_000) { !vm.busy.value }
    }
    private fun capture(name: String) {
        val folder = File(context.getExternalFilesDir(null), "parity-evidence").apply { mkdirs() }
        compose.waitForIdle()
        var previous: android.graphics.Bitmap? = null
        var stable = 0
        try {
            compose.waitUntil(8_000) {
                val next = instrumentation.uiAutomation.takeScreenshot()
                stable = if (previous?.sameAs(next) == true) stable + 1 else 0
                previous?.recycle(); previous = next
                stable >= 3
            }
            File(folder, name).outputStream().use { previous!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { previous?.recycle() }
    }
    @Test fun liveSeverityFilterCopiesOnlyVisibleEntriesAndSurvivesStoreRestart() {
        val seeded = seed(); show()
        compose.onNodeWithTag("diagnostics-filter-ERROR").performScrollTo().performClick().assertIsSelected()
        row(seeded.push).assertIsDisplayed(); row(seeded.pull).assertDoesNotExist(); row(seeded.branch).assertDoesNotExist()
        compose.onNode(hasText(context.getString(R.string.settings_m3_debug_error)) and hasAnyAncestor(hasTestTag("diagnostic-entry-${seeded.push.id}")), useUnmergedTree = true)
            .assertIsDisplayed()
        assertStored(3, 1)
        val fetch = DiagnosticEntry(operation = DiagnosticOperation.FETCH, outcome = DiagnosticOutcome.COMPLETED)
        logs.store.append(fetch)
        assertStored(4, 1); row(fetch).assertDoesNotExist(); row(seeded.push).assertIsDisplayed()
        capture("diagnostics-filter-light.png")
        compose.onNodeWithTag("diagnostics-copy").performClick()
        compose.waitUntil(10_000) { !vm.busy.value }
        var copied = ""
        instrumentation.runOnMainSync { copied = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString() }
        assertTrue(copied.contains("[PUSH] STOPPED")); assertFalse(copied.contains("[PULL]")); assertFalse(copied.contains("[BRANCH]"))
        val fresh = DiagnosticStore(root); fresh.load(); assertEquals(4, fresh.entries.value.size)
    }
    @Test fun clearRequiresConfirmationAndClearsHiddenEntriesAtLargeText() {
        val seeded = seed(); dark.value = true; show()
        compose.onNodeWithTag("diagnostics-filter-WARNING").performScrollTo().performClick()
        row(seeded.branch).assertIsDisplayed(); row(seeded.pull).assertDoesNotExist(); row(seeded.push).assertDoesNotExist()
        compose.onNodeWithTag("diagnostics-clear").performClick()
        compose.onNodeWithTag("diagnostics-cancel-clear").assertTextEquals("Cancel").performClick()
        assertEquals(3, logs.store.entries.value.size)
        compose.onNodeWithTag("diagnostics-clear").performClick()
        capture("diagnostics-clear-dark-xl.png")
        compose.onNodeWithTag("diagnostics-confirm-clear").performClick()
        compose.waitUntil(10_000) { !vm.busy.value && logs.store.entries.value.isEmpty() }
        assertStored(0, 0)
        compose.onNodeWithTag("diagnostics-empty").performScrollTo().assertTextEquals(context.getString(R.string.diagnostics_empty))
        compose.onNodeWithTag("diagnostics-clear").assertIsNotEnabled()
        capture("diagnostics-empty-dark-xl.png")
        val fresh = DiagnosticStore(root); fresh.load(); assertTrue(fresh.entries.value.isEmpty())
    }
    @Test fun nativeShareChooserCanBeCancelledAndReturnsToTheLog() {
        seed(); show()
        compose.onNodeWithTag("diagnostics-share").performClick()
        val device = UiDevice.getInstance(instrumentation)
        val chooserPackage = context.packageManager.resolveActivity(Intent(Intent.ACTION_CHOOSER), 0)!!.activityInfo.packageName
        compose.waitUntil(10_000) { device.currentPackageName == chooserPackage }
        device.pressBack()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("diagnostics-screen").fetchSemanticsNodes().isNotEmpty() && device.currentPackageName == context.packageName }
        assertEquals(3, logs.store.entries.value.size)
    }
    @Test fun corruptLogShowsRecoveryAndClearRestartsRecording() {
        root.mkdirs(); File(root, "events.json").writeText("broken private file not displayed")
        show()
        compose.onNodeWithTag("diagnostics-storage-problem").performScrollTo().assertIsDisplayed()
        assertEquals("broken private file not displayed", File(root, "events.json").readText())
        compose.onNodeWithTag("diagnostics-clear").assertIsEnabled().performClick()
        compose.onNodeWithTag("diagnostics-clear-text").assertTextEquals(context.getString(R.string.diagnostics_clear_recovery))
        compose.onNodeWithTag("diagnostics-confirm-clear").performClick()
        compose.waitUntil(10_000) { !vm.busy.value && !logs.store.storageProblem.value }
        compose.onNodeWithTag("diagnostics-storage-problem").assertDoesNotExist()
        val clone = DiagnosticEntry(operation = DiagnosticOperation.CLONE, outcome = DiagnosticOutcome.COMPLETED)
        logs.store.append(clone)
        assertStored(1, 0); row(clone).performScrollTo().assertIsDisplayed()
    }

    @Test fun everyLocaleRetainsFeedbackAndReviewsAllFilteredRecordsBeforeClearingAtLargeText() {
        // Component locale/direction/density coverage; not system-language recreation.
        val expected = org.json.JSONObject(instrumentation.context.assets.open("localization_expected.json")
            .bufferedReader().use { it.readText() }).getJSONObject("locales")
        val tags = expected.keys().asSequence().toList().sorted(); assertEquals(25, tags.size)
        seed(); dark.value = true; show()
        val sameModel = vm
        for (tag in tags) {
            val locale = Locale.forLanguageTag(tag)
            val rows = expected.getJSONArray(tag)
            val copy = (0 until rows.length()).map { rows.getJSONObject(it) }
                .associate { it.getString("name") to it.getString("source") }
            compose.runOnIdle { reviewLocale.value = tag; vm.copied() }
            compose.onNodeWithTag("diagnostics-notice").performScrollTo().assertTextEquals(copy.getValue("diagnostics_copied"))
            compose.onNodeWithTag("diagnostics-filter-ERROR").performScrollTo().performClick()
            // The stored count moved to the top bar subtitle; its Material 3 plurals are not translated yet, so
            // expect whatever the locale's resources resolve (English fallback).
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(tag)) }).resources
            assertStored(3, 1, localized)
            compose.onNodeWithTag("diagnostics-clear").performClick()
            val consent = String.format(locale, copy.getValue("diagnostics_clear_entries"), 3)
            compose.onNodeWithTag("diagnostics-clear-text").assertTextEquals(consent).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("diagnostics-clear-text").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Clear consent clipped: $tag", layouts.isNotEmpty() && layouts.all { !it.hasVisualOverflow })
            compose.onNodeWithTag("diagnostics-confirm-clear").assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("diagnostics-cancel-clear").assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(3, DiagnosticStore(root).apply { load() }.entries.value.size)
            compose.onNodeWithTag("diagnostics-clear").performClick()
            compose.onNodeWithTag("diagnostics-confirm-clear").performClick()
            compose.waitUntil(10_000) { !vm.busy.value && logs.store.entries.value.isEmpty() }
            compose.onNodeWithTag("diagnostics-notice").performScrollTo().assertTextEquals(copy.getValue("diagnostics_cleared"))
            assertTrue(DiagnosticStore(root).apply { load() }.entries.value.isEmpty())
            assertSame(sameModel, vm)
            seed()
        }
    }
}

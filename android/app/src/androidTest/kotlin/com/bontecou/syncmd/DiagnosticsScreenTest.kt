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
import java.text.NumberFormat

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
    private fun seed() {
        logs.store.append(DiagnosticEntry(operation = DiagnosticOperation.PULL, outcome = DiagnosticOutcome.COMPLETED))
        logs.store.append(DiagnosticEntry(operation = DiagnosticOperation.BRANCH, outcome = DiagnosticOutcome.ATTENTION, reason = DiagnosticReason.LFS_CONTENTS))
        logs.store.append(DiagnosticEntry(operation = DiagnosticOperation.PUSH, outcome = DiagnosticOutcome.STOPPED, reason = DiagnosticReason.LFS_LOCK))
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
        seed(); show()
        compose.onNodeWithTag("diagnostics-filter-ERROR").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-count").performScrollTo().assertTextEquals("1 shown · 3 stored")
        logs.store.append(DiagnosticEntry(operation = DiagnosticOperation.FETCH, outcome = DiagnosticOutcome.COMPLETED))
        compose.onNodeWithTag("diagnostics-count").assertTextEquals("1 shown · 4 stored")
        capture("diagnostics-filter-light.png")
        compose.onNodeWithTag("diagnostics-copy").performScrollTo().performClick()
        compose.waitUntil(10_000) { !vm.busy.value }
        var copied = ""
        instrumentation.runOnMainSync { copied = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString() }
        assertTrue(copied.contains("[PUSH] STOPPED")); assertFalse(copied.contains("[PULL]")); assertFalse(copied.contains("[BRANCH]"))
        val fresh = DiagnosticStore(root); fresh.load(); assertEquals(4, fresh.entries.value.size)
    }
    @Test fun clearRequiresConfirmationAndClearsHiddenEntriesAtLargeText() {
        seed(); dark.value = true; show()
        compose.onNodeWithTag("diagnostics-filter-WARNING").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-clear").performScrollTo().performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertEquals(3, logs.store.entries.value.size)
        compose.onNodeWithTag("diagnostics-clear").performClick()
        capture("diagnostics-clear-dark-xl.png")
        compose.onNodeWithTag("diagnostics-confirm-clear").performClick()
        compose.waitUntil(10_000) { !vm.busy.value && logs.store.entries.value.isEmpty() }
        compose.onNodeWithTag("diagnostics-count").performScrollTo().assertTextEquals("0 shown · 0 stored")
        capture("diagnostics-empty-dark-xl.png")
        val fresh = DiagnosticStore(root); fresh.load(); assertTrue(fresh.entries.value.isEmpty())
    }
    @Test fun nativeShareChooserCanBeCancelledAndReturnsToTheLog() {
        seed(); show()
        compose.onNodeWithTag("diagnostics-share").performScrollTo().performClick()
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
        compose.onNodeWithTag("diagnostics-clear").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics-confirm-clear").performClick()
        compose.waitUntil(10_000) { !vm.busy.value && !logs.store.storageProblem.value }
        logs.store.append(DiagnosticEntry(operation = DiagnosticOperation.CLONE, outcome = DiagnosticOutcome.COMPLETED))
        compose.onNodeWithTag("diagnostics-count").performScrollTo().assertTextEquals("1 shown · 1 stored")
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
            compose.onNodeWithTag("diagnostics-screen").performScrollToNode(hasTestTag("diagnostics-notice"))
            compose.onNodeWithTag("diagnostics-notice").assertTextEquals(copy.getValue("diagnostics_copied"))
            compose.onNodeWithTag("diagnostics-filter-ERROR").performScrollTo().performClick()
            compose.onNodeWithTag("diagnostics-count").performScrollTo().assertTextEquals(String.format(locale,
                copy.getValue("ui_1_s_shown_2_s_stored_c5d98835"), NumberFormat.getIntegerInstance(locale).format(1),
                NumberFormat.getIntegerInstance(locale).format(3)))
            compose.onNodeWithTag("diagnostics-clear").performScrollTo().performClick()
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
            compose.onNodeWithTag("diagnostics-screen").performScrollToNode(hasTestTag("diagnostics-notice"))
            compose.onNodeWithTag("diagnostics-notice").assertTextEquals(copy.getValue("diagnostics_cleared"))
            assertTrue(DiagnosticStore(root).apply { load() }.entries.value.isEmpty())
            assertSame(sameModel, vm)
            seed()
        }
    }
}

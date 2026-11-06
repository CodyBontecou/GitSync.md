package com.bontecou.syncmd

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File

class AppGuideNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val guide get() = context.getSharedPreferences("app_guide", Context.MODE_PRIVATE)
    private var original: Map<String, *> = emptyMap<String, Any>()
    private var scenario: ActivityScenario<MainActivity>? = null
    @Before fun setup() { original = guide.all.toMap() }
    @After fun cleanup() {
        scenario?.close()
        val edit = guide.edit().clear()
        original.forEach { (key,value) -> when(value) {
            is String -> edit.putString(key,value); is Int -> edit.putInt(key,value); is Boolean -> edit.putBoolean(key,value)
            is Long -> edit.putLong(key,value); is Float -> edit.putFloat(key,value)
        } }
        check(edit.commit())
    }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitChoices() = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag("onboarding-github") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    private fun startOnboarding() {
        check(guide.edit().clear().putBoolean("initialized",true).putBoolean("completed",false).putInt("page",0).putInt("notes_seen",BuildConfig.VERSION_CODE).commit())
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java)); awaitTag("onboarding"); awaitChoices()
    }
    private fun capture(name: String) {
        compose.waitForIdle(); val folder = File(context.getExternalFilesDir(null),"parity-evidence").apply { mkdirs() }
        var previous: android.graphics.Bitmap? = null; var stable = 0
        try {
            compose.waitUntil(8_000) { val next=instrumentation.uiAutomation.takeScreenshot(); stable=if(previous?.sameAs(next)==true) stable+1 else 0; previous?.recycle();previous=next;stable>=3 }
            File(folder,name).outputStream().use { previous!!.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        } finally { previous?.recycle() }
    }
    @Test fun firstRunResumesAndUrlChoiceDoesNotLeaveTourInBackStack() {
        startOnboarding(); capture("onboarding-light.png")
        scenario!!.recreate(); awaitTag("onboarding"); awaitChoices()
        compose.onNodeWithTag("onboarding-other-host").performScrollTo().performClick()
        awaitTag("remote-clone-screen")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("remote-clone-screen").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithTag("onboarding").assertCountEquals(0)
        assertTrue(guide.getBoolean("completed", false))
    }
    @Test fun settingsReplayAndReleaseNotesReturnWithoutChangingFirstRunCompletion() {
        check(guide.edit().putBoolean("initialized",true).putBoolean("completed",true).putInt("notes_seen",BuildConfig.VERSION_CODE).remove("pending").commit())
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("open-settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("open-settings").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("open-tour")); compose.onNodeWithTag("open-tour").performClick()
        awaitTag("how-it-works-step-0")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("app-tour").fetchSemanticsNodes().isEmpty() }
        assertTrue(guide.getBoolean("completed", false))
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("open-release-notes")); compose.onNodeWithTag("open-release-notes").performClick()
        awaitTag("release-notes"); capture("release-notes.png")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("release-notes").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun newBuildNotesAreAcknowledgedWithoutReenteringOnboarding() {
        check(guide.edit().putBoolean("initialized",true).putBoolean("completed",true).putInt("notes_seen",BuildConfig.VERSION_CODE-1).remove("pending").commit())
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java)); awaitTag("notes-acknowledge")
        compose.onNodeWithTag("notes-acknowledge").performClick()
        compose.waitUntil(10_000) { guide.getInt("notes_seen",-1)==BuildConfig.VERSION_CODE }
        scenario!!.recreate()
        compose.onAllNodesWithTag("notes-acknowledge").assertCountEquals(0)
        compose.onAllNodesWithTag("onboarding").assertCountEquals(0)
    }
    @Test fun supportActionsOpenDraftIntentsAndCopyThePublicAddress() {
        check(guide.edit().putBoolean("initialized",true).putBoolean("completed",true).putInt("notes_seen",BuildConfig.VERSION_CODE).remove("pending").commit())
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("open-settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("open-settings").performClick()
        val mail = android.content.IntentFilter(Intent.ACTION_SENDTO).apply { addDataScheme("mailto") }
        val community = android.content.IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https"); addDataAuthority("discord.gg",null) }
        val mailMonitor = instrumentation.addMonitor(mail, android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null),true)
        val communityMonitor = instrumentation.addMonitor(community, android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null),true)
        try {
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("send-feedback")); compose.onNodeWithTag("send-feedback").performClick()
            compose.waitUntil(5_000) { mailMonitor.hits == 1 }
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("open-community")); compose.onNodeWithTag("open-community").performClick()
            compose.waitUntil(5_000) { communityMonitor.hits == 1 }
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("copy-support-email")); compose.onNodeWithTag("copy-support-email").performClick()
            var copied = ""
            instrumentation.runOnMainSync { copied=(context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip!!.getItemAt(0).text.toString() }
            assertEquals(com.bontecou.syncmd.onboarding.SupportLinks.email,copied)
        } finally { instrumentation.removeMonitor(mailMonitor); instrumentation.removeMonitor(communityMonitor) }
    }

    @Test fun sampleSetupSelectsSampleEvenWhenAnotherVaultWasSelected() {
        val prefs = context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        val workspace = File(context.filesDir, "guide-vault-${java.util.UUID.randomUUID()}")
        val sampleWorkspace = File(context.filesDir, "sample-workspace")
        val sampleExisted = sampleWorkspace.exists()
        try {
            val other = com.bontecou.syncmd.services.git.SampleRepository.openOrCreate(workspace)
            val index = prefs.getInt("repository_count", 0)
            check(prefs.edit().putInt("repository_count", index+1).putString("repo_${index}_name", "Other guide vault")
                .putString("repo_${index}_path", other.path).putString("selected_repository",other.path).commit())
            startOnboarding()
            compose.onNodeWithTag("onboarding-demo").performScrollTo().performClick()
            val sample = File(sampleWorkspace,"sample-repository").canonicalPath
            compose.waitUntil(15_000) { prefs.getString("selected_repository","") == sample && compose.onAllNodesWithTag("vault-tab-files").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(guide.getBoolean("completed",false))
            compose.onAllNodesWithTag("onboarding").assertCountEquals(0)
        } finally {
            scenario?.close(); scenario=null
            val restore=prefs.edit().clear()
            before.forEach { (key,value) -> when(value) { is String -> restore.putString(key,value); is Int -> restore.putInt(key,value); is Long -> restore.putLong(key,value); is Boolean -> restore.putBoolean(key,value); is Float -> restore.putFloat(key,value); is Set<*> -> { @Suppress("UNCHECKED_CAST") restore.putStringSet(key,value as Set<String>) } } }
            check(restore.commit()); workspace.deleteRecursively()
            if (!sampleExisted) sampleWorkspace.deleteRecursively()
        }
    }


    @Test fun gitHubChoiceOpensSignIn() {
        startOnboarding()
        compose.onNodeWithTag("onboarding-github").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("ANY REPO. SYNCED TO YOUR ANDROID.",substring=false).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(guide.getBoolean("completed",false))
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("open-settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("onboarding").assertCountEquals(0)
        scenario!!.close(); scenario=null
    }

    @Test fun darkOnboardingShowsEveryWayIn() {
        val prefs = context.getSharedPreferences("sync_md_prefs",Context.MODE_PRIVATE)
        val hadTheme = prefs.contains("is_dark_theme"); val theme = prefs.getBoolean("is_dark_theme",false)
        try {
            check(prefs.edit().putBoolean("is_dark_theme",true).commit())
            startOnboarding()
            for (tag in listOf("onboarding-github", "onboarding-other-host", "onboarding-demo")) compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            capture("onboarding-dark.png")
            assertFalse(guide.getBoolean("completed",false))
        } finally {
            scenario?.close();scenario=null
            val restore=prefs.edit();if(hadTheme) restore.putBoolean("is_dark_theme",theme) else restore.remove("is_dark_theme")
            check(restore.commit())
        }
    }
}

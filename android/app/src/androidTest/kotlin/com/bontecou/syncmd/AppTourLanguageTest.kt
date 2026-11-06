package com.bontecou.syncmd

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.LocaleList
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import java.io.File

/**
 * Actual application-locale recreation over first run (the single OnboardingScreen) and the "How it works"
 * replay (Settings › Help › How it works), with owned preferences restored. Material 3 copy is English-only
 * for now, so expected text is whatever the activity's localized resources resolve (English fallback).
 */
@SdkSuppress(minSdkVersion = 33)
class AppTourLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val choices = listOf("onboarding-github" to R.string.m3home_onboarding_github,
        "onboarding-other-host" to R.string.m3home_onboarding_other, "onboarding-demo" to R.string.m3home_onboarding_demo)
    private fun restore(prefs: SharedPreferences, original: Map<String, *>) {
        val edit = prefs.edit().clear()
        original.forEach { (key,value) -> when(value) {
            is String -> edit.putString(key,value); is Int -> edit.putInt(key,value); is Boolean -> edit.putBoolean(key,value)
            is Long -> edit.putLong(key,value); is Float -> edit.putFloat(key,value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key,value as Set<String>) }
        } }
        check(edit.commit())
    }
    private fun assertPreferences(expected: Map<String,*>, actual: Map<String,*>) {
        assertEquals("Changed preference keys",expected.keys,actual.keys)
        expected.forEach { (key,value) -> assertTrue("Preference changed: $key",value==actual[key]) }
    }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitOnboarding() = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag("onboarding-github") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    /** No visual overflow; [checkHeight] also requires the parent not to clip a single-line label vertically. */
    private fun fits(node: SemanticsNodeInteraction, label: String, checkHeight: Boolean = true) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing layout: $label", layouts.isNotEmpty())
        assertTrue("Text overflow: $label", layouts.all { !it.hasVisualOverflow })
        val bounds = node.fetchSemanticsNode().boundsInRoot
        if (checkHeight) assertTrue("Parent clips text: $label; bounds=$bounds layout=${layouts.first().size}", layouts.all { it.size.height <= bounds.height + 1 })
    }
    private fun capture(scenario: ActivityScenario<MainActivity>, name: String) {
        compose.waitForIdle()
        val frame=java.util.concurrent.CountDownLatch(1)
        scenario.onActivity { activity ->
            val view=activity.window.decorView
            view.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }; view.postInvalidateOnAnimation()
        }
        assertTrue(frame.await(5,java.util.concurrent.TimeUnit.SECONDS))
        val file=File(context.filesDir,"parity-evidence/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun firstRun(guide: SharedPreferences) = check(guide.edit().clear().putBoolean("initialized",true).putBoolean("completed",false)
        .putInt("page",0).putInt("notes_seen",BuildConfig.VERSION_CODE).commit())
    /** Onboarding copy and every way in, as the localized activity resolves them, without overflow. */
    private fun assertOnboardingReadable(current: android.content.Context, tag: String) {
        val headline = current.getString(R.string.m3home_onboarding_headline)
        fits(compose.onNode(hasText(headline, substring = true) and isHeading(), useUnmergedTree = true).assertIsDisplayed(), "$tag / onboarding headline", false)
        fits(compose.onNode(hasText(current.getString(R.string.m3home_onboarding_body)) and hasAnyAncestor(hasTestTag("onboarding")), useUnmergedTree = true)
            .performScrollTo(), "$tag / onboarding body", false)
        choices.forEach { (choice, resource) ->
            val button = compose.onNodeWithTag(choice)
            button.performScrollTo().assertIsDisplayed().assertIsEnabled().assertTextEquals(current.getString(resource))
            fits(button, "$tag / $choice", false)
        }
    }
    @Test fun localizedOtherHostChoiceOpensReadableCloneFormAndBackCannotReenterOnboarding() {
        val guide=context.getSharedPreferences("app_guide",0); val originalGuide=guide.all.toMap()
        val prefs=context.getSharedPreferences("sync_md_prefs",0); val originalPrefs=prefs.all.toMap()
        val manager=context.getSystemService(LocaleManager::class.java)
        val before=manager.applicationLocales; val systemBefore=manager.systemLocales
        var scenario:ActivityScenario<MainActivity>?=null
        try {
            val locales=requireNotNull(LocaleConfig(context).supportedLocales)
            var checked=0
            for(index in 0 until locales.size()) {
                val tag=locales[index].toLanguageTag(); if(tag=="en") continue
                manager.applicationLocales=LocaleList.forLanguageTags(tag)
                firstRun(guide)
                scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java)); awaitTag("onboarding"); awaitOnboarding()
                var current:android.content.Context?=null; scenario.onActivity { current=it }
                val localized=checkNotNull(current)
                assertEquals(tag,localized.resources.configuration.locales[0].toLanguageTag())
                compose.onNodeWithTag("onboarding-other-host").performScrollTo().assertIsDisplayed()
                    .assertTextEquals(localized.getString(R.string.m3home_onboarding_other)).performClick()
                awaitTag("remote-clone-screen")
                val title=compose.onNode(hasText(localized.getString(R.string.add_remote_title)) and hasAnyAncestor(hasTestTag("remote-clone-title")),useUnmergedTree=true)
                title.assertIsDisplayed(); fits(title,"$tag / clone URL title")
                if(tag in listOf("ar","fi","ja")) capture(scenario,"onboarding-clone-$tag")
                compose.onNodeWithContentDescription(localized.getString(R.string.publication_back))
                    .assertIsDisplayed().assertIsEnabled().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("remote-clone-screen").fetchSemanticsNodes().isEmpty() }
                assertTrue(guide.getBoolean("completed",false)); compose.onAllNodesWithTag("onboarding").assertCountEquals(0)
                assertPreferences(originalPrefs,prefs.all)
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","onboarding_clone_locale=$tag; completed=true; back_reentry=false\n") })
                scenario.close(); scenario=null; checked++
            }
            assertEquals(25,checked)
        } finally {
            try { scenario?.close() } finally {
                manager.applicationLocales=before
                assertEquals(before,manager.applicationLocales); assertEquals(systemBefore,manager.systemLocales)
                restore(guide,originalGuide); restore(prefs,originalPrefs)
            }
        }
    }
    @Test fun actualLanguageChangesKeepFirstRunPendingAndOnboardingAndHowItWorksReadable() {
        val guide=context.getSharedPreferences("app_guide",0); val originalGuide=guide.all.toMap()
        val prefs=context.getSharedPreferences("sync_md_prefs",0); val originalPrefs=prefs.all.toMap()
        val manager=context.getSystemService(LocaleManager::class.java)
        val before=manager.applicationLocales; val systemBefore=manager.systemLocales
        var scenario: ActivityScenario<MainActivity>?=null
        fun recreateIn(tag: String): android.content.Context {
            val active=requireNotNull(scenario)
            var old:MainActivity?=null; active.onActivity { old=it }
            manager.applicationLocales=LocaleList.forLanguageTags(tag)
            compose.waitUntil(15_000) {
                var ready=false
                active.onActivity { ready=it!==old && it.resources.configuration.locales[0].toLanguageTag()==tag && it.lifecycle.currentState==Lifecycle.State.RESUMED }
                ready
            }
            var localized:android.content.Context?=null; active.onActivity { localized=it }
            val current=checkNotNull(localized)
            val systemScale=android.provider.Settings.System.getFloat(context.contentResolver,"font_scale",1f)
            assertEquals("Actual system text scale: $tag",systemScale,current.resources.configuration.fontScale,0.01f)
            return current
        }
        try {
            val locales=requireNotNull(LocaleConfig(context).supportedLocales)
            // Phase 1: first run stays pending (and on Onboarding) across every real language change.
            manager.applicationLocales=LocaleList.forLanguageTags("en")
            firstRun(guide)
            scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java)); awaitTag("onboarding"); awaitOnboarding()
            var checked=0
            for(index in 0 until locales.size()) {
                val tag=locales[index].toLanguageTag(); if(tag=="en") continue
                val current=recreateIn(tag)
                awaitTag("onboarding"); awaitOnboarding()
                assertOnboardingReadable(current,tag)
                if(tag in listOf("ar","de","ja")) capture(requireNotNull(scenario),"onboarding-$tag")
                assertFalse(guide.getBoolean("completed",true))
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","onboarding_locale=$tag; choices=${choices.size}; completed=false\n") })
                checked++
            }
            assertEquals(25,checked)
            requireNotNull(scenario).close(); scenario=null
            // Phase 2: How it works (Settings › Help) survives each recreation and keeps all four steps readable.
            manager.applicationLocales=LocaleList.forLanguageTags("en")
            check(guide.edit().putBoolean("completed",true).remove("pending").commit())
            scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java))
            awaitTag("open-settings"); compose.onNodeWithTag("open-settings").performClick()
            awaitTag("settings-help"); compose.onNodeWithTag("settings-help").performScrollTo().performClick()
            awaitTag("open-tour"); compose.onNodeWithTag("open-tour").performScrollTo().performClick()
            awaitTag("how-it-works-step-0")
            val titles=listOf(R.string.guide_clone_title,R.string.guide_edit_title,R.string.guide_git_title,R.string.guide_start_title)
            val bodies=listOf(R.string.guide_clone_body,R.string.guide_edit_body,R.string.guide_git_body,R.string.guide_start_body)
            checked=0
            for(index in 0 until locales.size()) {
                val tag=locales[index].toLanguageTag(); if(tag=="en") continue
                val current=recreateIn(tag)
                awaitTag("how-it-works-step-0") // The open help view is saved state; recreation must not drop it.
                for(step in 0..3) {
                    val stepTag="how-it-works-step-$step"
                    for((label,value) in listOf("step" to current.getString(R.string.guide_step,step+1),"title" to current.getString(titles[step]),"body" to current.getString(bodies[step]))) {
                        val node=compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag(stepTag)),useUnmergedTree=true).performScrollTo().assertIsDisplayed()
                        fits(node,"$tag / step $step / $label",false)
                    }
                }
                if(tag=="fi") capture(requireNotNull(scenario),"how-it-works-fi")
                assertTrue(guide.getBoolean("completed",false))
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","how_it_works_locale=$tag; steps=4\n") })
                checked++
            }
            assertEquals(25,checked)
            assertPreferences(originalPrefs,prefs.all)
        } finally {
            try { scenario?.close() } finally {
                manager.applicationLocales=before
                assertEquals(before,manager.applicationLocales); assertEquals(systemBefore,manager.systemLocales)
                restore(guide,originalGuide); restore(prefs,originalPrefs)
            }
        }
    }
}

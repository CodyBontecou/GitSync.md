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

/** Actual application-locale recreation and all four production pages, with owned preferences restored. */
@SdkSuppress(minSdkVersion = 33)
class AppTourLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
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
    private fun awaitPage(page: Int) = compose.waitUntil(10_000) {
        context.getSharedPreferences("app_guide",0).getInt("page",-1)==page &&
            compose.onAllNodes(hasTestTag(if(page==3) "tour-start-HOME" else "tour-next") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    private fun fits(tag: String, label: String) {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Missing layout: $label", layouts.isNotEmpty())
        assertTrue("Text overflow: $label", layouts.all { !it.hasVisualOverflow })
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue("Parent clips text: $label; bounds=$bounds layout=${layouts.single().size}", layouts.single().size.height <= bounds.height + 1)
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
    @Test fun localizedCloneChoiceOpensReadableUrlFormAndBackCannotReenterCompletedTour() {
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
                check(guide.edit().clear().putBoolean("initialized",true).putBoolean("completed",false)
                    .putInt("page",3).putInt("notes_seen",BuildConfig.VERSION_CODE).commit())
                scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java)); awaitPage(3)
                var current:android.content.Context?=null; scenario.onActivity { current=it }
                compose.onNodeWithTag("tour-start-URL").performScrollTo().assertIsDisplayed()
                    .assertTextEquals(checkNotNull(current).getString(R.string.guide_url)).performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("remote-clone-screen").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(checkNotNull(current).getString(R.string.add_remote_title)).assertIsDisplayed()
                fits("remote-clone-title","$tag / clone URL title")
                if(tag in listOf("ar","fi","ja")) capture(scenario,"tour-clone-$tag")
                compose.onNodeWithContentDescription(checkNotNull(current).getString(R.string.publication_back))
                    .assertIsDisplayed().assertIsEnabled().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("remote-clone-screen").fetchSemanticsNodes().isEmpty() }
                assertTrue(guide.getBoolean("completed",false)); compose.onAllNodesWithTag("app-tour").assertCountEquals(0)
                assertPreferences(originalPrefs,prefs.all)
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","tour_clone_locale=$tag; completed=true; back_reentry=false\n") })
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
    @Test fun actualLanguageChangesPreserveTourProgressAndKeepAllPagesAndSetupChoicesReadable() {
        val guide=context.getSharedPreferences("app_guide",0); val originalGuide=guide.all.toMap()
        val prefs=context.getSharedPreferences("sync_md_prefs",0); val originalPrefs=prefs.all.toMap()
        val manager=context.getSystemService(LocaleManager::class.java)
        val before=manager.applicationLocales; val systemBefore=manager.systemLocales
        var scenario: ActivityScenario<MainActivity>?=null
        try {
            manager.applicationLocales=LocaleList.forLanguageTags("en")
            check(guide.edit().clear().putBoolean("initialized",true).putBoolean("completed",false)
                .putInt("page",1).putInt("notes_seen",BuildConfig.VERSION_CODE).commit())
            scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java)); awaitPage(1)
            val locales=requireNotNull(LocaleConfig(context).supportedLocales)
            var checked=0
            for(index in 0 until locales.size()) {
                val tag=locales[index].toLanguageTag(); if(tag=="en") continue
                var old:MainActivity?=null; scenario.onActivity { old=it }
                manager.applicationLocales=LocaleList.forLanguageTags(tag)
                compose.waitUntil(15_000) {
                    var ready=false
                    scenario.onActivity { ready=it!==old && it.resources.configuration.locales[0].toLanguageTag()==tag && it.lifecycle.currentState==Lifecycle.State.RESUMED }
                    ready
                }
                compose.waitUntil(10_000) {
                    var loaded=false
                    scenario.onActivity { loaded=!androidx.lifecycle.ViewModelProvider(it)[com.bontecou.syncmd.ui.viewmodels.SettingsViewModel::class.java].isLoading.value }
                    loaded
                }
                val dark=tag in listOf("ar","he")
                scenario.onActivity {
                    val settings=androidx.lifecycle.ViewModelProvider(it)[com.bontecou.syncmd.ui.viewmodels.SettingsViewModel::class.java]
                    if(settings.appSettings.value.isDarkTheme!=dark) settings.updateSettings(settings.appSettings.value.copy(isDarkTheme=dark))
                }
                compose.waitUntil(10_000) {
                    var ready=false
                    scenario.onActivity { ready=androidx.lifecycle.ViewModelProvider(it)[com.bontecou.syncmd.ui.viewmodels.SettingsViewModel::class.java].appSettings.value.isDarkTheme==dark }
                    ready
                }
                awaitPage(1) // Mid-tour progress must survive the real locale recreation.
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; awaitPage(0)
                var localized:android.content.Context?=null; scenario.onActivity { localized=it }
                val current=checkNotNull(localized)
                val systemScale=android.provider.Settings.System.getFloat(context.contentResolver,"font_scale",1f)
                assertEquals("Actual system text scale: $tag",systemScale,current.resources.configuration.fontScale,0.01f)
                val titles=listOf(R.string.guide_clone_title,R.string.guide_edit_title,R.string.guide_git_title,R.string.guide_start_title)
                val bodies=listOf(R.string.guide_clone_body,R.string.guide_edit_body,R.string.guide_git_body,R.string.guide_start_body)
                compose.onNodeWithTag("tour-title",useUnmergedTree=true).assertTextEquals(current.getString(R.string.guide_title))
                fits("tour-title","$tag / app title")
                for(page in 0..3) {
                    awaitPage(page)
                    fits("tour-title","$tag / page $page / app title")
                    if(page>0) {
                        compose.onNodeWithTag("tour-back").assertContentDescriptionEquals(current.getString(R.string.guide_back))
                        val title=compose.onNodeWithTag("tour-title",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
                        val back=compose.onNodeWithTag("tour-back").fetchSemanticsNode().boundsInRoot
                        val rtl=current.resources.configuration.layoutDirection==android.view.View.LAYOUT_DIRECTION_RTL
                        assertTrue("Back/title overlap: $tag",if(rtl) title.right<=back.left else back.right<=title.left)
                    }
                    if(tag=="fi" && page==1) capture(scenario,"tour-header-fi")
                    compose.onNodeWithTag("tour-step-$page").assertTextEquals(current.getString(R.string.guide_step,page+1))
                    for((part,resource) in listOf("title" to titles[page],"body" to bodies[page])) {
                        compose.onNodeWithTag("tour-page-$part-$page").performScrollTo().assertTextEquals(current.getString(resource)).assertIsDisplayed()
                        fits("tour-page-$part-$page","$tag / page $page / $part")
                    }
                    compose.onNodeWithTag("tour-skip").assertIsDisplayed().assertTextEquals(current.getString(R.string.guide_skip))
                    if(page<3) compose.onNodeWithTag("tour-next").assertTextEquals(current.getString(R.string.guide_continue)).assertIsDisplayed().performClick()
                }
                val choices=listOf("LOGIN" to R.string.guide_login,"URL" to R.string.guide_url,"SAMPLE" to R.string.guide_sample,"STORAGE" to R.string.guide_storage,"HOME" to R.string.guide_home)
                choices.forEach { (target,resource) ->
                    val button=compose.onNodeWithTag("tour-start-$target")
                    button.performScrollTo().assertIsDisplayed().assertIsEnabled().assertTextEquals(current.getString(resource))
                    val layouts=mutableListOf<TextLayoutResult>()
                    button.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTrue("Setup label clips: $tag / $target",layouts.isNotEmpty() && layouts.all { !it.hasVisualOverflow })
                }
                if(tag in listOf("ar","de","ja")) capture(scenario,"tour-setup-$tag")
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","tour_locale=$tag; pages=4; choices=5; system_font=$systemScale; dark=$dark\n") })
                // Leave the next language change at page 1, so recreation proves restoration again.
                for(page in 2 downTo 1) { scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; awaitPage(page) }
                assertFalse(guide.getBoolean("completed",true)); checked++
            }
            assertEquals(25,checked)
            // Production theme updates also persist the currently loaded interval/debug defaults.
            // Those exact values are expected; all repository/account/selection values must stay unchanged.
            val expectedPrefs=originalPrefs.filterKeys { it!="is_dark_theme" }.toMutableMap()
            expectedPrefs["auto_fetch_interval"]=originalPrefs["auto_fetch_interval"] ?: 3600000L
            expectedPrefs["show_debug_info"]=originalPrefs["show_debug_info"] ?: false
            assertPreferences(expectedPrefs,prefs.all.filterKeys { it!="is_dark_theme" })
        } finally {
            try { scenario?.close() } finally {
                manager.applicationLocales=before
                assertEquals(before,manager.applicationLocales); assertEquals(systemBefore,manager.systemLocales)
                restore(guide,originalGuide); restore(prefs,originalPrefs)
            }
        }
    }
}

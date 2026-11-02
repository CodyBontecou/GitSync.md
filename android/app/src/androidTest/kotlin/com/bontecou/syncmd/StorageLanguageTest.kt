package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.storage.RepositoryLocations
import com.bontecou.syncmd.ui.screens.StorageScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.StorageViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual app language and system text size; no folder grants or real catalog changes. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 33)
class StorageLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private fun fits(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertTrue(layouts.all { !it.hasVisualOverflow })
        assertTrue(layouts.all { it.size.height <= node.fetchSemanticsNode().boundsInRoot.height + 1 })
    }
    @Test fun allLocalesSaveAppStorageAndRetainLocalizedNoticeAfterRecreation() {
        assertEquals(1.6f, android.provider.Settings.System.getFloat(app.contentResolver,"font_scale",1f),0.01f)
        val manager = app.getSystemService(android.app.LocaleManager::class.java)
        val before = manager.applicationLocales
        val systemBefore = manager.systemLocales
        val prefix = "storage-language-${UUID.randomUUID()}-"
        val names = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val scoped = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences((prefix+name).also { names += it },mode)
        }
        var scenario: ActivityScenario<ComponentActivity>? = null
        try {
            val values = org.json.JSONObject(instrumentation.context.assets.open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
            val tags = values.keys().asSequence().toList().sorted(); assertEquals(25,tags.size)
            for(tag in tags) {
                manager.applicationLocales = LocaleList.forLanguageTags(tag)
                scenario = ActivityScenario.launch(android.content.Intent(app,ComponentActivity::class.java))
                lateinit var activity: ComponentActivity
                lateinit var model: StorageViewModel
                fun attach() {
                    scenario!!.onActivity { current ->
                        activity = current
                        assertEquals(tag,current.resources.configuration.locales[0].toLanguageTag())
                        assertEquals(1.6f,current.resources.configuration.fontScale,0.01f)
                        model = ViewModelProvider(current,object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T {
                                require(modelClass==StorageViewModel::class.java)
                                return StorageViewModel(scoped) as T
                            }
                        })[StorageViewModel::class.java]
                        current.setContent { SyncMdTheme(darkTheme=tag in listOf("ar","he","ja")) { StorageScreen({},model) } }
                    }
                }
                fun label(id:Int,scroll:Boolean=true) {
                    val node = compose.onNodeWithText(activity.getString(id),useUnmergedTree=true)
                    if(scroll)node.performScrollTo()
                    node.assertIsDisplayed(); fits(node)
                }
                fun header() {
                    label(R.string.ui_clone_storage_47cafc8a,false)
                    val bounds=compose.onNodeWithContentDescription(activity.getString(R.string.publication_back)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val density=activity.resources.displayMetrics.density
                    assertTrue(bounds.width>=48*density-1 && bounds.height>=48*density-1)
                }
                attach();compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.current!=null }
                assertNull(model.state.value.error);assertEquals(RepositoryLocations.APP,model.state.value.current!!.uri)
                header()
                val pathNode=compose.onNodeWithTag("storage-default-path").performScrollTo().assertIsDisplayed()
                val pathLayouts=mutableListOf<TextLayoutResult>()
                pathNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(pathLayouts) }
                assertTrue("Filesystem paths must read left to right in $tag",pathLayouts.isNotEmpty() && pathLayouts.all {
                    it.getParagraphDirection(0)==androidx.compose.ui.text.style.ResolvedTextDirection.Ltr
                })
                label(R.string.ui_choose_the_default_folder_for_new_clones_e_9782dc64)
                label(R.string.ui_current_default_d914f86e)
                label(R.string.ui_choose_a_device_folder_d00b7daf)
                label(R.string.ui_choose_local_device_or_attached_storage_cl_69bc112f)
                compose.onNodeWithTag("storage-use-app").performScrollTo().assertIsDisplayed().performClick()
                compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.message!=null }
                assertNull(model.state.value.error);assertEquals(StorageViewModel.Message.APP_STORAGE_SAVED,model.state.value.message)
                val rows=values.getJSONArray(tag)
                val expected=(0 until rows.length()).map { rows.getJSONObject(it) }
                    .single { it.getString("name")=="storage_app_saved" }.getString("source")
                assertEquals(expected,activity.getString(R.string.storage_app_saved))
                compose.onNodeWithTag("storage-message").performScrollTo().assertTextEquals(expected).assertIsDisplayed();fits(compose.onNodeWithTag("storage-message"))
                val retained=model;val oldActivity=activity
                scenario!!.recreate();attach();assertNotSame(oldActivity,activity);assertSame(retained,model)
                header();compose.onNodeWithTag("storage-message").performScrollTo().assertTextEquals(expected).assertIsDisplayed();fits(compose.onNodeWithTag("storage-message"))
                assertEquals(RepositoryLocations.APP,RepositoryLocations(scoped).defaultSelection())
                if(tag in listOf("ar","de","ja")) {
                    compose.waitForIdle()
                    CommittedScreenshot.save(instrumentation,activity.window.decorView,File(app.filesDir,"parity-evidence/storage-language-$tag.png").apply { parentFile!!.mkdirs() })
                }
                scenario!!.close();scenario=null
                instrumentation.sendStatus(2,android.os.Bundle().apply { putString("stream","storage_locale=$tag; states=default,saved,recreated-saved; font_scale=1.6; folder_grants=0\n") })
            }
        } finally {
            scenario?.close();manager.applicationLocales=before
            assertEquals(systemBefore,manager.systemLocales)
            names.forEach { app.deleteSharedPreferences(it) }
        }
    }
}

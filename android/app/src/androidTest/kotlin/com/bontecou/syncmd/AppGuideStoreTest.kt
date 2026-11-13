package com.bontecou.syncmd

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.onboarding.*
import com.bontecou.syncmd.ui.viewmodels.AppGuideViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class AppGuideStoreTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var name: String
    private lateinit var prefs: SharedPreferences
    @Before fun setup() { name = "guide-store-${UUID.randomUUID()}"; prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE) }
    @After fun cleanup() { context.deleteSharedPreferences(name) }
    @Test fun freshTourResumesAndCompletionRetainsTargetUntilAcknowledged() {
        val store = AppGuideStore(prefs, 8) { false }
        assertFalse(store.load().completed)
        store.page(2)
        assertEquals(2, AppGuideStore(prefs, 8) { false }.load().page)
        val completed = store.complete(GuideTarget.URL)
        assertTrue(completed.completed); assertEquals(GuideTarget.URL, completed.pending)
        val restarted = AppGuideStore(prefs, 8) { false }
        assertEquals(GuideTarget.URL, restarted.load().pending)
        restarted.consumeTarget(); assertNull(restarted.load().pending)
        assertTrue(restarted.load().completed)
    }
    @Test fun existingInstallSkipsTourAndFutureReleaseIsAcknowledgedOnce() {
        val installed = AppGuideStore(prefs, 8) { true }
        assertTrue(installed.load().completed); assertFalse(installed.load().showNotes)
        val updated = AppGuideStore(prefs, 9) { false }
        assertTrue(updated.load().completed); assertTrue(updated.load().showNotes)
        updated.notesSeen(); assertFalse(AppGuideStore(prefs, 9) { false }.load().showNotes)
        assertFalse(AppGuideStore(prefs, 8) { false }.load().showNotes)
    }
    @Test fun failedSavingDoesNotTrapTheUserInTheTour() {
        var failWrites = true
        val failing = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putBoolean(key: String?, value: Boolean) = apply { delegate.putBoolean(key, value) }
                    override fun putInt(key: String?, value: Int) = apply { delegate.putInt(key, value) }
                    override fun putString(key: String?, value: String?) = apply { delegate.putString(key, value) }
                    override fun remove(key: String?) = apply { delegate.remove(key) }
                    override fun commit() = if (failWrites) false else delegate.commit()
                }
            }
        }
        lateinit var vm: AppGuideViewModel
        instrumentation.runOnMainSync { vm = AppGuideViewModel(AppGuideStore(failing, 8) { false }) }
        fun idle() = runBlocking { withTimeout(10_000) { vm.state.first { it.ready && !it.busy } } }
        idle(); assertTrue(vm.state.value.error)
        instrumentation.runOnMainSync { vm.page(1) }
        idle(); assertEquals(1, vm.state.value.progress.page)
        instrumentation.runOnMainSync { vm.complete(GuideTarget.HOME) }
        idle(); assertTrue(vm.state.value.progress.completed); assertTrue(vm.state.value.error)
        assertTrue(prefs.all.isEmpty())
        failWrites = false
        instrumentation.runOnMainSync { vm.consumeTarget() }; idle()
        assertTrue(vm.state.value.progress.completed); assertTrue(vm.state.value.error)
        instrumentation.runOnMainSync { vm.notesSeen() }; idle()
        assertTrue(vm.state.value.progress.completed)
        instrumentation.runOnMainSync { ViewModelStore().apply { put("guide", vm); clear() } }
    }
    @Test fun feedbackDraftContainsOnlyAppAndDeviceMetadataAndNeverSends() {
        val intent = SupportLinks.feedbackIntent()
        assertEquals(android.content.Intent.ACTION_SENDTO, intent.action)
        val address = intent.data.toString()
        assertTrue(address.startsWith("mailto:cody@isolated.tech?"))
        val decoded = android.net.Uri.decode(address)
        assertTrue(decoded.contains(BuildConfig.VERSION_NAME)); assertTrue(decoded.contains("Android API:"))
        assertFalse(decoded.contains("token=")); assertFalse(decoded.contains("repository"))
        assertEquals(SupportLinks.community, SupportLinks.communityIntent().data.toString())
    }
    @Test fun incompleteSampleReportsFailureWithoutChangingItsFiles() = runBlocking {
        val prefix = "guide-sample-error-${UUID.randomUUID()}"
        val root = java.io.File(context.filesDir,prefix)
        val names = mutableSetOf<String>()
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getFilesDir() = java.io.File(root,"files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = java.io.File(root,"no_backup").apply { mkdirs() }
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences {
                val scoped = "$prefix-$key"; names += scoped
                return context.getSharedPreferences(scoped,mode)
            }
        }
        val occupied = java.io.File(isolated.filesDir,"sample-workspace/keep.txt").apply { parentFile.mkdirs(); writeText("Keep this file") }
        lateinit var vm: com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
        var created = false
        var opened = false
        try {
            instrumentation.runOnMainSync { vm = com.bontecou.syncmd.ui.viewmodels.SettingsViewModel(isolated); created=true; vm.openSampleRepository { opened=true } }
            withTimeout(10_000) { vm.sampleError.first { it != null }; vm.errorMessage.first { it != null }; vm.isOpeningSample.first { !it } }
            assertFalse(opened); assertTrue(vm.errorMessage.value!!.contains("sample repository"))
            assertEquals("Keep this file",occupied.readText())
        } finally {
            if (created) instrumentation.runOnMainSync { ViewModelStore().apply { put("settings",vm); clear() } }
            root.deleteRecursively(); names.forEach { context.deleteSharedPreferences(it) }
        }
    }
}

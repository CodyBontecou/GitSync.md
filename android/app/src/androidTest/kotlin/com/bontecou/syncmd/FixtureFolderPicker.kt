package com.bontecou.syncmd

import android.os.Bundle
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import java.util.Locale
import java.util.regex.Pattern

/** Observed native DocumentsUI variants; the caller must verify the exact persisted folder grant. */
internal object FixtureFolderPicker {
    fun confirm(compose: ComposeContentTestRule, resultAvailable: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val choice = device.wait(Until.findObject(By.text(Pattern.compile("^(?:use this folder|select)$", Pattern.CASE_INSENSITIVE))),10_000)
        assertNotNull("Native folder confirmation must be visible",choice)
        requireNotNull(choice)
        assertTrue("Confirmation must belong to DocumentsUI",choice.applicationPackage.endsWith(".documentsui"))
        assertTrue("Selected fixture folder must be selectable",choice.isEnabled)
        val label=choice.text.lowercase(Locale.ROOT)
        choice.click()
        val allow=By.text(Pattern.compile("^allow$",Pattern.CASE_INSENSITIVE))
        // A caller may already hold a grant from an earlier picker visit. That
        // prior result cannot prove this DocumentsUI handoff has completed.
        val packageName=instrumentation.targetContext.packageName
        fun returned()=device.currentPackageName==packageName && resultAvailable()
        compose.waitUntil(5_000) { returned() || device.hasObject(allow) }
        val completion=if(returned()) "direct" else {
            val permission=device.findObject(allow)
            assertNotNull("Native folder access confirmation must be visible",permission)
            assertTrue(requireNotNull(permission).isEnabled);permission.click()
            "allow"
        }
        assertTrue("Folder picker must return to the app",device.wait(Until.hasObject(By.pkg(packageName)),5_000))
        instrumentation.sendStatus(2,Bundle().apply { putString("stream","folder_picker_confirmation=$label; completion=$completion\n") })
    }
}

package com.bontecou.syncmd

import android.view.View
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowInsetsCompat
import com.bontecou.syncmd.ui.theme.M3ModalSheet
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Run with both gestural and three-button system navigation. Checks screen, not clipped semantics bounds. */
class ModalSystemBarsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun sheetFooterIsAboveSystemNavigationAndRemainsClickable() {
        var dialogView: View? = null
        var footerBottom = 0
        var clicked = false
        val dark = mutableStateOf(false)
        val longBody = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (dark.value) 1.6f else 1f)) {
                SyncMdTheme(darkTheme = dark.value) {
                    M3ModalSheet(onDismissRequest = {}, title = "Merge with remote", footer = {
                        val view = LocalView.current
                        SideEffect { dialogView = view }
                        M3TextButton("Merge and push", {})
                        M3TextButton("Merge locally", {})
                        M3TextButton("Cancel", { clicked = true }, Modifier.testTag("cancel").onGloballyPositioned {
                            footerBottom = it.positionInWindow().y.toInt() + it.size.height
                        })
                    }) {
                        Text(if (longBody.value) "Review changes before merging.\n".repeat(80) else "Review changes before merging.")
                    }
                }
            }
        }
        for (largeAndDark in listOf(false, true)) {
            for (scrolling in listOf(false, true)) {
                compose.runOnIdle { dark.value = largeAndDark; longBody.value = scrolling; clicked = false }
                compose.waitForIdle()
                compose.runOnIdle {
                    val activity = compose.activity.window.decorView
                    val insets = WindowInsetsCompat.toWindowInsetsCompat(activity.rootWindowInsets, activity)
                        .getInsets(WindowInsetsCompat.Type.systemBars())
                    assertTrue("Test requires visible navigation bars", insets.bottom > 0)
                    val activityAt = IntArray(2).also(activity::getLocationOnScreen)
                    val dialogAt = IntArray(2).also(requireNotNull(dialogView).rootView::getLocationOnScreen)
                    val safeBottom = activityAt[1] + activity.height - insets.bottom
                    val actualBottom = dialogAt[1] + footerBottom
                    assertTrue("Footer bottom $actualBottom overlaps system navigation starting at $safeBottom", actualBottom <= safeBottom)
                    assertEquals("System bars must be reserved once, retaining the 24dp footer spacing",
                        24f * activity.resources.displayMetrics.density, (safeBottom - actualBottom).toFloat(), 1f)
                }
                compose.onNodeWithTag("cancel").performClick()
                compose.runOnIdle { assertTrue(clicked) }
            }
        }
    }
}

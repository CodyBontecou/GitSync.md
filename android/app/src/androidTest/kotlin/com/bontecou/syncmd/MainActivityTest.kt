package com.bontecou.syncmd

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bontecou.syncmd.presentation.theme.SyncMdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RED 🔴 Compose UI tests for A0.1: MainActivity and HomeScreen display
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `homescreen displays welcome text`() {
        composeTestRule.setContent {
            SyncMdTheme {
                HomeScreen()
            }
        }

        composeTestRule
            .onNodeWithText("Welcome to Sync.md")
            .assertExists()
    }
}

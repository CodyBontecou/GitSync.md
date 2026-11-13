package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.onboarding.GuideTarget
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.SyncMdTheme

/**
 * The app tour, replayed from Settings: the "How it works" content on one scrolling screen. First run
 * no longer uses a tour; it shows [OnboardingScreen]. The page/finish parameters are kept so existing
 * routes compile; on first run (replay = false) this falls back to the onboarding screen.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AppTourScreen(page: Int, busy: Boolean, error: Boolean, replay: Boolean, onPage: (Int) -> Unit,
    onFinish: (GuideTarget) -> Unit, onClose: () -> Unit = {}) {
    if (!replay) { OnboardingScreen(busy, error, onFinish); return }
    BackHandler(onBack = onClose)
    val c = M3.colors
    Column(Modifier.fillMaxSize().background(c.surface).testTag("app-tour")) {
        M3TopBar(stringResource(R.string.m3home_how_title), onBack = onClose, backLabel = stringResource(R.string.guide_back),
            modifier = Modifier.testTag("tour-header"))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            HowItWorksContent(Modifier.widthIn(max = 720.dp).padding(horizontal = 20.dp, vertical = 16.dp).navigationBarsPadding())
        }
    }
}

@Preview(name = "How it works · replay", widthDp = 412, heightDp = 915) @Composable
private fun TourReplayPreview() = SyncMdTheme(darkTheme = false) { AppTourScreen(0, false, false, replay = true, onPage = {}, onFinish = {}) }

@Preview(name = "How it works · replay · dark", widthDp = 412, heightDp = 915) @Composable
private fun TourReplayDarkPreview() = SyncMdTheme(darkTheme = true) { AppTourScreen(0, false, false, replay = true, onPage = {}, onFinish = {}) }

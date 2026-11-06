package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.onboarding.GuideTarget
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3RailState
import com.bontecou.syncmd.ui.theme.M3SyncIllustration
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.M3Wordmark
import com.bontecou.syncmd.ui.theme.SyncMdTheme

/**
 * First run (Editor board 10 · Onboarding): one screen with three ways in. GitHub opens sign-in,
 * "SSH, token or another host" opens the custom-remote clone screen, and the demo vault opens the
 * on-device sample repository (created on first use). [onFinish] records the choice; the app shell
 * routes to it once the first-run gate closes.
 */
@Composable
fun OnboardingScreen(busy: Boolean, error: Boolean, onFinish: (GuideTarget) -> Unit) {
    val c = M3.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(c.surface).testTag("onboarding")) {
        val minHeight = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = minHeight)
                    .padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 28.dp),
            ) {
                M3Wordmark()
                if (busy) M3IndeterminateProgressBar(Modifier.padding(top = 8.dp))
                M3SyncIllustration(
                    startLabel = stringResource(R.string.m3home_onboarding_this_phone),
                    endLabel = stringResource(R.string.m3home_rail_github),
                    state = M3RailState.Onboarding,
                    modifier = Modifier.padding(top = if (busy) 44.dp else 48.dp).background(c.inv, RoundedCornerShape(28.dp))
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    description = stringResource(R.string.m3home_onboarding_rail),
                )
                Text(
                    buildAnnotatedString {
                        append(stringResource(R.string.m3home_onboarding_headline))
                        append("\n")
                        withStyle(SpanStyle(color = c.onVar)) { append(stringResource(R.string.m3home_onboarding_accent)) }
                    },
                    style = M3Type.displayMedium, color = c.on,
                    modifier = Modifier.padding(top = 40.dp).semantics { heading() },
                )
                Text(stringResource(R.string.m3home_onboarding_body), style = M3Type.body, color = c.onVar, modifier = Modifier.padding(top = 16.dp))
                if (error) M3Note(stringResource(R.string.m3home_onboarding_save_error), Modifier.padding(top = 16.dp), tone = M3NoteTone.Warn)
                Spacer(Modifier.weight(1f).heightIn(min = 32.dp))
                M3Button(stringResource(R.string.m3home_onboarding_github), { onFinish(GuideTarget.LOGIN) },
                    Modifier.fillMaxWidth().padding(top = 32.dp).testTag("onboarding-github"), style = M3ButtonStyle.Filled, size = M3ButtonSize.Hero, enabled = !busy)
                M3Button(stringResource(R.string.m3home_onboarding_other), { onFinish(GuideTarget.URL) },
                    Modifier.fillMaxWidth().padding(top = 8.dp).testTag("onboarding-other-host"), style = M3ButtonStyle.Outlined, size = M3ButtonSize.Large, enabled = !busy)
                M3Button(stringResource(R.string.m3home_onboarding_demo), { onFinish(GuideTarget.SAMPLE) },
                    Modifier.fillMaxWidth().padding(top = 4.dp).testTag("onboarding-demo"), style = M3ButtonStyle.Text, size = M3ButtonSize.Large, enabled = !busy)
            }
        }
    }
}

@Preview(name = "10 Onboarding · light", widthDp = 412, heightDp = 915) @Composable
private fun OnboardingLightPreview() = SyncMdTheme(darkTheme = false) { OnboardingScreen(busy = false, error = false, onFinish = {}) }

@Preview(name = "10 Onboarding · dark", widthDp = 412, heightDp = 915) @Composable
private fun OnboardingDarkPreview() = SyncMdTheme(darkTheme = true) { OnboardingScreen(busy = false, error = true, onFinish = {}) }

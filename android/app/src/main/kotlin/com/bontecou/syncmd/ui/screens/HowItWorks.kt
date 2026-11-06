package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Badge
import com.bontecou.syncmd.ui.theme.M3Hero
import com.bontecou.syncmd.ui.theme.M3HeroStyle
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3RailState
import com.bontecou.syncmd.ui.theme.M3SyncIllustration
import com.bontecou.syncmd.ui.theme.M3Tag
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.m3TopDivider

/**
 * "How it works": the former four-page app tour as one scrolling section (clone, edit, review and
 * publish, then the ways to start). First run uses [OnboardingScreen]; About & help embeds this.
 * The caller provides scrolling and horizontal padding.
 */
@Composable
fun HowItWorksContent(modifier: Modifier = Modifier) {
    val steps = listOf(
        R.string.guide_clone_title to R.string.guide_clone_body,
        R.string.guide_edit_title to R.string.guide_edit_body,
        R.string.guide_git_title to R.string.guide_git_body,
        R.string.guide_start_title to R.string.guide_start_body,
    )
    Column(modifier.fillMaxWidth().testTag("how-it-works"), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        steps.forEachIndexed { index, (title, body) ->
            Column(Modifier.testTag("how-it-works-step-$index")) {
                Text(stringResource(R.string.guide_step, index + 1), style = M3Type.section, color = M3.colors.primary)
                if (index < 3) HowItWorksVignette(index, Modifier.padding(top = 12.dp))
                Text(stringResource(title), style = M3Type.headlineSmall, color = M3.colors.on,
                    modifier = Modifier.padding(top = 16.dp).semantics { heading() })
                Text(stringResource(body), style = M3Type.body, color = M3.colors.onVar, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** Small decorative scenes per step, built from kit parts (rail, file rows, a vault row). */
@Composable
private fun HowItWorksVignette(index: Int, modifier: Modifier = Modifier) {
    val c = M3.colors
    Box(modifier.fillMaxWidth().clearAndSetSemantics {}) {
        when (index) {
            0 -> M3Hero(style = M3HeroStyle.Inverse) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("notes", style = M3Type.title, color = c.onInv, modifier = Modifier.weight(1f))
                    Text("main", style = M3Type.meta, color = c.invMeta)
                }
                M3SyncIllustration(stringResource(R.string.m3home_onboarding_this_phone), stringResource(R.string.m3home_rail_github),
                    M3RailState.InSync, Modifier.padding(top = 18.dp), labelsInline = true)
            }
            1 -> M3ListCard {
                listOf("daily/2026-11-02.md" to true, "ideas.md" to true, "README.md" to false).forEachIndexed { i, (path, edited) ->
                    Row(Modifier.fillMaxWidth().m3TopDivider(i > 0, c.outlineVar).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(8.dp).background(if (edited) c.ahead else c.outlineVar, CircleShape))
                        Text(path, style = M3Type.code, color = c.on, modifier = Modifier.weight(1f))
                        if (edited) M3Tag("M", tone = M3Tone.Ahead)
                    }
                }
            }
            else -> M3ListCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("notes", style = M3Type.title, color = c.on, modifier = Modifier.weight(1f))
                        M3Badge("↑3", M3Tone.Ahead)
                    }
                    M3SyncIllustration(stringResource(R.string.m3home_onboarding_this_phone), stringResource(R.string.m3home_rail_github),
                        M3RailState.Pending, Modifier.padding(top = 16.dp), onHero = false, background = c.cLow, labelsInline = true)
                }
            }
        }
    }
}

@Preview(name = "How it works", widthDp = 412, heightDp = 1400) @Composable
private fun HowItWorksPreview() = SyncMdTheme(darkTheme = false) {
    Box(Modifier.background(M3.colors.surface).padding(16.dp)) { HowItWorksContent() }
}

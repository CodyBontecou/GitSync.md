package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Hero
import com.bontecou.syncmd.ui.theme.M3HeroHeadline
import com.bontecou.syncmd.ui.theme.M3LeadingTile
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.m3TopDivider

/**
 * "How GitSync works" (AboutHelp › tour): the inverse hero and the four numbered steps.
 * Fallback for the settings branch; the home builder's version of this file takes precedence.
 */
@Composable
fun HowItWorksContent() {
    val c = M3.colors
    Column(Modifier.fillMaxWidth()) {
        M3Hero(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            M3HeroHeadline(stringResource(R.string.settings_m3_how_headline), accent = stringResource(R.string.settings_m3_how_accent),
                accentOnNewLine = true, style = M3Type.headlineSmall)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.settings_m3_how_phone), style = M3Type.metaSmall, color = c.invMeta)
                Text(stringResource(R.string.settings_m3_how_github), style = M3Type.metaSmall, color = c.invMeta)
            }
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(14.dp).clearAndSetSemantics {}, contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(2.dp).background(c.invLine))
                Box(Modifier.size(14.dp).background(c.invAhead, CircleShape))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Box(Modifier.size(8.dp).background(c.invAhead, CircleShape)) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { Box(Modifier.size(14.dp).background(c.invBehind, CircleShape)) }
            }
        }
        val steps = listOf(
            Triple(R.drawable.ic_m3_phone, R.string.settings_m3_how_step1_title, R.string.settings_m3_how_step1_body),
            Triple(R.drawable.ic_m3_edit, R.string.settings_m3_how_step2_title, R.string.settings_m3_how_step2_body),
            Triple(R.drawable.ic_m3_publish, R.string.settings_m3_how_step3_title, R.string.settings_m3_how_step3_body),
            Triple(R.drawable.ic_m3_sync, R.string.settings_m3_how_step4_title, R.string.settings_m3_how_step4_body),
        )
        M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            steps.forEachIndexed { index, (icon, title, body) ->
                Row(Modifier.fillMaxWidth().m3TopDivider(index > 0, c.outlineVar).padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    M3LeadingTile(icon)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_m3_how_step_of, index + 1, steps.size),
                            style = M3Type.tag.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = c.primary)
                        Text(stringResource(title), style = M3Type.bodyStrong, color = c.on)
                        Text(stringResource(body), style = M3Type.bodyMedium, color = c.onVar)
                    }
                }
            }
        }
    }
}

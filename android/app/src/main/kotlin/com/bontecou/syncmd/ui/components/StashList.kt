package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.m3TopDivider

/**
 * One saved stash (Stashes board): `stash@{n}` in tertiary with its age, the message, where it was
 * made ("On draft/pipeline · 3f2a910 · 2 files"), then Apply, Pop and Drop.
 */
@Composable
fun StashRow(
    id: String,
    message: String,
    meta: String,
    whenText: String,
    divider: Boolean,
    enabled: Boolean,
    onApply: () -> Unit,
    onPop: () -> Unit,
    onDrop: () -> Unit,
) {
    val c = M3.colors
    Column(Modifier.fillMaxWidth().m3TopDivider(divider, c.outlineVar).testTag("git-stash-$id").padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(id, Modifier.weight(1f), style = M3Type.metaStrong, color = c.tertiary)
            Text(whenText, style = M3Type.meta, color = c.onVar)
        }
        Text(message, Modifier.padding(top = 4.dp), style = M3Type.bodyStrong, color = c.on, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (meta.isNotEmpty()) Text(meta, style = M3Type.meta, color = c.onVar)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            M3Button(stringResource(R.string.git_m3_apply), onApply, Modifier.testTag("git-stash-apply"), style = M3ButtonStyle.Tonal, size = M3ButtonSize.Small, enabled = enabled)
            M3Button(stringResource(R.string.git_m3_pop), onPop, Modifier.testTag("git-stash-pop"), style = M3ButtonStyle.Outlined, size = M3ButtonSize.Small, enabled = enabled)
            Spacer(Modifier.weight(1f))
            GitDeleteButton(stringResource(R.string.git_m3_drop_cd, id), onDrop, Modifier.testTag("git-stash-drop"), enabled = enabled)
        }
    }
}

/** "What each does": Apply, Pop and Drop (in error). */
@Composable
fun StashLegend(modifier: Modifier = Modifier) {
    val c = M3.colors
    M3ListCard(modifier) {
        GitLegendRow(stringResource(R.string.git_m3_apply), stringResource(R.string.git_m3_apply_help), c.on, divider = false)
        GitLegendRow(stringResource(R.string.git_m3_pop), stringResource(R.string.git_m3_pop_help), c.on, divider = true)
        GitLegendRow(stringResource(R.string.git_m3_drop), stringResource(R.string.git_m3_drop_help), c.error, divider = true)
    }
}

@Preview(name = "Stash rows · light")
@Composable
private fun StashRowsPreview() = SyncMdTheme(darkTheme = false) {
    Column(Modifier.padding(16.dp)) {
        M3ListCard { StashRow("stash@{0}", "Speaker tags half done", "On draft/pipeline · 3f2a910 · 2 files", "Oct 29", false, true, {}, {}, {}) }
        StashLegend(Modifier.padding(top = 16.dp))
    }
}

@Preview(name = "Stash rows · dark")
@Composable
private fun StashRowsDarkPreview() = SyncMdTheme(darkTheme = true) {
    Column(Modifier.padding(16.dp)) {
        M3ListCard { StashRow("stash@{0}", "Speaker tags half done", "On draft/pipeline · 3f2a910 · 2 files", "Oct 29", false, true, {}, {}, {}) }
        StashLegend(Modifier.padding(top = 16.dp))
    }
}

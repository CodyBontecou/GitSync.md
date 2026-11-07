package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.ConflictSide
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideType

/**
 * Rename/rename picker (iOS "Pick a filename"): two path cards, the chosen
 * one ringed in ink. [oursIsYours] colours the sides — during a rebase Git's
 * "ours" is the upstream, so callers pass false there.
 */
@Composable
fun ConflictFilenameChoices(
    conflict: Conflict,
    resolutionPath: String?,
    enabled: Boolean,
    onResolutionPathChange: ((String) -> Unit)?,
    oursLabel: Int = R.string.tide_merge_ours_filename,
    theirsLabel: Int = R.string.tide_merge_theirs_filename,
    oursIsYours: Boolean = true,
) {
    if (!conflict.isRenameRename) return
    val c = Tide.colors
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.conflict_choose_filename), style = TideType.sans(17.sp, FontWeight.SemiBold), color = c.ink)
        Text(stringResource(R.string.conflict_filename_cleanup), style = TideType.sans(14.sp), color = c.inkMid)
        listOf("ours" to requireNotNull(conflict.ours).path, "theirs" to requireNotNull(conflict.theirs).path).forEach { (role, path) ->
            val selected = (resolutionPath ?: conflict.ours?.path) == path
            val canChoose = enabled && onResolutionPathChange != null
            val yours = (role == "ours") == oursIsYours
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(c.card, shape)
                    .border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.line, shape)
                    .selectable(selected = selected, enabled = canChoose, role = Role.RadioButton,
                        onClick = { onResolutionPathChange?.invoke(path) })
                    .testTag("conflict-filename-$role")
                    .alpha(if (canChoose || selected) 1f else 0.6f)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Radio mark drawn in Tide ink.
                Box(Modifier.size(20.dp).border(2.dp, if (selected) c.ink else c.lineSoft, CircleShape), contentAlignment = Alignment.Center) {
                    if (selected) Box(Modifier.size(10.dp).background(c.ink, CircleShape))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GitWorkSideDot(yours)
                        Text(stringResource(if (role == "ours") oursLabel else theirsLabel),
                            style = TideType.sans(14.sp, FontWeight.SemiBold), color = c.ink)
                    }
                    Text(path, style = TideType.mono(12.5.sp).copy(textDirection = TextDirection.Ltr), color = c.inkMid)
                }
            }
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

internal val gitWorkPreviewRename = Conflict(
    filePath = "notes/plan.md", currentContent = "Plan\n", incomingContent = "Plan v2\n",
    ours = ConflictSide("notes/plan-final.md", "a1", 0x81a4), theirs = ConflictSide("notes/roadmap.md", "b2", 0x81a4),
)

@Composable
private fun FilenamePreviewBody() {
    var path by remember { mutableStateOf<String?>(null) }
    Box(Modifier.background(Tide.colors.paper).padding(16.dp)) {
        ConflictFilenameChoices(gitWorkPreviewRename, path, true, { path = it })
    }
}

@Preview(name = "Filename choice · light") @Composable
private fun FilenameLight() = SyncMdTheme(darkTheme = false) { FilenamePreviewBody() }

@Preview(name = "Filename choice · dark") @Composable
private fun FilenameDark() = SyncMdTheme(darkTheme = true) { FilenamePreviewBody() }

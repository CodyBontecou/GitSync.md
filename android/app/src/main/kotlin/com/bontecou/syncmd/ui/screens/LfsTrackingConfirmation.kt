package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.LfsDotPathRow
import com.bontecou.syncmd.ui.theme.*

/**
 * Board "Large files" › trackLfs / keepGit sheet. The paths belong to the frozen confirmation,
 * independently of later screen selection; a changed plan disables Apply and says so.
 */
@Composable
internal fun LfsTrackingConfirmation(selected: Set<String>, useLfs: Boolean, canApply: Boolean,
    onCancel: () -> Unit, onConfirm: () -> Unit) {
    val c = M3.colors
    M3ModalSheet(onDismissRequest = onCancel, title = stringResource(if (useLfs) R.string.lfs_m3_track_title else R.string.lfs_m3_keep_title),
        modifier = Modifier.testTag("lfs-tracking-title"), footer = {
            M3FilledButton(stringResource(R.string.lfs_m3_apply_tracking), onConfirm, Modifier.fillMaxWidth().testTag("lfs-confirm-tracking"),
                size = M3ButtonSize.Hero, enabled = canApply && selected.isNotEmpty())
            M3TextButton(stringResource(R.string.lfs_m3_cancel), onCancel, Modifier.fillMaxWidth().testTag("lfs-cancel-tracking"))
        }) {
        Column(Modifier.fillMaxWidth().testTag("lfs-tracking-review")) {
            Text(pluralStringResource(R.plurals.lfs_m3_file_count, selected.size, selected.size), style = M3Type.count,
                color = if (useLfs) c.tertiary else c.on, modifier = Modifier.padding(top = 8.dp).testTag("lfs-tracking-count"))
            Text(stringResource(if (useLfs) R.string.lfs_m3_track_body else R.string.lfs_m3_keep_body), style = M3Type.bodyMedium, color = c.onVar,
                modifier = Modifier.padding(top = 8.dp).testTag("lfs-tracking-scope"))
            M3InsetList(Modifier.padding(top = 16.dp)) {
                selected.sorted().forEachIndexed { index, path -> LfsDotPathRow(path, index > 0, Modifier.testTag("lfs-tracking-path:$path")) }
            }
            if (!canApply) Text(stringResource(R.string.lfs_m3_stale), style = M3Type.bodySmall, color = c.error,
                modifier = Modifier.padding(top = 12.dp).testTag("lfs-tracking-stale"))
        }
    }
}

@Preview(name = "Track with LFS · light")
@Composable
private fun LfsTrackingLfsPreview() = SyncMdTheme(darkTheme = false) {
    LfsTrackingConfirmation(setOf("audio/2026-11-02-standup.m4a", "attachments/whiteboard-v2.png"), useLfs = true, canApply = true, onCancel = {}, onConfirm = {})
}

@Preview(name = "Keep in Git · stale · dark")
@Composable
private fun LfsTrackingGitPreview() = SyncMdTheme(darkTheme = true) {
    LfsTrackingConfirmation(setOf("audio/2026-11-02-standup.m4a"), useLfs = false, canApply = false, onCancel = {}, onConfirm = {})
}

package com.bontecou.syncmd.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.repositories.GitTrackingRemovalPlan
import com.bontecou.syncmd.ui.components.VaultOpsBanner
import com.bontecou.syncmd.ui.components.VaultOpsButton
import com.bontecou.syncmd.ui.components.VaultOpsDangerIcon
import com.bontecou.syncmd.ui.components.VaultOpsSheet
import com.bontecou.syncmd.ui.components.VaultOpsTextButton
import com.bontecou.syncmd.ui.components.VaultOpsToggleRow
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TideType

/** Step one of "Stop tracking": what stays, what goes, and the backup choice (iOS RemoveGitTrackingView). */
@Composable
internal fun TrackingRemovalReview(review: GitTrackingRemovalPlan, keepBackup: Boolean, busy: Boolean,
    onKeepBackup: (Boolean) -> Unit, onContinue: () -> Unit, onCancel: () -> Unit) {
    TrackingRemovalReviewSheet(review.path, review.branch, Formatter.formatFileSize(LocalContext.current, review.metadataBytes),
        keepBackup, busy, onKeepBackup, onContinue, onCancel)
}

@Composable
private fun TrackingRemovalReviewSheet(path: String, branch: String?, size: String, keepBackup: Boolean, busy: Boolean,
    onKeepBackup: (Boolean) -> Unit, onContinue: () -> Unit, onCancel: () -> Unit) {
    val c = Tide.colors
    VaultOpsSheet(
        onDismissRequest = { if (!busy) onCancel() },
        title = stringResource(R.string.ui_remove_git_tracking_1f8255d9),
        titleModifier = Modifier.testTag("tracking-review-title"),
        icon = { VaultOpsDangerIcon() },
        confirmButton = {
            VaultOpsButton(stringResource(R.string.guide_continue), onContinue, Modifier.testTag("tracking-review-continue"), enabled = !busy)
        },
        dismissButton = {
            VaultOpsTextButton(stringResource(R.string.publication_cancel), onCancel, Modifier.fillMaxWidth().testTag("tracking-review-cancel"), enabled = !busy)
        },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("tracking-review-scroll"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.ui_working_files_stay_in_place_local_branches_a0748350), modifier = Modifier.testTag("tracking-review-scope"))
            TideCard(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SelectionContainer {
                        Text(path, style = TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr), color = c.ink,
                            modifier = Modifier.testTag("tracking-review-path"))
                    }
                    Text(stringResource(R.string.callback_result_branch, branch ?: stringResource(R.string.repository_discovery_branch_unknown)),
                        style = TideType.mono(12.sp), color = c.inkMid, modifier = Modifier.testTag("tracking-review-branch"))
                    Text(stringResource(R.string.tracking_metadata_size, size), style = TideType.mono(12.sp), color = c.inkMid,
                        modifier = Modifier.testTag("tracking-review-size"))
                }
                TideHairline()
                Box(Modifier.padding(horizontal = 16.dp)) {
                    VaultOpsToggleRow(keepBackup, onKeepBackup, stringResource(R.string.ui_keep_a_verified_metadata_backup_5e821b62),
                        Modifier.testTag("tracking-review-backup"), enabled = !busy, labelModifier = Modifier.testTag("tracking-review-backup-label"))
                }
            }
            if (!keepBackup) VaultOpsBanner(stringResource(R.string.ui_without_a_backup_local_git_history_is_perm_bcb5c492),
                Modifier.testTag("tracking-review-warning"), danger = true)
        }
    }
}

@Preview(name = "Stop tracking · backup · light")
@Composable
private fun TrackingReviewBackupPreview() = SyncMdTheme(darkTheme = false) {
    TrackingRemovalReviewSheet("/storage/emulated/0/Documents/VoxVault", "main", "4.2 MB", true, false, {}, {}, {})
}

@Preview(name = "Stop tracking · no backup · dark")
@Composable
private fun TrackingReviewPermanentPreview() = SyncMdTheme(darkTheme = true) {
    TrackingRemovalReviewSheet("/storage/emulated/0/Documents/VoxVault", null, "4.2 MB", false, false, {}, {}, {})
}

@Preview(name = "Stop tracking · busy · light")
@Composable
private fun TrackingReviewBusyPreview() = SyncMdTheme(darkTheme = false) {
    TrackingRemovalReviewSheet("/storage/emulated/0/Documents/VoxVault", "main", "4.2 MB", true, true, {}, {}, {})
}

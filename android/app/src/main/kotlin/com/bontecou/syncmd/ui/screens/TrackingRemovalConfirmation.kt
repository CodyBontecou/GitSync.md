package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.GitDangerBadge
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ModalSheet
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3SheetSurface
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.TrackingRemovalRequest

/** Final "Stop tracking" consent (StopTracking board, steps confirm / confirmStale): warning, exact folder, one red button. */
@Composable
internal fun TrackingRemovalConfirmation(request: TrackingRemovalRequest, valid: Boolean, busy: Boolean,
    onCancel: () -> Unit, onConfirm: () -> Unit) {
    M3ModalSheet(onDismissRequest = { if (!busy) onCancel() }, modifier = Modifier.testTag("tracking-final-sheet"),
        footer = { ConfirmFooter(valid, busy, onCancel, onConfirm) }) {
        ConfirmBody(request.plan.path, request.keepBackup, valid)
    }
}

@Composable
private fun ConfirmFooter(valid: Boolean, busy: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    M3Button(stringResource(R.string.ui_remove_tracking_4b40d72c), onConfirm, Modifier.fillMaxWidth().testTag("tracking-final-confirm"),
        size = M3ButtonSize.Hero, destructive = true, enabled = valid && !busy, loading = busy)
    M3TextButton(stringResource(R.string.publication_cancel), onCancel, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("tracking-final-cancel"), enabled = !busy)
}

@Composable
private fun ConfirmBody(path: String, keepBackup: Boolean, valid: Boolean) {
    val c = M3.colors
    Column(Modifier.fillMaxWidth().testTag("tracking-final-review")) {
        GitDangerBadge(R.drawable.ic_m3_warning, Modifier.align(Alignment.CenterHorizontally))
        Text(stringResource(if (keepBackup) R.string.git_m3_tracking_backup_title else R.string.tracking_remove_permanent_title),
            Modifier.fillMaxWidth().padding(top = 12.dp).testTag("tracking-final-title"), style = M3Type.dialogTitle, color = c.on, textAlign = TextAlign.Center)
        Text(stringResource(if (keepBackup) R.string.git_m3_tracking_backup_body else R.string.git_m3_tracking_permanent_body),
            Modifier.padding(top = 12.dp).testTag("tracking-final-scope"), style = M3Type.bodyMedium, color = c.onVar)
        Text(path, Modifier.padding(top = 16.dp).fillMaxWidth().background(c.surface, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("tracking-final-path"), style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.on)
        if (!valid) M3Note(stringResource(R.string.git_m3_tracking_stale), Modifier.padding(top = 12.dp).testTag("tracking-final-stale"), tone = M3NoteTone.Warn)
    }
}

@Preview(name = "Stop tracking final · backup · light")
@Composable
private fun TrackingFinalBackupPreview() = SyncMdTheme(darkTheme = false) {
    M3SheetSurface(footer = { ConfirmFooter(true, false, {}, {}) }) { ConfirmBody("/storage/emulated/0/Documents/Obsidian/VoxVault", true, true) }
}

@Preview(name = "Stop tracking final · permanent · stale · dark")
@Composable
private fun TrackingFinalStalePreview() = SyncMdTheme(darkTheme = true) {
    M3SheetSurface(footer = { ConfirmFooter(false, false, {}, {}) }) { ConfirmBody("/storage/emulated/0/Documents/Obsidian/VoxVault", false, false) }
}

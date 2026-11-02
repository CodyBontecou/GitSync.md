package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.VaultOpsBanner
import com.bontecou.syncmd.ui.components.VaultOpsButton
import com.bontecou.syncmd.ui.components.VaultOpsButtonStyle
import com.bontecou.syncmd.ui.components.VaultOpsDangerIcon
import com.bontecou.syncmd.ui.components.VaultOpsDetail
import com.bontecou.syncmd.ui.components.VaultOpsSheet
import com.bontecou.syncmd.ui.components.VaultOpsTextButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.TrackingRemovalRequest

/** Final "Stop tracking" confirmation: danger glyph, exact path, one red pill. */
@Composable
internal fun TrackingRemovalConfirmation(request: TrackingRemovalRequest, valid: Boolean, busy: Boolean,
    onCancel: () -> Unit, onConfirm: () -> Unit) {
    TrackingRemovalConfirmationSheet(request.plan.path, request.keepBackup, valid, busy, onCancel, onConfirm)
}

@Composable
private fun TrackingRemovalConfirmationSheet(path: String, keepBackup: Boolean, valid: Boolean, busy: Boolean,
    onCancel: () -> Unit, onConfirm: () -> Unit) {
    VaultOpsSheet(
        onDismissRequest = { if (!busy) onCancel() },
        title = stringResource(if (keepBackup) R.string.tracking_remove_backup_title else R.string.tracking_remove_permanent_title),
        titleModifier = Modifier.testTag("tracking-final-title"),
        icon = { VaultOpsDangerIcon() },
        confirmButton = {
            VaultOpsButton(stringResource(R.string.ui_remove_tracking_4b40d72c), onConfirm, Modifier.testTag("tracking-final-confirm"),
                enabled = valid && !busy, style = VaultOpsButtonStyle.DANGER)
        },
        dismissButton = {
            VaultOpsTextButton(stringResource(R.string.publication_cancel), onCancel, Modifier.fillMaxWidth().testTag("tracking-final-cancel"), enabled = !busy)
        },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("tracking-final-review"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (keepBackup) R.string.tracking_remove_backup_body else R.string.tracking_remove_permanent_body),
                modifier = Modifier.testTag("tracking-final-scope"))
            VaultOpsDetail(path, textModifier = Modifier.testTag("tracking-final-path"))
            if (!valid) VaultOpsBanner(stringResource(R.string.tracking_remove_review_changed), Modifier.testTag("tracking-final-stale"), danger = true)
        }
    }
}

@Preview(name = "Stop tracking final · backup · light")
@Composable
private fun TrackingFinalBackupPreview() = SyncMdTheme(darkTheme = false) {
    TrackingRemovalConfirmationSheet("/storage/emulated/0/Documents/VoxVault", true, true, false, {}, {})
}

@Preview(name = "Stop tracking final · permanent · stale · dark")
@Composable
private fun TrackingFinalStalePreview() = SyncMdTheme(darkTheme = true) {
    TrackingRemovalConfirmationSheet("/storage/emulated/0/Documents/VoxVault", false, false, false, {}, {})
}

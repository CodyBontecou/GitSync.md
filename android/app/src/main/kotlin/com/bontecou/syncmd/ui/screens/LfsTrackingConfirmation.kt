package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.VaultOpsButton
import com.bontecou.syncmd.ui.components.VaultOpsSheet
import com.bontecou.syncmd.ui.components.VaultOpsTextButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideType

/** The paths belong to the frozen confirmation, independently of subsequent screen selection. */
@Composable
internal fun LfsTrackingConfirmation(selected: Set<String>, useLfs: Boolean, canApply: Boolean,
    onCancel: () -> Unit, onConfirm: () -> Unit) {
    val c = Tide.colors
    VaultOpsSheet(
        onDismissRequest = onCancel,
        title = stringResource(if (useLfs) R.string.lfs_tracking_lfs_title else R.string.lfs_tracking_git_title),
        titleModifier = Modifier.testTag("lfs-tracking-title"),
        confirmButton = {
            VaultOpsButton(stringResource(R.string.ui_apply_tracking_choice_47a2e187), onConfirm,
                Modifier.testTag("lfs-confirm-tracking"), enabled = canApply && selected.isNotEmpty())
        },
        dismissButton = {
            VaultOpsTextButton(stringResource(R.string.publication_cancel), onCancel, Modifier.fillMaxWidth().testTag("lfs-cancel-tracking"))
        },
    ) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp).testTag("lfs-tracking-review"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "count") {
                Text(pluralStringResource(R.plurals.lfs_tracking_files, selected.size, selected.size),
                    style = TideType.mono(13.sp, FontWeight.SemiBold), color = if (useLfs) c.ahead else c.ink,
                    modifier = Modifier.testTag("lfs-tracking-count"))
            }
            item(key = "scope") {
                Text(stringResource(if (useLfs) R.string.lfs_tracking_lfs_body else R.string.lfs_tracking_git_body),
                    modifier = Modifier.testTag("lfs-tracking-scope"))
            }
            items(selected.sorted(), key = { "path:$it" }) { path ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(6.dp).background(c.inkFaint, CircleShape))
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(path, style = TideType.mono(12.5.sp).copy(textDirection = TextDirection.Ltr), color = c.ink,
                            modifier = Modifier.fillMaxWidth().testTag("lfs-tracking-path:$path"))
                    }
                }
            }
        }
    }
}

@Preview(name = "Track with LFS · light")
@Composable
private fun LfsTrackingLfsPreview() = SyncMdTheme(darkTheme = false) {
    LfsTrackingConfirmation(setOf("assets/diagram.psd", "audio/interview.wav"), useLfs = true, canApply = true, onCancel = {}, onConfirm = {})
}

@Preview(name = "Keep in Git · stale · dark")
@Composable
private fun LfsTrackingGitPreview() = SyncMdTheme(darkTheme = true) {
    LfsTrackingConfirmation(setOf("video/cut.mov"), useLfs = false, canApply = false, onCancel = {}, onConfirm = {})
}

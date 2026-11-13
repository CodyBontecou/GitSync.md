package com.bontecou.syncmd.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3DialogSurface
import com.bontecou.syncmd.ui.theme.SyncMdTheme

/** "Abort this merge?" behind the conflict screen's Abort: the branch goes back, picks are thrown away. */
@Composable
fun AbortMergeDialog(branch: String, busy: Boolean, onAbort: () -> Unit, onDismiss: () -> Unit) {
    M3Dialog(onDismissRequest = { if (!busy) onDismiss() }, title = stringResource(R.string.git_m3_abort_merge_title),
        modifier = Modifier.testTag("conflict-abort-dialog"), icon = R.drawable.ic_m3_warning, iconTint = M3.colors.error,
        text = stringResource(R.string.git_m3_abort_merge_body, branch)) {
        M3DialogAction(stringResource(R.string.git_m3_cancel), onDismiss, enabled = !busy)
        M3DialogAction(stringResource(R.string.git_m3_abort_merge), onAbort, Modifier.testTag("conflict-abort-confirm"), destructive = true, filled = true, loading = busy)
    }
}

@Preview(name = "Abort merge · light")
@Composable
private fun AbortMergePreview() = SyncMdTheme(darkTheme = false) {
    M3DialogSurface(stringResource(R.string.git_m3_abort_merge_title), icon = R.drawable.ic_m3_warning, iconTint = M3.colors.error,
        text = stringResource(R.string.git_m3_abort_merge_body, "main")) {
        M3DialogAction(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.publication_cancel), {}); M3DialogAction(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.adaptive_abort_merge), {}, destructive = true, filled = true)
    }
}

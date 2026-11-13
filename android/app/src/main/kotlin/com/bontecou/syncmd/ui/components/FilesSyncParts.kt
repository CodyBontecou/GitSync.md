package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.sync.VaultSyncResult
import com.bontecou.syncmd.sync.VaultSyncRun
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.util.asString

/** The Files tab's view of the vault's sync run: the Changes tab's syncing hero, then its result notes. */
@Composable
fun FilesSyncStatus(run: VaultSyncRun?, onCancel: () -> Unit, onDismiss: () -> Unit) {
    if (run == null) return
    if (run.running) { VaultSyncingSection(run, onCancel); return }
    val dismiss = stringResource(R.string.ui_dismiss_48845bff)
    val notice = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
    val action: @Composable () -> Unit = {
        M3Button(dismiss, onDismiss, Modifier.testTag("files-sync-dismiss"), M3ButtonStyle.OutlinedContent, M3ButtonSize.XSmall)
    }
    when (val result = run.result) {
        is VaultSyncResult.Failed -> M3Note(result.message.asString(), notice.testTag("files-sync-error"), tone = M3NoteTone.Warn,
            title = stringResource(R.string.vault_m3_sync_failed), action = action)
        is VaultSyncResult.Done -> M3Note(result.message.asString(), notice.testTag("files-sync-done"), tone = M3NoteTone.Ok, action = action)
        VaultSyncResult.Cancelled -> M3Note(stringResource(R.string.vault_m3_sync_cancelled), notice.testTag("files-sync-cancelled"),
            tone = M3NoteTone.Muted, action = action)
        // Push rejected and local-changes blocks belong to the Changes tab, which offers their next steps.
        else -> Unit
    }
}

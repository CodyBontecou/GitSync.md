package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.util.RepositorySelection
import com.bontecou.syncmd.ui.viewmodels.FetchOutcome
import com.bontecou.syncmd.ui.viewmodels.PullViewModel

/**
 * Notes shared by the Git screens: a lingering Git lock with a confirmed "Remove lock" action
 * (issue #66), and what the last Fetch found (issue #67).
 */
@Composable
internal fun GitRecoveryNotes(pullVM: PullViewModel, selection: RepositorySelection?) {
    if (selection == null) return
    val locks by pullVM.staleLocks.collectAsState()
    val removing by pullVM.removingLocks.collectAsState()
    val busy by pullVM.isLoading.collectAsState()
    val fetchResult by pullVM.fetchResult.collectAsState()
    var confirming by remember { mutableStateOf(false) }
    val noteModifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)

    if (locks.isNotEmpty()) {
        M3Note(stringResource(R.string.git_lock_body), noteModifier.testTag("git-stale-lock"), tone = M3NoteTone.Warn,
            icon = R.drawable.ic_m3_lock, title = stringResource(R.string.git_lock_title)) {
            M3TextButton(stringResource(R.string.git_lock_remove), { confirming = true }, Modifier.testTag("git-remove-lock"),
                enabled = !removing && !busy, destructive = true)
        }
    }
    fetchResult?.let { outcome ->
        M3Note(fetchResultText(outcome), noteModifier.testTag("git-fetch-result"), tone = M3NoteTone.Info) {
            M3TextButton(stringResource(R.string.ui_dismiss_48845bff), { pullVM.clearFetchResult(selection) })
        }
    }
    if (confirming) {
        M3Dialog(
            onDismissRequest = { confirming = false },
            title = stringResource(R.string.git_lock_confirm_title),
            text = stringResource(R.string.git_lock_confirm_body),
            icon = R.drawable.ic_m3_warning,
        ) {
            M3DialogAction(stringResource(R.string.publication_cancel), { confirming = false })
            M3DialogAction(stringResource(R.string.git_lock_remove), { confirming = false; pullVM.removeStaleLocks(selection) },
                Modifier.testTag("git-remove-lock-confirm"), destructive = true)
        }
    }
}

@Composable
private fun fetchResultText(outcome: FetchOutcome): String = when {
    outcome.behind == 0 -> stringResource(R.string.git_fetch_up_to_date)
    outcome.ahead > 0 -> pluralStringResource(R.plurals.git_fetch_diverged, outcome.behind, outcome.behind)
    else -> pluralStringResource(R.plurals.git_fetch_new_commits, outcome.behind, outcome.behind)
}

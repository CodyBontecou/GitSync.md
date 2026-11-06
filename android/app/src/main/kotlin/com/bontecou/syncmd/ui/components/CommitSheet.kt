package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideBanner
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TideOutlineButton
import com.bontecou.syncmd.ui.theme.TidePrimaryButton
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideFieldColors
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.BranchViewModel
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel

/**
 * Commit bottom sheet (Material 3 board "08 · Commit bottom sheet"): the message,
 * the included files as checkboxes (checked = staged), then Commit & push or
 * Commit locally. It drives the same [DiffViewModel] staging and commit calls as
 * the Git tools, so nothing here bypasses the existing safety checks.
 */
@Composable
fun CommitSheet(
    repositoryPath: String,
    vaultName: String,
    onDismiss: () -> Unit,
    diffVM: DiffViewModel = hiltViewModel(),
    branchVM: BranchViewModel = hiltViewModel(),
) {
    LaunchedEffect(repositoryPath) {
        // Refresh, not re-own: on list-detail layouts the diff pane shares this view model, and
        // re-owning the path would drop the file diff it is loading.
        diffVM.refreshRepositoryPath(repositoryPath)
        branchVM.setRepositoryPath(repositoryPath)
    }
    val c = Tide.colors
    val diff by diffVM.diff.collectAsState()
    val message by diffVM.commitMessage.collectAsState()
    val isLoading by diffVM.isLoading.collectAsState()
    val isCommitting by diffVM.isCommitting.collectAsState()
    val isPushing by diffVM.isPushing.collectAsState()
    val error = diffVM.errorMessage.collectAsState().value?.asString()
    val notice = diffVM.operationMessage.collectAsState().value?.asString()
    val branchState by branchVM.state.collectAsState()
    val branch = branchState.currentBranch?.name
    val files = diff?.files.orEmpty().groupBy { it.filePath }.map { (path, entries) -> path to entries.any { it.isStaged } }
    val staged = files.count { it.second }
    var committing by remember { mutableStateOf(false) }
    // Close once a commit started here finishes cleanly; failures stay on the sheet.
    LaunchedEffect(isLoading, error) {
        if (committing && !isLoading) {
            committing = false
            if (error == null) onDismiss()
        }
    }
    val canCommit = staged > 0 && message.isNotBlank() && !isLoading
    TideDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        sheet = true,
        title = pluralStringResource(R.plurals.tide_git_headline_commit, staged, staged),
        modifier = Modifier.testTag("commit-sheet"),
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TidePrimaryButton(
                    stringResource(R.string.ui_commit_push_94e9650a),
                    onClick = { committing = true; branch?.let { diffVM.commitAndPush(it) } },
                    badge = if (staged > 0) "↑$staged" else null,
                    enabled = canCommit && branch != null,
                    loading = isPushing,
                    modifier = Modifier.testTag("commit-sheet-push"),
                )
                TideOutlineButton(
                    stringResource(if (isCommitting && !isPushing) R.string.tide_git_committing else R.string.tide_git_commit_locally),
                    onClick = { committing = true; diffVM.commitOnly() },
                    enabled = canCommit,
                    modifier = Modifier.fillMaxWidth().testTag("commit-sheet-local"),
                )
            }
        },
        dismissButton = { BGhostButton(stringResource(R.string.repository_actions_cancel), Modifier.fillMaxWidth(), onClick = onDismiss) },
    ) {
        Text(listOfNotNull(vaultName, branch).joinToString(" → "), style = TideType.meta.copy(textDirection = TextDirection.Ltr),
            color = c.inkMid, modifier = Modifier.padding(bottom = 12.dp))
        OutlinedTextField(
            value = message,
            onValueChange = diffVM::setCommitMessage,
            label = { Text(stringResource(R.string.tide_git_message)) },
            placeholder = { Text(stringResource(R.string.ui_commit_message_a4b361b7)) },
            minLines = 2,
            maxLines = 5,
            enabled = !isLoading,
            colors = tideFieldColors(),
            shape = Tide.fieldShape,
            textStyle = TideType.body,
            modifier = Modifier.fillMaxWidth().testTag("commit-sheet-message"),
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TideSectionLabel(stringResource(R.string.tide_git_included), Modifier.weight(1f))
            if (files.any { !it.second }) BGhostButton(stringResource(R.string.ui_stage_all_d1ce47c8), onClick = { diffVM.stageAll() })
        }
        Column(Modifier.fillMaxWidth().clip(Tide.cardShape).background(c.container)) {
            if (files.isEmpty()) {
                Text(stringResource(R.string.ui_no_local_changes_56319dc5), style = TideType.body, color = c.inkMid,
                    modifier = Modifier.padding(16.dp))
            }
            files.forEachIndexed { index, (path, isStaged) ->
                if (index > 0) TideHairline()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(isStaged, enabled = !isLoading, role = Role.Checkbox) { on ->
                            if (on) diffVM.stageFile(path) else diffVM.unstageFile(path)
                        }
                        .padding(horizontal = 8.dp)
                        .testTag("commit-sheet-file-$path"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(isStaged, onCheckedChange = null, enabled = !isLoading,
                        colors = CheckboxDefaults.colors(checkedColor = c.primary, checkmarkColor = c.onPrimary, uncheckedColor = c.inkMid),
                        modifier = Modifier.padding(horizontal = 8.dp))
                    Text(path, style = TideType.mono(13.sp).copy(textDirection = TextDirection.Ltr), color = c.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                }
            }
        }
        if (notice != null) TideBanner(notice, Modifier.padding(top = 12.dp))
        if (error != null) TideBanner(error, Modifier.padding(top = 12.dp), tone = TideBannerTone.DANGER,
            onDismiss = { diffVM.clearError() })
    }
}

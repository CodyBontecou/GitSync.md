@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.GitUntrackTarget
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3InsetList
import com.bontecou.syncmd.ui.theme.M3ModalSheet
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3RadioIndicator
import com.bontecou.syncmd.ui.theme.M3SheetSurface
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.m3TopDivider
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.StopTrackingViewModel

/** Stable ids for e2e tests: `stop-tracking-option-file`, `-folder`, `-workspace`. */
fun stopTrackingOptionTag(target: GitUntrackTarget): String = "stop-tracking-option-" + when (target) {
    is GitUntrackTarget.File -> "file"
    is GitUntrackTarget.Folder -> "folder"
    is GitUntrackTarget.Pattern -> "workspace"
}

/**
 * "Stop tracking in Git?" (issue #149, iOS `StopTrackingPathSheet`): removes a file, its folder or
 * Obsidian's workspace files from Git, ignores them in `.gitignore` and commits that. The files stay
 * on this device; the next push stops tracking them everywhere. File and folder show a red warning
 * (other devices delete their copy when they pull); the workspace pattern shows an info note.
 */
@Composable
fun StopTrackingSheet(state: StopTrackingViewModel.State, onChoose: (GitUntrackTarget) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val working = state.working
    M3ModalSheet(
        onDismissRequest = { if (!working) onDismiss() },
        modifier = Modifier.testTag("stop-tracking-sheet").semantics { testTagsAsResourceId = true },
        title = stringResource(R.string.stop_tracking_title),
        footer = {
            M3Button(stringResource(R.string.stop_tracking_confirm), onConfirm, Modifier.fillMaxWidth().testTag("stop-tracking-confirm"),
                size = M3ButtonSize.Hero, enabled = !working && state.choice != null, loading = working)
            M3TextButton(stringResource(R.string.publication_cancel), onDismiss, Modifier.fillMaxWidth().testTag("stop-tracking-cancel"), enabled = !working)
        },
    ) { StopTrackingBody(state, onChoose) }
}

@Composable
private fun StopTrackingBody(state: StopTrackingViewModel.State, onChoose: (GitUntrackTarget) -> Unit) {
    val c = M3.colors
    Text(stringResource(R.string.stop_tracking_body), style = M3Type.bodyMedium, color = c.onVar, modifier = Modifier.padding(top = 12.dp))
    M3InsetList(Modifier.padding(top = 16.dp).selectableGroup()) {
        state.options.forEachIndexed { index, option ->
            OptionRow(option, selected = option == state.choice, count = state.counts[option], divider = index > 0,
                enabled = !state.working, onClick = { onChoose(option) })
        }
    }
    val workspace = state.choice is GitUntrackTarget.Pattern
    M3Note(
        stringResource(if (workspace) R.string.stop_tracking_note_workspace else R.string.stop_tracking_note_file),
        Modifier.padding(top = 12.dp).testTag(if (workspace) "stop-tracking-note-workspace" else "stop-tracking-note-warning"),
        tone = if (workspace) M3NoteTone.Muted else M3NoteTone.Warn,
    )
    state.error?.let { M3Note(it.asString(), Modifier.padding(top = 12.dp).testTag("stop-tracking-error"), tone = M3NoteTone.Warn) }
}

@Composable
private fun OptionRow(option: GitUntrackTarget, selected: Boolean, count: Int?, divider: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = M3.colors
    Row(
        Modifier
            .fillMaxWidth()
            .m3TopDivider(divider, c.outlineVar)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .testTag(stopTrackingOptionTag(option))
            .heightIn(min = 56.dp)
            .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        M3RadioIndicator(selected)
        Column(Modifier.weight(1f)) {
            Text(stringResource(when (option) {
                is GitUntrackTarget.File -> R.string.stop_tracking_option_file
                is GitUntrackTarget.Folder -> R.string.stop_tracking_option_folder
                is GitUntrackTarget.Pattern -> R.string.stop_tracking_option_workspace
            }), style = M3Type.body, color = c.on)
            Text(option.displayName, style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.onVar, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        if (count != null && count > 0) {
            Text(if (count == 1) stringResource(R.string.stop_tracking_one_file) else stringResource(R.string.stop_tracking_files, count),
                style = M3Type.caption, color = c.onVar)
        }
    }
}

@Preview(name = "Stop tracking · file", heightDp = 640)
@Composable
private fun StopTrackingFilePreview() = SyncMdTheme(darkTheme = false) {
    val path = ".obsidian/workspace-mobile.json"
    val state = StopTrackingViewModel.State("/vault", path, GitUntrackTarget.options(path), GitUntrackTarget.File(path),
        mapOf(GitUntrackTarget.Folder(".obsidian") to 12, GitUntrackTarget.ObsidianWorkspace to 2))
    M3SheetSurface(title = stringResource(R.string.stop_tracking_title)) { StopTrackingBody(state) {} }
}

@Preview(name = "Stop tracking · workspace · dark", heightDp = 640)
@Composable
private fun StopTrackingWorkspacePreview() = SyncMdTheme(darkTheme = true) {
    val state = StopTrackingViewModel.State("/vault", null, GitUntrackTarget.options(null), GitUntrackTarget.ObsidianWorkspace,
        mapOf(GitUntrackTarget.ObsidianWorkspace to 2))
    M3SheetSurface(title = stringResource(R.string.stop_tracking_title)) { StopTrackingBody(state) {} }
}

/**
 * Opens [StopTrackingSheet] for [path] (a changed file) or, with a null path, for the Obsidian
 * workspace pattern. [onClose] gets true once the commit exists, false on cancel.
 */
@Composable
fun StopTrackingHost(
    repositoryPath: String,
    path: String?,
    onClose: (stopped: Boolean) -> Unit,
    viewModel: StopTrackingViewModel = hiltViewModel(),
) {
    LaunchedEffect(repositoryPath, path) { viewModel.open(repositoryPath, path) }
    val state = viewModel.state.collectAsState().value
    if (state.repoPath == repositoryPath && state.path == path) {
        StopTrackingSheet(state, viewModel::choose, onConfirm = { viewModel.confirm { onClose(true) } },
            onDismiss = { viewModel.dismiss(); onClose(false) })
    }
}

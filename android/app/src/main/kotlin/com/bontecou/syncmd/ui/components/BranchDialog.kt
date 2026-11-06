package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.DiffStatus
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.services.git.GitBranchDetail
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3DialogSurface
import com.bontecou.syncmd.ui.theme.M3FilterChip
import com.bontecou.syncmd.ui.theme.M3ModalSheet
import com.bontecou.syncmd.ui.theme.M3RadioRow
import com.bontecou.syncmd.ui.theme.M3Segmented
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.m3TopDivider

/** A file the switch would replace: path and its A/M/D letter. */
data class DirtyPath(val path: String, val status: DiffStatus)

/**
 * "Merge into main" sheet (Branches board, overlay merge): pick the source branch (with how many
 * commits it adds), the strategy as chips with its explanation, then "Merge <branch>".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MergeBranchSheet(
    vault: String,
    target: String,
    sources: List<GitBranchDetail>,
    initial: GitBranchDetail,
    busy: Boolean,
    onMerge: (GitBranchDetail, MergeStrategy) -> Unit,
    onDismiss: () -> Unit,
) {
    var source by remember(initial.name) { mutableStateOf(initial) }
    var strategy by remember { mutableStateOf(MergeStrategy.PREFER_FF) }
    val c = M3.colors
    M3ModalSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag("git-merge-sheet"),
        title = stringResource(R.string.git_m3_merge_into, target),
        titleMeta = vault,
        footer = {
            M3Button(stringResource(R.string.git_m3_merge_named, source.name), { onMerge(source, strategy) },
                Modifier.fillMaxWidth().testTag("git-merge-confirm"), size = M3ButtonSize.Hero, loading = busy, enabled = !busy)
            M3TextButton(stringResource(R.string.git_m3_cancel), onDismiss, Modifier.fillMaxWidth(), enabled = !busy)
        },
    ) {
        GitSheetLabel(stringResource(R.string.git_m3_from))
        GitInsetBlock(padding = false) {
            sources.forEachIndexed { index, branch ->
                M3RadioRow(
                    branch.name, selected = branch.name == source.name, onClick = { source = branch }, divider = index > 0,
                    meta = listOfNotNull(branch.last?.shortId,
                        if (branch.aheadOfHead > 0) pluralStringResource(R.plurals.git_m3_commits_missing, branch.aheadOfHead, branch.aheadOfHead, target)
                        else stringResource(R.string.git_m3_nothing_new, target)).joinToString(" · "),
                    enabled = !busy,
                )
            }
        }
        GitSheetLabel(stringResource(R.string.git_m3_how), bottom = 8.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(MergeStrategy.PREFER_FF to R.string.git_m3_strategy_prefer, MergeStrategy.FAST_FORWARD to R.string.git_m3_strategy_ff,
                MergeStrategy.RECURSIVE to R.string.git_m3_strategy_always).forEach { (value, label) ->
                M3FilterChip(stringResource(label), strategy == value, { strategy = value }, compact = true, enabled = !busy, role = Role.RadioButton)
            }
        }
        Text(when (strategy) {
            MergeStrategy.PREFER_FF -> stringResource(R.string.git_m3_strategy_prefer_help, target)
            MergeStrategy.FAST_FORWARD -> stringResource(R.string.git_m3_strategy_ff_help, source.name, target)
            MergeStrategy.RECURSIVE -> stringResource(R.string.git_m3_strategy_always_help, target)
        }, Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp), style = M3Type.bodyMedium, color = c.onVar)
        Text(stringResource(R.string.git_m3_merge_pause_note), Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), style = M3Type.bodyMedium, color = c.onVar)
    }
}

/** "Delete branch?" with Normal / Force and what each does. */
@Composable
fun DeleteBranchDialog(branch: String, busy: Boolean, onDelete: (force: Boolean) -> Unit, onDismiss: () -> Unit) {
    var force by remember(branch) { mutableStateOf(false) }
    M3Dialog(onDismissRequest = { if (!busy) onDismiss() }, title = stringResource(R.string.git_m3_delete_branch_title),
        modifier = Modifier.testTag("git-delete-branch-dialog"), icon = R.drawable.ic_m3_delete, iconTint = M3.colors.error,
        content = { DeleteBranchBody(branch, force, busy) { force = it } }) {
        M3DialogAction(stringResource(R.string.git_m3_cancel), onDismiss, enabled = !busy)
        M3DialogAction(stringResource(R.string.git_m3_delete), { onDelete(force) }, Modifier.testTag("git-delete-branch-confirm"),
            destructive = true, filled = true, loading = busy, enabled = !busy)
    }
}

@Composable
private fun DeleteBranchBody(branch: String, force: Boolean, busy: Boolean, onForce: (Boolean) -> Unit) {
    val c = M3.colors
    Text(gitMonoInline(stringResource(R.string.git_m3_delete_branch_body), branch), style = M3Type.bodyMedium, color = c.onVar)
    M3Segmented(listOf(stringResource(R.string.git_m3_delete_normal), stringResource(R.string.git_m3_delete_force)), if (force) 1 else 0,
        { onForce(it == 1) }, Modifier.padding(top = 16.dp), enabled = !busy, role = Role.RadioButton)
    Text(stringResource(if (force) R.string.git_m3_delete_force_help else R.string.git_m3_delete_normal_help),
        Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp), style = M3Type.bodySmall, color = if (force) c.error else c.onVar)
}

/**
 * "Can't switch yet": uncommitted files would be replaced. Offers Stash changes, Commit first, or
 * Stay on <current>. Nothing has changed yet.
 */
@Composable
fun CantSwitchDialog(target: String, current: String, dirty: List<DirtyPath>, onStash: () -> Unit, onCommit: () -> Unit, onDismiss: () -> Unit) {
    M3Dialog(onDismissRequest = onDismiss, title = stringResource(R.string.git_m3_cant_switch_title), modifier = Modifier.testTag("git-cant-switch"),
        icon = R.drawable.ic_m3_branch, stackedActions = true, content = { CantSwitchBody(target, dirty) }) {
        M3Button(stringResource(R.string.git_m3_stash_changes), onStash, Modifier.fillMaxWidth().testTag("git-cant-switch-stash"))
        M3Button(stringResource(R.string.git_m3_commit_first), onCommit, Modifier.fillMaxWidth().testTag("git-cant-switch-commit"), style = M3ButtonStyle.Outlined)
        M3TextButton(stringResource(R.string.git_m3_stay_on, current), onDismiss, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CantSwitchBody(target: String, dirty: List<DirtyPath>) {
    val c = M3.colors
    Text(gitMonoInline(pluralStringResource(R.plurals.git_m3_cant_switch_body, dirty.size, "%1\$s", dirty.size), target),
        style = M3Type.bodyMedium, color = c.onVar)
    GitInsetBlock(Modifier.padding(top = 16.dp), radius = 12.dp, padding = false) {
        dirty.take(8).forEachIndexed { index, file ->
            Row(Modifier.fillMaxWidth().m3TopDivider(index > 0, c.outlineVar).heightIn(min = 40.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(file.path, Modifier.weight(1f), style = M3Type.meta, color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (letter, ink) = statusLetter(file.status)
                Text(letter, Modifier.padding(start = 8.dp), style = M3Type.metaStrong, color = ink)
            }
        }
        if (dirty.size > 8) Text(stringResource(R.string.git_m3_more_files, dirty.size - 8), Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = M3Type.meta, color = c.onVar)
    }
    Text(stringResource(R.string.git_m3_cant_switch_note), Modifier.padding(top = 12.dp), style = M3Type.bodySmall, color = c.onVar)
}

/** A/M/D/R letter and ink for a change. */
@Composable
fun statusLetter(status: DiffStatus) = when (status) {
    DiffStatus.ADDED -> "A" to M3.colors.addInk
    DiffStatus.DELETED -> "D" to M3.colors.delInk
    DiffStatus.RENAMED -> "R" to M3.colors.ahead
    else -> "M" to M3.colors.ahead
}

@Preview(name = "Delete branch · light")
@Composable
private fun DeleteBranchPreview() = SyncMdTheme(darkTheme = false) {
    M3DialogSurface(stringResource(R.string.git_m3_delete_branch_title), icon = R.drawable.ic_m3_delete, iconTint = M3.colors.error,
        content = { DeleteBranchBody("draft/spring", false, false) {} }) {
        M3DialogAction("Cancel", {}); M3DialogAction("Delete", {}, destructive = true, filled = true)
    }
}

@Preview(name = "Can't switch · dark")
@Composable
private fun CantSwitchPreview() = SyncMdTheme(darkTheme = true) {
    M3DialogSurface(stringResource(R.string.git_m3_cant_switch_title), icon = R.drawable.ic_m3_branch, stackedActions = true,
        content = { CantSwitchBody("draft/pipeline", listOf(DirtyPath("daily/2026-11-01.md", DiffStatus.ADDED),
            DirtyPath("ideas/voice-memo-pipeline.md", DiffStatus.MODIFIED), DirtyPath("old-inbox.md", DiffStatus.DELETED))) }) {
        M3Button("Stash changes", {}, Modifier.fillMaxWidth())
        M3Button("Commit first", {}, Modifier.fillMaxWidth(), style = M3ButtonStyle.Outlined)
        M3TextButton("Stay on main", {}, Modifier.fillMaxWidth())
    }
}


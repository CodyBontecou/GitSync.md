package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextDirection
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.data.models.BranchType
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.ui.theme.BDestructiveButton
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideChipRow
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideFilterChip
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideSegmented
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideFieldColors

/**
 * Tide sheet for creating a new branch: mono name field and a chip row to
 * pick the start point (HEAD or an existing branch).
 */
@Composable
fun CreateBranchDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, startPoint: String) -> Unit,
    availableBranches: List<Branch> = emptyList()
) {
    var branchName by remember { mutableStateOf("") }
    var startPoint by remember { mutableStateOf("HEAD") }
    CreateBranchContent(
        branchName = branchName,
        onBranchNameChange = { branchName = it },
        startPoint = startPoint,
        onStartPointChange = { startPoint = it },
        availableBranches = availableBranches,
        onDismiss = onDismiss,
        onCreate = {
            if (branchName.isNotBlank()) {
                onCreate(branchName, startPoint)
                onDismiss()
            }
        },
    )
}

@Composable
private fun CreateBranchContent(
    branchName: String,
    onBranchNameChange: (String) -> Unit,
    startPoint: String,
    onStartPointChange: (String) -> Unit,
    availableBranches: List<Branch>,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
) {
    TideDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.ui_create_new_branch_bf815ec6),
        confirmButton = {
            BPrimaryButton(
                stringResource(R.string.ui_create_4759498a),
                Modifier.fillMaxWidth(),
                isDisabled = branchName.isBlank(),
                onClick = onCreate,
            )
        },
        dismissButton = { BGhostButton(stringResource(R.string.publication_cancel), Modifier.fillMaxWidth(), onClick = onDismiss) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = branchName,
                onValueChange = onBranchNameChange,
                label = { Text(stringResource(R.string.ui_branch_name_06f6bb71)) },
                placeholder = { Text(stringResource(R.string.ui_e_g_feature_new_ui_76d6dd27), style = TideType.mono(15.sp)) },
                textStyle = TideType.mono(15.sp).copy(textDirection = TextDirection.Ltr),
                singleLine = true,
                shape = Tide.fieldShape,
                colors = tideFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TideSectionLabel(stringResource(R.string.ui_start_from_eb3f51dc), Modifier.padding(start = 4.dp))
                TideChipRow {
                    TideFilterChip(label = "HEAD", selected = startPoint == "HEAD", onClick = { onStartPointChange("HEAD") })
                    availableBranches.forEach { branch ->
                        TideFilterChip(label = branch.name, selected = startPoint == branch.name, onClick = { onStartPointChange(branch.name) })
                    }
                }
            }
        }
    }
}

/**
 * Tide sheet for merging another branch into the current one: a card of
 * selectable branch rows, then the merge strategy and what it means.
 */
@Composable
fun MergeBranchDialog(
    onDismiss: () -> Unit,
    onMerge: (sourceBranch: String, strategy: MergeStrategy) -> Unit,
    currentBranch: String = "",
    availableBranches: List<Branch> = emptyList()
) {
    var sourceBranch by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf(MergeStrategy.PREFER_FF) }
    MergeBranchContent(
        currentBranch = currentBranch,
        availableBranches = availableBranches,
        sourceBranch = sourceBranch,
        onSourceChange = { sourceBranch = it },
        strategy = strategy,
        onStrategyChange = { strategy = it },
        onDismiss = onDismiss,
        onMerge = {
            if (sourceBranch.isNotBlank()) {
                onMerge(sourceBranch, strategy)
                onDismiss()
            }
        },
    )
}

@Composable
private fun MergeBranchContent(
    currentBranch: String,
    availableBranches: List<Branch>,
    sourceBranch: String,
    onSourceChange: (String) -> Unit,
    strategy: MergeStrategy,
    onStrategyChange: (MergeStrategy) -> Unit,
    onDismiss: () -> Unit,
    onMerge: () -> Unit,
) {
    val c = Tide.colors
    TideDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.ui_merge_branch_into_1_s_88a0ca2d, currentBranch),
        confirmButton = {
            BPrimaryButton(
                stringResource(R.string.ui_merge_8851aaa7),
                Modifier.fillMaxWidth(),
                isDisabled = sourceBranch.isBlank(),
                onClick = onMerge,
            )
        },
        dismissButton = { BGhostButton(stringResource(R.string.publication_cancel), Modifier.fillMaxWidth(), onClick = onDismiss) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.ui_select_source_branch_to_merge_c3dbf949), style = TideType.sans(14.sp), color = c.inkMid)
            Column(
                Modifier.fillMaxWidth().background(c.card, Tide.cardShape).padding(horizontal = 16.dp)
            ) {
                availableBranches.filter { it.name != currentBranch }.forEachIndexed { index, branch ->
                    if (index > 0) TideHairline()
                    val selected = sourceBranch == branch.name
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clickable(enabled = !selected, role = Role.RadioButton) { onSourceChange(branch.name) }
                            .semantics { this.selected = selected },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            branch.name,
                            style = TideType.mono(15.sp, FontWeight.SemiBold),
                            color = c.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) Text("✓", style = TideType.sans(16.sp, FontWeight.Bold), color = c.synced)
                    }
                }
            }

            if (sourceBranch.isNotBlank()) {
                TideSectionLabel(stringResource(R.string.ui_merge_strategy_69ea53c4).trimEnd(':'), Modifier.padding(start = 4.dp, top = 8.dp))
                val strategies = MergeStrategy.values().toList()
                TideChipRow {
                    strategies.forEach { s ->
                        TideFilterChip(label = strategyLabel(s), selected = strategy == s, onClick = { onStrategyChange(s) })
                    }
                }
                Text(strategyDescription(strategy), style = TideType.sans(13.sp), color = c.inkFaint)
            }
        }
    }
}

@Composable
private fun strategyLabel(strategy: MergeStrategy): String = stringResource(
    when (strategy) {
        MergeStrategy.FAST_FORWARD -> R.string.tide_git_strategy_ff
        MergeStrategy.RECURSIVE -> R.string.tide_git_strategy_recursive
        MergeStrategy.PREFER_FF -> R.string.tide_git_strategy_prefer_ff
    }
)

/**
 * Tide confirmation for deleting a branch, with a normal / force choice.
 */
@Composable
fun DeleteBranchDialog(
    branchName: String,
    onDismiss: () -> Unit,
    onConfirm: (force: Boolean) -> Unit
) {
    var useForce by remember { mutableStateOf(false) }
    DeleteBranchContent(
        branchName = branchName,
        useForce = useForce,
        onForceChange = { useForce = it },
        onDismiss = onDismiss,
        onConfirm = {
            onConfirm(useForce)
            onDismiss()
        },
    )
}

@Composable
private fun DeleteBranchContent(
    branchName: String,
    useForce: Boolean,
    onForceChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = Tide.colors
    TideDialog(
        onDismissRequest = onDismiss,
        icon = { TideModalIcon() },
        title = stringResource(R.string.ui_delete_branch_00339d48),
        text = { Text(stringResource(R.string.ui_delete_branch_1_s_bbe46386, branchName)) },
        confirmButton = { BDestructiveButton(stringResource(R.string.ui_delete_e2d0a549), Modifier.fillMaxWidth(), onClick = onConfirm) },
        dismissButton = { BGhostButton(stringResource(R.string.publication_cancel), Modifier.fillMaxWidth(), onClick = onDismiss) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TideSegmented(
                options = listOf(stringResource(R.string.tide_git_normal_delete), stringResource(R.string.tide_git_force_delete)),
                selectedIndex = if (useForce) 1 else 0,
                onSelect = { onForceChange(it == 1) },
            )
            Text(
                stringResource(if (useForce) R.string.tide_git_force_delete_detail else R.string.tide_git_normal_delete_detail),
                style = TideType.sans(13.sp),
                color = if (useForce) c.danger else c.inkFaint,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * Get a description of a merge strategy.
 */
@Composable
fun strategyDescription(strategy: MergeStrategy): String {
    return stringResource(when (strategy) {
        MergeStrategy.FAST_FORWARD -> R.string.dialogs_merge_strategy_fast_forward_description
        MergeStrategy.RECURSIVE -> R.string.dialogs_merge_strategy_recursive_description
        MergeStrategy.PREFER_FF -> R.string.dialogs_merge_strategy_prefer_ff_description
    })
}

// ─── Previews ─────────────────────────────────────────────────────────────────
// Dialogs render in their own window; previews show the sheet bodies.

private val previewDialogBranches = listOf(
    Branch("main", BranchType.TRACKING, isHead = true),
    Branch("feature/tide", BranchType.LOCAL),
)

@Preview(name = "Create branch · light")
@Composable
private fun CreateBranchPreview() = SyncMdTheme(darkTheme = false) {
    Box(Modifier.background(Tide.colors.paper)) {
        CreateBranchContent("feature/rail", {}, "HEAD", {}, previewDialogBranches, {}, {})
    }
}

@Preview(name = "Merge branch · dark")
@Composable
private fun MergeBranchPreview() = SyncMdTheme(darkTheme = true) {
    Box(Modifier.background(Tide.colors.paper)) {
        MergeBranchContent("main", previewDialogBranches, "feature/tide", {}, MergeStrategy.PREFER_FF, {}, {}, {})
    }
}

@Preview(name = "Delete branch · force · light")
@Composable
private fun DeleteBranchPreview() = SyncMdTheme(darkTheme = false) {
    Box(Modifier.background(Tide.colors.paper)) { DeleteBranchContent("feature/tide", true, {}, {}, {}) }
}

@Preview(name = "Delete branch · dark")
@Composable
private fun DeleteBranchDarkPreview() = SyncMdTheme(darkTheme = true) {
    Box(Modifier.background(Tide.colors.paper)) { DeleteBranchContent("feature/tide", false, {}, {}, {}) }
}

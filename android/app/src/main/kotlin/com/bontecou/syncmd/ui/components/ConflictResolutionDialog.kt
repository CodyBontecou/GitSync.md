package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.BDestructiveButton
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TideType

/** Tide sheet confirming how a conflict will be resolved ("Ours" / "Theirs" / "Manual"). */
@Composable
fun ConflictResolutionDialog(
    filePath: String,
    strategy: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = Tide.colors
    val yours = strategy == "Ours"
    TideDialog(
        onDismissRequest = { onDismiss() },
        icon = {
            TideModalIcon(
                glyph = when (strategy) { "Ours" -> "↑"; "Theirs" -> "↓"; else -> "✎" },
                tint = when (strategy) { "Ours" -> c.ahead; "Theirs" -> c.behind; else -> c.ink },
            )
        },
        title = stringResource(R.string.ui_resolve_conflict_ff598c8a),
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(when (strategy) {
                        "Ours" -> R.string.ui_this_will_keep_your_version_of_the_file_a9475bb6
                        "Theirs" -> R.string.ui_this_will_use_the_incoming_version_of_the_96940ba5
                        else -> R.string.ui_this_will_use_your_manually_edited_content_86084831
                    }),
                )
                Text(
                    stringResource(R.string.ui_file_1_s_9fdb0f6d, filePath),
                    style = TideType.mono(12.5.sp).copy(textDirection = TextDirection.Ltr),
                    color = c.ink,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (strategy == "Ours" || strategy == "Theirs") GitWorkSideDot(yours)
                    Text(
                        stringResource(R.string.ui_using_strategy_1_s_6c76b5ef, stringResource(when (strategy) {
                            "Ours" -> R.string.tide_merge_yours
                            "Theirs" -> R.string.tide_merge_theirs
                            else -> R.string.ui_manual_7c8107bd
                        })),
                        style = TideType.mono(12.sp, FontWeight.SemiBold),
                        color = when (strategy) { "Ours" -> c.ahead; "Theirs" -> c.behind; else -> c.inkMid },
                    )
                }
            }
        },
        confirmButton = {
            BPrimaryButton(stringResource(R.string.ui_confirm_eebdd24a), onClick = { onConfirm(); onDismiss() })
        },
        dismissButton = {
            BGhostButton(stringResource(R.string.publication_cancel), Modifier.fillMaxWidth(), onClick = { onDismiss() })
        },
    )
}

/** Tide sheet for finishing a merge, or explaining what is still unresolved. */
@Composable
fun MergeCompletionDialog(
    unresolvedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = Tide.colors
    val done = unresolvedCount == 0
    TideDialog(
        onDismissRequest = { onDismiss() },
        icon = { TideModalIcon(glyph = if (done) "✓" else "!", tint = if (done) c.synced else c.danger) },
        title = stringResource(if (done) R.string.tide_merge_merge_complete_title else R.string.tide_merge_merge_unresolved_title),
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done) {
                    Text(stringResource(R.string.ui_all_conflicts_have_been_resolved_proceed_w_ed0a737a))
                } else {
                    Text(
                        stringResource(R.string.ui_there_are_still_1_s_unresolved_conflicts_9ce41fa3, unresolvedCount.toString()),
                        color = c.danger,
                        style = TideType.sans(15.sp, FontWeight.Medium),
                    )
                    Text(stringResource(R.string.ui_please_resolve_all_conflicts_before_comple_9be0a6e5))
                }
            }
        },
        confirmButton = {
            if (done) BPrimaryButton(stringResource(R.string.ui_complete_merge_385cc51b), onClick = { onConfirm(); onDismiss() })
            else BPrimaryButton(stringResource(R.string.ui_ok_565339bc), onClick = { onDismiss() })
        },
    )
}

/** Tide destructive sheet confirming a merge abort. */
@Composable
fun MergeAbortDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val c = Tide.colors
    TideDialog(
        onDismissRequest = { onDismiss() },
        icon = { TideModalIcon() },
        title = stringResource(R.string.ui_abort_merge_8821f613),
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ui_are_you_sure_you_want_to_abort_this_merge_a64d6e49))
                Text(stringResource(R.string.ui_this_will_discard_all_progress_and_return_a2f7aabf), color = c.danger)
            }
        },
        confirmButton = {
            BDestructiveButton(stringResource(R.string.ui_abort_merge_8821f613), onClick = { onConfirm(); onDismiss() })
        },
        dismissButton = {
            BGhostButton(stringResource(R.string.publication_cancel), Modifier.fillMaxWidth(), onClick = { onDismiss() })
        },
    )
}

// ─── Previews ───────────────────────────────────────────────────────────────
// Dialogs render in their own window, so previews show the sheets in place.

@Preview(name = "Resolve · ours · light") @Composable
private fun ResolveOursPreview() = SyncMdTheme(darkTheme = false) {
    Box { ConflictResolutionDialog("ideas/launch-plan.md", "Ours", {}, {}) }
}

@Preview(name = "Merge · unresolved · dark") @Composable
private fun MergeUnresolvedPreview() = SyncMdTheme(darkTheme = true) { MergeCompletionDialog(2, {}, {}) }

@Preview(name = "Merge · abort") @Composable
private fun MergeAbortPreview() = SyncMdTheme(darkTheme = false) { MergeAbortDialog({}, {}) }

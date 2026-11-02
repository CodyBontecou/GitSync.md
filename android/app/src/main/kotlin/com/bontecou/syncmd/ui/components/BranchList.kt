package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.data.models.BranchType
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TideType

/**
 * Tide branch list: one white card of hairline-separated rows, mono branch
 * names, a green "Current" pill on HEAD and outline pills for actions
 * (iOS VaultBranchesScreen / GitControlSheet branch rows).
 */
@Composable
fun BranchList(
    branches: List<Branch>,
    currentBranch: Branch? = null,
    onSwitchBranch: (String) -> Unit,
    onDeleteBranch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (branches.isEmpty()) {
        TideCard(modifier = modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.ui_no_branches_available_7f5adec5),
                style = TideType.sans(15.sp, FontWeight.SemiBold),
                color = Tide.colors.ink,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .background(Tide.colors.card, Tide.cardShape)
            .padding(horizontal = 16.dp),
    ) {
        itemsIndexed(branches) { index, branch ->
            if (index > 0) TideHairline()
            BranchListItem(
                branch = branch,
                isCurrent = branch.isHead || branch.name == currentBranch?.name,
                onSwitchClick = { onSwitchBranch(branch.name) },
                onDeleteClick = { onDeleteBranch(branch.name) }
            )
        }
    }
}

/**
 * Single branch row: name + type pill, tracking and last commit in mono meta.
 */
@Composable
fun BranchListItem(
    branch: Branch,
    isCurrent: Boolean = false,
    onSwitchClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = Tide.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = !isCurrent, role = Role.Button) { onSwitchClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = branch.name,
                    style = TideType.mono(14.sp, FontWeight.SemiBold),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TidePill(
                    text = branchTypeLabel(branch.type.name),
                    fg = branchTypeTextColor(branch.type.name),
                    bg = branchTypeBgColor(branch.type.name),
                )
            }

            if (!branch.trackingBranch.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.ui_tracking_1_s_b83cf9b3, branch.trackingBranch.toString()),
                    style = TideType.mono(11.sp),
                    color = c.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!branch.lastCommitMessage.isNullOrBlank() && !branch.lastCommit.isNullOrBlank()) {
                Text(
                    text = "${branch.lastCommit?.take(7)} · ${branch.lastCommitMessage}",
                    style = TideType.mono(11.sp),
                    color = c.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isCurrent) {
            TidePill(stringResource(R.string.ui_current_e3cc57e1), c.synced, c.synced.copy(alpha = 0.12f))
        } else {
            BranchRowPill(
                label = stringResource(R.string.ui_switch_39921a74),
                description = stringResource(R.string.ui_switch_to_branch_278b039a),
                onClick = onSwitchClick,
            )
        }
        BranchRowPill(
            label = "✕",
            description = stringResource(R.string.ui_delete_branch_1c3f98e5),
            danger = true,
            onClick = onDeleteClick,
        )
    }
}

/** Outline capsule with a 48dp hit target. */
@Composable
private fun BranchRowPill(label: String, description: String, onClick: () -> Unit, danger: Boolean = false) {
    val c = Tide.colors
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = 36.dp)
                .alpha(1f)
                .border(1.dp, if (danger) c.danger.copy(alpha = 0.35f) else c.lineSoft, CircleShape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = TideType.sans(14.sp, FontWeight.SemiBold), color = if (danger) c.danger else c.ink, maxLines = 1)
        }
    }
}

/**
 * Background tint for the branch type pill.
 */
@Composable
fun branchTypeBgColor(type: String): Color = branchTypeTextColor(type).copy(alpha = 0.12f)

/**
 * Text color for the branch type pill: orange = local only (yours),
 * blue = remote (theirs), green = tracking (paired with the remote).
 */
@Composable
fun branchTypeTextColor(type: String): Color = when (type) {
    "LOCAL" -> Tide.colors.ahead
    "REMOTE" -> Tide.colors.behind
    "TRACKING" -> Tide.colors.synced
    else -> Tide.colors.inkMid
}

/**
 * Get display label for branch type.
 */
fun branchTypeLabel(type: String): String {
    return when (type) {
        "LOCAL" -> "local"
        "REMOTE" -> "remote"
        "TRACKING" -> "tracking"
        else -> "unknown"
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

private val previewBranches = listOf(
    Branch("main", BranchType.TRACKING, isHead = true, trackingBranch = "origin/main", lastCommit = "e19c4a0d8b7f", lastCommitMessage = "Tidy notes"),
    Branch("feature/tide", BranchType.LOCAL, lastCommit = "a1b2c3d4e5f6", lastCommitMessage = "Rail polish"),
    Branch("origin/release", BranchType.REMOTE),
)

@Preview(name = "Branches · light")
@Composable
private fun BranchListLightPreview() = SyncMdTheme(darkTheme = false) {
    Box(Modifier.background(Tide.colors.paper).padding(16.dp)) { BranchList(previewBranches, previewBranches.first(), {}, {}) }
}

@Preview(name = "Branches · dark")
@Composable
private fun BranchListDarkPreview() = SyncMdTheme(darkTheme = true) {
    Box(Modifier.background(Tide.colors.paper).padding(16.dp)) { BranchList(previewBranches, previewBranches.first(), {}, {}) }
}

@Preview(name = "Branches · empty")
@Composable
private fun BranchListEmptyPreview() = SyncMdTheme(darkTheme = false) {
    Box(Modifier.background(Tide.colors.paper)) { BranchList(emptyList(), null, {}, {}) }
}

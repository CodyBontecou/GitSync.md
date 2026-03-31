package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.data.models.Branch

/**
 * Displays a list of branches with switch and delete actions.
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
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = "No branches available",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(branches) { branch ->
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
 * Single branch item in the branch list.
 */
@Composable
fun BranchListItem(
    branch: Branch,
    isCurrent: Boolean = false,
    onSwitchClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { if (!isCurrent) onSwitchClick() },
        color = if (isCurrent)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Current branch indicator
            if (isCurrent) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = "*",
                        modifier = Modifier.padding(4.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            } else {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.outlineVariant
                ) {
                    Text(
                        text = " ",
                        modifier = Modifier.padding(4.dp),
                        fontSize = 12.sp
                    )
                }
            }

            // Branch details
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Branch name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = branch.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Branch type badge
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = branchTypeBgColor(branch.type.name)
                    ) {
                        Text(
                            text = branchTypeLabel(branch.type.name),
                            modifier = Modifier.padding(2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = branchTypeTextColor(branch.type.name)
                        )
                    }
                }

                // Tracking info
                if (!branch.trackingBranch.isNullOrBlank()) {
                    Text(
                        text = "Tracking: ${branch.trackingBranch}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }

                // Last commit info
                if (!branch.lastCommitMessage.isNullOrBlank() && !branch.lastCommit.isNullOrBlank()) {
                    Text(
                        text = "${branch.lastCommit?.substring(0, 7)} - ${branch.lastCommitMessage}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }

            // Action buttons
            if (!isCurrent) {
                IconButton(
                    onClick = onSwitchClick,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Switch to branch",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            IconButton(
                onClick = onDeleteClick
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete branch",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Get background color for branch type badge.
 */
@Composable
fun branchTypeBgColor(type: String) = when (type) {
    "LOCAL" -> MaterialTheme.colorScheme.primaryContainer
    "REMOTE" -> MaterialTheme.colorScheme.secondaryContainer
    "TRACKING" -> MaterialTheme.colorScheme.tertiaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
}

/**
 * Get text color for branch type badge.
 */
@Composable
fun branchTypeTextColor(type: String) = when (type) {
    "LOCAL" -> MaterialTheme.colorScheme.onPrimaryContainer
    "REMOTE" -> MaterialTheme.colorScheme.onSecondaryContainer
    "TRACKING" -> MaterialTheme.colorScheme.onTertiaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
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

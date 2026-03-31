package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.data.models.DiffStatus
import com.bontecou.syncmd.data.models.FileDiff

/**
 * Displays a list of files with status badges and click handlers.
 */
@Composable
fun FileList(
    files: List<FileDiff>,
    selectedFilePath: String? = null,
    onFileClick: (String) -> Unit,
    onStageClick: (String) -> Unit,
    onUnstageClick: (String) -> Unit,
    isStaged: Boolean = false,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(files) { file ->
            FileListItem(
                fileDiff = file,
                isSelected = file.filePath == selectedFilePath,
                isStaged = isStaged,
                onFileClick = { onFileClick(file.filePath) },
                onStageClick = { onStageClick(file.filePath) },
                onUnstageClick = { onUnstageClick(file.filePath) }
            )
        }
    }
}

/**
 * Single file item in the file list.
 */
@Composable
fun FileListItem(
    fileDiff: FileDiff,
    isSelected: Boolean = false,
    isStaged: Boolean = false,
    onFileClick: () -> Unit,
    onStageClick: () -> Unit,
    onUnstageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onFileClick() },
        color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status badge
            Surface(
                modifier = Modifier.padding(end = 4.dp),
                shape = MaterialTheme.shapes.extraSmall,
                color = statusColor(fileDiff.status)
            ) {
                Text(
                    text = statusIcon(fileDiff.status),
                    modifier = Modifier.padding(4.dp),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // File path
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = fileDiff.filePath,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = FontFamily.Monospace
                )

                // Show change summary if available
                if (fileDiff.hunks.isNotEmpty()) {
                    val additions = fileDiff.hunks.sumOf { hunk ->
                        hunk.lines.count { it.type.name == "ADDITION" }
                    }
                    val deletions = fileDiff.hunks.sumOf { hunk ->
                        hunk.lines.count { it.type.name == "DELETION" }
                    }

                    Text(
                        text = "+$additions -$deletions lines",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Stage/Unstage button
            if (isStaged) {
                Surface(
                    modifier = Modifier.clickable { onUnstageClick() },
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Unstage",
                        modifier = Modifier.padding(6.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            } else {
                Surface(
                    modifier = Modifier.clickable { onStageClick() },
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Stage",
                        modifier = Modifier.padding(6.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

/**
 * Get the status badge color.
 */
@Composable
fun statusColor(status: DiffStatus): Color {
    return when (status) {
        DiffStatus.ADDED -> Color(0xFF4CAF50)      // Green
        DiffStatus.DELETED -> Color(0xFFF44336)    // Red
        DiffStatus.MODIFIED -> Color(0xFF2196F3)   // Blue
        DiffStatus.RENAMED -> Color(0xFF9C27B0)    // Purple
        DiffStatus.COPIED -> Color(0xFFFF9800)     // Orange
        DiffStatus.TYPE_CHANGE -> Color(0xFF00BCD4) // Cyan
        DiffStatus.UNKNOWN -> Color(0xFF9E9E9E)    // Gray
    }
}

/**
 * Get the status badge icon text.
 */
fun statusIcon(status: DiffStatus): String {
    return when (status) {
        DiffStatus.ADDED -> "A"
        DiffStatus.DELETED -> "D"
        DiffStatus.MODIFIED -> "M"
        DiffStatus.RENAMED -> "R"
        DiffStatus.COPIED -> "C"
        DiffStatus.TYPE_CHANGE -> "T"
        DiffStatus.UNKNOWN -> "?"
    }
}

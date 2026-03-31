package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.data.models.DiffHunk
import com.bontecou.syncmd.data.models.DiffLine
import com.bontecou.syncmd.data.models.DiffLineType
import com.bontecou.syncmd.data.models.UnifiedDiffResult

/**
 * Displays a unified diff with syntax highlighting.
 */
@Composable
fun DiffViewer(
    diff: UnifiedDiffResult?,
    modifier: Modifier = Modifier
) {
    if (diff == null || diff.files.isEmpty()) {
        Text(
            text = "No changes to display",
            modifier = modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(0.dp)
        ) {
            // File summary
            items(diff.files) { fileDiff ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    // File header
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = fileDiff.filePath,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Diff hunks
                    fileDiff.hunks.forEachIndexed { index, hunk ->
                        DiffHunkViewer(hunk)
                        if (index < fileDiff.hunks.size - 1) {
                            Divider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }

            // Summary statistics
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.tertiary)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Summary: ${diff.summary.filesChanged} files changed, " +
                                "+${diff.summary.insertions} -${diff.summary.deletions}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

/**
 * Displays a single diff hunk (a contiguous block of changes).
 */
@Composable
fun DiffHunkViewer(
    hunk: DiffHunk,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        // Hunk header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFE3E3E3))
                .padding(4.dp)
        ) {
            Text(
                text = "@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Color(0xFF666666)
            )
        }

        // Diff lines
        hunk.lines.forEach { line ->
            DiffLineViewer(line)
        }
    }
}

/**
 * Displays a single line in a diff.
 */
@Composable
fun DiffLineViewer(
    line: DiffLine,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(lineBackgroundColor(line.type))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Line type indicator
        Text(
            text = when (line.type) {
                DiffLineType.ADDITION -> "+"
                DiffLineType.DELETION -> "-"
                DiffLineType.CONTEXT -> " "
            },
            modifier = Modifier
                .padding(end = 4.dp)
                .background(lineIndicatorColor(line.type))
                .padding(horizontal = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = lineTextColor(line.type)
        )

        // Line content
        Text(
            text = line.content,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = lineTextColor(line.type),
            maxLines = 1
        )
    }
}

/**
 * Get the background color for a diff line.
 */
@Composable
fun lineBackgroundColor(type: DiffLineType): Color {
    return when (type) {
        DiffLineType.ADDITION -> Color(0xFFC8E6C9)     // Light green
        DiffLineType.DELETION -> Color(0xFFFFCDD2)     // Light red
        DiffLineType.CONTEXT -> Color(0xFFFAFAFA)      // Very light gray
    }
}

/**
 * Get the indicator color for a diff line.
 */
@Composable
fun lineIndicatorColor(type: DiffLineType): Color {
    return when (type) {
        DiffLineType.ADDITION -> Color(0xFF4CAF50)     // Green
        DiffLineType.DELETION -> Color(0xFFF44336)     // Red
        DiffLineType.CONTEXT -> Color(0xFFBDBDBD)      // Gray
    }
}

/**
 * Get the text color for a diff line.
 */
fun lineTextColor(type: DiffLineType): Color {
    return when (type) {
        DiffLineType.ADDITION -> Color(0xFF1B5E20)     // Dark green
        DiffLineType.DELETION -> Color(0xFFB71C1C)     // Dark red
        DiffLineType.CONTEXT -> Color(0xFF424242)      // Dark gray
    }
}

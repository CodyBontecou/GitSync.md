package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.data.models.Conflict

/**
 * Displays a three-way diff for conflict resolution.
 */
@Composable
fun ConflictViewer(
    conflict: Conflict,
    manualResolution: String = "",
    onManualResolutionChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Title
        Text(
            text = "Resolving: ${conflict.filePath}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        // Three-way diff
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Base content (if available)
                val baseContent = conflict.baseContent
                if (baseContent != null && baseContent.isNotBlank()) {
                    ConflictPanel(
                        title = "Base",
                        content = baseContent,
                        backgroundColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                    Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }

                // Our content
                ConflictPanel(
                    title = "Ours (Current Branch)",
                    content = conflict.currentContent,
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer
                )
                Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

                // Their content
                ConflictPanel(
                    title = "Theirs (Incoming Branch)",
                    content = conflict.incomingContent,
                    backgroundColor = MaterialTheme.colorScheme.secondaryContainer
                )
            }
        }

        // Manual resolution editor
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Manual Resolution",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            OutlinedTextField(
                value = manualResolution,
                onValueChange = onManualResolutionChange,
                label = { Text("Resolved content") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .padding(horizontal = 12.dp),
                placeholder = { Text("Edit the content here to manually resolve the conflict") },
                maxLines = Int.MAX_VALUE,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            )

            Text(
                text = "${manualResolution.length} characters",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}

/**
 * Displays a single conflict panel (ours/theirs/base).
 */
@Composable
fun ConflictPanel(
    title: String,
    content: String,
    backgroundColor: androidx.compose.ui.graphics.Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Title
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace
        )

        // Content
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp),
            shape = MaterialTheme.shapes.extraSmall
        ) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            )
        }

        // Stats
        Text(
            text = "${content.lines().size} lines, ${content.length} bytes",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.sp
        )
    }
}

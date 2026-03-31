package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.ui.viewmodels.PullViewModel

/**
 * Status screen displays repository status, working tree changes, and pull options.
 */
@Composable
fun StatusScreen(
    viewModel: PullViewModel = hiltViewModel()
) {
    // Initialize with a default repository path (this would come from config/settings)
    LaunchedEffect(Unit) {
        viewModel.setRepositoryPath("/tmp/sync-md-repo")
    }

    val status by viewModel.status.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Text(
            text = "Repository Status",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // Error message
        if (errorMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = errorMessage!!,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Loading state
        if (isLoading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text(text = "Loading status...")
            }
        } else if (status != null) {
            val repoStatus = status!!

            // Status summary card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Branch: ${repoStatus.currentBranch}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (repoStatus.isClean) "State: Clean" else "State: Dirty (${repoStatus.totalChanges} changes)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // File status sections
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Modified files
                if (repoStatus.modifiedFiles.isNotEmpty()) {
                    item {
                        FileStatusSection(
                            title = "Modified (${repoStatus.modifiedFiles.size})",
                            files = repoStatus.modifiedFiles,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }

                // Staged files
                if (repoStatus.stagedFiles.isNotEmpty()) {
                    item {
                        FileStatusSection(
                            title = "Staged (${repoStatus.stagedFiles.size})",
                            files = repoStatus.stagedFiles,
                            backgroundColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    }
                }

                // Untracked files
                if (repoStatus.untrackedFiles.isNotEmpty()) {
                    item {
                        FileStatusSection(
                            title = "Untracked (${repoStatus.untrackedFiles.size})",
                            files = repoStatus.untrackedFiles,
                            backgroundColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    }
                }

                // Clean state
                if (repoStatus.isClean) {
                    item {
                        Text(
                            text = "Working tree is clean",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.fetch() },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Fetch",
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("Fetch")
                }

                Button(
                    onClick = { viewModel.executePull() },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text("Pull")
                }
            }
        } else {
            Text(
                text = "No repository loaded",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Composable for displaying a section of files with the same status.
 */
@Composable
private fun FileStatusSection(
    title: String,
    files: List<GitStatusEntry>,
    backgroundColor: androidx.compose.ui.graphics.Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                files.take(5).forEach { file ->
                    Text(
                        text = file.filePath,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(4.dp)
                    )
                }

                if (files.size > 5) {
                    Text(
                        text = "... and ${files.size - 5} more",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }
    }
}

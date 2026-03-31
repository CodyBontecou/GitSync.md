package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.ui.components.DiffViewer
import com.bontecou.syncmd.ui.components.FileList
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel

/**
 * Commit screen for staging files, viewing diffs, and creating commits.
 */
@Composable
fun CommitScreen(
    repositoryPath: String = "/tmp/sync-md-repo",
    viewModel: DiffViewModel = hiltViewModel()
) {
    // Initialize with provided repository path
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) {
            viewModel.setRepositoryPath(repositoryPath)
        }
    }

    val diff by viewModel.diff.collectAsState()
    val selectedFilePath by viewModel.selectedFilePath.collectAsState()
    val selectedFileDiff by viewModel.selectedFileDiff.collectAsState()
    val commitMessage by viewModel.commitMessage.collectAsState()
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
            text = "Commit Changes",
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
                Text(text = "Loading changes...")
            }
        } else if (diff != null) {
            // Two-pane layout: Files on left, Diff on right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Left pane: File list
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.4f)
                ) {
                    // Untracked/Modified files section
                    if (diff!!.files.isNotEmpty()) {
                        Text(
                            text = "Changes to Stage",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )

                        FileList(
                            files = diff!!.files,
                            selectedFilePath = selectedFilePath,
                            onFileClick = { viewModel.selectFile(it) },
                            onStageClick = { viewModel.stageFile(it) },
                            onUnstageClick = { viewModel.unstageFile(it) },
                            isStaged = false,
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }

                // Right pane: Diff viewer
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.6f)
                ) {
                    if (selectedFilePath != null) {
                        Text(
                            text = "Diff: $selectedFilePath",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )

                        DiffViewer(
                            diff = selectedFileDiff,
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Select a file to view changes",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom section: Commit message and buttons
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Commit message input
                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { viewModel.setCommitMessage(it) },
                        label = { Text("Commit message") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        supportingText = {
                            Text(text = "${commitMessage.length} characters")
                        }
                    )

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.stageAll() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Stage All")
                        }

                        OutlinedButton(
                            onClick = { viewModel.unstageAll() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Unstage All")
                        }

                        Button(
                            onClick = { viewModel.commit() },
                            modifier = Modifier.weight(1f),
                            enabled = commitMessage.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Commit",
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text("Commit")
                        }
                    }
                }
            }
        }
    }
}

package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.ui.components.ConflictList
import com.bontecou.syncmd.ui.components.ConflictResolutionDialog
import com.bontecou.syncmd.ui.components.ConflictViewer
import com.bontecou.syncmd.ui.components.MergeAbortDialog
import com.bontecou.syncmd.ui.components.MergeCompletionDialog
import com.bontecou.syncmd.ui.viewmodels.ConflictViewModel

/**
 * Screen for resolving merge conflicts.
 */
@Composable
fun ConflictScreen(
    repositoryPath: String = "/tmp/sync-md-repo",
    viewModel: ConflictViewModel = hiltViewModel()
) {
    // Initialize with provided repository path
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) {
            viewModel.setRepositoryPath(repositoryPath)
        }
    }

    val conflicts by viewModel.conflicts.collectAsState()
    val selectedConflict by viewModel.selectedConflict.collectAsState()
    val manualResolution by viewModel.manualResolutionContent.collectAsState()
    val resolvedConflicts by viewModel.resolvedConflicts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val mergeState by viewModel.mergeState.collectAsState()

    var showResolutionDialog by remember { mutableStateOf(false) }
    var selectedResolutionStrategy by remember { mutableStateOf<ConflictResolutionStrategy?>(null) }
    var showMergeCompletionDialog by remember { mutableStateOf(false) }
    var showMergeAbortDialog by remember { mutableStateOf(false) }

    val unresolvedCount = conflicts.size - resolvedConflicts.size
    val totalConflicts = conflicts.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Text(
            text = "Merge Conflict Resolution",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // Merge info
        if (mergeState != null) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Merging from: ${mergeState?.sourceBranch ?: "unknown"}",
                        style = MaterialTheme.typography.labelMedium
                    )

                    // Progress bar
                    LinearProgressIndicator(
                        progress = if (totalConflicts > 0) resolvedConflicts.size.toFloat() / totalConflicts else 1f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Resolved: ${resolvedConflicts.size}/$totalConflicts conflicts",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

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
                Text(text = "Processing...")
            }
        } else if (totalConflicts == 0) {
            // No conflicts
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = "All resolved",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(32.dp)
                )
                Text(
                    text = "All conflicts resolved!",
                    style = MaterialTheme.typography.headlineSmall
                )
                Button(
                    onClick = { showMergeCompletionDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Complete Merge")
                }
            }
        } else {
            // Two-pane layout: conflicts list + conflict detail
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Left pane: conflict list
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(0.35f)
                ) {
                    Text(
                        text = "Conflicts",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )

                    ConflictList(
                        conflicts = conflicts,
                        resolvedConflicts = resolvedConflicts,
                        onSelectConflict = { viewModel.selectConflict(it) },
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    )
                }

                // Right pane: conflict detail
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(0.65f)
                ) {
                    if (selectedConflict != null) {
                        Text(
                            text = "Resolution",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )

                        ConflictViewer(
                            conflict = selectedConflict!!,
                            manualResolution = manualResolution,
                            onManualResolutionChange = { viewModel.setManualResolutionContent(it) },
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Resolution buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    selectedResolutionStrategy = ConflictResolutionStrategy.OURS
                                    showResolutionDialog = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Accept Ours")
                            }

                            OutlinedButton(
                                onClick = {
                                    selectedResolutionStrategy = ConflictResolutionStrategy.THEIRS
                                    showResolutionDialog = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Accept Theirs")
                            }

                            Button(
                                onClick = {
                                    selectedResolutionStrategy = ConflictResolutionStrategy.MANUAL
                                    showResolutionDialog = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Accept Manual")
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Select a conflict to view details",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.loadConflicts() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("Refresh")
                }

                Button(
                    onClick = { showMergeCompletionDialog = true },
                    modifier = Modifier.weight(1f),
                    enabled = unresolvedCount == 0
                ) {
                    Icon(
                        imageVector = Icons.Default.Done,
                        contentDescription = "Complete",
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("Complete Merge")
                }

                OutlinedButton(
                    onClick = { showMergeAbortDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Abort",
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("Abort")
                }
            }
        }
    }

    // Dialogs
    if (showResolutionDialog && selectedConflict != null && selectedResolutionStrategy != null) {
        ConflictResolutionDialog(
            filePath = selectedConflict!!.filePath,
            strategy = selectedResolutionStrategy!!.name,
            onConfirm = {
                when (selectedResolutionStrategy!!) {
                    ConflictResolutionStrategy.OURS -> viewModel.acceptOurs(selectedConflict!!.filePath)
                    ConflictResolutionStrategy.THEIRS -> viewModel.acceptTheirs(selectedConflict!!.filePath)
                    ConflictResolutionStrategy.MANUAL -> viewModel.acceptManual(selectedConflict!!.filePath)
                    else -> {}
                }
            },
            onDismiss = { showResolutionDialog = false }
        )
    }

    if (showMergeCompletionDialog) {
        MergeCompletionDialog(
            unresolvedCount = unresolvedCount,
            onConfirm = { viewModel.completeMerge("Merge completed") },
            onDismiss = { showMergeCompletionDialog = false }
        )
    }

    if (showMergeAbortDialog) {
        MergeAbortDialog(
            onConfirm = { viewModel.abortMerge() },
            onDismiss = { showMergeAbortDialog = false }
        )
    }
}

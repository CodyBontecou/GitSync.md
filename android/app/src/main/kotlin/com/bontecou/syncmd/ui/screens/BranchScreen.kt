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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.ui.components.BranchList
import com.bontecou.syncmd.ui.components.CreateBranchDialog
import com.bontecou.syncmd.ui.components.DeleteBranchDialog
import com.bontecou.syncmd.ui.components.MergeBranchDialog
import com.bontecou.syncmd.ui.viewmodels.BranchViewModel

/**
 * Branch management screen for switching, creating, deleting, and merging branches.
 */
@Composable
fun BranchScreen(
    repositoryPath: String = "/tmp/sync-md-repo",
    viewModel: BranchViewModel = hiltViewModel()
) {
    // Initialize with provided repository path
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) {
            viewModel.setRepositoryPath(repositoryPath)
        }
    }

    val localBranches by viewModel.localBranches.collectAsState()
    val remoteBranches by viewModel.remoteBranches.collectAsState()
    val currentBranch by viewModel.currentBranch.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val mergeInProgress by viewModel.mergeInProgress.collectAsState()

    var selectedTabIndex by remember { mutableStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedBranchForDelete by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Text(
            text = "Branch Management",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // Current branch info
        if (currentBranch != null) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Current Branch",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = currentBranch!!.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.loadBranches() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
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
                Text(text = "Loading branches...")
            }
        } else {
            // Branch tabs
            TabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Local (${localBranches.size})") }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Remote (${remoteBranches.size})") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Branch list content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (selectedTabIndex) {
                    0 -> {
                        // Local branches
                        BranchList(
                            branches = localBranches,
                            currentBranch = currentBranch,
                            onSwitchBranch = { viewModel.switchBranch(it) },
                            onDeleteBranch = {
                                selectedBranchForDelete = it
                                showDeleteDialog = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        )
                    }

                    1 -> {
                        // Remote branches
                        BranchList(
                            branches = remoteBranches,
                            currentBranch = currentBranch,
                            onSwitchBranch = { viewModel.switchBranch(it) },
                            onDeleteBranch = {
                                selectedBranchForDelete = it
                                showDeleteDialog = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create",
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("Create Branch")
                }

                Button(
                    onClick = { showMergeDialog = true },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading && !mergeInProgress
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Merge",
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text("Merge")
                }
            }
        }
    }

    // Dialogs
    if (showCreateDialog) {
        CreateBranchDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, startPoint ->
                viewModel.createBranch(name, startPoint)
            },
            availableBranches = localBranches + remoteBranches
        )
    }

    if (showMergeDialog && currentBranch != null) {
        MergeBranchDialog(
            onDismiss = { showMergeDialog = false },
            onMerge = { source, strategy ->
                viewModel.mergeBranch(source, strategy)
            },
            currentBranch = currentBranch!!.name,
            availableBranches = localBranches + remoteBranches
        )
    }

    if (showDeleteDialog) {
        DeleteBranchDialog(
            branchName = selectedBranchForDelete,
            onDismiss = { showDeleteDialog = false },
            onConfirm = { force ->
                viewModel.deleteBranch(selectedBranchForDelete, force)
            }
        )
    }
}

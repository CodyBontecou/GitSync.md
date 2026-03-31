package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.bontecou.syncmd.data.models.RevertStrategy
import com.bontecou.syncmd.ui.components.CommitList
import com.bontecou.syncmd.ui.components.DeleteBranchDialog
import com.bontecou.syncmd.ui.components.StashList
import com.bontecou.syncmd.ui.components.TagList
import com.bontecou.syncmd.ui.viewmodels.HistoryViewModel

/**
 * History screen for viewing commits, reverting, and managing stashes/tags.
 */
@Composable
fun HistoryScreen(
    repositoryPath: String = "/tmp/sync-md-repo",
    viewModel: HistoryViewModel = hiltViewModel()
) {
    // Initialize with provided repository path
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) {
            viewModel.setRepositoryPath(repositoryPath)
        }
    }

    val commits by viewModel.commits.collectAsState()
    val stashes by viewModel.stashes.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var selectedTabIndex by remember { mutableStateOf(0) }
    var showDeleteTagDialog by remember { mutableStateOf(false) }
    var selectedTagForDelete by remember { mutableStateOf("") }
    var showDeleteCommitDialog by remember { mutableStateOf(false) }
    var selectedCommitForRevert by remember { mutableStateOf("") }
    var showDeleteStashDialog by remember { mutableStateOf(false) }
    var selectedStashForDelete by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Text(
            text = "History & Recovery",
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

        // Refresh button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = { viewModel.loadHistory(); viewModel.loadStashes(); viewModel.loadTags() }
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh"
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
                Text(text = "Loading...")
            }
        } else {
            // Tabs
            TabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Commits (${commits.size})") }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Stash (${stashes.size})") }
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = { Text("Tags (${tags.size})") }
                )
            }

            // Content
            when (selectedTabIndex) {
                0 -> {
                    // Commits tab
                    CommitList(
                        commits = commits,
                        onRevertCommit = {
                            selectedCommitForRevert = it
                            showDeleteCommitDialog = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    )
                }

                1 -> {
                    // Stash tab
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StashList(
                            stashes = stashes,
                            onStashApply = { viewModel.stashApply(it) },
                            onStashPop = { viewModel.stashPop(it) },
                            onStashDrop = {
                                selectedStashForDelete = it
                                showDeleteStashDialog = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        )

                        Button(
                            onClick = { viewModel.stashSave() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Create",
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text("Create Stash")
                        }
                    }
                }

                2 -> {
                    // Tags tab
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TagList(
                            tags = tags,
                            onDeleteTag = {
                                selectedTagForDelete = it
                                showDeleteTagDialog = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        )

                        Button(
                            onClick = { },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Create",
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text("Create Tag")
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (showDeleteCommitDialog) {
        DeleteBranchDialog(
            branchName = selectedCommitForRevert.take(7),
            onDismiss = { showDeleteCommitDialog = false },
            onConfirm = { force ->
                val strategy = if (force) RevertStrategy.HARD_RESET else RevertStrategy.CREATE_NEW_COMMIT
                viewModel.revertCommit(selectedCommitForRevert, strategy)
                showDeleteCommitDialog = false
            }
        )
    }

    if (showDeleteStashDialog) {
        DeleteBranchDialog(
            branchName = selectedStashForDelete,
            onDismiss = { showDeleteStashDialog = false },
            onConfirm = {
                viewModel.stashDrop(selectedStashForDelete)
                showDeleteStashDialog = false
            }
        )
    }

    if (showDeleteTagDialog) {
        DeleteBranchDialog(
            branchName = selectedTagForDelete,
            onDismiss = { showDeleteTagDialog = false },
            onConfirm = {
                viewModel.deleteTag(selectedTagForDelete)
                showDeleteTagDialog = false
            }
        )
    }
}

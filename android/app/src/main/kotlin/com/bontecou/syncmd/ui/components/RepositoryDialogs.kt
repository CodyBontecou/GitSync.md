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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.ui.viewmodels.SavedRepository

/**
 * Dialog for adding a new repository.
 */
@Composable
fun AddRepositoryDialog(
    onDismiss: () -> Unit,
    onAddRepository: (name: String, path: String, alias: String) -> Unit
) {
    var repoName by remember { mutableStateOf("") }
    var repoPath by remember { mutableStateOf("") }
    var repoAlias by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Text(text = "Add Repository")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = repoName,
                    onValueChange = { repoName = it },
                    label = { Text("Repository name") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g., MyProject") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = repoPath,
                    onValueChange = { repoPath = it },
                    label = { Text("Repository path") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("/path/to/repo") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = repoAlias,
                    onValueChange = { repoAlias = it },
                    label = { Text("Alias (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g., prod") },
                    singleLine = true
                )

                Text(
                    text = "Enter the full path to your Git repository.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (repoName.isNotBlank() && repoPath.isNotBlank()) {
                        onAddRepository(repoName, repoPath, repoAlias)
                        onDismiss()
                    }
                },
                enabled = repoName.isNotBlank() && repoPath.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { onDismiss() }) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for selecting a repository from saved list.
 */
@Composable
fun RepositoryListDialog(
    repositories: List<SavedRepository>,
    currentRepository: String = "",
    onSelectRepository: (String) -> Unit,
    onRemoveRepository: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var repositoryToRemove by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Text(text = "Select Repository")
        },
        text = {
            if (repositories.isEmpty()) {
                Text(
                    text = "No repositories saved. Add one in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(repositories) { repo ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectRepository(repo.path)
                                    onDismiss()
                                },
                            color = if (repo.path == currentRepository)
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
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = repo.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = repo.path,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (repo.alias.isNotBlank()) {
                                        Text(
                                            text = "Alias: ${repo.alias}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        repositoryToRemove = repo.path
                                        showRemoveConfirm = true
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onDismiss() }) {
                Text("Done")
            }
        }
    )

    // Remove confirmation dialog
    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove Repository") },
            text = { Text("Are you sure you want to remove this repository?") },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveRepository(repositoryToRemove)
                        showRemoveConfirm = false
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRemoveConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

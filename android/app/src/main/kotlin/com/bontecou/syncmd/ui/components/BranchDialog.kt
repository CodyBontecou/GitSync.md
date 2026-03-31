package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.data.models.MergeStrategy

/**
 * Dialog for creating a new branch.
 */
@Composable
fun CreateBranchDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, startPoint: String) -> Unit,
    availableBranches: List<Branch> = emptyList()
) {
    var branchName by remember { mutableStateOf("") }
    var startPoint by remember { mutableStateOf("HEAD") }
    var showStartPointMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Text(text = "Create New Branch")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = branchName,
                    onValueChange = { branchName = it },
                    label = { Text("Branch name") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g., feature/new-ui") },
                    singleLine = true
                )

                // Start point selector
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = startPoint,
                        onValueChange = { },
                        label = { Text("Start from") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        readOnly = true,
                        singleLine = true
                    )

                    DropdownMenu(
                        expanded = showStartPointMenu,
                        onDismissRequest = { showStartPointMenu = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // HEAD option
                        DropdownMenuItem(
                            text = { Text("HEAD") },
                            onClick = {
                                startPoint = "HEAD"
                                showStartPointMenu = false
                            }
                        )

                        // Available branches
                        availableBranches.forEach { branch ->
                            DropdownMenuItem(
                                text = { Text(branch.name) },
                                onClick = {
                                    startPoint = branch.name
                                    showStartPointMenu = false
                                }
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { showStartPointMenu = !showStartPointMenu },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Choose start point")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (branchName.isNotBlank()) {
                        onCreate(branchName, startPoint)
                        onDismiss()
                    }
                },
                enabled = branchName.isNotBlank()
            ) {
                Text("Create")
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
 * Dialog for merging branches.
 */
@Composable
fun MergeBranchDialog(
    onDismiss: () -> Unit,
    onMerge: (sourceBranch: String, strategy: MergeStrategy) -> Unit,
    currentBranch: String = "",
    availableBranches: List<Branch> = emptyList()
) {
    var sourceBranch by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf(MergeStrategy.PREFER_FF) }
    var showStrategyMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Text(text = "Merge Branch into $currentBranch")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Select source branch to merge:",
                    style = MaterialTheme.typography.labelMedium
                )

                // Source branch selector
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    availableBranches
                        .filter { it.name != currentBranch }
                        .forEach { branch ->
                            OutlinedButton(
                                onClick = { sourceBranch = branch.name },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                enabled = sourceBranch != branch.name
                            ) {
                                Text(
                                    text = branch.name,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                }

                // Strategy selector
                if (sourceBranch.isNotBlank()) {
                    Text(
                        text = "Merge strategy:",
                        style = MaterialTheme.typography.labelMedium
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = strategy.name.replace("_", " "),
                            onValueChange = { },
                            label = { Text("Strategy") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            readOnly = true,
                            singleLine = true
                        )

                        DropdownMenu(
                            expanded = showStrategyMenu,
                            onDismissRequest = { showStrategyMenu = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            MergeStrategy.values().forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.name.replace("_", " ")) },
                                    onClick = {
                                        strategy = s
                                        showStrategyMenu = false
                                    }
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = { showStrategyMenu = !showStrategyMenu },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Choose strategy")
                        }
                    }

                    // Strategy descriptions
                    Text(
                        text = strategyDescription(strategy),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (sourceBranch.isNotBlank()) {
                        onMerge(sourceBranch, strategy)
                        onDismiss()
                    }
                },
                enabled = sourceBranch.isNotBlank()
            ) {
                Text("Merge")
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
 * Dialog for confirming branch deletion.
 */
@Composable
fun DeleteBranchDialog(
    branchName: String,
    onDismiss: () -> Unit,
    onConfirm: (force: Boolean) -> Unit
) {
    var useForce by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Text(text = "Delete Branch?")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Delete branch \"$branchName\"?",
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { useForce = !useForce },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = if (useForce) "Force delete (unsafe)" else "Normal delete")
                    }
                }

                Text(
                    text = if (useForce)
                        "Force deletion will delete even if not merged. Use with caution!"
                    else
                        "This will only delete if the branch is fully merged.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(useForce)
                    onDismiss()
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete")
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
 * Get a description of a merge strategy.
 */
fun strategyDescription(strategy: MergeStrategy): String {
    return when (strategy) {
        MergeStrategy.FAST_FORWARD ->
            "Only merge if source is directly ahead. Preserves linear history."
        MergeStrategy.RECURSIVE ->
            "Always create a merge commit, even if fast-forward is possible."
        MergeStrategy.PREFER_FF ->
            "Fast-forward if possible, otherwise create a merge commit."
    }
}

package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Error dialog showing error message with details and actions.
 */
@Composable
fun ErrorDialog(
    title: String = "Error",
    message: String,
    details: String? = null,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Text(text = title)
        },
        icon = {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Error",
                tint = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!details.isNullOrBlank()) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (onRetry != null) {
                Button(onClick = { onRetry(); onDismiss() }) {
                    Text("Retry")
                }
            } else {
                Button(onClick = { onDismiss() }) {
                    Text("OK")
                }
            }
        },
        dismissButton = {
            if (onRetry != null) {
                OutlinedButton(onClick = { onDismiss() }) {
                    Text("Dismiss")
                }
            }
        }
    )
}

/**
 * Warning dialog for potentially destructive actions.
 */
@Composable
fun WarningDialog(
    title: String = "Warning",
    message: String,
    confirmText: String = "Continue",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Text(text = title)
        },
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Warning",
                tint = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(); onDismiss() },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(confirmText)
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
 * Success dialog showing completion of an action.
 */
@Composable
fun SuccessDialog(
    title: String = "Success",
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Text(text = title)
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = { onDismiss() }) {
                Text("OK")
            }
        }
    )
}

/**
 * Info dialog for informational messages.
 */
@Composable
fun InfoDialog(
    title: String = "Information",
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Text(text = title)
        },
        icon = {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Info",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = { onDismiss() }) {
                Text("OK")
            }
        }
    )
}

/**
 * Helpful error messages for common git scenarios.
 */
object ErrorMessages {
    fun repositoryNotFound(path: String): String =
        "Repository not found at: $path\n\nCheck the path and try again."

    fun dirtyWorkTree(): String =
        "Cannot perform this operation with uncommitted changes.\n\nCommit or stash your changes first."

    fun mergeConflicts(count: Int): String =
        "Merge has $count unresolved conflicts.\n\nResolve them before completing the merge."

    fun networkError(): String =
        "Network error. Check your connection and try again."

    fun invalidGitRepository(): String =
        "This doesn't appear to be a valid Git repository.\n\nEnsure the path points to a .git folder or a working directory."

    fun commitFailed(reason: String): String =
        "Commit failed: $reason"

    fun pushFailed(reason: String): String =
        "Push failed: $reason"

    fun pullFailed(reason: String): String =
        "Pull failed: $reason"

    fun branchAlreadyExists(name: String): String =
        "Branch '$name' already exists.\n\nChoose a different name."

    fun cannotDeleteCurrentBranch(): String =
        "Cannot delete the current branch.\n\nSwitch to another branch first."

    fun invalidBranchName(): String =
        "Invalid branch name.\n\nUse alphanumeric characters, hyphens, and underscores."
}

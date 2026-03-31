package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.data.models.ConflictResolutionStrategy
import com.bontecou.syncmd.ui.theme.BBadge
import com.bontecou.syncmd.ui.theme.BBadgeStyle
import com.bontecou.syncmd.ui.theme.BCard
import com.bontecou.syncmd.ui.theme.BDestructiveButton
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.BEmptyState
import com.bontecou.syncmd.ui.theme.BLoading
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.BProgressBar
import com.bontecou.syncmd.ui.theme.BSmallActionButton
import com.bontecou.syncmd.ui.theme.BSectionHeader
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.components.ConflictResolutionDialog
import com.bontecou.syncmd.ui.components.MergeAbortDialog
import com.bontecou.syncmd.ui.components.MergeCompletionDialog
import com.bontecou.syncmd.ui.viewmodels.ConflictViewModel

@Composable
fun ConflictScreen(
    repositoryPath: String = "",
    viewModel: ConflictViewModel = hiltViewModel()
) {
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) viewModel.setRepositoryPath(repositoryPath)
    }

    val conflicts         by viewModel.conflicts.collectAsState()
    val resolvedConflicts by viewModel.resolvedConflicts.collectAsState()
    val isLoading         by viewModel.isLoading.collectAsState()
    val errorMessage      by viewModel.errorMessage.collectAsState()
    val mergeState        by viewModel.mergeState.collectAsState()
    val bc                = LocalBrutalColors.current

    var showResolutionDialog       by remember { mutableStateOf(false) }
    var selectedStrategy           by remember { mutableStateOf<ConflictResolutionStrategy?>(null) }
    var selectedConflictPath       by remember { mutableStateOf("") }
    var showMergeCompletionDialog  by remember { mutableStateOf(false) }
    var showMergeAbortDialog       by remember { mutableStateOf(false) }

    val totalConflicts   = conflicts.size
    val unresolvedCount  = totalConflicts - resolvedConflicts.size

    Box(modifier = Modifier.fillMaxSize().background(bc.bg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 20.dp, vertical = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Error
            if (errorMessage != null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bc.error.copy(alpha = 0.08f))
                            .border(1.dp, bc.error.copy(alpha = 0.4f))
                            .padding(12.dp)
                    ) {
                        Text(text = errorMessage!!, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.error))
                    }
                }
            }

            if (isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        BLoading(text = "Processing")
                    }
                }
            } else if (totalConflicts == 0) {
                // All resolved
                item {
                    Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                        BEmptyState(
                            title       = "No Conflicts",
                            subtitle    = "All conflicts resolved.\nReady to complete merge.",
                            actionTitle = "Complete Merge",
                            onAction    = { showMergeCompletionDialog = true },
                        )
                    }
                }
            } else {
                // ── Merge Info Card ───────────────────────────────────────
                if (mergeState != null) {
                    item {
                        BCard {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    BSectionHeader(title = "Conflict Center")
                                    BBadge(text = "$unresolvedCount remaining", style = if (unresolvedCount > 0) BBadgeStyle.ERROR else BBadgeStyle.SUCCESS)
                                }
                                BProgressBar(
                                    progress = if (totalConflicts > 0)
                                        resolvedConflicts.size.toFloat() / totalConflicts
                                    else 1f
                                )
                                Text(
                                    text = "Merging from: ${mergeState!!.sourceBranch ?: "unknown"}",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        color = bc.textMid,
                                    )
                                )
                            }
                        }
                    }
                }

                // ── Conflict Files Card ───────────────────────────────────
                item {
                    BCard {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                BSectionHeader(title = "Conflicts")
                                BBadge(text = "$totalConflicts files", style = BBadgeStyle.ERROR)
                            }

                            BDivider()

                            conflicts.forEachIndexed { idx, conflict ->
                                val isResolved = resolvedConflicts.contains(conflict.filePath)
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = conflict.filePath,
                                            style = TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp,
                                                color = bc.text,
                                            ),
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (isResolved) {
                                            BBadge(text = "Resolved", style = BBadgeStyle.SUCCESS)
                                        }
                                    }

                                    if (!isResolved) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            BSmallActionButton(title = "Ours",   onClick = {
                                                selectedConflictPath = conflict.filePath
                                                selectedStrategy = ConflictResolutionStrategy.OURS
                                                showResolutionDialog = true
                                            })
                                            BSmallActionButton(title = "Theirs", onClick = {
                                                selectedConflictPath = conflict.filePath
                                                selectedStrategy = ConflictResolutionStrategy.THEIRS
                                                showResolutionDialog = true
                                            })
                                            BSmallActionButton(title = "Manual", onClick = {
                                                selectedConflictPath = conflict.filePath
                                                selectedStrategy = ConflictResolutionStrategy.MANUAL
                                                showResolutionDialog = true
                                            })
                                        }
                                    }
                                }
                                if (idx < conflicts.size - 1) {
                                    BDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                }
                            }
                        }
                    }
                }

                // ── Actions ───────────────────────────────────────────────
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        BPrimaryButton(
                            title      = "Complete Merge",
                            isDisabled = unresolvedCount > 0,
                            onClick    = { showMergeCompletionDialog = true },
                        )
                        BDestructiveButton(
                            title   = "Abort Merge",
                            onClick = { showMergeAbortDialog = true },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    // Dialogs
    if (showResolutionDialog && selectedStrategy != null) {
        ConflictResolutionDialog(
            filePath  = selectedConflictPath,
            strategy  = selectedStrategy!!.name,
            onConfirm = {
                val conflict = conflicts.find { it.filePath == selectedConflictPath }
                if (conflict != null) {
                    when (selectedStrategy!!) {
                        ConflictResolutionStrategy.OURS   -> viewModel.acceptOurs(selectedConflictPath)
                        ConflictResolutionStrategy.THEIRS -> viewModel.acceptTheirs(selectedConflictPath)
                        ConflictResolutionStrategy.MANUAL -> viewModel.acceptManual(selectedConflictPath)
                        else -> {}
                    }
                }
                showResolutionDialog = false
            },
            onDismiss = { showResolutionDialog = false },
        )
    }
    if (showMergeCompletionDialog) {
        MergeCompletionDialog(
            unresolvedCount = unresolvedCount,
            onConfirm = { viewModel.completeMerge("Merge completed") },
            onDismiss = { showMergeCompletionDialog = false },
        )
    }
    if (showMergeAbortDialog) {
        MergeAbortDialog(
            onConfirm = { viewModel.abortMerge() },
            onDismiss = { showMergeAbortDialog = false },
        )
    }
}

@Composable
private fun BDivider(modifier: Modifier) {
    Box(modifier = modifier) { com.bontecou.syncmd.ui.theme.BDivider() }
}

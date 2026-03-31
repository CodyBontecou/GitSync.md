package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.data.models.MergeStrategy
import com.bontecou.syncmd.ui.theme.BBadge
import com.bontecou.syncmd.ui.theme.BBadgeStyle
import com.bontecou.syncmd.ui.theme.BCard
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.BEmptyState
import com.bontecou.syncmd.ui.theme.BLoading
import com.bontecou.syncmd.ui.theme.BSmallActionButton
import com.bontecou.syncmd.ui.theme.BSectionHeader
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.components.CreateBranchDialog
import com.bontecou.syncmd.ui.components.DeleteBranchDialog
import com.bontecou.syncmd.ui.components.MergeBranchDialog
import com.bontecou.syncmd.ui.viewmodels.BranchViewModel

@Composable
fun BranchScreen(
    repositoryPath: String = "",
    viewModel: BranchViewModel = hiltViewModel()
) {
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) viewModel.setRepositoryPath(repositoryPath)
    }

    val localBranches  by viewModel.localBranches.collectAsState()
    val remoteBranches by viewModel.remoteBranches.collectAsState()
    val currentBranch  by viewModel.currentBranch.collectAsState()
    val isLoading      by viewModel.isLoading.collectAsState()
    val errorMessage   by viewModel.errorMessage.collectAsState()
    val bc             = LocalBrutalColors.current

    var newBranchName              by remember { mutableStateOf("") }
    var showCreateDialog           by remember { mutableStateOf(false) }
    var showMergeDialog            by remember { mutableStateOf(false) }
    var showDeleteDialog           by remember { mutableStateOf(false) }
    var selectedBranchForDelete    by remember { mutableStateOf("") }

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
                        BLoading(text = "Loading branches")
                    }
                }
            } else {
                // ── Branches Card ─────────────────────────────────────────
                item {
                    BCard {
                        Column {
                            // Header with current branch badge
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                BSectionHeader(title = "Branches")
                                if (currentBranch != null) {
                                    BBadge(text = currentBranch!!.name, style = BBadgeStyle.ACCENT)
                                }
                            }

                            BDivider()

                            // Create branch input row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(bc.surface)
                                        .border(1.dp, bc.borderSoft)
                                        .padding(horizontal = 10.dp, vertical = 9.dp)
                                ) {
                                    if (newBranchName.isEmpty()) {
                                        Text(
                                            text = "new-branch-name",
                                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.textFaint)
                                        )
                                    }
                                    BasicTextField(
                                        value = newBranchName,
                                        onValueChange = { newBranchName = it },
                                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.text),
                                        singleLine = true,
                                        cursorBrush = SolidColor(bc.text),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                val canCreate = newBranchName.trim().isNotEmpty()
                                Box(
                                    modifier = Modifier
                                        .background(bc.text.copy(alpha = if (canCreate) 1f else 0.3f))
                                        .clickable(
                                            enabled = canCreate,
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() },
                                        ) {
                                            viewModel.createBranch(newBranchName.trim(), "HEAD")
                                            newBranchName = ""
                                        }
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                ) {
                                    Text(
                                        text = "CREATE",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            letterSpacing = 1.sp,
                                            color = bc.bg,
                                        )
                                    )
                                }
                            }

                            // Branch list
                            if (localBranches.isNotEmpty()) {
                                BDivider()
                                localBranches.forEachIndexed { idx, branch ->
                                    BranchRow(
                                        branch     = branch,
                                        isCurrent  = branch.isHead || branch.name == currentBranch?.name,
                                        onSwitch   = { viewModel.switchBranch(branch.name) },
                                        onMerge    = { showMergeDialog = true },
                                        onDelete   = {
                                            selectedBranchForDelete = branch.name
                                            showDeleteDialog = true
                                        },
                                    )
                                    if (idx < localBranches.size - 1) {
                                        BDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                    }
                                }
                            } else {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    Text(
                                        text = "No local branches",
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = bc.textMid)
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Remote Branches Card ──────────────────────────────────
                if (remoteBranches.isNotEmpty()) {
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
                                    BSectionHeader(title = "Remote Branches")
                                    BBadge(text = "${remoteBranches.size}", style = BBadgeStyle.DEFAULT)
                                }
                                BDivider()
                                remoteBranches.forEachIndexed { idx, branch ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = branch.name,
                                            style = TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 13.sp,
                                                color = bc.text,
                                            ),
                                            modifier = Modifier.weight(1f),
                                        )
                                        BBadge(text = "remote", style = BBadgeStyle.DEFAULT)
                                    }
                                    if (idx < remoteBranches.size - 1) {
                                        BDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    // Dialogs
    if (showCreateDialog) {
        CreateBranchDialog(
            onDismiss = { showCreateDialog = false },
            onCreate  = { name, startPoint -> viewModel.createBranch(name, startPoint) },
            availableBranches = localBranches + remoteBranches,
        )
    }
    if (showMergeDialog && currentBranch != null) {
        MergeBranchDialog(
            onDismiss        = { showMergeDialog = false },
            onMerge          = { source, strategy -> viewModel.mergeBranch(source, strategy) },
            currentBranch    = currentBranch!!.name,
            availableBranches = localBranches + remoteBranches,
        )
    }
    if (showDeleteDialog) {
        DeleteBranchDialog(
            branchName = selectedBranchForDelete,
            onDismiss  = { showDeleteDialog = false },
            onConfirm  = { force -> viewModel.deleteBranch(selectedBranchForDelete, force) }
        )
    }
}

@Composable
private fun BranchRow(
    branch: Branch,
    isCurrent: Boolean,
    onSwitch: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    val bc = LocalBrutalColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = branch.name,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = bc.text,
                )
            )
            if (!branch.trackingBranch.isNullOrBlank()) {
                Text(
                    text = branch.trackingBranch!!,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = bc.textMid)
                )
            }
        }

        if (isCurrent) {
            BBadge(text = "CURRENT", style = BBadgeStyle.SUCCESS)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BSmallActionButton(title = "Switch", onClick = onSwitch)
                BSmallActionButton(title = "Merge",  onClick = onMerge)
                BSmallActionButton(title = "✕", isDestructive = true, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun BDivider(modifier: Modifier) {
    Box(modifier = modifier) { com.bontecou.syncmd.ui.theme.BDivider() }
}

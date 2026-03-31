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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.bontecou.syncmd.data.models.RevertStrategy
import com.bontecou.syncmd.ui.theme.BBadge
import com.bontecou.syncmd.ui.theme.BBadgeStyle
import com.bontecou.syncmd.ui.theme.BCard
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.BEmptyState
import com.bontecou.syncmd.ui.theme.BLoading
import com.bontecou.syncmd.ui.theme.BSmallActionButton
import com.bontecou.syncmd.ui.theme.BSectionHeader
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.components.DeleteBranchDialog
import com.bontecou.syncmd.ui.viewmodels.HistoryViewModel

@Composable
fun HistoryScreen(
    repositoryPath: String = "",
    viewModel: HistoryViewModel = hiltViewModel()
) {
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) viewModel.setRepositoryPath(repositoryPath)
    }

    val commits      by viewModel.commits.collectAsState()
    val stashes      by viewModel.stashes.collectAsState()
    val tags         by viewModel.tags.collectAsState()
    val isLoading    by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val bc           = LocalBrutalColors.current

    var selectedTab              by remember { mutableIntStateOf(0) }
    var showDeleteTagDialog      by remember { mutableStateOf(false) }
    var selectedTagForDelete     by remember { mutableStateOf("") }
    var showDeleteCommitDialog   by remember { mutableStateOf(false) }
    var selectedCommitForRevert  by remember { mutableStateOf("") }
    var showDeleteStashDialog    by remember { mutableStateOf(false) }
    var selectedStashForDelete   by remember { mutableStateOf("") }

    val tabs = listOf("Commits" to commits.size, "Stash" to stashes.size, "Tags" to tags.size)

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

            // Tab selector
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    tabs.forEachIndexed { idx, (label, count) ->
                        val isSelected = selectedTab == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .background(if (isSelected) bc.text else bc.bg)
                                .border(1.dp, bc.border)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                ) { selectedTab = idx },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "$label ($count)".uppercase(),
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 11.sp,
                                    letterSpacing = 0.5.sp,
                                    color = if (isSelected) bc.bg else bc.textMid,
                                )
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        BLoading(text = "Loading")
                    }
                }
            } else {
                when (selectedTab) {
                    // ── Commits ───────────────────────────────────────────
                    0 -> {
                        if (commits.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                                    BEmptyState(title = "No Commits", subtitle = "No commit history found.")
                                }
                            }
                        } else {
                            item {
                                BCard {
                                    Column {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            BSectionHeader(title = "Commits")
                                            BBadge(text = "${commits.size}", style = BBadgeStyle.DEFAULT)
                                        }
                                        BDivider()
                                        commits.forEachIndexed { idx, commit ->
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Text(
                                                    text = commit.message,
                                                    style = TextStyle(
                                                        fontFamily = FontFamily.Default,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontSize = 14.sp,
                                                        color = bc.text,
                                                    ),
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                ) {
                                                    Text(
                                                        text = commit.shortHash,
                                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = bc.accent)
                                                    )
                                                    Text(
                                                        text = commit.author,
                                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = bc.textMid)
                                                    )
                                                    BSmallActionButton(
                                                        title   = "Revert",
                                                        isDestructive = true,
                                                        onClick = {
                                                            selectedCommitForRevert = commit.hash
                                                            showDeleteCommitDialog = true
                                                        }
                                                    )
                                                }
                                            }
                                            if (idx < commits.size - 1) {
                                                BDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── Stash ─────────────────────────────────────────────
                    1 -> {
                        if (stashes.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                                    BEmptyState(
                                        title    = "No Stashes",
                                        subtitle = "Stash local changes to save them temporarily.",
                                        actionTitle = "Create Stash",
                                        onAction    = { viewModel.stashSave() }
                                    )
                                }
                            }
                        } else {
                            item {
                                BCard {
                                    Column {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            BSectionHeader(title = "Stash")
                                            BBadge(text = "${stashes.size}", style = BBadgeStyle.DEFAULT)
                                        }
                                        BDivider()
                                        stashes.forEachIndexed { idx, stash ->
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Text(
                                                    text = stash.name,
                                                    style = TextStyle(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Medium,
                                                        fontSize = 13.sp,
                                                        color = bc.text,
                                                    ),
                                                    maxLines = 2,
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    BSmallActionButton(title = "Apply", onClick = { viewModel.stashApply(stash.id) })
                                                    BSmallActionButton(title = "Pop",   onClick = { viewModel.stashPop(stash.id) })
                                                    Spacer(Modifier.weight(1f))
                                                    BSmallActionButton(
                                                        title = "✕",
                                                        isDestructive = true,
                                                        onClick = {
                                                            selectedStashForDelete = stash.id
                                                            showDeleteStashDialog = true
                                                        }
                                                    )
                                                }
                                            }
                                            if (idx < stashes.size - 1) {
                                                BDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── Tags ──────────────────────────────────────────────
                    2 -> {
                        if (tags.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                                    BEmptyState(title = "No Tags", subtitle = "No tags found in this repository.")
                                }
                            }
                        } else {
                            item {
                                BCard {
                                    Column {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            BSectionHeader(title = "Tags")
                                            BBadge(text = "${tags.size}", style = BBadgeStyle.DEFAULT)
                                        }
                                        BDivider()
                                        tags.forEachIndexed { idx, tag ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = tag.name,
                                                        style = TextStyle(
                                                            fontFamily = FontFamily.Monospace,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.sp,
                                                            color = bc.text,
                                                        )
                                                    )
                                                    if (!tag.message.isNullOrBlank()) {
                                                        Text(
                                                            text = tag.message!!,
                                                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = bc.textMid),
                                                            maxLines = 1,
                                                        )
                                                    }
                                                    Text(
                                                        text = tag.commitHash.take(7),
                                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = bc.accent)
                                                    )
                                                }
                                                BSmallActionButton(
                                                    title = "✕",
                                                    isDestructive = true,
                                                    onClick = {
                                                        selectedTagForDelete = tag.name
                                                        showDeleteTagDialog = true
                                                    }
                                                )
                                            }
                                            if (idx < tags.size - 1) {
                                                BDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                            }
                                        }
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
    if (showDeleteCommitDialog) {
        DeleteBranchDialog(
            branchName = selectedCommitForRevert.take(7),
            onDismiss  = { showDeleteCommitDialog = false },
            onConfirm  = { force ->
                val strategy = if (force) RevertStrategy.HARD_RESET else RevertStrategy.CREATE_NEW_COMMIT
                viewModel.revertCommit(selectedCommitForRevert, strategy)
                showDeleteCommitDialog = false
            }
        )
    }
    if (showDeleteStashDialog) {
        DeleteBranchDialog(
            branchName = selectedStashForDelete,
            onDismiss  = { showDeleteStashDialog = false },
            onConfirm  = { viewModel.stashDrop(selectedStashForDelete); showDeleteStashDialog = false }
        )
    }
    if (showDeleteTagDialog) {
        DeleteBranchDialog(
            branchName = selectedTagForDelete,
            onDismiss  = { showDeleteTagDialog = false },
            onConfirm  = { viewModel.deleteTag(selectedTagForDelete); showDeleteTagDialog = false }
        )
    }
}

@Composable
private fun BDivider(modifier: Modifier) {
    Box(modifier = modifier) { com.bontecou.syncmd.ui.theme.BDivider() }
}

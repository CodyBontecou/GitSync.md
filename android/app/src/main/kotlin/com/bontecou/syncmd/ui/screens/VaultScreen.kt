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
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.bontecou.syncmd.data.models.GitFileStatusKind
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.ui.theme.BActionRow
import com.bontecou.syncmd.ui.theme.BBadge
import com.bontecou.syncmd.ui.theme.BBadgeStyle
import com.bontecou.syncmd.ui.theme.BCard
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.BEmptyState
import com.bontecou.syncmd.ui.theme.BLoading
import com.bontecou.syncmd.ui.theme.BMonoRow
import com.bontecou.syncmd.ui.theme.BSectionHeader
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.theme.badgeFg
import com.bontecou.syncmd.ui.viewmodels.PullViewModel

/**
 * Vault screen — matches iOS VaultView.
 *
 * Shows the current repository overview:
 *   • Status hero card (branch, clean/dirty badge)
 *   • Repo health pills (modified / staged / untracked)
 *   • Changed files (collapsible)
 *   • ⬇ Pull action
 *   • ⬆ Commit & Push → opens GitScreen
 */
@Composable
fun VaultScreen(
    repositoryPath: String,
    showDebugInfo: Boolean = false,
    onOpenGit: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: PullViewModel = hiltViewModel(),
) {
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) viewModel.setRepositoryPath(repositoryPath)
    }

    val status       by viewModel.status.collectAsState()
    val isLoading    by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val bc           = LocalBrutalColors.current

    var showChangedFiles by remember { mutableStateOf(true) }

    // Derive the repo display name from the resolved filesystem path.
    // e.g. /data/.../files/repos/owner/repo  →  repoDisplayName = "repo", repoOwner = "OWNER"
    val repoDisplayName = repositoryPath.substringAfterLast("/").ifBlank { "Repository" }
    val repoOwner = repositoryPath
        .substringAfterLast("/repos/")
        .substringBefore("/")
        .uppercase()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bc.bg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top Bar ───────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bc.bg)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Back button
                    Row(
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onNavigateBack,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "←",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 16.sp,
                                color = bc.accent,
                            )
                        )
                        Text(
                            text = "REPOS",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                letterSpacing = 1.sp,
                                color = bc.accent,
                            )
                        )
                    }

                    // Repo name (centered)
                    Text(
                        text = repoDisplayName.uppercase(),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            letterSpacing = 2.sp,
                            color = bc.text,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )

                    // ⚙ opens GitScreen settings / pass-through
                    Box(
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onOpenGit,
                            )
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "⚙",
                            style = TextStyle(fontSize = 18.sp, color = bc.textMid)
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(bc.border))
            }

            // ── Scrollable body ───────────────────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp
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
                            Text(
                                text = errorMessage!!,
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.error)
                            )
                        }
                    }
                }

                if (isLoading) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BLoading(text = "Loading repository")
                        }
                    }
                } else if (repositoryPath.isBlank()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(400.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BEmptyState(
                                title    = "No Repository",
                                subtitle = "Go back and select a repository.",
                            )
                        }
                    }
                } else {
                    val repoStatus = status

                    // ── Status Hero Card ──────────────────────────────────
                    item {
                        BCard {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Text(
                                            text = repoDisplayName,
                                            style = TextStyle(
                                                fontFamily = FontFamily.Default,
                                                fontWeight = FontWeight.Black,
                                                fontSize = 20.sp,
                                                color = bc.text,
                                            )
                                        )
                                        if (repoOwner.isNotBlank()) {
                                            Text(
                                                text = repoOwner,
                                                style = TextStyle(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 12.sp,
                                                    letterSpacing = 1.sp,
                                                    color = bc.textMid,
                                                )
                                            )
                                        }
                                    }
                                    if (repoStatus != null) {
                                        BBadge(
                                            text  = if (repoStatus.isClean) "Up to date" else "${repoStatus.totalChanges} changes",
                                            style = if (repoStatus.isClean) BBadgeStyle.SUCCESS else BBadgeStyle.WARNING,
                                        )
                                    }
                                }

                                if (repoStatus != null) {
                                    BDivider()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        VaultMetaChip(icon = "⎇", text = repoStatus.currentBranch)
                                        VaultMetaChip(icon = "#", text = "—")
                                        VaultMetaChip(icon = "⏱", text = "now")
                                    }
                                }
                            }
                        }
                    }

                    // ── Repo Health Card ──────────────────────────────────
                    if (repoStatus != null) {
                        if (showDebugInfo) {
                            item {
                                BCard {
                                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            BSectionHeader(title = "Debug Info")
                                            BBadge(text = "DEBUG", style = BBadgeStyle.WARNING)
                                        }
                                        BDivider()
                                        BMonoRow(key = "Path", value = repositoryPath)
                                        if (status != null) {
                                            BDivider()
                                            BMonoRow(key = "Branch", value = status!!.currentBranch)
                                            BDivider()
                                            BMonoRow(key = "Modified", value = "${status!!.modifiedFiles.size}")
                                            BDivider()
                                            BMonoRow(key = "Staged", value = "${status!!.stagedFiles.size}")
                                            BDivider()
                                            BMonoRow(key = "Untracked", value = "${status!!.untrackedFiles.size}")
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            BCard {
                                Column {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                    ) {
                                        BSectionHeader(title = "Repo Health")
                                    }
                                    BDivider()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                                    ) {
                                        VaultHealthPill(label = "Changed",   count = repoStatus.modifiedFiles.size)
                                        VaultHealthPill(label = "Staged",    count = repoStatus.stagedFiles.size,    style = BBadgeStyle.SUCCESS)
                                        VaultHealthPill(label = "Untracked", count = repoStatus.untrackedFiles.size, style = BBadgeStyle.ACCENT)
                                    }
                                }
                            }
                        }

                        // ── Changed Files Card ────────────────────────────
                        val allChanged = repoStatus.allChanges
                        if (allChanged.isNotEmpty()) {
                            item {
                                BCard {
                                    Column {
                                        // Header with collapse toggle
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(
                                                    indication = null,
                                                    interactionSource = remember { MutableInteractionSource() },
                                                ) { showChangedFiles = !showChangedFiles }
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            BSectionHeader(title = "Changed Files")
                                            BBadge(text = "${allChanged.size}", style = BBadgeStyle.ACCENT)
                                            Spacer(Modifier.weight(1f))
                                            Text(
                                                text = if (showChangedFiles) "▲" else "▼",
                                                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = bc.textFaint)
                                            )
                                        }

                                        if (showChangedFiles) {
                                            BDivider()
                                            allChanged.sortedBy { it.filePath }.forEachIndexed { idx, entry ->
                                                ChangedFileRow(entry)
                                                if (idx < allChanged.size - 1) {
                                                    Box(Modifier.padding(horizontal = 16.dp)) { BDivider() }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ── Sync Actions ──────────────────────────────────────
                    // Pull
                    item {
                        BCard(
                            modifier = Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { viewModel.executePull() }
                        ) {
                            BActionRow(
                                icon     = "⬇",
                                title    = "Pull",
                                subtitle = "Fetch remote changes",
                            )
                        }
                    }

                    // Commit & Push → opens GitScreen
                    item {
                        val changeCount = status?.totalChanges ?: 0
                        BCard(
                            modifier = Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { onOpenGit() }
                        ) {
                            BActionRow(
                                icon       = "⬆",
                                title      = "Commit & Push",
                                subtitle   = "Push local changes to remote",
                                badge      = if (changeCount > 0) changeCount else null,
                                badgeStyle = BBadgeStyle.ACCENT,
                            )
                        }
                    }

                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }
}

@Composable
private fun VaultHealthPill(label: String, count: Int, style: BBadgeStyle = BBadgeStyle.DEFAULT) {
    val bc = LocalBrutalColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = "$count",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                color = bc.badgeFg(style),
            )
        )
        Text(
            text = label.uppercase(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                color = bc.textMid,
            )
        )
    }
}

@Composable
private fun VaultMetaChip(icon: String, text: String) {
    val bc = LocalBrutalColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = icon, style = TextStyle(fontSize = 12.sp, color = bc.textFaint))
        Text(
            text = text,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = bc.textMid,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChangedFileRow(entry: GitStatusEntry) {
    val bc    = LocalBrutalColors.current
    val label = when (entry.kind) {
        GitFileStatusKind.STAGED    -> "staged"
        GitFileStatusKind.MODIFIED  -> "modified"
        GitFileStatusKind.UNTRACKED -> "untracked"
    }
    val badgeStyle = when (entry.kind) {
        GitFileStatusKind.STAGED    -> BBadgeStyle.SUCCESS
        GitFileStatusKind.MODIFIED  -> BBadgeStyle.DEFAULT
        GitFileStatusKind.UNTRACKED -> BBadgeStyle.ACCENT
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = entry.filePath,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = bc.text,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = bc.textFaint,
                )
            )
        }
        BBadge(text = label, style = badgeStyle)
    }
}

@Composable
private fun BCard(modifier: Modifier, content: @Composable () -> Unit) {
    com.bontecou.syncmd.ui.theme.BCard(modifier = modifier, content = content)
}

@Composable
private fun BDivider() {
    com.bontecou.syncmd.ui.theme.BDivider()
}



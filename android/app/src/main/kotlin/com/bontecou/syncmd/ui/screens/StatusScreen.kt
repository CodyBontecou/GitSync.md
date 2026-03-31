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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
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
import com.bontecou.syncmd.ui.theme.BProgressBar
import com.bontecou.syncmd.ui.theme.BSectionHeader
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.theme.badgeFg
import com.bontecou.syncmd.ui.viewmodels.PullViewModel

@Composable
fun StatusScreen(
    repositoryPath: String = "",
    viewModel: PullViewModel = hiltViewModel()
) {
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) viewModel.setRepositoryPath(repositoryPath)
    }

    val status       by viewModel.status.collectAsState()
    val isLoading    by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val bc           = LocalBrutalColors.current

    var showChangedFiles by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bc.bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 20.dp, vertical = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── Error ─────────────────────────────────────────────────────
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
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = bc.error,
                            )
                        )
                    }
                }
            }

            // ── Loading ───────────────────────────────────────────────────
            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BLoading(text = "Loading status")
                    }
                }
            } else if (status != null) {
                val repoStatus = status!!

                // ── Status Hero Card ──────────────────────────────────────
                item {
                    BCard {
                        Column {
                            // Header row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        text = repoStatus.currentBranch,
                                        style = TextStyle(
                                            fontFamily = FontFamily.Default,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 20.sp,
                                            color = bc.text,
                                        )
                                    )
                                    Text(
                                        text = "REPOSITORY",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 12.sp,
                                            letterSpacing = 1.sp,
                                            color = bc.textMid,
                                        )
                                    )
                                }
                                BBadge(
                                    text  = if (repoStatus.isClean) "Clean" else "Dirty",
                                    style = if (repoStatus.isClean) BBadgeStyle.SUCCESS else BBadgeStyle.WARNING,
                                )
                            }

                            BDivider()

                            // Meta chips
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                MetaChip(icon = "⎇", text = repoStatus.currentBranch, bc.textMid)
                                MetaChip(icon = "⊙", text = "${repoStatus.totalChanges} changes", bc.textMid)
                            }
                        }
                    }
                }

                // ── Repo Health Card ──────────────────────────────────────
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
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                            ) {
                                HealthPill(label = "Modified",  count = repoStatus.modifiedFiles.size)
                                HealthPill(label = "Staged",    count = repoStatus.stagedFiles.size, style = BBadgeStyle.SUCCESS)
                                HealthPill(label = "Untracked", count = repoStatus.untrackedFiles.size, style = if (repoStatus.untrackedFiles.isNotEmpty()) BBadgeStyle.ACCENT else BBadgeStyle.DEFAULT)
                            }
                        }
                    }
                }

                // ── Changed Files Card ─────────────────────────────────────
                val allChanged = repoStatus.modifiedFiles + repoStatus.stagedFiles + repoStatus.untrackedFiles
                if (allChanged.isNotEmpty()) {
                    item {
                        BCard {
                            Column {
                                // Header with collapse toggle
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp)
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() },
                                        ) { showChangedFiles = !showChangedFiles },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    BSectionHeader(title = "Changed Files")
                                    BBadge(text = "${allChanged.size}", style = BBadgeStyle.ACCENT)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        text = if (showChangedFiles) "▲" else "▼",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = bc.textFaint,
                                        )
                                    )
                                }

                                if (showChangedFiles) {
                                    BDivider()
                                    allChanged.forEachIndexed { idx, entry ->
                                        FileStatusRow(entry = entry, bc = bc)
                                        if (idx < allChanged.size - 1) {
                                            BDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Sync Actions ──────────────────────────────────────────
                item {
                    BSectionHeader(
                        title = "Sync Actions",
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                item {
                    BCard(
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { viewModel.fetch() }
                    ) {
                        BActionRow(
                            icon     = "⬇",
                            title    = "Pull",
                            subtitle = "Fetch remote changes",
                        )
                    }
                }

                item {
                    BCard(
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { viewModel.executePull() }
                    ) {
                        BActionRow(
                            icon     = "↻",
                            title    = "Fetch",
                            subtitle = "Check for remote updates",
                        )
                    }
                }

            } else {
                // ── Empty State ───────────────────────────────────────────
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BEmptyState(
                            title    = "No Repository",
                            subtitle = "Configure a repository in Settings\nto start syncing.",
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun HealthPill(
    label: String,
    count: Int,
    style: BBadgeStyle = BBadgeStyle.DEFAULT,
) {
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
private fun FileStatusRow(entry: GitStatusEntry, bc: com.bontecou.syncmd.ui.theme.BrutalColors) {
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
        }
        BBadge(
            text  = entry.statusLabel(),
            style = entry.statusBadgeStyle(),
        )
    }
}

private fun GitStatusEntry.statusLabel(): String = when (kind) {
    GitFileStatusKind.STAGED    -> "staged"
    GitFileStatusKind.MODIFIED  -> "modified"
    GitFileStatusKind.UNTRACKED -> "untracked"
}

private fun GitStatusEntry.statusBadgeStyle(): BBadgeStyle = when (kind) {
    GitFileStatusKind.STAGED    -> BBadgeStyle.SUCCESS
    GitFileStatusKind.MODIFIED  -> BBadgeStyle.DEFAULT
    GitFileStatusKind.UNTRACKED -> BBadgeStyle.ACCENT
}

@Composable
private fun MetaChip(icon: String, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = icon, style = TextStyle(fontSize = 12.sp, color = color))
        Text(
            text = text,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = color,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Extension for BSectionHeader with modifier
@Composable
private fun BSectionHeader(title: String, modifier: Modifier) {
    Row(modifier = modifier) {
        BSectionHeader(title = title)
    }
}

// Extension for BCard with modifier
@Composable
private fun BCard(modifier: Modifier, content: @Composable () -> Unit) {
    com.bontecou.syncmd.ui.theme.BCard(modifier = modifier, content = content)
}

// Extension for BDivider with modifier
@Composable
private fun BDivider(modifier: Modifier) {
    Box(modifier = modifier) {
        com.bontecou.syncmd.ui.theme.BDivider()
    }
}

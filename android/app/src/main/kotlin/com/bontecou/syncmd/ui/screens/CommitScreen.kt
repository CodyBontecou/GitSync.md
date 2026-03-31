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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.data.models.FileDiff
import com.bontecou.syncmd.ui.theme.BBadge
import com.bontecou.syncmd.ui.theme.BBadgeStyle
import com.bontecou.syncmd.ui.theme.BCard
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.BEmptyState
import com.bontecou.syncmd.ui.theme.BLoading
import com.bontecou.syncmd.ui.theme.BSmallActionButton
import com.bontecou.syncmd.ui.theme.BSectionHeader
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel

/**
 * Commit screen — matches iOS GitControlSheet changes + push section.
 */
@Composable
fun CommitScreen(
    repositoryPath: String = "",
    viewModel: DiffViewModel = hiltViewModel()
) {
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) viewModel.setRepositoryPath(repositoryPath)
    }

    val diff          by viewModel.diff.collectAsState()
    val commitMessage by viewModel.commitMessage.collectAsState()
    val isLoading     by viewModel.isLoading.collectAsState()
    val errorMessage  by viewModel.errorMessage.collectAsState()
    val bc            = LocalBrutalColors.current

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
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.error)
                        )
                    }
                }
            }

            // ── Loading ───────────────────────────────────────────────────
            if (isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        BLoading(text = "Loading changes")
                    }
                }
            } else if (diff != null) {
                val files = diff!!.files
                val stagedCount = files.count { it.status.name == "ADDED" || it.status.name == "MODIFIED" }

                // ── Changes Card ──────────────────────────────────────────
                item {
                    BCard {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                BSectionHeader(title = "Changes")
                                if (stagedCount > 0) {
                                    Spacer(Modifier.weight(1f))
                                    BBadge(text = "$stagedCount staged", style = BBadgeStyle.SUCCESS)
                                }
                            }

                            if (files.isEmpty()) {
                                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    Text(
                                        text = "No local changes",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 14.sp,
                                            color = bc.textMid,
                                        )
                                    )
                                }
                            } else {
                                BDivider()
                                files.forEachIndexed { idx, file ->
                                    ChangeFileRow(
                                        file       = file,
                                        onStage    = { viewModel.stageFile(file.filePath) },
                                        onUnstage  = { viewModel.unstageFile(file.filePath) },
                                        onDiff     = { viewModel.selectFile(file.filePath) },
                                    )
                                    if (idx < files.size - 1) {
                                        BDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Push Card ─────────────────────────────────────────────
                item {
                    BCard {
                        Column {
                            // Header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Text(text = "⬆", style = TextStyle(fontSize = 20.sp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "Commit & Push",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Default,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 17.sp,
                                            color = bc.text,
                                        )
                                    )
                                    Text(
                                        text = "Commit staged changes and push to remote",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            color = bc.textMid,
                                        )
                                    )
                                }
                            }

                            BDivider()

                            // Commit message
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bc.surface)
                                    .padding(13.dp)
                            ) {
                                if (commitMessage.isEmpty()) {
                                    Text(
                                        text = "Commit message…",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 15.sp,
                                            color = bc.textFaint,
                                        )
                                    )
                                }
                                BasicTextField(
                                    value = commitMessage,
                                    onValueChange = { viewModel.setCommitMessage(it) },
                                    textStyle = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 15.sp,
                                        color = bc.text,
                                    ),
                                    cursorBrush = SolidColor(bc.text),
                                    minLines = 2,
                                    maxLines = 4,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            BDivider()

                            // Staged count
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = if (stagedCount == 1) "1 file staged" else "$stagedCount files staged",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        color = bc.textMid,
                                    )
                                )
                            }

                            BDivider()

                            // Push button
                            val canPush = stagedCount > 0 && commitMessage.isNotBlank()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .background(bc.text.copy(alpha = if (canPush) 1f else 0.25f))
                                    .clickable(
                                        enabled = canPush,
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                    ) { viewModel.commit() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (stagedCount == 1) "↑ PUSH 1 FILE" else "↑ PUSH $stagedCount FILES",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        letterSpacing = 1.sp,
                                        color = bc.bg,
                                    )
                                )
                            }

                            // Stage all / Unstage all
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                BSmallActionButton(title = "Stage All", onClick = { viewModel.stageAll() })
                                BSmallActionButton(title = "Unstage All", onClick = { viewModel.unstageAll() })
                            }
                        }
                    }
                }

            } else {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(400.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BEmptyState(
                            title    = "No Changes",
                            subtitle = "Working tree is clean.\nNothing to commit.",
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun ChangeFileRow(
    file: FileDiff,
    onStage: () -> Unit,
    onUnstage: () -> Unit,
    onDiff: () -> Unit,
) {
    val bc          = LocalBrutalColors.current
    val isStaged    = file.status.name == "ADDED" || file.status.name == "MODIFIED"
    val additions   = file.hunks.sumOf { hunk -> hunk.lines.count { it.type.name == "ADDITION" } }
    val deletions   = file.hunks.sumOf { hunk -> hunk.lines.count { it.type.name == "DELETION" } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = file.filePath,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = bc.text,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (additions > 0 || deletions > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "+$additions",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = bc.success,
                        )
                    )
                    Text(
                        text = "-$deletions",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = bc.error,
                        )
                    )
                }
            }
        }

        BSmallActionButton(
            title   = if (isStaged) "Unstage" else "Stage",
            onClick = { if (isStaged) onUnstage() else onStage() },
        )
        BSmallActionButton(title = "Diff", onClick = onDiff)
    }
}

// Local div wrapper accepting modifier
@Composable
private fun BDivider(modifier: Modifier) {
    Box(modifier = modifier) { com.bontecou.syncmd.ui.theme.BDivider() }
}

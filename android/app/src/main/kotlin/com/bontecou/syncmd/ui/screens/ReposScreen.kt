package com.bontecou.syncmd.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.ui.theme.BBadge
import com.bontecou.syncmd.ui.theme.BBadgeStyle
import com.bontecou.syncmd.ui.theme.BCard
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.BEmptyState
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SavedRepository
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Home screen — matches iOS RepoListView.
 *
 * Shows all saved repositories as brutal cards.
 * Tap a card → navigate to VaultScreen.
 * Long-press or swipe-left or ⋮ menu → remove repository.
 * "ADD REPOSITORY" dashed button → GitHub login / repo picker.
 */
@Composable
fun ReposScreen(
    settingsViewModel: SettingsViewModel,
    githubViewModel: GitHubViewModel,
    onRepoSelected: (String) -> Unit,
    onRepoRemoved: (String) -> Unit,
    onAddRepo: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val bc              = LocalBrutalColors.current
    val allRepos        by settingsViewModel.allRepositories.collectAsState()
    val selectedRepo    by settingsViewModel.selectedRepository.collectAsState()
    val isLoggedIn      by githubViewModel.isLoggedIn.collectAsState()
    val user            by githubViewModel.user.collectAsState()
    val cachedLogin     by githubViewModel.authManager.cachedLogin.collectAsState()

    val displayLogin = user?.login ?: cachedLogin

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
                    // App name
                    Text(
                        text = "SYNC.MD",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp,
                            letterSpacing = 3.sp,
                            color = bc.text,
                        )
                    )

                    // Right: avatar/username or SIGN IN + settings gear
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (isLoggedIn && displayLogin != null) {
                            // Username badge
                            Box(
                                modifier = Modifier
                                    .border(1.dp, bc.border)
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "@$displayLogin",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp,
                                        color = bc.textMid,
                                    )
                                )
                            }
                        } else {
                            // SIGN IN button
                            Box(
                                modifier = Modifier
                                    .border(1.dp, bc.accent.copy(alpha = 0.5f))
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick = onAddRepo,
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "SIGN IN",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        letterSpacing = 1.sp,
                                        color = bc.accent,
                                    )
                                )
                            }
                        }

                        // Settings gear
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = onNavigateToSettings,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "⚙",
                                style = TextStyle(fontSize = 18.sp, color = bc.textMid)
                            )
                        }
                    }
                }
                // Bottom border
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(bc.border)
                )
            }

            // ── Content ───────────────────────────────────────────────────
            if (allRepos.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    BEmptyState(
                        title       = "No Repositories",
                        subtitle    = "Add a GitHub repository to\nstart syncing your files.",
                        actionTitle = "Add Repository",
                        onAction    = onAddRepo,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start  = 20.dp,
                        end    = 20.dp,
                        top    = 12.dp,
                        bottom = 20.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(allRepos, key = { it.path }) { repo ->
                        RepoCard(
                            repo       = repo,
                            isSelected = repo.path == selectedRepo,
                            onClick    = { onRepoSelected(repo.path) },
                            onRemove   = { onRepoRemoved(repo.path) },
                        )
                    }
                }
            }

            // ── ADD REPOSITORY dashed button (matches iOS exactly) ────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bc.bg)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(bc.border)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onAddRepo,
                        )
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "+",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp,
                            color = bc.text,
                        )
                    )
                    Text(
                        text = "ADD REPOSITORY",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 2.sp,
                            color = bc.text,
                        )
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ─── Repo Card ────────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RepoCard(
    repo: SavedRepository,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val bc    = LocalBrutalColors.current
    val scope = rememberCoroutineScope()

    var showMenu    by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    val offsetX     = remember { Animatable(0f) }

    // ── Swipe + card container ────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
    ) {
        // Delete background — visible as card slides left
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(bc.error),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = "REMOVE",
                modifier = Modifier.padding(end = 24.dp),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 2.sp,
                    color = bc.bg,
                )
            )
        }

        // Card — slides horizontally on drag
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(repo.path) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value < -size.width * 0.35f) {
                                    showConfirm = true
                                }
                                offsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                )
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                offsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                )
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            // Only allow left swipe (negative offset)
                            val newValue = (offsetX.value + dragAmount).coerceAtMost(0f)
                            scope.launch { offsetX.snapTo(newValue) }
                        },
                    )
                }
        ) {
            BCard(
                modifier = Modifier.combinedClickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClick,
                    onLongClick = { showConfirm = true },
                )
            ) {
                Column {
                    // ── Header row ────────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Name + owner
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = if (repo.alias.isNotBlank()) repo.alias else repo.name,
                                style = TextStyle(
                                    fontFamily = FontFamily.Default,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 17.sp,
                                    color = bc.text,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val ownerHint = repo.path
                                .removePrefix("github://")
                                .substringBefore("/")
                                .uppercase()
                            if (ownerHint.isNotBlank()) {
                                Text(
                                    text = ownerHint,
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

                        if (isSelected) {
                            BBadge(text = "ACTIVE", style = BBadgeStyle.ACCENT)
                        }

                        // ── ⋮ settings menu ───────────────────────────────
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        onClick = { showMenu = true },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "⋮",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 20.sp,
                                        color = bc.textMid,
                                    )
                                )
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Remove Repository",
                                            style = TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 14.sp,
                                                color = bc.error,
                                            )
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showConfirm = true
                                    },
                                )
                            }
                        }
                    }

                    BDivider()

                    // ── Meta row: path ────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(text = "⑂", style = TextStyle(fontSize = 12.sp, color = bc.textFaint))
                        Text(
                            text = repo.path.removePrefix("github://"),
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = bc.textFaint,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    // ── Remove confirmation dialog ────────────────────────────────────────
    if (showConfirm) {
        val displayName = if (repo.alias.isNotBlank()) repo.alias else repo.name
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = {
                Text(
                    text = "Remove Repository",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = bc.text,
                    )
                )
            },
            text = {
                Text(
                    text = "Remove \"$displayName\" from your saved repositories?\n\nThis only removes the local bookmark — your GitHub repo is not affected.",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = bc.textMid,
                    )
                )
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .border(1.dp, bc.error)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {
                                showConfirm = false
                                onRemove()
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "REMOVE",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 1.sp,
                            color = bc.error,
                        )
                    )
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier
                        .border(1.dp, bc.border)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = { showConfirm = false }
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "CANCEL",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            letterSpacing = 1.sp,
                            color = bc.textMid,
                        )
                    )
                }
            },
        )
    }
}

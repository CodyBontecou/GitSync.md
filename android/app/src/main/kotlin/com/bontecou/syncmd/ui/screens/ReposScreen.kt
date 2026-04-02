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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.ui.theme.BBadge
import com.bontecou.syncmd.ui.theme.BBadgeStyle
import com.bontecou.syncmd.ui.theme.BCard
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.BEmptyState
import com.bontecou.syncmd.ui.theme.BSectionHeader
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
    seenRepoIdentifiers: Set<String>,
    isUnlocked: Boolean,
    onRepoSelected: (String) -> Unit,
    onRepoRemoved: (String) -> Unit,
    onGhostRepoSelected: (String) -> Unit,
    onAddRepo: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPaywall: () -> Unit,
) {
    val bc           = LocalBrutalColors.current
    val allRepos     by settingsViewModel.allRepositories.collectAsState()
    val selectedRepo by settingsViewModel.selectedRepository.collectAsState()
    val isLoggedIn   by githubViewModel.isLoggedIn.collectAsState()

    val activeRepoIdentifiers = allRepos.mapNotNull(::savedRepoIdentifier).toSet()
    val ghostRepoIdentifiers = seenRepoIdentifiers
        .mapNotNull(::normaliseSeenRepoIdentifier)
        .filterNot { activeRepoIdentifiers.contains(it) }
        .sorted()

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
                        if (!isLoggedIn) {
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
            if (allRepos.isEmpty() && ghostRepoIdentifiers.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    BEmptyState(
                        title       = "No Repositories",
                        subtitle    = "Add a GitHub repository to start syncing your files.",
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

                    if (ghostRepoIdentifiers.isNotEmpty()) {
                        item {
                            BSectionHeader(title = "Previously Cloned")
                        }
                        items(ghostRepoIdentifiers, key = { it }) { identifier ->
                            GhostRepoCard(
                                identifier = identifier,
                                onClick = { onGhostRepoSelected(identifier) },
                            )
                        }
                    }
                }
            }

            // ── ADD REPOSITORY dashed button (matches iOS exactly) ────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bc.bg)
            ) {
                if (allRepos.isEmpty() && !isUnlocked) {
                    Text(
                        text = "1 FREE REPO · UNLOCK MORE WITH PRO",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            letterSpacing = 1.5.sp,
                            color = bc.accent,
                        ),
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onNavigateToPaywall,
                            )
                            .padding(start = 20.dp, top = 6.dp, bottom = 10.dp),
                    )
                }

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

// ─── Ghost Repo Card (previously cloned) ─────────────────────────────────────
@Composable
private fun GhostRepoCard(
    identifier: String,
    onClick: () -> Unit,
) {
    val bc = LocalBrutalColors.current
    val identity = parseRepoIdentity(identifier)
    val repoName = identity?.repo ?: identifier.substringAfterLast("/")

    BCard(
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClick,
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = repoName,
                        style = TextStyle(
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.Black,
                            fontSize = 17.sp,
                            color = bc.text,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    identity?.owner?.let { owner ->
                        Text(
                            text = owner.uppercase(),
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

                Text(
                    text = "→",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = bc.textFaint,
                    )
                )
            }

            BDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BBadge(text = "PREVIOUSLY CLONED", style = BBadgeStyle.DEFAULT)
                Text(
                    text = "Tap to re-clone",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = bc.textFaint,
                    )
                )
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
                            // Derive owner from alias ("owner/repo") when available,
                            // or fall back to the legacy "github://owner/repo" path format.
                            val ownerHint = repoOwnerHint(repo)
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
                            text = repoDisplayPath(repo),
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
                    text = "Remove \"$displayName\"?\n\nThis removes it from your saved repositories and deletes its local files from this device. Your GitHub repo is not affected.",
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

// ─── Display helpers ─────────────────────────────────────────────────────────

private data class RepoIdentity(val owner: String, val repo: String)

/** Returns a normalised "owner/repo" identifier for a saved repo when available. */
private fun savedRepoIdentifier(repo: SavedRepository): String? {
    return normaliseSeenRepoIdentifier(repo.alias)
        ?: normaliseSeenRepoIdentifier(repo.path)
}

/**
 * Normalises repo identifiers from all known formats into "owner/repo".
 *
 * Accepted inputs:
 *  - "owner/repo"
 *  - "https://github.com/owner/repo" (or .git)
 *  - "github://owner/repo"
 *  - "git@github.com:owner/repo(.git)"
 *  - Android local clone paths containing "/repos/owner/repo"
 */
private fun normaliseSeenRepoIdentifier(raw: String): String? {
    val trimmed = raw.trim().removeSuffix("/")
    if (trimmed.isBlank()) return null

    fun toOwnerRepo(pathLike: String): String? {
        val clean = pathLike.trim().trim('/').removeSuffix(".git")
        val parts = clean.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return "${parts[0].lowercase()}/${parts[1].lowercase()}"
    }

    val githubScheme = Regex("^github://(.+)$", RegexOption.IGNORE_CASE)
    githubScheme.matchEntire(trimmed)?.groupValues?.getOrNull(1)?.let { return toOwnerRepo(it) }

    val ssh = Regex("^git@github\\.com:(.+)$", RegexOption.IGNORE_CASE)
    ssh.matchEntire(trimmed)?.groupValues?.getOrNull(1)?.let { return toOwnerRepo(it) }

    val https = Regex("^https?://github\\.com/(.+)$", RegexOption.IGNORE_CASE)
    https.matchEntire(trimmed)?.groupValues?.getOrNull(1)?.let { return toOwnerRepo(it) }

    if (trimmed.contains("/repos/")) {
        val afterRepos = trimmed.substringAfter("/repos/", "")
        toOwnerRepo(afterRepos)?.let { return it }
    }

    if (trimmed.matches(Regex("^[^/]+/[^/]+$"))) {
        return toOwnerRepo(trimmed)
    }

    return null
}

private fun parseRepoIdentity(identifier: String): RepoIdentity? {
    val normalised = normaliseSeenRepoIdentifier(identifier) ?: return null
    val owner = normalised.substringBefore("/")
    val repo = normalised.substringAfter("/")
    if (owner.isBlank() || repo.isBlank()) return null
    return RepoIdentity(owner = owner, repo = repo)
}

/** Returns the "owner" portion for the active repo card subtitle. */
private fun repoOwnerHint(repo: SavedRepository): String {
    return parseRepoIdentity(repo.alias)?.owner?.uppercase()
        ?: parseRepoIdentity(repo.path)?.owner?.uppercase()
        ?: repo.path.removePrefix("github://").substringBefore("/").uppercase()
}

/** Returns the short "owner/repo" string for the active repo card path row. */
private fun repoDisplayPath(repo: SavedRepository): String {
    if (repo.alias.contains("/")) return repo.alias
    return normaliseSeenRepoIdentifier(repo.path)
        ?: repo.path.removePrefix("github://")
}

package com.bontecou.syncmd.ui.screens

import com.bontecou.syncmd.ui.util.displayIdentity

import android.text.format.DateUtils
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.RepositoryOverview
import com.bontecou.syncmd.ui.components.RepositoryOverviewState
import com.bontecou.syncmd.ui.components.headCommitText
import com.bontecou.syncmd.ui.components.recordedSyncText
import com.bontecou.syncmd.ui.components.rememberRepositoryOverview
import com.bontecou.syncmd.ui.theme.BDestructiveButton
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideAccessory
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideChipRow
import com.bontecou.syncmd.ui.theme.TideCircleButton
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideDisplay
import com.bontecou.syncmd.ui.theme.TideFilterChip
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TidePrimaryButton
import com.bontecou.syncmd.ui.theme.TidePulse
import com.bontecou.syncmd.ui.theme.TideRow
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideTopBar
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.TideWordmark
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SavedRepository
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Home screen — iOS Tide RepoListView.
 *
 * "State first, git second": a headline that says how the vaults are, filter
 * chips, one white card per vault (state pill, mono git facts, sync rail) and
 * a bottom bar with the one right action (Pull All) plus Add.
 * Tap a card → VaultScreen. Long-press, swipe left or ⋮ → remove.
 */
@Composable
fun ReposScreen(
    settingsViewModel: SettingsViewModel,
    githubViewModel: GitHubViewModel,
    onRepoSelected: (String) -> Unit,
    onRepoRemoved: (String) -> Unit,
    onAddRepo: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onManageRepositories: () -> Unit = {},
    onCloneFromUrl: () -> Unit = {},
    onPublishFolder: () -> Unit = {},
    onPullAll: () -> Unit = {},
    onRepositoryActions: () -> Unit = {},
) {
    val allRepos   by settingsViewModel.allRepositories.collectAsState()
    val isLoggedIn by githubViewModel.isLoggedIn.collectAsState()
    val user       by githubViewModel.user.collectAsState()
    val ghRepos    by githubViewModel.repos.collectAsState()

    // Each vault keeps its own lifecycle-bound overview read; hoisted here so
    // the headline and Attention filter can count every vault, not only the
    // cards on screen. key() keeps a read attached to its path across reorders.
    val overviews: List<Pair<SavedRepository, State<RepositoryOverviewState>>> = allRepos.map { repo ->
        key(repo.path) { repo to rememberRepositoryOverview(repo.path) }
    }

    ReposHomeContent(
        vaults = overviews.map { (repo, state) -> HomeVault(repo, state.value) },
        isLoggedIn = isLoggedIn,
        signedInLogin = user?.login,
        gitHubRepoCount = ghRepos.size,
        onRepoSelected = onRepoSelected,
        onRepoRemoved = onRepoRemoved,
        onAddRepo = onAddRepo,
        onNavigateToSettings = onNavigateToSettings,
        onManageRepositories = onManageRepositories,
        onCloneFromUrl = onCloneFromUrl,
        onPublishFolder = onPublishFolder,
        onPullAll = onPullAll,
        onRepositoryActions = onRepositoryActions,
    )
}

private data class HomeVault(val repo: SavedRepository, val overview: RepositoryOverviewState) {
    val needsAttention get() = overview is RepositoryOverviewState.Unavailable
}

private enum class HomeFilter { ALL, ATTENTION }

/** Stateless home: every visible state is a function of these inputs. */
@Composable
private fun ReposHomeContent(
    vaults: List<HomeVault>,
    isLoggedIn: Boolean,
    signedInLogin: String?,
    gitHubRepoCount: Int,
    onRepoSelected: (String) -> Unit,
    onRepoRemoved: (String) -> Unit,
    onAddRepo: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onManageRepositories: () -> Unit,
    onCloneFromUrl: () -> Unit,
    onPublishFolder: () -> Unit,
    onPullAll: () -> Unit,
    onRepositoryActions: () -> Unit,
) {
    val c = Tide.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        HomeTopBar(isLoggedIn = isLoggedIn, onSignIn = onAddRepo, onNavigateToSettings = onNavigateToSettings)

        if (vaults.isEmpty()) {
            HomeEmptyState(
                isLoggedIn = isLoggedIn,
                signedInLogin = signedInLogin,
                gitHubRepoCount = gitHubRepoCount,
                onAddRepo = onAddRepo,
                onCloneFromUrl = onCloneFromUrl,
                onManageRepositories = onManageRepositories,
                onPublishFolder = onPublishFolder,
                modifier = Modifier.weight(1f),
            )
            // Bottom tools stay on screen so every entry point is reachable.
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Tide.gutter, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HomeToolStrip(
                    listOf(
                        HomeTool(stringResource(R.string.pull_all_title), "open-pull-all", onPullAll),
                        HomeTool(stringResource(R.string.repository_actions_title), "open-repository-actions", onRepositoryActions),
                    )
                )
                TidePrimaryButton(stringResource(R.string.tide_home_add_first), onAddRepo)
            }
        } else {
            var filter by rememberSaveable { mutableStateOf(HomeFilter.ALL) }
            val attention = vaults.count { it.needsAttention }
            val shown = if (filter == HomeFilter.ATTENTION) vaults.filter { it.needsAttention } else vaults

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = Tide.gutter).padding(top = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HomeHeader(vaults, Modifier.padding(horizontal = 4.dp))
                TideChipRow(Modifier.padding(top = 10.dp, bottom = 6.dp)) {
                    TideFilterChip(stringResource(R.string.tide_home_filter_all), filter == HomeFilter.ALL,
                        onClick = { filter = HomeFilter.ALL }, count = vaults.size)
                    TideFilterChip(stringResource(R.string.tide_home_filter_attention), filter == HomeFilter.ATTENTION,
                        onClick = { filter = HomeFilter.ATTENTION }, count = attention,
                        dot = if (attention > 0) c.ahead else null)
                }
                if (shown.isEmpty()) {
                    TideCard {
                        Text(stringResource(R.string.tide_home_empty_filter_title), style = TideType.sans(15.sp, FontWeight.SemiBold), color = c.ink)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.tide_home_empty_filter_detail), style = TideType.meta, color = c.inkFaint)
                    }
                }
                shown.forEach { vault ->
                    key(vault.repo.path) {
                        HomeVaultCard(
                            vault = vault,
                            onClick = { onRepoSelected(vault.repo.path) },
                            onRemove = { onRepoRemoved(vault.repo.path) },
                        )
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().background(c.paper).navigationBarsPadding()
                    .padding(horizontal = Tide.gutter, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HomeToolStrip(
                    listOf(
                        HomeTool(stringResource(R.string.guide_url), null, onCloneFromUrl),
                        HomeTool(stringResource(R.string.publication_title), "open-folder-publication", onPublishFolder),
                        HomeTool(stringResource(R.string.ui_local_repositories_ddcf4270), null, onManageRepositories),
                        HomeTool(stringResource(R.string.repository_actions_title), "open-repository-actions", onRepositoryActions),
                    )
                )
                HomeBottomBar(onPullAll = onPullAll, onAddRepo = onAddRepo)
            }
        }
    }
}

// ─── Top bar ──────────────────────────────────────────────────────────────────

@Composable
private fun HomeTopBar(isLoggedIn: Boolean, onSignIn: () -> Unit, onNavigateToSettings: () -> Unit) {
    TideTopBar(
        center = { TideWordmark() },
        actions = {
            if (!isLoggedIn) {
                TideCircleButton(stringResource(R.string.guide_login), onSignIn, size = 48.dp, filled = false) {
                    Icon(painterResource(R.drawable.ic_github), contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            TideCircleButton(stringResource(R.string.ui_app_settings_da645334), onNavigateToSettings, Modifier.testTag("open-settings"), size = 48.dp) {
                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        },
    )
}

// ─── Header ───────────────────────────────────────────────────────────────────

/** "1 vault\nneeds you." / "3 vaults\non this phone." plus a mono meta line. */
@Composable
private fun HomeHeader(vaults: List<HomeVault>, modifier: Modifier = Modifier) {
    val c = Tide.colors
    val attention = vaults.count { it.needsAttention }
    val checking = vaults.count { it.overview is RepositoryOverviewState.Loading }
    val ready = vaults.size - attention - checking
    val headline = if (attention > 0) pluralStringResource(R.plurals.tide_home_headline_attention, attention, attention)
        else pluralStringResource(R.plurals.tide_home_headline_ready, vaults.size, vaults.size)
    val parts = mutableListOf(stringResource(R.string.tide_home_meta_ready, ready))
    if (checking > 0) parts += stringResource(R.string.tide_home_meta_checking, checking)
    if (attention > 0) parts += stringResource(R.string.tide_home_meta_unreadable, attention)
    Column(modifier.semantics(mergeDescendants = true) { heading() }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TideDisplay(headline)
        Text(parts.joinToString(" · "), style = TideType.mono(12.sp), color = c.inkMid)
    }
}

// ─── Vault card ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun HomeVaultCard(
    vault: HomeVault,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = Tide.colors
    val repo = vault.repo
    val scope = rememberCoroutineScope()
    var showMenu    by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    val offsetX     = remember { Animatable(0f) }
    val shape = Tide.cardShape

    Box(Modifier.fillMaxWidth().clip(shape)) {
        // Danger well revealed as the card slides left.
        Box(Modifier.matchParentSize().background(c.danger, shape), contentAlignment = Alignment.CenterEnd) {
            Text(stringResource(R.string.ui_remove_c3812fc4), modifier = Modifier.padding(end = 24.dp),
                style = TideType.sans(15.sp, FontWeight.SemiBold), color = Color.White)
        }

        Column(
            Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(repo.path) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value < -size.width * 0.35f) showConfirm = true
                                offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                            }
                        },
                        onDragCancel = {
                            scope.launch { offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            // Only allow left swipe (negative offset)
                            val newValue = (offsetX.value + dragAmount).coerceAtMost(0f)
                            scope.launch { offsetX.snapTo(newValue) }
                        },
                    )
                }
                .fillMaxWidth()
                .clip(shape)
                .background(c.card, shape)
                .testTag("repo-card-${repo.path}")
                .combinedClickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClick,
                    onLongClick = { showConfirm = true },
                )
                .padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(repo.displayIdentity().name, style = TideType.title.copy(letterSpacing = (-0.4).sp), color = c.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(repoDisplayPath(repo), style = TideType.meta.copy(textDirection = TextDirection.Ltr), color = c.inkFaint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.padding(top = 10.dp)) { HomeVaultPill(vault.overview) }
                Box {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).clickable { showMenu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.ui_more_d47d7cb0),
                            tint = c.inkMid, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ui_remove_repository_fc68168b), style = TideType.body, color = c.danger) },
                            onClick = { showMenu = false; showConfirm = true },
                        )
                    }
                }
            }

            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val loaded = vault.overview) {
                    is RepositoryOverviewState.Loaded -> FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(loaded.overview.branch, Modifier.testTag("repo-overview-branch"),
                            style = TideType.metaStrong.copy(textDirection = TextDirection.Ltr), color = c.inkMid)
                        Text("·", style = TideType.meta, color = c.inkFaint)
                        Text(headCommitText(loaded.overview.headCommit), Modifier.testTag("repo-overview-head"),
                            style = TideType.meta, color = c.inkFaint)
                        Text("·", style = TideType.meta, color = c.inkFaint)
                        Text(recordedSyncText(loaded.overview.lastSyncAt), Modifier.testTag("repo-overview-sync"),
                            style = TideType.meta, color = c.inkFaint)
                    }
                    RepositoryOverviewState.Loading -> Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TidePulse(color = c.inkFaint)
                        Text(stringResource(R.string.ui_loading_repository_15b79262), Modifier.testTag("repo-overview-unavailable"),
                            style = TideType.meta, color = c.inkMid)
                    }
                    RepositoryOverviewState.Unavailable -> Text(stringResource(R.string.repository_metadata_unavailable),
                        Modifier.testTag("repo-overview-unavailable"), style = TideType.meta, color = c.danger)
                }
                HomeVaultRail(vault.overview, remoteHostLabel(repo))
            }
        }
    }

    if (showConfirm) {
        val displayName = if (repo.alias.isNotBlank()) repo.alias else repo.name
        TideDialog(
            onDismissRequest = { showConfirm = false },
            icon = { TideModalIcon() },
            title = stringResource(R.string.ui_remove_repository_fc68168b),
            text = {
                Text(stringResource(R.string.ui_remove_1_s_this_removes_it_from_gitsync_md_0a6b198d, displayName),
                    modifier = Modifier.testTag("repo-remove-body"))
            },
            confirmButton = {
                BDestructiveButton(stringResource(R.string.ui_remove_c3812fc4), onClick = { showConfirm = false; onRemove() })
            },
            dismissButton = {
                BGhostButton(stringResource(R.string.repository_actions_cancel), Modifier.fillMaxWidth(), onClick = { showConfirm = false })
            },
        )
    }
}

/** What the home read can honestly say: checking, unreadable, no commits, or when it last synced. */
@Composable
private fun HomeVaultPill(state: RepositoryOverviewState) {
    val c = Tide.colors
    when (state) {
        RepositoryOverviewState.Loading -> TidePill(stringResource(R.string.tide_home_state_checking), c.inkMid, c.well)
        RepositoryOverviewState.Unavailable -> TidePill(stringResource(R.string.tide_home_state_unreadable), c.danger, c.danger.copy(alpha = 0.12f))
        is RepositoryOverviewState.Loaded -> {
            val time = state.overview.lastSyncAt
            when {
                state.overview.headCommit == null -> TidePill(stringResource(R.string.repository_no_commits), c.inkMid, c.well)
                time == null -> TidePill(stringResource(R.string.tide_home_state_never_synced), c.inkMid, c.well)
                else -> TidePill(stringResource(R.string.tide_home_state_synced, relativeTime(time)), c.ink, c.well)
            }
        }
    }
}

private fun relativeTime(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/**
 * Phone ── sync point ── Remote. The home read has no ahead/behind counts, so
 * the rail draws no commit dots: a solid ring once read, dashed while
 * checking, danger when the working copy can't be read. Visual only — the
 * pill already says the state.
 */
@Composable
private fun HomeVaultRail(state: RepositoryOverviewState, remoteLabel: String) {
    val c = Tide.colors
    val ring = when (state) {
        RepositoryOverviewState.Unavailable -> c.danger
        RepositoryOverviewState.Loading -> c.inkFaint
        is RepositoryOverviewState.Loaded -> c.ink
    }
    val dashed = state is RepositoryOverviewState.Loading
    Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tide_home_rail_phone), style = TideType.mono(10.5.sp), color = c.inkFaint)
        Box(Modifier.weight(1f).height(18.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Box(
                Modifier.size(14.dp).background(c.card, CircleShape).drawBehind {
                    val stroke = 2.dp.toPx()
                    drawCircle(ring, radius = size.minDimension / 2 - stroke / 2,
                        style = Stroke(stroke, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4f, 3f)) else null))
                }
            )
        }
        Text(remoteLabel, style = TideType.mono(10.5.sp), color = c.inkFaint, maxLines = 1)
    }
}

// ─── Bottom bar & tools ───────────────────────────────────────────────────────

@Composable
private fun HomeBottomBar(onPullAll: () -> Unit, onAddRepo: () -> Unit) {
    val c = Tide.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        TidePrimaryButton(
            title = stringResource(R.string.pull_all_title),
            onClick = onPullAll,
            modifier = Modifier.weight(1f).testTag("open-pull-all"),
            icon = painterResource(R.drawable.ic_pull_all),
        )
        Box(
            Modifier.size(60.dp).clip(CircleShape).background(c.card, CircleShape).border(1.dp, c.lineSoft, CircleShape)
                .clickable(onClick = onAddRepo),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.ui_add_repository_33da2ac1), tint = c.ink,
                modifier = Modifier.size(24.dp))
        }
    }
}

private data class HomeTool(val label: String, val tag: String?, val onClick: () -> Unit)

/** Secondary entry points as one quiet card of equal tiles; labels wrap. */
@Composable
private fun HomeToolStrip(tools: List<HomeTool>) {
    val c = Tide.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 56.dp).clip(shape).background(c.card, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tools.forEachIndexed { index, tool ->
            if (index > 0) Box(Modifier.width(1.dp).height(28.dp).background(c.line))
            Box(
                Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp)
                    .then(if (tool.tag != null) Modifier.testTag(tool.tag) else Modifier)
                    .clickable(onClick = tool.onClick)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(tool.label, style = TideType.sans(13.sp, FontWeight.Medium), color = c.ink, textAlign = TextAlign.Center)
            }
        }
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun HomeEmptyState(
    isLoggedIn: Boolean,
    signedInLogin: String?,
    gitHubRepoCount: Int,
    onAddRepo: () -> Unit,
    onCloneFromUrl: () -> Unit,
    onManageRepositories: () -> Unit,
    onPublishFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Tide.colors
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Tide.gutter).padding(bottom = 16.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp).padding(top = 14.dp).semantics(mergeDescendants = true) { heading() },
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TideDisplay(stringResource(R.string.tide_home_empty_title))
            if (isLoggedIn && !signedInLogin.isNullOrBlank()) {
                Text(stringResource(R.string.tide_home_signed_in_as, signedInLogin), style = TideType.mono(12.sp), color = c.inkMid)
            }
        }

        HomeEmptyRail(Modifier.padding(top = 26.dp))

        TideSectionLabel(stringResource(R.string.tide_home_start_with), Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp))
        TideCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                TideRow(
                    title = stringResource(R.string.tide_home_start_github),
                    subtitle = when {
                        isLoggedIn && gitHubRepoCount > 0 -> pluralStringResource(R.plurals.tide_home_start_github_count, gitHubRepoCount, gitHubRepoCount)
                        isLoggedIn -> stringResource(R.string.tide_home_start_github_signed_in)
                        else -> stringResource(R.string.tide_home_start_github_signed_out)
                    },
                    accessory = TideAccessory.CHEVRON,
                    onClick = onAddRepo,
                )
                TideHairline()
                TideRow(stringResource(R.string.guide_url), subtitle = stringResource(R.string.tide_home_start_url_detail),
                    accessory = TideAccessory.CHEVRON, onClick = onCloneFromUrl)
                TideHairline()
                TideRow(stringResource(R.string.ui_local_repositories_ddcf4270), subtitle = stringResource(R.string.tide_home_start_local_detail),
                    accessory = TideAccessory.CHEVRON, onClick = onManageRepositories)
                TideHairline()
                TideRow(stringResource(R.string.publication_title), Modifier.testTag("open-folder-publication"),
                    subtitle = stringResource(R.string.tide_home_start_publish_detail),
                    accessory = TideAccessory.CHEVRON, onClick = onPublishFolder)
            }
        }
    }
}

/** Dashed, empty sync rail: nothing on either side, a dashed sync point. */
@Composable
private fun HomeEmptyRail(modifier: Modifier = Modifier) {
    val c = Tide.colors
    val description = stringResource(R.string.tide_home_empty_rail_description)
    val dashed = c.lineSoft
    val faint = c.inkFaint
    Box(
        modifier.fillMaxWidth().height(140.dp)
            .drawBehind {
                val r = Tide.radiusHero.toPx()
                drawRoundRect(dashed, cornerRadius = CornerRadius(r, r),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))))
                val y = size.height / 2
                val inset = 24.dp.toPx()
                drawLine(dashed, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 1.dp.toPx())
            }
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).offset(y = (-26).dp)) {
            Text(stringResource(R.string.tide_home_rail_phone), style = TideType.mono(11.sp), color = faint)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.upstream_remote), style = TideType.mono(11.sp), color = faint)
        }
        Box(
            Modifier.size(28.dp).background(c.paper, CircleShape).drawBehind {
                val stroke = 2.dp.toPx()
                drawCircle(faint, radius = size.minDimension / 2 - stroke / 2,
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))))
            }
        )
        Text(stringResource(R.string.tide_home_empty_rail_nothing), Modifier.offset(y = 34.dp), style = TideType.mono(11.sp), color = faint)
    }
}

// ─── Display helpers ─────────────────────────────────────────────────────────

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

/** Returns the short "owner/repo" string for the card's mono identity line. */
private fun repoDisplayPath(repo: SavedRepository): String {
    if (repo.alias.contains("/")) return repo.alias
    return normaliseSeenRepoIdentifier(repo.path)
        ?: repo.path.removePrefix("github://")
}

/** "GitHub", the remote's host, or the generic "Remote" for the rail's right end. */
@Composable
private fun remoteHostLabel(repo: SavedRepository): String {
    val remote = repo.remoteUrl?.trim().orEmpty()
    val host = when {
        remote.isBlank() -> null
        remote.startsWith("git@") -> remote.substringAfter("@").substringBefore(":")
        else -> runCatching { java.net.URI(remote).host }.getOrNull()
    }
    return when {
        host.isNullOrBlank() -> if (repo.path.startsWith("github://")) "GitHub" else stringResource(R.string.upstream_remote)
        host.equals("github.com", ignoreCase = true) -> "GitHub"
        else -> host
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

private fun previewVaults(): List<HomeVault> = listOf(
    HomeVault(SavedRepository("Personal notes", "/data/repos/cody/notes", "cody/notes", remoteUrl = "https://github.com/cody/notes.git"),
        RepositoryOverviewState.Unavailable),
    HomeVault(SavedRepository("VoxVault", "/data/repos/cody/voxvault", "cody/voxvault", remoteUrl = "https://github.com/cody/voxvault.git"),
        RepositoryOverviewState.Loaded(RepositoryOverview("main", "e19c4a0d2b", System.currentTimeMillis() - 3_600_000))),
    HomeVault(SavedRepository("Team wiki", "/data/repos/team/wiki", "", remoteUrl = "git@git.example.com:team/wiki.git"),
        RepositoryOverviewState.Loading),
    HomeVault(SavedRepository("Scratch", "/data/local/scratch"),
        RepositoryOverviewState.Loaded(RepositoryOverview("main", null, null))),
)

@Composable
private fun PreviewHome(vaults: List<HomeVault>, loggedIn: Boolean, dark: Boolean) {
    SyncMdTheme(darkTheme = dark) {
        ReposHomeContent(vaults, loggedIn, if (loggedIn) "cody" else null, 12, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(name = "Home · mixed states · light", heightDp = 900)
@Composable private fun PreviewHomeLight() = PreviewHome(previewVaults(), loggedIn = true, dark = false)

@Preview(name = "Home · mixed states · dark", heightDp = 900)
@Composable private fun PreviewHomeDark() = PreviewHome(previewVaults(), loggedIn = true, dark = true)

@Preview(name = "Home · all readable · signed out · light", heightDp = 900)
@Composable private fun PreviewHomeReadyLight() =
    PreviewHome(previewVaults().filter { it.overview is RepositoryOverviewState.Loaded }, loggedIn = false, dark = false)

@Preview(name = "Home · all readable · signed out · dark", heightDp = 900)
@Composable private fun PreviewHomeReadyDark() =
    PreviewHome(previewVaults().filter { it.overview is RepositoryOverviewState.Loaded }, loggedIn = false, dark = true)

@Preview(name = "Home · empty · signed in · light", heightDp = 900)
@Composable private fun PreviewHomeEmptyLight() = PreviewHome(emptyList(), loggedIn = true, dark = false)

@Preview(name = "Home · empty · signed out · dark", heightDp = 900)
@Composable private fun PreviewHomeEmptyDark() = PreviewHome(emptyList(), loggedIn = false, dark = true)

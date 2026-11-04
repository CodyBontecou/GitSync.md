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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.bontecou.syncmd.ui.theme.TideChevron
import com.bontecou.syncmd.ui.theme.TideHero
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
    /** A GitHub session problem (board "24 · Sign-in expired"), shown on Home with a sign-in action. */
    signInIssue: String? = null,
    onSignIn: () -> Unit = onAddRepo,
    onDismissSignInIssue: () -> Unit = {},
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
        signInIssue = signInIssue,
        onSignIn = onSignIn,
        onDismissSignInIssue = onDismissSignInIssue,
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
    signInIssue: String? = null,
    onSignIn: () -> Unit = onAddRepo,
    onDismissSignInIssue: () -> Unit = {},
) {
    val c = Tide.colors
    var adding by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(c.paper)) {
        Column(Modifier.fillMaxSize()) {
            HomeTopBar(
                isLoggedIn = isLoggedIn,
                signedInLogin = signedInLogin,
                onSignIn = onAddRepo,
                onAdd = { adding = true },
                onNavigateToSettings = onNavigateToSettings,
                onPullAll = onPullAll,
                onRepositoryActions = onRepositoryActions,
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = Tide.gutter).padding(top = 8.dp)) {
                    if (signInIssue != null) HomeSignInIssue(signInIssue, onSignIn, onDismissSignInIssue)
                    if (vaults.isEmpty()) {
                        HomeEmptyState(
                            isLoggedIn = isLoggedIn,
                            signedInLogin = signedInLogin,
                            gitHubRepoCount = gitHubRepoCount,
                            onAddRepo = onAddRepo,
                            onCloneFromUrl = onCloneFromUrl,
                            onManageRepositories = onManageRepositories,
                            onPublishFolder = onPublishFolder,
                        )
                    } else {
                        var filter by rememberSaveable { mutableStateOf(HomeFilter.ALL) }
                        val attention = vaults.count { it.needsAttention }
                        val shown = if (filter == HomeFilter.ATTENTION) vaults.filter { it.needsAttention } else vaults
                        HomeHeader(vaults)
                        TideChipRow(Modifier.padding(top = 8.dp)) {
                            TideFilterChip(stringResource(R.string.tide_home_filter_all), filter == HomeFilter.ALL,
                                onClick = { filter = HomeFilter.ALL }, count = vaults.size)
                            TideFilterChip(stringResource(R.string.tide_home_filter_attention), filter == HomeFilter.ATTENTION,
                                onClick = { filter = HomeFilter.ATTENTION }, count = attention,
                                dot = if (attention > 0) c.ahead else null)
                        }
                        Spacer(Modifier.height(8.dp))
                        if (shown.isEmpty()) {
                            TideCard {
                                Text(stringResource(R.string.tide_home_empty_filter_title), style = TideType.sans(16.sp, FontWeight.Medium), color = c.ink)
                                Spacer(Modifier.height(4.dp))
                                Text(stringResource(R.string.tide_home_empty_filter_detail), style = TideType.sans(14.sp), color = c.inkMid)
                            }
                        } else {
                            HomeVaultList(shown, onRepoSelected, onRepoRemoved)
                        }
                    }
                    // Room for the floating action button.
                    Spacer(Modifier.height(112.dp))
                }
            }
        }
        HomeFab(
            empty = vaults.isEmpty(),
            onPullAll = onPullAll,
            onAddFirst = onAddRepo,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 24.dp),
        )
    }
    if (adding) {
        AddVaultSheet(
            isLoggedIn = isLoggedIn,
            gitHubRepoCount = gitHubRepoCount,
            onDismiss = { adding = false },
            onAddRepo = { adding = false; onAddRepo() },
            onCloneFromUrl = { adding = false; onCloneFromUrl() },
            onManageRepositories = { adding = false; onManageRepositories() },
            onPublishFolder = { adding = false; onPublishFolder() },
        )
    }
}

/** Error-container card: sync is paused until the GitHub session is renewed; local notes are untouched. */
@Composable
private fun HomeSignInIssue(message: String, onSignIn: () -> Unit, onDismiss: () -> Unit) {
    val c = Tide.colors
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).background(c.errorContainer, Tide.heroShape).padding(20.dp)
            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }.testTag("home-sign-in-issue"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_m3_lock), contentDescription = null, tint = c.onErrorContainer, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.ui_github_sign_in_a2e50311), style = TideType.sans(14.sp, FontWeight.SemiBold), color = c.onErrorContainer,
                modifier = Modifier.semantics { heading() })
        }
        Text(message, style = TideType.sans(14.sp).copy(lineHeight = 20.sp), color = c.onErrorContainer)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.heightIn(min = 40.dp).clip(CircleShape).background(c.danger).clickable(role = Role.Button, onClick = onSignIn)
                .padding(horizontal = 24.dp).testTag("home-sign-in-renew"), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.guide_login), style = TideType.sans(14.sp, FontWeight.SemiBold), color = c.onError)
            }
            BGhostButton(stringResource(R.string.ui_dismiss_48845bff), color = c.onErrorContainer, onClick = onDismiss)
        }
    }
}

// ─── Top bar ──────────────────────────────────────────────────────────────────

/** gitsync.md wordmark, Add, overflow (Pull All, Repository actions) and the account avatar → Settings. */
@Composable
private fun HomeTopBar(
    isLoggedIn: Boolean,
    signedInLogin: String?,
    onSignIn: () -> Unit,
    onAdd: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onPullAll: () -> Unit,
    onRepositoryActions: () -> Unit,
) {
    val c = Tide.colors
    var menu by remember { mutableStateOf(false) }
    TideTopBar(
        center = { TideWordmark() },
        actions = {
            if (!isLoggedIn) {
                TideCircleButton(stringResource(R.string.guide_login), onSignIn) {
                    Icon(painterResource(R.drawable.ic_github), contentDescription = null, tint = c.inkMid, modifier = Modifier.size(22.dp))
                }
            }
            TideCircleButton(stringResource(R.string.ui_add_repository_33da2ac1), onAdd, Modifier.testTag("home-add-vault")) {
                Icon(painterResource(R.drawable.ic_m3_add), contentDescription = null, tint = c.inkMid, modifier = Modifier.size(24.dp))
            }
            Box {
                TideCircleButton(stringResource(R.string.ui_more_d47d7cb0), { menu = true }, Modifier.testTag("home-more")) {
                    Icon(painterResource(R.drawable.ic_m3_more_vert), contentDescription = null, tint = c.inkMid, modifier = Modifier.size(24.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(c.container)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.pull_all_title), style = TideType.sans(14.sp)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_m3_arrow_downward), contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = { menu = false; onPullAll() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.repository_actions_title), style = TideType.sans(14.sp)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_m3_sync), contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = { menu = false; onRepositoryActions() },
                        modifier = Modifier.testTag("open-repository-actions"),
                    )
                }
            }
            TideCircleButton(stringResource(R.string.ui_app_settings_da645334), onNavigateToSettings, Modifier.testTag("open-settings")) {
                val initials = signedInLogin?.filter { it.isLetterOrDigit() }?.take(2)?.uppercase()
                if (isLoggedIn && !initials.isNullOrEmpty()) {
                    Box(Modifier.size(32.dp).background(c.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Text(initials, style = TideType.sans(13.sp, FontWeight.SemiBold), color = c.onTertiaryContainer)
                    }
                } else {
                    Icon(painterResource(R.drawable.ic_m3_settings), contentDescription = null, tint = c.inkMid, modifier = Modifier.size(24.dp))
                }
            }
        },
    )
}

// ─── Header ───────────────────────────────────────────────────────────────────

/** Inverse hero: "1 vault needs you." (the second line in push orange) over a mono meta line. */
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
    val lines = headline.split('\n', limit = 2)
    TideHero(modifier.semantics(mergeDescendants = true) { heading() }.testTag("home-hero")) {
        Text(
            buildAnnotatedString {
                append(lines[0])
                if (lines.size > 1) {
                    append(' ')
                    withStyle(SpanStyle(color = if (attention > 0) c.heroAhead else c.heroMeta)) { append(lines[1]) }
                }
            },
            style = TideType.display(28.sp).copy(lineHeight = 34.sp),
            color = c.heroText,
        )
        Spacer(Modifier.height(6.dp))
        Text(parts.joinToString(" · "), style = TideType.meta, color = c.heroMeta)
    }
}

// ─── Vault list ───────────────────────────────────────────────────────────────

/** One M3 list in a 28dp tonal container; rows separated by outline-variant dividers. */
@Composable
private fun HomeVaultList(vaults: List<HomeVault>, onRepoSelected: (String) -> Unit, onRepoRemoved: (String) -> Unit) {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth().clip(Tide.cardShape).background(c.card)) {
        vaults.forEachIndexed { index, vault ->
            key(vault.repo.path) {
                if (index > 0) TideHairline()
                HomeVaultRow(
                    vault = vault,
                    onClick = { onRepoSelected(vault.repo.path) },
                    onRemove = { onRepoRemoved(vault.repo.path) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun HomeVaultRow(
    vault: HomeVault,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = Tide.colors
    val repo = vault.repo
    val scope = rememberCoroutineScope()
    var showConfirm by remember { mutableStateOf(false) }
    val offsetX     = remember { Animatable(0f) }
    val removeLabel = stringResource(R.string.ui_remove_repository_fc68168b)

    Box(Modifier.fillMaxWidth()) {
        // Error well revealed as the row slides left.
        Box(Modifier.matchParentSize().background(c.danger), contentAlignment = Alignment.CenterEnd) {
            Text(stringResource(R.string.ui_remove_c3812fc4), modifier = Modifier.padding(end = 24.dp),
                style = TideType.sans(14.sp, FontWeight.SemiBold), color = c.onError)
        }

        Row(
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
                .background(c.card)
                .testTag("repo-card-${repo.path}")
                .combinedClickable(onClick = onClick, onLongClick = { showConfirm = true })
                .semantics { customActions = listOf(CustomAccessibilityAction(removeLabel) { showConfirm = true; true }) }
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HomeVaultLead(repo.displayIdentity().name, vault.overview)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(repo.displayIdentity().name, style = TideType.sans(16.sp, FontWeight.Medium).copy(lineHeight = 24.sp), color = c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (val loaded = vault.overview) {
                    is RepositoryOverviewState.Loaded -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(loaded.overview.branch, Modifier.testTag("repo-overview-branch"),
                            style = TideType.meta.copy(textDirection = TextDirection.Ltr), color = c.inkMid, maxLines = 1)
                        Text("·", style = TideType.meta, color = c.inkMid)
                        Text(headCommitText(loaded.overview.headCommit), Modifier.testTag("repo-overview-head"),
                            style = TideType.meta, color = c.inkMid, maxLines = 1)
                        Text("·", style = TideType.meta, color = c.inkMid)
                        Text(recordedSyncText(loaded.overview.lastSyncAt), Modifier.testTag("repo-overview-sync"),
                            style = TideType.meta, color = c.inkMid, maxLines = 1)
                    }
                    RepositoryOverviewState.Loading -> Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TidePulse(color = c.inkMid)
                        Text(stringResource(R.string.ui_loading_repository_15b79262), Modifier.testTag("repo-overview-unavailable"),
                            style = TideType.meta, color = c.inkMid)
                    }
                    RepositoryOverviewState.Unavailable -> Text(stringResource(R.string.repository_metadata_unavailable),
                        Modifier.testTag("repo-overview-unavailable"), style = TideType.meta, color = c.danger)
                }
                Text(repoDisplayPath(repo) + " · " + remoteHostLabel(repo), style = TideType.meta.copy(textDirection = TextDirection.Ltr),
                    color = c.inkMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HomeVaultPill(vault.overview)
        }
    }

    if (showConfirm) {
        val displayName = if (repo.alias.isNotBlank()) repo.alias else repo.name
        TideDialog(
            onDismissRequest = { showConfirm = false },
            icon = { TideModalIcon() },
            title = removeLabel,
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

/** 40dp circle with the vault's initial, toned by what the home read could say. */
@Composable
private fun HomeVaultLead(name: String, state: RepositoryOverviewState) {
    val c = Tide.colors
    val (fill, ink) = when (state) {
        RepositoryOverviewState.Unavailable -> c.errorContainer to c.onErrorContainer
        RepositoryOverviewState.Loading -> c.well to c.inkMid
        is RepositoryOverviewState.Loaded -> if (state.overview.headCommit == null) c.well to c.inkMid else c.okContainer to c.onOkContainer
    }
    Box(Modifier.size(40.dp).background(fill, CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(name.firstOrNull { it.isLetterOrDigit() }?.toString() ?: "·", style = TideType.sans(16.sp, FontWeight.SemiBold), color = ink)
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
                else -> TidePill(stringResource(R.string.tide_home_state_synced, relativeTime(time)), c.synced, c.synced.copy(alpha = 0.12f))
            }
        }
    }
}

private fun relativeTime(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE).toString()

// ─── Floating action ──────────────────────────────────────────────────────────

/** M3 extended FAB: Pull All once there are vaults, "Add your first vault" before. */
@Composable
private fun HomeFab(empty: Boolean, onPullAll: () -> Unit, onAddFirst: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tide.colors
    val shape = RoundedCornerShape(16.dp)
    val (fill, ink) = if (empty) c.primary to c.onPrimary else c.primaryContainer to c.onPrimaryContainer
    Row(
        modifier
            .heightIn(min = 56.dp)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(fill)
            .clickable(role = Role.Button, onClick = if (empty) onAddFirst else onPullAll)
            .testTag(if (empty) "home-add-first" else "open-pull-all")
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(if (empty) R.drawable.ic_m3_add else R.drawable.ic_m3_arrow_downward), contentDescription = null,
            tint = ink, modifier = Modifier.size(24.dp))
        Text(stringResource(if (empty) R.string.tide_home_add_first else R.string.pull_all_title), style = TideType.sans(15.sp, FontWeight.SemiBold), color = ink)
    }
}

// ─── Add a vault ──────────────────────────────────────────────────────────────

/** Bottom sheet listing every way to bring a vault in (GitHub, URL, on this phone, publish a folder). */
@Composable
private fun AddVaultSheet(
    isLoggedIn: Boolean,
    gitHubRepoCount: Int,
    onDismiss: () -> Unit,
    onAddRepo: () -> Unit,
    onCloneFromUrl: () -> Unit,
    onManageRepositories: () -> Unit,
    onPublishFolder: () -> Unit,
) {
    TideDialog(
        onDismissRequest = onDismiss,
        sheet = true,
        title = stringResource(R.string.ui_add_repository_33da2ac1),
        modifier = Modifier.testTag("home-add-sheet"),
        confirmButton = { BGhostButton(stringResource(R.string.repository_actions_cancel), Modifier.fillMaxWidth(), onClick = onDismiss) },
    ) {
        HomeStartList(isLoggedIn, gitHubRepoCount, onAddRepo, onCloneFromUrl, onManageRepositories, onPublishFolder)
    }
}

@Composable
private fun HomeStartList(
    isLoggedIn: Boolean,
    gitHubRepoCount: Int,
    onAddRepo: () -> Unit,
    onCloneFromUrl: () -> Unit,
    onManageRepositories: () -> Unit,
    onPublishFolder: () -> Unit,
) {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth().clip(Tide.cardShape).background(if (c.isDark) c.container else c.card)) {
        HomeStartRow(R.drawable.ic_m3_branch, stringResource(R.string.tide_home_start_github),
            when {
                isLoggedIn && gitHubRepoCount > 0 -> pluralStringResource(R.plurals.tide_home_start_github_count, gitHubRepoCount, gitHubRepoCount)
                isLoggedIn -> stringResource(R.string.tide_home_start_github_signed_in)
                else -> stringResource(R.string.tide_home_start_github_signed_out)
            }, null, onAddRepo)
        TideHairline()
        HomeStartRow(R.drawable.ic_m3_link, stringResource(R.string.guide_url), stringResource(R.string.tide_home_start_url_detail), null, onCloneFromUrl)
        TideHairline()
        HomeStartRow(R.drawable.ic_m3_phone, stringResource(R.string.ui_local_repositories_ddcf4270), stringResource(R.string.tide_home_start_local_detail), null, onManageRepositories)
        TideHairline()
        HomeStartRow(R.drawable.ic_m3_publish, stringResource(R.string.publication_title), stringResource(R.string.tide_home_start_publish_detail),
            "open-folder-publication", onPublishFolder)
    }
}

@Composable
private fun HomeStartRow(icon: Int, title: String, subtitle: String, tag: String?, onClick: () -> Unit) {
    val c = Tide.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(40.dp).background(c.primaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = c.onPrimaryContainer, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = TideType.body, color = c.ink)
            Text(subtitle, style = TideType.sans(14.sp).copy(lineHeight = 20.sp), color = c.inkMid)
        }
        TideChevron()
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
    Column(modifier.fillMaxWidth()) {
        TideHero(Modifier.testTag("home-hero")) {
            Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                Text(stringResource(R.string.tide_home_empty_title), style = TideType.display(28.sp).copy(lineHeight = 34.sp), color = c.heroText)
                if (isLoggedIn && !signedInLogin.isNullOrBlank()) {
                    Text(stringResource(R.string.tide_home_signed_in_as, signedInLogin), style = TideType.meta, color = c.heroMeta,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            HomeEmptyRail(Modifier.padding(top = 20.dp))
        }
        TideSectionLabel(stringResource(R.string.tide_home_start_with), Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp))
        HomeStartList(isLoggedIn, gitHubRepoCount, onAddRepo, onCloneFromUrl, onManageRepositories, onPublishFolder)
    }
}

/** Dashed, empty sync rail inside the hero: Phone ┄┄┄ Remote, "nothing to sync". */
@Composable
private fun HomeEmptyRail(modifier: Modifier = Modifier) {
    val c = Tide.colors
    val description = stringResource(R.string.tide_home_empty_rail_description)
    val line = c.heroLine
    val ring = c.heroMeta
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.tide_home_rail_phone), style = TideType.mono(11.sp), color = c.heroMeta)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.upstream_remote), style = TideType.mono(11.sp), color = c.heroMeta)
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 6.dp).height(14.dp).drawBehind {
                val stroke = 2.dp.toPx()
                val r = 7.dp.toPx()
                val y = size.height / 2
                drawLine(line, Offset(r * 2, y), Offset(size.width - r * 2, y), strokeWidth = stroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                drawCircle(ring, radius = r - stroke / 2, center = Offset(r, y), style = Stroke(stroke))
                drawCircle(ring, radius = r - stroke / 2, center = Offset(size.width - r, y), style = Stroke(stroke))
            }
        )
        Text(stringResource(R.string.tide_home_empty_rail_nothing), Modifier.fillMaxWidth().padding(top = 8.dp),
            style = TideType.mono(11.sp), color = c.heroMeta, textAlign = TextAlign.Center)
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

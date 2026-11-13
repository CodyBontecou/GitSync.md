package com.bontecou.syncmd.ui.screens

import com.bontecou.syncmd.ui.util.quantityPhrase
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.git.RepositorySyncStatus
import com.bontecou.syncmd.storage.VaultPreference
import com.bontecou.syncmd.storage.collectAll
import com.bontecou.syncmd.ui.components.VaultStatusState
import com.bontecou.syncmd.ui.components.compactBytes
import com.bontecou.syncmd.ui.components.rememberMinuteClock
import com.bontecou.syncmd.ui.components.rememberVaultSyncStatus
import com.bontecou.syncmd.ui.components.relativeAgo
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Chevron
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3DropdownMenu
import com.bontecou.syncmd.ui.theme.M3ExtendedFab
import com.bontecou.syncmd.ui.theme.M3FabStyle
import com.bontecou.syncmd.ui.theme.M3FilterChip
import com.bontecou.syncmd.ui.theme.M3Hero
import com.bontecou.syncmd.ui.theme.M3HeroHeadline
import com.bontecou.syncmd.ui.theme.M3HeroKicker
import com.bontecou.syncmd.ui.theme.M3HeroMeta
import com.bontecou.syncmd.ui.theme.M3HeroStyle
import com.bontecou.syncmd.ui.theme.M3HomeTopBar
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3LeadingAvatar
import com.bontecou.syncmd.ui.theme.M3LeadingTile
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3ListRow
import com.bontecou.syncmd.ui.theme.M3MenuItem
import com.bontecou.syncmd.ui.theme.M3ModalSheet
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3RailState
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3SnackbarHost
import com.bontecou.syncmd.ui.theme.M3SyncIllustration
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.M3Badge
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.m3Gutter
import com.bontecou.syncmd.ui.util.displayIdentity
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.HomeViewModel
import com.bontecou.syncmd.ui.viewmodels.SavedRepository
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * Home: M3 boards 01 (home + Pinned card and chip), 23 (offline), 24 (sign-in expired), 26 (dark) and
 * 11 (empty). Every badge and count comes from a local read of each vault ([RepositorySyncStatus]):
 * ↑ commits to push, ↓ fetched commits to pull, edited files and the last fetch. "Sync all" pulls and
 * pushes every vault ([HomeViewModel.syncAll]).
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
    /** Kept for callers; Home's floating action is now Sync all (Pull All stays on its tile, widget and shortcut). */
    @Suppress("UNUSED_PARAMETER") onPullAll: () -> Unit = {},
    /** Kept for callers; repository actions are managed from Settings › Widget & launcher shortcuts. */
    @Suppress("UNUSED_PARAMETER") onRepositoryActions: () -> Unit = {},
    /** A GitHub session storage problem, shown on Home with a sign-in action. */
    signInIssue: String? = null,
    onSignIn: () -> Unit = onAddRepo,
    onDismissSignInIssue: () -> Unit = {},
    /** "Sign in with GitHub" on the sign-in-expired hero: a fresh GitHub sign-in that replaces the refused token. */
    onRenewSignIn: () -> Unit = onSignIn,
    /** The top-bar "+" opens the full-screen Add a vault (board 12). Null keeps the in-place sheet (previews, tests). */
    onOpenAddVault: (() -> Unit)? = null,
    home: HomeViewModel = hiltViewModel(),
) {
    val allRepos by settingsViewModel.allRepositories.collectAsState()
    val isLoggedIn by githubViewModel.isLoggedIn.collectAsState()
    val user by githubViewModel.user.collectAsState()
    val token by githubViewModel.authManager.token.collectAsState()
    val preferences by home.preferences.collectAll()
    val online by home.online.collectAsState()
    val syncAll by home.syncAll.collectAsState()
    val expiredRecord by (home.signInStatus?.expired ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsState()
    val expired = expiredRecord?.takeIf { home.signInStatus?.expiredFor(token) != null }

    // One lifecycle-bound read per vault, hoisted so the hero and chips can count every vault.
    val vaults = allRepos.map { repo ->
        key(repo.path) {
            val status by rememberVaultSyncStatus(repo.path)
            val credential by produceState<RepositoryCredentialStore.Summary?>(null, repo.path, expired) { value = home.credential(repo.path) }
            HomeVault(repo, preferences[repo.path] ?: VaultPreference(), status, credential, syncAll.results[repo.path], syncAll.current == repo.path)
        }
    }
    val mode = when {
        vaults.isEmpty() -> HomeMode.Empty
        expired != null -> HomeMode.Auth
        !online -> HomeMode.Offline
        else -> HomeMode.Home
    }
    val snackbar = remember { SnackbarHostState() }
    val snackbarScope = androidx.compose.runtime.rememberCoroutineScope()
    val finished = syncAll.finished
    val doneText = finished?.let {
        if (it.nothingToSync) pluralStringResource(R.plurals.m3home_sync_nothing_local, it.localOnly, it.localOnly)
        else stringResource(R.string.m3home_sync_done, it.pulled, it.pushed)
    }
    val failedText = finished?.failed?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.m3home_sync_failed, it.joinToString(", ")) }
    val busyText = stringResource(R.string.m3home_sync_busy)
    val startText = stringResource(R.string.m3home_sync_start_failed)
    LaunchedEffect(finished, syncAll.alreadyRunning, syncAll.failedToStart) {
        val message = when {
            syncAll.alreadyRunning -> busyText
            syncAll.failedToStart -> startText
            failedText != null -> failedText
            doneText != null -> doneText
            else -> null
        } ?: return@LaunchedEffect
        // Consuming changes this effect's keys, which cancels it: show the snackbar outside it, or it
        // is dismissed the moment it appears and Sync all ends without any feedback.
        snackbarScope.launch { snackbar.showSnackbar(message) }
        home.consumeFinished()
    }
    val pinFailed = stringResource(R.string.m3home_pin_failed)
    var pinError by remember { mutableStateOf(false) }
    LaunchedEffect(pinError) { if (pinError) { snackbar.showSnackbar(pinFailed); pinError = false } }

    ReposHomeContent(
        mode = mode,
        vaults = vaults,
        isLoggedIn = isLoggedIn,
        signedInLogin = user?.login,
        expiredAt = expired?.at,
        expiredAccount = expired?.accountId,
        syncing = syncAll.busy,
        syncDone = syncAll.done,
        syncTotal = syncAll.total,
        snackbar = snackbar,
        onRepoSelected = onRepoSelected,
        onRepoRemoved = { path -> onRepoRemoved(path); home.forget(path) },
        onTogglePin = { path, pinned -> if (!home.togglePinned(path, pinned)) pinError = true },
        onSyncAll = home::syncAll,
        onAddRepo = onAddRepo,
        onNavigateToSettings = onNavigateToSettings,
        onManageRepositories = onManageRepositories,
        onCloneFromUrl = onCloneFromUrl,
        onPublishFolder = onPublishFolder,
        signInIssue = signInIssue,
        onSignIn = onSignIn,
        onRenewSignIn = onRenewSignIn,
        onDismissSignInIssue = onDismissSignInIssue,
        onOpenAddVault = onOpenAddVault,
    )
}

internal enum class HomeMode { Home, Empty, Offline, Auth }

internal data class HomeVault(
    val repo: SavedRepository,
    val preference: VaultPreference,
    val status: VaultStatusState,
    val credential: RepositoryCredentialStore.Summary? = null,
    val lastSync: HomeViewModel.Outcome? = null,
    val syncing: Boolean = false,
) {
    val name: String get() = preference.displayName ?: repo.displayIdentity().name
    val pinned: Boolean get() = preference.pinned
    val loaded: RepositorySyncStatus? get() = (status as? VaultStatusState.Loaded)?.status
    val ahead: Int get() = loaded?.ahead ?: 0
    val behind: Int get() = loaded?.behind ?: 0
    val failed: Boolean get() = lastSync?.failed == true
    val inSync: Boolean get() = loaded?.inSync == true && !failed
    /** Counted under "Attention": anything except in sync or still checking. */
    val needsAttention: Boolean get() = status != VaultStatusState.Loading && !inSync
    /** Uses the GitHub account whose sign-in expired (a vault without an assigned account uses the default). */
    fun waitsOnSignIn(account: String?): Boolean = credential?.usesGithubAccount == true &&
        (account == null || credential.accountId == null || credential.accountId == account)
}

private enum class HomeFilter { ALL, ATTENTION, PINNED }

/** Stateless Home: every visible state is a function of these inputs. */
@Composable
internal fun ReposHomeContent(
    mode: HomeMode,
    vaults: List<HomeVault>,
    isLoggedIn: Boolean,
    signedInLogin: String?,
    expiredAt: Long? = null,
    expiredAccount: String? = null,
    syncing: Boolean = false,
    syncDone: Int = 0,
    syncTotal: Int = 0,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onRepoSelected: (String) -> Unit = {},
    onRepoRemoved: (String) -> Unit = {},
    onTogglePin: (String, Boolean) -> Unit = { _, _ -> },
    onSyncAll: () -> Unit = {},
    onAddRepo: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onManageRepositories: () -> Unit = {},
    onCloneFromUrl: () -> Unit = {},
    onPublishFolder: () -> Unit = {},
    signInIssue: String? = null,
    onSignIn: () -> Unit = {},
    onRenewSignIn: () -> Unit = onSignIn,
    onDismissSignInIssue: () -> Unit = {},
    onOpenAddVault: (() -> Unit)? = null,
) {
    val c = M3.colors
    val now = rememberMinuteClock()
    var adding by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(HomeFilter.ALL) }
    Box(Modifier.fillMaxSize().background(c.surface)) {
        Column(Modifier.fillMaxSize()) {
            val initials = signedInLogin?.filter { it.isLetterOrDigit() }?.take(2)?.uppercase()?.takeIf { isLoggedIn && it.isNotEmpty() }
            M3HomeTopBar(
                avatarInitials = initials,
                avatarDescription = signedInLogin?.let { stringResource(R.string.m3home_settings_signed_in, it) },
                onAvatarClick = onNavigateToSettings,
                modifier = if (initials != null) Modifier.testTag("open-settings") else Modifier,
            ) {
                M3IconButton(R.drawable.ic_m3_add, stringResource(R.string.m3home_add_vault), { onOpenAddVault?.invoke() ?: run { adding = true } }, Modifier.testTag("home-add-vault"))
                if (initials == null) M3IconButton(R.drawable.ic_m3_settings, stringResource(R.string.m3home_settings), onNavigateToSettings,
                    Modifier.testTag("open-settings"))
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 8.dp)) {
                    if (signInIssue != null) SessionIssue(signInIssue, onSignIn, onDismissSignInIssue)
                    when (mode) {
                        HomeMode.Empty -> HomeEmptyState(isLoggedIn, signedInLogin, onAddRepo, onCloneFromUrl, onManageRepositories, onPublishFolder)
                        HomeMode.Auth -> AuthState(vaults, expiredAt, expiredAccount, now, onRenewSignIn, onRepoSelected, onRepoRemoved, onTogglePin)
                        HomeMode.Offline, HomeMode.Home -> {
                            val offline = mode == HomeMode.Offline
                            if (offline) OfflineHero(vaults, now) else HomeHero(vaults, now)
                            val attention = vaults.count { it.needsAttention }
                            if (!offline) Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                M3FilterChip(stringResource(R.string.m3home_filter_all, vaults.size), filter == HomeFilter.ALL, { filter = HomeFilter.ALL },
                                    Modifier.testTag("home-filter-all"))
                                M3FilterChip(stringResource(R.string.m3home_filter_attention, attention), filter == HomeFilter.ATTENTION, { filter = HomeFilter.ATTENTION },
                                    Modifier.testTag("home-filter-attention"))
                                M3FilterChip(stringResource(R.string.m3home_filter_pinned), filter == HomeFilter.PINNED, { filter = HomeFilter.PINNED },
                                    Modifier.testTag("home-filter-pinned"))
                            }
                            val active = if (offline) HomeFilter.ALL else filter
                            val pinned = vaults.filter { it.pinned && (active != HomeFilter.ATTENTION || it.needsAttention) }
                            val listed = when (active) {
                                HomeFilter.ALL -> vaults.filterNot { it.pinned }
                                HomeFilter.ATTENTION -> vaults.filter { !it.pinned && it.needsAttention }
                                HomeFilter.PINNED -> emptyList()
                            }
                            pinned.forEach { vault ->
                                key(vault.repo.path) {
                                    PinnedCard(vault, offline, onClick = { onRepoSelected(vault.repo.path) },
                                        onUnpin = { onTogglePin(vault.repo.path, false) }, onRemove = { onRepoRemoved(vault.repo.path) },
                                        modifier = Modifier.padding(top = 12.dp))
                                }
                            }
                            if (listed.isNotEmpty()) {
                                VaultList(listed, now, offline, Modifier.padding(top = 12.dp), onRepoSelected, onRepoRemoved, onTogglePin)
                            } else if (pinned.isEmpty() && active != HomeFilter.ALL) {
                                M3Note(
                                    stringResource(if (active == HomeFilter.PINNED) R.string.m3home_filter_empty_pinned else R.string.m3home_filter_empty_attention),
                                    Modifier.m3Gutter().padding(top = 12.dp), tone = M3NoteTone.Muted,
                                )
                            }
                        }
                    }
                    // Room for the floating action button.
                    Spacer(Modifier.height(120.dp))
                }
            }
        }
        val fabModifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 24.dp)
        when (mode) {
            HomeMode.Empty -> M3ExtendedFab(stringResource(R.string.m3home_add_first), R.drawable.ic_m3_add, onAddRepo, fabModifier.testTag("home-add-first"),
                style = M3FabStyle.Primary)
            HomeMode.Offline -> M3ExtendedFab(stringResource(R.string.m3home_offline_fab), R.drawable.ic_m3_cloud_off, null, fabModifier.testTag("home-sync-all"),
                meta = vaults.sumOf { it.ahead }.takeIf { it > 0 }?.let { "↑$it" }, style = M3FabStyle.Flat)
            HomeMode.Auth -> Unit
            HomeMode.Home -> if (syncing) M3ExtendedFab(stringResource(R.string.m3home_syncing), R.drawable.ic_m3_sync, null,
                fabModifier.testTag("home-sync-all").semantics { liveRegion = LiveRegionMode.Polite },
                meta = if (syncTotal > 0) stringResource(R.string.m3home_syncing_meta, syncDone, syncTotal) else null)
            else M3ExtendedFab(stringResource(R.string.m3home_sync_all), R.drawable.ic_m3_sync, onSyncAll, fabModifier.testTag("home-sync-all"),
                meta = stringResource(R.string.m3home_sync_all_meta, vaults.sumOf { it.behind }, vaults.sumOf { it.ahead }))
        }
        M3SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }
    if (adding) {
        M3ModalSheet(onDismissRequest = { adding = false }, title = stringResource(R.string.m3home_add_vault), modifier = Modifier.testTag("home-add-sheet")) {
            StartList(
                onAddRepo = { adding = false; onAddRepo() },
                onCloneFromUrl = { adding = false; onCloneFromUrl() },
                onManageRepositories = { adding = false; onManageRepositories() },
                onPublishFolder = { adding = false; onPublishFolder() },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

// ─── Heroes ───────────────────────────────────────────────────────────────────

/** Most recent fetch across [vaults], for "checked 2 min ago". */
private fun lastChecked(vaults: List<HomeVault>): Long? = vaults.mapNotNull { it.loaded?.checkedAt ?: it.loaded?.lastSyncAt }.maxOrNull()

/** Inverse hero: "1 vault needs you." over "4 in sync · checked 2 min ago". */
@Composable
private fun HomeHero(vaults: List<HomeVault>, now: Long) {
    val attention = vaults.count { it.needsAttention }
    val checking = vaults.count { it.status == VaultStatusState.Loading }
    val inSync = vaults.count { it.inSync }
    val checked = lastChecked(vaults)
    M3Hero(Modifier.m3Gutter().testTag("home-hero"), style = M3HeroStyle.Inverse) {
        if (attention > 0) {
            M3HeroHeadline(quantityPhrase(R.plurals.m3home_headline_attention_accent, attention,
                pluralStringResource(R.plurals.m3home_headline_attention, attention, attention)))
        } else {
            M3HeroHeadline(pluralStringResource(R.plurals.m3home_headline_ready, vaults.size, vaults.size),
                accent = stringResource(R.string.m3home_headline_ready_accent), accentColor = M3.colors.invOk)
        }
        val parts = buildList {
            add(stringResource(R.string.m3home_meta_in_sync, inSync))
            if (checking > 0) add(stringResource(R.string.m3home_meta_checking, checking))
            add(checked?.let { stringResource(R.string.m3home_meta_checked, relativeAgo(it, now, longMinutes = true)) }
                ?: stringResource(R.string.m3home_meta_never_checked))
        }
        M3HeroMeta(parts.joinToString(" · "))
    }
}

/** Board 23: cHighest card with the cloud-off icon, "Keep writing. / We'll catch up." */
@Composable
private fun OfflineHero(vaults: List<HomeVault>, now: Long) {
    val c = M3.colors
    val checked = lastChecked(vaults)
    M3Hero(Modifier.m3Gutter().testTag("home-offline").semantics { liveRegion = LiveRegionMode.Polite }, style = M3HeroStyle.Highest,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            M3Icon(R.drawable.ic_m3_cloud_off, Modifier.padding(top = 4.dp), tint = c.onVar)
            Column {
                M3HeroHeadline(stringResource(R.string.m3home_offline_headline), accent = stringResource(R.string.m3home_offline_accent),
                    accentOnNewLine = true, style = M3Type.headlineSmall)
                M3HeroMeta(checked?.let { stringResource(R.string.m3home_offline_meta, relativeAgo(it, now, longMinutes = true)) }
                    ?: stringResource(R.string.m3home_offline_meta_never))
            }
        }
    }
}

// ─── Badges ───────────────────────────────────────────────────────────────────

private data class Badge(val text: String, val tone: M3Tone)

/** What the local read can honestly say about one vault. */
@Composable
private fun badgeFor(vault: HomeVault, offline: Boolean = false): Badge {
    val s = vault.loaded
    if (vault.syncing) return Badge(stringResource(R.string.m3home_badge_syncing), M3Tone.Behind)
    return when {
        vault.status == VaultStatusState.Loading -> Badge(stringResource(R.string.m3home_badge_checking), M3Tone.Muted)
        s == null -> Badge(stringResource(R.string.m3home_badge_unreadable), M3Tone.Warn)
        offline -> when {
            s.ahead > 0 -> Badge(stringResource(R.string.m3home_offline_queued, s.ahead), M3Tone.Muted)
            s.behind > 0 -> Badge(stringResource(R.string.m3home_offline_last_known_behind, s.behind), M3Tone.Muted)
            s.changes > 0 -> Badge(pluralStringResource(R.plurals.m3home_badge_edited, s.changes, s.changes), M3Tone.Muted)
            else -> Badge(stringResource(R.string.m3home_offline_last_known), M3Tone.Muted)
        }
        !s.safe -> Badge(stringResource(R.string.m3home_badge_needs_you), M3Tone.Warn)
        vault.failed -> Badge(stringResource(R.string.m3home_badge_sync_failed), M3Tone.Warn)
        s.ahead > 0 && s.behind > 0 -> Badge(stringResource(R.string.m3home_badge_both, s.behind, s.ahead), M3Tone.Ahead)
        s.ahead > 0 -> Badge(stringResource(R.string.m3home_badge_to_push, s.ahead), M3Tone.Ahead)
        s.behind > 0 -> Badge(stringResource(R.string.m3home_badge_to_pull, s.behind), M3Tone.Behind)
        s.changes > 0 -> Badge(pluralStringResource(R.plurals.m3home_badge_edited, s.changes, s.changes), M3Tone.Ahead)
        s.upstream == null && s.remoteUrl == null -> Badge(stringResource(R.string.m3home_badge_local_only), M3Tone.Muted)
        else -> Badge(stringResource(R.string.m3home_badge_in_sync), M3Tone.Ok)
    }
}

/** "main · 12m ago · 2 MB LFS". */
@Composable
private fun rowMeta(vault: HomeVault, now: Long, offline: Boolean): String {
    val s = vault.loaded ?: return stringResource(
        if (vault.status == VaultStatusState.Loading) R.string.m3home_meta_loading else R.string.m3home_meta_unreadable)
    val synced = s.lastSyncAt?.let { relativeAgo(it, now) } ?: stringResource(R.string.m3home_meta_not_synced)
    val branch = s.branch.ifBlank { s.headCommit?.take(7) ?: "—" }
    val base = if (offline && s.lastSyncAt != null) stringResource(R.string.m3home_offline_row_meta, branch, synced) else "$branch · $synced"
    return if (s.lfsBytes > 0) base + " · " + stringResource(R.string.m3home_meta_lfs, compactBytes(s.lfsBytes)) else base
}

// ─── Pinned card ──────────────────────────────────────────────────────────────

/** tcSoft card: "Pinned" kicker, name with its mono state, "main · owner/repo" and the Phone ↔ GitHub rail. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PinnedCard(vault: HomeVault, offline: Boolean, onClick: () -> Unit, onUnpin: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val c = M3.colors
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val s = vault.loaded
    val badge = when {
        s != null && offline && s.ahead > 0 -> stringResource(R.string.m3home_offline_queued, s.ahead)
        else -> badgeFor(vault, offline).text
    }
    val unpinLabel = stringResource(R.string.m3home_unpin)
    val removeLabel = stringResource(R.string.m3home_remove)
    Box(modifier.m3Gutter()) {
        M3Hero(
            Modifier.testTag("home-pinned-${vault.repo.path}")
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                .combinedClickable(onClick = onClick, onLongClick = { menu = true },
                    onLongClickLabel = stringResource(R.string.m3home_row_actions), role = androidx.compose.ui.semantics.Role.Button)
                .semantics { customActions = listOf(CustomAccessibilityAction(unpinLabel) { onUnpin(); true },
                    CustomAccessibilityAction(removeLabel) { confirmRemove = true; true }) },
            style = M3HeroStyle.PinnedSoft,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 18.dp),
        ) {
            M3HeroKicker(stringResource(R.string.m3home_pinned), icon = R.drawable.ic_m3_pin)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                Text(vault.name, style = M3Type.title, color = c.on, modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(badge, style = M3Type.count, color = c.tertiary, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
            }
            Text(listOfNotNull(s?.branch?.takeIf { it.isNotBlank() }, remoteSlug(vault)).joinToString(" · "),
                style = M3Type.meta, color = c.onVar, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val state = when {
                s == null -> M3RailState.Empty
                s.ahead > 0 -> M3RailState.Pending
                s.behind > 0 -> M3RailState.Incoming
                s.inSync -> M3RailState.InSync
                else -> M3RailState.Pending
            }
            M3SyncIllustration(
                stringResource(R.string.m3home_rail_phone), remoteHostLabel(vault), state,
                Modifier.padding(top = 16.dp), onHero = false,
                dots = when (state) { M3RailState.Pending -> s?.ahead ?: 0; M3RailState.Incoming -> s?.behind ?: 0; else -> 0 }.coerceAtMost(3),
                background = c.tcSoft,
            )
        }
        // Long-press opens the same menu as the list rows.
        Box(Modifier.align(Alignment.TopEnd)) {
            M3DropdownMenu(menu, { menu = false }) {
                M3MenuItem(unpinLabel, { menu = false; onUnpin() }, icon = R.drawable.ic_m3_pin)
                M3MenuItem(removeLabel, { menu = false; confirmRemove = true }, icon = R.drawable.ic_m3_delete, destructive = true)
            }
        }
    }
    if (confirmRemove) RemoveDialog(vault, onDismiss = { confirmRemove = false }, onRemove = { confirmRemove = false; onRemove() })
}

// ─── Vault list ───────────────────────────────────────────────────────────────

@Composable
private fun VaultList(
    vaults: List<HomeVault>, now: Long, offline: Boolean, modifier: Modifier,
    onRepoSelected: (String) -> Unit, onRepoRemoved: (String) -> Unit, onTogglePin: (String, Boolean) -> Unit,
    meta: @Composable (HomeVault) -> String = { rowMeta(it, now, offline) },
    badge: @Composable (HomeVault) -> Badge = { badgeFor(it, offline) },
) {
    M3ListCard(modifier.m3Gutter()) {
        vaults.forEachIndexed { index, vault ->
            key(vault.repo.path) {
                VaultRow(vault, meta(vault), badge(vault), divider = index > 0,
                    onClick = { onRepoSelected(vault.repo.path) }, onRemove = { onRepoRemoved(vault.repo.path) },
                    onTogglePin = { onTogglePin(vault.repo.path, !vault.pinned) })
            }
        }
    }
}

/** 72dp row: toned initial, name over mono meta, the state badge. Long-press: Pin to top / Remove. */
@Composable
private fun VaultRow(vault: HomeVault, meta: String, badge: Badge, divider: Boolean, onClick: () -> Unit, onRemove: () -> Unit, onTogglePin: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val pinLabel = stringResource(if (vault.pinned) R.string.m3home_unpin else R.string.m3home_pin)
    val removeLabel = stringResource(R.string.m3home_remove)
    Box {
        M3ListRow(
            title = vault.name,
            meta = meta,
            titleStyle = M3Type.bodyStrong,
            titleMaxLines = 1,
            leading = { M3LeadingAvatar(vault.name.firstOrNull { it.isLetterOrDigit() }?.toString() ?: "·", badge.tone) },
            trailing = { M3Badge(badge.text, badge.tone) },
            onClick = onClick,
            onLongClick = { menu = true },
            onLongClickLabel = stringResource(R.string.m3home_row_actions),
            divider = divider,
            modifier = Modifier.testTag("repo-card-${vault.repo.path}").semantics {
                customActions = listOf(CustomAccessibilityAction(pinLabel) { onTogglePin(); true },
                    CustomAccessibilityAction(removeLabel) { confirmRemove = true; true })
            },
        )
        M3DropdownMenu(menu, { menu = false }, offset = androidx.compose.ui.unit.DpOffset(72.dp, 0.dp)) {
            M3MenuItem(pinLabel, { menu = false; onTogglePin() }, Modifier.testTag("repo-pin"), icon = R.drawable.ic_m3_pin)
            M3MenuItem(removeLabel, { menu = false; confirmRemove = true }, Modifier.testTag("repo-remove"), icon = R.drawable.ic_m3_delete, destructive = true)
        }
    }
    if (confirmRemove) RemoveDialog(vault, onDismiss = { confirmRemove = false }, onRemove = { confirmRemove = false; onRemove() })
}

@Composable
private fun RemoveDialog(vault: HomeVault, onDismiss: () -> Unit, onRemove: () -> Unit) {
    M3Dialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.ui_remove_repository_fc68168b),
        icon = R.drawable.ic_m3_delete,
        text = stringResource(R.string.ui_remove_1_s_this_removes_it_from_gitsync_md_0a6b198d, vault.name),
        modifier = Modifier.testTag("repo-remove-body"),
    ) {
        M3DialogAction(stringResource(R.string.repository_actions_cancel), onDismiss)
        M3DialogAction(stringResource(R.string.ui_remove_c3812fc4), onRemove, destructive = true)
    }
}

// ─── Sign-in expired (board 24) ───────────────────────────────────────────────

@Composable
private fun AuthState(
    vaults: List<HomeVault>, expiredAt: Long?, account: String?, now: Long, onSignIn: () -> Unit,
    onRepoSelected: (String) -> Unit, onRepoRemoved: (String) -> Unit, onTogglePin: (String, Boolean) -> Unit,
) {
    val waiting = vaults.filter { it.waitsOnSignIn(account) }
    val unaffected = vaults.filterNot { it.waitsOnSignIn(account) }
    M3Hero(Modifier.m3Gutter().testTag("home-sign-in-expired").semantics { liveRegion = LiveRegionMode.Polite }, style = M3HeroStyle.ErrorContainer) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            M3Icon(R.drawable.ic_m3_lock, size = 20.dp)
            Text(stringResource(R.string.m3home_auth_kicker, expiredAt?.let { relativeAgo(it, now) } ?: stringResource(R.string.m3home_time_now)),
                style = M3Type.section, color = androidx.compose.material3.LocalContentColor.current)
        }
        M3HeroHeadline(stringResource(R.string.m3home_auth_headline), Modifier.padding(top = 10.dp))
        val body = stringResource(R.string.m3home_auth_body) +
            if (waiting.isNotEmpty()) " " + pluralStringResource(R.plurals.m3home_auth_waiting, waiting.size, waiting.size) else ""
        Text(body, style = M3Type.bodyMedium, color = androidx.compose.material3.LocalContentColor.current, modifier = Modifier.padding(top = 6.dp))
        M3Button(stringResource(R.string.m3home_auth_sign_in), onSignIn, Modifier.padding(top = 16.dp).testTag("home-sign-in-renew"),
            style = M3ButtonStyle.Danger)
    }
    if (waiting.isNotEmpty()) {
        M3SectionLabel(stringResource(R.string.m3home_auth_waiting_label))
        VaultList(waiting, now, offline = false, Modifier.padding(top = 8.dp), onRepoSelected, onRepoRemoved, onTogglePin,
            meta = { v ->
                val s = v.loaded
                stringResource(R.string.m3home_auth_row_meta, s?.branch?.ifBlank { null } ?: "—",
                    s?.lastSyncAt?.let { relativeAgo(it, now) } ?: stringResource(R.string.m3home_meta_not_synced))
            },
            badge = { v -> if (v.ahead > 0) Badge(stringResource(R.string.m3home_auth_held, v.ahead), M3Tone.Ahead)
                else Badge(stringResource(R.string.m3home_auth_paused), M3Tone.Warn) })
    }
    if (unaffected.isNotEmpty()) {
        M3SectionLabel(stringResource(R.string.m3home_auth_unaffected_label))
        VaultList(unaffected, now, offline = false, Modifier.padding(top = 8.dp), onRepoSelected, onRepoRemoved, onTogglePin,
            meta = { v -> listOf(remoteHostLabel(v), credentialLabel(v)).joinToString(" · ") })
    }
}

@Composable
private fun credentialLabel(vault: HomeVault): String {
    val credential = vault.credential
    return stringResource(when {
        credential == null -> if (vault.loaded?.remoteUrl == null && vault.repo.remoteUrl == null) R.string.m3home_cred_local else R.string.m3home_cred_public
        credential.usesSshKey -> R.string.m3home_cred_ssh
        credential.usesGithubAccount -> R.string.m3home_cred_github
        credential.hasSecret -> R.string.m3home_cred_token
        else -> R.string.m3home_cred_public
    })
}

/** GitHub session storage problems (not an expiry): a warn note with sign-in and dismiss. */
@Composable
private fun SessionIssue(message: String, onSignIn: () -> Unit, onDismiss: () -> Unit) {
    M3Note(message, Modifier.m3Gutter().padding(bottom = 12.dp).testTag("home-sign-in-issue"), tone = M3NoteTone.Warn, icon = R.drawable.ic_m3_lock) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            M3TextButton(stringResource(R.string.m3home_auth_sign_in), onSignIn)
            M3TextButton(stringResource(R.string.m3home_session_dismiss), onDismiss)
        }
    }
}

// ─── Empty Home (board 11) ────────────────────────────────────────────────────

@Composable
private fun HomeEmptyState(
    isLoggedIn: Boolean,
    signedInLogin: String?,
    onAddRepo: () -> Unit,
    onCloneFromUrl: () -> Unit,
    onManageRepositories: () -> Unit,
    onPublishFolder: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        M3Hero(Modifier.m3Gutter().testTag("home-hero"), style = M3HeroStyle.Inverse) {
            M3HeroHeadline(stringResource(R.string.m3home_empty_title))
            if (isLoggedIn && !signedInLogin.isNullOrBlank()) M3HeroMeta(stringResource(R.string.m3home_empty_signed_in, signedInLogin), topPadding = 4.dp)
            M3SyncIllustration(
                stringResource(R.string.m3home_rail_phone), stringResource(R.string.m3home_rail_github), M3RailState.Empty,
                Modifier.padding(top = 20.dp), caption = stringResource(R.string.m3home_empty_rail_nothing),
                description = stringResource(R.string.m3home_empty_rail_description),
            )
        }
        M3SectionLabel(stringResource(R.string.m3home_start_with), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp))
        StartList(onAddRepo, onCloneFromUrl, onManageRepositories, onPublishFolder, Modifier.m3Gutter())
    }
}

/** The four ways in, as one tonal list (also the Add-a-vault sheet). */
@Composable
private fun StartList(onAddRepo: () -> Unit, onCloneFromUrl: () -> Unit, onManageRepositories: () -> Unit, onPublishFolder: () -> Unit, modifier: Modifier = Modifier) {
    M3ListCard(modifier) {
        StartRow(R.drawable.ic_m3_branch, R.string.m3home_start_github, R.string.m3home_start_github_detail, false, "home-start-github", onAddRepo)
        StartRow(R.drawable.ic_m3_link, R.string.m3home_start_url, R.string.m3home_start_url_detail, true, "home-start-url", onCloneFromUrl)
        StartRow(R.drawable.ic_m3_phone, R.string.m3home_start_local, R.string.m3home_start_local_detail, true, "home-start-local", onManageRepositories)
        StartRow(R.drawable.ic_m3_publish, R.string.m3home_start_publish, R.string.m3home_start_publish_detail, true, "open-folder-publication", onPublishFolder)
    }
}

@Composable
private fun StartRow(icon: Int, title: Int, detail: Int, divider: Boolean, tag: String, onClick: () -> Unit) {
    M3ListRow(
        title = stringResource(title), meta = stringResource(detail), metaMono = false,
        leading = { M3LeadingTile(icon) }, trailing = { M3Chevron() },
        onClick = onClick, divider = divider, modifier = Modifier.testTag(tag),
    )
}

// ─── Display helpers ─────────────────────────────────────────────────────────

/** "CodyBontecou/VoxVault" from the remote URL; the folder name for a local vault. */
private fun remoteSlug(vault: HomeVault): String {
    val url = vault.repo.remoteUrl ?: vault.loaded?.remoteUrl
    val path = url?.trim()?.let { raw ->
        runCatching { java.net.URI(raw).path }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: Regex("^[^/@:]+@[^/:]+:(.+)$").matchEntire(raw)?.groupValues?.get(1)
    }
    val parts = path?.trim('/')?.removeSuffix(".git")?.split('/')?.filter { it.isNotBlank() }.orEmpty()
    return if (parts.size >= 2) parts.takeLast(2).joinToString("/") else vault.repo.path.removePrefix("github://").substringAfterLast('/')
}

/** "GitHub", the remote's host, or "Remote" for the rail's far end. */
@Composable
private fun remoteHostLabel(vault: HomeVault): String {
    val remote = (vault.repo.remoteUrl ?: vault.loaded?.remoteUrl)?.trim().orEmpty()
    val host = when {
        remote.isBlank() -> null
        Regex("^[^/@:]+@[^/:]+:.+$").matches(remote) -> remote.substringAfter("@").substringBefore(":")
        else -> runCatching { java.net.URI(remote).host }.getOrNull()
    }
    return when {
        host.isNullOrBlank() -> if (vault.repo.path.startsWith("github://")) stringResource(R.string.m3home_rail_github) else stringResource(R.string.m3home_rail_remote)
        host.equals("github.com", ignoreCase = true) -> stringResource(R.string.m3home_rail_github)
        else -> host
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

private fun previewStatus(ahead: Int = 0, behind: Int = 0, changes: Int = 0, syncedMinutesAgo: Long = 12, lfs: Long = 0, branch: String = "main",
    remote: String? = "https://github.com/CodyBontecou/notes.git") = VaultStatusState.Loaded(RepositorySyncStatus(
    branch, "a41c9e2d", ahead, behind, changes, RepositorySyncStatus.Upstream("origin", branch), true,
    System.currentTimeMillis() - 120_000, System.currentTimeMillis() - syncedMinutesAgo * 60_000, lfs, remote))

private fun previewVaults(): List<HomeVault> = listOf(
    HomeVault(SavedRepository("VoxVault", "/data/voxvault", remoteUrl = "https://github.com/CodyBontecou/VoxVault.git"), VaultPreference(pinned = true),
        previewStatus(ahead = 3), RepositoryCredentialStore.Summary("https://github.com/CodyBontecou/VoxVault", null, false, usesGithubAccount = true)),
    HomeVault(SavedRepository("notes", "/data/notes"), VaultPreference(), previewStatus(),
        RepositoryCredentialStore.Summary("https://github.com/CodyBontecou/notes", null, false, usesGithubAccount = true)),
    HomeVault(SavedRepository("health-md", "/data/health"), VaultPreference(), previewStatus(syncedMinutesAgo = 60, lfs = 2L shl 20)),
    HomeVault(SavedRepository("sacralsol", "/data/sacralsol"), VaultPreference(), previewStatus(syncedMinutesAgo = 180, branch = "draft/spring")),
    HomeVault(SavedRepository("recipes", "/data/recipes", remoteUrl = "git@gitea.home.lan:cody/recipes.git"), VaultPreference(),
        previewStatus(syncedMinutesAgo = 1500, remote = "git@gitea.home.lan:cody/recipes.git"),
        RepositoryCredentialStore.Summary("ssh://gitea.home.lan/cody/recipes", null, true, usesSshKey = true)),
)

@Composable
private fun PreviewHome(mode: HomeMode, dark: Boolean, vaults: List<HomeVault> = previewVaults()) {
    SyncMdTheme(darkTheme = dark) {
        ReposHomeContent(mode, if (mode == HomeMode.Empty) emptyList() else vaults, true, "CodyBontecou",
            expiredAt = System.currentTimeMillis() - 7_200_000)
    }
}

@Preview(name = "01 Home · light", heightDp = 915, widthDp = 412) @Composable private fun PreviewHomeLight() = PreviewHome(HomeMode.Home, false)
@Preview(name = "26 Home · dark", heightDp = 915, widthDp = 412) @Composable private fun PreviewHomeDark() = PreviewHome(HomeMode.Home, true)
@Preview(name = "23 Offline", heightDp = 915, widthDp = 412) @Composable private fun PreviewOffline() = PreviewHome(HomeMode.Offline, false)
@Preview(name = "24 Sign-in expired", heightDp = 915, widthDp = 412) @Composable private fun PreviewAuth() = PreviewHome(HomeMode.Auth, false)
@Preview(name = "11 Empty", heightDp = 915, widthDp = 412) @Composable private fun PreviewEmpty() = PreviewHome(HomeMode.Empty, false)
@Preview(name = "11 Empty · dark", heightDp = 915, widthDp = 412) @Composable private fun PreviewEmptyDark() = PreviewHome(HomeMode.Empty, true)

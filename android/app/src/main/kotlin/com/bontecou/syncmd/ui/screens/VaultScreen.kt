package com.bontecou.syncmd.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.GitFileStatusKind
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.data.models.RepositoryStatus
import com.bontecou.syncmd.services.git.FileLineCount
import com.bontecou.syncmd.services.git.VaultOverview
import com.bontecou.syncmd.sync.VaultSyncResult
import com.bontecou.syncmd.sync.VaultSyncRun
import com.bontecou.syncmd.ui.components.VaultNavigationBar
import com.bontecou.syncmd.ui.components.VaultPushRejectedSection
import com.bontecou.syncmd.ui.components.VaultSyncingSection
import com.bontecou.syncmd.ui.components.VaultTab
import com.bontecou.syncmd.ui.components.headCommitText
import com.bontecou.syncmd.ui.components.recordedSyncText
import com.bontecou.syncmd.ui.components.vaultAgo
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3DiffStat
import com.bontecou.syncmd.ui.theme.M3DropdownMenu
import com.bontecou.syncmd.ui.theme.M3Group
import com.bontecou.syncmd.ui.theme.M3Hero
import com.bontecou.syncmd.ui.theme.M3HeroActions
import com.bontecou.syncmd.ui.theme.M3HeroCount
import com.bontecou.syncmd.ui.theme.M3HeroHeadline
import com.bontecou.syncmd.ui.theme.M3HeroKicker
import com.bontecou.syncmd.ui.theme.M3HeroMeta
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3LeadingLetter
import com.bontecou.syncmd.ui.theme.M3LeadingTile
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3ListRow
import com.bontecou.syncmd.ui.theme.M3MenuItem
import com.bontecou.syncmd.ui.theme.M3ModalSheet
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3OutlinedCard
import com.bontecou.syncmd.ui.theme.M3ProgressTone
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3TextField
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.m3Gutter
import com.bontecou.syncmd.ui.util.FilesAppLauncher
import com.bontecou.syncmd.ui.util.RepositorySelection
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.LocalChangesRequestSupersededException
import com.bontecou.syncmd.ui.viewmodels.PullViewModel
import com.bontecou.syncmd.ui.viewmodels.ResolveLocalChangesViewModel
import com.bontecou.syncmd.ui.viewmodels.VaultSyncViewModel

/**
 * Vault › Changes (Material 3 boards 02 Changes, 22 Syncing, 25 Push rejected, 27 dark).
 *
 * The state hero first (count, remote meta "· fetched 2m ago", Commit & push / Pull), then the
 * changed files with their +/− line counts and the latest commit. While a sync runs the hero turns
 * into the named operation with real transfer progress, Cancel and the Steps list; a rejected push
 * shows inline with the overlap analysis and three ways forward.
 */
@Composable
fun VaultScreen(
    repositoryPath: String,
    showDebugInfo: Boolean = false,
    onOpenGit: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenDiff: (String) -> Unit = {},
    onNavigateBack: () -> Unit,
    repositoryName: String? = null,
    repositoryOwner: String? = null,
    remoteUrl: String? = null,
    onOpenConnection: () -> Unit = {},
    onEditAuthor: () -> Unit = {},
    onMoveRepository: () -> Unit = {},
    onOpenRemoteMerge: () -> Unit = {},
    viewModel: PullViewModel = hiltViewModel(),
    /** Created only when the Resolve sheet opens, so non-Hilt hosts can render the Vault. */
    resolveViewModel: ResolveLocalChangesViewModel? = null,
    /** Bottom navigation between Changes, Files, History and Git; off when a rail is shown instead. */
    showNavigation: Boolean = true,
    onSelectTab: (VaultTab) -> Unit = {},
    /** The file shown in a list-detail pane beside the list, highlighted in Changes. */
    selectedFile: String? = null,
    /** On tablets the vault list sits beside the vault, so Back can be hidden. */
    showBack: Boolean = true,
    /** Called when the commit sheet closes, so a diff pane beside the list can re-read its file. */
    onCommitSheetClosed: () -> Unit = {},
    /** More opens Vault settings. Hosts without that route get the overflow menu instead. */
    onOpenVaultSettings: (() -> Unit)? = null,
    /** Push rejected › Review first: the incoming commits, reviewed before merging. */
    onReviewIncoming: () -> Unit = onOpenRemoteMerge,
    /** App-wide syncs with progress; null in hosts without Hilt, which fall back to [viewModel]'s pull. */
    syncViewModel: VaultSyncViewModel? = rememberVaultSyncViewModel(),
) {
    var committing by rememberSaveable(repositoryPath) { mutableStateOf(false) }
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(repositoryPath) {
        viewModel.setRepositoryPath(repositoryPath)
        syncViewModel?.refresh()
    }
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) {
            viewModel.setRepositoryPath(repositoryPath)
            syncViewModel?.setRepositoryPath(repositoryPath)
        }
    }

    val selection by viewModel.selection.collectAsState()
    val requestContext = selection?.takeIf { it.path == repositoryPath }
    val loadedStatus by viewModel.status.collectAsState()
    val status = loadedStatus?.takeIf { requestContext != null && it.repoPath == repositoryPath }
    val isLoading by viewModel.isLoading.collectAsState()
    // "Commit & push" launcher shortcut: open this vault's commit sheet once it has loaded.
    val shortcutCommit by com.bontecou.syncmd.automation.VaultShortcutRequests.pendingCommit.collectAsState()
    LaunchedEffect(shortcutCommit, requestContext != null) {
        if (shortcutCommit == repositoryPath && requestContext != null &&
            com.bontecou.syncmd.automation.VaultShortcutRequests.consumeCommit(repositoryPath)) committing = true
    }
    val loadedError = viewModel.errorText.collectAsState().value?.asString()
    val errorMessage = loadedError.takeIf { requestContext != null }
    val loadedAttention by viewModel.attention.collectAsState()
    val attention = loadedAttention.takeIf { requestContext != null }
    // Upstream counts are only shown when a plan for this repository is already loaded.
    val loadedPlan by viewModel.pullPlan.collectAsState()
    val plan = loadedPlan?.takeIf { requestContext != null && it.repoPath == repositoryPath }
    val loadedBlock by viewModel.localChangesBlock.collectAsState()
    val syncPath = syncViewModel?.repositoryPath?.collectAsState()?.value
    val overview = syncViewModel?.overview?.collectAsState()?.value?.takeIf { syncPath == repositoryPath }
    val run = syncViewModel?.run?.collectAsState()?.value?.takeIf { it.path == repositoryPath }
    val localChangesBlock = (loadedBlock || run?.result == VaultSyncResult.LocalChangesBlocked) && requestContext != null
    val context = LocalContext.current
    val openInFilesFailed = stringResource(R.string.vault_open_in_files_failed)
    val copiedLabel = stringResource(R.string.vault_url_copied)
    var resolving by rememberSaveable(repositoryPath) { mutableStateOf(false) }

    // A finished sync changed the repository: re-read status and the overview.
    LaunchedEffect(run?.result) { if (run?.result != null) { viewModel.loadStatus(); syncViewModel?.refresh() } }
    // Every fresh status read refreshes the per-file counts and the remote meta.
    LaunchedEffect(status) { if (status != null) syncViewModel?.refresh() }

    // Catalog identity follows selection; only an unregistered working copy uses its folder name.
    val repoDisplayName = repositoryName?.takeIf { it.isNotBlank() }
        ?: repositoryPath.substringAfterLast("/").ifBlank { stringResource(R.string.vault_repository_fallback) }
    val pull: () -> Unit = {
        if (syncViewModel != null) { viewModel.clearError(requestContext); syncViewModel.pull() }
        else requestContext?.let(viewModel::executePull)
    }

    VaultContent(
        ui = VaultUi(
            hasRepository = repositoryPath.isNotBlank(),
            repositoryPath = repositoryPath,
            displayName = repoDisplayName,
            owner = repositoryOwner.orEmpty(),
            status = status,
            isLoading = isLoading,
            canPull = !isLoading && requestContext != null && run?.running != true,
            errorMessage = errorMessage,
            attention = attention,
            localChangesBlock = localChangesBlock,
            canCopyUrl = !remoteUrl.isNullOrBlank(),
            ahead = overview?.ahead ?: plan?.commitsAhead,
            behind = overview?.behind ?: plan?.commitsBehind,
            showDebugInfo = showDebugInfo,
            overview = overview,
            run = run,
        ),
        onNavigateBack = onNavigateBack,
        onOpenGit = onOpenGit,
        onOpenDiff = onOpenDiff,
        onPull = pull,
        onDismissError = { viewModel.clearError(requestContext) },
        onDismissAttention = { viewModel.clearAttention(requestContext) },
        onOpenInFiles = {
            val opened = FilesAppLauncher.openFolder(context, repositoryPath)
            if (!opened) Toast.makeText(context, openInFilesFailed, Toast.LENGTH_SHORT).show()
        },
        onPush = { if (syncViewModel != null) syncViewModel.push() else onOpenGit() },
        onResolveLocalChanges = { resolving = true },
        onDismissLocalChanges = { viewModel.clearLocalChangesBlock(requestContext); syncViewModel?.dismiss() },
        onOpenConnection = onOpenConnection,
        onEditAuthor = onEditAuthor,
        onMoveRepository = onMoveRepository,
        onOpenVaultSettings = onOpenVaultSettings,
        onCopyUrl = {
            remoteUrl?.let(::shareableRemoteUrl)?.let { url ->
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(url, url))
                // Android 13+ shows its own clipboard confirmation.
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
            }
        },
        showNavigation = showNavigation,
        onSelectTab = onSelectTab,
        selectedFile = selectedFile,
        showBack = showBack,
        onCommit = { committing = true },
        onCancelSync = { syncViewModel?.cancel() },
        onDismissSync = { syncViewModel?.dismiss() },
        onPullThenPush = { syncViewModel?.pullThenPush() },
        onReviewFirst = { syncViewModel?.dismiss(); onReviewIncoming() },
        onPushToBranch = { syncViewModel?.pushToBranch() },
    )
    if (committing && requestContext != null) {
        com.bontecou.syncmd.ui.components.CommitSheet(repositoryPath, repoDisplayName,
            onDismiss = { committing = false; viewModel.loadStatus(); syncViewModel?.refresh(); onCommitSheetClosed() },
            syncViewModel = syncViewModel)
    }
    if (resolving && requestContext != null) ResolveLocalChangesSheet(requestContext, resolveViewModel ?: hiltViewModel(),
        onDismiss = { resolving = false },
        onCommitted = {
            resolving = false; viewModel.clearLocalChangesBlock(requestContext); syncViewModel?.dismiss()
            viewModel.loadStatus(); onOpenRemoteMerge()
        })
}

/** The app's [VaultSyncViewModel] in a Hilt activity; null in plain test hosts. */
@Composable
fun rememberVaultSyncViewModel(): VaultSyncViewModel? {
    var context = LocalContext.current
    while (context !is android.app.Activity && context is android.content.ContextWrapper) context = context.baseContext
    return if (context is dagger.hilt.internal.GeneratedComponentManagerHolder) hiltViewModel() else null
}

/** HTTP(S) userinfo can hold legacy tokens; only the address itself may reach the clipboard. */
internal fun shareableRemoteUrl(remote: String): String {
    val trimmed = remote.trim()
    val match = Regex("^(https?://)[^/@]*@(.*)$", RegexOption.IGNORE_CASE).matchEntire(trimmed) ?: return trimmed
    return match.groupValues[1] + match.groupValues[2]
}

@Composable
private fun ResolveLocalChangesSheet(selection: RepositorySelection, model: ResolveLocalChangesViewModel, onDismiss: () -> Unit, onCommitted: () -> Unit) {
    SideEffect { model.bind(selection) }
    DisposableEffect(model, selection) { onDispose { model.bind(null) } }
    val busy by model.busy.collectAsState()
    val failure by model.failure.collectAsState()
    val defaultMessage = stringResource(R.string.resolve_local_default_message)
    var message by rememberSaveable(selection.path) { mutableStateOf(defaultMessage) }
    M3ModalSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag("resolve-local-sheet"),
        title = stringResource(R.string.resolve_local_title),
        footer = {
            M3Button(stringResource(R.string.resolve_local_commit_merge), { model.commitAll(selection, message, onCommitted) },
                Modifier.fillMaxWidth().testTag("resolve-local-confirm"), size = M3ButtonSize.Hero, enabled = !busy && message.isNotBlank(), loading = busy)
            M3TextButton(stringResource(R.string.publication_cancel), onDismiss, Modifier.fillMaxWidth(), enabled = !busy)
        },
    ) {
        Text(stringResource(R.string.resolve_local_body), style = M3Type.bodyMedium, color = M3.colors.onVar, modifier = Modifier.padding(top = 12.dp))
        M3TextField(message, { message = it }, stringResource(R.string.resolve_local_message_label),
            Modifier.padding(top = 16.dp).testTag("resolve-local-message"), enabled = !busy)
        if (busy) M3IndeterminateProgressBar(Modifier.padding(top = 16.dp), description = stringResource(R.string.resolve_local_committing))
        failure?.let { M3Note(resolveFailureText(it), Modifier.padding(top = 12.dp).testTag("resolve-local-error"), tone = M3NoteTone.Warn) }
    }
}

@Composable
private fun resolveFailureText(failure: Exception): String = when (failure) {
    is LocalChangesRequestSupersededException -> stringResource(R.string.resolve_local_superseded)
    else -> failure.message ?: stringResource(R.string.ui_repository_operation_failed_f1152917)
}

// ─── Stateless content ──────────────────────────────────────────────────────

private data class VaultUi(
    val hasRepository: Boolean,
    val repositoryPath: String,
    val displayName: String,
    val owner: String,
    val status: RepositoryStatus?,
    val isLoading: Boolean,
    val canPull: Boolean,
    val errorMessage: String?,
    val attention: String?,
    val localChangesBlock: Boolean = false,
    val canCopyUrl: Boolean = false,
    /** Upstream commit counts; null when the remote has not been checked. */
    val ahead: Int?,
    val behind: Int?,
    val showDebugInfo: Boolean,
    val overview: VaultOverview? = null,
    val run: VaultSyncRun? = null,
)

/** Which story the hero tells. */
private enum class VaultHeroKind { READING, UNREADABLE, CONFLICT, DIRTY, AHEAD, BEHIND, DIVERGED, EMPTY, IN_SYNC }

private fun VaultUi.heroKind(): VaultHeroKind {
    val s = status ?: return if (isLoading || errorMessage == null) VaultHeroKind.READING else VaultHeroKind.UNREADABLE
    val ahead = ahead ?: 0
    val behind = behind ?: 0
    return when {
        s.allChanges.any { it.isConflicted } -> VaultHeroKind.CONFLICT
        s.totalChanges > 0 -> VaultHeroKind.DIRTY
        ahead > 0 && behind > 0 -> VaultHeroKind.DIVERGED
        behind > 0 -> VaultHeroKind.BEHIND
        ahead > 0 -> VaultHeroKind.AHEAD
        s.headCommit == null -> VaultHeroKind.EMPTY
        else -> VaultHeroKind.IN_SYNC
    }
}

@Composable
private fun VaultContent(
    ui: VaultUi,
    onNavigateBack: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenDiff: (String) -> Unit,
    onPull: () -> Unit,
    onDismissError: () -> Unit,
    onDismissAttention: () -> Unit,
    onOpenInFiles: () -> Unit,
    onPush: () -> Unit = onOpenGit,
    onResolveLocalChanges: () -> Unit = {},
    onDismissLocalChanges: () -> Unit = {},
    onOpenConnection: () -> Unit = {},
    onEditAuthor: () -> Unit = {},
    onMoveRepository: () -> Unit = {},
    onOpenVaultSettings: (() -> Unit)? = null,
    onCopyUrl: () -> Unit = {},
    showNavigation: Boolean = false,
    onSelectTab: (VaultTab) -> Unit = {},
    selectedFile: String? = null,
    showBack: Boolean = true,
    onCommit: (() -> Unit)? = null,
    onCancelSync: () -> Unit = {},
    onDismissSync: () -> Unit = {},
    onPullThenPush: () -> Unit = {},
    onReviewFirst: () -> Unit = {},
    onPushToBranch: () -> Unit = {},
) {
    val c = M3.colors
    val status = ui.status
    val run = ui.run
    val rejected = run?.result as? VaultSyncResult.Rejected

    Column(Modifier.fillMaxSize().background(c.surface)) {
        VaultTopBar(ui, if (showBack) onNavigateBack else null, onPull, onOpenVaultSettings,
            onOpenInFiles, onCopyUrl, onOpenConnection, onEditAuthor, onMoveRepository)

        // A plain scrolling column (not lazy): the vault page is a handful of cards, and every
        // card (including the latest commit at the bottom) stays in the semantics tree.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .testTag(if (!ui.isLoading && status != null) "vault-ready" else "vault-loading")
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            if (!ui.hasRepository) {
                VaultNoRepository()
            } else {
                VaultIdentity(ui.displayName, ui.owner)
                when {
                    run != null && run.running -> VaultSyncingSection(run, onCancelSync)
                    rejected != null -> VaultPushRejectedSection(rejected.divergence, rejected.gitLine,
                        onPullThenPush = onPullThenPush, onReviewFirst = onReviewFirst, onPushToBranch = onPushToBranch)
                    else -> {
                        VaultStateHero(ui, onOpenGit, onPull, onCommit ?: onOpenGit, onPush, onPullThenPush)
                        VaultNotices(ui, onDismissError, onDismissAttention, onResolveLocalChanges, onDismissLocalChanges, onDismissSync)
                        if (status != null) {
                            VaultChanges(status.allChanges, ui.overview?.lineCounts.orEmpty(), onOpenDiff, selectedFile)
                            VaultLatestCommit(status, ui.overview, onOpen = { onSelectTab(VaultTab.HISTORY) })
                            if (ui.showDebugInfo) VaultDebugInfo(ui.repositoryPath, status)
                        }
                    }
                }
            }
        }
        if (showNavigation && ui.hasRepository) {
            VaultNavigationBar(VaultTab.CHANGES, onSelectTab, changes = status?.totalChanges)
        }
    }
}

// ─── Top bar ────────────────────────────────────────────────────────────────

/** Back, the vault name over "branch · owner/name", Check for changes and More (Vault settings). */
@Composable
private fun VaultTopBar(
    ui: VaultUi,
    onBack: (() -> Unit)?,
    onCheck: () -> Unit,
    onOpenVaultSettings: (() -> Unit)?,
    onOpenInFiles: () -> Unit,
    onCopyUrl: () -> Unit,
    onOpenConnection: () -> Unit,
    onEditAuthor: () -> Unit,
    onMoveRepository: () -> Unit,
) {
    val c = M3.colors
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(c.surface).statusBarsPadding().heightIn(min = 64.dp)
            .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) M3IconButton(R.drawable.ic_arrow_back, stringResource(R.string.publication_back), onBack, tint = c.on)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(ui.displayName, style = M3Type.title, color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("vault-title").semantics { heading() })
            val sub = listOfNotNull(ui.status?.currentBranch, ui.owner.takeIf { it.isNotBlank() }?.let { "$it/${ui.displayName}" })
            if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = M3Type.meta, color = c.onVar, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("vault-branch-caption"))
        }
        if (ui.hasRepository) {
            M3IconButton(R.drawable.ic_m3_sync, stringResource(R.string.vault_m3_check_for_changes), onCheck,
                Modifier.testTag("vault-check"), enabled = ui.canPull)
            if (onOpenVaultSettings != null) {
                M3IconButton(R.drawable.ic_m3_more_vert, stringResource(R.string.vault_m3_vault_settings), onOpenVaultSettings,
                    Modifier.testTag("vault-settings"))
            } else Box {
                M3IconButton(R.drawable.ic_m3_more_vert, stringResource(R.string.tide_vault_more), { menuOpen = true })
                M3DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    M3MenuItem(stringResource(R.string.ui_open_in_files_e1d250d0), { menuOpen = false; onOpenInFiles() }, icon = R.drawable.ic_m3_open_in_new)
                    if (ui.canCopyUrl) M3MenuItem(stringResource(R.string.vault_copy_url), { menuOpen = false; onCopyUrl() },
                        Modifier.testTag("vault-copy-url"), icon = R.drawable.ic_m3_content_copy)
                    M3MenuItem(stringResource(R.string.ui_repository_connection_1188001b), { menuOpen = false; onOpenConnection() },
                        Modifier.testTag("vault-connection"), icon = R.drawable.ic_m3_link)
                    M3MenuItem(stringResource(R.string.ui_commit_author_e9376b3a), { menuOpen = false; onEditAuthor() },
                        Modifier.testTag("vault-author"), icon = R.drawable.ic_m3_person)
                    M3MenuItem(stringResource(R.string.ui_move_repository_1c4a6083), { menuOpen = false; onMoveRepository() },
                        Modifier.testTag("vault-move"), icon = R.drawable.ic_m3_folder)
                }
            }
        }
    }
}

/** Catalog identity stays addressable as nodes separate from the visible title. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun VaultIdentity(name: String, owner: String) {
    Box(Modifier.testTag("vault-name").semantics { text = AnnotatedString(name); invisibleToUser() })
    if (owner.isNotBlank()) Box(Modifier.testTag("vault-owner").semantics { text = AnnotatedString(owner); invisibleToUser() })
}

// ─── State hero ─────────────────────────────────────────────────────────────

@Composable
private fun VaultStateHero(ui: VaultUi, onOpenGit: () -> Unit, onPull: () -> Unit, onCommit: () -> Unit, onPush: () -> Unit, onPullThenPush: () -> Unit) {
    val c = M3.colors
    val kind = ui.heroKind()
    val status = ui.status
    val changes = status?.totalChanges ?: 0
    val busy = ui.isLoading || ui.run?.running == true
    val commitPush = stringResource(R.string.vault_m3_commit_push)
    val pull = stringResource(R.string.vault_m3_pull)
    M3Hero(Modifier.m3Gutter().padding(top = 8.dp).testTag("vault-hero")) {
        when (kind) {
            VaultHeroKind.READING -> {
                M3HeroHeadline(stringResource(R.string.tide_vault_reading), style = M3Type.headlineSmall)
                M3IndeterminateProgressBar(Modifier.padding(top = 16.dp), tone = M3ProgressTone.Hero)
            }
            VaultHeroKind.UNREADABLE -> {
                M3HeroKicker(stringResource(R.string.tide_vault_eyebrow_unreadable), color = c.invDanger)
                M3HeroHeadline(stringResource(R.string.tide_vault_headline_unreadable), Modifier.padding(top = 6.dp), style = M3Type.headlineSmall)
            }
            VaultHeroKind.CONFLICT -> {
                val count = status!!.allChanges.count { it.isConflicted }
                M3HeroKicker(stringResource(R.string.tide_vault_eyebrow_conflict), color = c.invDanger)
                M3HeroCount("$count", pluralStringResource(R.plurals.tide_vault_conflicts_to_resolve, count))
                M3HeroMeta(stringResource(R.string.tide_vault_conflict_detail), topPadding = 2.dp)
                HeroActions {
                    M3Button(stringResource(R.string.tide_vault_resolve), onOpenGit, Modifier.weight(1f).testTag("vault-resolve"),
                        M3ButtonStyle.OnHero, M3ButtonSize.Large, icon = R.drawable.ic_m3_merge, enabled = !busy)
                }
            }
            VaultHeroKind.DIRTY -> {
                M3HeroKicker(stringResource(R.string.tide_vault_eyebrow_ready_to_commit))
                M3HeroCount("$changes", pluralStringResource(R.plurals.tide_vault_changes_on_phone, changes))
                M3HeroMeta(remoteMeta(ui), Modifier.testTag("vault-remote-meta"), topPadding = 2.dp)
                HeroActions {
                    M3Button(commitPush, onCommit, Modifier.weight(1f).testTag("vault-commit"), M3ButtonStyle.OnHero, M3ButtonSize.Large,
                        icon = R.drawable.ic_m3_arrow_upward, enabled = !busy)
                    M3Button(pull, onPull, Modifier.testTag("vault-pull"), M3ButtonStyle.OutlinedOnHero, M3ButtonSize.Large,
                        icon = R.drawable.ic_m3_arrow_downward, enabled = ui.canPull)
                }
            }
            VaultHeroKind.AHEAD -> {
                M3HeroKicker(stringResource(R.string.tide_vault_eyebrow_ready_to_push))
                M3HeroCount("${ui.ahead ?: 0}", stringResource(R.string.tide_vault_sub_ahead))
                M3HeroMeta(remoteMeta(ui), Modifier.testTag("vault-remote-meta"), topPadding = 2.dp)
                HeroActions {
                    M3Button(stringResource(R.string.vault_m3_push), onPush, Modifier.weight(1f).testTag("vault-commit"), M3ButtonStyle.OnHero,
                        M3ButtonSize.Large, icon = R.drawable.ic_m3_arrow_upward, trailingMeta = "↑${ui.ahead ?: 0}", enabled = !busy)
                    M3Button(pull, onPull, Modifier.testTag("vault-pull"), M3ButtonStyle.OutlinedOnHero, M3ButtonSize.Large,
                        icon = R.drawable.ic_m3_arrow_downward, enabled = ui.canPull)
                }
            }
            VaultHeroKind.BEHIND -> {
                M3HeroKicker(stringResource(R.string.tide_vault_eyebrow_new_on_remote), color = c.invBehind)
                M3HeroHeadline(stringResource(R.string.tide_vault_headline_behind, ui.behind ?: 0), Modifier.padding(top = 4.dp))
                M3HeroMeta(remoteMeta(ui), Modifier.testTag("vault-remote-meta"))
                HeroActions {
                    M3Button(pull, onPull, Modifier.weight(1f).testTag("vault-pull"), M3ButtonStyle.OnHero, M3ButtonSize.Large,
                        icon = R.drawable.ic_m3_arrow_downward, trailingMeta = "↓${ui.behind ?: 0}", enabled = ui.canPull)
                }
            }
            VaultHeroKind.DIVERGED -> {
                M3HeroKicker(stringResource(R.string.tide_vault_eyebrow_both_moved), color = c.invDanger)
                M3HeroHeadline(stringResource(R.string.tide_vault_moved_on), Modifier.padding(top = 6.dp), style = M3Type.headlineSmall)
                M3HeroMeta("${stringResource(R.string.tide_vault_yours, ui.ahead ?: 0)} · ${stringResource(R.string.tide_vault_from_remote, ui.behind ?: 0)}")
                HeroActions {
                    M3Button(stringResource(R.string.vault_m3_pull_then_push), onPullThenPush, Modifier.weight(1f).testTag("vault-pull-then-push"),
                        M3ButtonStyle.OnHero, M3ButtonSize.Large, enabled = !busy)
                    M3Button(pull, onPull, Modifier.testTag("vault-pull"), M3ButtonStyle.OutlinedOnHero, M3ButtonSize.Large, enabled = ui.canPull)
                }
            }
            VaultHeroKind.EMPTY -> {
                M3HeroKicker(stringResource(R.string.tide_vault_eyebrow_empty), color = c.invMeta)
                M3HeroHeadline(stringResource(R.string.repository_no_commits), Modifier.padding(top = 4.dp))
                M3HeroMeta(stringResource(R.string.tide_vault_sub_empty))
                HeroActions {
                    M3Button(stringResource(R.string.vault_m3_check_for_changes), onPull, Modifier.weight(1f).testTag("vault-pull"),
                        M3ButtonStyle.OutlinedOnHero, M3ButtonSize.Large, icon = R.drawable.ic_m3_sync, enabled = ui.canPull)
                }
            }
            VaultHeroKind.IN_SYNC -> {
                M3HeroKicker(stringResource(R.string.tide_vault_eyebrow_in_sync), color = c.invOk)
                M3HeroHeadline(stringResource(R.string.tide_vault_sub_in_sync), Modifier.padding(top = 4.dp))
                M3HeroMeta(remoteMeta(ui), Modifier.testTag("vault-remote-meta"))
                HeroActions {
                    M3Button(stringResource(R.string.vault_m3_check_for_changes), onPull, Modifier.weight(1f).testTag("vault-pull"),
                        M3ButtonStyle.OnHero, M3ButtonSize.Large, icon = R.drawable.ic_m3_sync, enabled = ui.canPull)
                }
            }
        }
    }
}

/** Hero actions; narrow panes (foldable inner display, split screen) give the row the whole width. */
@Composable
private fun HeroActions(content: @Composable RowScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp) Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
        } else M3HeroActions(content = content)
    }
}

/** "Remote has no new commits · fetched 2m ago", from the last fetch's tracking refs. */
@Composable
private fun remoteMeta(ui: VaultUi): String {
    val overview = ui.overview
    val behind = ui.behind
    val remote = when {
        overview != null && overview.upstream == null -> stringResource(R.string.vault_m3_no_upstream)
        behind == null -> stringResource(R.string.vault_m3_remote_not_checked)
        behind > 0 -> pluralStringResource(R.plurals.vault_m3_remote_new, behind, behind)
        else -> stringResource(R.string.vault_m3_remote_no_new)
    }
    val fetched = (overview?.lastFetchAt ?: ui.status?.lastFetchAt)?.let { stringResource(R.string.vault_m3_fetched_ago, vaultAgo(it)) }
    return listOfNotNull(remote, fetched).joinToString(" · ")
}

// ─── Notices ────────────────────────────────────────────────────────────────

@Composable
private fun VaultNotices(
    ui: VaultUi,
    onDismissError: () -> Unit,
    onDismissAttention: () -> Unit,
    onResolveLocalChanges: () -> Unit,
    onDismissLocalChanges: () -> Unit,
    onDismissSync: () -> Unit,
) {
    val dismiss = stringResource(R.string.ui_dismiss_48845bff)
    val notice = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
    if (ui.errorMessage != null) M3Note(ui.errorMessage, notice.testTag("vault-pull-error"), tone = M3NoteTone.Warn, action = {
        M3Button(dismiss, onDismissError, Modifier.testTag("vault-pull-error-dismiss"), M3ButtonStyle.OutlinedContent, M3ButtonSize.XSmall)
    })
    when (val result = ui.run?.result) {
        is VaultSyncResult.Failed -> M3Note(result.message.asString(), notice.testTag("vault-sync-error"), tone = M3NoteTone.Warn,
            title = stringResource(R.string.vault_m3_sync_failed), action = {
                M3Button(dismiss, onDismissSync, Modifier.testTag("vault-sync-dismiss"), M3ButtonStyle.OutlinedContent, M3ButtonSize.XSmall)
            })
        is VaultSyncResult.Done -> M3Note(result.message.asString(), notice.testTag("vault-sync-done"), tone = M3NoteTone.Ok, action = {
            M3Button(dismiss, onDismissSync, Modifier.testTag("vault-sync-dismiss"), M3ButtonStyle.OutlinedContent, M3ButtonSize.XSmall)
        })
        VaultSyncResult.Cancelled -> M3Note(stringResource(R.string.vault_m3_sync_cancelled), notice.testTag("vault-sync-cancelled"), tone = M3NoteTone.Muted, action = {
            M3Button(dismiss, onDismissSync, Modifier.testTag("vault-sync-dismiss"), M3ButtonStyle.OutlinedContent, M3ButtonSize.XSmall)
        })
        else -> Unit
    }
    if (ui.localChangesBlock) M3Note(stringResource(R.string.resolve_local_title), notice.testTag("vault-local-changes-block"), tone = M3NoteTone.Ahead, action = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            M3Button(stringResource(R.string.resolve_local_action), onResolveLocalChanges, style = M3ButtonStyle.Filled, size = M3ButtonSize.XSmall)
            M3Button(dismiss, onDismissLocalChanges, style = M3ButtonStyle.Text, size = M3ButtonSize.XSmall)
        }
    })
    if (ui.attention != null) M3Note(ui.attention, notice, tone = M3NoteTone.Ahead, action = {
        M3Button(dismiss, onDismissAttention, style = M3ButtonStyle.Text, size = M3ButtonSize.XSmall)
    })
}

@Composable
private fun VaultNoRepository() {
    val c = M3.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 120.dp, start = 28.dp, end = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        M3LeadingTile(R.drawable.ic_m3_branch, container = c.cHighest, content = c.onVar, size = 56.dp)
        Text(stringResource(R.string.ui_no_repository_555da78f), style = M3Type.headlineSmall, color = c.on)
        Text(stringResource(R.string.ui_go_back_and_select_a_repository_c3eedd39), style = M3Type.bodyMedium, color = c.onVar)
    }
}

// ─── Changes ────────────────────────────────────────────────────────────────

@Composable
private fun VaultChanges(changes: List<GitStatusEntry>, counts: Map<String, FileLineCount>, onOpenDiff: (String) -> Unit, selectedFile: String? = null) {
    val c = M3.colors
    Column(Modifier.fillMaxWidth().testTag("vault-changes")) {
        M3SectionLabel(stringResource(R.string.ui_changes_bbd4b6a8), count = changes.size.takeIf { it > 0 }?.toString(),
            trailing = if (changes.isNotEmpty()) stringResource(R.string.vault_m3_tap_for_diff) else null)
        M3ListCard(Modifier.m3Gutter().padding(top = 8.dp)) {
            if (changes.isEmpty()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(stringResource(R.string.tide_vault_no_changes_title), style = M3Type.body, color = c.on)
                    Text(stringResource(R.string.tide_vault_no_changes_body), style = M3Type.bodyMedium, color = c.onVar, modifier = Modifier.padding(top = 2.dp))
                }
            } else changes.sortedBy { it.filePath }.forEachIndexed { index, entry ->
                ChangedFileRow(entry, counts[entry.filePath], divider = index > 0, selected = entry.filePath == selectedFile,
                    onClick = { onOpenDiff(entry.filePath) })
            }
        }
    }
}

/** Letter and tone for a change: A added, M modified, D deleted, ! conflicted. */
internal fun changeBadge(entry: GitStatusEntry): Pair<String, M3Tone> = when {
    entry.isConflicted -> "!" to M3Tone.Warn
    entry.isDeleted -> "D" to M3Tone.Warn
    entry.kind == GitFileStatusKind.UNTRACKED -> "A" to M3Tone.Ok
    else -> "M" to M3Tone.Ahead
}

@Composable
private fun ChangedFileRow(entry: GitStatusEntry, count: FileLineCount?, divider: Boolean, selected: Boolean = false, onClick: () -> Unit = {}) {
    val (letter, tone) = changeBadge(entry)
    val name = entry.filePath.substringAfterLast('/')
    val dir = entry.filePath.substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else "$it/" }
    val stat: (@Composable RowScope.() -> Unit)? = if (count != null && !count.binary && (count.added > 0 || count.removed > 0)) { {
        M3DiffStat(count.added.takeIf { it > 0 }?.let { "+$it" }, count.removed.takeIf { it > 0 }?.let { "−$it" })
    } } else null
    M3ListRow(
        title = name,
        meta = dir,
        titleMaxLines = 1,
        divider = divider,
        selected = selected,
        modifier = Modifier.testTag("vault-change:${entry.filePath}"),
        contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        leading = { M3LeadingLetter(letter, tone) },
        trailing = stat,
        onClick = onClick,
    )
}

// ─── Latest commit ──────────────────────────────────────────────────────────

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun VaultLatestCommit(status: RepositoryStatus, overview: VaultOverview?, onOpen: () -> Unit) {
    val c = M3.colors
    val head = overview?.head?.takeIf { it.hash == status.headCommit }
    M3SectionLabel(stringResource(R.string.vault_m3_latest_commit))
    M3OutlinedCard(Modifier.m3Gutter().padding(top = 8.dp).testTag("vault-latest-commit"), onClick = onOpen) {
        if (head != null) Text(head.message.lineSequence().firstOrNull().orEmpty(), style = M3Type.body, color = c.on, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        val meta = if (head != null) listOf(head.shortHash, head.author, vaultAgo(head.timestamp)).joinToString(" · ")
            else headCommitText(status.headCommit)
        Text(meta, style = M3Type.meta, color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("vault-overview-head"))
    }
    // Branch and recorded sync time stay addressable for catalog checks; the top bar shows the branch.
    val branch = stringResource(R.string.vault_l10n_branch_value, status.currentBranch)
    val sync = stringResource(R.string.vault_l10n_last_sync_value, recordedSyncText(status.lastSyncAt))
    Box(Modifier.testTag("vault-overview-branch").semantics { text = AnnotatedString(branch); invisibleToUser() })
    Box(Modifier.testTag("vault-overview-sync").semantics { text = AnnotatedString(sync); invisibleToUser() })
}

// ─── Debug ──────────────────────────────────────────────────────────────────

@Composable
private fun VaultDebugInfo(path: String, status: RepositoryStatus) {
    M3Group(stringResource(R.string.ui_debug_info_697b4941)) {
        M3ListRow(stringResource(R.string.ui_path_62fa5a5b), meta = path)
        M3ListRow(stringResource(R.string.ui_branch_52656e81), meta = status.currentBranch, divider = true)
        M3ListRow(stringResource(R.string.modified), meta = "${status.modifiedFiles.size}", divider = true)
        M3ListRow(stringResource(R.string.staged), meta = "${status.stagedFiles.size}", divider = true)
        M3ListRow(stringResource(R.string.untracked), meta = "${status.untrackedFiles.size}", divider = true)
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewChanges = listOf(
    GitStatusEntry("daily/2026-11-01.md", GitFileStatusKind.UNTRACKED),
    GitStatusEntry("ideas/voice-memo-pipeline.md", GitFileStatusKind.MODIFIED),
    GitStatusEntry("old-inbox.md", GitFileStatusKind.STAGED, isDeleted = true),
)

private val previewCounts = mapOf(
    "daily/2026-11-01.md" to FileLineCount(42, 0), "ideas/voice-memo-pipeline.md" to FileLineCount(8, 3), "old-inbox.md" to FileLineCount(0, 17),
)

private fun previewStatus(changes: List<GitStatusEntry> = emptyList(), head: String? = "a41c9e2b9c0") = RepositoryStatus(
    repoPath = "/vaults/VoxVault", currentBranch = "main", isClean = changes.isEmpty(),
    modifiedFiles = changes.filter { it.kind == GitFileStatusKind.MODIFIED },
    untrackedFiles = changes.filter { it.kind == GitFileStatusKind.UNTRACKED },
    stagedFiles = changes.filter { it.kind == GitFileStatusKind.STAGED },
    allChanges = changes, headCommit = head, lastSyncAt = 1_762_000_000_000,
)

private fun previewOverview(behind: Int = 0) = VaultOverview(
    branch = "main", upstream = "origin/main", ahead = 0, behind = behind, lastFetchAt = System.currentTimeMillis() - 120_000,
    head = com.bontecou.syncmd.data.models.Commit("a41c9e2b9c0", author = "Obsidian Git on Mac", message = "Capture morning voice notes",
        timestamp = System.currentTimeMillis() - 6 * 3_600_000),
    lineCounts = previewCounts,
)

private fun previewUi(
    status: RepositoryStatus? = previewStatus(),
    isLoading: Boolean = false,
    error: String? = null,
    ahead: Int? = null,
    behind: Int? = null,
    hasRepository: Boolean = true,
    attention: String? = null,
    overview: VaultOverview? = previewOverview(),
) = VaultUi(
    hasRepository = hasRepository, repositoryPath = "/vaults/VoxVault", displayName = "VoxVault", owner = "CodyBontecou",
    status = status, isLoading = isLoading, canPull = !isLoading, errorMessage = error, attention = attention,
    ahead = ahead, behind = behind, showDebugInfo = false, overview = overview,
)

@Composable
private fun VaultPreview(ui: VaultUi, dark: Boolean = false) {
    SyncMdTheme(darkTheme = dark) {
        VaultContent(ui, {}, {}, {}, {}, {}, {}, {}, onOpenVaultSettings = {}, showNavigation = true)
    }
}

@Preview(name = "Vault · changes", heightDp = 915, widthDp = 412) @Composable private fun PreviewVaultDirty() = VaultPreview(previewUi(previewStatus(previewChanges), ahead = 0, behind = 0))
@Preview(name = "Vault · changes dark", heightDp = 915, widthDp = 412) @Composable private fun PreviewVaultDirtyDark() = VaultPreview(previewUi(previewStatus(previewChanges), ahead = 0, behind = 0), dark = true)
@Preview(name = "Vault · in sync", heightDp = 900) @Composable private fun PreviewVaultInSync() = VaultPreview(previewUi(ahead = 0, behind = 0))
@Preview(name = "Vault · ahead", heightDp = 900) @Composable private fun PreviewVaultAhead() = VaultPreview(previewUi(ahead = 2, behind = 0))
@Preview(name = "Vault · behind", heightDp = 900) @Composable private fun PreviewVaultBehind() = VaultPreview(previewUi(ahead = 0, behind = 3, overview = previewOverview(3)))
@Preview(name = "Vault · diverged dark", heightDp = 900) @Composable private fun PreviewVaultDivergedDark() = VaultPreview(previewUi(ahead = 1, behind = 1), dark = true)
@Preview(name = "Vault · conflict", heightDp = 900) @Composable private fun PreviewVaultConflict() = VaultPreview(previewUi(previewStatus(listOf(GitStatusEntry("daily/2026-11-02.md", GitFileStatusKind.MODIFIED, isConflicted = true)))))
@Preview(name = "Vault · reading", heightDp = 700) @Composable private fun PreviewVaultReading() = VaultPreview(previewUi(status = null, isLoading = true))
@Preview(name = "Vault · pull error", heightDp = 1000) @Composable private fun PreviewVaultError() = VaultPreview(previewUi(error = "Owned pull failure", attention = "Pulled with conflicts in 1 file"))
@Preview(name = "Vault · no repository", heightDp = 700) @Composable private fun PreviewVaultNoRepo() = VaultPreview(previewUi(status = null, hasRepository = false), dark = true)

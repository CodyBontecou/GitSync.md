package com.bontecou.syncmd.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.GitFileStatusKind
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.data.models.RepositoryStatus
import com.bontecou.syncmd.ui.components.VaultOpsBanner
import com.bontecou.syncmd.ui.components.VaultOpsButton
import com.bontecou.syncmd.ui.components.VaultOpsProgress
import com.bontecou.syncmd.ui.components.VaultOpsSheet
import com.bontecou.syncmd.ui.components.VaultOpsTextButton
import com.bontecou.syncmd.ui.components.headCommitText
import com.bontecou.syncmd.ui.components.recordedSyncText
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.SyncRail
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideAccessory
import com.bontecou.syncmd.ui.theme.TideBanner
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideChevron
import com.bontecou.syncmd.ui.theme.TideCircleButton
import com.bontecou.syncmd.ui.theme.TideDisplay
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TideHero
import com.bontecou.syncmd.ui.theme.TideIcon
import com.bontecou.syncmd.ui.components.VaultNavigationBar
import com.bontecou.syncmd.ui.components.VaultTab
import androidx.compose.ui.semantics.selected
import com.bontecou.syncmd.ui.theme.TidePulse
import com.bontecou.syncmd.ui.theme.TideRow
import com.bontecou.syncmd.ui.theme.TideSection
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideSyncState
import com.bontecou.syncmd.ui.theme.TideTopBar
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.util.FilesAppLauncher
import com.bontecou.syncmd.ui.util.RepositorySelection
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.LocalChangesRequestSupersededException
import com.bontecou.syncmd.ui.viewmodels.PullViewModel
import com.bontecou.syncmd.ui.viewmodels.ResolveLocalChangesViewModel

/**
 * Vault screen — Tide port of iOS VaultView.
 *
 * State first, git second: a mono owner line and the vault name as a display
 * title, then the dark state hero (eyebrow, plain-language headline, SyncRail,
 * the one right action), then files, changes and the latest commit.
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
) {
    var committing by rememberSaveable(repositoryPath) { mutableStateOf(false) }
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(repositoryPath) { viewModel.setRepositoryPath(repositoryPath) }
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) viewModel.setRepositoryPath(repositoryPath)
    }

    val selection by viewModel.selection.collectAsState()
    val requestContext = selection?.takeIf { it.path == repositoryPath }
    val loadedStatus by viewModel.status.collectAsState()
    val status = loadedStatus?.takeIf { requestContext != null && it.repoPath == repositoryPath }
    val isLoading by viewModel.isLoading.collectAsState()
    val loadedError = viewModel.errorText.collectAsState().value?.asString()
    val errorMessage = loadedError.takeIf { requestContext != null }
    val loadedAttention by viewModel.attention.collectAsState()
    val attention = loadedAttention.takeIf { requestContext != null }
    // Upstream counts are only shown when a plan for this repository is already loaded.
    val loadedPlan by viewModel.pullPlan.collectAsState()
    val plan = loadedPlan?.takeIf { requestContext != null && it.repoPath == repositoryPath }
    val loadedBlock by viewModel.localChangesBlock.collectAsState()
    val localChangesBlock = loadedBlock && requestContext != null
    val context = LocalContext.current
    val openInFilesFailed = stringResource(R.string.vault_open_in_files_failed)
    val copiedLabel = stringResource(R.string.vault_url_copied)
    var resolving by rememberSaveable(repositoryPath) { mutableStateOf(false) }

    // Catalog identity follows selection; only an unregistered working copy uses its folder name.
    val repoDisplayName = repositoryName?.takeIf { it.isNotBlank() }
        ?: repositoryPath.substringAfterLast("/").ifBlank { stringResource(R.string.vault_repository_fallback) }

    VaultContent(
        ui = VaultUi(
            hasRepository = repositoryPath.isNotBlank(),
            repositoryPath = repositoryPath,
            displayName = repoDisplayName,
            owner = repositoryOwner.orEmpty(),
            status = status,
            isLoading = isLoading,
            canPull = !isLoading && requestContext != null,
            errorMessage = errorMessage,
            attention = attention,
            localChangesBlock = localChangesBlock,
            canCopyUrl = !remoteUrl.isNullOrBlank(),
            ahead = plan?.commitsAhead,
            behind = plan?.commitsBehind,
            showDebugInfo = showDebugInfo,
        ),
        onNavigateBack = onNavigateBack,
        onOpenGit = onOpenGit,
        onOpenFiles = onOpenFiles,
        onOpenDiff = onOpenDiff,
        onPull = { requestContext?.let(viewModel::executePull) },
        onDismissError = { viewModel.clearError(requestContext) },
        onDismissAttention = { viewModel.clearAttention(requestContext) },
        onResolveLocalChanges = { resolving = true },
        onDismissLocalChanges = { viewModel.clearLocalChangesBlock(requestContext) },
        onOpenConnection = onOpenConnection,
        onEditAuthor = onEditAuthor,
        onMoveRepository = onMoveRepository,
        onCopyUrl = {
            remoteUrl?.let(::shareableRemoteUrl)?.let { url ->
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(url, url))
                // Android 13+ shows its own clipboard confirmation.
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
            }
        },
        onOpenInFiles = {
            val opened = FilesAppLauncher.openFolder(context, repositoryPath)
            if (!opened) Toast.makeText(context, openInFilesFailed, Toast.LENGTH_SHORT).show()
        },
        showNavigation = showNavigation,
        onSelectTab = onSelectTab,
        selectedFile = selectedFile,
        showBack = showBack,
        onCommit = { committing = true },
    )
    if (committing && requestContext != null) {
        com.bontecou.syncmd.ui.components.CommitSheet(repositoryPath, repoDisplayName,
            onDismiss = { committing = false; viewModel.loadStatus(); onCommitSheetClosed() })
    }
    if (resolving && requestContext != null) ResolveLocalChangesSheet(requestContext, resolveViewModel ?: hiltViewModel(),
        onDismiss = { resolving = false },
        onCommitted = { resolving = false; viewModel.clearLocalChangesBlock(requestContext); viewModel.loadStatus(); onOpenRemoteMerge() })
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
    VaultOpsSheet(onDismissRequest = { if (!busy) onDismiss() }, title = stringResource(R.string.resolve_local_title),
        modifier = Modifier.testTag("resolve-local-sheet"),
        confirmButton = { VaultOpsButton(stringResource(R.string.resolve_local_commit_merge), { model.commitAll(selection, message, onCommitted) },
            Modifier.testTag("resolve-local-confirm"), enabled = !busy && message.isNotBlank()) },
        dismissButton = { VaultOpsTextButton(stringResource(R.string.publication_cancel), onDismiss, Modifier.fillMaxWidth(), enabled = !busy) }) {
        Text(stringResource(R.string.resolve_local_body), style = TideType.body)
        OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth().testTag("resolve-local-message"), enabled = !busy,
            label = { Text(stringResource(R.string.resolve_local_message_label)) })
        if (busy) VaultOpsProgress(stringResource(R.string.resolve_local_committing))
        failure?.let { VaultOpsBanner(resolveFailureText(it), Modifier.testTag("resolve-local-error"), danger = true) }
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

/** Same mapping as iOS: uncommitted changes count as "yours to push". */
private fun VaultUi.railState(): TideSyncState {
    val changes = status?.totalChanges ?: 0
    val ahead = ahead ?: 0
    val behind = behind ?: 0
    val local = if (ahead > 0) ahead else changes
    return TideSyncState.of(local, behind)
}

@Composable
private fun VaultContent(
    ui: VaultUi,
    onNavigateBack: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenDiff: (String) -> Unit,
    onPull: () -> Unit,
    onDismissError: () -> Unit,
    onDismissAttention: () -> Unit,
    onOpenInFiles: () -> Unit,
    onResolveLocalChanges: () -> Unit = {},
    onDismissLocalChanges: () -> Unit = {},
    onOpenConnection: () -> Unit = {},
    onEditAuthor: () -> Unit = {},
    onMoveRepository: () -> Unit = {},
    onCopyUrl: () -> Unit = {},
    showNavigation: Boolean = false,
    onSelectTab: (VaultTab) -> Unit = {},
    selectedFile: String? = null,
    showBack: Boolean = true,
    onCommit: (() -> Unit)? = null,
) {
    val c = Tide.colors
    val status = ui.status
    var menuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.paper)) {
        TideTopBar(
            onBack = if (showBack) onNavigateBack else null,
            backLabel = stringResource(R.string.publication_back),
            center = {
                Column {
                    Text(ui.displayName, style = TideType.title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("vault-title"))
                    val sub = listOfNotNull(status?.currentBranch, ui.owner.takeIf { it.isNotBlank() }?.let { "$it/${ui.displayName}" })
                    if (sub.isNotEmpty()) Text(
                        sub.joinToString(" · "),
                        style = TideType.meta,
                        color = c.inkMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("vault-branch-caption"),
                    )
                }
            },
            actions = if (ui.hasRepository) {
                {
                    TideCircleButton(stringResource(R.string.tide_vault_check_for_changes), onPull, Modifier.testTag("vault-check"), enabled = ui.canPull) {
                        TideIcon(R.drawable.ic_m3_sync, tint = c.inkMid)
                    }
                    Box {
                        TideCircleButton(stringResource(R.string.tide_vault_more), { menuOpen = true }) {
                            TideIcon(R.drawable.ic_m3_more_vert, tint = c.inkMid)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_open_in_files_e1d250d0), style = TideType.body) },
                                onClick = { menuOpen = false; onOpenInFiles() },
                            )
                            if (ui.canCopyUrl) DropdownMenuItem(
                                text = { Text(stringResource(R.string.vault_copy_url), style = TideType.body) },
                                onClick = { menuOpen = false; onCopyUrl() },
                                modifier = Modifier.testTag("vault-copy-url"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_repository_connection_1188001b), style = TideType.body) },
                                onClick = { menuOpen = false; onOpenConnection() },
                                modifier = Modifier.testTag("vault-connection"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_commit_author_e9376b3a), style = TideType.body) },
                                onClick = { menuOpen = false; onEditAuthor() },
                                modifier = Modifier.testTag("vault-author"),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_move_repository_1c4a6083), style = TideType.body) },
                                onClick = { menuOpen = false; onMoveRepository() },
                                modifier = Modifier.testTag("vault-move"),
                            )
                        }
                    }
                }
            } else null,
        )

        // A plain scrolling column (not lazy): the vault page is a handful of cards, and every
        // card (including the latest-commit overview at the bottom) stays in the semantics tree.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .testTag(if (!ui.isLoading && status != null) "vault-ready" else "vault-loading")
                .verticalScroll(rememberScrollState())
                .padding(start = Tide.gutter, end = Tide.gutter, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!ui.hasRepository) {
                VaultNoRepository()
            } else {
                VaultTitleBlock(ui.displayName, ui.owner)

                VaultStateHero(
                    ui = ui,
                    onOpenGit = onOpenGit,
                    onPull = onPull,
                    onCommit = onCommit ?: onOpenGit,
                )

                if (ui.errorMessage != null) {
                    VaultErrorCard(ui.errorMessage, onDismissError)
                }
                if (ui.localChangesBlock) {
                    TideBanner(
                        title = stringResource(R.string.resolve_local_title),
                        modifier = Modifier.testTag("vault-local-changes-block"),
                        tone = TideBannerTone.AHEAD,
                        actionTitle = stringResource(R.string.resolve_local_action),
                        onAction = onResolveLocalChanges,
                        onDismiss = onDismissLocalChanges,
                        dismissLabel = stringResource(R.string.ui_dismiss_48845bff),
                    )
                }
                if (ui.attention != null) {
                    TideBanner(
                        title = ui.attention,
                        tone = TideBannerTone.AHEAD,
                        onDismiss = onDismissAttention,
                        dismissLabel = stringResource(R.string.ui_dismiss_48845bff),
                    )
                }

                if (status != null) {
                    val loaded: RepositoryStatus = status
                    VaultChanges(loaded.allChanges, onOpenDiff, selectedFile)
                    VaultLatestCommit(loaded)
                    if (ui.showDebugInfo) VaultDebugInfo(ui.repositoryPath, loaded)
                }
            }
        }
        if (showNavigation && ui.hasRepository) {
            VaultNavigationBar(VaultTab.CHANGES, onSelectTab, changes = status?.totalChanges)
        }
    }
}

// ─── Title ──────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun VaultTitleBlock(name: String, owner: String) {
    val c = Tide.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            // Catalog identity remains addressable as one node, separate from the visible title.
            .testTag("vault-name")
            .semantics { text = AnnotatedString(name); invisibleToUser() },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The visible title and owner live in the top app bar.
        if (owner.isNotBlank()) Box(Modifier.testTag("vault-owner").semantics { text = AnnotatedString(owner) })
    }
}

// ─── State hero ─────────────────────────────────────────────────────────────

@Composable
private fun VaultStateHero(ui: VaultUi, onOpenGit: () -> Unit, onPull: () -> Unit, onCommit: () -> Unit = onOpenGit) {
    val c = Tide.colors
    val kind = ui.heroKind()
    val status = ui.status
    val changes = status?.totalChanges ?: 0
    val rail = ui.railState()
    val syncing = ui.isLoading && status != null

    // Board "22 · Syncing": the hero turns primary-container while an operation runs.
    TideHero(Modifier.testTag("vault-hero"), color = if (syncing) c.primaryContainer else c.heroBg) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            when {
                kind == VaultHeroKind.READING -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.tide_vault_reading), style = TideType.sans(24.sp, FontWeight.SemiBold).copy(letterSpacing = (-0.03).em), color = c.heroText)
                        TidePulse(color = c.heroMeta)
                    }
                    return@Column
                }
                kind == VaultHeroKind.UNREADABLE -> {
                    HeroEyebrow(stringResource(R.string.tide_vault_eyebrow_unreadable), c.heroDanger)
                    HeroSentence(stringResource(R.string.tide_vault_headline_unreadable))
                    return@Column
                }
                syncing -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.tide_vault_syncing), style = TideType.display(28.sp).copy(lineHeight = 34.sp), color = c.onPrimaryContainer)
                        Text(stringResource(R.string.ui_loading_repository_15b79262), style = TideType.sans(14.sp), color = c.onPrimaryContainer)
                    }
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = c.primary,
                        trackColor = c.onPrimaryContainer.copy(alpha = 0.16f),
                    )
                }
                kind == VaultHeroKind.CONFLICT -> {
                    val count = status!!.allChanges.count { it.isConflicted }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HeroEyebrow(stringResource(R.string.tide_vault_eyebrow_conflict), c.heroDanger)
                        HeroCount(count, c.heroDanger)
                        Text(pluralStringResource(R.plurals.tide_vault_conflicts_to_resolve, count), style = TideType.body, color = c.heroText)
                        Text(stringResource(R.string.tide_vault_conflict_detail), style = TideType.mono(11.sp), color = c.heroMeta, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                kind == VaultHeroKind.DIVERGED -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeroEyebrow(stringResource(R.string.tide_vault_eyebrow_both_moved), c.heroDanger)
                        HeroSentence(stringResource(R.string.tide_vault_moved_on))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.tide_vault_yours, maxOf(ui.ahead ?: 0, 1)), style = TideType.mono(11.sp), color = c.heroMeta)
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(R.string.tide_vault_from_remote, ui.behind ?: 0), style = TideType.mono(11.sp), color = c.heroMeta)
                    }
                }
                else -> {
                    val (eyebrow, tint) = when (kind) {
                        VaultHeroKind.DIRTY -> stringResource(R.string.tide_vault_eyebrow_ready_to_commit) to c.heroAhead
                        VaultHeroKind.AHEAD -> stringResource(R.string.tide_vault_eyebrow_ready_to_push) to c.heroAhead
                        VaultHeroKind.BEHIND -> stringResource(R.string.tide_vault_eyebrow_new_on_remote) to c.heroBehind
                        VaultHeroKind.EMPTY -> stringResource(R.string.tide_vault_eyebrow_empty) to c.heroMeta
                        else -> stringResource(R.string.tide_vault_eyebrow_in_sync) to c.heroSynced
                    }
                    HeroEyebrow(eyebrow, tint)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            when (kind) {
                                VaultHeroKind.DIRTY -> {
                                    HeroCount(changes, c.heroText)
                                    Text(pluralStringResource(R.plurals.tide_vault_changes_on_phone, changes), style = TideType.sans(18.sp).copy(lineHeight = 24.sp), color = c.heroText)
                                }
                                else -> {
                                    val (headline, sub) = when (kind) {
                                        VaultHeroKind.AHEAD -> stringResource(R.string.tide_vault_headline_ahead, ui.ahead ?: 0) to stringResource(R.string.tide_vault_sub_ahead)
                                        VaultHeroKind.BEHIND -> stringResource(R.string.tide_vault_headline_behind, ui.behind ?: 0) to stringResource(R.string.tide_vault_sub_behind)
                                        VaultHeroKind.EMPTY -> stringResource(R.string.repository_no_commits) to stringResource(R.string.tide_vault_sub_empty)
                                        else -> stringResource(R.string.tide_vault_headline_in_sync) to stringResource(R.string.tide_vault_sub_in_sync)
                                    }
                                    Text(headline, style = TideType.display(32.sp).copy(lineHeight = 38.sp), color = tint, maxLines = 2)
                                    Text(sub, style = TideType.body, color = c.heroText)
                                }
                            }
                        }
                    }
                    Text(remoteSummary(ui), style = TideType.meta, color = c.heroMeta)
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalHeroTonal provides syncing) {
                VaultHeroActions(ui, kind, onOpenGit, onPull, onCommit)
            }
        }
    }
}

@Composable
private fun remoteSummary(ui: VaultUi): String = when {
    ui.ahead == null || ui.behind == null -> stringResource(R.string.tide_vault_remote_not_checked)
    (ui.behind ?: 0) > 0 -> stringResource(R.string.tide_vault_remote_new)
    else -> stringResource(R.string.tide_vault_remote_current)
}

@Composable
private fun HeroEyebrow(text: String, tint: Color) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
    Text(text, style = TideType.sans(12.sp, FontWeight.SemiBold).copy(letterSpacing = 0.033.em, lineHeight = 16.sp), color = tint)
}

@Composable
private fun HeroSentence(text: String) {
    Text(text, style = TideType.display(24.sp).copy(lineHeight = 30.sp), color = Tide.colors.heroText)
}

@Composable
private fun HeroCount(count: Int, tint: Color) {
    Text("$count", style = TideType.display(57.sp).copy(lineHeight = 64.sp, letterSpacing = (-0.026).em), color = tint)
}

/**
 * The one right action for the state, plus a quiet secondary. Pull keeps its
 * `vault-pull` identity and is disabled (not hidden) while an operation runs.
 */
@Composable
private fun VaultHeroActions(ui: VaultUi, kind: VaultHeroKind, onOpenGit: () -> Unit, onPull: () -> Unit, onCommit: () -> Unit = onOpenGit) {
    if (kind == VaultHeroKind.READING) return
    val commitPush = stringResource(R.string.ui_commit_push_94e9650a)
    val pull = stringResource(R.string.pull_screen)
    val changes = ui.status?.totalChanges ?: 0
    val busy = ui.isLoading
    // The one right action for the state, plus a quiet secondary.
    val primary: (@Composable RowScope.() -> Unit)? = when (kind) {
        VaultHeroKind.UNREADABLE -> null
        VaultHeroKind.CONFLICT -> { { HeroPrimary(stringResource(R.string.tide_vault_resolve), "⑂", onOpenGit, enabled = !busy) } }
        VaultHeroKind.DIRTY, VaultHeroKind.AHEAD -> { {
            HeroPrimary(commitPush, "↑", if (kind == VaultHeroKind.DIRTY) onCommit else onOpenGit, enabled = !busy,
                badge = changes.takeIf { it > 0 }, tag = "vault-commit")
        } }
        VaultHeroKind.BEHIND, VaultHeroKind.DIVERGED -> { { HeroPrimary(pull, "↓", onPull, enabled = ui.canPull, tag = "vault-pull") } }
        else -> { { HeroPrimary(stringResource(R.string.tide_vault_check_for_changes), "↻", onPull, enabled = ui.canPull, tag = "vault-pull") } }
    }
    val secondary: @Composable (Modifier) -> Unit = { m ->
        if (kind == VaultHeroKind.DIRTY || kind == VaultHeroKind.AHEAD) HeroSecondary(pull, onPull, enabled = ui.canPull, tag = "vault-pull", modifier = m)
        else HeroSecondary(commitPush, onOpenGit, enabled = !busy, modifier = m)
    }
    // Narrow panes (foldable inner display, split screen) stack the two actions so labels never truncate.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 320.dp && primary != null) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), content = primary)
                secondary(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                primary?.invoke(this)
                secondary(Modifier)
            }
        }
    }
}

/** Light pill on the dark hero (heroText fill, heroBg label). */
@Composable
private fun RowScope.HeroPrimary(title: String, glyph: String, onClick: () -> Unit, enabled: Boolean, tag: String? = null, badge: Int? = null) {
    val c = Tide.colors
    val tonal = LocalHeroTonal.current
    val fill = if (tonal) c.primary else c.heroText
    val ink = if (tonal) c.onPrimary else c.heroBg
    val icon = when (glyph) {
        "↑" -> R.drawable.ic_m3_arrow_upward
        "↓" -> R.drawable.ic_m3_arrow_downward
        "↻" -> R.drawable.ic_m3_sync
        else -> R.drawable.ic_m3_branch
    }
    Row(
        Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(fill.copy(alpha = if (enabled) 1f else 0.45f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        TideIcon(icon, tint = ink.copy(alpha = if (enabled) 1f else 0.6f), size = 20.dp)
        Spacer(Modifier.size(8.dp))
        Text(title, style = TideType.sans(15.sp, FontWeight.SemiBold), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (badge != null) {
            Spacer(Modifier.size(8.dp))
            Box(Modifier.background(c.heroAhead, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("$badge", style = TideType.metaStrong, color = Color.White)
            }
        }
    }
}

/** Outline pill on the dark hero. */
@Composable
private fun HeroSecondary(title: String, onClick: () -> Unit, enabled: Boolean, tag: String? = null, modifier: Modifier = Modifier) {
    val c = Tide.colors
    val tonal = LocalHeroTonal.current
    val ink = if (tonal) c.onPrimaryContainer else c.heroText
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .border(1.dp, if (tonal) c.onPrimaryContainer.copy(alpha = 0.3f) else c.heroLine, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = TideType.sans(15.sp, FontWeight.Medium), color = ink.copy(alpha = if (enabled) 1f else 0.45f), maxLines = 1)
    }
}

/** True while the hero is the primary-container "Syncing" card, so its actions use that container's ink. */
private val LocalHeroTonal = androidx.compose.runtime.staticCompositionLocalOf { false }

// ─── Error, empty ───────────────────────────────────────────────────────────

@Composable
private fun VaultErrorCard(message: String, onDismiss: () -> Unit) {
    val c = Tide.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.errorContainer, shape)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TideIcon(R.drawable.ic_m3_info, tint = c.onErrorContainer, size = 20.dp)
        Text(message, style = TideType.sans(14.sp), color = c.onErrorContainer, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onDismiss)
                .testTag("vault-pull-error-dismiss")
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.ui_dismiss_48845bff), style = TideType.sans(14.sp, FontWeight.SemiBold), color = c.onErrorContainer)
        }
    }
}

@Composable
private fun VaultNoRepository() {
    val c = Tide.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 120.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(56.dp).background(c.well, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            TideIcon(R.drawable.ic_m3_branch, tint = c.inkMid)
        }
        TideDisplay(stringResource(R.string.ui_no_repository_555da78f), size = 26.sp)
        Text(stringResource(R.string.ui_go_back_and_select_a_repository_c3eedd39), style = TideType.meta, color = c.inkFaint)
    }
}

// ─── Changes ────────────────────────────────────────────────────────────────

@Composable
private fun VaultChanges(changes: List<GitStatusEntry>, onOpenDiff: (String) -> Unit, selectedFile: String? = null) {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth()) {
        TideSectionLabel(
            stringResource(R.string.ui_changes_bbd4b6a8) + if (changes.isNotEmpty()) " · ${changes.size}" else "",
            Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        )
        if (changes.isEmpty()) {
            TideCard {
                Text(stringResource(R.string.tide_vault_no_changes_title), style = TideType.body, color = c.ink)
                Text(
                    stringResource(R.string.tide_vault_no_changes_body),
                    style = TideType.sans(14.sp),
                    color = c.inkMid,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        } else {
            Column(Modifier.fillMaxWidth().clip(Tide.cardShape).background(c.card)) {
                val sorted = changes.sortedBy { it.filePath }
                sorted.forEachIndexed { index, entry ->
                    if (index > 0) TideHairline()
                    ChangedFileRow(entry, selected = entry.filePath == selectedFile, onClick = { onOpenDiff(entry.filePath) })
                }
            }
        }
    }
}

/** Letter badge colors for a change: A added, M modified, D deleted, ! conflicted. */
@Composable
internal fun changeBadge(entry: GitStatusEntry): Triple<String, Color, Color> {
    val c = Tide.colors
    return when {
        entry.isConflicted -> Triple("!", c.errorContainer, c.onErrorContainer)
        entry.isDeleted -> Triple("D", c.errorContainer, c.onErrorContainer)
        entry.kind == GitFileStatusKind.UNTRACKED -> Triple("A", c.okContainer, c.onOkContainer)
        else -> Triple("M", c.tertiaryContainer, c.onTertiaryContainer)
    }
}

@Composable
private fun ChangedFileRow(entry: GitStatusEntry, selected: Boolean = false, onClick: () -> Unit = {}) {
    val c = Tide.colors
    val label = when {
        entry.isConflicted -> stringResource(R.string.vault_l10n_status_conflict)
        entry.hasStagedChanges && entry.hasUnstagedChanges -> stringResource(R.string.vault_l10n_status_staged_unstaged)
        entry.isDeleted -> stringResource(if (entry.hasStagedChanges) R.string.vault_l10n_status_staged_deletion else R.string.tide_git_status_deleted)
        entry.kind == GitFileStatusKind.STAGED -> stringResource(R.string.vault_l10n_status_staged)
        entry.kind == GitFileStatusKind.UNTRACKED -> stringResource(R.string.vault_l10n_status_untracked)
        else -> stringResource(R.string.tide_git_status_modified)
    }
    val (glyph, fill, ink) = changeBadge(entry)
    val name = entry.filePath.substringAfterLast('/')
    val dir = entry.filePath.substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else "$it/" }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(if (selected) Modifier.background(c.secondaryContainer) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(fill, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, style = TideType.mono(15.sp, FontWeight.SemiBold), color = ink) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = TideType.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(dir, label).joinToString(" · "), style = TideType.meta, color = c.inkMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ─── Latest commit ──────────────────────────────────────────────────────────

@Composable
private fun VaultLatestCommit(status: RepositoryStatus) {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth()) {
        TideSectionLabel(stringResource(R.string.tide_vault_latest_commit), Modifier.padding(start = 4.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().border(1.dp, c.line, Tide.cardShape).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        headCommitText(status.headCommit),
                        style = TideType.mono(16.sp, FontWeight.Medium),
                        color = c.ink,
                        modifier = Modifier.testTag("vault-overview-head"),
                    )
                    Text(
                        stringResource(R.string.vault_l10n_branch_value, status.currentBranch),
                        style = TideType.meta,
                        color = c.inkMid,
                        modifier = Modifier.testTag("vault-overview-branch"),
                    )
                    Text(
                        stringResource(R.string.vault_l10n_last_sync_value, recordedSyncText(status.lastSyncAt)),
                        style = TideType.meta,
                        color = c.inkMid,
                        modifier = Modifier.testTag("vault-overview-sync"),
                    )
                }
            }
        }
    }
}

// ─── Debug ──────────────────────────────────────────────────────────────────

@Composable
private fun VaultDebugInfo(path: String, status: RepositoryStatus) {
    TideSection(title = stringResource(R.string.ui_debug_info_697b4941)) {
        TideRow(title = stringResource(R.string.ui_path_62fa5a5b), value = path)
        TideHairline()
        TideRow(title = stringResource(R.string.ui_branch_52656e81), value = status.currentBranch)
        TideHairline()
        TideRow(title = stringResource(R.string.modified), value = "${status.modifiedFiles.size}")
        TideHairline()
        TideRow(title = stringResource(R.string.staged), value = "${status.stagedFiles.size}")
        TideHairline()
        TideRow(title = stringResource(R.string.untracked), value = "${status.untrackedFiles.size}")
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewChanges = listOf(
    GitStatusEntry("Daily/2026-11-02.md", GitFileStatusKind.MODIFIED),
    GitStatusEntry("Ideas/tide.md", GitFileStatusKind.UNTRACKED),
    GitStatusEntry("Archive/old.md", GitFileStatusKind.STAGED, isDeleted = true),
)

private fun previewStatus(changes: List<GitStatusEntry> = emptyList(), head: String? = "b32427b9c0") = RepositoryStatus(
    repoPath = "/vaults/notes", currentBranch = "main", isClean = changes.isEmpty(),
    modifiedFiles = changes.filter { it.kind == GitFileStatusKind.MODIFIED },
    untrackedFiles = changes.filter { it.kind == GitFileStatusKind.UNTRACKED },
    stagedFiles = changes.filter { it.kind == GitFileStatusKind.STAGED },
    allChanges = changes, headCommit = head, lastSyncAt = 1_762_000_000_000,
)

private fun previewUi(
    status: RepositoryStatus? = previewStatus(),
    isLoading: Boolean = false,
    error: String? = null,
    ahead: Int? = null,
    behind: Int? = null,
    hasRepository: Boolean = true,
    attention: String? = null,
) = VaultUi(
    hasRepository = hasRepository, repositoryPath = "/vaults/notes", displayName = "Personal notes", owner = "team",
    status = status, isLoading = isLoading, canPull = !isLoading, errorMessage = error, attention = attention,
    ahead = ahead, behind = behind, showDebugInfo = false,
)

@Composable
private fun VaultPreview(ui: VaultUi, dark: Boolean = false) {
    SyncMdTheme(darkTheme = dark) {
        VaultContent(ui, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(name = "Vault · in sync", heightDp = 900) @Composable private fun PreviewVaultInSync() = VaultPreview(previewUi(ahead = 0, behind = 0))
@Preview(name = "Vault · in sync dark", heightDp = 900) @Composable private fun PreviewVaultInSyncDark() = VaultPreview(previewUi(ahead = 0, behind = 0), dark = true)
@Preview(name = "Vault · dirty", heightDp = 1000) @Composable private fun PreviewVaultDirty() = VaultPreview(previewUi(previewStatus(previewChanges)))
@Preview(name = "Vault · dirty dark", heightDp = 1000) @Composable private fun PreviewVaultDirtyDark() = VaultPreview(previewUi(previewStatus(previewChanges)), dark = true)
@Preview(name = "Vault · ahead", heightDp = 900) @Composable private fun PreviewVaultAhead() = VaultPreview(previewUi(ahead = 2, behind = 0))
@Preview(name = "Vault · behind", heightDp = 900) @Composable private fun PreviewVaultBehind() = VaultPreview(previewUi(ahead = 0, behind = 3))
@Preview(name = "Vault · diverged", heightDp = 900) @Composable private fun PreviewVaultDiverged() = VaultPreview(previewUi(ahead = 1, behind = 1))
@Preview(name = "Vault · diverged dark", heightDp = 900) @Composable private fun PreviewVaultDivergedDark() = VaultPreview(previewUi(ahead = 1, behind = 1), dark = true)
@Preview(name = "Vault · conflict", heightDp = 900) @Composable private fun PreviewVaultConflict() = VaultPreview(previewUi(previewStatus(listOf(GitStatusEntry("Daily/2026-11-02.md", GitFileStatusKind.MODIFIED, isConflicted = true)))))
@Preview(name = "Vault · syncing", heightDp = 900) @Composable private fun PreviewVaultSyncing() = VaultPreview(previewUi(isLoading = true))
@Preview(name = "Vault · reading", heightDp = 700) @Composable private fun PreviewVaultReading() = VaultPreview(previewUi(status = null, isLoading = true))
@Preview(name = "Vault · unreadable", heightDp = 700) @Composable private fun PreviewVaultUnreadable() = VaultPreview(previewUi(status = null, error = "Repository not found"))
@Preview(name = "Vault · pull error", heightDp = 1000) @Composable private fun PreviewVaultError() = VaultPreview(previewUi(error = "Owned pull failure", attention = "Pulled with conflicts in 1 file"))
@Preview(name = "Vault · empty repo", heightDp = 900) @Composable private fun PreviewVaultEmptyRepo() = VaultPreview(previewUi(previewStatus(head = null)))
@Preview(name = "Vault · no repository", heightDp = 700) @Composable private fun PreviewVaultNoRepo() = VaultPreview(previewUi(status = null, hasRepository = false), dark = true)

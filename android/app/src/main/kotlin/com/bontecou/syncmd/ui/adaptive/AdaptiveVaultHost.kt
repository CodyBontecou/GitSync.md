package com.bontecou.syncmd.ui.adaptive

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.sync.VaultSyncResult
import com.bontecou.syncmd.ui.components.CommitSheet
import com.bontecou.syncmd.ui.components.RailChrome
import com.bontecou.syncmd.ui.components.VaultPushRejectedSection
import com.bontecou.syncmd.ui.components.VaultRailRow
import com.bontecou.syncmd.ui.components.VaultSyncingSection
import com.bontecou.syncmd.ui.components.VaultTab
import com.bontecou.syncmd.ui.screens.HomeVault
import com.bontecou.syncmd.ui.screens.changeBadge
import com.bontecou.syncmd.ui.screens.rememberVaultSyncViewModel
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.util.AdaptiveInfo
import com.bontecou.syncmd.ui.util.AdaptiveLayout
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.ConflictViewModel
import com.bontecou.syncmd.ui.viewmodels.PullViewModel
import com.bontecou.syncmd.ui.viewmodels.SavedRepository
import com.bontecou.syncmd.ui.viewmodels.VaultSyncViewModel

/** What the detail pane shows. */
enum class DetailKind { DIFF, EDITOR, CONFLICT }

/**
 * The vault on large screens and foldables: the boards Tablet / TabletEditor / TabletConflict
 * (rail, vaults, changes, detail), Fold* inner (rail, changes up to the hinge, detail),
 * FoldTabletop and FoldSplit. Phones and the cover display keep the phone Vault screen.
 */
@Composable
fun AdaptiveVault(
    info: AdaptiveInfo,
    repositoryPath: String,
    vaultName: String,
    owner: String?,
    repositories: List<SavedRepository>,
    onSelectVault: (String) -> Unit,
    chrome: @Composable () -> RailChrome,
    onSelectTab: (VaultTab) -> Unit,
    /** Push rejected › Review first, and the Resolve flow after a blocked pull. */
    onOpenRemoteMerge: () -> Unit,
    /** Full-screen diff / editor, used where no pane is beside the list (split screen, tabletop). */
    onOpenDiffScreen: (String) -> Unit,
    onOpenEditorScreen: (String) -> Unit,
) {
    val syncViewModel = rememberVaultSyncViewModel()
    // The open detail lives above the layout switch: rotating or folding into tabletop/split and
    // back must not drop an open editor (those layouts have no inline editor, so it opens full screen).
    val kind = rememberSaveable(repositoryPath) { mutableStateOf<DetailKind?>(null) }
    val file = rememberSaveable(repositoryPath) { mutableStateOf<String?>(null) }
    val inlineEditor = info.layout != AdaptiveLayout.Split && info.layout != AdaptiveLayout.Tabletop
    LaunchedEffect(inlineEditor) {
        val open = file.value
        if (!inlineEditor && kind.value == DetailKind.EDITOR && open != null) {
            kind.value = DetailKind.DIFF
            onOpenEditorScreen(open)
        }
    }
    when (info.layout) {
        AdaptiveLayout.Split -> SplitVaultPanel(repositoryPath, vaultName, onOpenDiffScreen, syncViewModel)
        AdaptiveLayout.Tabletop -> TabletopVault(info, repositoryPath, onOpenEditorScreen, syncViewModel)
        else -> VaultRailRow(VaultTab.CHANGES, onSelectTab, chrome(), wide = info.isExpanded) {
            VaultPanes(info, repositoryPath, vaultName, owner, repositories, onSelectVault, onOpenRemoteMerge, syncViewModel, kind, file)
        }
    }
}

@Composable
private fun VaultPanes(
    info: AdaptiveInfo,
    repositoryPath: String,
    vaultName: String,
    owner: String?,
    repositories: List<SavedRepository>,
    onSelectVault: (String) -> Unit,
    onOpenRemoteMerge: () -> Unit,
    syncViewModel: VaultSyncViewModel?,
    kindState: MutableState<DetailKind?>,
    fileState: MutableState<String?>,
) {
    var kind by kindState
    var file by fileState
    var autoPicked by rememberSaveable(repositoryPath) { mutableStateOf(false) }
    var handEdited by rememberSaveable(repositoryPath) { mutableStateOf(setOf<String>()) }
    // Bumped when the commit sheet closes: the diff re-reads what is left.
    var revision by rememberSaveable { mutableIntStateOf(0) }
    val tablet = info.isTabletDevice
    val open: (DetailKind?, String?) -> Unit = { k, f -> kind = k; file = f }
    val overview = syncViewModel?.overview?.collectAsState()?.value

    Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 12.dp, end = 16.dp, bottom = 16.dp)) {
        if (info.isExpanded) {
            val home = activityHomeViewModel()
            val vaults = rememberAdaptiveVaults(repositories, home)
            VaultListPane(vaultListItems(vaults), vaults.count(HomeVault::needsAttention), vaults.count(HomeVault::inSync), repositoryPath,
                onSelect = { path -> open(null, null); onSelectVault(path) })
            Spacer(Modifier.width(PaneGap))
        }
        PaneRow(info, if (info.isExpanded) 300.dp else 333.dp, Modifier.weight(1f),
            list = {
                ChangesPane(repositoryPath, vaultName, owner, tablet, selected = file,
                    onOpen = { row -> open(if (row.conflict) DetailKind.CONFLICT else DetailKind.DIFF, row.path) },
                    onRows = { rows ->
                        if (!autoPicked && kind == null && rows.isNotEmpty()) {
                            autoPicked = true
                            val first = rows.firstOrNull { it.conflict } ?: rows.first()
                            open(if (first.conflict) DetailKind.CONFLICT else DetailKind.DIFF, first.path)
                        }
                        if (kind == DetailKind.DIFF && file != null && rows.none { it.path == file }) open(null, null)
                    },
                    onOpenRemoteMerge = onOpenRemoteMerge,
                    onCommitSheetClosed = { revision++ },
                    syncViewModel = syncViewModel)
            },
            detail = {
                val path = file
                when {
                    path == null || kind == null -> DetailPlaceholder()
                    kind == DetailKind.EDITOR -> key(path) {
                        EditorPane(repositoryPath, path, vaultName, overview?.lineCounts?.get(path), onCommitted = { revision++ },
                            syncViewModel = syncViewModel)
                    }
                    kind == DetailKind.CONFLICT -> ConflictPane(repositoryPath, path, tablet,
                        onShowing = { next -> if (next == null) open(null, null) else file = next },
                        onEditByHand = { open(DetailKind.EDITOR, it) },
                        handEdited = handEdited, onHandEdited = { handEdited = it })
                    else -> key(path, revision) {
                        DiffPane(repositoryPath, path, wide = info.isExpanded,
                            onOpenEditor = { open(DetailKind.EDITOR, it) },
                            onDiscarded = { next -> open(next?.let { DetailKind.DIFF }, next) })
                    }
                }
            },
        )
    }
}

/**
 * The stateful Changes pane: the phone Vault's reads (status, overview, the app-wide sync run)
 * laid out as the boards' list pane. While a sync runs or after a rejected push the phone's
 * progress and three-ways-forward sections stand in for the file list.
 */
@Composable
fun ChangesPane(
    repositoryPath: String,
    vaultName: String,
    owner: String?,
    tablet: Boolean,
    selected: String?,
    onOpen: (ChangeRowUi) -> Unit,
    onRows: (List<ChangeRowUi>) -> Unit,
    onOpenRemoteMerge: () -> Unit,
    onCommitSheetClosed: () -> Unit,
    syncViewModel: VaultSyncViewModel?,
    modifier: Modifier = Modifier,
    viewModel: PullViewModel = hiltViewModel(),
    conflictVM: ConflictViewModel = hiltViewModel(),
) {
    var committing by rememberSaveable(repositoryPath) { mutableStateOf(false) }
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(repositoryPath) { viewModel.setRepositoryPath(repositoryPath); syncViewModel?.refresh() }
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) { viewModel.setRepositoryPath(repositoryPath); syncViewModel?.setRepositoryPath(repositoryPath) }
    }
    val selection by viewModel.selection.collectAsState()
    val requestContext = selection?.takeIf { it.path == repositoryPath }
    val status = viewModel.status.collectAsState().value?.takeIf { requestContext != null && it.repoPath == repositoryPath }
    val error = viewModel.errorText.collectAsState().value?.asString()?.takeIf { requestContext != null }
    val syncPath = syncViewModel?.repositoryPath?.collectAsState()?.value
    val overview = syncViewModel?.overview?.collectAsState()?.value?.takeIf { syncPath == repositoryPath }
    val run = syncViewModel?.run?.collectAsState()?.value?.takeIf { it.path == repositoryPath }
    LaunchedEffect(run?.result) { if (run?.result != null) { viewModel.loadStatus(); syncViewModel?.refresh() } }
    LaunchedEffect(status) { if (status != null) syncViewModel?.refresh() }

    val counts = overview?.lineCounts.orEmpty()
    val rows = status?.allChanges.orEmpty().distinctBy { it.filePath }.sortedBy { it.filePath }.map { entry ->
        val (letter, tone) = changeBadge(entry)
        val count = counts[entry.filePath]?.takeIf { !it.binary }
        ChangeRowUi(entry.filePath, letter, tone, count?.added, count?.removed, entry.isConflicted)
    }
    LaunchedEffect(rows) { if (status != null) onRows(rows) }
    val conflicts = rows.count { it.conflict }
    LaunchedEffect(conflicts > 0, repositoryPath) { if (conflicts > 0) conflictVM.setRepositoryPath(repositoryPath) }
    val mergeSource = conflictVM.mergeState.collectAsState().value?.sourceBranch?.takeIf { it.isNotBlank() } ?: overview?.upstream
    val ahead = overview?.ahead ?: 0
    val behind = overview?.behind ?: 0
    val rejected = run?.result as? VaultSyncResult.Rejected
    val eyebrow = listOfNotNull(overview?.branch ?: status?.currentBranch?.ifBlank { null }, owner?.takeIf { it.isNotBlank() }?.let { "$it/$vaultName" })
        .joinToString(" · ")

    ChangesPaneContent(
        eyebrow = eyebrow,
        name = vaultName,
        count = rows.size,
        countLabel = changesOnDevice(tablet, rows.size),
        remote = remoteMeta(overview, status?.lastFetchAt),
        rows = rows,
        selected = selected,
        onOpen = onOpen,
        banner = if (conflicts > 0) mergeSource?.let { pluralStringResource(R.plurals.adaptive_merging_left, conflicts, it, conflicts) }
            ?: pluralStringResource(R.plurals.adaptive_merging_left_plain, conflicts, conflicts) else null,
        modifier = modifier,
        notices = {
            val note = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            val dismiss = stringResource(R.string.vault_m3_dismiss)
            if (error != null) M3Note(error, note.testTag("vault-pull-error"), tone = M3NoteTone.Warn, action = {
                M3Button(dismiss, { viewModel.clearError(requestContext) }, style = M3ButtonStyle.OutlinedContent, size = M3ButtonSize.XSmall)
            })
            when (val result = run?.result) {
                is VaultSyncResult.Failed -> M3Note(result.message.asString(), note.testTag("vault-sync-error"), tone = M3NoteTone.Warn,
                    title = stringResource(R.string.vault_m3_sync_failed), action = {
                        M3Button(dismiss, { syncViewModel?.dismiss() }, style = M3ButtonStyle.OutlinedContent, size = M3ButtonSize.XSmall)
                    })
                is VaultSyncResult.Done -> M3Note(result.message.asString(), note.testTag("vault-sync-done"), tone = M3NoteTone.Ok, action = {
                    M3Button(dismiss, { syncViewModel?.dismiss() }, style = M3ButtonStyle.OutlinedContent, size = M3ButtonSize.XSmall)
                })
                VaultSyncResult.Cancelled -> M3Note(stringResource(R.string.vault_m3_sync_cancelled), note, tone = M3NoteTone.Muted, action = {
                    M3Button(dismiss, { syncViewModel?.dismiss() }, style = M3ButtonStyle.OutlinedContent, size = M3ButtonSize.XSmall)
                })
                VaultSyncResult.LocalChangesBlocked -> M3Note(stringResource(R.string.resolve_local_title), note, tone = M3NoteTone.Ahead, action = {
                    M3Button(dismiss, { syncViewModel?.dismiss(); viewModel.clearLocalChangesBlock(requestContext) }, style = M3ButtonStyle.Text, size = M3ButtonSize.XSmall)
                })
                else -> Unit
            }
        },
        body = when {
            run != null && run.running -> { { VaultSyncingSection(run, { syncViewModel?.cancel() }) } }
            rejected != null -> { {
                VaultPushRejectedSection(rejected.divergence, rejected.gitLine,
                    onPullThenPush = { syncViewModel?.pullThenPush() },
                    onReviewFirst = { syncViewModel?.dismiss(); onOpenRemoteMerge() },
                    onPushToBranch = { syncViewModel?.pushToBranch() })
            } }
            else -> null
        },
        footer = {
            val busy = run?.running == true
            val wide = Modifier.fillMaxWidth().testTag("changes-primary")
            when {
                rows.isNotEmpty() -> M3Button(stringResource(R.string.vault_m3_commit_push), { committing = true }, wide,
                    style = M3ButtonStyle.Filled, size = M3ButtonSize.Large, trailingMeta = "↑${rows.size}",
                    enabled = !busy && requestContext != null && conflicts == 0)
                ahead > 0 && behind > 0 -> M3Button(stringResource(R.string.adaptive_pull_then_push), { syncViewModel?.pullThenPush() }, wide,
                    size = M3ButtonSize.Large, trailingMeta = "↓$behind ↑$ahead", enabled = !busy && syncViewModel != null)
                ahead > 0 -> M3Button(stringResource(R.string.adaptive_push), { syncViewModel?.push() }, wide,
                    size = M3ButtonSize.Large, trailingMeta = "↑$ahead", enabled = !busy && syncViewModel != null)
                behind > 0 -> M3Button(stringResource(R.string.adaptive_pull), { syncViewModel?.pull() ?: requestContext?.let(viewModel::executePull) }, wide,
                    size = M3ButtonSize.Large, trailingMeta = "↓$behind", enabled = !busy)
                else -> M3Button(stringResource(R.string.vault_m3_commit_push), {}, wide, size = M3ButtonSize.Large, enabled = false)
            }
        },
    )
    if (committing && requestContext != null) CommitSheet(repositoryPath, vaultName,
        onDismiss = { committing = false; viewModel.loadStatus(); syncViewModel?.refresh(); onCommitSheetClosed() },
        syncViewModel = syncViewModel)
}

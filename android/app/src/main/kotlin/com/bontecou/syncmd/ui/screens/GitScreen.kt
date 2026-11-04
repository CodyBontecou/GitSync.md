package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.data.models.BranchType
import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.data.models.DiffStatus
import com.bontecou.syncmd.data.models.FileDiff
import com.bontecou.syncmd.data.models.Stash
import com.bontecou.syncmd.data.models.Tag
import com.bontecou.syncmd.ui.components.ConflictViewer
import com.bontecou.syncmd.ui.components.RevertConfirmDialog
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideAccessory
import com.bontecou.syncmd.ui.theme.TideBanner
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideDisplay
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TideOutlineButton
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TidePrimaryButton
import com.bontecou.syncmd.ui.theme.TidePulse
import com.bontecou.syncmd.ui.theme.TideRow
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideTopBar
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.BranchViewModel
import com.bontecou.syncmd.ui.viewmodels.ConflictViewModel
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel
import com.bontecou.syncmd.ui.viewmodels.HistoryViewModel
import com.bontecou.syncmd.ui.viewmodels.PullViewModel

/**
 * Git tools — Tide rebuild of iOS GitControlSheet (+ VaultGitTab rows).
 *
 * "State first, git second": a meta line (vault → branch) and a display
 * headline that says what's possible ("Commit 2 files", "Complete merge",
 * "Nothing to commit"), then the repository status card, conflict center,
 * the commit composer with its one ink primary action, and grouped cards
 * for branches, stash, tags and remote actions. Everything stays inside one
 * scrolling list ("git-actions") so each control is reachable by scrolling.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GitScreen(
    repositoryPath: String,
    showDebugInfo: Boolean = false,
    onNavigateBack: () -> Unit,
    onOpenDiff: (String, Boolean) -> Unit = { _, _ -> },
    onOpenRebase: () -> Unit = {},
    onOpenRemoteMerge: () -> Unit = {},
    onOpenLfs: () -> Unit = {},
    pullVM    : PullViewModel    = hiltViewModel(),
    diffVM    : DiffViewModel    = hiltViewModel(),
    branchVM  : BranchViewModel  = hiltViewModel(),
    conflictVM: ConflictViewModel= hiltViewModel(),
    historyVM : HistoryViewModel = hiltViewModel(),
    showNavigation: Boolean = false,
    onSelectTab: (com.bontecou.syncmd.ui.components.VaultTab) -> Unit = {},
) {
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(repositoryPath) {
        pullVM.setRepositoryPath(repositoryPath)
        diffVM.refreshRepositoryPath(repositoryPath)
        branchVM.setRepositoryPath(repositoryPath)
        conflictVM.setRepositoryPath(repositoryPath)
        historyVM.setRepositoryPath(repositoryPath)
    }
    // Establish path ownership during navigation, before this entry reaches RESUMED.
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) {
            pullVM.setRepositoryPath(repositoryPath)
            diffVM.setRepositoryPath(repositoryPath)
            branchVM.setRepositoryPath(repositoryPath)
            conflictVM.setRepositoryPath(repositoryPath)
            historyVM.setRepositoryPath(repositoryPath)
        }
    }

    val operationRevision by diffVM.operationRevision.collectAsState()
    val branchState = branchVM.state.collectAsState()
    val branchRevision = branchState.value.operationRevision
    LaunchedEffect(repositoryPath, branchRevision) {
        if (branchRevision > 0 && repositoryPath.isNotBlank()) {
            pullVM.loadStatus()
            diffVM.loadDiff()
            conflictVM.loadConflicts()
            historyVM.loadHistory()
        }
    }
    LaunchedEffect(repositoryPath, operationRevision) {
        if (operationRevision > 0 && repositoryPath.isNotBlank()) {
            pullVM.loadStatus()
            branchVM.loadBranches()
            historyVM.loadHistory()
        }
    }
    val pullAttention by pullVM.attention.collectAsState()
    val pullError = pullVM.errorText.collectAsState().value?.asString()
    val pullBusy by pullVM.isLoading.collectAsState()
    val pullSelection by pullVM.selection.collectAsState()
    val pullContext = pullSelection?.takeIf { it.path == repositoryPath }
    val loadedStatus by pullVM.status.collectAsState()
    val status = loadedStatus?.takeIf { pullContext != null && it.repoPath == repositoryPath }
    val diffState = diffVM.diff.collectAsState()
    val diff by diffState
    val commitMessage  by diffVM.commitMessage.collectAsState()
    val isCommitting by diffVM.isCommitting.collectAsState()
    val operationMessage = diffVM.operationMessage.collectAsState().value?.asString()
    val branchContext = branchState.value.selection?.takeIf { it.path == repositoryPath }
    val currentBranch = branchState.value.currentBranch?.takeIf { branchContext != null && branchState.value.actionsReady }
    val branchError = branchState.value.errorMessage?.takeIf { branchContext != null }?.asString()
    val branchBusy = branchState.value.isLoading
    val conflicts      by conflictVM.conflicts.collectAsState()
    val resolvedConflicts by conflictVM.resolvedConflicts.collectAsState()
    val mergeState     by conflictVM.mergeState.collectAsState()
    val isPushing      by diffVM.isPushing.collectAsState()
    val isLoading by diffVM.isLoading.collectAsState()
    val pushError = diffVM.errorMessage.collectAsState().value?.asString()
    val lockedPaths by diffVM.lockedPaths.collectAsState()
    val selectedConflict by conflictVM.selectedConflict.collectAsState()
    val manualContent by conflictVM.manualResolutionContent.collectAsState()
    val resolutionPath by conflictVM.resolutionPath.collectAsState()
    val conflictBusy by conflictVM.isLoading.collectAsState()
    val conflictError = conflictVM.errorMessage.collectAsState().value?.asString()
    val conflictSelection by conflictVM.selection.collectAsState()
    val conflictReady by conflictVM.actionsReady.collectAsState()
    val conflictContext = conflictSelection?.takeIf { it.path == repositoryPath }
    val canActOnConflict = conflictContext != null && conflictReady && !conflictBusy
    val historyState = historyVM.state.collectAsState()
    val historyContext = historyState.value.selection?.takeIf { it.path == repositoryPath }
    val historyError = historyState.value.errorMessage?.asString()
    val historyRevision = historyState.value.operationRevision
    LaunchedEffect(repositoryPath, historyRevision) {
        if (historyRevision > 0 && historyContext != null) {
            pullVM.loadStatus(); diffVM.loadDiff(); branchVM.loadBranches(); conflictVM.loadConflicts()
        }
    }
    val c = Tide.colors

    var newBranchName    by remember(repositoryPath) { mutableStateOf("") }
    var newTagName       by remember(repositoryPath) { mutableStateOf("") }
    var newTagMessage by remember(repositoryPath) { mutableStateOf("") }
    var stashMsg         by remember(repositoryPath) { mutableStateOf("") }
    var mergeMsg         by remember { mutableStateOf("") }
    val discardReview by diffVM.discardReview.collectAsState()
    val reviewingDiscard by diffVM.isReviewingDiscard.collectAsState()
    val discarding by diffVM.isDiscarding.collectAsState()
    val isRevertingAll = reviewingDiscard || discarding
    DisposableEffect(repositoryPath) { onDispose { diffVM.dismissDiscard() } }

    val focusManager       = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissKeyboardOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                focusManager.clearFocus()
                return Offset.Zero
            }
        }
    }

    val changeCount    = diff?.summary?.filesChanged ?: 0
    val stagedCount    = diff?.files?.count { it.isStaged } ?: 0
    val mergeActive = conflictContext != null && mergeState?.isMergeInProgress == true
    val detached = status?.currentBranch?.let(::isDetachedHead) == true
    val branchLabel = when {
        detached -> status!!.currentBranch.take(7)
        currentBranch != null -> currentBranch.name
        status != null -> status.currentBranch
        else -> "—"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.paper)
            .then(if (showNavigation) Modifier else Modifier.navigationBarsPadding())
    ) {
        // ── Top bar ───────────────────────────────────────────────────────
        TideTopBar(
            modifier = Modifier.testTag(
                if (historyContext != null && historyState.value.actionsReady && !historyState.value.isLoading &&
                    !pullBusy && !isLoading && !branchBusy && !conflictBusy) "git-history-ready" else "git-history-loading"
            ),
            onBack = onNavigateBack,
            backLabel = gitLabel(stringResource(R.string.ui_done_283e91eb)),
            center = {
                com.bontecou.syncmd.ui.theme.TideTopBarTitle(stringResource(R.string.tide_git_tools), "${java.io.File(repositoryPath).name} → $branchLabel")
            },
        )
        Box(
            Modifier.fillMaxWidth().height(1.dp).background(c.paper).testTag(
                if (canActOnConflict && mergeState?.isMergeInProgress == true) "git-merge-active" else "git-merge-inactive"
            )
        )

        // ── Scrollable body ───────────────────────────────────────────────
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .nestedScroll(dismissKeyboardOnScroll)
                .testTag("git-actions"),
            contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── Header: vault → branch, state headline ────────────────────
            item {
                GitHeader(
                    meta = "${java.io.File(repositoryPath).name} → $branchLabel",
                    headline = when {
                        mergeActive -> stringResource(R.string.tide_git_headline_complete_merge)
                        stagedCount > 0 -> pluralStringResource(R.plurals.tide_git_headline_commit, stagedCount, stagedCount)
                        changeCount > 0 -> stringResource(R.string.tide_git_headline_choose)
                        else -> stringResource(R.string.tide_git_headline_nothing)
                    },
                )
            }

            // ── Debug info ────────────────────────────────────────────────
            if (showDebugInfo) {
                item {
                    GitGroup(
                        title = stringResource(R.string.ui_debug_info_697b4941),
                        trailing = { TidePill(gitLabel(stringResource(R.string.ui_debug_0ffd43f5)), c.ahead, c.ahead.copy(alpha = 0.12f)) },
                    ) {
                        TideRow(stringResource(R.string.ui_repo_path_a3f06032), value = repositoryPath)
                        TideHairline()
                        TideRow(stringResource(R.string.ui_changes_bbd4b6a8), value = stringResource(R.string.ui_1_s_files_ddef923b, changeCount.toString()))
                        TideHairline()
                        TideRow(stringResource(R.string.staged), value = stringResource(R.string.ui_1_s_files_ddef923b, stagedCount.toString()))
                    }
                }
            }

            // ── Repository status (current branch card) ───────────────────
            item {
                GitStatusCard(
                    branchName = branchLabel,
                    detached = detached,
                    loaded = status != null,
                    headText = status?.let { com.bontecou.syncmd.ui.components.headCommitText(it.headCommit) },
                    syncText = status?.let { com.bontecou.syncmd.ui.components.recordedSyncText(it.lastSyncAt) },
                    changeCount = changeCount,
                )
            }

            // ── Conflict center (only when merge in progress) ─────────────
            if (mergeActive) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TideSectionLabel(stringResource(R.string.ui_conflict_center_e9f65d36), Modifier.weight(1f))
                            TidePill(gitLabel(stringResource(R.string.ui_merge_fc2b737d)), c.danger, c.danger.copy(alpha = 0.12f))
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(c.card, Tide.cardShape)
                                .border(1.dp, c.danger.copy(alpha = 0.35f), Tide.cardShape)
                                .padding(horizontal = 16.dp)
                        ) {
                            mergeState?.sourceBranch?.takeIf { it.isNotBlank() }?.let { source ->
                                Text(
                                    "$source → $branchLabel",
                                    style = TideType.mono(12.sp),
                                    color = c.inkMid,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 14.dp),
                                )
                            }
                            if (conflicts.isEmpty()) {
                                Text(
                                    stringResource(R.string.ui_all_conflicts_resolved_complete_or_abort_t_9a4e2198),
                                    style = TideType.sans(15.sp),
                                    color = c.synced,
                                    modifier = Modifier.padding(vertical = 14.dp),
                                )
                            } else {
                                Text(
                                    pluralStringResource(R.plurals.tide_git_conflicts_to_resolve, conflicts.size, conflicts.size),
                                    style = TideType.meta,
                                    color = c.danger,
                                    modifier = Modifier.padding(top = 14.dp),
                                )
                                conflicts.forEachIndexed { idx, conflict ->
                                    if (idx > 0) TideHairline()
                                    ConflictRow(
                                        conflict      = conflict,
                                        isResolved    = resolvedConflicts.contains(conflict.filePath),
                                        enabled       = canActOnConflict,
                                        onOurs        = { conflictContext?.let { conflictVM.acceptOurs(it, conflict) } },
                                        onTheirs      = { conflictContext?.let { conflictVM.acceptTheirs(it, conflict) } },
                                        onManual      = { conflictContext?.let { conflictVM.selectConflict(it, conflict) } },
                                    )
                                }
                            }
                            TideHairline()
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    GitField(
                                        value       = mergeMsg,
                                        onChange    = { mergeMsg = it },
                                        placeholder = stringResource(R.string.ui_merge_commit_message_2132e189),
                                        modifier    = Modifier.weight(1f),
                                        mono = false,
                                    )
                                    GitPill(
                                        label   = gitLabel(stringResource(R.string.ui_complete_894981ff)),
                                        enabled = canActOnConflict && conflicts.isEmpty(),
                                        style   = GitPillStyle.SUCCESS,
                                    ) {
                                        conflictContext?.let { conflictVM.completeMerge(it, mergeMsg) }
                                        mergeMsg = ""
                                    }
                                }
                                GitPill(
                                    label = gitLabel(stringResource(R.string.ui_abort_merge_5af7a9ac)),
                                    enabled = canActOnConflict,
                                    style = GitPillStyle.DANGER,
                                    wide = true,
                                ) { conflictContext?.let { conflictVM.abortMerge(it) } }
                            }
                        }
                    }
                }
            }

            // ── Commit composer ───────────────────────────────────────────
            // One lazy item: the message, the included files and the commit/push actions
            // compose together, so the actions are present whenever the message field is.
            item(key = "commit-composer") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Message
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TideSectionLabel(stringResource(R.string.tide_git_message), Modifier.padding(start = 4.dp, top = 8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .border(1.dp, c.lineSoft, Tide.fieldShape)
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            val messageStyle = TideType.body
                            if (commitMessage.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.ui_commit_message_a4b361b7),
                                    style = messageStyle,
                                    color = c.inkFaint,
                                )
                            }
                            BasicTextField(
                                value = commitMessage,
                                onValueChange = { diffVM.setCommitMessage(it) },
                                textStyle = messageStyle.copy(color = c.ink),
                                cursorBrush = SolidColor(c.primary),
                                minLines = 3,
                                maxLines = 5,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                }),
                                modifier = Modifier.fillMaxWidth().testTag("commit-message"),
                            )
                        }
                    }

                    // Included files
                    val files = diff?.files ?: emptyList()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TideSectionLabel(stringResource(R.string.tide_git_included))
                            if (stagedCount > 0) {
                                Text("$stagedCount/${files.size}", style = TideType.section, color = c.inkFaint)
                            }
                            Spacer(Modifier.weight(1f))
                            if (files.any { !it.isStaged }) {
                                GitTextButton(
                                    label = stringResource(R.string.ui_stage_all_d1ce47c8),
                                    enabled = !isLoading,
                                    onClick = { diffVM.stageAll() },
                                )
                            }
                        }
                        if (files.isEmpty()) {
                            TideCard(padding = 16.dp, radius = 20.dp) {
                                Text(
                                    stringResource(R.string.ui_no_local_changes_56319dc5),
                                    style = TideType.sans(15.sp),
                                    color = c.inkMid,
                                )
                            }
                        } else {
                            TideCard(padding = 0.dp, radius = 20.dp) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                                    files.forEachIndexed { idx, file ->
                                        if (idx > 0) TideHairline()
                                        GitChangeRow(
                                            file      = file,
                                            onStage   = { diffVM.stageFile(file.filePath) },
                                            onUnstage = { diffVM.unstageFile(file.filePath) },
                                            onDiff    = { onOpenDiff(file.filePath, file.isStaged) },
                                            enabled   = !isLoading,
                                        )
                                    }
                                }
                            }
                            GitPill(
                                label = gitLabel(stringResource(R.string.ui_revert_all_changes_416c8713)),
                                enabled = !isRevertingAll,
                                loading = isRevertingAll,
                                style = GitPillStyle.DANGER,
                                wide = true,
                                onClick = { diffVM.requestDiscard() },
                            )
                        }
                    }

                    // Actions
                    val canCommit = stagedCount > 0 && commitMessage.isNotBlank() && !isLoading && mergeState?.isMergeInProgress != true
                    val canPush = canCommit && currentBranch != null
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = when {
                                !canPush && !isPushing && stagedCount == 0 -> stringResource(R.string.tide_git_hint_stage)
                                !canPush && !isPushing && commitMessage.isBlank() -> stringResource(R.string.tide_git_hint_message)
                                else -> pluralStringResource(R.plurals.tide_git_files_staged, stagedCount, stagedCount)
                            },
                            style = TideType.meta,
                            color = c.inkFaint,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        if (isPushing || isCommitting) {
                            Row(
                                Modifier.padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                TidePulse()
                                Text(
                                    stringResource(if (isPushing) R.string.tide_git_pushing else R.string.tide_git_committing),
                                    style = TideType.meta,
                                    color = c.inkMid,
                                )
                            }
                        }
                        TidePrimaryButton(
                            title = stringResource(R.string.ui_commit_push_94e9650a),
                            onClick = { diffVM.commitAndPush(branch = currentBranch?.name ?: "main") },
                            badge = if (stagedCount > 0) "↑$stagedCount" else null,
                            enabled = canPush,
                            loading = isPushing,
                        )
                        TideOutlineButton(
                            title = stringResource(if (isCommitting && !isPushing) R.string.tide_git_committing else R.string.tide_git_commit_locally),
                            onClick = { diffVM.commitOnly() },
                            enabled = canCommit,
                            modifier = Modifier.fillMaxWidth(),
                            height = 48.dp,
                        )
                        TideOutlineButton(
                            title = stringResource(if (isPushing) R.string.tide_git_pushing else R.string.tide_git_push_current),
                            onClick = { currentBranch?.let { diffVM.pushCurrentBranch(it.name) } },
                            enabled = !isLoading && currentBranch != null && mergeState?.isMergeInProgress != true,
                            modifier = Modifier.fillMaxWidth(),
                            height = 48.dp,
                        )
                        operationMessage?.let { message ->
                            TideBanner(title = message, tone = TideBannerTone.NEUTRAL)
                        }
                        // Push error (rejected, auth, network…): tap or ✕ clears it.
                        if (pushError != null) {
                            TideBanner(
                                title = pushError,
                                tone = TideBannerTone.DANGER,
                                onDismiss = { diffVM.clearError() },
                                dismissLabel = stringResource(R.string.ui_dismiss_48845bff),
                                modifier = Modifier.clickable(role = Role.Button) { diffVM.clearError() },
                            )
                        }
                    }
                }
            }

            // ── Branches ──────────────────────────────────────────────────
            item {
                val branches = branchState.value
                val context = branches.selection?.takeIf { it.path == repositoryPath }
                val canAct = context != null && branches.actionsReady && !branches.isLoading
                val localBranches = branches.localBranches.takeIf { context != null }.orEmpty()
                val current = branches.currentBranch?.takeIf { canAct }
                GitGroup(title = stringResource(R.string.branch_screen), count = localBranches.size.takeIf { it > 0 }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GitField(
                            value       = newBranchName,
                            onChange    = { newBranchName = it },
                            placeholder = stringResource(R.string.ui_new_branch_name_3fb60017),
                            modifier    = Modifier.weight(1f).testTag("git-branch-name"),
                            enabled = canAct,
                        )
                        val canCreate = newBranchName.trim().isNotEmpty() && canAct && current?.lastCommit != null
                        GitPill(
                            label   = gitLabel(stringResource(R.string.ui_create_fde9c501)),
                            enabled = canCreate,
                            modifier = Modifier.testTag("git-branch-create"),
                            style   = GitPillStyle.FILLED,
                        ) {
                            val expected = context ?: return@GitPill
                            val submitted = newBranchName
                            branchVM.createBranch(expected, submitted.trim()) { if (newBranchName == submitted) newBranchName = "" }
                        }
                    }
                    if (detached) {
                        TideHairline()
                        DetachedHeadRow(status!!.currentBranch)
                    }
                    if (localBranches.isNotEmpty()) {
                        localBranches.forEach { branch ->
                            TideHairline()
                            GitBranchRow(
                                branch     = branch,
                                isCurrent  = branches.actionsReady && (branch.isHead || branch.name == current?.name),
                                enabled = canAct,
                                onSwitch   = { context?.let { branchVM.switchBranch(it, branch) } },
                                onMerge    = { context?.let { branchVM.mergeBranch(it, branch) } },
                                onDelete   = { context?.let { branchVM.deleteBranch(it, branch) } },
                            )
                        }
                    } else if (context != null && branches.actionsReady && !branches.isLoading) {
                        GitEmptyLine(stringResource(R.string.ui_no_branches_available_7f5adec5))
                    }
                }
            }

            // ── Stash ─────────────────────────────────────────────────────
            item {
                val history = historyState.value
                val canActOnHistory = historyContext != null && history.actionsReady && !history.isLoading
                val stashes = history.stashes.takeIf { historyContext != null }.orEmpty()
                GitGroup(title = stringResource(R.string.stash_screen), count = stashes.size.takeIf { it > 0 }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GitField(
                            value       = stashMsg,
                            onChange    = { stashMsg = it },
                            placeholder = stringResource(R.string.ui_stash_message_optional_7b463cf6),
                            modifier    = Modifier.weight(1f).testTag("git-stash-message"),
                            enabled = canActOnHistory,
                            mono = false,
                        )
                        GitPill(
                            label   = gitLabel(stringResource(R.string.ui_save_c210bfdf)),
                            modifier = Modifier.testTag("git-stash-save"),
                            enabled = changeCount > 0 && canActOnHistory,
                            style   = GitPillStyle.FILLED,
                        ) {
                            val context = historyContext ?: return@GitPill
                            val submitted = stashMsg
                            historyVM.stashSave(context, submitted) { if (stashMsg == submitted) stashMsg = "" }
                        }
                    }
                    if (stashes.isNotEmpty()) {
                        stashes.forEach { stash ->
                            TideHairline()
                            GitStashRow(
                                stash   = stash,
                                enabled = canActOnHistory,
                                onApply = { historyContext?.let { historyVM.stashApply(it, stash) } },
                                onPop   = { historyContext?.let { historyVM.stashPop(it, stash) } },
                                onDrop  = { historyContext?.let { historyVM.stashDrop(it, stash) } },
                            )
                        }
                    } else if (changeCount == 0) {
                        GitEmptyLine(stringResource(R.string.ui_no_local_changes_to_stash_0beb8393))
                    } else {
                        GitEmptyLine(stringResource(R.string.ui_no_stashes_found_ace2fa6f))
                    }
                }
            }

            // ── Tags ──────────────────────────────────────────────────────
            item {
                val history = historyState.value
                val canActOnHistory = historyContext != null && history.actionsReady && !history.isLoading
                val tags = history.tags.takeIf { historyContext != null }.orEmpty()
                GitGroup(title = stringResource(R.string.ui_tags_1331275b), count = tags.size.takeIf { it > 0 }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GitField(value = newTagName, onChange = { newTagName = it },
                            placeholder = stringResource(R.string.ui_tag_name_e_g_v1_0_0_16aa5a68),
                            enabled = canActOnHistory, modifier = Modifier.fillMaxWidth().testTag("git-tag-name"))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GitField(value = newTagMessage, onChange = { newTagMessage = it },
                                placeholder = stringResource(R.string.history_tag_annotation),
                                enabled = canActOnHistory, mono = false, modifier = Modifier.weight(1f).testTag("git-tag-message"))
                            GitPill(label = gitLabel(stringResource(R.string.ui_create_fde9c501)),
                                modifier = Modifier.testTag("git-tag-create"), enabled = newTagName.trim().isNotEmpty() && canActOnHistory,
                                style = GitPillStyle.FILLED) {
                                val context = historyContext ?: return@GitPill
                                val name = newTagName; val message = newTagMessage
                                val annotation = message.trim().takeIf { it.isNotEmpty() }
                                historyVM.createTag(context, name.trim(), annotated = annotation != null, message = annotation) {
                                    if (newTagName == name) newTagName = ""
                                    if (newTagMessage == message) newTagMessage = ""
                                }
                            }
                        }
                    }
                    if (tags.isNotEmpty()) {
                        tags.forEach { tag ->
                            TideHairline()
                            GitTagRow(
                                tag      = tag,
                                enabled = canActOnHistory,
                                onPush   = { historyContext?.let { historyVM.pushTag(it, tag) } },
                                onDelete = { historyContext?.let { historyVM.deleteTag(it, tag) } },
                            )
                        }
                    } else {
                        GitEmptyLine(stringResource(R.string.ui_no_tags_in_this_repository_3b060f73))
                    }
                }
            }

            // ── LFS attention after a pull ────────────────────────────────
            pullAttention?.takeIf { pullContext != null }?.let { notice -> item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.behind.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
                        .border(1.dp, c.behind.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(notice, modifier = Modifier.testTag("pull-lfs-attention"), style = TideType.sans(14.sp), color = c.ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GitPill(label = stringResource(R.string.ui_open_git_lfs_f7aff155), style = GitPillStyle.FILLED, onClick = onOpenLfs)
                        GitPill(label = stringResource(R.string.ui_dismiss_48845bff), onClick = { pullVM.clearAttention(pullContext) })
                    }
                }
            } }

            // ── Remote ────────────────────────────────────────────────────
            item {
                GitGroup(title = stringResource(R.string.tide_git_remote)) {
                    TideRow(
                        title = stringResource(R.string.git_fetch),
                        subtitle = stringResource(R.string.git_fetch_detail),
                        modifier = Modifier.testTag("git-fetch"),
                        enabled = !pullBusy && pullContext != null,
                        leading = { GitRowIcon("↕") },
                        onClick = { pullContext?.let(pullVM::fetch) },
                    )
                    TideHairline()
                    TideRow(
                        title = stringResource(R.string.pull_screen),
                        subtitle = stringResource(R.string.ui_fetch_and_apply_remote_changes_399278ae),
                        enabled = !pullBusy && pullContext != null,
                        leading = { GitRowIcon("↓") },
                        trailing = { if (pullBusy) TidePulse() },
                        onClick = { pullContext?.let(pullVM::executePull) },
                    )
                    TideHairline()
                    TideRow(
                        title = stringResource(R.string.ui_pull_with_rebase_resume_rebase_035aaf45),
                        modifier = Modifier.testTag("open-rebase"),
                        leading = { GitRowIcon("⤓") },
                        accessory = TideAccessory.CHEVRON,
                        onClick = onOpenRebase,
                    )
                    TideHairline()
                    TideRow(
                        title = stringResource(R.string.remote_merge_title),
                        modifier = Modifier.testTag("open-remote-merge"),
                        leading = { GitRowIcon("⇄") },
                        accessory = TideAccessory.CHEVRON,
                        onClick = onOpenRemoteMerge,
                    )
                    TideHairline()
                    TideRow(
                        title = stringResource(R.string.ui_git_lfs_tracking_and_downloads_0ec019bf),
                        modifier = Modifier.testTag("open-lfs"),
                        leading = { GitRowIcon("◫") },
                        accessory = TideAccessory.CHEVRON,
                        onClick = onOpenLfs,
                    )
                }
            }
        }
        if (showNavigation) com.bontecou.syncmd.ui.components.VaultNavigationBar(com.bontecou.syncmd.ui.components.VaultTab.GIT, onSelectTab,
            changes = changeCount.takeIf { it > 0 })
    }

    // ── Manual conflict resolution sheet ──────────────────────────────────
    selectedConflict?.takeIf { conflictContext != null }?.let { conflict ->
        TideDialog(
            onDismissRequest = { if (!conflictBusy) conflictContext?.let { conflictVM.deselectConflict(it, conflict) } },
            title = stringResource(R.string.ui_resolve_1_s_94accfee, conflict.filePath),
            confirmButton = {
                GitPill(
                    label = stringResource(if (conflictBusy) R.string.tide_git_resolving else R.string.tide_git_resolve_and_stage),
                    onClick = { conflictContext?.let { conflictVM.acceptManual(it, conflict) } },
                    enabled = canActOnConflict && conflicts.any { it == conflict },
                    style = GitPillStyle.FILLED,
                    wide = true,
                )
            },
            dismissButton = {
                GitTextButton(
                    label = stringResource(R.string.publication_cancel),
                    onClick = { conflictContext?.let { conflictVM.deselectConflict(it, conflict) } },
                    enabled = !conflictBusy,
                )
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GitPill(
                    label = stringResource(R.string.ui_use_ours_4bec9877),
                    onClick = { conflictContext?.let { conflictVM.setManualResolutionContent(it, conflict, conflict.currentContent) } },
                    enabled = !conflictBusy && conflict.hasCurrentVersion,
                )
                GitPill(
                    label = stringResource(R.string.ui_use_theirs_352c4272),
                    onClick = { conflictContext?.let { conflictVM.setManualResolutionContent(it, conflict, conflict.incomingContent) } },
                    enabled = !conflictBusy && conflict.hasIncomingVersion,
                )
            }
            conflictError?.let {
                Text(it, style = TideType.sans(14.sp), color = c.danger, modifier = Modifier.padding(top = 8.dp))
            }
            ConflictViewer(
                conflict = conflict,
                manualResolution = manualContent,
                onManualResolutionChange = { if (!conflictBusy) conflictContext?.let { context -> conflictVM.setManualResolutionContent(context, conflict, it) } },
                enabled = !conflictBusy,
                resolutionPath = resolutionPath,
                onResolutionPathChange = { path -> conflictContext?.let { conflictVM.setResolutionPath(it, conflict, path) } },
                modifier = Modifier.padding(top = 8.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
    val operationError = pullError.takeIf { pullContext != null } ?: conflictError.takeIf { conflictContext != null && selectedConflict == null } ?: historyError.takeIf { historyContext != null } ?: branchError
    if (lockedPaths != null && pushError != null) {
        TideDialog(
            onDismissRequest = diffVM::clearError,
            icon = { com.bontecou.syncmd.ui.theme.TideModalIcon(glyph = "!", tint = c.danger) },
            title = stringResource(R.string.ui_push_blocked_by_lfs_locks_e4e28836),
            text = { Text(pushError, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("lfs-lock-error")) },
            confirmButton = { BPrimaryButton(stringResource(R.string.ui_ok_565339bc), Modifier.fillMaxWidth(), onClick = diffVM::clearError) },
        )
    } else if (operationError != null) {
        val clearAll = { pullVM.clearError(pullContext); conflictVM.clearError(conflictContext); historyVM.clearError(historyContext); branchVM.clearError(branchContext) }
        TideDialog(
            onDismissRequest = clearAll,
            title = stringResource(if (operationError == branchError) R.string.tide_git_branch_operation else R.string.tide_git_git_operation),
            text = { Text(operationError, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("git-operation-message")) },
            confirmButton = { BPrimaryButton(stringResource(R.string.ui_ok_565339bc), Modifier.fillMaxWidth(), onClick = clearAll) },
        )
    }

    // ── Revert-all confirmation dialog ────────────────────────────────────
    discardReview?.takeIf { it.filePath == null }?.let { review ->
        RevertConfirmDialog(
            review = review,
            onConfirm = { diffVM.confirmDiscard(review) },
            onDismiss = { diffVM.dismissDiscard(review) },
        )
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/** A 40-hex `currentBranch` means HEAD points at a commit, not a branch. */
private fun isDetachedHead(branch: String): Boolean = branch.length == 40 && branch.all { it in '0'..'9' || it in 'a'..'f' }

/**
 * Tide reads in sentence case. Legacy resources carry SHOUTED labels
 * ("CURRENT", "✕  ABORT MERGE"); drop decorative glyph prefixes and turn
 * all-caps labels into sentence case. Already-cased translations pass through.
 */
/** Tide pills carry no leading glyphs; drop the "✕  " / "↩  " prefixes the shared strings keep. */
private fun gitLabel(raw: String): String = raw.trimStart { !it.isLetterOrDigit() }.ifEmpty { raw }

private enum class GitPillStyle { OUTLINE, FILLED, SUCCESS, DANGER }

/** Small capsule for row actions; 48dp touch target around a 38dp pill. */
@Composable
private fun GitPill(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: GitPillStyle = GitPillStyle.OUTLINE,
    description: String? = null,
    wide: Boolean = false,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Tide.colors
    val fill = when (style) {
        GitPillStyle.FILLED -> c.primary
        GitPillStyle.SUCCESS -> c.okContainer
        else -> Color.Transparent
    }
    val border = when (style) {
        GitPillStyle.OUTLINE -> c.lineSoft
        GitPillStyle.DANGER -> c.lineSoft
        else -> null
    }
    val fg = when (style) {
        GitPillStyle.FILLED -> c.onPrimary
        GitPillStyle.SUCCESS -> c.onOkContainer
        GitPillStyle.DANGER -> c.danger
        GitPillStyle.OUTLINE -> c.primary
    }
    Box(
        modifier
            .then(if (wide) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(
                enabled = enabled && !loading,
                role = Role.Button,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .then(if (wide) Modifier.fillMaxWidth() else Modifier)
                .heightIn(min = 40.dp)
                .alpha(if (enabled || loading) 1f else 0.38f)
                .background(fill, CircleShape)
                .then(if (border != null) Modifier.border(1.dp, border, CircleShape) else Modifier)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = fg)
            } else {
                Text(label, style = TideType.sans(14.sp, FontWeight.SemiBold), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Plain text action ("Stage all", "Cancel") with a 48dp hit target. */
@Composable
private fun GitTextButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TideType.sans(14.sp, FontWeight.Medium), color = Tide.colors.ink, maxLines = 1)
    }
}

/** Inline single-line field inside a card: paper well, hairline outline. */
@Composable
private fun GitField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    mono: Boolean = true,
) {
    val c = Tide.colors
    val shape = RoundedCornerShape(14.dp)
    val style = if (mono) TideType.mono(14.sp) else TideType.sans(15.sp)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .border(1.dp, c.lineSoft, RoundedCornerShape(4.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(text = placeholder, modifier = Modifier.clearAndSetSemantics {}, style = style, color = c.inkFaint,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            textStyle = style.copy(color = c.ink),
            singleLine = true,
            cursorBrush = SolidColor(c.ink),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        )
    }
}

/** Section label (+ mono count) over a white card of hairline-separated rows. */
@Composable
private fun GitGroup(
    title: String,
    count: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TideSectionLabel(title)
            if (count != null) Text("$count", style = TideType.section, color = Tide.colors.inkFaint)
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        TideCard(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp)) { content() }
        }
    }
}

@Composable
private fun GitEmptyLine(text: String) {
    Text(text, style = TideType.meta, color = Tide.colors.inkFaint, modifier = Modifier.padding(bottom = 14.dp))
}

@Composable
private fun GitRowIcon(glyph: String) {
    val icon = when (glyph) {
        "↓", "⤓" -> R.drawable.ic_m3_arrow_downward
        "↕" -> R.drawable.ic_m3_sync
        "⇄" -> R.drawable.ic_m3_branch
        "◫" -> R.drawable.ic_m3_storage
        else -> R.drawable.ic_m3_info
    }
    Box(Modifier.size(24.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        com.bontecou.syncmd.ui.theme.TideIcon(icon, tint = Tide.colors.inkMid)
    }
}

// ─── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun GitHeader(meta: String, headline: String) {
    // The vault → branch line lives in the top app bar subtitle.
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TideDisplay(headline, size = 28.sp)
    }
}

/** iOS "Current branch" card: big mono branch, head · last sync, local changes. */
@Composable
private fun GitStatusCard(
    branchName: String,
    detached: Boolean,
    loaded: Boolean,
    headText: String?,
    syncText: String?,
    changeCount: Int,
) {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TideSectionLabel(stringResource(R.string.ui_repository_status_64c67cd2), Modifier.padding(horizontal = 4.dp))
        TideCard(color = c.primaryContainer, padding = 20.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        TideSectionLabel(stringResource(R.string.ui_branch_52656e81), color = c.onPrimaryContainer)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.bontecou.syncmd.ui.theme.TideIcon(R.drawable.ic_m3_branch, tint = c.onPrimaryContainer, size = 22.dp)
                        Text(
                            branchName,
                            style = TideType.mono(24.sp, FontWeight.SemiBold),
                            color = c.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        }
                    }
                    if (detached) TidePill(stringResource(R.string.tide_git_detached), c.ahead, c.ahead.copy(alpha = 0.12f))
                }
                if (loaded && headText != null && syncText != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(headText, style = TideType.mono(12.sp), color = c.onPrimaryContainer, modifier = Modifier.testTag("git-status-head"))
                        Text("  ·  " + stringResource(R.string.ui_last_sync_a0d540dd) + " ", style = TideType.mono(12.sp), color = c.onPrimaryContainer)
                        Text(syncText, style = TideType.mono(12.sp), color = c.onPrimaryContainer, modifier = Modifier.weight(1f, fill = false).testTag("git-status-sync"),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    TidePulse(color = c.onPrimaryContainer)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.onPrimaryContainer.copy(alpha = 0.16f)))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(gitLabel(stringResource(R.string.ui_local_changes_4d25c7d4)), style = TideType.sans(14.sp), color = c.onPrimaryContainer, modifier = Modifier.weight(1f))
                    Text(
                        if (changeCount > 0) stringResource(R.string.ui_1_s_files_ddef923b, changeCount.toString()) else stringResource(R.string.ui_none_dc937b59),
                        style = TideType.mono(13.sp, FontWeight.SemiBold),
                        color = c.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetachedHeadRow(commit: String) {
    val c = Tide.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TidePill(stringResource(R.string.tide_git_detached), c.ahead, c.ahead.copy(alpha = 0.12f))
        Text(commit.take(7), style = TideType.mono(13.sp), color = c.inkMid)
    }
}

@Composable
private fun GitBranchRow(
    branch: Branch,
    isCurrent: Boolean,
    enabled: Boolean,
    onSwitch: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Tide.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("git-branch-${branch.name}")
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = branch.name,
                style = TideType.mono(14.sp, FontWeight.SemiBold),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = branch.trackingBranch?.takeIf { it.isNotBlank() } ?: stringResource(R.string.tide_git_local_only),
                style = TideType.mono(11.sp),
                color = c.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            TidePill(gitLabel(stringResource(R.string.ui_current_e3cc57e1)), c.synced, c.synced.copy(alpha = 0.12f))
        } else {
            GitPill(label = stringResource(R.string.ui_switch_39921a74), enabled = enabled, onClick = onSwitch)
            GitPill(label = stringResource(R.string.ui_merge_8851aaa7), enabled = enabled, onClick = onMerge)
            GitPill(label = "✕", enabled = enabled, style = GitPillStyle.DANGER,
                description = stringResource(R.string.ui_delete_branch_1c3f98e5), onClick = onDelete)
        }
    }
}

@Composable
private fun ConflictRow(
    conflict: Conflict,
    isResolved: Boolean,
    enabled: Boolean,
    onOurs: () -> Unit,
    onTheirs: () -> Unit,
    onManual: () -> Unit,
) {
    val c = Tide.colors
    Column(
        modifier = Modifier
            .fillMaxWidth().testTag("git-conflict-${conflict.filePath}")
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = conflict.filePath,
                style = TideType.mono(13.sp, FontWeight.SemiBold),
                color = c.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isResolved) TidePill(stringResource(R.string.ui_resolved_5be3c2c8), c.synced, c.synced.copy(alpha = 0.12f))
        }
        if (!conflict.hasCurrentVersion) Text(stringResource(R.string.conflict_current_deleted), style = TideType.sans(13.sp), color = c.inkMid)
        if (!conflict.hasIncomingVersion) Text(stringResource(R.string.conflict_incoming_deleted), style = TideType.sans(13.sp), color = c.inkMid)
        if (conflict.isRenameRename) {
            Text(stringResource(R.string.conflict_renamed_differently), style = TideType.sans(13.sp, FontWeight.SemiBold), color = c.ahead)
            Text(requireNotNull(conflict.ours).path + "\n" + requireNotNull(conflict.theirs).path,
                style = TideType.mono(12.sp), color = c.inkMid, modifier = Modifier.fillMaxWidth())
        }
        if (!isResolved) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GitPill(label = gitLabel(stringResource(R.string.ui_ours_ab658c14)),   enabled = enabled, onClick = onOurs)
                GitPill(label = gitLabel(stringResource(R.string.ui_theirs_691ef727)), enabled = enabled, onClick = onTheirs)
                GitPill(label = gitLabel(stringResource(R.string.ui_manual_7c8107bd)), enabled = enabled && !conflict.isBinaryOrTooLarge, onClick = onManual)
            }
        }
    }
}

/** Mono status glyph: orange = modified (yours), blue = new, red = deleted. */
@Composable
private fun ChangeGlyph(status: DiffStatus) {
    val c = Tide.colors
    val (glyph, tint) = when (status) {
        DiffStatus.ADDED -> "A" to c.behind
        DiffStatus.COPIED -> "C" to c.behind
        DiffStatus.DELETED -> "D" to c.danger
        DiffStatus.MODIFIED -> "M" to c.ahead
        DiffStatus.RENAMED -> "R" to c.ahead
        DiffStatus.TYPE_CHANGE -> "T" to c.ahead
        DiffStatus.UNKNOWN -> "?" to c.inkFaint
    }
    Box(
        Modifier.size(28.dp).background(tint.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = TideType.mono(13.sp, FontWeight.SemiBold), color = tint) }
}

@Composable
private fun statusWord(status: DiffStatus): String = stringResource(
    when (status) {
        DiffStatus.ADDED -> R.string.tide_git_status_added
        DiffStatus.DELETED -> R.string.tide_git_status_deleted
        DiffStatus.MODIFIED -> R.string.tide_git_status_modified
        DiffStatus.RENAMED -> R.string.tide_git_status_renamed
        DiffStatus.COPIED -> R.string.tide_git_status_copied
        DiffStatus.TYPE_CHANGE -> R.string.tide_git_status_type_changed
        DiffStatus.UNKNOWN -> R.string.tide_git_status_changed
    }
)

@Composable
private fun GitChangeRow(file: FileDiff, onStage: () -> Unit, onUnstage: () -> Unit, onDiff: () -> Unit = {}, enabled: Boolean = true) {
    val c = Tide.colors
    val isStaged = file.isStaged
    val additions = file.hunks.sumOf { h -> h.lines.count { it.type.name == "ADDITION" } }
    val deletions = file.hunks.sumOf { h -> h.lines.count { it.type.name == "DELETION" } }
    val name = file.filePath.substringAfterLast('/')
    val directory = file.filePath.substringBeforeLast('/', "")
    val deleted = file.status == DiffStatus.DELETED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("git-change-${if (file.isStaged) "staged" else "unstaged"}-${file.filePath}")
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ChangeGlyph(file.status)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = name,
                style = TideType.body,
                color = if (deleted) c.inkMid else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val stage = if (isStaged) stringResource(R.string.staged) else stringResource(R.string.tide_git_unstaged)
            Text(
                (if (directory.isEmpty()) "" else "$directory/ · ") + "$stage ${statusWord(file.status)}",
                style = TideType.mono(11.sp),
                color = c.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file.oldPath != null) Text(stringResource(R.string.ui_from_1_s_40f7d611, file.oldPath.toString()), style = TideType.mono(11.sp), color = c.inkFaint, maxLines = 2)
            if (file.isBinary) Text(stringResource(R.string.ui_binary_file_2c7ccf98), style = TideType.mono(11.sp), color = c.inkFaint)
            if (additions > 0 || deletions > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (additions > 0) Text(text = "+$additions", style = TideType.mono(11.sp, FontWeight.SemiBold), color = c.synced)
                    if (deletions > 0) Text(text = "−$deletions", style = TideType.mono(11.sp, FontWeight.SemiBold), color = c.danger)
                }
            }
        }
        GitPill(
            label   = stringResource(if (isStaged) R.string.ui_unstage_033f86f9 else R.string.ui_stage_de838855),
            enabled = enabled,
            onClick = { if (isStaged) onUnstage() else onStage() },
        )
        GitPill(
            label   = gitLabel(stringResource(R.string.ui_diff_b6aeb797)),
            onClick = onDiff,
        )
    }
}

@Composable
private fun GitStashRow(stash: Stash, onApply: () -> Unit, onPop: () -> Unit, onDrop: () -> Unit, enabled: Boolean = true) {
    val c = Tide.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("git-stash-${stash.commitHash}")
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stash.id, style = TideType.mono(12.sp, FontWeight.SemiBold), color = c.inkMid)
        Text(
            text = stash.name,
            style = TideType.sans(15.sp, FontWeight.Medium),
            color = c.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GitPill(label = gitLabel(stringResource(R.string.ui_apply_2cbf6555)), enabled = enabled, onClick = onApply)
            GitPill(label = gitLabel(stringResource(R.string.ui_pop_52e740ad)),   enabled = enabled, onClick = onPop)
            Spacer(Modifier.weight(1f))
            GitPill(label = "✕", style = GitPillStyle.DANGER, enabled = enabled,
                description = stringResource(R.string.ui_drop_stash_969bf549), onClick = onDrop)
        }
    }
}

@Composable
private fun GitTagRow(tag: Tag, onPush: () -> Unit, onDelete: () -> Unit, enabled: Boolean = true) {
    val c = Tide.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("git-tag-${tag.name}")
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = tag.name, style = TideType.mono(14.sp, FontWeight.SemiBold), color = c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (tag.isAnnotated) TidePill(stringResource(R.string.tide_git_annotated), c.behind, c.behind.copy(alpha = 0.12f))
                else TidePill(stringResource(R.string.tide_git_lightweight), c.inkMid, c.well)
            }
            if (!tag.message.isNullOrBlank()) {
                Text(text = tag.message!!, style = TideType.sans(14.sp), color = c.inkMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(text = tag.commitHash.take(7), style = TideType.mono(11.sp), color = c.inkFaint)
        }
        GitPill(label = gitLabel(stringResource(R.string.ui_push_792aac67)), enabled = enabled, onClick = onPush)
        GitPill(label = "✕", style = GitPillStyle.DANGER, enabled = enabled,
            description = stringResource(R.string.ui_delete_tag_365635aa), onClick = onDelete)
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

private val previewFiles = listOf(
    FileDiff("notes/today.md", status = DiffStatus.MODIFIED, hunks = emptyList(), isStaged = true),
    FileDiff("ideas.md", status = DiffStatus.ADDED, hunks = emptyList(), isStaged = false),
    FileDiff("old/draft.md", status = DiffStatus.DELETED, hunks = emptyList(), isStaged = false),
)

@Composable
private fun GitPreviewBody(dirty: Boolean, detached: Boolean) {
    val c = Tide.colors
    Column(
        Modifier.background(c.paper).padding(Tide.gutter),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GitHeader(
            meta = "VoxVault → ${if (detached) "e19c4a0" else "main"}",
            headline = if (dirty) "Commit 1 file" else "Nothing to commit",
        )
        GitStatusCard(
            branchName = if (detached) "e19c4a0" else "main",
            detached = detached,
            loaded = true,
            headText = "e19c4a0",
            syncText = "11/2/26, 9:41 AM",
            changeCount = if (dirty) previewFiles.size else 0,
        )
        if (dirty) {
            TideCard(padding = 0.dp, radius = 20.dp) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    previewFiles.forEachIndexed { i, f ->
                        if (i > 0) TideHairline()
                        GitChangeRow(f, onStage = {}, onUnstage = {})
                    }
                }
            }
            TidePrimaryButton("Commit & Push", onClick = {}, badge = "↑1")
        } else {
            TideCard(padding = 16.dp, radius = 20.dp) { Text("No local changes", style = TideType.sans(15.sp), color = c.inkMid) }
            TidePrimaryButton("Commit & Push", onClick = {}, enabled = false)
        }
        GitGroup(title = "Branches", count = 2) {
            if (detached) DetachedHeadRow("e19c4a0d8b7f")
            GitBranchRow(Branch("main", BranchType.TRACKING, isHead = !detached, trackingBranch = "origin/main"), !detached, true, {}, {}, {})
            TideHairline()
            GitBranchRow(Branch("feature/tide", BranchType.LOCAL), false, true, {}, {}, {})
        }
        GitGroup(title = "Stash", count = 1) {
            GitStashRow(Stash("stash@{0}", "WIP on main: tidy notes", "abc1234"), {}, {}, {})
        }
        GitGroup(title = "Tags") {
            if (dirty) GitTagRow(Tag("v1.0.0", "e19c4a0d8b7f", isAnnotated = true, message = "First release"), {}, {})
            else GitEmptyLine("No tags in this repository")
        }
        TideBanner(title = "Push rejected: fetch first", tone = TideBannerTone.DANGER, onDismiss = {})
    }
}

@Preview(name = "Git · dirty · light", heightDp = 1800)
@Composable
private fun GitDirtyLightPreview() = SyncMdTheme(darkTheme = false) { GitPreviewBody(dirty = true, detached = false) }

@Preview(name = "Git · dirty · dark", heightDp = 1800)
@Composable
private fun GitDirtyDarkPreview() = SyncMdTheme(darkTheme = true) { GitPreviewBody(dirty = true, detached = false) }

@Preview(name = "Git · clean, detached · light", heightDp = 1600)
@Composable
private fun GitCleanLightPreview() = SyncMdTheme(darkTheme = false) { GitPreviewBody(dirty = false, detached = true) }

@Preview(name = "Git · clean, detached · dark", heightDp = 1600)
@Composable
private fun GitCleanDarkPreview() = SyncMdTheme(darkTheme = true) { GitPreviewBody(dirty = false, detached = true) }

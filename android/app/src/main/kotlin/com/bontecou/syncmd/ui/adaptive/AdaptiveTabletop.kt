package com.bontecou.syncmd.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.DiffLineType
import com.bontecou.syncmd.data.models.DiffStatus
import com.bontecou.syncmd.services.git.FileLineCount
import com.bontecou.syncmd.ui.components.CommitFile
import com.bontecou.syncmd.ui.components.RevertConfirmDialog
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.util.AdaptiveInfo
import com.bontecou.syncmd.ui.util.HingeAwareColumn
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.BranchViewModel
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel
import com.bontecou.syncmd.ui.viewmodels.VaultSyncViewModel

/**
 * Half-open foldable, fold across the window (board FoldTabletop): no rail. Above the fold the
 * diff with "2 of 3" and previous / next file; below it the commit form inline.
 */
@Composable
fun TabletopVault(
    info: AdaptiveInfo,
    repositoryPath: String,
    onOpenEditor: (String) -> Unit,
    syncViewModel: VaultSyncViewModel?,
    diffVM: DiffViewModel = hiltViewModel(),
) {
    LaunchedEffect(repositoryPath) { diffVM.refreshRepositoryPath(repositoryPath); syncViewModel?.setRepositoryPath(repositoryPath) }
    val changes by diffVM.diff.collectAsState()
    val paths = changes?.files?.map { it.filePath }?.distinct()?.sorted().orEmpty()
    var current by rememberSaveable(repositoryPath) { mutableStateOf<String?>(null) }
    val shown = current?.takeIf { it in paths } ?: paths.firstOrNull()
    val c = M3.colors
    HingeAwareColumn(
        info,
        Modifier.background(c.surface),
        top = {
            Box(Modifier.fillMaxSize().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                if (shown == null) DetailPlaceholder()
                else DiffPane(repositoryPath, shown, wide = false, onOpenEditor = onOpenEditor, onDiscarded = { current = it },
                    style = DiffPaneStyle.Tabletop, onSelectFile = { current = it }, diffVM = diffVM)
            }
        },
        bottom = {
            Box(Modifier.fillMaxSize().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                InlineCommitForm(repositoryPath, syncViewModel, diffVM = diffVM)
            }
        },
    )
}

/**
 * The commit sheet as an inline form (board FoldTabletop): "Commit 3 changes" with "main → origin",
 * the message beside the Include checklist, then Discard, Commit only and "Commit & push ↑3".
 * Same logic as [com.bontecou.syncmd.ui.components.CommitSheet]: checked = staged through
 * [DiffViewModel]; Commit & push runs as a vault sync when [syncViewModel] is there.
 */
@Composable
fun InlineCommitForm(
    repositoryPath: String,
    syncViewModel: VaultSyncViewModel?,
    modifier: Modifier = Modifier,
    diffVM: DiffViewModel = hiltViewModel(),
    branchVM: BranchViewModel = hiltViewModel(),
) {
    LaunchedEffect(repositoryPath) { diffVM.refreshRepositoryPath(repositoryPath); branchVM.setRepositoryPath(repositoryPath) }
    val diff by diffVM.diff.collectAsState()
    val message by diffVM.commitMessage.collectAsState()
    val isLoading by diffVM.isLoading.collectAsState()
    val isCommitting by diffVM.isCommitting.collectAsState()
    val isPushing by diffVM.isPushing.collectAsState()
    val error = diffVM.errorMessage.collectAsState().value?.asString()
    val review by diffVM.discardReview.collectAsState()
    val branch = branchVM.state.collectAsState().value.currentBranch?.name
    val overview = syncViewModel?.overview?.collectAsState()?.value
    val running = syncViewModel?.run?.collectAsState()?.value?.running == true
    val files = diff?.files.orEmpty().groupBy { it.filePath }.map { (path, entries) ->
        CommitFile(path, entries.any { it.isStaged }, entries.any { it.status == DiffStatus.DELETED }, entries.any { it.status == DiffStatus.ADDED })
    }.sortedBy { it.path }
    val counts: Map<String, FileLineCount> = overview?.lineCounts ?: diff?.files.orEmpty().groupBy { it.filePath }.mapValues { (_, entries) ->
        FileLineCount(
            entries.sumOf { f -> f.hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.ADDITION } } },
            entries.sumOf { f -> f.hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.DELETION } } },
            entries.all { it.isBinary },
        )
    }
    // The board opens with every change included: stage all once when nothing is staged yet.
    // A caller that staged a selection first (e.g. "Commit this file") keeps that selection.
    var includedAll by rememberSaveable(repositoryPath) { mutableStateOf(false) }
    LaunchedEffect(diff, isLoading) {
        val loaded = diff ?: return@LaunchedEffect
        if (!includedAll && !isLoading && loaded.files.isNotEmpty()) {
            includedAll = true
            if (loaded.files.none { it.isStaged }) diffVM.stageAll()
        }
    }
    val staged = files.count { it.staged }
    val canCommit = staged > 0 && message.isNotBlank() && !isLoading && !running
    InlineCommitFormContent(
        files = files,
        counts = counts,
        branch = branch ?: overview?.branch,
        // Unknown until the overview loads; a loaded overview without a push remote is local-only.
        remote = overview?.remote,
        localOnly = overview != null && overview.remote == null,
        message = message,
        onMessage = diffVM::setCommitMessage,
        onToggle = { file, on -> if (on) diffVM.stageFile(file.path) else diffVM.unstageFile(file.path) },
        enabled = !isLoading,
        canCommit = canCommit,
        canPush = canCommit && (branch != null || syncViewModel != null),
        committing = isCommitting && !isPushing,
        pushing = isPushing,
        onDiscard = { diffVM.requestDiscard(null) },
        onCommitOnly = { diffVM.commitOnly() },
        onCommitAndPush = {
            if (syncViewModel != null) { syncViewModel.commitAndPush(message.trim(), staged); diffVM.setCommitMessage("") }
            else branch?.let { diffVM.commitAndPush(it) }
        },
        error = error,
        onDismissError = diffVM::clearError,
        modifier = modifier,
    )
    review?.takeIf { it.filePath == null }?.let { r ->
        RevertConfirmDialog(r, onConfirmDiscard = { stash -> diffVM.confirmDiscard(r, stashFirst = stash) }, onDismiss = { diffVM.dismissDiscard(r) })
    }
}

/** Stateless inline commit form. */
@Composable
internal fun InlineCommitFormContent(
    files: List<CommitFile>,
    counts: Map<String, FileLineCount>,
    branch: String?,
    remote: String?,
    message: String,
    onMessage: (String) -> Unit,
    onToggle: (CommitFile, Boolean) -> Unit,
    enabled: Boolean,
    canCommit: Boolean,
    canPush: Boolean,
    committing: Boolean,
    pushing: Boolean,
    onDiscard: () -> Unit,
    onCommitOnly: () -> Unit,
    onCommitAndPush: () -> Unit,
    modifier: Modifier = Modifier,
    /** True once the vault is known to have no push remote (shown instead of "→ remote"). */
    localOnly: Boolean = false,
    error: String? = null,
    onDismissError: () -> Unit = {},
) {
    val c = M3.colors
    val staged = files.count { it.staged }
    Column(
        modifier.fillMaxSize().background(c.cLow, RoundedCornerShape(24.dp)).padding(horizontal = 20.dp, vertical = 18.dp).testTag("tabletop-commit"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(pluralStringResource(R.plurals.adaptive_commit_changes, staged, staged), Modifier.weight(1f),
                style = M3Type.title.copy(fontSize = 20.sp), color = c.on)
            if (branch != null) Text(when {
                    remote != null -> stringResource(R.string.adaptive_branch_to_remote, branch, remote)
                    localOnly -> stringResource(R.string.adaptive_branch_local_only, branch)
                    else -> branch
                },
                Modifier.background(c.sc, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                style = M3Type.metaStrong.copy(textDirection = TextDirection.Ltr), color = c.onSc, maxLines = 1)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.adaptive_commit_message), style = M3Type.caption.copy(fontWeight = FontWeight.Medium), color = c.onVar)
                val shape = RoundedCornerShape(12.dp)
                BasicTextField(
                    value = message, onValueChange = onMessage, enabled = enabled,
                    textStyle = M3Type.body.copy(fontSize = 15.sp, lineHeight = 22.sp, color = c.on),
                    cursorBrush = SolidColor(c.primary),
                    modifier = Modifier.weight(1f).fillMaxWidth().background(c.cLowest, shape).border(1.dp, c.outline, shape)
                        .padding(horizontal = 14.dp, vertical = 12.dp).testTag("tabletop-message"),
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text(stringResource(R.string.adaptive_include), Modifier.padding(bottom = 6.dp),
                    style = M3Type.caption.copy(fontWeight = FontWeight.Medium), color = c.onVar)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    files.forEach { file ->
                        val count = counts[file.path]
                        val (letter, tone) = when { file.deleted -> "D" to M3Tone.Warn; file.added -> "A" to M3Tone.Ok; else -> "M" to M3Tone.Ahead }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                .toggleable(file.staged, enabled = enabled, role = Role.Checkbox) { onToggle(file, it) }
                                .testTag("tabletop-include-${file.path}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Checkbox(file.staged, onCheckedChange = null, enabled = enabled,
                                colors = CheckboxDefaults.colors(checkedColor = c.primary, uncheckedColor = c.onVar, checkmarkColor = c.onPrimary))
                            ChangeLetter(letter, tone, size = 24.dp, radius = 8.dp, fontSize = 12)
                            Text(nameOf(file.path), Modifier.weight(1f), style = M3Type.bodyMedium, color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (count != null && !count.binary) ChangeDelta(count.added, count.removed)
                        }
                    }
                }
            }
        }
        if (error != null) M3Note(error, tone = M3NoteTone.Warn, action = {
            M3Button(stringResource(R.string.vault_m3_dismiss), onDismissError, style = M3ButtonStyle.OutlinedContent, size = M3ButtonSize.XSmall)
        })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            M3TextButton(stringResource(R.string.adaptive_discard_all), onDiscard, Modifier.testTag("tabletop-discard"),
                enabled = enabled && files.isNotEmpty(), destructive = true)
            Spacer(Modifier.weight(1f))
            M3Button(stringResource(if (committing) R.string.tide_git_committing else R.string.adaptive_commit_only), onCommitOnly,
                Modifier.testTag("tabletop-commit-only"), style = M3ButtonStyle.Outlined, size = M3ButtonSize.Large, enabled = canCommit)
            M3Button(stringResource(R.string.vault_m3_commit_push), onCommitAndPush, Modifier.testTag("tabletop-commit-push"),
                style = M3ButtonStyle.Filled, size = M3ButtonSize.Large, trailingMeta = if (staged > 0) "↑$staged" else null,
                enabled = canPush, loading = pushing)
        }
    }
}

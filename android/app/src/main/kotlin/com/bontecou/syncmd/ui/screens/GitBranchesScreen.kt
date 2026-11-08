package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Branch
import com.bontecou.syncmd.services.git.GitBranchDetail
import com.bontecou.syncmd.ui.components.BranchRow
import com.bontecou.syncmd.ui.components.CantSwitchDialog
import com.bontecou.syncmd.ui.components.DeleteBranchDialog
import com.bontecou.syncmd.ui.components.DirtyPath
import com.bontecou.syncmd.ui.components.GitDestination
import com.bontecou.syncmd.ui.components.GitPage
import com.bontecou.syncmd.ui.components.GitToneHero
import com.bontecou.syncmd.ui.components.MergeBranchSheet
import com.bontecou.syncmd.ui.components.RemoteBranchRow
import com.bontecou.syncmd.ui.components.commitLineText
import com.bontecou.syncmd.ui.components.gitRemoteName
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3TextField
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.util.singleLineNameInput
import com.bontecou.syncmd.ui.viewmodels.BranchViewModel
import com.bontecou.syncmd.ui.viewmodels.ConflictViewModel
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel
import com.bontecou.syncmd.ui.viewmodels.GitWorkspaceViewModel
import com.bontecou.syncmd.ui.viewmodels.PullViewModel

/**
 * Branches (Material 3 board GitBranches): the current branch with its upstream counts, a New
 * branch field, every local branch with its own ahead/behind and Switch / Merge / Delete, and the
 * branches only on the remote. Switching with uncommitted changes opens "Can't switch yet".
 */
@Composable
fun GitBranchesScreen(
    path: String,
    vaultName: String,
    onBack: () -> Unit,
    onNavigate: (GitDestination) -> Unit,
    branchVM: BranchViewModel = hiltViewModel(),
    diffVM: DiffViewModel = hiltViewModel(),
    pullVM: PullViewModel = hiltViewModel(),
    conflictVM: ConflictViewModel = hiltViewModel(),
    workspaceVM: GitWorkspaceViewModel = hiltViewModel(),
) {
    fun reload() { branchVM.loadBranches(); diffVM.loadDiff(); workspaceVM.load(path) }
    LaunchedEffect(path) {
        branchVM.setRepositoryPath(path); diffVM.setRepositoryPath(path); pullVM.setRepositoryPath(path); conflictVM.setRepositoryPath(path)
        workspaceVM.load(path)
    }
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(path) { reload() }
    val state by branchVM.state.collectAsState()
    val workspace by workspaceVM.state.collectAsState()
    val summary = workspace.summary?.takeIf { workspace.path == path }
    val diff by diffVM.diff.collectAsState()
    val pullBusy by pullVM.isLoading.collectAsState()
    val pullSelection by pullVM.selection.collectAsState()
    val pullContext = pullSelection?.takeIf { it.path == path }
    val mergeState by conflictVM.mergeState.collectAsState()
    LaunchedEffect(state.operationRevision) { if (state.operationRevision > 0) { workspaceVM.load(path); diffVM.loadDiff(); pullVM.loadStatus() } }
    val fetchBusy = pullBusy
    LaunchedEffect(fetchBusy) { if (!fetchBusy) workspaceVM.load(path) }

    val context = state.selection?.takeIf { it.path == path }
    val canAct = context != null && state.actionsReady && !state.isLoading
    val current = summary?.locals?.firstOrNull { it.isHead }
    val currentName = current?.name ?: state.currentBranch?.name ?: "—"
    val dirty = diff?.files.orEmpty().distinctBy { it.filePath }.map { DirtyPath(it.filePath, it.status) }
    fun branchFor(detail: GitBranchDetail): Branch? = state.localBranches.firstOrNull { it.name == detail.name }

    var newName by rememberSaveable(path) { mutableStateOf("") }
    var merging by remember { mutableStateOf<GitBranchDetail?>(null) }
    var deleting by remember { mutableStateOf<GitBranchDetail?>(null) }
    var blocked by remember { mutableStateOf<GitBranchDetail?>(null) }
    var awaitingMerge by remember { mutableStateOf(false) }
    LaunchedEffect(awaitingMerge, mergeState) {
        if (awaitingMerge && mergeState?.isMergeInProgress == true) { awaitingMerge = false; onNavigate(GitDestination.Conflicts) }
    }

    GitPage(
        title = stringResource(R.string.git_m3_branches),
        subtitle = stringResource(R.string.git_m3_subtitle, vaultName, currentName),
        onBack = onBack,
        modifier = Modifier.testTag("git-branches-screen"),
        scrollTag = "git-branches",
        actions = { M3IconButton(R.drawable.ic_m3_refresh, stringResource(R.string.git_m3_refresh_branches), { reload() }, enabled = !state.isLoading) },
    ) {
        val c = M3.colors
        GitToneHero(c.pc, c.onPc, padding = 0.dp) {
            androidx.compose.foundation.layout.Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) {
                Text(stringResource(R.string.git_m3_current_branch), style = M3Type.badge)
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    M3Icon(R.drawable.ic_m3_branch, size = 22.dp)
                    Text(currentName, style = M3Type.branchHeadline, maxLines = 1)
                }
                current?.let { head ->
                    Text(buildAnnotatedString {
                        if (head.upstream != null) {
                            append(stringResource(R.string.git_m3_tracking, head.upstream!!)); append(" · ")
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(stringResource(R.string.git_m3_ahead, head.ahead ?: 0)) }
                            append(" · "); append(stringResource(R.string.git_m3_behind, head.behind ?: 0))
                        } else append(stringResource(R.string.git_m3_local_only))
                    }, Modifier.padding(top = 6.dp), style = M3Type.meta)
                    commitLineText(head.last).takeIf { it.isNotEmpty() }?.let { Text(it, Modifier.padding(top = 2.dp), style = M3Type.meta, maxLines = 1) }
                }
            }
        }

        M3SectionLabel(stringResource(R.string.git_m3_new_branch))
        M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val canCreate = canAct && newName.isNotBlank() && state.currentBranch?.lastCommit != null
                    val create = {
                        context?.let { expected -> val submitted = newName; branchVM.createBranch(expected, submitted.trim()) { if (newName == submitted) newName = "" } }
                        Unit
                    }
                    M3TextField(newName, { newName = singleLineNameInput(it) }, stringResource(R.string.git_m3_branch_name), Modifier.weight(1f).testTag("git-branch-name"),
                        placeholder = stringResource(R.string.git_m3_branch_placeholder), mono = true, enabled = canAct,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (canCreate) create() }))
                    M3Button(stringResource(R.string.git_m3_create), create, Modifier.padding(top = 8.dp).testTag("git-branch-create"),
                        size = M3ButtonSize.Hero, icon = R.drawable.ic_m3_add, enabled = canCreate)
                }
                current?.last?.let {
                    Text(stringResource(R.string.git_m3_starts_from, it.shortId, currentName), Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
                        style = M3Type.meta, color = c.onVar)
                }
            }
        }

        val locals = summary?.locals.orEmpty()
        M3SectionLabel(stringResource(R.string.git_m3_on_this_phone), count = locals.size)
        if (dirty.isNotEmpty()) M3Note(pluralStringResource(R.plurals.git_m3_dirty_note, dirty.size, dirty.size),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), tone = M3NoteTone.Ahead)
        if (locals.isNotEmpty()) M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            locals.forEachIndexed { index, branch ->
                BranchRow(branch, currentName, divider = index > 0, enabled = canAct && branchFor(branch) != null,
                    onSwitch = {
                        if (dirty.isNotEmpty()) blocked = branch
                        else { val b = branchFor(branch); if (context != null && b != null) branchVM.switchBranch(context, b) }
                    },
                    onMerge = { merging = branch },
                    onDelete = { deleting = branch })
            }
        }

        val remote = summary?.remoteOnly.orEmpty()
        if (remote.isNotEmpty()) {
            M3SectionLabel(stringResource(R.string.git_m3_only_on, gitRemoteName(summary?.remoteUrl)), count = remote.size)
            M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                remote.forEachIndexed { index, branch -> RemoteBranchRow(branch, divider = index > 0) }
            }
        }
        if (summary?.remoteName != null) Row(Modifier.padding(start = 12.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            com.bontecou.syncmd.ui.theme.M3TextButton(stringResource(R.string.git_fetch), { pullContext?.let(pullVM::fetch) },
                size = M3ButtonSize.XSmall, enabled = pullContext != null && !pullBusy)
            Text(stringResource(R.string.git_m3_fetch_hint), style = M3Type.bodySmall, color = c.onVar)
        }
    }

    merging?.let { initial ->
        val sources = summary?.locals.orEmpty().filterNot { it.isHead }
        MergeBranchSheet(vaultName, currentName, sources.ifEmpty { listOf(initial) }, initial, busy = state.isMerging,
            onMerge = { source, strategy ->
                val b = branchFor(source)
                if (context != null && b != null) branchVM.mergeBranch(context, b, strategy) {
                    merging = null; awaitingMerge = true; conflictVM.loadConflicts()
                }
            },
            onDismiss = { merging = null })
    }
    deleting?.let { branch ->
        DeleteBranchDialog(branch.name, busy = state.isLoading && !state.actionsReady, onDelete = { force ->
            val b = branchFor(branch)
            if (context != null && b != null) branchVM.deleteBranch(context, b, force) { deleting = null }
            if (b == null) deleting = null
        }, onDismiss = { deleting = null })
    }
    blocked?.let { target ->
        CantSwitchDialog(target.name, currentName, dirty,
            onStash = { blocked = null; onNavigate(GitDestination.Stashes) },
            onCommit = { blocked = null; onNavigate(GitDestination.Changes) },
            onDismiss = { blocked = null })
    }
    state.errorMessage?.takeIf { context != null }?.asString()?.let { message ->
        M3Dialog(onDismissRequest = { branchVM.clearError(context) }, title = stringResource(R.string.tide_git_branch_operation),
            content = { Text(message, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("git-operation-message"), style = M3Type.bodyMedium) }) {
            M3DialogAction(stringResource(R.string.ui_ok_565339bc), { branchVM.clearError(context) })
        }
    }
}

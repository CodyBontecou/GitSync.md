package com.bontecou.syncmd.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.GitActionRow
import com.bontecou.syncmd.ui.components.GitDestination
import com.bontecou.syncmd.ui.components.GitHeroTitle
import com.bontecou.syncmd.ui.components.GitKicker
import com.bontecou.syncmd.ui.components.GitPage
import com.bontecou.syncmd.ui.components.GitToneHero
import com.bontecou.syncmd.ui.components.RevertConfirmDialog
import com.bontecou.syncmd.ui.components.VaultTab
import com.bontecou.syncmd.ui.components.gitAheadBehind
import com.bontecou.syncmd.ui.components.gitFolderTrail
import com.bontecou.syncmd.ui.components.gitRemoteDisplay
import com.bontecou.syncmd.ui.components.gitRemoteOwner
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3Group
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3OutlinedButton
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.M3VaultNavigationBar
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.ConflictViewModel
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel
import com.bontecou.syncmd.ui.viewmodels.GitWorkspaceViewModel
import com.bontecou.syncmd.ui.viewmodels.PullViewModel

/**
 * Git tab (Material 3 board 05): a summary. The current-branch card with Switch and the upstream
 * counts, then Workspace (Branches, Stashes, Tags, Large files) and Connection (Remote, Commit as,
 * Saved in) rows that open their own screens, and the Discard changes / Stop tracking… buttons.
 * A merge or rebase in progress shows on top so its resolution stays one tap away.
 */
@Composable
fun GitScreen(
    repositoryPath: String,
    showDebugInfo: Boolean = false,
    onNavigateBack: () -> Unit,
    onOpenDiff: (String, Boolean) -> Unit = { _, _ -> },
    onOpenRebase: () -> Unit = {},
    onOpenRemoteMerge: () -> Unit = {},
    onOpenLfs: () -> Unit = {},
    repositoryName: String? = null,
    repositoryOwner: String? = null,
    onNavigate: ((GitDestination) -> Unit)? = null,
    pullVM: PullViewModel = hiltViewModel(),
    diffVM: DiffViewModel = hiltViewModel(),
    conflictVM: ConflictViewModel = hiltViewModel(),
    workspaceVM: GitWorkspaceViewModel = hiltViewModel(),
    showNavigation: Boolean = false,
    onSelectTab: (VaultTab) -> Unit = {},
) {
    val navigate: (GitDestination) -> Unit = onNavigate ?: { destination ->
        when (destination) {
            GitDestination.LargeFiles -> onOpenLfs()
            GitDestination.Rebase -> onOpenRebase()
            GitDestination.Remote -> onOpenRemoteMerge()
            else -> Unit
        }
    }
    fun refresh() {
        pullVM.loadStatus(); diffVM.loadDiff(); conflictVM.loadConflicts()
        workspaceVM.load(repositoryPath); workspaceVM.loadRebase(repositoryPath)
    }
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(repositoryPath) {
        pullVM.setRepositoryPath(repositoryPath); diffVM.refreshRepositoryPath(repositoryPath); conflictVM.setRepositoryPath(repositoryPath)
        workspaceVM.load(repositoryPath); workspaceVM.loadRebase(repositoryPath)
    }
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) {
            pullVM.setRepositoryPath(repositoryPath); diffVM.setRepositoryPath(repositoryPath); conflictVM.setRepositoryPath(repositoryPath)
            workspaceVM.load(repositoryPath); workspaceVM.loadRebase(repositoryPath)
        }
    }
    val operationRevision by diffVM.operationRevision.collectAsState()
    LaunchedEffect(repositoryPath, operationRevision) { if (operationRevision > 0 && repositoryPath.isNotBlank()) { pullVM.loadStatus(); workspaceVM.load(repositoryPath) } }
    DisposableEffect(repositoryPath) { onDispose { diffVM.dismissDiscard() } }

    val context = LocalContext.current
    val workspace by workspaceVM.state.collectAsState()
    val summary = workspace.summary?.takeIf { workspace.path == repositoryPath }
    val diff by diffVM.diff.collectAsState()
    val changeCount = diff?.summary?.filesChanged ?: 0
    val pullBusy by pullVM.isLoading.collectAsState()
    val pullSelection by pullVM.selection.collectAsState()
    val pullContext = pullSelection?.takeIf { it.path == repositoryPath }
    val pullError = pullVM.errorText.collectAsState().value?.asString()
    val pullAttention by pullVM.attention.collectAsState()
    val status by pullVM.status.collectAsState()
    val mergeState by conflictVM.mergeState.collectAsState()
    val conflicts by conflictVM.conflicts.collectAsState()
    val conflictSelection by conflictVM.selection.collectAsState()
    val conflictReady by conflictVM.actionsReady.collectAsState()
    val conflictContext = conflictSelection?.takeIf { it.path == repositoryPath }
    val mergeActive = conflictContext != null && mergeState?.isMergeInProgress == true
    val discardReview by diffVM.discardReview.collectAsState()
    val reviewingDiscard by diffVM.isReviewingDiscard.collectAsState()
    val discarding by diffVM.isDiscarding.collectAsState()
    val diffError = diffVM.errorMessage.collectAsState().value?.asString()

    val vaultName = repositoryName?.takeIf { it.isNotBlank() } ?: repositoryPath.substringAfterLast('/')
    val branchName = summary?.branch ?: status?.currentBranch?.takeUnless { it.length == 40 }
    val slug = repositoryOwner?.takeIf { it.isNotBlank() }?.let { "$it/$vaultName" }
        ?: gitRemoteDisplay(summary?.remoteUrl)?.substringAfter('/', "")?.takeIf { it.isNotBlank() }
    val subtitle = listOfNotNull(branchName ?: summary?.head?.shortId, slug).joinToString(" · ").ifBlank { null }

    GitPage(
        title = vaultName,
        subtitle = subtitle,
        onBack = onNavigateBack,
        modifier = Modifier.testTag(if (summary != null && !workspace.loading && !pullBusy) "git-history-ready" else "git-history-loading"),
        scrollTag = "git-actions",
        actions = {
            M3IconButton(R.drawable.ic_m3_sync, stringResource(R.string.git_m3_check_changes), { pullContext?.let(pullVM::fetch) },
                Modifier.testTag("git-fetch"), enabled = pullContext != null && !pullBusy)
            M3IconButton(R.drawable.ic_m3_more_vert, stringResource(R.string.git_m3_vault_settings), { navigate(GitDestination.VaultSettings) })
        },
        bottomBar = if (showNavigation) ({ M3VaultNavigationBar(VaultTab.GIT, onSelectTab, changes = changeCount.takeIf { it > 0 }) }) else null,
        bottomSpace = 96.dp,
    ) {
        Box(Modifier.fillMaxWidth().height(0.dp).testTag(if (mergeActive && conflictReady) "git-merge-active" else "git-merge-inactive"))
        if (mergeActive) {
            GitToneHero(M3.colors.ec, M3.colors.onEc) {
                GitKicker(stringResource(R.string.git_m3_merge_in_progress))
                GitHeroTitle(if (conflicts.isEmpty()) stringResource(R.string.git_m3_merge_ready)
                    else pluralStringResource(R.plurals.git_m3_conflicts_left, conflicts.size, conflicts.size))
                M3Button(stringResource(R.string.git_m3_resolve), { navigate(GitDestination.Conflicts) },
                    Modifier.padding(top = 16.dp).testTag("git-open-conflicts"), style = M3ButtonStyle.Filled, size = M3ButtonSize.Medium)
            }
        } else workspace.rebase?.let { rebase ->
            GitToneHero(M3.colors.tc, M3.colors.onTc) {
                GitKicker(stringResource(R.string.git_m3_rebase_in_progress))
                val step = rebase.step; val total = rebase.total
                GitHeroTitle(if (step != null && total != null) stringResource(R.string.git_m3_rebase_step, step, total)
                    else stringResource(R.string.git_m3_rebase_stopped))
                M3Button(stringResource(R.string.git_m3_open_rebase), { navigate(GitDestination.Rebase) },
                    Modifier.padding(top = 16.dp).testTag("open-rebase"), style = M3ButtonStyle.Filled, size = M3ButtonSize.Medium)
            }
        }

        BranchCard(
            branch = branchName ?: summary?.head?.shortId?.let { stringResource(R.string.git_m3_detached, it) } ?: "—",
            meta = when {
                summary == null -> null
                summary.upstream != null -> gitAheadBehind(stringResource(R.string.git_m3_vs, summary.upstream!!), summary.ahead ?: 0, summary.behind ?: 0)
                else -> AnnotatedString(stringResource(R.string.git_m3_no_upstream))
            },
            onSwitch = { navigate(GitDestination.Branches) },
        )

        pullAttention?.takeIf { pullContext != null }?.let { notice ->
            M3Note(notice, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("pull-lfs-attention"), tone = M3NoteTone.Info) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    M3TextButton(stringResource(R.string.ui_open_git_lfs_f7aff155), { navigate(GitDestination.LargeFiles) })
                    M3TextButton(stringResource(R.string.ui_dismiss_48845bff), { pullVM.clearAttention(pullContext) })
                }
            }
        }

        M3Group(stringResource(R.string.git_m3_workspace)) {
            GitActionRow(stringResource(R.string.git_m3_branches), R.drawable.ic_m3_branch, Modifier.testTag("git-open-branches"),
                value = summary?.locals?.size?.toString(), minHeight = 56.dp, onClick = { navigate(GitDestination.Branches) })
            GitActionRow(stringResource(R.string.git_m3_stashes), R.drawable.ic_m3_stash, Modifier.testTag("git-open-stashes"),
                value = summary?.stashCount?.toString(), minHeight = 56.dp, divider = true, onClick = { navigate(GitDestination.Stashes) })
            GitActionRow(stringResource(R.string.git_m3_tags), R.drawable.ic_m3_tag, Modifier.testTag("git-open-tags"),
                value = summary?.tagCount?.toString(), minHeight = 56.dp, divider = true, onClick = { navigate(GitDestination.Tags) })
            val lfs = workspace.lfs
            GitActionRow(stringResource(R.string.git_m3_lfs), R.drawable.ic_m3_storage, Modifier.testTag("open-lfs"),
                value = when {
                    lfs == null -> null
                    lfs.files == 0 -> stringResource(R.string.git_m3_lfs_none)
                    lfs.locked != null -> stringResource(R.string.git_m3_lfs_value, Formatter.formatShortFileSize(context, lfs.bytes), lfs.locked!!)
                    else -> Formatter.formatShortFileSize(context, lfs.bytes)
                },
                minHeight = 56.dp, divider = true, onClick = { navigate(GitDestination.LargeFiles) })
        }
        M3Group(stringResource(R.string.git_m3_connection)) {
            GitActionRow(stringResource(R.string.git_m3_remote), R.drawable.ic_m3_link, Modifier.testTag("git-open-remote"),
                value = summary?.let { gitRemoteOwner(it.remoteUrl) ?: stringResource(R.string.git_m3_none) }, minHeight = 56.dp,
                onClick = { navigate(GitDestination.Remote) })
            GitActionRow(stringResource(R.string.git_m3_commit_as), R.drawable.ic_m3_person, Modifier.testTag("git-open-author"),
                value = workspace.author ?: stringResource(R.string.git_m3_not_set), minHeight = 56.dp, divider = true,
                onClick = { navigate(GitDestination.Author) })
            GitActionRow(stringResource(R.string.git_m3_saved_in), R.drawable.ic_m3_folder, Modifier.testTag("git-open-folder"),
                value = gitFolderTrail(context, repositoryPath), minHeight = 56.dp, divider = true,
                onClick = {
                    if (!com.bontecou.syncmd.ui.util.FilesAppLauncher.openFolder(context, repositoryPath))
                        android.widget.Toast.makeText(context, context.getString(R.string.vault_open_in_files_failed), android.widget.Toast.LENGTH_SHORT).show()
                })
        }

        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            M3OutlinedButton(stringResource(R.string.git_m3_discard), { diffVM.requestDiscard() }, Modifier.weight(1f).testTag("git-discard"),
                destructive = true, enabled = changeCount > 0 && !reviewingDiscard && !discarding, loading = reviewingDiscard || discarding)
            M3OutlinedButton(stringResource(R.string.git_m3_stop_tracking), { navigate(GitDestination.ManageVault) },
                Modifier.weight(1f).testTag("git-stop-tracking"), destructive = true)
        }

        if (showDebugInfo) M3Group(stringResource(R.string.ui_debug_info_697b4941)) {
            GitActionRow(stringResource(R.string.ui_repo_path_a3f06032), R.drawable.ic_m3_bug_report, subtitle = repositoryPath, subtitleMono = true)
            GitActionRow(stringResource(R.string.ui_changes_bbd4b6a8), R.drawable.ic_m3_changes, value = changeCount.toString(), divider = true)
        }
    }

    discardReview?.takeIf { it.filePath == null }?.let { review ->
        RevertConfirmDialog(review, onConfirmDiscard = { diffVM.confirmDiscard(review, stashFirst = it) }, onDismiss = { diffVM.dismissDiscard(review) })
    }
    val error = pullError.takeIf { pullContext != null } ?: diffError
    if (error != null) {
        val clear = { pullVM.clearError(pullContext); diffVM.clearError() }
        M3Dialog(onDismissRequest = clear, title = stringResource(R.string.tide_git_git_operation), icon = R.drawable.ic_m3_warning, iconTint = M3.colors.error,
            content = {
                Text(error, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("git-operation-message"), style = M3Type.bodyMedium)
            }) { M3DialogAction(stringResource(R.string.ui_ok_565339bc), clear) }
    }
}

/** "Current branch" card on pc: mono 24/600 branch with Switch, then the upstream line. */
@Composable
private fun BranchCard(branch: String, meta: AnnotatedString?, onSwitch: () -> Unit) {
    val c = M3.colors
    GitToneHero(c.pc, c.onPc, padding = 0.dp) {
        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) {
            Text(stringResource(R.string.git_m3_current_branch), style = M3Type.badge)
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    M3Icon(R.drawable.ic_m3_branch, size = 22.dp)
                    Text(branch, Modifier.testTag("git-current-branch"), style = M3Type.branchHeadline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                M3Button(stringResource(R.string.git_m3_switch), onSwitch, Modifier.padding(start = 8.dp).testTag("git-switch-branch"),
                    style = M3ButtonStyle.Filled, size = M3ButtonSize.Medium)
            }
            if (meta != null) Text(meta, Modifier.padding(top = 6.dp), style = M3Type.meta)
        }
    }
}

@Preview(name = "Git tab · branch card · light")
@Composable
private fun GitBranchCardPreview() = SyncMdTheme(darkTheme = false) {
    Column { BranchCard("main", gitAheadBehind("vs origin/main", 3, 0), {}) }
}

@Preview(name = "Git tab · branch card · dark")
@Composable
private fun GitBranchCardDarkPreview() = SyncMdTheme(darkTheme = true) {
    Column { BranchCard("main", gitAheadBehind("vs origin/main", 3, 0), {}) }
}

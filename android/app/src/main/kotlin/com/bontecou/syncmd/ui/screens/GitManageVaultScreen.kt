package com.bontecou.syncmd.ui.screens

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.GitActionRow
import com.bontecou.syncmd.ui.components.GitDestination
import com.bontecou.syncmd.ui.components.GitHeroTitle
import com.bontecou.syncmd.ui.components.GitKicker
import com.bontecou.syncmd.ui.components.GitPage
import com.bontecou.syncmd.ui.components.GitToneHero
import com.bontecou.syncmd.ui.components.gitFolderTrail
import com.bontecou.syncmd.ui.components.gitRemoteDisplay
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.util.displayIdentity
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel
import com.bontecou.syncmd.ui.viewmodels.GitWorkspaceViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import com.bontecou.syncmd.ui.viewmodels.TrackingRemovalRequest
import java.io.File

/**
 * Manage vault (Material 3 board StopTracking): the vault card, then Open, Edit connection, Move
 * repository, Remove from app, Remove Git tracking and Delete local files. Removing tracking runs
 * the review sheet and the final consent, then shows "VaultName is a plain folder now." with the
 * backup to share and the folder to review.
 */
@Composable
fun GitManageVaultScreen(
    path: String,
    settings: SettingsViewModel,
    onBack: () -> Unit,
    onOpenVault: () -> Unit,
    onNavigate: (GitDestination) -> Unit,
    onRemoved: () -> Unit,
    workspaceVM: GitWorkspaceViewModel = hiltViewModel(),
    diffVM: DiffViewModel = hiltViewModel(),
) {
    LaunchedEffect(path) { workspaceVM.load(path); diffVM.setRepositoryPath(path) }
    val repositories by settings.allRepositories.collectAsState()
    val history by settings.repositoryHistory.collectAsState()
    val plan by settings.trackingPlan.collectAsState()
    val busy by settings.isLoading.collectAsState()
    val destructive by settings.destructiveBusy.collectAsState()
    val error by settings.errorMessage.collectAsState()
    val operationMessage by settings.operationMessage.collectAsState()
    val deletion by settings.deletionReview.collectAsState()
    val workspace by workspaceVM.state.collectAsState()
    val diff by diffVM.diff.collectAsState()
    val summary = workspace.summary?.takeIf { workspace.path == path }
    val repo = repositories.firstOrNull { it.path == path }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var keepBackup by remember(plan?.operationId) { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<TrackingRemovalRequest?>(null) }
    var removing by rememberSaveable(path) { mutableStateOf(false) }
    var done by rememberSaveable(path) { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val doneEntry = history.firstOrNull { it.repository.path == path && it.trackingRemoved }
    val name = repo?.displayIdentity()?.name?.takeIf { it.isNotBlank() } ?: doneEntry?.repository?.name ?: path.substringAfterLast('/')
    BackHandler(destructive) {}
    DisposableEffect(settings) { onDispose { settings.clearRepositoryDeletion(); if (!destructive) settings.clearTrackingPlan() } }
    LaunchedEffect(removing, busy, destructive, plan, error) {
        if (removing && !busy && !destructive && plan == null) {
            removing = false
            if (error == null) {
                done = true
                settings.clearOperationMessage()
                snackbar.showSnackbar(context.getString(if (keepBackup) R.string.git_m3_tracking_removed_backup_toast else R.string.tracking_removed_files_kept))
            }
        }
    }
    // Remove from app / Delete local files: leave once the vault is gone from the list.
    LaunchedEffect(leaving, repo, busy) { if (leaving && repo == null && !busy) onRemoved() }
    LaunchedEffect(operationMessage) { if (!removing && !done) operationMessage?.let { settings.clearOperationMessage() } }

    GitPage(
        title = stringResource(R.string.git_m3_manage_vault),
        subtitle = if (done) stringResource(R.string.git_m3_not_tracked, name) else stringResource(R.string.git_m3_subtitle, name, summary?.branch ?: "—"),
        onBack = { if (!destructive) onBack() },
        modifier = Modifier.testTag("git-manage-screen"),
        snackbar = snackbar,
    ) {
        val c = M3.colors
        if (!done) {
            M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(name, style = M3Type.title, color = c.on)
                    val slug = repo?.displayIdentity()?.let { id -> id.owner?.takeIf { it.isNotBlank() }?.let { "$it/${id.name}" } }
                        ?: gitRemoteDisplay(summary?.remoteUrl)?.substringAfter('/', "")?.takeIf { it.isNotBlank() }
                    Text(listOfNotNull(summary?.branch, slug, summary?.ahead?.takeIf { it > 0 }?.let { "↑$it" }).joinToString(" · "),
                        Modifier.padding(top = 2.dp), style = M3Type.meta, color = c.onVar)
                    Text(gitFolderTrail(context, path), Modifier.padding(top = 2.dp), style = M3Type.meta, color = c.onVar)
                }
            }
            M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                val enabled = !busy && !destructive
                GitActionRow(stringResource(R.string.git_m3_open), R.drawable.ic_m3_open_in_new, subtitle = stringResource(R.string.git_m3_open_sub),
                    chevron = true, minHeight = 56.dp, enabled = enabled, onClick = onOpenVault)
                GitActionRow(stringResource(R.string.git_m3_edit_connection), R.drawable.ic_m3_link, divider = true, minHeight = 56.dp,
                    subtitle = listOfNotNull(summary?.remoteName, gitRemoteDisplay(summary?.remoteUrl)?.substringBefore('/')).joinToString(" · ").ifBlank { null },
                    chevron = true, enabled = enabled, onClick = { onNavigate(GitDestination.Connection) })
                GitActionRow(stringResource(R.string.git_m3_move_repository), R.drawable.ic_m3_folder, divider = true, minHeight = 56.dp,
                    subtitle = stringResource(R.string.git_m3_move_sub), chevron = true, enabled = enabled, onClick = { onNavigate(GitDestination.MoveRepository) })
                GitActionRow(stringResource(R.string.git_m3_remove_from_app), R.drawable.ic_m3_close, divider = true, minHeight = 56.dp,
                    subtitle = stringResource(R.string.git_m3_remove_from_app_sub), enabled = enabled && repo != null, onClick = { confirmRemove = true })
                GitActionRow(stringResource(R.string.ui_remove_git_tracking_d3e1065f), R.drawable.ic_m3_link_off, Modifier.testTag("git-remove-tracking"), divider = true,
                    minHeight = 56.dp, subtitle = stringResource(R.string.git_m3_remove_tracking_sub), titleColor = c.error, iconTint = c.error, subtitleColor = c.error,
                    enabled = enabled, onClick = { settings.prepareTrackingRemoval(path) })
                if (repo?.managedStorage == true) GitActionRow(stringResource(R.string.ui_delete_local_files_20424441), R.drawable.ic_m3_delete, divider = true,
                    minHeight = 56.dp, subtitle = stringResource(R.string.git_m3_delete_files_sub), titleColor = c.error, iconTint = c.error, subtitleColor = c.error,
                    enabled = enabled, onClick = { settings.prepareRepositoryDeletion(path) })
            }
        } else {
            GitToneHero(c.okc, c.onOkc, Modifier.testTag("tracking-removed")) {
                GitKicker(stringResource(R.string.git_m3_tracking_removed))
                GitHeroTitle(stringResource(R.string.git_m3_plain_folder, name))
                Text(stringResource(R.string.git_m3_plain_folder_body), Modifier.padding(top = 8.dp), style = M3Type.bodyMedium)
            }
            M3SectionLabel(stringResource(R.string.git_m3_previously_added))
            M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(name, style = M3Type.bodyStrong, color = c.on)
                    Text(stringResource(R.string.git_m3_tracking_removed_kept), Modifier.padding(top = 2.dp), style = M3Type.bodyMedium, color = c.onVar)
                    doneEntry?.backupDirectory?.let { Text(stringResource(R.string.git_m3_backup_at, gitFolderTrail(context, it)), Modifier.padding(top = 6.dp), style = M3Type.meta, color = c.onVar) }
                }
                doneEntry?.backupArchive?.let { archive ->
                    GitActionRow(stringResource(R.string.ui_share_backup_archive_ee4ba873), R.drawable.ic_m3_share, divider = true, minHeight = 56.dp, onClick = {
                        runCatching {
                            val file = File(archive)
                            require(file.isFile && file.name == "git-metadata.zip") { context.getString(R.string.vault_l10n_backup_unavailable) }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            val intent = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            intent.clipData = ClipData.newUri(context.contentResolver, context.getString(R.string.vault_l10n_backup_clip_label), uri)
                            context.startActivity(Intent.createChooser(intent, context.getString(R.string.vault_l10n_backup_share_title)))
                        }.onFailure { settings.reportError(it.message ?: context.getString(R.string.vault_l10n_backup_share_failed)) }
                    })
                }
                GitActionRow(stringResource(R.string.git_m3_review_folder), R.drawable.ic_m3_folder, divider = true, minHeight = 56.dp, chevron = true, onClick = {
                    if (!com.bontecou.syncmd.ui.util.FilesAppLauncher.openFolder(context, path))
                        android.widget.Toast.makeText(context, context.getString(R.string.vault_open_in_files_failed), android.widget.Toast.LENGTH_SHORT).show()
                })
            }
        }
    }

    plan?.takeIf { it.path == path || File(it.path).canonicalPath == File(path).canonicalPath }?.let { review ->
        val facts = summary?.let { s ->
            TrackingFacts(s.trackedFiles, diff?.files.orEmpty().distinctBy { it.filePath }.size, s.locals.size, s.stashCount, s.tagCount, s.commitCount, s.remoteName)
        }
        if (confirm == null) TrackingRemovalReview(review, keepBackup, busy = busy || destructive, onKeepBackup = { keepBackup = it },
            onContinue = { confirm = TrackingRemovalRequest(review, keepBackup) }, onCancel = settings::clearTrackingPlan, facts = facts)
        confirm?.let { pending ->
            TrackingRemovalConfirmation(pending, valid = review === pending.plan, busy = busy || destructive,
                onCancel = { confirm = null },
                onConfirm = { confirm = null; removing = true; settings.removeTracking(pending) })
        }
    }
    if (confirmRemove) M3Dialog(onDismissRequest = { confirmRemove = false }, title = stringResource(R.string.git_m3_remove_from_app_title, name),
        text = stringResource(R.string.git_m3_remove_from_app_body)) {
        M3DialogAction(stringResource(R.string.git_m3_cancel), { confirmRemove = false })
        M3DialogAction(stringResource(R.string.git_m3_remove), { confirmRemove = false; leaving = true; settings.removeRepository(path) }, destructive = true, filled = true)
    }
    deletion?.takeIf { it.repository.path == path }?.let { review ->
        M3Dialog(onDismissRequest = { if (!destructive) settings.clearRepositoryDeletion() }, title = stringResource(R.string.ui_delete_local_files_09697434),
            icon = R.drawable.ic_m3_delete, iconTint = M3.colors.error, modifier = Modifier.testTag("managed-delete-review"),
            content = {
                Text(stringResource(R.string.ui_1_s_2_s_this_permanently_deletes_this_app_fffd860e, review.repository.name, review.plan.path),
                    Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), style = M3Type.bodyMedium)
            }) {
            M3DialogAction(stringResource(R.string.git_m3_cancel), { settings.clearRepositoryDeletion() }, enabled = !destructive)
            M3DialogAction(stringResource(R.string.ui_delete_local_files_20424441), { leaving = true; settings.deleteRepository(review) },
                Modifier.testTag("managed-delete-confirm"), destructive = true, filled = true, loading = destructive, enabled = !busy && !destructive)
        }
    }
    error?.let { message ->
        M3Dialog(onDismissRequest = { settings.clearError(); leaving = false }, title = stringResource(R.string.ui_repository_operation_failed_f1152917),
            icon = R.drawable.ic_m3_warning, iconTint = M3.colors.error,
            content = { Text(message, Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), style = M3Type.bodyMedium) }) {
            M3DialogAction(stringResource(R.string.ui_ok_565339bc), { settings.clearError(); leaving = false })
        }
    }
}

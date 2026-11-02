package com.bontecou.syncmd.ui.screens

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.VaultOpsBanner
import com.bontecou.syncmd.ui.components.VaultOpsButton
import com.bontecou.syncmd.ui.components.VaultOpsButtonStyle
import com.bontecou.syncmd.ui.components.VaultOpsDangerIcon
import com.bontecou.syncmd.ui.components.VaultOpsDetail
import com.bontecou.syncmd.ui.components.VaultOpsHeading
import com.bontecou.syncmd.ui.components.VaultOpsNote
import com.bontecou.syncmd.ui.components.VaultOpsOutlineButton
import com.bontecou.syncmd.ui.components.VaultOpsProgress
import com.bontecou.syncmd.ui.components.VaultOpsRow
import com.bontecou.syncmd.ui.components.VaultOpsSelectMark
import com.bontecou.syncmd.ui.components.VaultOpsSheet
import com.bontecou.syncmd.ui.components.VaultOpsTextButton
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TideToast
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.SavedRepository
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import java.io.File

/**
 * Local repositories: find working copies, then per-vault management cards
 * (open, connection, move) with the destructive choices in danger red, each
 * behind its own Tide confirmation sheet (iOS SettingsView / RemoveGitTrackingView).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepositoryManagementScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onOpenRepository: (String) -> Unit,
    onCloneRepository: (String) -> Unit,
    onEditConnection: ((String) -> Unit)? = null,
    onMoveRepository: ((String) -> Unit)? = null,
    onRestoreMoveRecovery: ((String) -> Unit)? = null,
) {
    val repos by viewModel.allRepositories.collectAsState()
    val history by viewModel.repositoryHistory.collectAsState()
    val result by viewModel.discoveryResult.collectAsState()
    val busy by viewModel.isLoading.collectAsState()
    val destructive by viewModel.destructiveBusy.collectAsState()
    val error by viewModel.errorMessage.collectAsState()
    val message by viewModel.operationMessage.collectAsState()
    val plan by viewModel.trackingPlan.collectAsState()
    val moveJournals by viewModel.moveJournals.collectAsState()
    val cloneRecoveries by viewModel.cloneRecoveries.collectAsState()
    val cloneRecoveryUnavailable by viewModel.cloneRecoveryUnavailable.collectAsState()
    com.bontecou.syncmd.ui.util.RepositoryResumeEffect("clone-recoveries", viewModel::refreshCloneRecoveries)
    val context = LocalContext.current
    val c = Tide.colors
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var remove by remember { mutableStateOf<SavedRepository?>(null) }
    val deletionReview by viewModel.deletionReview.collectAsState()
    DisposableEffect(viewModel) { onDispose { viewModel.clearRepositoryDeletion() } }
    var keepBackup by remember(plan?.operationId) { mutableStateOf(true) }
    var confirmTracking by remember { mutableStateOf<com.bontecou.syncmd.ui.viewmodels.TrackingRemovalRequest?>(null) }
    var discardRecovery by remember { mutableStateOf<String?>(null) }
    var dismissClone by remember { mutableStateOf<com.bontecou.syncmd.services.repositories.CloneRecovery?>(null) }
    BackHandler(enabled = destructive) { /* Finish an already-confirmed destructive operation. */ }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.clearOperationMessage() } }
    Scaffold(
        modifier = Modifier.testTag("repository-management"),
        containerColor = c.paper,
        contentColor = c.ink,
        snackbarHost = { SnackbarHost(snackbar) { data -> TideToast(data.visuals.message, Modifier.padding(horizontal = Tide.gutter, vertical = 8.dp)) } },
        topBar = {
            WrappingTopBar(title = stringResource(R.string.ui_local_repositories_ddcf4270), backLabel = stringResource(R.string.publication_back),
                onBack = onNavigateBack, backEnabled = !destructive)
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    VaultOpsNote(stringResource(R.string.ui_reconnect_working_copies_already_stored_by_76634d9e), Modifier.padding(horizontal = 4.dp))
                    TideCard(padding = 0.dp) {
                        VaultOpsRow(stringResource(R.string.ui_scan_app_folders_12ed3a77), { viewModel.scanLocalRepositories() },
                            Modifier.testTag("scan-repositories"), enabled = !busy, chevron = true)
                        TideHairline(Modifier.padding(horizontal = 16.dp))
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            com.bontecou.syncmd.ui.components.DeviceFolderButton(stringResource(R.string.ui_scan_a_device_folder_995756f2), enabled = !busy,
                                onFolder = viewModel::scanChosenFolder, onError = viewModel::reportStorageError)
                        }
                    }
                    if (busy) VaultOpsProgress()
                }
            }
            if (cloneRecoveries.isNotEmpty() || cloneRecoveryUnavailable) item(key = "clone-recoveries-title") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    VaultOpsHeading(stringResource(R.string.clone_recovery_title), Modifier.padding(top = 12.dp))
                    if (cloneRecoveryUnavailable) VaultOpsBanner(stringResource(R.string.clone_recovery_unavailable), danger = true)
                    TideCard(padding = 0.dp) {
                        VaultOpsRow(stringResource(R.string.clone_recovery_refresh), viewModel::refreshCloneRecoveries,
                            Modifier.testTag("clone-recovery-refresh"), enabled = !busy)
                    }
                }
            }
            items(cloneRecoveries, key = { "clone-recovery-${it.key}" }) { recovery ->
                val clone = recovery.publication
                TideCard(Modifier.testTag("clone-recovery-${recovery.key}"), padding = 0.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(clone?.name ?: stringResource(R.string.clone_recovery_unreadable), style = TideType.title, color = c.ink, modifier = Modifier.weight(1f))
                        }
                        VaultOpsNote(stringResource(if (recovery.damaged) R.string.clone_recovery_damaged else R.string.clone_recovery_ready),
                            color = if (recovery.damaged) c.danger else c.inkMid)
                        clone?.let {
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.ui_destination_1_s_0df94639, it.completed.path), style = TideType.mono(11.5.sp), color = c.inkFaint)
                                    Text(stringResource(R.string.clone_recovery_staging, it.staging), style = TideType.mono(11.5.sp), color = c.inkFaint)
                                }
                            }
                            if (it.registeredDate == null) VaultOpsOutlineButton(stringResource(R.string.clone_recovery_retry), { viewModel.retryCloneRecovery(recovery) },
                                Modifier.padding(top = 4.dp).testTag("clone-recovery-retry-${recovery.key}"), enabled = !busy && !destructive)
                        }
                    }
                    TideHairline(Modifier.padding(horizontal = 16.dp))
                    VaultOpsRow(stringResource(R.string.clone_recovery_dismiss), { dismissClone = recovery },
                        Modifier.testTag("clone-recovery-dismiss-${recovery.key}"), enabled = !busy && !destructive && recovery.dismissible)
                    if (!recovery.dismissible) Text(stringResource(R.string.clone_recovery_cannot_dismiss), style = TideType.sans(13.sp), color = c.inkFaint,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
                }
            }
            result?.let { scan ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        VaultOpsHeading(stringResource(R.string.ui_discovered_repositories_059a8e8d), Modifier.padding(top = 12.dp))
                        if (scan.repositories.isEmpty()) TideCard { VaultOpsNote(stringResource(R.string.ui_no_working_copies_found_in_the_scanned_fol_904c5b06)) }
                        if (scan.truncated) VaultOpsBanner(stringResource(R.string.ui_the_scan_reached_its_depth_or_folder_limit_9b655557), tone = TideBannerTone.AHEAD)
                        if (scan.warnings.isNotEmpty()) VaultOpsDetail(scan.warnings.joinToString("\n") { "${it.path}: ${it.message}" })
                    }
                }
                items(scan.repositories, key = { "discovered-${it.path}" }) { candidate ->
                    val alreadyAdded = repos.any { it.path == candidate.path }
                    val checked = alreadyAdded || candidate.path in selected
                    TideCard(Modifier.testTag("discovered-${candidate.path}"), padding = 0.dp) {
                        Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = !busy && !alreadyAdded, role = Role.Checkbox,
                            onValueChange = { on -> selected = if (on) selected + candidate.path else selected - candidate.path })
                            .padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.padding(top = 2.dp)) { VaultOpsSelectMark(checked, enabled = !busy && !alreadyAdded) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(candidate.name, style = TideType.sans(16.sp, FontWeight.SemiBold), color = c.ink)
                                Text(repositoryLocation(context, candidate.path), style = TideType.mono(11.5.sp), color = c.inkFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                CompositionLocalProvider(LocalTextStyle provides TideType.mono(11.5.sp), LocalContentColor provides c.inkMid) {
                                    RepositoryDiscoverySummary(candidate.branch, candidate.hasLocalChanges)
                                }
                                candidate.remoteUrl?.let { Text(remoteLabel(it), style = TideType.mono(11.5.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                if (alreadyAdded) TidePill(stringResource(R.string.ui_already_added_253b22d7), c.synced, c.synced.copy(alpha = 0.12f), Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
                item {
                    val additions = selected.filter { path -> repos.none { it.path == path } }
                    VaultOpsButton(stringResource(R.string.ui_add_selected_repositories_d05e3c53), { viewModel.addDiscoveredRepositories(additions); selected = emptySet() },
                        enabled = !busy && additions.isNotEmpty())
                }
            }
            item { VaultOpsHeading(stringResource(R.string.ui_added_repositories_400f05cb), Modifier.padding(top = 12.dp)) }
            if (repos.isEmpty()) item { TideCard { VaultOpsNote(stringResource(R.string.ui_no_repositories_added_yet_74c8565e)) } }
            items(repos, key = { "active-${it.path}" }) { repo ->
                TideCard(Modifier.testTag("managed-repository-${repo.path}"), padding = 0.dp) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(repo.alias.ifBlank { repo.name }, style = TideType.title, color = c.ink)
                        Text(repositoryLocation(context, repo.path), style = TideType.mono(11.5.sp), color = c.inkFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    VaultOpsRow(stringResource(R.string.ui_open_ed077f3d), { onOpenRepository(repo.path) }, enabled = !busy, chevron = true)
                    onEditConnection?.let { edit ->
                        TideHairline(Modifier.padding(horizontal = 16.dp))
                        VaultOpsRow(stringResource(R.string.ui_edit_connection_047f9f95), { edit(repo.path) }, enabled = !busy, chevron = true)
                    }
                    onMoveRepository?.let { move ->
                        TideHairline(Modifier.padding(horizontal = 16.dp))
                        VaultOpsRow(stringResource(R.string.ui_move_repository_1c4a6083), { move(repo.path) }, enabled = !busy, chevron = true)
                    }
                    // Keeps-files choice first, then the two that remove something.
                    TideHairline()
                    VaultOpsRow(stringResource(R.string.ui_remove_from_app_0d20b3a2), { remove = repo }, enabled = !busy)
                    TideHairline(Modifier.padding(horizontal = 16.dp))
                    VaultOpsRow(stringResource(R.string.ui_remove_git_tracking_d3e1065f), { viewModel.prepareTrackingRemoval(repo.path) }, enabled = !busy, destructive = true)
                    if (repo.managedStorage) {
                        TideHairline(Modifier.padding(horizontal = 16.dp))
                        VaultOpsRow(stringResource(R.string.ui_delete_local_files_20424441), { viewModel.prepareRepositoryDeletion(repo.path) }, enabled = !busy, destructive = true)
                    }
                }
            }
            if (moveJournals.isNotEmpty()) item(key = "move-recoveries-title") { VaultOpsHeading(stringResource(R.string.ui_move_recoveries_90731b9e), Modifier.padding(top = 12.dp)) }
            items(moveJournals, key = { "move-${it.plan.id}" }) { move ->
                TideCard(Modifier.testTag("move-recovery-${move.plan.id}"), padding = 0.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(File(move.plan.destination).name, style = TideType.sans(16.sp, FontWeight.SemiBold), color = c.ink)
                        SelectionContainer { Text(stringResource(R.string.ui_destination_1_s_0df94639, (move.plan.destination).toString()), style = TideType.mono(11.5.sp), color = c.inkFaint) }
                        if (move.phase == com.bontecou.syncmd.services.repositories.RepositoryMovePhase.COMMITTED && move.plan.copyRequired) {
                            SelectionContainer { Text(stringResource(R.string.ui_retained_original_1_s_61bc9b73, (move.plan.recovery.path).toString()), style = TideType.mono(11.5.sp), color = c.inkFaint) }
                        } else {
                            VaultOpsBanner(stringResource(R.string.ui_recovery_needs_attention_recognized_workin_89ce1116), modifier = Modifier.padding(top = 4.dp), tone = TideBannerTone.AHEAD)
                        }
                    }
                    TideHairline(Modifier.padding(horizontal = 16.dp))
                    if (move.phase == com.bontecou.syncmd.services.repositories.RepositoryMovePhase.COMMITTED && move.plan.copyRequired) {
                        onRestoreMoveRecovery?.let { restore ->
                            VaultOpsRow(stringResource(R.string.ui_restore_as_a_separate_repository_6253e37c), { restore(move.plan.id) }, enabled = !busy, chevron = true)
                            TideHairline(Modifier.padding(horizontal = 16.dp))
                        }
                        VaultOpsRow(stringResource(R.string.ui_remove_recovery_copy_b24c79b1), { discardRecovery = move.plan.id }, enabled = !busy, destructive = true)
                    } else {
                        VaultOpsRow(stringResource(R.string.ui_finish_move_recovery_44113cea), { viewModel.resumeMove(move.plan.id) }, enabled = !busy, chevron = true)
                        if (move.phase in listOf(com.bontecou.syncmd.services.repositories.RepositoryMovePhase.PREPARED, com.bontecou.syncmd.services.repositories.RepositoryMovePhase.COPY_READY, com.bontecou.syncmd.services.repositories.RepositoryMovePhase.SOURCE_RETAINED)) {
                            TideHairline(Modifier.padding(horizontal = 16.dp))
                            VaultOpsRow(stringResource(R.string.ui_cancel_pending_move_0858583b), { viewModel.cancelPendingMove(move.plan.id) }, enabled = !busy, destructive = true)
                        }
                    }
                }
            }
            if (history.isNotEmpty()) item { VaultOpsHeading(stringResource(R.string.ui_previously_added_627f4765), Modifier.padding(top = 12.dp)) }
            items(history, key = { "history-${it.repository.path}" }) { entry ->
                val repo = entry.repository
                val actions = buildList<Pair<String, () -> Unit>> {
                    if (entry.trackingRemoved) add(stringResource(R.string.ui_review_restored_folder_66729a70) to { viewModel.scanDirectory(repo.path) })
                    else if (!entry.filesDeleted) add(stringResource(R.string.ui_reconnect_bf8a9eab) to { viewModel.reconnect(repo.path) })
                    if ((repo.remoteUrl != null || repo.alias.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) && !entry.trackingRemoved && (entry.filesDeleted || !File(repo.path).exists()))
                        add(stringResource(R.string.ui_clone_again_e05eb495) to { onCloneRepository(repo.path) })
                }
                TideCard(Modifier.testTag("repository-history-${repo.path}"), padding = 0.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(repo.alias.ifBlank { repo.name }, style = TideType.sans(16.sp, FontWeight.SemiBold), color = c.ink)
                        Text(repositoryLocation(context, repo.path), style = TideType.mono(11.5.sp), color = c.inkFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        CompositionLocalProvider(LocalTextStyle provides TideType.sans(13.sp), LocalContentColor provides c.inkMid) {
                            RepositoryHistorySummary(entry.trackingRemoved, entry.filesDeleted)
                        }
                        if (entry.trackingRemoved) entry.backupDirectory?.let {
                            SelectionContainer { Text(stringResource(R.string.ui_backup_1_s_6ad01ca6, (it).toString()), style = TideType.mono(11.5.sp), color = c.inkFaint) }
                        }
                    }
                    actions.forEach { (label, action) ->
                        TideHairline(Modifier.padding(horizontal = 16.dp))
                        VaultOpsRow(label, action, enabled = !busy, chevron = true)
                    }
                    entry.backupArchive?.let { archive ->
                        TideHairline(Modifier.padding(horizontal = 16.dp))
                        VaultOpsRow(stringResource(R.string.ui_share_backup_archive_ee4ba873), {
                            runCatching {
                                val file = File(archive)
                                require(file.isFile && file.name == "git-metadata.zip") { "Backup archive is not available" }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                val intent = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.clipData = ClipData.newUri(context.contentResolver, "Git metadata backup", uri)
                                context.startActivity(Intent.createChooser(intent, "Share Git metadata backup"))
                            }.onFailure { viewModel.reportError(it.message ?: "Cannot share the backup archive") }
                        }, chevron = true)
                    }
                }
            }
        }
    }
    error?.let {
        VaultOpsSheet(onDismissRequest = viewModel::clearError, title = stringResource(R.string.ui_repository_operation_failed_f1152917),
            icon = { VaultOpsDangerIcon() },
            confirmButton = { VaultOpsButton(stringResource(R.string.ui_ok_565339bc), viewModel::clearError) }) {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) { VaultOpsDetail(it) }
        }
    }
    dismissClone?.let { review ->
        VaultOpsSheet(onDismissRequest = { dismissClone = null }, modifier = Modifier.testTag("clone-recovery-confirm"),
            title = stringResource(R.string.clone_recovery_confirm_title),
            confirmButton = { VaultOpsButton(stringResource(R.string.clone_recovery_dismiss), { dismissClone = null; viewModel.dismissCloneRecovery(review) },
                Modifier.testTag("clone-recovery-confirm-dismiss")) },
            dismissButton = { VaultOpsTextButton(stringResource(R.string.publication_cancel), { dismissClone = null }, Modifier.fillMaxWidth().testTag("clone-recovery-confirm-cancel")) }) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("clone-recovery-confirm-scroll")) {
                Text(stringResource(R.string.clone_recovery_confirm_body))
            }
        }
    }
    discardRecovery?.let { id ->
        VaultOpsSheet(onDismissRequest = { discardRecovery = null }, title = stringResource(R.string.ui_remove_the_retained_original_a09b39c8),
            icon = { VaultOpsDangerIcon() },
            confirmButton = { VaultOpsButton(stringResource(R.string.ui_remove_recovery_copy_b24c79b1), { discardRecovery = null; viewModel.discardMoveRecovery(id) },
                style = VaultOpsButtonStyle.DANGER) },
            dismissButton = { VaultOpsTextButton(stringResource(R.string.publication_cancel), { discardRecovery = null }, Modifier.fillMaxWidth()) }) {
            Text(stringResource(R.string.ui_the_active_destination_stays_in_place_gits_a4f1f68a))
        }
    }
    remove?.let { repo ->
        VaultOpsSheet(onDismissRequest = { remove = null }, title = stringResource(R.string.ui_remove_from_gitsync_md_ead6e2d6),
            confirmButton = { VaultOpsButton(stringResource(R.string.ui_remove_from_app_0d20b3a2), { remove = null; viewModel.removeRepository(repo.path) }) },
            dismissButton = { VaultOpsTextButton(stringResource(R.string.publication_cancel), { remove = null }, Modifier.fillMaxWidth()) }) {
            Text(stringResource(R.string.ui_1_s_2_s_all_working_files_and_local_git_hi_872b2983, (repo.name).toString(), (repo.path).toString()))
        }
    }
    deletionReview?.let { deletion ->
        val repo = deletion.repository
        VaultOpsSheet(onDismissRequest = { viewModel.clearRepositoryDeletion() }, modifier = Modifier.testTag("managed-delete-review"),
            title = stringResource(R.string.ui_delete_local_files_09697434), icon = { VaultOpsDangerIcon() },
            confirmButton = { VaultOpsButton(stringResource(R.string.ui_delete_local_files_20424441), { viewModel.deleteRepository(deletion) },
                Modifier.testTag("managed-delete-confirm"), enabled = !busy && !destructive, style = VaultOpsButtonStyle.DANGER) },
            dismissButton = { VaultOpsTextButton(stringResource(R.string.publication_cancel), { viewModel.clearRepositoryDeletion() },
                Modifier.fillMaxWidth().testTag("managed-delete-cancel"), enabled = !busy && !destructive) }) {
            Text(stringResource(R.string.ui_1_s_2_s_this_permanently_deletes_this_app_fffd860e, repo.name, deletion.plan.path),
                modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()))
            if (destructive) VaultOpsProgress()
        }
    }
    plan?.let { review ->
        TrackingRemovalReview(review, keepBackup, busy = busy || destructive,
            onKeepBackup = { keepBackup = it },
            onContinue = { confirmTracking = com.bontecou.syncmd.ui.viewmodels.TrackingRemovalRequest(review, keepBackup) },
            onCancel = viewModel::clearTrackingPlan)
        confirmTracking?.let { pending ->
            TrackingRemovalConfirmation(pending, valid = review === pending.plan, busy = busy || destructive,
                onCancel = { confirmTracking = null },
                onConfirm = { confirmTracking = null; viewModel.removeTracking(pending) })
        }
    }
}

private fun repositoryLocation(context: android.content.Context, path: String): String {
    val bases = buildList {
        add(context.filesDir.canonicalPath to "App storage")
        @Suppress("DEPRECATION")
        context.externalMediaDirs.firstOrNull()?.let { add(it.canonicalPath to "Android/media") }
        context.getExternalFilesDir(null)?.let { add(it.canonicalPath to "App external storage") }
    }
    val base = bases.firstOrNull { path.startsWith(it.first + "/") }
    return if (base != null) "${base.second} / ${path.removePrefix(base.first + "/")}" else path
}

private fun remoteLabel(remote: String): String = if (remote.startsWith("file:")) {
    val name = runCatching { java.net.URI(remote).path.trimEnd('/').substringAfterLast('/') }.getOrDefault("")
    "Local remote${if (name.isBlank()) "" else ": $name"}"
} else remote

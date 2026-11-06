package com.bontecou.syncmd.ui.screens

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.AddVaultCodeNote
import com.bontecou.syncmd.ui.components.AddVaultInlineIssue
import com.bontecou.syncmd.ui.components.AddVaultWayRow
import com.bontecou.syncmd.ui.components.addVaultFriendlyPath
import com.bontecou.syncmd.ui.components.rememberAddVaultFolderPicker
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Chevron
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3ListRow
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3SnackbarHost
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.m3TopDivider
import com.bontecou.syncmd.ui.viewmodels.SavedRepository
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import java.io.File

/**
 * Board LocalClones · Local repositories: scan app folders or a device folder (with the folder-access
 * explanation), interrupted clones, discovered working copies to add, added repositories with their
 * management rows, and previously added ones. Destructive choices keep their own confirmations.
 */
@Composable
fun RepositoryManagementScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onOpenRepository: (String) -> Unit,
    onCloneRepository: (String) -> Unit,
    onEditConnection: ((String) -> Unit)? = null,
    onMoveRepository: ((String) -> Unit)? = null,
    onRestoreMoveRecovery: ((String) -> Unit)? = null,
    onCloneFromGitHub: (() -> Unit)? = null,
) {
    // Adding found clones is a new vault, so locked installs unlock first (as on iOS).
    val unlockGate = com.bontecou.syncmd.ui.util.rememberUnlockGate()
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
    val scanProgress by viewModel.scanProgress.collectAsState()
    val scanLabel by viewModel.scanLabel.collectAsState()
    com.bontecou.syncmd.ui.util.RepositoryResumeEffect("clone-recoveries", viewModel::refreshCloneRecoveries)
    val context = LocalContext.current
    val c = M3.colors
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var remove by remember { mutableStateOf<SavedRepository?>(null) }
    val deletionReview by viewModel.deletionReview.collectAsState()
    DisposableEffect(viewModel) { onDispose { viewModel.clearRepositoryDeletion() } }
    var keepBackup by remember(plan?.operationId) { mutableStateOf(true) }
    var confirmTracking by remember { mutableStateOf<com.bontecou.syncmd.ui.viewmodels.TrackingRemovalRequest?>(null) }
    var discardRecovery by remember { mutableStateOf<String?>(null) }
    var dismissClone by remember { mutableStateOf<com.bontecou.syncmd.services.repositories.CloneRecovery?>(null) }
    val scanFolder = rememberAddVaultFolderPicker(onFolder = viewModel::scanChosenFolder, onError = viewModel::reportStorageError)
    BackHandler(enabled = destructive) { /* Finish an already-confirmed destructive operation. */ }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.clearOperationMessage() } }
    // A new scan starts a fresh selection: found copies arrive checked unless already added.
    LaunchedEffect(result) { selected = result?.repositories?.filter { it.branch != null }?.map { it.path }?.filter { path -> repos.none { it.path == path } }?.toSet().orEmpty() }
    val rowPadding = PaddingValues(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
    Box(Modifier.fillMaxSize().background(c.surface).testTag("repository-management")) {
        Column(Modifier.fillMaxSize()) {
            M3TopBar(stringResource(R.string.add_local_title), onBack = if (destructive) null else onNavigateBack)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                item(key = "intro") {
                    Text(stringResource(R.string.add_local_intro), style = M3Type.bodyMedium, color = c.onVar, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp))
                }
                item(key = "scan") {
                    M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                        AddVaultWayRow(stringResource(R.string.add_local_scan_app), stringResource(R.string.add_local_scan_app_sub), R.drawable.ic_m3_phone,
                            { viewModel.scanLocalRepositories() }, Modifier.testTag("scan-repositories"), enabled = !busy)
                        AddVaultWayRow(stringResource(R.string.add_local_scan_device), stringResource(R.string.add_local_scan_device_sub), R.drawable.ic_m3_folder,
                            scanFolder, Modifier.testTag("device-folder-picker"), divider = true, enabled = !busy)
                    }
                }
                scanProgress?.let { scan -> item(key = "scanning") {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                        M3IndeterminateProgressBar(description = stringResource(R.string.add_local_scanning, scan.label))
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(stringResource(R.string.add_local_scanning, scan.label), style = M3Type.meta, color = c.onVar, modifier = Modifier.weight(1f))
                            Text(pluralStringResource(R.plurals.add_local_folders, scan.folders, scan.folders), style = M3Type.meta, color = c.onVar)
                        }
                    }
                } }
                if (busy && scanProgress == null) item(key = "busy") { M3IndeterminateProgressBar(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) }

                // Interrupted clones
                if (cloneRecoveries.isNotEmpty() || cloneRecoveryUnavailable) item(key = "clone-recoveries-title") {
                    Column {
                        M3SectionLabel(stringResource(R.string.add_local_interrupted), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp))
                        if (cloneRecoveryUnavailable) M3Note(stringResource(R.string.clone_recovery_unavailable), Modifier.padding(horizontal = 16.dp), tone = M3NoteTone.Warn)
                    }
                }
                items(cloneRecoveries, key = { "clone-recovery-${it.key}" }) { recovery ->
                    val clone = recovery.publication
                    M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("clone-recovery-${recovery.key}")) {
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(clone?.name ?: stringResource(R.string.clone_recovery_unreadable), style = M3Type.bodyStrong, color = c.on)
                            Text(stringResource(if (recovery.damaged) R.string.clone_recovery_damaged else R.string.add_local_interrupted_body),
                                style = M3Type.bodyMedium, color = if (recovery.damaged) c.error else c.onVar)
                            clone?.let {
                                SelectionContainer {
                                    Text(stringResource(R.string.add_local_destination, addVaultFriendlyPath(context, it.completed.path, "/")) + "\n" +
                                        stringResource(R.string.add_local_staged, it.staging), style = M3Type.meta, color = c.onVar)
                                }
                                if (it.registeredDate == null) M3Button(stringResource(R.string.clone_recovery_retry), { viewModel.retryCloneRecovery(recovery) },
                                    Modifier.padding(top = 4.dp).testTag("clone-recovery-retry-${recovery.key}"), style = M3ButtonStyle.Outlined, size = M3ButtonSize.Medium,
                                    enabled = !busy && !destructive)
                            }
                        }
                        M3ListRow(stringResource(R.string.clone_recovery_dismiss), Modifier.testTag("clone-recovery-dismiss-${recovery.key}"), divider = true,
                            onClick = { dismissClone = recovery }, enabled = !busy && !destructive && recovery.dismissible, contentPadding = rowPadding)
                        if (!recovery.dismissible) Text(stringResource(R.string.clone_recovery_cannot_dismiss), style = M3Type.bodySmall, color = c.onVar,
                            modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 14.dp))
                    }
                }
                if (cloneRecoveries.isNotEmpty() || cloneRecoveryUnavailable) item(key = "clone-recovery-refresh") {
                    M3Button(stringResource(R.string.clone_recovery_refresh), viewModel::refreshCloneRecoveries, Modifier.padding(start = 12.dp, top = 8.dp).testTag("clone-recovery-refresh"),
                        style = M3ButtonStyle.Text, icon = R.drawable.ic_m3_sync, enabled = !busy)
                }

                // Discovered repositories
                result?.let { scan ->
                    item(key = "discovered-title") {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            M3SectionLabel(stringResource(R.string.add_local_discovered), Modifier.weight(1f), contentPadding = PaddingValues(0.dp))
                            scanLabel?.let { Text(stringResource(R.string.add_local_in, it), style = M3Type.meta, color = c.onVar) }
                        }
                    }
                    if (scan.repositories.isEmpty()) item(key = "discovered-empty") {
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.fillMaxWidth().background(c.cLow, RoundedCornerShape(28.dp)).padding(horizontal = 20.dp, vertical = 20.dp)) {
                                Text(stringResource(R.string.add_local_none_title), style = M3Type.bodyStrong, color = c.on)
                                Text(stringResource(R.string.add_local_none_body, scanLabel ?: stringResource(R.string.add_local_scan_app_label)), style = M3Type.bodyMedium, color = c.onVar,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                            if (scan.truncated) M3Note(stringResource(R.string.add_local_truncated), tone = M3NoteTone.Ahead, icon = R.drawable.ic_m3_warning)
                            if (onCloneFromGitHub != null) M3Button(stringResource(R.string.add_local_clone_instead), onCloneFromGitHub, Modifier.fillMaxWidth(),
                                style = M3ButtonStyle.Outlined, size = M3ButtonSize.Large)
                        }
                    } else {
                        item(key = "discovered-list") {
                            M3ListCard(Modifier.padding(horizontal = 16.dp)) {
                                scan.repositories.forEachIndexed { index, candidate ->
                                    val alreadyAdded = repos.any { it.path == candidate.path }
                                    val checked = alreadyAdded || candidate.path in selected
                                    Row(Modifier.fillMaxWidth().m3TopDivider(index > 0, c.outlineVar)
                                        .toggleable(value = checked, enabled = !busy && !alreadyAdded, role = Role.Checkbox,
                                            onValueChange = { on -> selected = if (on) selected + candidate.path else selected - candidate.path })
                                        .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp).testTag("discovered-${candidate.path}"),
                                        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Checkbox(checked, null, enabled = !alreadyAdded && !busy,
                                            colors = CheckboxDefaults.colors(checkedColor = c.primary, uncheckedColor = c.onVar, checkmarkColor = c.onPrimary))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(candidate.name, style = M3Type.bodyStrong, color = c.on)
                                                if (alreadyAdded) com.bontecou.syncmd.ui.theme.M3Badge(stringResource(R.string.add_local_already), com.bontecou.syncmd.ui.theme.M3Tone.Ok)
                                            }
                                            Text(addVaultFriendlyPath(context, candidate.path), style = M3Type.meta, color = c.onVar, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Text(candidate.branch ?: stringResource(R.string.add_local_detached),
                                                    style = if (candidate.branch != null) M3Type.meta else M3Type.bodySmall, color = c.onVar,
                                                    modifier = Modifier.testTag("repository-discovery-branch"))
                                                Text(stringResource(if (candidate.hasLocalChanges) R.string.add_local_dirty else R.string.add_local_clean),
                                                    style = M3Type.bodySmall.copy(fontWeight = if (candidate.hasLocalChanges) FontWeight.SemiBold else FontWeight.Normal),
                                                    color = if (candidate.hasLocalChanges) c.ahead else c.onVar, modifier = Modifier.testTag("repository-discovery-status"))
                                            }
                                            candidate.remoteUrl?.let { Text(remoteLabel(context, it), style = M3Type.meta, color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "discovered-notes") {
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (scan.warnings.isNotEmpty()) AddVaultCodeNote(scan.warnings.joinToString("\n") { "${addVaultFriendlyPath(context, it.path, "/")}: ${it.message}" })
                                if (scan.truncated) M3Note(stringResource(R.string.add_local_truncated), tone = M3NoteTone.Ahead, icon = R.drawable.ic_m3_warning)
                                val additions = selected.filter { path -> repos.none { it.path == path } }
                                M3Button(stringResource(R.string.add_local_add_selected), { unlockGate.require { viewModel.addDiscoveredRepositories(additions); selected = emptySet() } },
                                    Modifier.fillMaxWidth().testTag("add-discovered"), size = M3ButtonSize.Hero, trailingMeta = additions.size.takeIf { it > 0 }?.toString(),
                                    enabled = !busy && additions.isNotEmpty())
                            }
                        }
                    }
                }

                // Added repositories
                item(key = "added-title") { M3SectionLabel(stringResource(R.string.add_local_added), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)) }
                if (repos.isEmpty()) item(key = "added-empty") {
                    Text(stringResource(R.string.add_local_none_added), style = M3Type.bodyMedium, color = c.onVar, modifier = Modifier.padding(horizontal = 20.dp))
                }
                items(repos, key = { "active-${it.path}" }) { repo ->
                    M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag("managed-repository-${repo.path}")) {
                        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) {
                            Text(repo.alias.ifBlank { repo.name }, style = M3Type.bodyStrong, color = c.on)
                            Text(addVaultFriendlyPath(context, repo.path), style = M3Type.meta, color = c.onVar, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        M3ListRow(stringResource(R.string.ui_open_ed077f3d), divider = true, onClick = { onOpenRepository(repo.path) }, enabled = !busy,
                            contentPadding = rowPadding, trailing = { M3Chevron() })
                        onEditConnection?.let { edit -> M3ListRow(stringResource(R.string.ui_edit_connection_047f9f95), divider = true, onClick = { edit(repo.path) },
                            enabled = !busy, contentPadding = rowPadding, trailing = { M3Chevron() }) }
                        onMoveRepository?.let { move -> M3ListRow(stringResource(R.string.ui_move_repository_1c4a6083), divider = true, onClick = { move(repo.path) },
                            enabled = !busy, contentPadding = rowPadding, trailing = { M3Chevron() }) }
                        // Keeps-files choice first, below a stronger line, then the two that remove something.
                        M3ListRow(stringResource(R.string.ui_remove_from_app_0d20b3a2), Modifier.m3TopDivider(true, c.outline), onClick = { remove = repo },
                            enabled = !busy, contentPadding = rowPadding)
                        M3ListRow(stringResource(R.string.ui_remove_git_tracking_d3e1065f), divider = true, onClick = { viewModel.prepareTrackingRemoval(repo.path) },
                            enabled = !busy, titleColor = c.error, contentPadding = rowPadding)
                        if (repo.managedStorage) M3ListRow(stringResource(R.string.ui_delete_local_files_20424441), divider = true,
                            onClick = { viewModel.prepareRepositoryDeletion(repo.path) }, enabled = !busy, titleColor = c.error, contentPadding = rowPadding)
                    }
                }

                // Move recoveries
                if (moveJournals.isNotEmpty()) item(key = "move-recoveries-title") {
                    M3SectionLabel(stringResource(R.string.ui_move_recoveries_90731b9e), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp))
                }
                items(moveJournals, key = { "move-${it.plan.id}" }) { move ->
                    val committed = move.phase == com.bontecou.syncmd.services.repositories.RepositoryMovePhase.COMMITTED && move.plan.copyRequired
                    M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag("move-recovery-${move.plan.id}")) {
                        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(File(move.plan.destination).name, style = M3Type.bodyStrong, color = c.on)
                            SelectionContainer { Text(stringResource(R.string.ui_destination_1_s_0df94639, move.plan.destination.toString()), style = M3Type.meta, color = c.onVar) }
                            if (committed) SelectionContainer { Text(stringResource(R.string.ui_retained_original_1_s_61bc9b73, move.plan.recovery.path.toString()), style = M3Type.meta, color = c.onVar) }
                            else M3Note(stringResource(R.string.ui_recovery_needs_attention_recognized_workin_89ce1116), tone = M3NoteTone.Ahead)
                        }
                        if (committed) {
                            onRestoreMoveRecovery?.let { restore -> M3ListRow(stringResource(R.string.ui_restore_as_a_separate_repository_6253e37c), divider = true,
                                onClick = { restore(move.plan.id) }, enabled = !busy, contentPadding = rowPadding, trailing = { M3Chevron() }) }
                            M3ListRow(stringResource(R.string.ui_remove_recovery_copy_b24c79b1), divider = true, onClick = { discardRecovery = move.plan.id },
                                enabled = !busy, titleColor = c.error, contentPadding = rowPadding)
                        } else {
                            M3ListRow(stringResource(R.string.ui_finish_move_recovery_44113cea), divider = true, onClick = { viewModel.resumeMove(move.plan.id) },
                                enabled = !busy, contentPadding = rowPadding, trailing = { M3Chevron() })
                            if (move.phase in listOf(com.bontecou.syncmd.services.repositories.RepositoryMovePhase.PREPARED, com.bontecou.syncmd.services.repositories.RepositoryMovePhase.COPY_READY,
                                    com.bontecou.syncmd.services.repositories.RepositoryMovePhase.SOURCE_RETAINED))
                                M3ListRow(stringResource(R.string.ui_cancel_pending_move_0858583b), divider = true, onClick = { viewModel.cancelPendingMove(move.plan.id) },
                                    enabled = !busy, titleColor = c.error, contentPadding = rowPadding)
                        }
                    }
                }

                // Previously added
                if (history.isNotEmpty()) item(key = "previous-title") {
                    M3SectionLabel(stringResource(R.string.add_local_previous), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp))
                }
                items(history, key = { "history-${it.repository.path}" }) { entry ->
                    val repo = entry.repository
                    val actions = buildList<Pair<String, () -> Unit>> {
                        if (entry.trackingRemoved) add(stringResource(R.string.ui_review_restored_folder_66729a70) to { viewModel.scanDirectory(repo.path) })
                        else if (!entry.filesDeleted) add(stringResource(R.string.ui_reconnect_bf8a9eab) to { viewModel.reconnect(repo.path) })
                        if ((repo.remoteUrl != null || repo.alias.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) && !entry.trackingRemoved && (entry.filesDeleted || !File(repo.path).exists()))
                            add(stringResource(R.string.ui_clone_again_e05eb495) to { onCloneRepository(repo.path) })
                    }
                    M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag("repository-history-${repo.path}")) {
                        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(repo.alias.ifBlank { repo.name }, style = M3Type.bodyStrong, color = c.on)
                            Text(addVaultFriendlyPath(context, repo.path), style = M3Type.meta, color = c.onVar, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(when {
                                entry.trackingRemoved -> R.string.add_local_tracking_removed
                                entry.filesDeleted -> R.string.add_local_files_deleted
                                else -> R.string.add_local_files_kept
                            }), style = M3Type.bodySmall, color = c.onVar, modifier = Modifier.testTag("repository-history-status"))
                            if (entry.trackingRemoved) entry.backupDirectory?.let {
                                SelectionContainer { Text(stringResource(R.string.ui_backup_1_s_6ad01ca6, it.toString()), style = M3Type.meta, color = c.onVar) }
                            }
                        }
                        actions.forEach { (label, action) ->
                            M3ListRow(label, divider = true, onClick = action, enabled = !busy, contentPadding = rowPadding, trailing = { M3Chevron() })
                        }
                        entry.backupArchive?.let { archive ->
                            M3ListRow(stringResource(R.string.ui_share_backup_archive_ee4ba873), divider = true, contentPadding = rowPadding, trailing = { M3Chevron() }, onClick = {
                                runCatching {
                                    val file = File(archive)
                                    require(file.isFile && file.name == "git-metadata.zip") { context.getString(R.string.vault_l10n_backup_unavailable) }
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                    val intent = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    intent.clipData = ClipData.newUri(context.contentResolver, context.getString(R.string.vault_l10n_backup_clip_label), uri)
                                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.vault_l10n_backup_share_title)))
                                }.onFailure { viewModel.reportError(it.message ?: context.getString(R.string.vault_l10n_backup_share_failed)) }
                            })
                        }
                    }
                }
            }
        }
        M3SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp))
    }
    error?.let {
        M3Dialog(onDismissRequest = viewModel::clearError, title = stringResource(R.string.ui_repository_operation_failed_f1152917), icon = R.drawable.ic_m3_error,
            iconTint = c.error, content = { Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) { Text(it, style = M3Type.meta) } }) {
            M3DialogAction(stringResource(R.string.ui_ok_565339bc), viewModel::clearError, filled = true)
        }
    }
    dismissClone?.let { review ->
        M3Dialog(onDismissRequest = { dismissClone = null }, title = stringResource(R.string.clone_recovery_confirm_title), modifier = Modifier.testTag("clone-recovery-confirm"),
            content = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("clone-recovery-confirm-scroll")) {
                Text(stringResource(R.string.clone_recovery_confirm_body)) } }) {
            M3DialogAction(stringResource(R.string.publication_cancel), { dismissClone = null }, Modifier.testTag("clone-recovery-confirm-cancel"))
            M3DialogAction(stringResource(R.string.clone_recovery_dismiss), { dismissClone = null; viewModel.dismissCloneRecovery(review) },
                Modifier.testTag("clone-recovery-confirm-dismiss"), filled = true)
        }
    }
    discardRecovery?.let { id ->
        M3Dialog(onDismissRequest = { discardRecovery = null }, title = stringResource(R.string.ui_remove_the_retained_original_a09b39c8), icon = R.drawable.ic_m3_delete,
            iconTint = c.error, text = stringResource(R.string.ui_the_active_destination_stays_in_place_gits_a4f1f68a)) {
            M3DialogAction(stringResource(R.string.publication_cancel), { discardRecovery = null })
            M3DialogAction(stringResource(R.string.ui_remove_recovery_copy_b24c79b1), { discardRecovery = null; viewModel.discardMoveRecovery(id) }, destructive = true, filled = true)
        }
    }
    remove?.let { repo ->
        M3Dialog(onDismissRequest = { remove = null }, title = stringResource(R.string.ui_remove_from_gitsync_md_ead6e2d6),
            text = stringResource(R.string.ui_1_s_2_s_all_working_files_and_local_git_hi_872b2983, repo.name, repo.path)) {
            M3DialogAction(stringResource(R.string.publication_cancel), { remove = null })
            M3DialogAction(stringResource(R.string.ui_remove_from_app_0d20b3a2), { remove = null; viewModel.removeRepository(repo.path) }, filled = true)
        }
    }
    deletionReview?.let { deletion ->
        val repo = deletion.repository
        M3Dialog(onDismissRequest = { viewModel.clearRepositoryDeletion() }, title = stringResource(R.string.ui_delete_local_files_09697434), icon = R.drawable.ic_m3_delete,
            iconTint = c.error, modifier = Modifier.testTag("managed-delete-review"), dismissOnClickOutside = !destructive, dismissOnBackPress = !destructive,
            content = {
                Text(stringResource(R.string.ui_1_s_2_s_this_permanently_deletes_this_app_fffd860e, repo.name, deletion.plan.path),
                    modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()))
                if (destructive) M3IndeterminateProgressBar(Modifier.padding(top = 12.dp))
            }) {
            M3DialogAction(stringResource(R.string.publication_cancel), { viewModel.clearRepositoryDeletion() }, Modifier.testTag("managed-delete-cancel"), enabled = !busy && !destructive)
            M3DialogAction(stringResource(R.string.ui_delete_local_files_20424441), { viewModel.deleteRepository(deletion) }, Modifier.testTag("managed-delete-confirm"),
                destructive = true, filled = true, enabled = !busy && !destructive)
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

private fun remoteLabel(context: android.content.Context, remote: String): String = if (remote.startsWith("file:")) {
    val name = runCatching { java.net.URI(remote).path.trimEnd('/').substringAfterLast('/') }.getOrDefault("")
    if (name.isBlank()) context.getString(R.string.vault_l10n_local_remote) else context.getString(R.string.vault_l10n_local_remote_named, name)
} else remote

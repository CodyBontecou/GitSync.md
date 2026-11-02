package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.repositories.RepositoryMovePhase
import com.bontecou.syncmd.ui.components.FolderLocationField
import com.bontecou.syncmd.ui.components.VaultOpsBanner
import com.bontecou.syncmd.ui.components.VaultOpsButton
import com.bontecou.syncmd.ui.components.VaultOpsHeading
import com.bontecou.syncmd.ui.components.VaultOpsNote
import com.bontecou.syncmd.ui.components.VaultOpsProgress
import com.bontecou.syncmd.ui.components.VaultOpsRow
import com.bontecou.syncmd.ui.components.VaultOpsSheet
import com.bontecou.syncmd.ui.components.VaultOpsTextButton
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

/** Move a working copy (or restore a retained original): pick, review the exact destination, confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveRepositoryScreen(path: String, recoveryId: String? = null, settings: SettingsViewModel, onBack: () -> Unit) {
    val review by settings.moveReview.collectAsState()
    val journals by settings.moveJournals.collectAsState()
    val busy by settings.isLoading.collectAsState()
    val destructive by settings.destructiveBusy.collectAsState()
    val error by settings.errorMessage.collectAsState()
    var location by rememberSaveable(path, recoveryId) { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(path, recoveryId) { settings.clearMoveReview() }
    val current = review?.takeIf { if (recoveryId == null) it.plan.source == path else it.metadata.restoringRecoveryId == recoveryId }
    val pendingSource = if (recoveryId == null) path else journals.firstOrNull { it.plan.id == recoveryId }?.plan?.recovery?.path ?: current?.plan?.source
    val pending = journals.filter { it.phase != RepositoryMovePhase.DISCARDING_RECOVERY &&
        (it.phase != RepositoryMovePhase.COMMITTED || !it.plan.copyRequired) && it.plan.source == pendingSource }
    BackHandler(destructive) {}
    val c = Tide.colors
    Scaffold(modifier = Modifier.testTag("move-repository-screen"), containerColor = c.paper, contentColor = c.ink, topBar = {
        WrappingTopBar(title = if (recoveryId == null) "Move repository" else "Restore recovery copy",
            backLabel = stringResource(R.string.publication_back), onBack = onBack, backEnabled = !destructive)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            VaultOpsNote(if (recoveryId == null) "Move this entire working copy, including Git history, staged changes and unsaved files on disk. Its account and author settings move with it."
                else "Restore the retained original as a separate repository. The current working copy stays in place.", Modifier.padding(horizontal = 4.dp))
            if (path.isNotEmpty()) TideCard(padding = 16.dp) {
                SelectionContainer { Text(path, style = TideType.mono(12.5.sp).copy(textDirection = TextDirection.Ltr), color = c.ink) }
            }
            FolderLocationField(location, { location = it; settings.clearMoveReview() }, enabled = !busy, title = stringResource(R.string.ui_destination_parent_folder_c6c4f97e))
            if (busy) VaultOpsProgress(if (destructive) "Moving and saving repository settings…" else "Reviewing files and Git metadata…")
            if (pending.isEmpty()) VaultOpsButton(stringResource(R.string.ui_review_destination_59808b00), {
                if (recoveryId == null) settings.prepareMove(path, location) else settings.prepareRecoveryRestore(recoveryId, location)
            }, Modifier.testTag("move-review"), enabled = !busy)
            current?.let { item ->
                VaultOpsHeading(stringResource(R.string.ui_reviewed_destination_7173c3da), Modifier.padding(top = 8.dp))
                TideCard(padding = 16.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionContainer {
                            Text(item.plan.destination, style = TideType.mono(13.sp, FontWeight.Medium).copy(textDirection = TextDirection.Ltr), color = c.ink,
                                modifier = Modifier.testTag("move-destination"))
                        }
                        Text(stringResource(R.string.ui_1_s_files_including_git_metadata_2_s_bytes_ae4f1906, (item.plan.files).toString(), (item.plan.bytes).toString()),
                            style = TideType.meta, color = c.inkFaint)
                    }
                }
                VaultOpsNote(stringResource(if (item.plan.copyRequired) R.string.ui_this_crosses_storage_volumes_gitsync_md_wi_559c0a3d
                    else R.string.ui_the_folder_can_move_directly_on_this_stora_7b29fabb), Modifier.padding(horizontal = 4.dp))
                VaultOpsButton(if (recoveryId == null) "Move working copy" else "Restore as a separate repository", { confirming = true },
                    Modifier.testTag("move-confirm"), enabled = !busy && pending.isEmpty())
            }
            pending.forEach { journal ->
                VaultOpsBanner(stringResource(R.string.ui_an_interrupted_move_needs_recovery_all_rec_38aefacd), tone = TideBannerTone.AHEAD)
                TideCard(padding = 0.dp) {
                    VaultOpsRow(stringResource(R.string.ui_finish_move_recovery_44113cea), { settings.resumeMove(journal.plan.id) }, enabled = !busy, chevron = true)
                    if (journal.phase in listOf(RepositoryMovePhase.PREPARED, RepositoryMovePhase.COPY_READY, RepositoryMovePhase.SOURCE_RETAINED)) {
                        TideHairline(Modifier.padding(horizontal = 16.dp))
                        VaultOpsRow(stringResource(R.string.ui_cancel_pending_move_0858583b), { settings.cancelPendingMove(journal.plan.id) }, enabled = !busy, destructive = true)
                    }
                }
            }
            error?.let { VaultOpsBanner(it, Modifier.testTag("move-error"), danger = true) }
        }
    }
    if (confirming && current != null) VaultOpsSheet(onDismissRequest = { confirming = false },
        title = if (recoveryId == null) "Move this working copy?" else "Restore this recovery copy?",
        icon = { TideModalIcon(glyph = "→", tint = c.ink) },
        confirmButton = { VaultOpsButton(if (recoveryId == null) "Move" else "Restore", { confirming = false; settings.moveReviewed(onBack) }, Modifier.testTag("move-final-confirm")) },
        dismissButton = { VaultOpsTextButton(stringResource(R.string.publication_cancel), { confirming = false }, Modifier.fillMaxWidth()) }) {
        Text(stringResource(R.string.ui_1_s_2_schoose_an_unused_destination_and_ke_6161c527, (current.plan.destination).toString(),
            (if (current.plan.copyRequired) "The original will be retained as a recovery copy. " else "").toString()))
    }
}

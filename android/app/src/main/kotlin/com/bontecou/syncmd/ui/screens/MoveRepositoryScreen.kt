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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.text.format.Formatter
import com.bontecou.syncmd.R
import java.text.NumberFormat
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
    val review = settings.moveReview.collectAsState().value
    val journals by settings.moveJournals.collectAsState()
    val busy by settings.isLoading.collectAsState()
    val destructive by settings.destructiveBusy.collectAsState()
    val sharedError by settings.errorMessage.collectAsState()
    val preparationError by settings.moveReviewError.collectAsState()
    val error = preparationError ?: sharedError
    var location by rememberSaveable(path, recoveryId) { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<com.bontecou.syncmd.storage.RepositoryMoveReview?>(null) }
    val routeId = rememberSaveable(path, recoveryId) { java.util.UUID.randomUUID().toString() }
    val screenOwner = remember(settings, path, recoveryId) { Any() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnBack by rememberUpdatedState(onBack)
    DisposableEffect(settings, screenOwner) {
        settings.openMoveReview(screenOwner, routeId)
        onDispose { settings.closeMoveReview(screenOwner) }
    }
    LaunchedEffect(settings, screenOwner, lifecycleOwner) {
        settings.deliverMoveCompletion(screenOwner, lifecycleOwner.lifecycle) { latestOnBack() }
    }
    val current = review?.takeIf { if (recoveryId == null) it.plan.source == path else it.metadata.restoringRecoveryId == recoveryId }
    val pendingSource = if (recoveryId == null) path else journals.firstOrNull { it.plan.id == recoveryId }?.plan?.recovery?.path ?: current?.plan?.source
    val pending = journals.filter { it.phase != RepositoryMovePhase.DISCARDING_RECOVERY &&
        (it.phase != RepositoryMovePhase.COMMITTED || !it.plan.copyRequired) && it.plan.source == pendingSource }
    BackHandler(destructive) {}
    val c = Tide.colors
    Scaffold(modifier = Modifier.testTag("move-repository-screen"), containerColor = c.paper, contentColor = c.ink, topBar = {
        WrappingTopBar(title = stringResource(if (recoveryId == null) R.string.ui_move_repository_1c4a6083 else R.string.move_title_restore),
            backLabel = stringResource(R.string.publication_back), onBack = onBack, backEnabled = !destructive)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            VaultOpsNote(stringResource(if (recoveryId == null) R.string.move_intro else R.string.move_intro_restore), Modifier.padding(horizontal = 4.dp))
            if (path.isNotEmpty()) TideCard(padding = 16.dp) {
                SelectionContainer { Text(path, style = TideType.mono(12.5.sp).copy(textDirection = TextDirection.Ltr), color = c.ink) }
            }
            FolderLocationField(location, { location = it; settings.clearMoveReview(screenOwner) }, enabled = !busy, title = stringResource(R.string.ui_destination_parent_folder_c6c4f97e))
            if (busy) VaultOpsProgress(stringResource(if (destructive) R.string.move_progress_moving else R.string.move_progress_reviewing))
            if (pending.isEmpty()) VaultOpsButton(stringResource(R.string.ui_review_destination_59808b00), {
                if (recoveryId == null) settings.prepareMove(path, location, screenOwner) else settings.prepareRecoveryRestore(recoveryId, location, screenOwner)
            }, Modifier.testTag("move-review"), enabled = !busy)
            current?.let { item ->
                VaultOpsHeading(stringResource(R.string.ui_reviewed_destination_7173c3da), Modifier.padding(top = 8.dp))
                TideCard(padding = 16.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionContainer {
                            Text(item.plan.destination, style = TideType.mono(13.sp, FontWeight.Medium).copy(textDirection = TextDirection.Ltr), color = c.ink,
                                modifier = Modifier.testTag("move-destination"))
                        }
                        Text(pluralStringResource(R.plurals.move_plan_summary, item.plan.files,
                            NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(item.plan.files),
                            Formatter.formatFileSize(LocalContext.current, item.plan.bytes)),
                            style = TideType.meta, color = c.inkFaint)
                    }
                }
                VaultOpsNote(stringResource(if (item.plan.copyRequired) R.string.ui_this_crosses_storage_volumes_gitsync_md_wi_559c0a3d
                    else R.string.ui_the_folder_can_move_directly_on_this_stora_7b29fabb), Modifier.padding(horizontal = 4.dp))
                VaultOpsButton(stringResource(if (recoveryId == null) R.string.move_action else R.string.ui_restore_as_a_separate_repository_6253e37c), { confirming = item },
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
    val confirmation = confirming?.takeIf { it === current }
    if (confirmation != null) VaultOpsSheet(onDismissRequest = { confirming = null },
        title = stringResource(if (recoveryId == null) R.string.move_confirm_title else R.string.move_confirm_title_restore),
        icon = { TideModalIcon(glyph = "→", tint = c.ink) },
        confirmButton = { VaultOpsButton(stringResource(if (recoveryId == null) R.string.move_confirm_action else R.string.move_confirm_action_restore), { confirming = null; settings.moveReviewed(confirmation, screenOwner) }, Modifier.testTag("move-final-confirm")) },
        dismissButton = { VaultOpsTextButton(stringResource(R.string.publication_cancel), { confirming = null }, Modifier.fillMaxWidth()) }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The reviewed path is data: keep it LTR and selectable, separate from translated sentences.
            SelectionContainer { Text(confirmation.plan.destination, style = TideType.mono(12.5.sp).copy(textDirection = TextDirection.Ltr),
                modifier = Modifier.testTag("move-confirm-destination")) }
            if (confirmation.plan.copyRequired) Text(stringResource(R.string.move_confirm_retained))
            Text(stringResource(R.string.move_confirm_guidance))
        }
    }
}

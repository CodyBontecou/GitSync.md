package com.bontecou.syncmd.ui.screens

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.repositories.RepositoryMovePhase
import com.bontecou.syncmd.ui.components.FolderLocationField
import com.bontecou.syncmd.ui.components.SettingsCard
import com.bontecou.syncmd.ui.components.SettingsHint
import com.bontecou.syncmd.ui.components.SettingsIntro
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3BottomActionBar
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3Chevron
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3FilledButton
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3ListRow
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import java.text.NumberFormat

// ─── Move vault (board Storage: move · reviewed · confirm · moving · recovery) ──

/** Move a working copy (or restore a retained original): pick, review the exact destination, confirm. */
@Composable
fun MoveRepositoryScreen(path: String, recoveryId: String? = null, settings: SettingsViewModel, onBack: () -> Unit) {
    val review = settings.moveReview.collectAsState().value
    val journals by settings.moveJournals.collectAsState()
    val busy by settings.isLoading.collectAsState()
    val destructive by settings.destructiveBusy.collectAsState()
    val sharedError by settings.errorMessage.collectAsState()
    val preparationError by settings.moveReviewError.collectAsState()
    val repositories by settings.allRepositories.collectAsState()
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
    val vaultName = repositories.firstOrNull { it.path == path }?.let { it.alias.ifBlank { it.name } } ?: java.io.File(path).name.ifEmpty { null }
    BackHandler(destructive) {}
    val c = M3.colors
    Column(Modifier.fillMaxSize().background(c.surface).testTag("move-repository-screen")) {
        M3TopBar(stringResource(if (recoveryId == null) R.string.settings_m3_move_title else R.string.move_title_restore), subtitle = vaultName,
            onBack = if (destructive) null else onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SettingsIntro(stringResource(if (recoveryId == null) R.string.settings_m3_move_intro else R.string.move_intro_restore))
            if (path.isNotEmpty()) {
                M3SectionLabel(stringResource(R.string.settings_m3_move_from))
                SettingsCard {
                    SelectionContainer {
                        Text(path, Modifier.padding(horizontal = 20.dp, vertical = 16.dp), style = M3Type.code.copy(textDirection = TextDirection.Ltr), color = c.on)
                    }
                }
            }
            M3SectionLabel(stringResource(R.string.settings_m3_move_into))
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                FolderLocationField(location, { location = it; settings.clearMoveReview(screenOwner) }, enabled = !busy,
                    title = stringResource(R.string.ui_destination_parent_folder_c6c4f97e))
            }
            current?.let { item ->
                M3SectionLabel(stringResource(R.string.settings_m3_move_will_move_to))
                SettingsCard {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        SelectionContainer {
                            Text(item.plan.destination, Modifier.testTag("move-destination"),
                                style = M3Type.code.copy(fontWeight = FontWeight.Medium, textDirection = TextDirection.Ltr), color = c.on)
                        }
                        Text(pluralStringResource(R.plurals.move_plan_summary, item.plan.files,
                            NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(item.plan.files),
                            Formatter.formatFileSize(LocalContext.current, item.plan.bytes)),
                            Modifier.padding(top = 6.dp), style = M3Type.caption, color = c.onVar)
                    }
                }
                SettingsHint(stringResource(if (item.plan.copyRequired) R.string.settings_m3_move_copy_note else R.string.ui_the_folder_can_move_directly_on_this_stora_7b29fabb))
            }
            if (busy) Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) {
                val label = stringResource(if (destructive) R.string.settings_m3_move_progress else R.string.move_progress_reviewing)
                M3IndeterminateProgressBar(description = label)
                Text(label, Modifier.padding(top = 8.dp), style = M3Type.meta, color = c.onVar)
            }
            pending.forEach { journal ->
                M3Note(stringResource(R.string.settings_m3_move_interrupted), Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
                    tone = M3NoteTone.Ahead, icon = R.drawable.ic_m3_warning)
                SettingsCard(top = 12.dp) {
                    M3ListRow(stringResource(R.string.ui_finish_move_recovery_44113cea), meta = stringResource(R.string.settings_m3_move_finish_meta), metaMono = false,
                        leading = { M3Icon(R.drawable.ic_m3_refresh) }, trailing = { M3Chevron() }, enabled = !busy,
                        onClick = { settings.resumeMove(journal.plan.id) })
                    if (journal.phase in listOf(RepositoryMovePhase.PREPARED, RepositoryMovePhase.COPY_READY, RepositoryMovePhase.SOURCE_RETAINED)) {
                        M3ListRow(stringResource(R.string.ui_cancel_pending_move_0858583b), meta = stringResource(R.string.settings_m3_move_cancel_meta), metaMono = false,
                            leading = { M3Icon(R.drawable.ic_m3_close, tint = c.error) }, titleColor = c.error, divider = true, enabled = !busy,
                            onClick = { settings.cancelPendingMove(journal.plan.id) })
                    }
                }
            }
            error?.let { M3Note(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp).testTag("move-error"), tone = M3NoteTone.Warn) }
            Spacer(Modifier.height(32.dp))
        }
        if (pending.isEmpty()) M3BottomActionBar {
            when {
                destructive -> M3FilledButton(stringResource(R.string.settings_m3_move_moving_button), {}, Modifier.fillMaxWidth(), size = M3ButtonSize.XLarge, enabled = false)
                current != null -> M3FilledButton(stringResource(if (recoveryId == null) R.string.settings_m3_move_action else R.string.ui_restore_as_a_separate_repository_6253e37c),
                    { confirming = current }, Modifier.fillMaxWidth().testTag("move-confirm"), size = M3ButtonSize.XLarge,
                    icon = R.drawable.ic_m3_arrow_forward.takeIf { recoveryId == null }, enabled = !busy)
                else -> M3FilledButton(stringResource(R.string.ui_review_destination_59808b00), {
                    if (recoveryId == null) settings.prepareMove(path, location, screenOwner) else settings.prepareRecoveryRestore(recoveryId, location, screenOwner)
                }, Modifier.fillMaxWidth().testTag("move-review"), size = M3ButtonSize.XLarge, enabled = !busy)
            }
        }
    }
    val confirmation = confirming?.takeIf { it === current }
    if (confirmation != null) M3Dialog(onDismissRequest = { confirming = null },
        title = stringResource(if (recoveryId == null) R.string.settings_m3_move_confirm_title else R.string.move_confirm_title_restore),
        content = {
            // The reviewed path is data: keep it LTR and selectable, separate from translated sentences.
            SelectionContainer { Text(confirmation.plan.destination, Modifier.testTag("move-confirm-destination"),
                style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.on) }
            if (confirmation.plan.copyRequired) Text(stringResource(R.string.settings_m3_move_confirm_retained), Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.settings_m3_move_confirm_guidance), Modifier.padding(top = 12.dp))
        }) {
        M3DialogAction(stringResource(R.string.publication_cancel), { confirming = null })
        M3DialogAction(stringResource(if (recoveryId == null) R.string.move_confirm_action else R.string.move_confirm_action_restore),
            { confirming = null; settings.moveReviewed(confirmation, screenOwner) }, Modifier.testTag("move-final-confirm"), filled = true)
    }
}

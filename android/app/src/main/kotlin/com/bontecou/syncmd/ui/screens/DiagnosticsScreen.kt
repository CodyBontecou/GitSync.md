package com.bontecou.syncmd.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.diagnostics.DiagnosticEntry
import com.bontecou.syncmd.services.diagnostics.DiagnosticLevel
import com.bontecou.syncmd.services.diagnostics.DiagnosticOperation
import com.bontecou.syncmd.services.diagnostics.DiagnosticOutcome
import com.bontecou.syncmd.services.diagnostics.DiagnosticReason
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3BottomActionBar
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ChipRow
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3FilledButton
import com.bontecou.syncmd.ui.theme.M3FilterChip
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3Tag
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3TopBarTextAction
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.m3GroupedItem
import com.bontecou.syncmd.ui.theme.m3TopDivider
import com.bontecou.syncmd.ui.viewmodels.DiagnosticsViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// ─── Debug log (board 21) ───────────────────────────────────────────────────

/** Level chips: All, Warnings, "Errors 2". */
internal enum class DebugFilter { ALL, WARNINGS, ERRORS }

/**
 * [vaultNames] maps [com.bontecou.syncmd.services.diagnostics.Diagnostics.vaultKey] to the vault's
 * name, so rows read "09:42:18 · VoxVault" while the log itself stores no names.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, model: DiagnosticsViewModel = hiltViewModel(), vaultNames: Map<String, String> = emptyMap()) {
    val entries by model.store.entries.collectAsState()
    val problem by model.store.storageProblem.collectAsState()
    val busy by model.busy.collectAsState()
    val error by model.error.collectAsState()
    val notice by model.notice.collectAsState()
    var filter by remember { mutableStateOf(DebugFilter.ALL) }
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.diagnostics_share_title)
    val visible = visibleEntries(entries, filter)
    DiagnosticsContent(
        entries = entries, visible = visible, problem = problem, busy = busy, error = error, notice = notice, filter = filter, vaultNames = vaultNames,
        onBack = onBack,
        onFilter = { filter = it },
        onCopy = {
            model.exportVisible(visible) { text ->
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(shareTitle, text))
                model.copied()
            }
        },
        onShare = {
            model.exportVisible(visible) { text ->
                runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, shareTitle)
                }, shareTitle)) }
            }
        },
        onClear = { confirmClear = true },
    )
    if (confirmClear) M3Dialog(
        onDismissRequest = { confirmClear = false },
        title = stringResource(R.string.ui_clear_all_diagnostics_e2d6a185),
        icon = R.drawable.ic_m3_delete, iconTint = M3.colors.error,
        content = {
            Column(Modifier.testTag("diagnostics-clear-body")) {
                Text(if (problem) stringResource(R.string.diagnostics_clear_recovery)
                    else stringResource(R.string.diagnostics_clear_entries, entries.size), modifier = Modifier.testTag("diagnostics-clear-text"))
            }
        },
    ) {
        M3DialogAction(stringResource(R.string.publication_cancel), { confirmClear = false }, Modifier.testTag("diagnostics-cancel-clear"))
        M3DialogAction(stringResource(R.string.ui_clear_log_a8858ac7), { confirmClear = false; model.clear() }, Modifier.testTag("diagnostics-confirm-clear"),
            destructive = true)
    }
}

private fun visibleEntries(entries: List<DiagnosticEntry>, filter: DebugFilter) = entries.filter {
    when (filter) {
        DebugFilter.ALL -> true
        DebugFilter.WARNINGS -> it.level == DiagnosticLevel.WARNING
        DebugFilter.ERRORS -> it.level == DiagnosticLevel.ERROR
    }
}.asReversed()

@Composable
private fun DiagnosticsContent(
    entries: List<DiagnosticEntry>,
    visible: List<DiagnosticEntry>,
    problem: Boolean,
    busy: Boolean,
    error: Int?,
    notice: Int?,
    filter: DebugFilter,
    vaultNames: Map<String, String>,
    onBack: () -> Unit,
    onFilter: (DebugFilter) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    val c = M3.colors
    val locale = LocalConfiguration.current.locales[0]
    val errorCount = entries.count { it.level == DiagnosticLevel.ERROR }
    val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    val recentErrors = entries.count { it.level == DiagnosticLevel.ERROR && it.time >= dayAgo }
    Column(Modifier.fillMaxSize().background(c.surface).testTag("diagnostics-screen")) {
        M3TopBar(stringResource(R.string.settings_m3_debug_log),
            subtitle = pluralStringResource(R.plurals.settings_m3_entries, entries.size, entries.size) + " · " +
                pluralStringResource(R.plurals.settings_m3_debug_errors_day, recentErrors, recentErrors),
            onBack = onBack, modifier = Modifier.testTag("diagnostics-title")) {
            M3TopBarTextAction(stringResource(R.string.settings_m3_debug_clear), onClear, Modifier.testTag("diagnostics-clear"), destructive = true,
                enabled = !busy && (entries.isNotEmpty() || problem))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "chips") {
                M3ChipRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
                    M3FilterChip(stringResource(R.string.diagnostics_all), filter == DebugFilter.ALL, { onFilter(DebugFilter.ALL) },
                        Modifier.testTag("diagnostics-filter-ALL"), showCheck = false)
                    M3FilterChip(stringResource(R.string.diagnostics_warnings), filter == DebugFilter.WARNINGS, { onFilter(DebugFilter.WARNINGS) },
                        Modifier.testTag("diagnostics-filter-WARNING"), showCheck = false)
                    M3FilterChip(if (errorCount > 0) stringResource(R.string.settings_m3_debug_errors_count, errorCount) else stringResource(R.string.diagnostics_errors),
                        filter == DebugFilter.ERRORS, { onFilter(DebugFilter.ERRORS) }, Modifier.testTag("diagnostics-filter-ERROR"), showCheck = false)
                }
            }
            if (busy) item(key = "busy") { M3IndeterminateProgressBar(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) }
            if (problem) item(key = "problem") {
                M3Note(stringResource(R.string.ui_some_diagnostics_could_not_be_read_or_save_19e3372d), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    .testTag("diagnostics-storage-problem"), tone = M3NoteTone.Warn)
            }
            error?.let { item(key = "error") { M3Note(stringResource(it), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("diagnostics-error"), tone = M3NoteTone.Warn) } }
            notice?.let { item(key = "notice") { M3Note(stringResource(it), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("diagnostics-notice"), tone = M3NoteTone.Ok) } }
            item(key = "gap") { Spacer(Modifier.height(12.dp)) }
            if (visible.isEmpty()) item(key = "empty") {
                Text(stringResource(if (entries.isEmpty()) R.string.diagnostics_empty else R.string.diagnostics_filtered_empty),
                    Modifier.padding(horizontal = 16.dp).m3GroupedItem(0, 1, c.cLow).padding(horizontal = 20.dp, vertical = 28.dp).fillMaxWidth().testTag("diagnostics-empty"),
                    style = M3Type.bodyMedium.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), color = c.onVar)
            }
            itemsIndexed(visible, key = { _, entry -> entry.id }) { index, entry ->
                DebugLogRow(entry, locale, vaultNames, Modifier.padding(horizontal = 16.dp).m3GroupedItem(index, visible.size, c.cLow), divider = index > 0)
            }
        }
        M3BottomActionBar {
            M3FilledButton(stringResource(R.string.settings_m3_debug_share), onShare, Modifier.fillMaxWidth().testTag("diagnostics-share"), size = M3ButtonSize.XLarge,
                icon = R.drawable.ic_m3_share, enabled = !busy && visible.isNotEmpty())
            M3TextButton(stringResource(R.string.settings_m3_debug_copy), onCopy, Modifier.align(Alignment.CenterHorizontally).testTag("diagnostics-copy"),
                enabled = !busy && visible.isNotEmpty())
        }
    }
}

/** "09:42:18 · VoxVault" + level tag (INFO muted, WARN tertiary, ERROR error, BG primary), then the mono message. */
@Composable
private fun DebugLogRow(entry: DiagnosticEntry, locale: Locale, vaultNames: Map<String, String>, modifier: Modifier, divider: Boolean) {
    val c = M3.colors
    val now = System.currentTimeMillis()
    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM, locale).format(Date(entry.time))
    val when_ = if (now - entry.time < 24 * 60 * 60 * 1000L) time else stringResource(R.string.settings_m3_debug_day_time,
        DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(entry.time)), DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(entry.time)))
    val source = when {
        entry.vault != null -> vaultNames[entry.vault] ?: stringResource(R.string.settings_m3_debug_removed_vault)
        entry.background -> stringResource(R.string.settings_m3_debug_background)
        else -> stringResource(R.string.settings_m3_debug_app)
    }
    val message = listOf(entry.operation.label(), entry.outcome.label(), entry.reason.label()).filter { it.isNotEmpty() }.joinToString(" · ")
    SelectionContainer(modifier.testTag("diagnostic-entry-${entry.id}")) {
        Column(Modifier.fillMaxWidth().m3TopDivider(divider, c.outlineVar).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.l10n_meta_pair, when_, source), Modifier.weight(1f), style = M3Type.meta.copy(textDirection = TextDirection.ContentOrLtr), color = c.onVar, maxLines = 1)
                if (entry.background) M3Tag(stringResource(R.string.settings_m3_debug_bg), tone = M3Tone.Behind)
                when (entry.level) {
                    DiagnosticLevel.ERROR -> M3Tag(stringResource(R.string.settings_m3_debug_error), tone = M3Tone.Warn)
                    DiagnosticLevel.WARNING -> M3Tag(stringResource(R.string.settings_m3_debug_warn), tone = M3Tone.Ahead)
                    DiagnosticLevel.INFO -> if (!entry.background) M3Tag(stringResource(R.string.settings_m3_debug_info_tag), tone = M3Tone.Muted)
                }
            }
            Text(message, Modifier.padding(top = 4.dp), style = M3Type.code, color = c.on)
        }
    }
}

@Composable
private fun DiagnosticOperation.label(): String = stringResource(when (this) {
    DiagnosticOperation.APP -> R.string.ui_app_0d04bfeb
    DiagnosticOperation.CLONE -> R.string.diagnostics_operation_clone
    DiagnosticOperation.FETCH -> R.string.diagnostics_operation_fetch
    DiagnosticOperation.PULL -> R.string.pull_screen
    DiagnosticOperation.PUSH -> R.string.diagnostics_operation_push
    DiagnosticOperation.STAGE -> R.string.ui_stage_de838855
    DiagnosticOperation.UNSTAGE -> R.string.ui_unstage_033f86f9
    DiagnosticOperation.DISCARD -> R.string.ui_discard_eb1a70e3
    DiagnosticOperation.COMMIT -> R.string.commit_screen
    DiagnosticOperation.BRANCH -> R.string.ui_branch_52656e81
    DiagnosticOperation.MERGE -> R.string.ui_merge_8851aaa7
    DiagnosticOperation.REBASE -> R.string.diagnostics_operation_rebase
    DiagnosticOperation.CONFLICT -> R.string.diagnostics_operation_conflict
    DiagnosticOperation.LFS -> R.string.diagnostics_operation_lfs
    DiagnosticOperation.STASH -> R.string.stash_screen
    DiagnosticOperation.TAG -> R.string.diagnostics_operation_tag
    DiagnosticOperation.REVERT -> R.string.diagnostics_operation_revert
})

@Composable
private fun DiagnosticOutcome.label(): String = stringResource(when (this) {
    DiagnosticOutcome.STARTED -> R.string.diagnostics_outcome_started
    DiagnosticOutcome.COMPLETED -> R.string.diagnostics_outcome_completed
    DiagnosticOutcome.STOPPED -> R.string.diagnostics_outcome_stopped
    DiagnosticOutcome.ATTENTION -> R.string.diagnostics_outcome_attention
    DiagnosticOutcome.CANCELLED -> R.string.diagnostics_outcome_cancelled
})

@Composable
private fun DiagnosticReason.label(): String = if (this == DiagnosticReason.NONE) "" else stringResource(when (this) {
    DiagnosticReason.NONE -> error("No label for an absent reason")
    DiagnosticReason.LOCAL_CHANGES -> R.string.diagnostics_reason_local_changes
    DiagnosticReason.LFS_CONTENTS -> R.string.diagnostics_reason_lfs_contents
    DiagnosticReason.LFS_LOCK -> R.string.diagnostics_reason_lfs_lock
    DiagnosticReason.TRUST_REQUIRED -> R.string.diagnostics_reason_trust
    DiagnosticReason.CANCELLED -> R.string.diagnostics_reason_cancelled
    DiagnosticReason.NETWORK -> R.string.diagnostics_reason_network
    DiagnosticReason.STORAGE -> R.string.diagnostics_reason_storage
    DiagnosticReason.REVIEW_REQUIRED -> R.string.diagnostics_reason_review
    DiagnosticReason.UNKNOWN -> R.string.diagnostics_reason_unknown
})

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewEntries = listOf(
    DiagnosticEntry(operation = DiagnosticOperation.COMMIT, outcome = DiagnosticOutcome.COMPLETED, vault = "0000000000000001"),
    DiagnosticEntry(operation = DiagnosticOperation.PUSH, outcome = DiagnosticOutcome.STOPPED, reason = DiagnosticReason.NETWORK, vault = "0000000000000001"),
    DiagnosticEntry(operation = DiagnosticOperation.PULL, outcome = DiagnosticOutcome.COMPLETED, vault = "0000000000000002", background = true),
    DiagnosticEntry(operation = DiagnosticOperation.FETCH, outcome = DiagnosticOutcome.COMPLETED, vault = "0000000000000002"),
)
private val previewNames = mapOf("0000000000000001" to "notes", "0000000000000002" to "VoxVault")

@Composable
private fun DiagnosticsPreview(entries: List<DiagnosticEntry>, filter: DebugFilter = DebugFilter.ALL, problem: Boolean = false) =
    DiagnosticsContent(entries, visibleEntries(entries, filter), problem, false, null, null, filter, previewNames, {}, {}, {}, {}, {})

@Preview(name = "21 · Debug log", heightDp = 915)
@Composable private fun DiagnosticsEntriesPreview() = SyncMdTheme(darkTheme = false) { DiagnosticsPreview(previewEntries) }

@Preview(name = "21 · Debug log · errors · dark", heightDp = 915)
@Composable private fun DiagnosticsEntriesDarkPreview() = SyncMdTheme(darkTheme = true) { DiagnosticsPreview(previewEntries, DebugFilter.ERRORS) }

@Preview(name = "Debug log · empty, unreadable")
@Composable private fun DiagnosticsEmptyPreview() = SyncMdTheme(darkTheme = false) { DiagnosticsPreview(emptyList(), problem = true) }

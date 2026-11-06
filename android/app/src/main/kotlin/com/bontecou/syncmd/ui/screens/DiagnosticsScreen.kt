package com.bontecou.syncmd.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.diagnostics.*
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.theme.BDestructiveButton
import com.bontecou.syncmd.ui.theme.BGhostButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.DiagnosticsViewModel
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

// iOS DebugLogView — summary, filter chips, one card of mono log lines, copy/share pills.

@Composable
fun DiagnosticsScreen(onBack: () -> Unit, model: DiagnosticsViewModel = hiltViewModel()) {
    val entries by model.store.entries.collectAsState()
    val problem by model.store.storageProblem.collectAsState()
    val busy by model.busy.collectAsState()
    val error by model.error.collectAsState()
    val notice by model.notice.collectAsState()
    var filter by remember { mutableStateOf<DiagnosticLevel?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.diagnostics_share_title)
    DiagnosticsContent(
        entries = entries, problem = problem, busy = busy, error = error, notice = notice, filter = filter,
        onBack = onBack,
        onFilter = { filter = it },
        onCopy = {
            model.export(filter) { text ->
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(shareTitle, text))
                model.copied()
            }
        },
        onShare = {
            model.export(filter) { text -> context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, shareTitle)
            }, shareTitle)) }
        },
        onClear = { confirmClear = true },
    )
    if (confirmClear) TideDialog(
        onDismissRequest = { confirmClear = false },
        icon = { TideModalIcon() },
        title = stringResource(R.string.ui_clear_all_diagnostics_e2d6a185),
        text = {
            Column(Modifier.testTag("diagnostics-clear-body")) {
                Text(if (problem) stringResource(R.string.diagnostics_clear_recovery)
                    else stringResource(R.string.diagnostics_clear_entries, entries.size), modifier = Modifier.testTag("diagnostics-clear-text"))
            }
        },
        confirmButton = {
            BDestructiveButton(stringResource(R.string.ui_clear_log_a8858ac7), Modifier.testTag("diagnostics-confirm-clear"),
                onClick = { confirmClear = false; model.clear() })
        },
        dismissButton = {
            BGhostButton(stringResource(R.string.publication_cancel), Modifier.fillMaxWidth().testTag("diagnostics-cancel-clear"),
                onClick = { confirmClear = false })
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticsContent(
    entries: List<DiagnosticEntry>,
    problem: Boolean,
    busy: Boolean,
    error: Int?,
    notice: Int?,
    filter: DiagnosticLevel?,
    onBack: () -> Unit,
    onFilter: (DiagnosticLevel?) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    val c = Tide.colors
    val visible = entries.filter { filter == null || it.level == filter }.asReversed()
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val errorCount = entries.count { it.level == DiagnosticLevel.ERROR }
    Scaffold(containerColor = c.paper, topBar = {
        WrappingTopBar(title = stringResource(R.string.ui_diagnostics_268f14bb),
            backLabel = stringResource(R.string.publication_back), onBack = onBack,
            backModifier = Modifier.testTag("diagnostics-back"), titleModifier = Modifier.testTag("diagnostics-title"))
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("diagnostics-screen"),
            contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 0.dp, bottom = 32.dp),
        ) {
            item(key = "count") {
                Text(stringResource(R.string.ui_1_s_shown_2_s_stored_c5d98835, numbers.format(visible.size), numbers.format(entries.size)),
                    modifier = Modifier.padding(horizontal = 4.dp).testTag("diagnostics-count"),
                    style = TideType.mono(12.sp), color = c.inkMid)
            }
            item(key = "intro") {
                Text(stringResource(R.string.ui_recent_operation_outcomes_on_this_device_t_2a52131b),
                    Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), style = TideType.sans(14.sp), color = c.inkMid)
            }
            item(key = "filters") {
                FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf<DiagnosticLevel?>(null) + DiagnosticLevel.values()).forEach { level ->
                        DiagnosticsChip(
                            label = level?.label() ?: stringResource(R.string.diagnostics_all),
                            count = if (level == DiagnosticLevel.ERROR && errorCount > 0) errorCount else null,
                            dot = level?.tint(),
                            selected = filter == level,
                            onClick = { onFilter(level) },
                            modifier = Modifier.testTag("diagnostics-filter-${level?.name ?: "ALL"}"),
                        )
                    }
                }
            }
            item(key = "actions") {
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsAreaPill(stringResource(R.string.ui_copy_visible_29e7587c), onCopy, Modifier.testTag("diagnostics-copy"),
                        enabled = !busy && visible.isNotEmpty())
                    SettingsAreaPill(stringResource(R.string.ui_share_visible_4fcf9b94), onShare, Modifier.testTag("diagnostics-share"),
                        style = SettingsPillStyle.INK, enabled = !busy && visible.isNotEmpty())
                    SettingsAreaPill(stringResource(R.string.ui_clear_all_29a390f9), onClear, Modifier.testTag("diagnostics-clear"),
                        style = SettingsPillStyle.DANGER_TEXT, enabled = !busy && (entries.isNotEmpty() || problem))
                }
            }
            if (busy) item(key = "busy") { SettingsAreaProgress(Modifier.padding(top = 10.dp)) }
            if (problem) item(key = "problem") {
                SettingsAreaNotice(stringResource(R.string.ui_some_diagnostics_could_not_be_read_or_save_19e3372d), Modifier.padding(top = 10.dp),
                    tint = c.danger, textModifier = Modifier.testTag("diagnostics-storage-problem"))
            }
            error?.let { item(key = "error") {
                SettingsAreaNotice(stringResource(it), Modifier.padding(top = 10.dp), tint = c.danger, textModifier = Modifier.testTag("diagnostics-error"))
            } }
            notice?.let { item(key = "notice") {
                SettingsAreaNotice(stringResource(it), Modifier.padding(top = 10.dp), tint = c.synced, textModifier = Modifier.testTag("diagnostics-notice"))
            } }
            item(key = "gap") { Box(Modifier.height(12.dp)) }
            if (visible.isEmpty()) item(key = "empty") {
                val shape = RoundedCornerShape(Tide.radiusCard)
                Column(Modifier.fillMaxWidth().clip(shape).background(c.card, shape).padding(horizontal = 16.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(if (entries.isEmpty()) R.string.diagnostics_empty else R.string.diagnostics_filtered_empty),
                        modifier = Modifier.testTag("diagnostics-empty"),
                        style = TideType.sans(14.sp, FontWeight.Medium), color = c.inkMid, textAlign = TextAlign.Center)
                }
            }
            itemsIndexed(visible, key = { _, entry -> entry.id }) { index, entry ->
                DiagnosticLogRow(entry, locale, first = index == 0, last = index == visible.lastIndex)
            }
        }
    }
}

/** One mono log line inside a continuous card; error rows get a red wash. */
@Composable
private fun DiagnosticLogRow(entry: DiagnosticEntry, locale: Locale, first: Boolean, last: Boolean) {
    val c = Tide.colors
    val radius = Tide.radiusCard
    val shape = RoundedCornerShape(
        topStart = if (first) radius else 0.dp, topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp, bottomEnd = if (last) radius else 0.dp,
    )
    val isError = entry.level == DiagnosticLevel.ERROR
    Column(Modifier.fillMaxWidth().testTag("diagnostic-entry-${entry.id}").clip(shape).background(c.card, shape)) {
        SelectionContainer {
            Column(
                Modifier.fillMaxWidth()
                    .background(if (isError) c.danger.copy(alpha = 0.08f) else Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale).format(Date(entry.time)),
                        Modifier.weight(1f), style = TideType.mono(11.5.sp), color = if (isError) c.danger else c.inkFaint)
                    Text(entry.level.label(), style = TideType.mono(11.5.sp, FontWeight.SemiBold), color = entry.level.tint())
                }
                Text("${entry.operation.label()} · ${entry.outcome.label()}", style = TideType.mono(12.5.sp, FontWeight.Medium),
                    color = if (isError) c.danger else c.ink)
                if (entry.reason != DiagnosticReason.NONE) Text(entry.reason.label(), style = TideType.mono(11.sp), color = c.inkMid)
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

@Composable
private fun DiagnosticsChip(label: String, count: Int?, dot: Color?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // M3 filter chip (board "21 · Debug log").
    com.bontecou.syncmd.ui.theme.TideFilterChip(label, selected, onClick, modifier, count = count, dot = dot)
}

@Composable
private fun DiagnosticLevel.tint(): Color = when (this) {
    DiagnosticLevel.INFO -> Tide.colors.synced
    DiagnosticLevel.WARNING -> Tide.colors.ahead
    DiagnosticLevel.ERROR -> Tide.colors.danger
}

@Composable
private fun DiagnosticLevel.label(): String = stringResource(when (this) {
    DiagnosticLevel.INFO -> R.string.ui_info_170322a3
    DiagnosticLevel.WARNING -> R.string.diagnostics_warnings
    DiagnosticLevel.ERROR -> R.string.diagnostics_errors
})

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
    DiagnosticEntry(operation = DiagnosticOperation.CLONE, outcome = DiagnosticOutcome.COMPLETED),
    DiagnosticEntry(operation = DiagnosticOperation.PULL, outcome = DiagnosticOutcome.ATTENTION, reason = DiagnosticReason.LOCAL_CHANGES),
    DiagnosticEntry(operation = DiagnosticOperation.PUSH, outcome = DiagnosticOutcome.STOPPED, reason = DiagnosticReason.NETWORK),
)

@Composable
private fun DiagnosticsPreview(entries: List<DiagnosticEntry>, busy: Boolean = false, problem: Boolean = false, filter: DiagnosticLevel? = null, notice: Int? = null) =
    DiagnosticsContent(entries, problem, busy, null, notice, filter, {}, {}, {}, {}, {})

@Preview(name = "Diagnostics · entries", heightDp = 1000)
@Composable private fun DiagnosticsEntriesPreview() = SyncMdTheme(darkTheme = false) { DiagnosticsPreview(previewEntries, notice = R.string.diagnostics_copied) }

@Preview(name = "Diagnostics · entries · dark", heightDp = 1000)
@Composable private fun DiagnosticsEntriesDarkPreview() = SyncMdTheme(darkTheme = true) { DiagnosticsPreview(previewEntries, filter = DiagnosticLevel.ERROR) }

@Preview(name = "Diagnostics · empty")
@Composable private fun DiagnosticsEmptyPreview() = SyncMdTheme(darkTheme = false) { DiagnosticsPreview(emptyList()) }

@Preview(name = "Diagnostics · running, unreadable · dark")
@Composable private fun DiagnosticsBusyDarkPreview() = SyncMdTheme(darkTheme = true) { DiagnosticsPreview(emptyList(), busy = true, problem = true) }

package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.files.FileInsights
import com.bontecou.syncmd.services.files.WorkingFileDiff
import com.bontecou.syncmd.services.files.WorkingLine
import com.bontecou.syncmd.services.files.WorkingLineKind
import com.bontecou.syncmd.ui.components.RevertConfirmDialog
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3BottomToolbar
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3Segmented
import com.bontecou.syncmd.ui.theme.M3Spinner
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel
import com.bontecou.syncmd.ui.viewmodels.WorkingDiffViewModel

// ─── Diff (board 07) ────────────────────────────────────────────────────────

/**
 * One changed file compared with its last commit: "+8 −3 vs last commit a41c9e2", Changes /
 * Whole file / Preview, "folder/ · i of n" with previous and next file, and a footer with
 * Discard (this file only, through the reviewed discard) and Open in editor.
 */
@Composable
fun DiffScreen(
    repositoryPath: String,
    filePath: String,
    onNavigateBack: () -> Unit,
    /** True compares the last commit with the staged index (Git tools); otherwise with the working copy. */
    staged: Boolean? = null,
    diffVM: DiffViewModel = hiltViewModel(),
    /** Opens the file in the editor ("Open in editor"). */
    onOpenEditor: (() -> Unit)? = null,
    /** False inside a list-detail pane, where the list stays beside the diff. */
    showBack: Boolean = true,
    /** Opens a given file in the editor; used after moving to another file with previous / next. */
    onOpenEditorFor: ((String) -> Unit)? = null,
    workingVM: WorkingDiffViewModel = hiltViewModel(),
) {
    var current by rememberSaveable(filePath) { mutableStateOf(filePath) }
    LaunchedEffect(repositoryPath) { if (repositoryPath.isNotBlank()) diffVM.setRepositoryPath(repositoryPath) }
    LaunchedEffect(repositoryPath, current, staged) { workingVM.load(repositoryPath, current, staged) }

    val working by workingVM.state.collectAsState()
    val changes by diffVM.diff.collectAsState()
    val diffError = diffVM.errorMessage.collectAsState().value?.asString()
    val discardReview by diffVM.discardReview.collectAsState()
    val reviewingDiscard by diffVM.isReviewingDiscard.collectAsState()
    val discarding by diffVM.isDiscarding.collectAsState()
    val isReverting = reviewingDiscard || discarding
    DisposableEffect(repositoryPath, current) { onDispose { diffVM.dismissDiscard() } }

    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(repositoryPath) {
        // A periodic read must not close a frozen review. Confirmation revalidates its exact revision.
        if (!isReverting) {
            diffVM.refreshRepositoryPath(repositoryPath)
            workingVM.load(repositoryPath, current, staged)
        }
    }

    // The files on this phone that differ from the last commit, in the Changes order.
    val paths = changes?.files?.map { it.filePath }?.distinct().orEmpty()
    val index = paths.indexOf(current)
    val openEditor: (() -> Unit)? = when {
        onOpenEditorFor != null -> { { onOpenEditorFor(current) } }
        current == filePath -> onOpenEditor
        else -> null
    }

    DiffContent(
        filePath = current,
        position = if (index >= 0 && paths.size > 1) index to paths.size else null,
        loading = working.loading && working.diff == null,
        failed = working.failed,
        diff = working.diff?.takeIf { working.path == current },
        isReverting = isReverting,
        showBack = showBack,
        onBack = onNavigateBack,
        onPrevious = { if (index > 0) current = paths[index - 1] },
        onNext = { if (index >= 0 && index < paths.lastIndex) current = paths[index + 1] },
        onDiscard = { diffVM.requestDiscard(current) },
        onOpenEditor = openEditor,
    )

    if (diffError != null) {
        M3Dialog(
            onDismissRequest = diffVM::clearError,
            title = stringResource(R.string.ui_error_54a0e8c1),
            content = { Text(diffError, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).testTag("diff-operation-error")) },
        ) { M3DialogAction(stringResource(R.string.ui_ok_565339bc), diffVM::clearError) }
    }
    discardReview?.takeIf { it.filePath == current }?.let { review ->
        RevertConfirmDialog(
            review = review,
            onConfirmDiscard = { stashFirst ->
                diffVM.confirmDiscard(review, stashFirst = stashFirst, onComplete = {
                    // Move on to the next changed file when there is one; otherwise leave.
                    val rest = paths.filter { it != current }
                    if (rest.isEmpty()) onNavigateBack() else current = rest[index.coerceIn(0, rest.lastIndex)]
                })
            },
            onDismiss = { diffVM.dismissDiscard(review) },
        )
    }
}

private enum class DiffView { CHANGES, WHOLE, PREVIEW }

/** Stateless body of the diff screen. */
@Composable
private fun DiffContent(
    filePath: String,
    position: Pair<Int, Int>?,
    loading: Boolean,
    failed: Boolean,
    diff: WorkingFileDiff?,
    isReverting: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDiscard: () -> Unit,
    onOpenEditor: (() -> Unit)?,
) {
    val c = M3.colors
    val filename = filePath.substringAfterLast('/').ifEmpty { filePath }
    val directory = filePath.substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else "$it/" }
    val markdown = isMarkdownPath(filePath)
    var view by rememberSaveable(filePath) { mutableStateOf(DiffView.CHANGES) }
    if (!markdown && view == DiffView.PREVIEW) view = DiffView.CHANGES

    Column(Modifier.fillMaxSize().background(c.surface)) {
        M3TopBar(
            title = filename,
            subtitle = position?.let { (i, n) -> stringResource(R.string.diff_m3_position, directory, i + 1, n) } ?: directory,
            onBack = if (showBack) onBack else null,
            modifier = Modifier.testTag("diff-title"),
            actions = {
                if (position != null) {
                    M3IconButton(R.drawable.ic_m3_chevron_left, stringResource(R.string.diff_m3_previous), onPrevious,
                        Modifier.testTag("diff-previous"), enabled = position.first > 0)
                    M3IconButton(R.drawable.ic_m3_chevron_right, stringResource(R.string.diff_m3_next), onNext,
                        Modifier.testTag("diff-next"), enabled = position.first < position.second - 1)
                }
            },
        )
        if (diff != null && !diff.binary) {
            val a11y = stringResource(R.string.diff_m3_stats_a11y, diff.added, diff.removed)
            val against = diff.headShortHash?.let { stringResource(R.string.diff_m3_vs_last_commit, it) } ?: stringResource(R.string.diff_m3_vs_nothing)
            val statsDescription = stringResource(R.string.l10n_a11y_pair, a11y, against)
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp).clearAndSetSemantics { contentDescription = statsDescription }.testTag("diff-stats"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val style = M3Type.code.copy(textDirection = TextDirection.Ltr)
                Text(stringResource(R.string.l10n_added_count, diff.added), style = style.copy(fontWeight = FontWeight.SemiBold), color = c.addInk)
                Text(stringResource(R.string.l10n_removed_count, diff.removed), style = style.copy(fontWeight = FontWeight.SemiBold), color = c.delInk)
                Text(against, style = style, color = c.onVar)
            }
        }
        val options = buildList {
            add(stringResource(R.string.diff_m3_changes)); add(stringResource(R.string.diff_m3_whole_file))
            if (markdown) add(stringResource(R.string.diff_m3_preview))
        }
        M3Segmented(options, view.ordinal, { view = DiffView.values()[it] }, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("diff-view"))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { M3Spinner(size = 24.dp, color = c.onVar) }
                failed || diff == null -> DiffNote(stringResource(R.string.diff_m3_load_failed), M3NoteTone.Warn)
                diff.binary -> DiffNote(stringResource(R.string.diff_m3_binary), M3NoteTone.Muted)
                view == DiffView.PREVIEW -> {
                    if (diff.newText == null) DiffNote(stringResource(R.string.diff_m3_deleted), M3NoteTone.Muted)
                    else MarkdownPreview(diff.newText.orEmpty(), Modifier.testTag("diff-preview"), changedLines = diff.changedLines)
                }
                else -> {
                    val rows = if (view == DiffView.CHANGES) diff.changes else diff.whole
                    if (rows.isEmpty()) DiffNote(stringResource(if (diff.deleted) R.string.diff_m3_deleted else R.string.diff_m3_no_changes), M3NoteTone.Muted)
                    else LazyColumn(Modifier.fillMaxSize().testTag("diff-lines"), contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)) { diffCard(rows) }
                }
            }
        }

        M3BottomToolbar {
            M3TextButton(stringResource(R.string.diff_m3_discard), onDiscard, Modifier.testTag("diff-discard"),
                enabled = !isReverting && !loading && diff != null, destructive = true)
            Spacer(Modifier.weight(1f))
            if (onOpenEditor != null && diff?.binary != true && diff?.deleted != true) {
                M3Button(stringResource(R.string.m3_open_in_editor), onOpenEditor, Modifier.testTag("diff-open-editor"),
                    style = M3ButtonStyle.PrimaryContainer, size = M3ButtonSize.Large, icon = R.drawable.ic_m3_edit, shape = RoundedCornerShape(16.dp))
            }
        }
    }
}

@Composable
private fun DiffNote(text: String, tone: M3NoteTone) {
    M3Note(text, Modifier.padding(horizontal = 16.dp, vertical = 16.dp).testTag("diff-state"), tone = tone)
}

/** The lines as one 16dp-cornered cLow card spread across lazy items: mono 13/22, number, sign, text. */
private fun LazyListScope.diffCard(rows: List<WorkingLine>) {
    itemsIndexed(rows) { index, row ->
        val c = M3.colors
        val r = 16.dp
        val shape = when {
            rows.size == 1 -> RoundedCornerShape(r)
            index == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
            index == rows.lastIndex -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
            else -> RoundedCornerShape(0.dp)
        }
        val (bg, ink) = when (row.kind) {
            WorkingLineKind.ADDED -> c.addBg to c.addInk
            WorkingLineKind.REMOVED -> c.delBg to c.delInk
            WorkingLineKind.HUNK -> c.cHighest to c.onVar
            WorkingLineKind.CONTEXT -> c.cLow to c.on
        }
        val number = when (row.kind) {
            WorkingLineKind.HUNK -> "@@"
            WorkingLineKind.REMOVED -> ""
            else -> row.newNumber?.toString().orEmpty()
        }
        val text = when (row.kind) {
            WorkingLineKind.HUNK -> if (row.text.isBlank()) stringResource(R.string.diff_m3_hunk_line, row.newNumber ?: 1)
                else stringResource(R.string.diff_m3_hunk, row.text, row.newNumber ?: 1)
            else -> row.text.ifEmpty { " " }
        }
        val sign = when (row.kind) { WorkingLineKind.ADDED -> "+"; WorkingLineKind.REMOVED -> "−"; else -> "" }
        val style = M3Type.code.copy(lineHeight = 22.sp, textDirection = TextDirection.Ltr)
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(c.cLow, shape).background(bg, shape)
                .padding(top = 1.dp, bottom = 1.dp, end = 12.dp),
        ) {
            Text(number, style = style.copy(fontSize = 11.sp), color = c.onVar, textAlign = TextAlign.End, maxLines = 1,
                modifier = Modifier.width(30.dp).padding(end = 8.dp))
            Text(sign, style = style.copy(fontWeight = FontWeight.SemiBold), color = ink, modifier = Modifier.width(16.dp))
            Text(text, style = if (row.kind == WorkingLineKind.HUNK) style.copy(fontSize = 12.sp) else style, color = ink, modifier = Modifier.weight(1f))
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewDiff = FileInsights.compare(
    "ideas/voice-memo-pipeline.md",
    "# Voice memo pipeline\n\n## Steps\n1. Record in Voice Memos\n2. Transcribe by hand\n3. Paste into daily note\n\n## Open questions\n- Where do audio files live?\n- Is LFS worth it?\n- Share with Sam?\n",
    "# Voice memo pipeline\n\n## Steps\n1. Record in Voice Memos\n2. Whisper → transcript.md\n3. Tag speakers (A/B)\n4. Link from the daily note\n\n## Open questions\n- Where do audio files live?\n- LFS for anything over 5 MB ✓\n- Speaker names from contacts?\n- Share with Sam?\n",
    headShortHash = "a41c9e2",
)

@Composable
private fun DiffPreview(diff: WorkingFileDiff?, loading: Boolean = false) {
    DiffContent("ideas/voice-memo-pipeline.md", 1 to 3, loading, false, diff, false, true, {}, {}, {}, {}, {})
}

@Preview(name = "Diff · light", heightDp = 800) @Composable private fun DiffLight() = SyncMdTheme(darkTheme = false) { DiffPreview(previewDiff) }
@Preview(name = "Diff · dark", heightDp = 800) @Composable private fun DiffDark() = SyncMdTheme(darkTheme = true) { DiffPreview(previewDiff) }
@Preview(name = "Diff · loading", heightDp = 400) @Composable private fun DiffLoading() = SyncMdTheme { DiffPreview(null, loading = true) }

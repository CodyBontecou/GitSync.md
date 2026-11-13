package com.bontecou.syncmd.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.files.WorkingFileDiff
import com.bontecou.syncmd.services.files.WorkingLine
import com.bontecou.syncmd.services.files.WorkingLineKind
import com.bontecou.syncmd.ui.components.RevertConfirmDialog
import com.bontecou.syncmd.ui.screens.MarkdownPreview
import com.bontecou.syncmd.ui.screens.isMarkdownPath
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3Spinner
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel
import com.bontecou.syncmd.ui.viewmodels.WorkingDiffViewModel

/** How the diff detail is framed: the list-detail pane (Tablet, FoldVault) or the tabletop top half. */
enum class DiffPaneStyle { Pane, Tabletop }

/**
 * One changed file beside the change list (boards Tablet, FoldVault) or above the fold
 * (FoldTabletop). Uses the phone diff's view models: the working-copy comparison and the reviewed,
 * stash-first discard.
 */
@Composable
fun DiffPane(
    repositoryPath: String,
    filePath: String,
    wide: Boolean,
    onOpenEditor: (String) -> Unit,
    /** After a discard: the next changed file, or null when none is left. */
    onDiscarded: (String?) -> Unit,
    modifier: Modifier = Modifier,
    style: DiffPaneStyle = DiffPaneStyle.Pane,
    /** Tabletop: previous / next file. */
    onSelectFile: (String) -> Unit = {},
    diffVM: DiffViewModel = hiltViewModel(),
    workingVM: WorkingDiffViewModel = hiltViewModel(),
) {
    LaunchedEffect(repositoryPath) { if (repositoryPath.isNotBlank()) diffVM.refreshRepositoryPath(repositoryPath) }
    LaunchedEffect(repositoryPath, filePath) { workingVM.load(repositoryPath, filePath) }
    val working by workingVM.state.collectAsState()
    val changes by diffVM.diff.collectAsState()
    val error = diffVM.errorMessage.collectAsState().value?.asString()
    val review by diffVM.discardReview.collectAsState()
    val reviewing by diffVM.isReviewingDiscard.collectAsState()
    val discarding by diffVM.isDiscarding.collectAsState()
    val busy = reviewing || discarding
    DisposableEffect(repositoryPath, filePath) { onDispose { diffVM.dismissDiscard() } }
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(repositoryPath) {
        if (!busy) { diffVM.refreshRepositoryPath(repositoryPath); workingVM.load(repositoryPath, filePath) }
    }
    val paths = changes?.files?.map { it.filePath }?.distinct().orEmpty()
    val index = paths.indexOf(filePath)

    DiffPaneContent(
        filePath = filePath,
        diff = working.diff?.takeIf { working.path == filePath },
        loading = working.loading && working.diff == null,
        failed = working.failed,
        wide = wide,
        style = style,
        position = if (index >= 0) index to paths.size else null,
        busy = busy,
        onPrevious = { if (index > 0) onSelectFile(paths[index - 1]) },
        onNext = { if (index >= 0 && index < paths.lastIndex) onSelectFile(paths[index + 1]) },
        onDiscard = { diffVM.requestDiscard(filePath) },
        onOpenEditor = { onOpenEditor(filePath) },
        modifier = modifier,
    )

    if (error != null) M3Dialog(
        onDismissRequest = diffVM::clearError,
        title = stringResource(R.string.ui_error_54a0e8c1),
        content = { Text(error, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) },
    ) { M3DialogAction(stringResource(R.string.ui_ok_565339bc), diffVM::clearError) }
    review?.takeIf { it.filePath == filePath }?.let { r ->
        RevertConfirmDialog(
            review = r,
            onConfirmDiscard = { stashFirst ->
                diffVM.confirmDiscard(r, stashFirst = stashFirst, onComplete = {
                    val rest = paths.filter { it != filePath }
                    onDiscarded(rest.getOrNull(index.coerceIn(0, (rest.size - 1).coerceAtLeast(0))))
                })
            },
            onDismiss = { diffVM.dismissDiscard(r) },
        )
    }
}

private enum class PaneView { CHANGES, WHOLE, PREVIEW }

/** Stateless diff detail. */
@Composable
fun DiffPaneContent(
    filePath: String,
    diff: WorkingFileDiff?,
    loading: Boolean,
    failed: Boolean,
    wide: Boolean,
    style: DiffPaneStyle,
    position: Pair<Int, Int>?,
    busy: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDiscard: () -> Unit,
    onOpenEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = M3.colors
    val markdown = isMarkdownPath(filePath)
    // The Tablet board offers Changes / Whole file / Preview; narrower panes Changes / Preview.
    val views = buildList {
        add(PaneView.CHANGES)
        if (wide || !markdown) add(PaneView.WHOLE)
        if (markdown) add(PaneView.PREVIEW)
    }
    var selected by rememberSaveable(filePath) { mutableIntStateOf(0) }
    val view = views.getOrElse(selected) { PaneView.CHANGES }
    val subtitle = buildList {
        add(folderOf(filePath))
        if (diff != null && !diff.binary) add("+${diff.added} −${diff.removed}")
        add(diff?.headShortHash?.let { stringResource(R.string.adaptive_vs, it) } ?: stringResource(R.string.adaptive_new_file))
    }.joinToString(" · ")
    val tabletop = style == DiffPaneStyle.Tabletop
    Column(modifier.fillMaxSize().detailPane(if (tabletop) 24.dp else PaneRadius).testTag("diff-pane")) {
        DetailHeader(nameOf(filePath), subtitle, minHeight = if (tabletop) 56.dp else 64.dp, titleSize = if (tabletop) 16 else 18) {
            if (tabletop) {
                if (position != null) Text(stringResource(R.string.adaptive_position, position.first + 1, position.second),
                    style = M3Type.meta, color = c.onVar, modifier = Modifier.testTag("diff-position"))
                M3IconButton(R.drawable.ic_m3_chevron_left, stringResource(R.string.diff_m3_previous), onPrevious, Modifier.testTag("diff-previous"),
                    tint = c.on, size = 44.dp, enabled = position != null && position.first > 0)
                M3IconButton(R.drawable.ic_m3_chevron_right, stringResource(R.string.diff_m3_next), onNext, Modifier.testTag("diff-next"),
                    tint = c.on, size = 44.dp, enabled = position != null && position.first < position.second - 1)
            } else {
                val labels = views.map { stringResource(when (it) {
                    PaneView.CHANGES -> R.string.diff_m3_changes; PaneView.WHOLE -> R.string.diff_m3_whole_file; PaneView.PREVIEW -> R.string.diff_m3_preview
                }) }
                CompactSegmented(labels, selected.coerceIn(0, views.lastIndex), { selected = it })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { M3Spinner(size = 24.dp, color = c.onVar) }
                failed || diff == null -> PaneNote(stringResource(R.string.diff_m3_load_failed), M3NoteTone.Warn)
                diff.binary -> PaneNote(stringResource(R.string.diff_m3_binary), M3NoteTone.Muted)
                view == PaneView.PREVIEW -> {
                    if (diff.newText == null) PaneNote(stringResource(R.string.diff_m3_deleted), M3NoteTone.Muted)
                    else MarkdownPreview(diff.newText.orEmpty(), Modifier.testTag("diff-preview"), changedLines = diff.changedLines)
                }
                else -> {
                    val rows = if (view == PaneView.CHANGES) diff.changes else diff.whole
                    if (rows.isEmpty()) PaneNote(stringResource(if (diff.deleted) R.string.diff_m3_deleted else R.string.diff_m3_no_changes), M3NoteTone.Muted)
                    else DiffLines(rows, contentPadding = PaddingValues(vertical = if (tabletop) 8.dp else 12.dp))
                }
            }
        }
        if (!tabletop) DetailFooter {
            M3TextButton(stringResource(R.string.diff_m3_discard), onDiscard, Modifier.testTag("diff-discard"),
                enabled = !busy && !loading && diff != null, destructive = true)
            if (diff?.binary != true && diff?.deleted != true) {
                M3Button(stringResource(R.string.m3_open_in_editor), onOpenEditor, Modifier.testTag("diff-open-editor"),
                    style = M3ButtonStyle.Tonal, size = M3ButtonSize.Medium)
            }
        }
    }
}

@Composable
private fun PaneNote(text: String, tone: M3NoteTone) {
    M3Note(text, Modifier.padding(16.dp).testTag("diff-state"), tone = tone)
}

/** Diff rows edge to edge: a 44dp number column, an 18dp sign, mono 13/22, tinted per kind. */
@Composable
internal fun DiffLines(rows: List<WorkingLine>, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(vertical = 12.dp), numberWidth: Dp = 44.dp) {
    val c = M3.colors
    val style = M3Type.code.copy(lineHeight = 22.sp, textDirection = TextDirection.Ltr)
    LazyColumn(modifier.fillMaxSize().testTag("diff-lines"), contentPadding = contentPadding) {
        items(rows) { row ->
            val (bg, ink) = when (row.kind) {
                WorkingLineKind.ADDED -> c.addBg to c.addInk
                WorkingLineKind.REMOVED -> c.delBg to c.delInk
                WorkingLineKind.HUNK -> c.cHighest to c.onVar
                WorkingLineKind.CONTEXT -> androidx.compose.ui.graphics.Color.Transparent to c.on
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
            Row(Modifier.fillMaxWidth().background(bg).padding(top = 1.dp, bottom = 1.dp, end = 16.dp)) {
                Text(number, style = style.copy(fontSize = 11.sp), color = c.onVar, textAlign = TextAlign.End, maxLines = 1,
                    modifier = Modifier.width(numberWidth).padding(end = 10.dp))
                Text(sign, style = style.copy(fontWeight = FontWeight.SemiBold), color = ink, modifier = Modifier.width(18.dp))
                Text(text, style = style, color = ink, modifier = Modifier.weight(1f))
            }
        }
    }
}

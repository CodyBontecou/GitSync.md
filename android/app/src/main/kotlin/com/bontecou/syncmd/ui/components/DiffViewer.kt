package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.DiffHunk
import com.bontecou.syncmd.data.models.DiffLine
import com.bontecou.syncmd.data.models.DiffLineType
import com.bontecou.syncmd.data.models.DiffStatus
import com.bontecou.syncmd.data.models.DiffSummary
import com.bontecou.syncmd.data.models.FileDiff
import com.bontecou.syncmd.data.models.UnifiedDiffResult
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TideType

// ─── Tide diff palette ──────────────────────────────────────────────────────
// Derived from the Tide tokens so both appearances follow the theme:
// green rows for additions, red for removals, a quiet band for hunk headers.

@Immutable
internal data class GitWorkDiffPalette(
    val hunkBg: Color,
    val lineNumber: Color,
    val addedBg: Color,
    val addedText: Color,
    val addedSign: Color,
    val addedWord: Color,
    val removedBg: Color,
    val removedText: Color,
    val removedSign: Color,
    val removedWord: Color,
)

@Composable
internal fun gitWorkDiffPalette(): GitWorkDiffPalette {
    val c = Tide.colors
    val rowAlpha = if (c.isDark) 0.14f else 0.10f
    val wordAlpha = if (c.isDark) 0.32f else 0.22f
    return GitWorkDiffPalette(
        hunkBg = c.ink.copy(alpha = if (c.isDark) 0.06f else 0.04f),
        lineNumber = c.inkFaint.copy(alpha = 0.75f),
        addedBg = c.synced.copy(alpha = rowAlpha),
        addedText = if (c.isDark) lerp(c.synced, c.ink, 0.45f) else lerp(c.synced, c.ink, 0.35f),
        addedSign = c.synced.copy(alpha = 0.8f),
        addedWord = c.synced.copy(alpha = wordAlpha),
        removedBg = c.danger.copy(alpha = rowAlpha * 0.9f),
        removedText = if (c.isDark) lerp(c.danger, c.ink, 0.45f) else lerp(c.danger, c.ink, 0.3f),
        removedSign = c.danger.copy(alpha = 0.75f),
        removedWord = c.danger.copy(alpha = wordAlpha),
    )
}

/** Kinds of rendered diff rows. */
internal enum class GitWorkDiffKind { HUNK, ADDED, REMOVED, CONTEXT }

/** One rendered diff row with line numbers and optional changed-word range. */
@Immutable
internal data class GitWorkDiffRow(
    val kind: GitWorkDiffKind,
    val content: String,
    val oldLineNo: Int?,
    val newLineNo: Int?,
    /** Changed character range [start, end) when this line pairs with an opposite line. */
    val changed: IntRange? = null,
)

/** Builds numbered rows for every hunk of [file], marking changed words on paired lines. */
internal fun gitWorkDiffRows(file: FileDiff): List<GitWorkDiffRow> {
    val out = mutableListOf<GitWorkDiffRow>()
    for (hunk in file.hunks) {
        out += GitWorkDiffRow(GitWorkDiffKind.HUNK, hunkRaw(hunk), hunk.oldStart, hunk.newStart)
        var old = hunk.oldStart
        var new = hunk.newStart
        for (dl in hunk.lines) when (dl.type) {
            DiffLineType.ADDITION -> { out += GitWorkDiffRow(GitWorkDiffKind.ADDED, dl.content, null, new); new++ }
            DiffLineType.DELETION -> { out += GitWorkDiffRow(GitWorkDiffKind.REMOVED, dl.content, old, null); old++ }
            DiffLineType.CONTEXT -> { out += GitWorkDiffRow(GitWorkDiffKind.CONTEXT, dl.content, old, new); old++; new++ }
        }
    }
    return attachChangedRanges(out)
}

private fun hunkRaw(hunk: DiffHunk) = "@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@"

/**
 * Pairs each run of removed lines with the added run that follows it and
 * marks the span between their common prefix and suffix (iOS TideTextDiff).
 */
private fun attachChangedRanges(input: List<GitWorkDiffRow>): List<GitWorkDiffRow> {
    val rows = input.toMutableList()
    var i = 0
    while (i < rows.size) {
        if (rows[i].kind != GitWorkDiffKind.REMOVED) { i++; continue }
        val removedStart = i
        while (i < rows.size && rows[i].kind == GitWorkDiffKind.REMOVED) i++
        val addedStart = i
        while (i < rows.size && rows[i].kind == GitWorkDiffKind.ADDED) i++
        for (k in 0 until minOf(addedStart - removedStart, i - addedStart)) {
            val old = rows[removedStart + k].content
            val new = rows[addedStart + k].content
            var prefix = 0
            while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
            var suffix = 0
            while (suffix < old.length - prefix && suffix < new.length - prefix &&
                old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
            // Only highlight when the lines are recognisably related.
            if ((prefix + suffix) * 3 < new.length || (prefix + suffix) == 0) continue
            val oldRange = prefix until old.length - suffix
            val newRange = prefix until new.length - suffix
            if (!oldRange.isEmpty()) rows[removedStart + k] = rows[removedStart + k].copy(changed = oldRange)
            if (!newRange.isEmpty()) rows[addedStart + k] = rows[addedStart + k].copy(changed = newRange)
        }
    }
    return rows
}

/** Sentence-case word for a diff status ("Added", "Modified"…). */
@Composable
internal fun gitWorkStatusWord(status: DiffStatus): String = stringResource(when (status) {
    DiffStatus.ADDED -> R.string.tide_merge_diff_added
    DiffStatus.DELETED -> R.string.tide_merge_diff_deleted
    DiffStatus.MODIFIED -> R.string.tide_merge_diff_modified
    DiffStatus.RENAMED -> R.string.tide_merge_diff_renamed
    DiffStatus.COPIED -> R.string.tide_merge_diff_copied
    DiffStatus.TYPE_CHANGE -> R.string.tide_merge_diff_type_changed
    DiffStatus.UNKNOWN -> R.string.tide_merge_diff_changed
})

/** Status pill: green for added, red for deleted, quiet for everything else. */
@Composable
internal fun GitWorkStatusPill(status: DiffStatus, modifier: Modifier = Modifier) {
    val c = Tide.colors
    val tint = when (status) {
        DiffStatus.ADDED -> c.synced
        DiffStatus.DELETED -> c.danger
        DiffStatus.RENAMED, DiffStatus.COPIED -> c.behind
        else -> c.inkMid
    }
    TidePill(gitWorkStatusWord(status), tint, if (tint == c.inkMid) c.well else tint.copy(alpha = 0.12f), modifier)
}

/** Hunk band: "@@ · line 12", faint mono; reads the raw range to screen readers. */
@Composable
internal fun GitWorkHunkRow(row: GitWorkDiffRow, modifier: Modifier = Modifier) {
    val palette = gitWorkDiffPalette()
    val caption = "@@ · " + stringResource(R.string.tide_merge_diff_hunk_line, row.newLineNo ?: 1)
    Text(
        caption,
        modifier = modifier.fillMaxWidth().background(palette.hunkBg).padding(horizontal = 14.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = row.content },
        style = TideType.mono(11.sp),
        color = Tide.colors.inkFaint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** One diff line: gutter (+, − or the new-side number) and mono body on its tint. */
@Composable
internal fun GitWorkDiffLineRow(row: GitWorkDiffRow, modifier: Modifier = Modifier, wrap: Boolean = true) {
    if (row.kind == GitWorkDiffKind.HUNK) { GitWorkHunkRow(row, modifier); return }
    val c = Tide.colors
    val p = gitWorkDiffPalette()
    val (bg, fg, gutterFg) = when (row.kind) {
        GitWorkDiffKind.ADDED -> Triple(p.addedBg, p.addedText, p.addedSign)
        GitWorkDiffKind.REMOVED -> Triple(p.removedBg, p.removedText, p.removedSign)
        else -> Triple(Color.Transparent, c.inkMid, p.lineNumber)
    }
    val gutter = when (row.kind) {
        GitWorkDiffKind.ADDED -> "+"
        GitWorkDiffKind.REMOVED -> "−"
        else -> row.newLineNo?.toString().orEmpty()
    }
    val label = when (row.kind) {
        GitWorkDiffKind.ADDED -> stringResource(R.string.tide_merge_diff_line_added, row.content)
        GitWorkDiffKind.REMOVED -> stringResource(R.string.tide_merge_diff_line_removed, row.content)
        else -> row.content
    }
    val body: AnnotatedString = row.changed?.let { range ->
        val word = if (row.kind == GitWorkDiffKind.ADDED) p.addedWord else p.removedWord
        buildAnnotatedString {
            append(row.content.substring(0, range.first))
            withStyle(SpanStyle(background = word)) { append(row.content.substring(range.first, range.last + 1)) }
            append(row.content.substring(range.last + 1))
        }
    } ?: AnnotatedString(row.content.ifEmpty { " " })
    Row(
        modifier.fillMaxWidth().background(bg).padding(horizontal = 14.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.Top,
    ) {
        Text(gutter, Modifier.width(34.dp).clearAndSetSemantics {}, style = TideType.mono(11.sp).copy(lineHeight = 18.sp),
            color = gutterFg, maxLines = 1)
        Text(
            body,
            Modifier.weight(1f),
            style = TideType.mono(12.5.sp).copy(lineHeight = 18.sp, textDirection = TextDirection.Ltr),
            color = fg,
            softWrap = wrap,
            maxLines = if (wrap) Int.MAX_VALUE else 1,
        )
    }
}

/**
 * Displays a unified diff: one Tide card per file with a mono path, a status
 * pill, its hunks, and a mono summary line underneath.
 */
@Composable
fun DiffViewer(
    diff: UnifiedDiffResult?,
    modifier: Modifier = Modifier
) {
    if (diff == null || diff.files.isEmpty()) {
        TideCard(modifier) {
            Text(
                text = stringResource(R.string.ui_no_changes_to_display_11be6ffc),
                style = TideType.sans(15.sp, FontWeight.SemiBold),
                color = Tide.colors.ink,
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(diff.files) { fileDiff -> DiffFileCard(fileDiff) }
        item {
            Text(
                text = stringResource(R.string.tide_merge_diff_summary, diff.summary.filesChanged,
                    diff.summary.insertions, diff.summary.deletions),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                style = TideType.meta,
                color = Tide.colors.inkFaint,
            )
        }
    }
}

@Composable
private fun DiffFileCard(fileDiff: FileDiff) {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth().clip(Tide.cardShape).background(c.card)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(fileDiff.filePath, Modifier.weight(1f), style = TideType.mono(12.5.sp, FontWeight.SemiBold)
                .copy(textDirection = TextDirection.Ltr), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            GitWorkStatusPill(fileDiff.status)
        }
        if (fileDiff.isBinary) {
            Text(stringResource(R.string.tide_merge_diff_binary_body), Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                style = TideType.meta, color = c.inkFaint)
        } else {
            fileDiff.hunks.forEach { hunk -> DiffHunkViewer(hunk) }
            Spacer(Modifier.padding(bottom = 6.dp))
        }
    }
}

/** Displays a single diff hunk (a contiguous block of changes). */
@Composable
fun DiffHunkViewer(
    hunk: DiffHunk,
    modifier: Modifier = Modifier
) {
    val rows = gitWorkDiffRows(FileDiff(filePath = "", status = DiffStatus.MODIFIED, hunks = listOf(hunk)))
    Column(modifier = modifier.fillMaxWidth()) {
        rows.forEach { GitWorkDiffLineRow(it) }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

internal val gitWorkPreviewDiff = UnifiedDiffResult(
    files = listOf(
        FileDiff(
            filePath = "ideas/launch-plan.md", status = DiffStatus.MODIFIED, isStaged = false,
            hunks = listOf(DiffHunk(10, 5, 10, 6, listOf(
                DiffLine(DiffLineType.CONTEXT, "## Steps"),
                DiffLine(DiffLineType.DELETION, "- Ship the beta on Friday"),
                DiffLine(DiffLineType.ADDITION, "- Ship the beta on Monday"),
                DiffLine(DiffLineType.ADDITION, "- Tell the newsletter"),
                DiffLine(DiffLineType.CONTEXT, ""),
                DiffLine(DiffLineType.CONTEXT, "Notes follow."),
            ))),
        ),
        FileDiff(filePath = "assets/cover.png", status = DiffStatus.ADDED, hunks = emptyList(), isBinary = true),
    ),
    summary = DiffSummary(filesChanged = 2, insertions = 2, deletions = 1),
)

@Composable
private fun DiffViewerPreviewBody(diff: UnifiedDiffResult?) {
    Box(Modifier.background(Tide.colors.paper).padding(16.dp)) { DiffViewer(diff) }
}

@Preview(name = "Diff viewer · light") @Composable
private fun DiffViewerLight() = SyncMdTheme(darkTheme = false) { DiffViewerPreviewBody(gitWorkPreviewDiff) }

@Preview(name = "Diff viewer · dark") @Composable
private fun DiffViewerDark() = SyncMdTheme(darkTheme = true) { DiffViewerPreviewBody(gitWorkPreviewDiff) }

@Preview(name = "Diff viewer · empty") @Composable
private fun DiffViewerEmpty() = SyncMdTheme(darkTheme = false) { DiffViewerPreviewBody(null) }

package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.data.models.DiffHunk
import com.bontecou.syncmd.data.models.DiffLineType
import com.bontecou.syncmd.data.models.FileDiff
import com.bontecou.syncmd.data.models.UnifiedDiffResult
import com.bontecou.syncmd.ui.components.RevertConfirmDialog
import com.bontecou.syncmd.ui.theme.BBadge
import com.bontecou.syncmd.ui.theme.BBadgeStyle
import com.bontecou.syncmd.ui.theme.BCard
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.BLoading
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel

// ─── Numbered diff line (adds line-number tracking to the parsed model) ───────

private data class NumberedDiffLine(
    val type: LineKind,
    val content: String,
    val oldLineNo: Int?,
    val newLineNo: Int?,
)

private enum class LineKind { ADDED, REMOVED, CONTEXT, HUNK_HEADER }

/** Convert [UnifiedDiffResult] for a single file into numbered display lines. */
private fun buildNumberedLines(result: UnifiedDiffResult): List<NumberedDiffLine> {
    val out = mutableListOf<NumberedDiffLine>()
    val file = result.files.firstOrNull() ?: return out
    for (hunk in file.hunks) {
        out += NumberedDiffLine(
            type      = LineKind.HUNK_HEADER,
            content   = "@@ -${hunk.oldStart},${hunk.oldCount} +${hunk.newStart},${hunk.newCount} @@",
            oldLineNo = null,
            newLineNo = null,
        )
        var old = hunk.oldStart
        var new = hunk.newStart
        for (dl in hunk.lines) {
            when (dl.type) {
                DiffLineType.ADDITION -> { out += NumberedDiffLine(LineKind.ADDED,   dl.content, null, new); new++ }
                DiffLineType.DELETION -> { out += NumberedDiffLine(LineKind.REMOVED, dl.content, old, null); old++ }
                DiffLineType.CONTEXT  -> { out += NumberedDiffLine(LineKind.CONTEXT, dl.content, old, new);  old++; new++ }
            }
        }
    }
    return out
}

// ─── Screen ──────────────────────────────────────────────────────────────────

/**
 * Full-screen diff viewer for a single file — mirrors iOS FileDiffView.
 *
 * Shows:
 *  - Top bar with filename and a revert (↩) button
 *  - Header card with +additions / −deletions counts and status badge
 *  - Numbered diff lines (hunk headers, additions, deletions, context)
 *  - [RevertConfirmDialog] when the revert button is tapped
 */
@Composable
fun DiffScreen(
    repositoryPath: String,
    filePath: String,
    onNavigateBack: () -> Unit,
    diffVM: DiffViewModel = hiltViewModel(),
) {
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) diffVM.setRepositoryPath(repositoryPath)
    }
    LaunchedEffect(filePath) {
        if (filePath.isNotBlank()) diffVM.selectFile(filePath)
    }

    val isLoading        by diffVM.isLoading.collectAsState()
    val selectedFileDiff by diffVM.selectedFileDiff.collectAsState()
    val bc               = LocalBrutalColors.current

    var showRevertConfirm by remember { mutableStateOf(false) }
    var isReverting       by remember { mutableStateOf(false) }

    val filename  = filePath.substringAfterLast('/').ifEmpty { filePath }
    val directory = filePath.substringBeforeLast('/', "")

    val numberedLines = remember(selectedFileDiff) {
        selectedFileDiff?.let { buildNumberedLines(it) } ?: emptyList()
    }
    val addedCount   = numberedLines.count { it.type == LineKind.ADDED }
    val removedCount = numberedLines.count { it.type == LineKind.REMOVED }
    val fileDiff     = selectedFileDiff?.files?.firstOrNull()

    Box(modifier = Modifier.fillMaxSize().background(bc.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bc.bg)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // ← Back
                    Box(
                        modifier = Modifier
                            .border(1.dp, bc.border)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onNavigateBack,
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "←  BACK",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                letterSpacing = 1.sp,
                                color = bc.text,
                            )
                        )
                    }

                    // Filename title (center)
                    Text(
                        text = filename.uppercase(),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            letterSpacing = 2.sp,
                            color = bc.text,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Revert button  ↩
                    Box(
                        modifier = Modifier
                            .border(1.dp, bc.error.copy(alpha = if (isReverting || isLoading) 0.3f else 0.6f))
                            .clickable(
                                enabled = !isReverting && !isLoading,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { showRevertConfirm = true },
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        if (isReverting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = bc.error,
                            )
                        } else {
                            Text(
                                text = "↩  REVERT",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    letterSpacing = 1.sp,
                                    color = if (isLoading) bc.error.copy(alpha = 0.3f) else bc.error,
                                )
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(bc.border))
            }

            // ── Body ──────────────────────────────────────────────────────
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BLoading(text = "Loading diff…")
                    }
                }

                numberedLines.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "📄",
                                style = TextStyle(fontSize = 40.sp)
                            )
                            Text(
                                text = "No diff available",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = bc.text,
                                )
                            )
                            Text(
                                text = "Binary file or no content changes",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    color = bc.textFaint,
                                )
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // ── Header card ───────────────────────────────────
                        item {
                            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                                BCard {
                                    Column {
                                        // Filename + badge
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 16.dp),
                                            verticalAlignment = Alignment.Top,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                Text(
                                                    text = filename,
                                                    style = TextStyle(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Black,
                                                        fontSize = 22.sp,
                                                        color = bc.text,
                                                    )
                                                )
                                                if (directory.isNotBlank()) {
                                                    Text(
                                                        text = directory,
                                                        style = TextStyle(
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 12.sp,
                                                            color = bc.textFaint,
                                                        ),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                            fileDiff?.let { fd ->
                                                BBadge(
                                                    text = fd.status.name,
                                                    style = when (fd.status.name) {
                                                        "ADDED"   -> BBadgeStyle.SUCCESS
                                                        "DELETED" -> BBadgeStyle.ERROR
                                                        else      -> BBadgeStyle.DEFAULT
                                                    }
                                                )
                                            }
                                        }

                                        BDivider()

                                        // Stats row: +additions / −deletions
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                        ) {
                                            StatPill(count = addedCount,   label = "ADDED",   color = Color(0xFF1A7A1A))
                                            Spacer(Modifier.weight(1f))
                                            Box(Modifier.width(1.dp).height(32.dp).background(bc.borderSoft))
                                            Spacer(Modifier.weight(1f))
                                            StatPill(count = removedCount, label = "REMOVED", color = Color(0xFFD70015))
                                        }
                                    }
                                }
                            }
                        }

                        // ── Diff lines ────────────────────────────────────
                        items(numberedLines) { line ->
                            DiffLineRow(line = line)
                        }

                        item { Spacer(Modifier.height(40.dp)) }
                    }
                }
            }
        }

        // ── Revert confirm dialog ──────────────────────────────────────────
        if (showRevertConfirm) {
            RevertConfirmDialog(
                title        = "Revert Changes",
                filename     = filename,
                files        = emptyList(),
                confirmLabel = "Revert",
                onConfirm    = {
                    showRevertConfirm = false
                    isReverting = true
                    diffVM.discardFileChanges(filePath) {
                        isReverting = false
                        onNavigateBack()
                    }
                },
                onDismiss    = { showRevertConfirm = false },
            )
        }
    }
}

// ─── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun StatPill(count: Int, label: String, color: Color) {
    val bc = LocalBrutalColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text  = "$count",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize   = 22.sp,
                color      = if (count > 0) color else bc.textFaint,
            )
        )
        Text(
            text  = label,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize   = 10.sp,
                letterSpacing = 1.sp,
                color      = bc.textFaint,
            )
        )
    }
}

@Composable
private fun DiffLineRow(line: NumberedDiffLine) {
    val bc = LocalBrutalColors.current

    // Colours per line kind
    val rowBg    = when (line.type) {
        LineKind.ADDED      -> Color(0xFF1A7A1A).copy(alpha = 0.10f)
        LineKind.REMOVED    -> Color(0xFFD70015).copy(alpha = 0.08f)
        LineKind.HUNK_HEADER -> Color(0xFF007AFF).copy(alpha = 0.06f)
        LineKind.CONTEXT    -> Color.Transparent
    }
    val prefixFg = when (line.type) {
        LineKind.ADDED      -> Color(0xFF1A7A1A)
        LineKind.REMOVED    -> Color(0xFFD70015)
        LineKind.HUNK_HEADER -> Color(0xFF007AFF)
        LineKind.CONTEXT    -> bc.textFaint
    }
    val textFg = when (line.type) {
        LineKind.HUNK_HEADER -> Color(0xFF007AFF)
        else                 -> bc.text
    }
    val gutterFg = when (line.type) {
        LineKind.ADDED      -> Color(0xFF1A7A1A).copy(alpha = 0.55f)
        LineKind.REMOVED    -> Color(0xFFD70015).copy(alpha = 0.55f)
        else                -> bc.textFaint
    }
    val prefix = when (line.type) {
        LineKind.ADDED      -> "+"
        LineKind.REMOVED    -> "−"
        LineKind.HUNK_HEADER -> "↕"
        LineKind.CONTEXT    -> " "
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg),
        verticalAlignment = Alignment.Top,
    ) {
        // Old line number
        Text(
            text     = line.oldLineNo?.toString() ?: "",
            modifier = Modifier
                .width(40.dp)
                .padding(vertical = 3.dp),
            style    = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize   = 11.sp,
                color      = gutterFg,
            ),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )

        // New line number
        Text(
            text     = line.newLineNo?.toString() ?: "",
            modifier = Modifier
                .width(40.dp)
                .padding(vertical = 3.dp),
            style    = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize   = 11.sp,
                color      = gutterFg,
            ),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )

        // Gutter rule
        Box(Modifier.width(1.dp).height(20.dp).background(bc.borderSoft).padding(vertical = 1.dp))

        // +/−/↕ prefix
        Text(
            text     = prefix,
            modifier = Modifier
                .width(22.dp)
                .padding(vertical = 3.dp),
            style    = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize   = 13.sp,
                color      = prefixFg,
            ),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        // Content (horizontally scrollable so long lines don't wrap)
        val scrollState = rememberScrollState()
        Text(
            text     = line.content,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState)
                .padding(top = 3.dp, bottom = 3.dp, end = 24.dp),
            style    = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize   = 13.sp,
                color      = textFg,
            ),
            maxLines = 1,
        )
    }
}

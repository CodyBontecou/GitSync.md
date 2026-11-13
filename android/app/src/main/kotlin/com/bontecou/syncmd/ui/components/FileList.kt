package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.DiffStatus
import com.bontecou.syncmd.data.models.FileDiff
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideType
import java.text.NumberFormat

/**
 * Displays a list of files with status badges and click handlers.
 */
@Composable
fun FileList(
    files: List<FileDiff>,
    selectedFilePath: String? = null,
    onFileClick: (String) -> Unit,
    onStageClick: (String) -> Unit,
    onUnstageClick: (String) -> Unit,
    isStaged: Boolean = false,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(files) { file ->
            FileListItem(
                fileDiff = file,
                isSelected = file.filePath == selectedFilePath,
                isStaged = isStaged,
                onFileClick = { onFileClick(file.filePath) },
                onStageClick = { onStageClick(file.filePath) },
                onUnstageClick = { onUnstageClick(file.filePath) }
            )
        }
    }
}

/**
 * Single changed-file row: tinted status glyph, mono path, +/− line counts and
 * a stage / unstage pill.
 */
@Composable
fun FileListItem(
    fileDiff: FileDiff,
    isSelected: Boolean = false,
    isStaged: Boolean = false,
    onFileClick: () -> Unit,
    onStageClick: () -> Unit,
    onUnstageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = Tide.colors
    val shape = RoundedCornerShape(18.dp)
    val tint = statusColor(fileDiff.status)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isSelected) c.well else c.card, shape)
            .then(if (isSelected) Modifier.border(1.dp, c.lineSoft, shape) else Modifier)
            .clickable(role = Role.Button) { onFileClick() }
            .heightIn(min = 56.dp)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(26.dp).background(tint.copy(alpha = 0.12f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text(statusIcon(fileDiff.status), style = TideType.mono(12.sp, FontWeight.SemiBold), color = tint)
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = fileDiff.filePath,
                style = TideType.mono(13.sp, FontWeight.Medium),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (fileDiff.hunks.isNotEmpty()) {
                val additions = fileDiff.hunks.sumOf { hunk -> hunk.lines.count { it.type.name == "ADDITION" } }
                val deletions = fileDiff.hunks.sumOf { hunk -> hunk.lines.count { it.type.name == "DELETION" } }
                Text(
                    text = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).let { numbers ->
                        pluralStringResource(R.plurals.diff_changed_lines, additions + deletions, numbers.format(additions), numbers.format(deletions))
                    },
                    style = TideType.mono(11.sp),
                    color = c.inkFaint,
                )
            }
        }

        val label = stringResource(if (isStaged) R.string.ui_unstage_033f86f9 else R.string.ui_stage_de838855)
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) { if (isStaged) onUnstageClick() else onStageClick() }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .then(if (isStaged) Modifier.background(c.ink, CircleShape) else Modifier.border(1.dp, c.lineSoft, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (isStaged) "−" else "+", style = TideType.sans(16.sp, FontWeight.SemiBold), color = if (isStaged) c.paper else c.ink)
            }
        }
    }
}

/**
 * Get the status badge color: green added, red deleted, orange edited (yours),
 * blue renamed/copied, quiet ink for anything else.
 */
@Composable
fun statusColor(status: DiffStatus): Color {
    val c = Tide.colors
    return when (status) {
        DiffStatus.ADDED -> c.synced
        DiffStatus.DELETED -> c.danger
        DiffStatus.MODIFIED -> c.ahead
        DiffStatus.RENAMED -> c.behind
        DiffStatus.COPIED -> c.behind
        DiffStatus.TYPE_CHANGE -> c.ahead
        DiffStatus.UNKNOWN -> c.inkFaint
    }
}

/**
 * Get the status badge icon text.
 */
fun statusIcon(status: DiffStatus): String {
    return when (status) {
        DiffStatus.ADDED -> "A"
        DiffStatus.DELETED -> "D"
        DiffStatus.MODIFIED -> "M"
        DiffStatus.RENAMED -> "R"
        DiffStatus.COPIED -> "C"
        DiffStatus.TYPE_CHANGE -> "T"
        DiffStatus.UNKNOWN -> "?"
    }
}

@Composable
private fun PreviewFileListFrame(dark: Boolean) = SyncMdTheme(darkTheme = dark) {
    Column(Modifier.background(Tide.colors.paper).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FileListItem(FileDiff(filePath = "Daily/2026-11-02.md", status = DiffStatus.MODIFIED, hunks = emptyList()), onFileClick = {}, onStageClick = {}, onUnstageClick = {})
        FileListItem(FileDiff(filePath = "Ideas/tide.md", status = DiffStatus.ADDED, hunks = emptyList()), isSelected = true, onFileClick = {}, onStageClick = {}, onUnstageClick = {})
        FileListItem(FileDiff(filePath = "Archive/old.md", status = DiffStatus.DELETED, hunks = emptyList()), isStaged = true, onFileClick = {}, onStageClick = {}, onUnstageClick = {})
    }
}

@Preview(name = "FileList · light") @Composable private fun PreviewFileListLight() = PreviewFileListFrame(false)
@Preview(name = "FileList · dark") @Composable private fun PreviewFileListDark() = PreviewFileListFrame(true)

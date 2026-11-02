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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Conflict
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideChevron
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideType

/**
 * Conflicting files as one Tide card of rows: a red ring for files still to
 * resolve, a green check once staged, the mono path and each side's size.
 */
@Composable
fun ConflictList(
    conflicts: List<Conflict>,
    resolvedConflicts: Set<String> = emptySet(),
    onSelectConflict: (Conflict) -> Unit,
    modifier: Modifier = Modifier
) {
    if (conflicts.isEmpty()) {
        TideCard(modifier) {
            Text(
                text = stringResource(R.string.ui_no_conflicts_found_d356b17b),
                style = TideType.sans(15.sp, FontWeight.SemiBold),
                color = Tide.colors.ink,
            )
        }
        return
    }

    val resolved = conflicts.count { it.filePath in resolvedConflicts }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item(key = "count") {
            TideSectionLabel(
                stringResource(R.string.tide_merge_conflict_list_count, resolved, conflicts.size),
                Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        itemsIndexed(conflicts, key = { _, it -> it.filePath }) { index, conflict ->
            val r = Tide.radiusCard
            val shape = when {
                conflicts.size == 1 -> RoundedCornerShape(r)
                index == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
                index == conflicts.lastIndex -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
                else -> RoundedCornerShape(0.dp)
            }
            Column(Modifier.fillMaxWidth().clip(shape).background(Tide.colors.card)) {
                if (index > 0) TideHairline(Modifier.padding(horizontal = 16.dp))
                ConflictItem(
                    conflict = conflict,
                    isResolved = conflict.filePath in resolvedConflicts,
                    onSelectClick = { onSelectConflict(conflict) }
                )
            }
        }
    }
}

/** Single conflict row. Resolved rows are not tappable. */
@Composable
fun ConflictItem(
    conflict: Conflict,
    isResolved: Boolean = false,
    onSelectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = Tide.colors
    val statusLabel = stringResource(if (isResolved) R.string.ui_resolved_5be3c2c8 else R.string.tide_merge_conflict_unresolved)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = !isResolved, role = Role.Button) { if (!isResolved) onSelectClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status mark: green check when resolved, red ring while unresolved.
        Box(
            Modifier.size(22.dp).semantics { contentDescription = statusLabel }.then(
                if (isResolved) Modifier.background(c.synced, CircleShape) else Modifier.border(2.dp, c.danger, CircleShape)
            ),
            contentAlignment = Alignment.Center,
        ) { if (isResolved) Text("✓", style = TideType.mono(11.sp, FontWeight.SemiBold), color = c.card) }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = conflict.filePath,
                style = TideType.mono(13.sp, FontWeight.SemiBold).copy(textDirection = TextDirection.Ltr),
                color = if (isResolved) c.inkMid else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    GitWorkSideDot(yours = true)
                    Text(stringResource(R.string.ui_ours_1_s_bytes_e7da423a, conflict.currentContent.length.toString()),
                        style = TideType.mono(11.sp), color = c.inkFaint, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    GitWorkSideDot(yours = false)
                    Text(stringResource(R.string.ui_theirs_1_s_bytes_70028ef3, conflict.incomingContent.length.toString()),
                        style = TideType.mono(11.sp), color = c.inkFaint, maxLines = 1)
                }
            }
        }
        Box(Modifier.clearAndSetSemantics {}) {
            if (isResolved) TidePill(statusLabel, c.synced, c.synced.copy(alpha = 0.12f))
            else TidePill(statusLabel, c.danger, c.danger.copy(alpha = 0.10f))
        }
        if (!isResolved) TideChevron()
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewConflicts = listOf(
    gitWorkPreviewConflict,
    gitWorkPreviewConflict.copy(filePath = "daily/2026-11-02.md"),
    gitWorkPreviewRename,
)

@Composable
private fun ConflictListPreviewBody(conflicts: List<Conflict>, resolved: Set<String>) {
    Box(Modifier.background(Tide.colors.paper).padding(16.dp)) { ConflictList(conflicts, resolved, {}) }
}

@Preview(name = "Conflict list · light") @Composable
private fun ConflictListLight() = SyncMdTheme(darkTheme = false) { ConflictListPreviewBody(previewConflicts, setOf("daily/2026-11-02.md")) }

@Preview(name = "Conflict list · dark") @Composable
private fun ConflictListDark() = SyncMdTheme(darkTheme = true) { ConflictListPreviewBody(previewConflicts, setOf("daily/2026-11-02.md")) }

@Preview(name = "Conflict list · empty") @Composable
private fun ConflictListEmpty() = SyncMdTheme(darkTheme = false) { ConflictListPreviewBody(emptyList(), emptySet()) }

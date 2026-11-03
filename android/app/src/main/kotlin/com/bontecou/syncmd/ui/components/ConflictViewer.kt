package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
import com.bontecou.syncmd.ui.theme.TideDisplay
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideFieldColors

/**
 * Conflict editor body (iOS Tide ConflictEditorView): a mono kicker naming the
 * path, a plain headline, "Yours" (orange) and "Theirs" (blue) version cards,
 * the common ancestor when Git has one, then the hand-edited result.
 */
@Composable
fun ConflictViewer(
    conflict: Conflict,
    manualResolution: String = "",
    onManualResolutionChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    resolutionPath: String? = null,
    onResolutionPathChange: ((String) -> Unit)? = null,
) {
    val c = Tide.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        GitWorkConflictHeader(conflict, stringResource(R.string.ui_resolving_1_s_5e8ada92, conflict.filePath))

        ConflictFilenameChoices(conflict, resolutionPath, enabled, onResolutionPathChange)

        GitWorkVersionCard(
            yours = true,
            label = stringResource(R.string.tide_merge_yours),
            content = if (conflict.hasCurrentVersion) conflict.currentContent else null,
            deletedNote = stringResource(R.string.conflict_current_deleted),
            blobByteCount = conflict.currentByteCount,
            textAvailable = !conflict.isBinaryOrTooLarge,
        )
        GitWorkVersionCard(
            yours = false,
            label = stringResource(R.string.tide_merge_theirs),
            content = if (conflict.hasIncomingVersion) conflict.incomingContent else null,
            deletedNote = stringResource(R.string.conflict_incoming_deleted),
            blobByteCount = conflict.incomingByteCount,
            textAvailable = !conflict.isBinaryOrTooLarge,
        )
        val baseContent = conflict.baseContent
        if (baseContent != null && baseContent.isNotBlank()) {
            GitWorkVersionCard(yours = null, label = stringResource(R.string.ui_base_7b47361a), content = baseContent, deletedNote = "",
                blobByteCount = conflict.ancestor?.byteCount, textAvailable = !conflict.isBinaryOrTooLarge)
        }

        // ── Result: what gets staged ─────────────────────────────────────
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TideSectionLabel(stringResource(R.string.tide_merge_conflict_result))
                Text(stringResource(R.string.tide_merge_conflict_result_hint), style = TideType.meta, color = c.inkFaint)
            }
            OutlinedTextField(
                value = manualResolution,
                onValueChange = onManualResolutionChange,
                enabled = enabled && !conflict.isBinaryOrTooLarge,
                label = { Text(stringResource(R.string.ui_resolved_content_69213720)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                placeholder = { Text(stringResource(R.string.ui_edit_the_content_here_to_manually_resolve_1985871d)) },
                maxLines = Int.MAX_VALUE,
                shape = Tide.fieldShape,
                colors = tideFieldColors(),
                textStyle = TideType.mono(12.5.sp).copy(color = c.ink, textDirection = TextDirection.Ltr),
            )
            Text(
                text = stringResource(R.string.ui_1_s_characters_bde60398,
                    java.text.NumberFormat.getIntegerInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
                        .format(remember(manualResolution) { com.bontecou.syncmd.ui.util.userCharacterCount(manualResolution) })),
                style = TideType.meta,
                color = c.inkFaint,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/** Mono kicker in "yours" orange plus the plain-language headline for this conflict. */
@Composable
internal fun GitWorkConflictHeader(conflict: Conflict, kicker: String, modifier: Modifier = Modifier) {
    val c = Tide.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(kicker, style = TideType.mono(11.5.sp), color = c.ahead, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TideDisplay(
            stringResource(when {
                conflict.isRenameRename -> R.string.tide_merge_conflict_renamed_headline
                !conflict.hasCurrentVersion || !conflict.hasIncomingVersion -> R.string.tide_merge_conflict_deleted_headline
                else -> R.string.tide_merge_conflict_headline
            }),
            size = 24.sp,
        )
        Text(stringResource(R.string.tide_merge_conflict_pick), style = TideType.sans(14.sp), color = c.inkMid)
    }
}

/**
 * One side of a conflict on a white card: side dot and label, a mono size
 * meta, then the text on a faint tint of the side's colour (scrolls past
 * 200dp). [yours] null renders the neutral common-ancestor card. A null
 * [content] means this side deleted the file.
 */
@Composable
internal fun GitWorkVersionCard(
    yours: Boolean?,
    label: String,
    content: String?,
    deletedNote: String,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    blobByteCount: Long? = null,
    textAvailable: Boolean = true,
) {
    val c = Tide.colors
    val textBytes = remember(content, textAvailable, blobByteCount) {
        if (textAvailable && blobByteCount == null) content?.toByteArray(Charsets.UTF_8)?.size?.toLong() else null
    }
    val bytes = blobByteCount ?: textBytes
    val tint = when (yours) { true -> c.ahead; false -> c.behind; null -> c.inkMid }
    Column(
        modifier.fillMaxWidth().background(c.card, Tide.cardShape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (yours != null) GitWorkSideDot(yours) else Box(Modifier.size(9.dp).background(c.lineSoft, RoundedCornerShape(2.dp)))
            Text(label, style = TideType.sans(14.sp, FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1f))
            val summary = when {
                content == null -> stringResource(R.string.tide_merge_side_deleted)
                !textAvailable -> bytes?.let { stringResource(R.string.ui_1_s_bytes_81cdb385, it.toString()) }
                else -> stringResource(R.string.ui_1_s_lines_2_s_bytes_c30a0cd1, content.lines().size.toString(), requireNotNull(bytes).toString())
            }
            if (summary != null) Text(
                summary,
                style = TideType.mono(11.sp), color = c.inkFaint, maxLines = 1,
            )
        }
        Box(
            Modifier.fillMaxWidth().heightIn(max = 200.dp)
                .background(if (yours == null) c.well.copy(alpha = 0.6f) else tint.copy(alpha = 0.07f), RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                if (content == null) deletedNote else if (!textAvailable)
                    stringResource(R.string.ui_binary_large_or_lfs_file_choose_an_entire_52c3278a) else content,
                style = TideType.mono(12.5.sp).copy(lineHeight = 19.sp, textDirection = TextDirection.Ltr),
                color = if (content == null || content.isEmpty()) c.inkFaint else c.ink,
            )
        }
        footer?.invoke()
    }
}

/** Displays a single conflict panel (ours/theirs/base) as a neutral Tide version card. */
@Composable
fun ConflictPanel(
    title: String,
    content: String,
    @Suppress("UNUSED_PARAMETER") backgroundColor: Color
) {
    GitWorkVersionCard(yours = null, label = title, content = content, deletedNote = "")
}

// ─── Previews ───────────────────────────────────────────────────────────────

internal val gitWorkPreviewConflict = Conflict(
    filePath = "ideas/launch-plan.md",
    currentContent = "## Steps\n- Ship the beta on Monday\n- Tell the newsletter\n",
    incomingContent = "## Steps\n- Ship the beta on Friday\n- Post in the forum\n",
    baseContent = "## Steps\n- Ship the beta\n",
)

@Composable
private fun ConflictViewerPreviewBody(conflict: Conflict, enabled: Boolean = true) {
    var draft by remember { mutableStateOf(conflict.currentContent) }
    Column(Modifier.background(Tide.colors.paper).verticalScroll(rememberScrollState()).padding(16.dp)) {
        ConflictViewer(conflict, draft, { draft = it }, enabled = enabled)
        Spacer(Modifier.size(16.dp))
    }
}

@Preview(name = "Conflict · both edited · light", heightDp = 1100) @Composable
private fun ConflictBothLight() = SyncMdTheme(darkTheme = false) { ConflictViewerPreviewBody(gitWorkPreviewConflict) }

@Preview(name = "Conflict · both edited · dark", heightDp = 1100) @Composable
private fun ConflictBothDark() = SyncMdTheme(darkTheme = true) { ConflictViewerPreviewBody(gitWorkPreviewConflict) }

@Preview(name = "Conflict · deleted on one side", heightDp = 900) @Composable
private fun ConflictDeleted() = SyncMdTheme(darkTheme = false) {
    ConflictViewerPreviewBody(gitWorkPreviewConflict.copy(hasIncomingVersion = false, incomingContent = "", baseContent = null))
}

@Preview(name = "Conflict · renamed · disabled", heightDp = 1200) @Composable
private fun ConflictRenamed() = SyncMdTheme(darkTheme = true) {
    ConflictViewerPreviewBody(gitWorkPreviewRename, enabled = false)
}

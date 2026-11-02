package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.bontecou.syncmd.data.models.Stash
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TideType

/**
 * Tide stash list: a white card of rows — mono `stash@{n}`, the message,
 * when it was saved, and Apply / Pop / ✕ pills (iOS VaultStashesScreen).
 */
@Composable
fun StashList(
    stashes: List<Stash>,
    onStashApply: (String) -> Unit,
    onStashPop: (String) -> Unit,
    onStashDrop: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (stashes.isEmpty()) {
        TideCard(modifier = modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.ui_no_stashes_found_ace2fa6f),
                style = TideType.sans(15.sp, FontWeight.SemiBold),
                color = Tide.colors.ink,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .background(Tide.colors.card, Tide.cardShape)
            .padding(horizontal = 16.dp),
    ) {
        itemsIndexed(stashes) { index, stash ->
            if (index > 0) TideHairline()
            StashItem(
                stash = stash,
                onApplyClick = { onStashApply(stash.id) },
                onPopClick = { onStashPop(stash.id) },
                onDropClick = { onStashDrop(stash.id) }
            )
        }
    }
}

/**
 * Single stash row.
 */
@Composable
fun StashItem(
    stash: Stash,
    onApplyClick: () -> Unit,
    onPopClick: () -> Unit,
    onDropClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = Tide.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stash.id,
            style = TideType.mono(12.sp, FontWeight.SemiBold),
            color = c.inkMid,
        )
        Text(
            text = stash.name,
            style = TideType.sans(15.sp, FontWeight.Medium),
            color = c.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val msg = stash.message
        if (msg != null && msg.isNotBlank()) {
            Text(
                text = msg,
                style = TideType.sans(14.sp),
                color = c.inkMid,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = formatTimestamp(stash.timestamp),
            style = TideType.meta,
            color = c.inkFaint,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StashRowPill(
                label = stringResource(R.string.ui_apply_2cbf6555),
                description = stringResource(R.string.ui_apply_stash_008c67fe),
                onClick = onApplyClick,
            )
            StashRowPill(
                label = stringResource(R.string.ui_pop_52e740ad),
                description = stringResource(R.string.ui_pop_stash_2adc4302),
                onClick = onPopClick,
            )
            Spacer(Modifier.weight(1f))
            StashRowPill(
                label = "✕",
                description = stringResource(R.string.ui_drop_stash_969bf549),
                danger = true,
                onClick = onDropClick,
            )
        }
    }
}

/** Outline capsule with a 48dp hit target. */
@Composable
private fun StashRowPill(label: String, description: String, onClick: () -> Unit, danger: Boolean = false) {
    val c = Tide.colors
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = 36.dp)
                .border(1.dp, if (danger) c.danger.copy(alpha = 0.35f) else c.lineSoft, CircleShape)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = TideType.sans(14.sp, FontWeight.SemiBold), color = if (danger) c.danger else c.ink, maxLines = 1)
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

private val previewStashes = listOf(
    Stash("stash@{0}", "WIP on main: tidy notes", "abc1234", timestamp = 1_793_000_000_000, message = "Before the big rename"),
    Stash("stash@{1}", "On feature/tide: rail", "def5678", timestamp = 1_792_000_000_000),
)

@Preview(name = "Stashes · light")
@Composable
private fun StashListLightPreview() = SyncMdTheme(darkTheme = false) {
    Box(Modifier.background(Tide.colors.paper).padding(16.dp)) { StashList(previewStashes, {}, {}, {}) }
}

@Preview(name = "Stashes · dark")
@Composable
private fun StashListDarkPreview() = SyncMdTheme(darkTheme = true) {
    Box(Modifier.background(Tide.colors.paper).padding(16.dp)) { StashList(previewStashes, {}, {}, {}) }
}

@Preview(name = "Stashes · empty")
@Composable
private fun StashListEmptyPreview() = SyncMdTheme(darkTheme = false) {
    Box(Modifier.background(Tide.colors.paper)) { StashList(emptyList(), {}, {}, {}) }
}

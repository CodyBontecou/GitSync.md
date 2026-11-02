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
import com.bontecou.syncmd.data.models.Tag
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TideType

/**
 * Tide tag list: a white card of hairline-separated rows with mono tag
 * names, an annotated/light pill and the target hash (iOS VaultTagsScreen).
 */
@Composable
fun TagList(
    tags: List<Tag>,
    onDeleteTag: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tags.isEmpty()) {
        TideCard(modifier = modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.ui_no_tags_found_8a6dd503),
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
        itemsIndexed(tags) { index, tag ->
            if (index > 0) TideHairline()
            TagItem(
                tag = tag,
                onDeleteClick = { onDeleteTag(tag.name) }
            )
        }
    }
}

/**
 * Single tag row.
 */
@Composable
fun TagItem(
    tag: Tag,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = Tide.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = tag.name,
                    style = TideType.mono(14.sp, FontWeight.SemiBold),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (tag.isAnnotated) TidePill(stringResource(R.string.tide_git_annotated), c.behind, c.behind.copy(alpha = 0.12f))
                else TidePill(stringResource(R.string.tide_git_lightweight), c.inkMid, c.well)
            }

            // Commit hash — label + mono hash
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.ui_commit_84da95ea),
                    style = TideType.sans(12.sp),
                    color = c.inkFaint,
                )
                Text(
                    text = tag.commitHash.take(7),
                    style = TideType.mono(11.sp, FontWeight.SemiBold),
                    color = c.inkMid,
                )
            }

            val taggerName = tag.taggerName
            val tagMsg = tag.message
            if (tag.createdAt > 0 || taggerName != null) {
                val parts = buildList {
                    if (tag.createdAt > 0) add(formatTimestamp(tag.createdAt))
                    if (!taggerName.isNullOrBlank()) add(taggerName)
                }
                if (parts.isNotEmpty()) {
                    Text(
                        text = parts.joinToString(" · "),
                        style = TideType.meta,
                        color = c.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (tag.isAnnotated && tagMsg != null && tagMsg.isNotBlank()) {
                Text(
                    text = tagMsg,
                    style = TideType.sans(14.sp),
                    color = c.inkMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        val description = stringResource(R.string.ui_delete_tag_365635aa)
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .widthIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onDeleteClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .border(1.dp, c.danger.copy(alpha = 0.35f), CircleShape)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", style = TideType.sans(14.sp, FontWeight.SemiBold), color = c.danger)
            }
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

private val previewTags = listOf(
    Tag("v1.0.0", "e19c4a0d8b7f", isAnnotated = true, message = "First release", taggerName = "Cody", createdAt = 1_793_000_000_000),
    Tag("checkpoint", "a1b2c3d4e5f6"),
)

@Preview(name = "Tags · light")
@Composable
private fun TagListLightPreview() = SyncMdTheme(darkTheme = false) {
    Box(Modifier.background(Tide.colors.paper).padding(16.dp)) { TagList(previewTags, {}) }
}

@Preview(name = "Tags · dark")
@Composable
private fun TagListDarkPreview() = SyncMdTheme(darkTheme = true) {
    Box(Modifier.background(Tide.colors.paper).padding(16.dp)) { TagList(previewTags, {}) }
}

@Preview(name = "Tags · empty")
@Composable
private fun TagListEmptyPreview() = SyncMdTheme(darkTheme = false) {
    Box(Modifier.background(Tide.colors.paper)) { TagList(emptyList(), {}) }
}

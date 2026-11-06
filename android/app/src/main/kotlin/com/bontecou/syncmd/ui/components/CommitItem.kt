package com.bontecou.syncmd.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import com.bontecou.syncmd.data.models.Commit
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideChevron
import com.bontecou.syncmd.ui.theme.TideType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A single commit in the history list — Tide timeline row: ring bullet, the
 * message in sans, git facts (hash · author · time) in mono.
 */
@Composable
fun CommitItem(
    commit: Commit,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit,
    onRevertClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = Tide.colors
    val shape = RoundedCornerShape(Tide.radiusCard)
    Column(modifier.fillMaxWidth().clip(shape).background(c.card, shape)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { onToggleExpand() }
                .heightIn(min = 60.dp)
                .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(12.dp).border(2.dp, c.ink, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    commit.message.split("\n").first(),
                    style = TideType.body,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${commit.shortHash} · ${commit.author} · ${formatTimestamp(commit.timestamp)}",
                    style = TideType.mono(11.sp),
                    color = c.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val expandLabel = stringResource(if (isExpanded) R.string.dialogs_commit_collapse else R.string.dialogs_commit_expand)
            Box(Modifier.size(32.dp).semantics { contentDescription = expandLabel }, contentAlignment = Alignment.Center) {
                Box(Modifier.rotate(if (isExpanded) 270f else 90f)) { TideChevron() }
            }
            val revertLabel = stringResource(R.string.ui_revert_commit_a2d355f7)
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onRevertClick)
                    .semantics { contentDescription = revertLabel },
                contentAlignment = Alignment.Center,
            ) {
                Text("↺", style = TideType.sans(18.sp, FontWeight.SemiBold), color = c.danger)
            }
        }

        AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .background(c.well, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (commit.message.contains("\n")) {
                    CommitDetailLabel(stringResource(R.string.ui_message_f5394f72))
                    Text(commit.message, style = TideType.mono(12.sp), color = c.ink)
                }
                CommitDetailLabel(stringResource(R.string.ui_metadata_b17f2c85))
                CommitFact(stringResource(R.string.ui_hash_a9106914), commit.hash)
                val email = commit.email
                if (email != null && email.isNotBlank()) {
                    CommitFact(stringResource(R.string.ui_email_969ccbd3), email)
                }
                if (commit.parentHashes.isNotEmpty()) {
                    CommitFact(
                        stringResource(R.string.ui_parents_1_s_914efbd0, (commit.parentHashes.size).toString()),
                        commit.parentHashes.joinToString("\n") { it.take(7) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CommitDetailLabel(text: String) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
    Text(text.trimEnd(':'), style = TideType.section, color = Tide.colors.primary)
}

@Composable
private fun CommitFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = TideType.sans(12.sp, FontWeight.Medium), color = Tide.colors.inkMid)
        Text(value, style = TideType.mono(11.sp), color = Tide.colors.ink)
    }
}

/**
 * Format a unix timestamp into a readable date string.
 */
@Composable
fun formatTimestamp(timestamp: Long): String {
    return if (timestamp > 0) {
        val date = Date(timestamp * 1000)
        val format = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        format.format(date)
    } else {
        stringResource(R.string.dialogs_timestamp_unknown)
    }
}

private val previewCommit = Commit(
    hash = "b32427b9c0ffee1234567890abcdef1234567890",
    author = "Phone",
    email = "phone@example.invalid",
    message = "Daily note\n\nWritten on the train.",
    timestamp = 1_762_000_000,
    parentHashes = listOf("a1b2c3d4e5f6"),
)

@Preview(name = "Commit · collapsed")
@Composable
private fun PreviewCommitCollapsed() = SyncMdTheme { Box(Modifier.background(Tide.colors.paper).padding(16.dp)) { CommitItem(previewCommit, false, {}, {}) } }

@Preview(name = "Commit · expanded dark")
@Composable
private fun PreviewCommitExpandedDark() = SyncMdTheme(darkTheme = true) { Box(Modifier.background(Tide.colors.paper).padding(16.dp)) { CommitItem(previewCommit, true, {}, {}) } }

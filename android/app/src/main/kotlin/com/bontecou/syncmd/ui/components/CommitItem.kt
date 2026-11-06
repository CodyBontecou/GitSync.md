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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Commit
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Tag
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A timeline dot: filled (unpushed, ahead color) or a ring (on the remote). */
data class CommitDot(val color: Color, val ring: Boolean)

/**
 * A commit on the History timeline (board 04): the spine with its dot, the message with tag chips
 * ("v0.4"), then mono "hash · author · time · N files". Tap to show the full message, hash, email
 * and parents; [onRevertClick] adds a Revert action there.
 */
@Composable
fun CommitItem(
    commit: Commit,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit,
    onRevertClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    dot: CommitDot? = null,
    meta: String? = null,
    tags: List<String> = emptyList(),
    lastInList: Boolean = false,
) {
    val c = M3.colors
    val bullet = dot ?: CommitDot(c.onVar, ring = true)
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onToggleExpand)
            .padding(start = 26.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.width(12.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(2.dp).height(14.dp).background(c.outlineVar))
            Box(Modifier.size(12.dp).then(
                if (bullet.ring) Modifier.background(c.surface, CircleShape).border(2.dp, bullet.color, CircleShape)
                else Modifier.background(bullet.color, CircleShape)
            ))
            Box(Modifier.width(2.dp).weight(1f).background(if (lastInList) Color.Transparent else c.outlineVar))
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(commit.message.lineSequence().firstOrNull().orEmpty(), style = M3Type.step.copy(lineHeight = 22.sp),
                    color = c.on, maxLines = if (isExpanded) 4 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                tags.forEach { M3Tag(it) }
            }
            Text(meta ?: listOf(commit.shortHash, commit.author).joinToString(" · "), style = M3Type.meta, color = c.onVar,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(
                    Modifier.padding(top = 8.dp).fillMaxWidth().background(c.cLow, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val body = commit.message.substringAfter('\n', "").trim()
                    if (body.isNotEmpty()) Text(body, style = M3Type.bodyMedium, color = c.on)
                    CommitFact(stringResource(R.string.ui_hash_a9106914), commit.hash)
                    commit.email?.takeIf { it.isNotBlank() }?.let { CommitFact(stringResource(R.string.ui_email_969ccbd3), it) }
                    if (commit.parentHashes.isNotEmpty()) CommitFact(
                        stringResource(R.string.ui_parents_1_s_914efbd0, commit.parentHashes.size.toString()),
                        commit.parentHashes.joinToString(" ") { it.take(7) },
                    )
                    if (onRevertClick != null) M3Button(stringResource(R.string.ui_revert_commit_a2d355f7), onRevertClick,
                        style = M3ButtonStyle.Outlined, size = M3ButtonSize.Small, destructive = true)
                }
            }
        }
    }
}

@Composable
private fun CommitFact(label: String, value: String) {
    Column {
        Text(label, style = M3Type.badge, color = M3.colors.onVar, modifier = Modifier.semantics { heading() })
        Text(value, style = M3Type.meta, color = M3.colors.on)
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
    timestamp = 1_762_000_000_000,
    parentHashes = listOf("a1b2c3d4e5f6"),
)

@Preview(name = "Commit · collapsed")
@Composable
private fun PreviewCommitCollapsed() = SyncMdTheme { Box(Modifier.background(M3.colors.surface)) { CommitItem(previewCommit, false, {}, tags = listOf("v0.4")) } }

@Preview(name = "Commit · expanded dark")
@Composable
private fun PreviewCommitExpandedDark() = SyncMdTheme(darkTheme = true) {
    Box(Modifier.background(M3.colors.surface)) { CommitItem(previewCommit, true, {}, onRevertClick = {}, dot = CommitDot(M3.colors.ahead, false)) }
}

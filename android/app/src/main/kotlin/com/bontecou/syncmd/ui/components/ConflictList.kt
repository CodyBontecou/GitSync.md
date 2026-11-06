package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3RadioIndicator
import com.bontecou.syncmd.ui.theme.M3SelectableCard
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme

/** The part of a conflicted file each side changed: its section heading and the lines that differ. */
data class ConflictExcerpt(val heading: String?, val lines: List<Pair<String, Boolean>>)

/**
 * Where two versions of a text file first differ. [heading] is the nearest Markdown heading above
 * the change (without the #s), so the screen can say "Both sides edited “Step 3”."
 */
object ConflictExcerpts {
    private const val CONTEXT = 4

    fun heading(ours: String, theirs: String): String? = section(ours.lines(), theirs.lines()).first

    fun excerpt(mine: String, other: String): ConflictExcerpt {
        val a = mine.lines(); val b = other.lines()
        val (heading, headingIndex) = section(a, b)
        val prefix = a.zip(b).takeWhile { it.first == it.second }.size
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val changedEnd = (a.size - suffix).coerceAtLeast(prefix)
        val start = if (headingIndex != null && prefix - headingIndex <= 2) headingIndex else (prefix - 1).coerceAtLeast(0)
        val end = (changedEnd + 1).coerceAtMost(a.size).coerceAtLeast((start + 1).coerceAtMost(a.size))
        val shown = (start until end).take(CONTEXT + 2).map { i -> a[i] to (i in prefix until changedEnd) }
        return ConflictExcerpt(heading, shown)
    }

    /** Nearest heading at or above the first differing line, and its index in [a]. */
    private fun section(a: List<String>, b: List<String>): Pair<String?, Int?> {
        val prefix = a.zip(b).takeWhile { it.first == it.second }.size
        for (i in prefix.coerceAtMost(a.size - 1) downTo 0) {
            val line = a.getOrNull(i)?.trim() ?: continue
            if (line.startsWith("#")) return line.trimStart('#').trim().takeIf { it.isNotEmpty() } to i
        }
        return null to null
    }
}

/**
 * One side of a conflict (conflict board 09): a selectable card with the radio, a side dot (filled
 * for this phone, ringed for the remote), who and when, then the changed lines on a tinted block
 * with each changed line marked.
 */
@Composable
fun ConflictSideCard(
    who: String,
    whenText: String,
    selected: Boolean,
    ours: Boolean,
    excerpt: ConflictExcerpt?,
    note: String?,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = M3.colors
    val sideColor = if (ours) c.ahead else c.behind
    val (block, mark) = if (ours) c.tcSoft to c.tc else c.pc.copy(alpha = 0.45f) to c.pc
    M3SelectableCard(selected, onSelect, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            M3RadioIndicator(selected)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GitSideDot(sideColor, ring = !ours, size = 10.dp)
                Text(who, style = M3Type.body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(whenText, style = M3Type.metaSmall, color = c.onVar)
        }
        val shape = RoundedCornerShape(12.dp)
        Column(Modifier.padding(top = 12.dp).fillMaxWidth().background(block, shape).padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(if (ours) "conflict-ours-excerpt" else "conflict-theirs-excerpt")) {
            if (note != null) Text(note, style = M3Type.code.copy(lineHeight = 21.sp), color = c.onVar)
            else excerpt?.lines?.forEach { (line, changed) ->
                Text(buildAnnotatedString {
                    if (changed && line.isNotBlank()) withStyle(SpanStyle(background = mark)) { append(" $line ") } else append(line.ifEmpty { " " })
                }, style = M3Type.code.copy(lineHeight = 21.sp), color = c.on, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Preview(name = "Conflict sides · light")
@Composable
private fun ConflictSidesPreview() = SyncMdTheme(darkTheme = false) {
    val ours = "## Step 3\nTranscribe with on-device Whisper,\nthen tag by speaker."
    val theirs = "## Step 3\nTranscribe with the API,\nthen summarize to inbox."
    Column(Modifier.background(M3.colors.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ConflictSideCard("This phone", "edited 40m ago", true, true, ConflictExcerpts.excerpt(ours, theirs), null, {})
        ConflictSideCard("GitHub", "from Mac · 14m ago", false, false, ConflictExcerpts.excerpt(theirs, ours), null, {})
    }
}

@Preview(name = "Conflict sides · dark")
@Composable
private fun ConflictSidesDarkPreview() = SyncMdTheme(darkTheme = true) {
    val ours = "## Step 3\nTranscribe with on-device Whisper,\nthen tag by speaker."
    val theirs = "## Step 3\nTranscribe with the API,\nthen summarize to inbox."
    Column(Modifier.background(M3.colors.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ConflictSideCard("This phone", "edited 40m ago", false, true, ConflictExcerpts.excerpt(ours, theirs), null, {})
        ConflictSideCard("GitHub", "from Mac · 14m ago", true, false, ConflictExcerpts.excerpt(theirs, ours), null, {})
    }
}


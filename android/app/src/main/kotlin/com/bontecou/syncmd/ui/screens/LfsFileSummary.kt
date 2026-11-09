package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.lfs.LfsFileInfo
import com.bontecou.syncmd.services.git.lfs.LfsPointer
import com.bontecou.syncmd.ui.components.LfsMono
import com.bontecou.syncmd.ui.components.LfsSizes
import com.bontecou.syncmd.ui.theme.*

private val imageTypes = setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "svg", "psd", "tif", "tiff", "bmp")
private val audioTypes = setOf("m4a", "mp3", "wav", "aac", "flac", "ogg", "opus", "aiff")

/**
 * One tracked LFS object (board "Large files" › Tracked files): a 40dp tile toned by state (ok =
 * contents here, muted = pointer only, ahead = local edits), the mono path, the state badge, the
 * size and cache line, and a lock glyph when the server holds a lock on it.
 */
@Composable
internal fun RowScope.LfsFileSummary(file: LfsFileInfo, locked: Boolean = false) {
    val c = M3.colors
    val tone = when { file.modified -> M3Tone.Ahead; file.hydrated -> M3Tone.Ok; else -> M3Tone.Muted }
    val (bg, fg) = tone.colors()
    val extension = file.path.substringAfterLast('.', "").lowercase()
    M3LeadingTile(when (extension) { in imageTypes -> R.drawable.ic_m3_image; in audioTypes -> R.drawable.ic_m3_audio_file; else -> R.drawable.ic_m3_file },
        container = bg, content = fg)
    Column(Modifier.weight(1f)) {
        Text(file.path, style = LfsMono.copy(fontWeight = FontWeight.Medium), color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
        M3Badge(stringResource(when {
            file.modified -> R.string.lfs_file_local_edits
            file.hydrated -> R.string.lfs_file_available
            else -> R.string.lfs_m3_state_pointer
        }), tone, Modifier.padding(top = 4.dp).testTag("lfs-file-state"))
        Text(stringResource(R.string.l10n_meta_pair, LfsSizes.format(file.pointer.size), stringResource(if (file.cached) R.string.lfs_m3_cache_verified else R.string.lfs_m3_cache_missing)),
            style = M3Type.meta, color = c.onVar, modifier = Modifier.padding(top = 4.dp).testTag("lfs-file-storage"))
    }
    if (locked) M3Icon(R.drawable.ic_m3_lock, tint = c.onVar, size = 20.dp, contentDescription = stringResource(R.string.lfs_m3_locked))
}

/** Compatibility entry point: the summary laid out in its own row. */
@Composable
internal fun LfsFileSummary(file: LfsFileInfo) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { LfsFileSummary(file, false) }
}

@Composable
private fun LfsFileSummaryPreviewContent() {
    M3ListCard(Modifier.padding(16.dp)) {
        listOf(
            LfsFileInfo("attachments/whiteboard.png", LfsPointer("a".repeat(64), 1_200_000), hydrated = true, cached = true, modified = false, lockable = true),
            LfsFileInfo("audio/field-recording.m4a", LfsPointer("b".repeat(64), 38_000_000), hydrated = false, cached = false, modified = false, lockable = false),
            LfsFileInfo("attachments/diagram.psd", LfsPointer("c".repeat(64), 8_800_000), hydrated = true, cached = true, modified = true, lockable = true),
        ).forEachIndexed { i, file ->
            Row(Modifier.m3TopDivider(i > 0, M3.colors.outlineVar).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { LfsFileSummary(file, locked = i != 1) }
        }
    }
}

@Preview(name = "LFS files · light", showBackground = true, backgroundColor = 0xFFFBF8F3)
@Composable
private fun LfsFileSummaryLight() = SyncMdTheme(darkTheme = false) { LfsFileSummaryPreviewContent() }

@Preview(name = "LFS files · dark", showBackground = true, backgroundColor = 0xFF131316)
@Composable
private fun LfsFileSummaryDark() = SyncMdTheme(darkTheme = true) { LfsFileSummaryPreviewContent() }

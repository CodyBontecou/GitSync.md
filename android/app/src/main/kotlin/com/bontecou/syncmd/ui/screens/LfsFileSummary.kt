package com.bontecou.syncmd.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.lfs.LfsFileInfo
import com.bontecou.syncmd.services.git.lfs.LfsPointer
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TideType

/** Resolve presentation from the current UI locale; stored Git/LFS state stays language-independent. */
@Composable
internal fun LfsFileSummary(file: LfsFileInfo) {
    val c = Tide.colors
    Text(file.path, style = TideType.mono(13.sp, androidx.compose.ui.text.font.FontWeight.Medium).copy(textDirection = TextDirection.Ltr), color = c.ink)
    val size = Formatter.formatFileSize(LocalContext.current, file.pointer.size)
    val cache = stringResource(if (file.cached) R.string.lfs_cache_verified else R.string.lfs_cache_missing)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Orange = edited here, green = contents on this device, quiet = pointer only.
        val (fg, bg) = when {
            file.modified -> c.ahead to c.ahead.copy(alpha = 0.12f)
            file.hydrated -> c.synced to c.synced.copy(alpha = 0.12f)
            else -> c.inkMid to c.well
        }
        TidePill(stringResource(when {
            file.modified -> R.string.lfs_file_local_edits
            file.hydrated -> R.string.lfs_file_available
            else -> R.string.lfs_file_pointer
        }), fg, bg, Modifier.testTag("lfs-file-state"))
    }
    Text("$size · $cache", style = TideType.meta, color = c.inkFaint, modifier = Modifier.testTag("lfs-file-storage"))
}

@Composable
private fun LfsFileSummaryPreviewContent() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(
            LfsFileInfo("assets/diagram.psd", LfsPointer("a".repeat(64), 48_000_000), hydrated = true, cached = true, modified = false, lockable = true),
            LfsFileInfo("audio/interview.wav", LfsPointer("b".repeat(64), 120_000_000), hydrated = false, cached = false, modified = false, lockable = false),
            LfsFileInfo("video/cut.mov", LfsPointer("c".repeat(64), 900_000_000), hydrated = true, cached = true, modified = true, lockable = false),
        ).forEach { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { LfsFileSummary(it) } }
    }
}

@Preview(name = "LFS files · light", showBackground = true)
@Composable
private fun LfsFileSummaryLight() = SyncMdTheme(darkTheme = false) { LfsFileSummaryPreviewContent() }

@Preview(name = "LFS files · dark", showBackground = true, backgroundColor = 0xFF111114)
@Composable
private fun LfsFileSummaryDark() = SyncMdTheme(darkTheme = true) { LfsFileSummaryPreviewContent() }

package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.LfsNotice

/** A finished LFS operation as an M3 note: ok container when it landed ("Restored 1 file…"), muted otherwise. */
@Composable
internal fun LfsOperationNotice(notice: LfsNotice) {
    val text = when (notice) {
        LfsNotice.TrackingUpdated -> stringResource(R.string.lfs_m3_tracking_updated)
        LfsNotice.ExtensionsUpdated -> stringResource(R.string.lfs_m3_extensions_updated)
        is LfsNotice.Hydrated -> listOf(
            pluralStringResource(R.plurals.lfs_restored_files, notice.files, notice.files),
            pluralStringResource(R.plurals.lfs_downloaded_objects, notice.objects, notice.objects),
            stringResource(R.string.lfs_local_edits_kept),
        ).joinToString(" ")
        is LfsNotice.LockListRefreshed -> stringResource(if (notice.supported) R.string.lfs_locks_refreshed else R.string.lfs_m3_unsupported)
        is LfsNotice.Plain -> notice.text
    }
    val tone = when (notice) {
        LfsNotice.TrackingUpdated, LfsNotice.ExtensionsUpdated, is LfsNotice.Hydrated -> M3NoteTone.Ok
        is LfsNotice.LockListRefreshed -> if (notice.supported) M3NoteTone.Ok else M3NoteTone.Muted
        is LfsNotice.Plain -> M3NoteTone.Muted
    }
    M3Note(text, Modifier.testTag("lfs-notice").semantics { liveRegion = LiveRegionMode.Polite }, tone = tone)
}

@Composable
private fun LfsNoticePreviewContent() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LfsOperationNotice(LfsNotice.TrackingUpdated)
        LfsOperationNotice(LfsNotice.Hydrated(1, 1))
        LfsOperationNotice(LfsNotice.LockListRefreshed(false))
        LfsOperationNotice(LfsNotice.Plain("Server locks were refreshed."))
    }
}

@Preview(name = "LFS notices · light", showBackground = true, backgroundColor = 0xFFFBF8F3)
@Composable
private fun LfsNoticeLight() = SyncMdTheme(darkTheme = false) { LfsNoticePreviewContent() }

@Preview(name = "LFS notices · dark", showBackground = true, backgroundColor = 0xFF131316)
@Composable
private fun LfsNoticeDark() = SyncMdTheme(darkTheme = true) { LfsNoticePreviewContent() }

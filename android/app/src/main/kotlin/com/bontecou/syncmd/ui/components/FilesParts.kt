package com.bontecou.syncmd.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3Spinner
import com.bontecou.syncmd.ui.theme.M3Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Pieces of the Files, editor and diff boards (03, 06, 07) that the M3 kit doesn't carry.

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "heic", "heif", "bmp", "avif", "tif", "tiff")
private val audioExtensions = setOf("m4a", "mp3", "wav", "ogg", "oga", "flac", "aac", "opus", "amr", "3gp", "aiff", "aif")

/** Folder, image, audio or document glyph by name. */
@DrawableRes
fun fileTypeIcon(name: String, isDirectory: Boolean): Int {
    if (isDirectory) return R.drawable.ic_m3_folder
    return when (name.substringAfterLast('.', "").lowercase()) {
        in imageExtensions -> R.drawable.ic_m3_image
        in audioExtensions -> R.drawable.ic_m3_audio_file
        else -> R.drawable.ic_m3_file
    }
}

/** "just now", "9m ago", "6h ago", "3d ago", "2w ago", "4mo ago", "1y ago". */
@Composable
fun fileAgeLabel(time: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - time).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.files_m3_age_now)
        minutes < 60 -> stringResource(R.string.files_m3_age_minutes, minutes)
        minutes < 60 * 24 -> stringResource(R.string.files_m3_age_hours, minutes / 60)
        minutes < 60 * 24 * 7 -> stringResource(R.string.files_m3_age_days, minutes / (60 * 24))
        minutes < 60 * 24 * 30 -> stringResource(R.string.files_m3_age_weeks, minutes / (60 * 24 * 7))
        minutes < 60 * 24 * 365 -> stringResource(R.string.files_m3_age_months, minutes / (60 * 24 * 30))
        else -> stringResource(R.string.files_m3_age_years, minutes / (60 * 24 * 365))
    }
}

/** "820 B", "1.4 KB", "38 MB" in the display locale. */
@Composable
fun fileSizeLabel(bytes: Long): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return when {
        bytes < 1024 -> stringResource(R.string.vault_l10n_size_bytes, java.text.NumberFormat.getIntegerInstance(locale).format(bytes))
        bytes < 1024 * 1024 -> stringResource(R.string.vault_l10n_size_kb, String.format(locale, "%.1f", bytes / 1024.0))
        bytes < 10L * 1024 * 1024 -> stringResource(R.string.vault_l10n_size_mb, String.format(locale, "%.1f", bytes / (1024.0 * 1024.0)))
        else -> stringResource(R.string.vault_l10n_size_mb, String.format(locale, "%.0f", bytes / (1024.0 * 1024.0)))
    }
}

/** Breadcrumb chips (board 03): ancestors outlined and tappable, the current folder filled, item count on the right. */
@Composable
fun FilesBreadcrumbs(vaultName: String, directory: String, itemCount: Int?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = M3.colors
    val segments = directory.split('/').filter { it.isNotEmpty() }
    val crumbs = listOf(vaultName to "") + segments.mapIndexed { i, name -> name to segments.take(i + 1).joinToString("/") }
    val label = stringResource(R.string.files_m3_folder_path)
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            crumbs.forEachIndexed { index, (name, path) ->
                val current = index == crumbs.lastIndex
                val shape = RoundedCornerShape(8.dp)
                if (index > 0) M3Icon(R.drawable.ic_m3_chevron_right, tint = c.onVar, size = 18.dp)
                Box(
                    Modifier
                        .heightIn(min = 32.dp)
                        .clip(shape)
                        .then(if (current) Modifier.background(c.sc, shape) else Modifier.border(1.dp, c.outlineVar, shape)
                            .clickable(role = Role.Button) { onOpen(path) })
                        .padding(horizontal = 12.dp)
                        .testTag(if (current) "files-crumb-current" else "files-crumb-$path"),
                    contentAlignment = Alignment.Center,
                ) { Text(name, style = M3Type.chip, color = if (current) c.onSc else c.onVar, maxLines = 1) }
            }
        }
        if (itemCount != null) Text(pluralStringResource(R.plurals.files_m3_items, itemCount, itemCount), style = M3Type.caption, color = c.onVar,
            modifier = Modifier.padding(start = 8.dp).testTag("files-item-count"))
    }
}

/** The 40dp leading tile: folders on pc, everything else on cHigh. */
@Composable
fun FileLeadingTile(name: String, isDirectory: Boolean) {
    val c = M3.colors
    Box(Modifier.size(40.dp).background(if (isDirectory) c.pc else c.cHigh, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        M3Icon(fileTypeIcon(name, isDirectory), tint = if (isDirectory) c.onPc else c.onVar)
    }
}

/** "Fetch" for an LFS pointer: 32dp, sc/onSc, 16dp corners; a spinner while downloading. */
@Composable
fun LfsFetchButton(name: String, fetching: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = M3.colors
    val description = stringResource(R.string.files_m3_fetch_cd, name)
    Box(
        modifier.heightIn(min = 48.dp).clickable(enabled = enabled && !fetching, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).background(c.sc, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (fetching) M3Spinner(color = c.onSc)
            Text(stringResource(R.string.files_m3_fetch), style = M3Type.button.copy(fontSize = 13.sp), color = c.onSc)
        }
    }
}

/**
 * "Open in…": hands a read-only copy to another app. Vault folders live in shared storage that the
 * app's FileProvider can't serve directly, so the file is copied to a private cache first.
 */
suspend fun openFileInOtherApp(context: Context, repositoryPath: String, relativePath: String): Boolean {
    val copy = withContext(Dispatchers.IO) {
        val root = File(repositoryPath).canonicalFile
        val source = File(root, relativePath).canonicalFile
        require(source.isFile && source.path.startsWith(root.path + File.separator)) { context.getString(R.string.l10n_not_vault_file) }
        val folder = File(context.cacheDir, "open-in").apply { mkdirs() }
        folder.listFiles()?.forEach { it.deleteRecursively() }
        File(folder, source.name).also { source.copyTo(it, overwrite = true) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(copy.extension.lowercase())
        ?: if (copy.extension.equals("md", true)) "text/markdown" else "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newUri(context.contentResolver, copy.name, uri)
    return try {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.files_m3_open_in_chooser, copy.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) { false }
}

package com.bontecou.syncmd.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

/**
 * Opens Android's directory browser and returns an absolute filesystem path when possible.
 *
 * Note: Sync.md currently stores repos using java.io.File paths, so we only accept directories
 * that can be resolved to writable file-system paths.
 */
@Composable
fun rememberDirectoryPicker(
    onDirectorySelected: (String) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        val path = resolveTreeUriToAbsolutePath(uri)
        if (path == null) {
            onError("Could not resolve that folder. Please choose a local device folder.")
            return@rememberLauncherForActivityResult
        }

        if (!isDirectoryWritable(path)) {
            onError("That folder is not writable by Sync.md. Please pick another folder.")
            return@rememberLauncherForActivityResult
        }

        onDirectorySelected(path)
    }

    return remember(launcher) {
        { launcher.launch(null) }
    }
}

private fun resolveTreeUriToAbsolutePath(uri: Uri): String? {
    val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        ?: return null

    if (treeDocId.startsWith("raw:")) {
        return treeDocId.removePrefix("raw:").trimEnd('/')
    }

    val parts = treeDocId.split(':', limit = 2)
    val volume = parts.firstOrNull() ?: return null
    val relative = parts.getOrNull(1).orEmpty().trim('/').replace("//", "/")

    return when (volume.lowercase()) {
        "primary" -> buildPath("/storage/emulated/0", relative)
        "home" -> {
            // SAF "home" points to the user's Documents directory.
            val homeRelative = if (relative.isBlank()) "" else relative
            buildPath("/storage/emulated/0/Documents", homeRelative)
        }
        else -> buildPath("/storage/$volume", relative)
    }
}

private fun buildPath(base: String, relative: String): String {
    val cleanBase = base.trimEnd('/')
    return if (relative.isBlank()) cleanBase else "$cleanBase/$relative"
}

private fun isDirectoryWritable(path: String): Boolean {
    val dir = File(path)
    if (!dir.exists() && !dir.mkdirs()) return false
    if (!dir.isDirectory) return false

    val probe = File(dir, ".syncmd-write-probe-${System.currentTimeMillis()}")
    return runCatching {
        probe.writeText("ok")
        probe.delete()
        true
    }.getOrDefault(false)
}

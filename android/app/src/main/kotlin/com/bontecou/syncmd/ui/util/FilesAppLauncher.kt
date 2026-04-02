package com.bontecou.syncmd.ui.util

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import java.io.File

/**
 * Opens Android's Files UI focused on a specific absolute folder path when possible.
 */
object FilesAppLauncher {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun openFolder(context: Context, absolutePath: String): Boolean {
        val resolvedPath = resolveExistingPath(absolutePath)
        val docId = absolutePathToDocId(resolvedPath) ?: return false
        val treeUri = DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
        val docUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)

        val intents = listOf(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, treeUri),
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri),
        )

        intents.forEach { base ->
            val intent = base
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

            runCatching { context.startActivity(intent) }
                .onSuccess { return true }
        }

        // Last-resort fallback: open picker root (still better than failing silently).
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }

    private fun resolveExistingPath(path: String): String {
        val clean = runCatching { File(path).canonicalPath }.getOrElse { path }
        val direct = File(clean)
        if (direct.exists()) return direct.absolutePath

        val root = File("/storage/emulated/0")
        if (!clean.startsWith(root.absolutePath + "/")) return clean

        var cursor = root
        val parts = clean.removePrefix(root.absolutePath).trim('/').split('/').filter { it.isNotBlank() }
        for (part in parts) {
            val next = File(cursor, part)
            cursor = if (next.exists()) {
                next
            } else {
                cursor.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return clean
            }
        }
        return cursor.absolutePath
    }

    private fun absolutePathToDocId(path: String): String? {
        val clean = runCatching { File(path).canonicalPath }.getOrElse { path }

        return when {
            clean == "/storage/emulated/0" || clean == "/sdcard" -> "primary:"
            clean.startsWith("/storage/emulated/0/") -> {
                "primary:${clean.removePrefix("/storage/emulated/0/")}"
            }
            clean.startsWith("/sdcard/") -> {
                "primary:${clean.removePrefix("/sdcard/")}"
            }
            clean.startsWith("/storage/") -> {
                val rest = clean.removePrefix("/storage/")
                val volume = rest.substringBefore("/")
                val relative = rest.substringAfter("/", missingDelimiterValue = "")
                if (volume.isBlank()) null else "$volume:$relative"
            }
            else -> null
        }
    }
}

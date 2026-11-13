package com.bontecou.syncmd.ui.util

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import java.io.File

/**
 * Opens Android's Files UI focused on a specific absolute folder path when possible.
 *
 * Strategy (tried in order until one succeeds):
 *   1. ACTION_VIEW targeting known DocumentsUI packages — reliable on Pixel/AOSP
 *   2. ACTION_VIEW without package targeting — lets the system resolve
 *   3. ACTION_OPEN_DOCUMENT_TREE with EXTRA_INITIAL_URI (document URI) — opens picker
 *      pre-navigated to the target folder
 *   4. Bare ACTION_OPEN_DOCUMENT_TREE — last-resort, opens picker at root
 */
object FilesAppLauncher {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** Known DocumentsUI package names across AOSP/Google/OEM variants. */
    private val DOCUMENTS_UI_PACKAGES = listOf(
        "com.google.android.documentsui",
        "com.android.documentsui",
    )

    fun openFolder(context: Context, absolutePath: String): Boolean {
        val resolvedPath = resolveExistingPath(absolutePath)
        val docId = absolutePathToDocId(resolvedPath) ?: return false
        val docUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)

        // ── 1. ACTION_VIEW targeting known DocumentsUI packages ──────────
        for (pkg in DOCUMENTS_UI_PACKAGES) {
            val launched = tryLaunch(context) {
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    .setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (launched) return true
        }

        // ── 2. ACTION_VIEW without package — let the system decide ───────
        val viewLaunched = tryLaunch(context) {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (viewLaunched) return true

        // ── 3. ACTION_OPEN_DOCUMENT_TREE pre-navigated to the folder ─────
        // Using a document URI (not tree URI) as EXTRA_INITIAL_URI gives the
        // picker the best chance of navigating to the target directory.
        val pickerLaunched = tryLaunch(context) {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, docUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (pickerLaunched) return true

        // ── 4. Bare picker (better than failing silently) ────────────────
        return tryLaunch(context) {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Build an intent and attempt to start it. Returns true on success.
     */
    private inline fun tryLaunch(context: Context, buildIntent: () -> Intent): Boolean {
        return runCatching { context.startActivity(buildIntent()) }
            .isSuccess
    }

    // ── Path helpers ─────────────────────────────────────────────────────

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

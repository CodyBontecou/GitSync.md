package com.bontecou.syncmd.storage

import android.content.Context
import java.io.File
/**
 * Play-safe clone storage helpers.
 *
 * For Obsidian interoperability, prefer app-owned user-visible storage first
 * (Android/media) and fall back to internal app storage when needed.
 */
@Suppress("DEPRECATION")
object CloneStorage {

    /**
     * Obsidian-compatible clone root (Android/media).
     * Returns null when no writable user-visible app media dir is available.
     */
    fun obsidianCompatibleCloneBaseDir(context: Context): String? {
        val mediaDir = context.externalMediaDirs.firstOrNull() ?: return null
        val reposDir = File(mediaDir, "repos")
        return if (ensureWritableDirectory(reposDir)) reposDir.absolutePath else null
    }

    fun defaultCloneBaseDir(context: Context): String {
        val obsidianBase = obsidianCompatibleCloneBaseDir(context)
        if (obsidianBase != null) return obsidianBase

        val candidates = buildList {
            context.getExternalFilesDir(null)?.let { add(File(it, "repos")) }
            add(File(context.filesDir, "repos"))
        }

        return candidates.firstOrNull { ensureWritableDirectory(it) }?.absolutePath
            ?: File(context.filesDir, "repos").absolutePath
    }

    fun preferredUserVisibleRoot(context: Context): String {
        val candidates = buildList {
            context.externalMediaDirs.firstOrNull()?.let { add(it) }
            context.getExternalFilesDir(null)?.let { add(it) }
            add(context.filesDir)
        }

        return candidates.firstOrNull { ensureWritableDirectory(File(it, "repos")) }?.absolutePath
            ?: context.filesDir.absolutePath
    }

    fun isWithinAppWritableRoots(context: Context, path: String): Boolean {
        val normalized = normalize(path)
        return appWritableRoots(context).any { root ->
            normalized == root || normalized.startsWith("$root/")
        }
    }

    private fun appWritableRoots(context: Context): List<String> {
        return buildList {
            context.externalMediaDirs.firstOrNull()?.let { add(it) }
            context.getExternalFilesDir(null)?.let { add(it) }
            add(context.filesDir)
        }
            .map { normalize(it.absolutePath) }
            .distinct()
    }

    private fun ensureWritableDirectory(dir: File): Boolean {
        if (!dir.exists() && !dir.mkdirs()) return false
        if (!dir.isDirectory) return false

        val probe = File(dir, ".syncmd-write-probe-${System.currentTimeMillis()}")
        return runCatching {
            probe.writeText("ok")
            probe.delete()
            true
        }.getOrDefault(false)
    }

    private fun normalize(path: String): String {
        val trimmed = path.trim().trimEnd('/').replace("//", "/")
        val canonical = runCatching { File(trimmed).canonicalPath }.getOrNull() ?: trimmed
        return canonical.trimEnd('/').replace("//", "/")
    }
}

package com.bontecou.syncmd.storage

import android.content.Context
import java.io.File
import java.nio.file.Files

/**
 * Play-safe clone storage helpers.
 *
 * JGit performs filesystem capability probes (including symlink creation).
 * Android's shared/external FUSE-backed storage can reject those operations
 * with "Operation not permitted", causing clone failures.
 *
 * To keep cloning reliable, we prefer Git-compatible roots and default to
 * internal app storage first.
 */
@Suppress("DEPRECATION")
object CloneStorage {

    fun defaultCloneBaseDir(context: Context): String {
        val candidates = buildList {
            // Most reliable for JGit operations on Android.
            add(File(context.filesDir, "repos"))
            context.getExternalFilesDir(null)?.let { add(File(it, "repos")) }
            context.externalMediaDirs.firstOrNull()?.let { add(File(it, "repos")) }
        }

        return candidates.firstOrNull { ensureGitCompatibleDirectory(it) }?.absolutePath
            ?: File(context.filesDir, "repos").absolutePath
    }

    fun preferredUserVisibleRoot(context: Context): String {
        val candidates = buildList {
            add(context.filesDir)
            context.getExternalFilesDir(null)?.let { add(it) }
            context.externalMediaDirs.firstOrNull()?.let { add(it) }
        }

        return candidates.firstOrNull { ensureGitCompatibleDirectory(File(it, "repos")) }?.absolutePath
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
            add(context.filesDir)
            context.getExternalFilesDir(null)?.let { add(it) }
            context.externalMediaDirs.firstOrNull()?.let { add(it) }
        }
            .map { normalize(it.absolutePath) }
            .distinct()
            .filter { root -> ensureGitCompatibleDirectory(File(root, "repos")) }
            .ifEmpty { listOf(normalize(context.filesDir.absolutePath)) }
    }

    private fun ensureGitCompatibleDirectory(dir: File): Boolean {
        if (!ensureWritableDirectory(dir)) return false

        // Probe for symlink support because JGit can require this capability check
        // during clone/checkout. External FUSE paths often reject it with EPERM.
        val stamp = System.currentTimeMillis()
        val src = File(dir, ".syncmd-git-probe-src-$stamp")
        val link = File(dir, ".syncmd-git-probe-link-$stamp")

        return runCatching {
            src.writeText("ok")
            Files.createSymbolicLink(link.toPath(), src.toPath().fileName)
            true
        }.getOrDefault(false).also {
            runCatching { link.delete() }
            runCatching { src.delete() }
        }
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

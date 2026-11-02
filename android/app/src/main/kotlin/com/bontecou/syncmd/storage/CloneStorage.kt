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
        return RepositoryLocations(context).summary().path
    }

    fun appDefaultCloneBaseDir(context: Context): String {
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

    fun managedCloneRoots(context: Context): List<String> = buildList {
        context.externalMediaDirs.filterNotNull().forEach { add(File(it, "repos")) }
        context.getExternalFilesDirs(null).filterNotNull().forEach { add(File(it, "repos")) }
        add(File(context.filesDir, "repos"))
    }.map { it.canonicalPath }.distinct()

    fun isManagedClonePath(context: Context, path: String): Boolean {
        if (path.startsWith("github://")) return false
        val normalized = File(path).canonicalPath
        return managedCloneRoots(context).any { normalized.startsWith("$it/") }
    }

    fun backupRoot(context: Context, repositoryPath: String): String {
        return File(sourceVolumeRoot(context, repositoryPath), "git-backups").absolutePath
    }

    fun moveRecoveryRoot(context: Context, repositoryPath: String): String =
        File(sourceVolumeRoot(context, repositoryPath), "git-move-recoveries").absolutePath

    private fun sourceVolumeRoot(context: Context, repositoryPath: String): File {
        val repository = File(repositoryPath).canonicalPath
        val externalRoots = buildList {
            addAll(context.externalMediaDirs.filterNotNull())
            addAll(context.getExternalFilesDirs(null).filterNotNull())
        }
        val matching = externalRoots.firstOrNull { repository.startsWith(it.canonicalPath + "/") }
        val volume = android.system.Os.stat(repository).st_dev
        val root = matching ?: (externalRoots + context.filesDir).firstOrNull { candidate ->
            runCatching { (candidate.isDirectory || candidate.mkdirs()) && android.system.Os.stat(candidate.path).st_dev == volume }.getOrDefault(false)
        } ?: error("No app backup folder is available on this repository's storage volume. Your working copy was kept.")
        return root
    }

    fun discoveryRoots(context: Context): List<String> =
        (managedCloneRoots(context) + File(context.filesDir, "sample-workspace").absolutePath).distinct()

    private fun appWritableRoots(context: Context): List<String> {
        return buildList {
            addAll(context.externalMediaDirs.filterNotNull())
            addAll(context.getExternalFilesDirs(null).filterNotNull())
            add(context.filesDir)
            add(context.cacheDir)
            add(context.noBackupFilesDir)
            addAll(context.externalCacheDirs.filterNotNull())
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

            // Prevent MediaProvider/MediaScanner interference during large git checkouts.
            // Android/media is user-visible storage and scanner activity can race with JGit
            // temp-file renames/deletes, causing intermittent checkout conflicts.
            File(dir, ".nomedia").apply {
                if (!exists()) createNewFile()
            }

            true
        }.getOrDefault(false)
    }

    private fun normalize(path: String): String {
        val trimmed = path.trim().trimEnd('/').replace("//", "/")
        val canonical = runCatching { File(trimmed).canonicalPath }.getOrNull() ?: trimmed
        return canonical.trimEnd('/').replace("//", "/")
    }
}

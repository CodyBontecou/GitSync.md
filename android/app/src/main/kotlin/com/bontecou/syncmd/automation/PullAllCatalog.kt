package com.bontecou.syncmd.automation

import android.content.Context
import com.bontecou.syncmd.services.automation.PullAllTarget
import java.io.File

/** One coherent preferences snapshot; removed/uncloned/history entries never become pull targets. */
class PullAllCatalog(context: Context) {
    private val prefs = context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE)
    fun snapshot(): List<PullAllTarget> {
        val values = prefs.all
        val count = values["repository_count"] ?: 0
        require(count is Int && count in 0..10_000) { "Repository settings need repair" }
        return (0 until count).mapNotNull { index ->
            val path = values["repo_${index}_path"]
            val name = values["repo_${index}_name"]
            require(path is String && path.isNotBlank() && name is String && name.isNotBlank()) { "Repository settings need repair" }
            if (path.startsWith("github://")) return@mapNotNull null
            require(File(path).isAbsolute && File(path).canonicalPath == path) { "Reconnect the original repository folder" }
            // Include connection metadata so an action prepared before a catalog replacement cannot silently use it.
            val registration = listOf(values["repo_${index}_date"], values["repo_${index}_remote"], values["repo_${index}_branch"])
                .joinToString("\u0000") { it?.toString().orEmpty() }
            PullAllTarget(path, (values["repo_${index}_alias"] as? String).orEmpty().ifBlank { name }, registration)
        }.distinctBy { it.path }
    }
    /** "main · CodyBontecou/VoxVault" for each catalog path: the saved branch and a short remote (owner/repo on GitHub, else the host). */
    fun details(): Map<String, String> {
        val values = prefs.all
        val count = (values["repository_count"] as? Int)?.coerceIn(0, 10_000) ?: 0
        return (0 until count).mapNotNull { index ->
            val path = values["repo_${index}_path"] as? String ?: return@mapNotNull null
            val branch = (values["repo_${index}_branch"] as? String)?.takeIf { it.isNotBlank() }
            val remote = (values["repo_${index}_remote"] as? String)?.let(::shortRemote)
            path to listOfNotNull(branch, remote).joinToString(" · ")
        }.toMap()
    }
    fun requireCurrent(target: PullAllTarget) {
        require(snapshot().any { it.path == target.path && it.registration == target.registration }) {
            "This repository was removed or changed. Start a fresh Pull All."
        }
    }
}

/** "https://github.com/CodyBontecou/VoxVault.git" → "CodyBontecou/VoxVault"; other hosts show just the host. Never credentials. */
internal fun shortRemote(url: String): String? {
    val trimmed = url.trim().removeSuffix("/").removeSuffix(".git")
    val (host, path) = when {
        trimmed.contains("://") -> runCatching { java.net.URI(trimmed).let { (it.host ?: return null) to it.path.orEmpty().trim('/') } }.getOrNull() ?: return null
        trimmed.contains(':') -> trimmed.substringAfter('@').substringBefore(':') to trimmed.substringAfter(':').trim('/')
        else -> return null
    }
    return if (host.equals("github.com", true) && path.count { it == '/' } == 1) path else host
}

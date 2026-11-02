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
    fun requireCurrent(target: PullAllTarget) {
        require(snapshot().any { it.path == target.path && it.registration == target.registration }) {
            "This repository was removed or changed. Start a fresh Pull All."
        }
    }
}

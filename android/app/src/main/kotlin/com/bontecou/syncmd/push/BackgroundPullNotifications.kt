package com.bontecou.syncmd.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bontecou.syncmd.R
import com.bontecou.syncmd.automation.PullAllCatalog
import com.bontecou.syncmd.automation.VaultShortcutActivity
import com.bontecou.syncmd.automation.VaultWidgets
import com.bontecou.syncmd.services.automation.RepositoryActionOutcome
import com.bontecou.syncmd.services.automation.VaultStatusReader
import com.bontecou.syncmd.services.background.BackgroundRunResult
import com.bontecou.syncmd.services.push.PushHintResult
import java.io.File

/**
 * Board 29 notification after a background pull: "<vault> was updated", "N commits from <author>
 * pulled in the background.", with "See changes" (the vault's history) and "Open in Obsidian".
 */
object BackgroundPullNotifications {
    const val CHANNEL = "repository-updates"

    /** HEAD of every catalog vault before a background pull, so the notification can count what came in. */
    fun heads(context: Context): Map<String, String?> =
        runCatching { PullAllCatalog(context).snapshot().associate { it.path to VaultStatusReader.head(it.path) } }.getOrDefault(emptyMap())

    /** Replace the push hint notification [tag] with "was updated" for each vault the pull moved forward. */
    fun afterPull(context: Context, tag: String, results: List<PushHintResult>, before: Map<String, String?>) {
        VaultWidgets.refresh(context)
        val updated = results.filter { it.result.pullUpdated || it.result.outcome in setOf(RepositoryActionOutcome.PULLED, RepositoryActionOutcome.PULLED_AND_PUSHED) }
        updated.forEachIndexed { index, item ->
            val incoming = VaultStatusReader.incoming(item.target.path, before[item.target.path], item.result.head ?: VaultStatusReader.head(item.target.path))
            updated(context, tag, index + 1, item.target.name, item.target.path, incoming.commits, incoming.author)
        }
    }

    /** Background Sync's pass: post "<vault> was updated" for each vault that actually pulled commits. */
    fun afterBackgroundSync(context: Context, results: List<BackgroundRunResult>, before: Map<String, String?>) {
        val pulled = results.filter { it.pulled }
        if (pulled.isEmpty()) return
        VaultWidgets.refresh(context)
        pulled.forEach { item ->
            val path = item.policy.target.path
            val incoming = VaultStatusReader.incoming(path, before[path], item.health.head ?: VaultStatusReader.head(path))
            if (incoming.commits <= 0) return@forEach
            updated(context, BACKGROUND_SYNC_TAG, path.hashCode(), item.policy.target.name, path, incoming.commits, incoming.author)
        }
    }

    private const val BACKGROUND_SYNC_TAG = "background-sync"

    fun updated(context: Context, tag: String, id: Int, vault: String, path: String, commits: Int, author: String?) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.push_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val body = when {
            commits <= 0 -> context.getString(R.string.notification_m3_pulled)
            author != null -> context.resources.getQuantityString(R.plurals.notification_m3_commits_from, commits, commits, author)
            else -> context.resources.getQuantityString(R.plurals.notification_m3_commits, commits, commits)
        }
        val changes = VaultShortcutActivity.pendingIntent(context, VaultShortcutActivity.CHANGES, path)
        // Obsidian names a vault after its folder.
        val obsidian = PendingIntent.getActivity(context, ("obsidian:$path").hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse("obsidian://open?vault=" + Uri.encode(File(path).name))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_pull_all)
            .setContentTitle(context.getString(R.string.notification_m3_updated, vault)).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(changes)
            .addAction(0, context.getString(R.string.notification_m3_see_changes), changes)
            .addAction(0, context.getString(R.string.notification_m3_open_obsidian), obsidian)
            .setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { manager.notify(tag, id, notification) } catch (_: SecurityException) { }
    }
}

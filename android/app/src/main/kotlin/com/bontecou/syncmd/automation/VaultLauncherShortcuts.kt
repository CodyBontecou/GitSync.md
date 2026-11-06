package com.bontecou.syncmd.automation

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.bontecou.syncmd.MainActivity
import com.bontecou.syncmd.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** An in-app destination asked for by a launcher shortcut, widget or notification. Never carries Git authority. */
data class VaultShortcutRequest(val action: String, val path: String?, val id: String = UUID.randomUUID().toString())

/** Pending navigation for AppShell: open a vault, its history ("See changes"), its commit flow, or Add a vault. */
object VaultShortcutRequests {
    private val mutable = MutableStateFlow<VaultShortcutRequest?>(null)
    val pending = mutable.asStateFlow()
    internal fun request(request: VaultShortcutRequest) { mutable.value = request }
    fun consume(request: VaultShortcutRequest): Boolean = mutable.compareAndSet(request, null)
}

/**
 * Private trampoline (not exported) behind launcher shortcuts, widget rows and notification
 * actions. "Pull" runs a background pull of that one vault through the same fetch-and-fast-forward
 * path as Pull all; everything else only navigates, so nothing commits or pushes without the app.
 */
class VaultShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fresh = savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
        val action = intent.getStringExtra(ACTION)
        val path = intent.getStringExtra(PATH)
        val known = path == null || runCatching { PullAllCatalog(this).snapshot().any { it.path == path } }.getOrDefault(false)
        if (fresh && action in ACTIONS && known) {
            if (action == PULL && path != null) {
                try {
                    val runs = PullAllBackgroundRuns(this)
                    val run = runs.reserve("shortcut")
                    try { startForegroundService(Intent(this, PullAllRunService::class.java).putExtra("run_id", run.id).putExtra(PullAllRunService.ONLY, path)) }
                    catch (failure: Exception) { runs.finish(run.id, BackgroundPullState.FAILED); throw failure }
                } catch (_: Exception) {
                    android.widget.Toast.makeText(this, if (PullAllBackgroundRuns.isActive()) R.string.pull_all_background_busy else R.string.pull_all_failed,
                        android.widget.Toast.LENGTH_LONG).show()
                }
                finish(); return
            }
            VaultShortcutRequests.request(VaultShortcutRequest(action!!, path))
        } else if (fresh && !known) {
            android.widget.Toast.makeText(this, R.string.widget_m3_vault_missing, android.widget.Toast.LENGTH_LONG).show()
            finish(); return
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object {
        const val ACTION = "vault_action"; const val PATH = "vault_path"
        const val OPEN = "open"; const val CHANGES = "changes"; const val PULL = "pull"; const val COMMIT = "commit"; const val ADD = "add"; const val HOME = "home"
        private val ACTIONS = setOf(OPEN, CHANGES, PULL, COMMIT, ADD, HOME)

        fun intent(context: Context, action: String, path: String?) = Intent(context, VaultShortcutActivity::class.java).setAction(Intent.ACTION_VIEW)
            // A distinct data URI per vault and action keeps PendingIntents from replacing each other.
            .setData(Uri.parse("gitsync-internal://vault/$action/${Uri.encode(path.orEmpty())}"))
            .putExtra(ACTION, action).apply { if (path != null) putExtra(PATH, path) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun pendingIntent(context: Context, action: String, path: String?): PendingIntent =
            PendingIntent.getActivity(context, 0, intent(context, action, path), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/**
 * Launcher long-press shortcuts: "<Vault>: Pull" and "<Vault>: Commit & push" for the pinned (else
 * most recently synced) vaults, next to the static "Add a vault" from res/xml/shortcuts.xml.
 */
object VaultLauncherShortcuts {
    private const val VAULTS = 2

    fun publish(context: Context, items: List<VaultSurfaceItem>) {
        val limit = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - 1).coerceAtLeast(0)
        val shortcuts = items.take(VAULTS).flatMapIndexed { rank, item ->
            listOf(
                shortcut(context, item, VaultShortcutActivity.PULL, context.getString(R.string.shortcut_m3_pull, item.name),
                    context.getString(R.string.shortcut_m3_pull_short), R.drawable.ic_shortcut_m3_pull, rank * 2),
                shortcut(context, item, VaultShortcutActivity.COMMIT, context.getString(R.string.shortcut_m3_commit_push, item.name),
                    context.getString(R.string.shortcut_m3_commit_short), R.drawable.ic_shortcut_m3_push, rank * 2 + 1),
            )
        }.take(limit)
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    private fun shortcut(context: Context, item: VaultSurfaceItem, action: String, long: String, short: String, icon: Int, rank: Int) =
        ShortcutInfoCompat.Builder(context, "vault-$action-${Integer.toHexString(item.path.hashCode())}")
            .setShortLabel(if (long.length <= 25) long else short)
            .setLongLabel(long)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setRank(rank)
            .setIntent(VaultShortcutActivity.intent(context, action, item.path))
            .build()
}

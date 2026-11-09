package com.bontecou.syncmd.automation

import com.bontecou.syncmd.ui.util.countedPhrase
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.automation.VaultStatus
import com.bontecou.syncmd.services.automation.VaultStatusReader
import com.bontecou.syncmd.storage.VaultPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

@EntryPoint @InstallIn(SingletonComponent::class)
interface VaultPreferencesEntryPoint { fun vaultPreferences(): VaultPreferences }

/** One vault as the widgets, tiles and launcher shortcuts show it: display name, pin and local status. */
data class VaultSurfaceItem(val path: String, val name: String, val pinned: Boolean, val status: VaultStatus?)

/**
 * Board 29 "Widgets, tile & notification": the vault list widget, the single-vault widget and the
 * launcher shortcuts all render from one local read of the catalog (no network), refreshed by a
 * unique worker after Pull all, background pulls, widget updates and every 30 minutes.
 */
object VaultWidgets {
    private const val WORK = "vault-surfaces-refresh"
    private const val PREFS = "vault_widgets"

    /** Re-render every widget and republish launcher shortcuts in the background. */
    fun refresh(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<VaultSurfacesWorker>().build())
        }
    }

    /** Catalog vaults, pinned first (then most recently synced), with their names from Vault preferences. */
    fun items(context: Context, withStatus: Boolean = true): List<VaultSurfaceItem> {
        val targets = runCatching { PullAllCatalog(context).snapshot() }.getOrDefault(emptyList())
        val preferences = runCatching { EntryPointAccessors.fromApplication(context.applicationContext, VaultPreferencesEntryPoint::class.java).vaultPreferences() }.getOrNull()
        return targets.map { target ->
            val preference = preferences?.current(target.path)
            VaultSurfaceItem(target.path, preference?.displayName ?: target.name, preference?.pinned == true,
                if (withStatus) VaultStatusReader.read(target.path) else null)
        }.sortedWith(compareByDescending<VaultSurfaceItem> { it.pinned }.thenByDescending { it.status?.lastSync ?: 0L })
    }

    internal fun render(context: Context) {
        val items = items(context)
        val manager = AppWidgetManager.getInstance(context)
        val now = System.currentTimeMillis()
        manager.getAppWidgetIds(ComponentName(context, VaultListWidget::class.java)).forEach { id ->
            manager.updateAppWidget(id, listViews(context, items, now))
        }
        manager.getAppWidgetIds(ComponentName(context, VaultWidget::class.java)).forEach { id ->
            manager.updateAppWidget(id, vaultViews(context, id, items.firstOrNull { it.path == vaultFor(context, id) }, now))
        }
        VaultLauncherShortcuts.publish(context, items)
    }

    /** "now", "2m ago", "3h ago", "4d ago". */
    fun ago(context: Context, time: Long?, now: Long = System.currentTimeMillis()): String? {
        if (time == null || time <= 0) return null
        val minutes = ((now - time).coerceAtLeast(0) / 60_000).toInt()
        return when {
            minutes < 1 -> context.getString(R.string.widget_m3_now)
            minutes < 60 -> context.getString(R.string.widget_m3_minutes_ago, minutes)
            minutes < 60 * 24 -> context.getString(R.string.widget_m3_hours_ago, minutes / 60)
            else -> context.getString(R.string.widget_m3_days_ago, minutes / (60 * 24))
        }
    }

    // ─── List widget ───────────────────────────────────────────────────────

    private val rows = listOf(
        intArrayOf(R.id.vault_list_row_1, R.id.vault_list_name_1, R.id.vault_list_ok_1, R.id.vault_list_ahead_1, R.id.vault_list_behind_1),
        intArrayOf(R.id.vault_list_row_2, R.id.vault_list_name_2, R.id.vault_list_ok_2, R.id.vault_list_ahead_2, R.id.vault_list_behind_2),
        intArrayOf(R.id.vault_list_row_3, R.id.vault_list_name_3, R.id.vault_list_ok_3, R.id.vault_list_ahead_3, R.id.vault_list_behind_3),
        intArrayOf(R.id.vault_list_row_4, R.id.vault_list_name_4, R.id.vault_list_ok_4, R.id.vault_list_ahead_4, R.id.vault_list_behind_4),
    )

    fun listViews(context: Context, items: List<VaultSurfaceItem>, now: Long = System.currentTimeMillis()) =
        RemoteViews(context.packageName, R.layout.vault_list_widget).apply {
            setTextViewText(R.id.vault_list_updated, ago(context, items.mapNotNull { it.status?.lastSync }.maxOrNull(), now).orEmpty())
            setViewVisibility(R.id.vault_list_empty, if (items.isEmpty()) View.VISIBLE else View.GONE)
            rows.forEachIndexed { index, ids ->
                val item = items.getOrNull(index)
                setViewVisibility(ids[0], if (item == null) View.GONE else View.VISIBLE)
                if (item == null) return@forEachIndexed
                setTextViewText(ids[1], item.name)
                val status = item.status
                val ahead = status?.toPush ?: 0
                val behind = status?.behind ?: 0
                setViewVisibility(ids[2], if (status != null && ahead == 0 && behind == 0) View.VISIBLE else View.GONE)
                setViewVisibility(ids[3], if (ahead > 0) View.VISIBLE else View.GONE)
                setViewVisibility(ids[4], if (ahead == 0 && behind > 0) View.VISIBLE else View.GONE)
                setTextViewText(ids[3], "↑$ahead")
                setTextViewText(ids[4], "↓$behind")
                setOnClickPendingIntent(ids[0], VaultShortcutActivity.pendingIntent(context, VaultShortcutActivity.OPEN, item.path))
            }
            setOnClickPendingIntent(R.id.vault_list_pull, PullAllAction.pendingIntent(context, "widget"))
            setOnClickPendingIntent(R.id.vault_list_header, VaultShortcutActivity.pendingIntent(context, VaultShortcutActivity.HOME, null))
        }

    // ─── Single-vault widget ───────────────────────────────────────────────

    fun vaultViews(context: Context, widgetId: Int, item: VaultSurfaceItem?, now: Long = System.currentTimeMillis()) =
        RemoteViews(context.packageName, R.layout.vault_widget).apply {
            if (item == null) {
                setTextViewText(R.id.vault_widget_name, context.getString(R.string.widget_m3_choose_vault))
                setTextViewText(R.id.vault_widget_count, "–")
                setTextViewText(R.id.vault_widget_label, context.getString(R.string.widget_m3_vault_missing))
                setTextViewText(R.id.vault_widget_meta, "")
                setOnClickPendingIntent(R.id.vault_widget_root, PendingIntent.getActivity(context, 5000 + widgetId,
                    Intent(context, VaultWidgetConfigureActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        .setData(Uri.parse("gitsync-internal://vault-widget/$widgetId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                return@apply
            }
            val count = item.status?.toPush ?: 0
            setTextViewText(R.id.vault_widget_name, item.name)
            val phrase = countedPhrase(context.resources.getQuantityString(R.plurals.widget_m3_changes_to_push, count),
                context.getString(R.string.l10n_count, count))
            setTextViewText(R.id.vault_widget_count, phrase.value)
            setTextViewText(R.id.vault_widget_label, phrase.label)
            setContentDescription(R.id.vault_widget_root, context.getString(R.string.l10n_a11y_pair, item.name, phrase.text))
            setTextViewText(R.id.vault_widget_meta, listOfNotNull(item.status?.branch, ago(context, item.status?.lastSync, now)).joinToString(" · "))
            setOnClickPendingIntent(R.id.vault_widget_root, VaultShortcutActivity.pendingIntent(context, VaultShortcutActivity.OPEN, item.path))
        }

    fun vaultFor(context: Context, widgetId: Int): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("widget_$widgetId", null)
    fun assign(context: Context, widgetId: Int, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("widget_$widgetId", path).apply()
    }
    fun forget(context: Context, widgetIds: IntArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { widgetIds.forEach { remove("widget_$it") } }.apply()
    }
}

class VaultSurfacesWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try { VaultWidgets.render(applicationContext); Result.success() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.failure() }
}

/** Home-screen list: wordmark, last update, up to four vaults with status badges and "Pull all repositories". */
class VaultListWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = VaultWidgets.refresh(context)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_LOCALE_CHANGED) VaultWidgets.refresh(context)
    }
}

/** Dark single-vault widget: name, the count waiting to push, "main · 2m ago". Configured per vault. */
class VaultWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = VaultWidgets.refresh(context)
    override fun onDeleted(context: Context, ids: IntArray) = VaultWidgets.forget(context, ids)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_LOCALE_CHANGED) VaultWidgets.refresh(context)
    }
}

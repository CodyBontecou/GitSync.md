package com.bontecou.syncmd.automation

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.bontecou.syncmd.MainActivity
import com.bontecou.syncmd.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Review navigation only. No execution payload crosses MainActivity or survives process loss. */
object PullAllRequests {
    private val mutable = MutableStateFlow<String?>(null)
    val pending = mutable.asStateFlow()
    internal fun request() { mutable.value = UUID.randomUUID().toString() }
    fun consume(id: String): Boolean = mutable.compareAndSet(id, null)
}

class PullAllActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            try {
                val runs = PullAllBackgroundRuns(this)
                val run = runs.reserve(intent.getStringExtra(PullAllAction.SOURCE))
                try { startForegroundService(Intent(this, PullAllRunService::class.java).putExtra("run_id", run.id)) }
                catch (failure: Exception) { runs.finish(run.id, BackgroundPullState.FAILED); throw failure }
            } catch (_: Exception) {
                android.widget.Toast.makeText(this, if (PullAllBackgroundRuns.isActive()) R.string.pull_all_background_busy else R.string.pull_all_failed,
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}

/** Result review is a separate private Activity PendingIntent and never requests another run. */
class PullAllReviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) PullAllRequests.request()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}

object PullAllAction {
    const val SOURCE = "source"
    fun intent(context: Context, source: String = "widget") = Intent(context, PullAllActionActivity::class.java)
        .putExtra(SOURCE, source).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun pendingIntent(context: Context, source: String = "widget"): PendingIntent = PendingIntent.getActivity(context,
        when (source) { "tile" -> 411; "shortcut" -> 412; else -> 410 },
        intent(context, source), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class PullAllWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_LOCALE_CHANGED) {
            val manager = AppWidgetManager.getInstance(context)
            onUpdate(context, manager, manager.getAppWidgetIds(ComponentName(context, PullAllWidget::class.java)))
        }
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> manager.updateAppWidget(id, responsiveViews(context, manager.getAppWidgetOptions(id))) }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        manager.updateAppWidget(id, responsiveViews(context, newOptions))
    }
    companion object {
        /** New hosts choose layouts without waking the app; older hosts use their conservative bounds. */
        fun responsiveViews(context: Context, options: Bundle): RemoteViews {
            if (Build.VERSION.SDK_INT >= 31) return RemoteViews(mapOf(
                android.util.SizeF(180f, 112f) to views(context),
                android.util.SizeF(240f, 280f) to views(context, expanded = true),
                android.util.SizeF(360f, 192f) to views(context, expanded = true)))
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            return views(context, expanded = (width >= 240 && height >= 280) || (width >= 360 && height >= 192))
        }
        fun views(context: Context, expanded: Boolean = false) = RemoteViews(context.packageName,
            if (expanded) R.layout.pull_all_widget_expanded else R.layout.pull_all_widget).apply {
            fun localized(id: Int) = android.text.SpannableString(context.getString(id)).apply {
                setSpan(android.text.style.LocaleSpan(context.resources.configuration.locales), 0, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setTextViewText(R.id.pull_all_widget_label, localized(R.string.pull_all_title))
            setTextViewText(R.id.pull_all_widget_subtitle, localized(R.string.pull_all_widget_subtitle))
            setContentDescription(R.id.pull_all_widget_button, localized(R.string.pull_all_widget_description))
            setInt(R.id.pull_all_widget_button, "setLayoutDirection", context.resources.configuration.layoutDirection)
            setOnClickPendingIntent(R.id.pull_all_widget_button, PullAllAction.pendingIntent(context))
        }
    }
}

/** Quick Settings "Pull all": subtitle "N vaults"; a tap runs Pull all once in the background. */
class PullAllTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val vaults = runCatching { PullAllCatalog(this).snapshot().size }.getOrNull()
        qsTile?.apply {
            state = if (PullAllBackgroundRuns.isActive()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.tile_m3_pull_all)
            icon = android.graphics.drawable.Icon.createWithResource(this@PullAllTileService, R.drawable.ic_m3_arrow_downward)
            if (Build.VERSION.SDK_INT >= 29) subtitle = vaults?.let { resources.getQuantityString(R.plurals.pullall_m3_vaults, it, it) }
            updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        unlockAndRun {
            androidx.core.service.quicksettings.TileServiceCompat.startActivityAndCollapse(this,
                androidx.core.service.quicksettings.PendingIntentActivityWrapper(this, 411,
                    PullAllAction.intent(this, "tile"), PendingIntent.FLAG_UPDATE_CURRENT, false))
        }
    }
}

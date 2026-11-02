package com.bontecou.syncmd.automation

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.Configuration
import androidx.work.*
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.automation.RepositoryAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal fun repositoryShortcutLabels(context: Context, record: RepositoryShortcutRecord): Pair<String, String> {
    val action = context.getString(when (record.action) {
        RepositoryAction.STATUS -> R.string.repository_actions_status
        RepositoryAction.PULL -> R.string.repository_actions_pull
        RepositoryAction.PUSH -> R.string.repository_actions_push
        RepositoryAction.SYNC -> R.string.repository_actions_sync
    })
    val label = "$action: ${record.plan.target.name}"
    fun clip(max: Int) = label.substring(0, label.offsetByCodePoints(0, minOf(label.codePointCount(0, label.length), max)))
    return clip(24) to clip(128)
}

internal interface ShortcutLabelPlatform {
    fun pinned(): List<ShortcutInfo>
    fun update(labels: List<ShortcutInfo>): Boolean
}

internal class AndroidShortcutLabelPlatform(context: Context) : ShortcutLabelPlatform {
    private val manager = context.getSystemService(ShortcutManager::class.java)
    override fun pinned(): List<ShortcutInfo> = manager.pinnedShortcuts
    override fun update(labels: List<ShortcutInfo>) = manager.updateShortcuts(labels)
}

/** Metadata only: never enables a shortcut, rewrites its intent or invokes repository operations. */
internal object RepositoryShortcutLabels {
    enum class Outcome { CURRENT, UPDATED, RETRY, UNREADABLE }
    private val mutex = Mutex()

    suspend fun refresh(context: Context, platform: ShortcutLabelPlatform = AndroidShortcutLabelPlatform(context)): Outcome =
        withContext(Dispatchers.IO) { mutex.withLock {
            if (context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == false)
                return@withLock Outcome.RETRY
            // Snapshot after acquiring the lock so a queued refresh reads the latest app locale.
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration))
            val records = try { RepositoryShortcutStore(context).records().associateBy { it.id } }
                catch (_: Exception) { return@withLock Outcome.UNREADABLE }
            try {
                val activity = ComponentName(context, RepositoryShortcutActivity::class.java)
                val patches = platform.pinned().mapNotNull { pin ->
                    val record = records[pin.id] ?: return@mapNotNull null
                    if (pin.isImmutable || pin.intent?.component != activity ||
                        pin.intent?.getStringExtra("shortcut_id") != pin.id) return@mapNotNull null
                    val (short, long) = repositoryShortcutLabels(localized, record)
                    // Only an app-revoked pin owns this message; preserve platform-disabled explanations.
                    val appDisabled = !pin.isEnabled && (android.os.Build.VERSION.SDK_INT < 28 ||
                        pin.disabledReason == ShortcutInfo.DISABLED_REASON_BY_APP)
                    val disabledMessage = if (!record.enabled && appDisabled)
                        localized.getString(R.string.repository_shortcut_revoked) else null
                    if (pin.shortLabel?.toString() == short && pin.longLabel?.toString() == long &&
                        (disabledMessage == null || pin.disabledMessage?.toString() == disabledMessage)) return@mapNotNull null
                    // Omitted fields preserve launcher intent stacks, icon, activity, rank and enabled state.
                    ShortcutInfo.Builder(context, pin.id).setShortLabel(short).setLongLabel(long).apply {
                        if (disabledMessage != null) setDisabledMessage(disabledMessage)
                    }.build()
                }
                when {
                    patches.isEmpty() -> Outcome.CURRENT
                    platform.update(patches) -> Outcome.UPDATED
                    else -> Outcome.RETRY
                }
            } catch (_: IllegalStateException) { Outcome.RETRY } // User is locked; wait for a later attempt.
        } }
}

object AutomationLocaleJobs {
    const val NAME = "automation-locale-metadata"
    fun enqueue(context: Context, changed: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(NAME,
            if (changed) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AutomationLocaleWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}

class AutomationLocaleWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val outcome = RepositoryShortcutLabels.refresh(applicationContext)
        // Widgets also use app resources; per-app locale changes need the same refresh as system changes.
        val manager = android.appwidget.AppWidgetManager.getInstance(applicationContext)
        val ids = manager.getAppWidgetIds(ComponentName(applicationContext, PullAllWidget::class.java))
        if (ids.isNotEmpty()) PullAllWidget().onUpdate(applicationContext, manager, ids)
        return when (outcome) {
            RepositoryShortcutLabels.Outcome.RETRY -> Result.retry()
            RepositoryShortcutLabels.Outcome.UNREADABLE -> Result.failure()
            else -> Result.success()
        }
    }
}

class AutomationLocaleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_LOCALE_CHANGED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            AutomationLocaleJobs.enqueue(context, changed = true)
    }
}

package com.bontecou.syncmd.automation

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bontecou.syncmd.R
import com.bontecou.syncmd.domain.repository.PullRepository
import com.bontecou.syncmd.services.automation.PullAllOutcome
import com.bontecou.syncmd.services.automation.PullAllRunner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/** Explicit widget/tile taps only: no sticky starts, boot work, retries, or process-restored execution. */
@AndroidEntryPoint
class PullAllRunService : Service() {
    @Inject lateinit var pull: PullRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runs by lazy { PullAllBackgroundRuns(this) }
    private var active: String? = null
    private var job: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.pull_all_background_title), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            val requested = intent.getStringExtra("run_id")
            if (requested == active) job?.cancel()
            if (active == null) {
                runCatching { runs.latest()?.takeIf { it.id == requested && it.state == BackgroundPullState.QUEUED }
                    ?.let { runs.finish(it.id, BackgroundPullState.INTERRUPTED) } }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra("run_id")
        if (id == null) { if (active == null) stopSelf(); return START_NOT_STICKY }
        if (active != null) return START_NOT_STICKY
        try {
            val notice = notification(id, true)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notice)
            if (!runs.claim(id)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        } catch (_: Exception) { runCatching { runs.finish(id, BackgroundPullState.FAILED) }; stopSelf(); return START_NOT_STICKY }
        active = id
        job = scope.launch {
            try {
                val catalog = PullAllCatalog(this@PullAllRunService)
                val targets = catalog.snapshot()
                runs.targets(id, targets.size)
                PullAllRunner(pull, catalog::requireCurrent).run(targets, onResult = { result -> runs.result(id, result) })
                runs.finish(id, BackgroundPullState.COMPLETE, retainOwnership = true)
            } catch (_: CancellationException) { runCatching { runs.finish(id, BackgroundPullState.INTERRUPTED, retainOwnership = true) }
            } catch (_: Exception) { runCatching { runs.finish(id, BackgroundPullState.FAILED, retainOwnership = true) }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    active = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    runCatching { if (runs.latest()?.id == id) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(id, false)) }
                    stopSelf()
                }
            }
        }
        job!!.invokeOnCompletion { runs.release(id) }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopSelf() }
    override fun onDestroy() {
        active?.let { runCatching { runs.finish(it, BackgroundPullState.INTERRUPTED, retainOwnership = true) } }
        scope.cancel(); super.onDestroy()
    }
    private fun notification(id: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 420, Intent(this, PullAllReviewActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val run = runCatching { runs.latest()?.takeIf { it.id == id } }.getOrNull()
        val attention = run?.counts.orEmpty().any { (outcome, count) -> count > 0 && outcome in setOf(PullAllOutcome.BLOCKED, PullAllOutcome.ATTENTION, PullAllOutcome.FAILED) }
        val text = getString(when {
            ongoing -> R.string.pull_all_background_running
            run?.state != BackgroundPullState.COMPLETE || attention -> R.string.pull_all_background_attention
            else -> R.string.pull_all_background_complete
        })
        val details = if (!ongoing && run != null) listOf(
            text, getString(R.string.pull_all_progress, run.checked, run.total),
            getString(R.string.pull_all_background_counts, run.counts[PullAllOutcome.UPDATED] ?: 0,
                run.counts[PullAllOutcome.UP_TO_DATE] ?: 0, run.counts[PullAllOutcome.ATTENTION] ?: 0,
                run.counts[PullAllOutcome.BLOCKED] ?: 0, run.counts[PullAllOutcome.FAILED] ?: 0)
        ).plus(run.attention.map { it.name }).joinToString("\n") else text
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_pull_all)
            .setContentTitle(getString(R.string.pull_all_background_title)).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details)).setContentIntent(open)
            .setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(ongoing).setAutoCancel(!ongoing)
        if (ongoing) builder.addAction(0, getString(R.string.pull_all_stop), stopPendingIntent(this, id))
        return builder.build()
    }
    companion object {
        private const val CHANNEL = "pull-all-actions"; const val NOTIFICATION = 422
        private const val STOP = "md.gitsync.md.STOP_PULL_ALL"
        fun stopIntent(context: Context, id: String): Intent {
            require(java.util.UUID.fromString(id).toString() == id)
            return Intent(context, PullAllRunService::class.java).setAction(STOP)
                .setData(android.net.Uri.parse("gitsync-internal://pull-all-stop/$id")).putExtra("run_id", id)
        }
        fun stopPendingIntent(context: Context, id: String): PendingIntent = PendingIntent.getService(context, 421,
            stopIntent(context, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

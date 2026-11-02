package com.bontecou.syncmd.automation

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bontecou.syncmd.MainActivity
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.automation.RepositoryActionOutcome
import com.bontecou.syncmd.services.automation.RepositoryCallbackProtocol
import com.bontecou.syncmd.services.automation.CallbackProblem
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/** User-invoked launcher actions and explicitly authorized background callbacks only. Never sticky, redelivered, scheduled or automatically retried. */
@AndroidEntryPoint
class RepositoryShortcutRunService : Service() {
    @Inject lateinit var shortcuts: RepositoryShortcutService
    @Inject lateinit var callbacks: RepositoryCallbackGrantService
    private var callbackRun = false
    private var callbackReturnAvailable = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runs by lazy { RepositoryShortcutRuns(this) }
    private var job: Job? = null
    @Volatile private var activeRun: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.shortcut_background_channel), NotificationManager.IMPORTANCE_LOW))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CALLBACK_CHANNEL, getString(R.string.callback_background_channel), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            if (intent.getStringExtra("run_id") == activeRun) job?.cancel()
            if (activeRun == null) stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra("run_id")
        val shortcut = intent?.getStringExtra("shortcut_id")
        if (id == null || shortcut == null) { if (activeRun == null) stopSelf(); return START_NOT_STICKY }
        if (activeRun != null) {
            if (id != activeRun) {
                if (intent.action == CALLBACK) BackgroundCallbackDelivery.rejectPending(this, id, shortcut, CallbackProblem.BUSY)
                else runCatching { runs.finish(id, ShortcutRunState.ATTENTION) }
            }
            return START_NOT_STICKY
        }
        callbackRun = intent.action == CALLBACK
        callbackReturnAvailable = false
        val callback = if (callbackRun) BackgroundCallbackDelivery.take(id, shortcut) else null
        if (callbackRun && callback == null) { runCatching { runs.finish(id, ShortcutRunState.INTERRUPTED) }; stopSelf(); return START_NOT_STICKY }
        try {
            val notice = notification(R.string.shortcut_background_running, true, id)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notice)
            if (!runs.claim(id, shortcut)) {
                if (callback != null) BackgroundCallbackDelivery.reject(this, id, callback, CallbackProblem.FAILED)
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
            }
        } catch (_: Exception) {
            if (callback != null) BackgroundCallbackDelivery.reject(this, id, callback, CallbackProblem.FAILED)
            else runCatching { runs.finish(id, ShortcutRunState.ATTENTION) }
            stopSelf(); return START_NOT_STICKY
        }
        activeRun = id
        job = scope.launch {
            var notice = if (callbackRun) R.string.callback_background_attention else R.string.shortcut_background_attention
            try {
                val result = withTimeout(120_000) {
                    if (callback != null) callbacks.run(callback.token, callback.grant) {} else {
                        require(shortcuts.store.get(shortcut).background) { "Background execution was not authorized" }
                        shortcuts.execute(shortcut).result
                    }
                }
                val good = result.outcome !in setOf(RepositoryActionOutcome.BLOCKED, RepositoryActionOutcome.FAILED,
                    RepositoryActionOutcome.ATTENTION, RepositoryActionOutcome.LOCAL_COMMIT_SAVED)
                runs.finish(id, if (good) ShortcutRunState.COMPLETE else ShortcutRunState.ATTENTION, result.outcome, retainOwnership = true)
                if (callback != null && callbacks.canReturn(callback.token, callback.grant)) callbackReturnAvailable = BackgroundCallbackDelivery.complete(this@RepositoryShortcutRunService, id, callback,
                    RepositoryCallbackProtocol.response(callback.grant.request, result))
                if (good) notice = if (callbackRun) R.string.callback_background_complete else R.string.shortcut_background_complete
            } catch (_: CancellationException) {
                runCatching { runs.finish(id, ShortcutRunState.INTERRUPTED, retainOwnership = true) }
                if (callback != null && callbacks.canReturn(callback.token, callback.grant)) callbackReturnAvailable = BackgroundCallbackDelivery.complete(this@RepositoryShortcutRunService, id, callback,
                    RepositoryCallbackProtocol.failure(callback.grant.request, CallbackProblem.INTERRUPTED))
            } catch (_: Exception) {
                runCatching { runs.finish(id, ShortcutRunState.ATTENTION, retainOwnership = true) }
                if (callback != null && callbacks.canReturn(callback.token, callback.grant)) callbackReturnAvailable = BackgroundCallbackDelivery.complete(this@RepositoryShortcutRunService, id, callback,
                    RepositoryCallbackProtocol.failure(callback.grant.request, CallbackProblem.FAILED))
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    activeRun = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    // With notifications denied, the saved result remains available in the app.
                    runCatching {
                        val manager = getSystemService(NotificationManager::class.java)
                        if (callbackRun && callbackReturnAvailable) manager.notify(BackgroundCallbackDelivery.notificationTag(id), 0, notification(notice, false, id))
                        else manager.notify(NOTIFICATION, notification(notice, false, id))
                    }
                    stopSelf()
                }
            }
        }
        job!!.invokeOnCompletion { runs.release(id) }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopSelf() }
    override fun onDestroy() {
        activeRun?.let { id -> runCatching { runs.finish(id, ShortcutRunState.INTERRUPTED, retainOwnership = true) } }
        scope.cancel(); super.onDestroy()
    }
    private fun notification(message: Int, ongoing: Boolean, id: String): Notification {
        val returnTarget = callbackRun && !ongoing && callbackReturnAvailable
        val target = if (returnTarget) Intent(this, BackgroundCallbackResultActivity::class.java).setAction("md.gitsync.md.CALLBACK_RESULT.$id").putExtra("run_id", id)
            else Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        val open = PendingIntent.getActivity(this, if (returnTarget) id.hashCode() else 590, target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, if (callbackRun) CALLBACK_CHANNEL else CHANNEL).setSmallIcon(R.drawable.ic_pull_all)
            .setContentTitle(getString(if (callbackRun) R.string.callback_background_channel else R.string.shortcut_background_channel))
            .setContentText(getString(if (callbackRun && ongoing) R.string.callback_background_running else if (callbackRun && !ongoing && message == R.string.callback_background_complete && !callbackReturnAvailable) R.string.callback_background_complete_no_return else message))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(if (callbackRun && ongoing) R.string.callback_background_running else if (callbackRun && !ongoing && message == R.string.callback_background_complete && !callbackReturnAvailable) R.string.callback_background_complete_no_return else message))).setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(ongoing).setAutoCancel(!ongoing)
        if (ongoing) {
            val cancel = PendingIntent.getService(this, id.hashCode(), stopIntent(this, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, getString(R.string.repository_actions_cancel), cancel)
        }
        return builder.build()
    }
    companion object { const val CALLBACK = "md.gitsync.md.RUN_CALLBACK"; private const val CALLBACK_CHANNEL = "repository-callback-actions"; private const val CHANNEL = "repository-shortcut-actions"; private const val NOTIFICATION = 591; private const val STOP = "md.gitsync.md.STOP_SHORTCUT"
        fun stopIntent(context: android.content.Context, id: String): Intent {
            require(java.util.UUID.fromString(id).toString() == id)
            return Intent(context, RepositoryShortcutRunService::class.java).setAction(STOP)
                .setData(android.net.Uri.parse("gitsync-internal://shortcut-stop/$id")).putExtra("run_id", id)
        }
    }
}

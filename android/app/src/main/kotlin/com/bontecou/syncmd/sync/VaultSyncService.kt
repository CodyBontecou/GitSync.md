package com.bontecou.syncmd.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bontecou.syncmd.MainActivity
import com.bontecou.syncmd.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps a single-vault sync alive while the user leaves the screen and shows its progress
 * (operation, percent, Cancel) in a notification. The work itself runs in [VaultSyncRunner];
 * this service only holds the foreground slot and stops itself when nothing is running.
 */
@AndroidEntryPoint
class VaultSyncService : Service() {
    @Inject lateinit var runner: VaultSyncRunner
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.vault_m3_notify_channel), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            intent.getStringExtra(EXTRA_PATH)?.let(runner::cancel)
            if (!foreground) stopSelf()
            return START_NOT_STICKY
        }
        val active = runner.runs.value.values.firstOrNull { it.running }
        try {
            val notice = notification(active)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notice)
            foreground = true
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (watcher == null) watcher = scope.launch {
            // StateFlow is conflated: a collector that pauses sees only the latest value.
            runner.runs.collect { runs ->
                val running = runs.values.firstOrNull { it.running }
                if (running == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    foreground = false
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(running))
                    // Transfer callbacks arrive per object; one notification update a second is plenty.
                    delay(1000)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        runner.runs.value.values.filter { it.running }.forEach { runner.cancel(it.path) }
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun notification(run: VaultSyncRun?): Notification {
        val open = PendingIntent.getActivity(this, 430, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val name = run?.path?.substringAfterLast('/').orEmpty()
        val operation = getString(run?.let(::operationLabel) ?: R.string.vault_m3_preparing)
        val transfer = run?.transfer
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_m3_sync)
            .setContentTitle(if (name.isNotEmpty()) getString(R.string.vault_m3_notify_title, name, operation) else operation)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (transfer != null && transfer.total > 0) {
            builder.setProgress(transfer.total, transfer.done.coerceAtMost(transfer.total), false)
            builder.setContentText("${transfer.task} · ${(transfer.done * 100L / transfer.total).coerceIn(0, 100)}%")
        } else builder.setProgress(0, 0, true)
        if (run != null) builder.addAction(0, getString(R.string.vault_m3_cancel), cancelIntent(this, run.path))
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "vault-sync"
        private const val NOTIFICATION = 432
        private const val CANCEL = "com.bontecou.syncmd.CANCEL_VAULT_SYNC"
        private const val EXTRA_PATH = "path"

        fun cancelIntent(context: Context, path: String): PendingIntent = PendingIntent.getService(context, 431,
            Intent(context, VaultSyncService::class.java).setAction(CANCEL).putExtra(EXTRA_PATH, path),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** The hero's named operation for the step that is running. */
fun operationLabel(run: VaultSyncRun): Int = when (run.current?.id) {
    SyncStepId.COMMIT -> R.string.vault_m3_committing
    SyncStepId.CHECK -> R.string.vault_m3_checking
    SyncStepId.DOWNLOAD, SyncStepId.MERGE, SyncStepId.UPDATE -> R.string.vault_m3_pulling
    SyncStepId.BRANCH, SyncStepId.LFS, SyncStepId.UPLOAD -> R.string.vault_m3_pushing
    null -> if (run.kind == VaultSyncKind.PULL) R.string.vault_m3_pulling else R.string.vault_m3_pushing
}

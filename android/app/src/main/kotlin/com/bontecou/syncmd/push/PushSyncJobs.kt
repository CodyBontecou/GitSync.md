package com.bontecou.syncmd.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.bontecou.syncmd.MainActivity
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.push.PushSyncProtocol
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@EntryPoint @InstallIn(SingletonComponent::class)
interface PushSyncEntryPoint { fun backend(): PushSyncBackend }
private fun backend(context: Context) = EntryPointAccessors.fromApplication(context.applicationContext, PushSyncEntryPoint::class.java).backend()

object PushSyncJobs {
    private const val EVENTS = "push-sync-events"
    fun maintain(context: Context) {
        val state = PushStateStore(context).read(); if (!state.enabled && !state.unregisterPending) return
        WorkManager.getInstance(context).enqueueUniqueWork("push-sync-maintenance", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<PushMaintenanceWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun cancelEvents(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(EVENTS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.notification.channelId == "repository-updates" }.forEach { manager.cancel(it.tag, it.id) }
    }
    fun receive(context: Context, data: Map<String, String>) {
        val hint = runCatching { PushSyncProtocol.hint(data) }.getOrNull() ?: return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (context.getSystemService(NotificationManager::class.java).getNotificationChannel("repository-updates")?.importance == NotificationManager.IMPORTANCE_NONE) return
        val event = PushStateStore(context).receive(hint) ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("repository-updates", context.getString(R.string.push_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, PushSyncActionActivity::class.java).setData(Uri.parse("syncmd-internal://push/${event.id}"))
            .putExtra("event", event.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(context, event.id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, "repository-updates").setSmallIcon(R.drawable.ic_pull_all)
            .setContentTitle(hint.repository).setContentText(context.getString(R.string.push_notification, hint.branch)).setContentIntent(pending)
            .setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { manager.notify(event.id, 1, notification) } catch (_: SecurityException) { return }
        WorkManager.getInstance(context).enqueueUniqueWork("push-sync-${event.id}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PushEventWorker>().setInputData(workDataOf("event" to event.id)).addTag(EVENTS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}
class GitSyncMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        runCatching { backend(this).tokenChanged(token); PushSyncJobs.maintain(this) }
    }
    override fun onMessageReceived(message: RemoteMessage) { runCatching { PushSyncJobs.receive(this, message.data) } }
}
class PushMaintenanceWorker @JvmOverloads constructor(context: Context, parameters: WorkerParameters, private val overrideBackend: PushSyncBackend? = null) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try { PushRuntime.ready.await(); (overrideBackend ?: backend(applicationContext)).maintain(); Result.success() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
}
class PushEventWorker @JvmOverloads constructor(context: Context, parameters: WorkerParameters, private val overrideBackend: PushSyncBackend? = null) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        PushRuntime.ready.await()
        val id = inputData.getString("event") ?: error("Missing event")
        val service = overrideBackend ?: backend(applicationContext)
        if (service.store.event(id) != null) {
            val before = BackgroundPullNotifications.heads(applicationContext)
            val results = service.reconcile(id)
            runCatching { BackgroundPullNotifications.afterPull(applicationContext, id, results, before) }
        }
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
}
data class PushTap(val event: String, val id: String = java.util.UUID.randomUUID().toString())
object PushSyncRequests {
    private val taps = MutableStateFlow<PushTap?>(null); val pending = taps.asStateFlow()
    fun offer(id: String) { if (id.matches(Regex("[a-f0-9]{64}"))) taps.compareAndSet(null, PushTap(id)) }
    fun consume(tap: PushTap) = taps.compareAndSet(tap, null)
    fun clear() { taps.value = null }
}
object PushRuntime { val ready = kotlinx.coroutines.CompletableDeferred<Unit>() }
data class PushLinkCallback(val raw: String, val id: String = java.util.UUID.randomUUID().toString()) {
    override fun toString() = "PushLinkCallback(id=$id, callback=<redacted>)"
}
object PushLinkCallbacks {
    private val callbacks = MutableStateFlow<PushLinkCallback?>(null); val pending = callbacks.asStateFlow()
    fun offer(raw: String) { if (raw.length <= 4096) callbacks.compareAndSet(null, PushLinkCallback(raw)) }
    fun consume(value: PushLinkCallback) = callbacks.compareAndSet(value, null)
}
class PushSyncActionActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) intent.getStringExtra("event")?.let(PushSyncRequests::offer)
        intent.removeExtra("event"); intent.data = null
        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish()
    }
}

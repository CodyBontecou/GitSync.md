package com.bontecou.syncmd.push

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject

interface PushMessagingPlatform {
    fun configured(): Boolean
    fun notificationsAllowed(): Boolean
    suspend fun token(): String
    fun autoInit(enabled: Boolean)
}
class FirebasePushPlatform @Inject constructor(@ApplicationContext private val context: Context) : PushMessagingPlatform {
    override fun configured() = FirebaseApp.getApps(context).isNotEmpty()
    override fun notificationsAllowed() = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel("repository-updates")?.importance != android.app.NotificationManager.IMPORTANCE_NONE
    override fun autoInit(enabled: Boolean) { if (configured()) FirebaseMessaging.getInstance().isAutoInitEnabled = enabled }
    override suspend fun token(): String = suspendCancellableCoroutine { continuation ->
        if (!configured()) { continuation.resumeWithException(IllegalStateException("Push unavailable")); return@suspendCancellableCoroutine }
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (continuation.isActive) {
                if (task.isSuccessful) continuation.resume(task.result) else continuation.resumeWithException(IllegalStateException("Notification registration unavailable"))
            }
        }
    }
}

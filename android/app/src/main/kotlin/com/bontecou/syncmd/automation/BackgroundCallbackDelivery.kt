package com.bontecou.syncmd.automation

import android.app.Activity
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.bontecou.syncmd.MainActivity
import com.bontecou.syncmd.services.automation.CallbackProblem
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.automation.RepositoryCallbackProtocol
import com.bontecou.syncmd.services.automation.RepositoryCallbackResponse

/** Tokens and callback response data never enter service intents or durable execution receipts.
 * Each admitted invocation and notification return can be consumed once in this process only. */
object BackgroundCallbackDelivery {
    data class Request(val token: String, val grant: RepositoryCallbackGrant) {
        override fun toString() = "BackgroundCallbackRequest(<redacted>)"
    }
    data class Start(val id: String, val dispatched: Boolean)
    private data class Return(val request: Request, val response: RepositoryCallbackResponse)
    private val pending = LinkedHashMap<String, Request>()
    private val completed = LinkedHashMap<String, Return>()
    @Synchronized fun reserve(context: Context, token: String, grant: RepositoryCallbackGrant): ShortcutRun {
        require(grant.scope.background && !grant.automaticReturn)
        require(RepositoryCallbackGrantStore(context).authorized(token) == grant)
        require(pending.size < 64) { "Too many callback requests" }
        val run = RepositoryShortcutRuns(context).reserve(grant.scope.id)
        pending[run.id] = Request(token, grant)
        return run
    }
    /** The injectable platform dispatch boundary also covers unavailable/denied foreground-service starts. */
    fun start(context: Context, token: String, grant: RepositoryCallbackGrant,
        dispatch: (Intent) -> Unit = { checkNotNull(context.startForegroundService(it)); Unit }): Start {
        require(grant.scope.background && !grant.automaticReturn)
        val request = Request(token, grant)
        val run = try { reserve(context, token, grant) } catch (_: Exception) {
            val id = java.util.UUID.randomUUID().toString()
            publishFailure(context, id, request, CallbackProblem.FAILED)
            return Start(id, false)
        }
        try {
            dispatch(Intent(context, RepositoryShortcutRunService::class.java).setAction(RepositoryShortcutRunService.CALLBACK)
                .putExtra("run_id", run.id).putExtra("shortcut_id", grant.scope.id))
        } catch (_: Exception) {
            rejectPending(context, run.id, grant.scope.id, CallbackProblem.FAILED)
            return Start(run.id, false)
        }
        return Start(run.id, true)
    }
    fun rejectPending(context: Context, id: String, grantId: String, problem: CallbackProblem): Boolean {
        val request = take(id, grantId) ?: return false
        reject(context, id, request, problem)
        return true
    }
    fun reject(context: Context, id: String, request: Request, problem: CallbackProblem) {
        require(problem in setOf(CallbackProblem.BUSY, CallbackProblem.FAILED))
        runCatching { RepositoryShortcutRuns(context).finish(id, ShortcutRunState.ATTENTION, callbackProblem = problem) }
        publishFailure(context, id, request, problem)
    }
    private fun publishFailure(context: Context, id: String, request: Request, problem: CallbackProblem) {
        if (!request.grant.scope.background || request.grant.automaticReturn ||
            !runCatching { RepositoryCallbackGrantStore(context).authorized(request.token) == request.grant }.getOrDefault(false)) return
        val available = complete(context, id, request, RepositoryCallbackProtocol.failure(request.grant.request, problem))
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = "repository-callback-actions"
            manager.createNotificationChannel(NotificationChannel(channel, context.getString(R.string.callback_background_channel), NotificationManager.IMPORTANCE_LOW))
            val target = if (available) Intent(context, BackgroundCallbackResultActivity::class.java)
                .setAction("md.gitsync.md.CALLBACK_RESULT.$id").putExtra("run_id", id) else Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            val open = PendingIntent.getActivity(context, id.hashCode(), target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val text = context.getString(if (problem == CallbackProblem.BUSY) R.string.callback_background_busy else R.string.callback_background_attention)
            val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_pull_all)
                .setContentTitle(context.getString(R.string.callback_background_channel)).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setAutoCancel(true).build()
            manager.notify(notificationTag(id), 0, notification)
        }
    }
    @Synchronized fun take(id: String, grantId: String): Request? = pending.remove(id)?.takeIf { it.grant.scope.id == grantId }
    @Synchronized fun discard(id: String) { pending.remove(id); completed.remove(id) }
    @Synchronized fun complete(context: Context, id: String, request: Request, response: RepositoryCallbackResponse): Boolean {
        if (RepositoryCallbackProtocol.destination(request.grant.request, response) == null) return false
        completed[id] = Return(request, response)
        while (completed.size > 24) {
            val oldest = completed.keys.first(); completed.remove(oldest)
            runCatching { context.getSystemService(android.app.NotificationManager::class.java).cancel(notificationTag(oldest), 0) }
        }
        return true
    }
    fun notificationTag(id: String) = "authorized-callback-$id"
    /** Consume before external delivery. Revoked/changed authorization and process restoration never return data. */
    @Synchronized fun destination(context: Context, id: String): String? {
        val value = completed.remove(id) ?: return null
        runCatching { context.getSystemService(android.app.NotificationManager::class.java).cancel(notificationTag(id), 0) }
        val request = value.request
        if (runCatching { RepositoryCallbackGrantStore(context).authorized(request.token) == request.grant }.getOrDefault(false))
            return RepositoryCallbackProtocol.destination(request.grant.request, value.response)
        return null
    }
}

/** A direct immutable notification Activity PendingIntent satisfies Android's user-driven return requirement. */
class BackgroundCallbackResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("run_id"); intent.replaceExtras(null as Bundle?)
        val destination = if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 && id != null)
            BackgroundCallbackDelivery.destination(this, id) else null
        if (destination != null) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)).addCategory(Intent.CATEGORY_BROWSABLE)) }
            catch (_: Exception) { android.widget.Toast.makeText(this, R.string.callback_delivery_failed, android.widget.Toast.LENGTH_LONG).show() }
        } else android.widget.Toast.makeText(this, R.string.callback_background_return_expired, android.widget.Toast.LENGTH_LONG).show()
        finish()
    }
}

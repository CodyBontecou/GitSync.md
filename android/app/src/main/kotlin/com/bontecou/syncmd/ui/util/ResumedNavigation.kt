package com.bontecou.syncmd.ui.util

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleDestroyedException
import androidx.lifecycle.withStateAtLeast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume

/** Lifecycle observers can resume a coroutine synchronously while NavController iterates its back stack.
 * Leave that dispatch turn before navigation, then verify that the destination is still current/resumed.
 */
suspend fun NavController.awaitResumedNavigationEntry(): NavBackStackEntry = withContext(Dispatchers.Main.immediate) {
    while (true) {
        currentCoroutineContext().ensureActive()
        val entry = currentBackStackEntryFlow.first()
        val resumed = try { entry.lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) {}; true }
        catch (_: LifecycleDestroyedException) { currentCoroutineContext().ensureActive(); false }
        suspendCancellableCoroutine<Unit> { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val next = Runnable { if (continuation.isActive) continuation.resume(Unit) }
            if (!handler.post(next)) continuation.cancel(IllegalStateException("Main looper unavailable"))
            continuation.invokeOnCancellation { handler.removeCallbacks(next) }
        }
        if (resumed && currentBackStackEntry === entry && entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@withContext entry
    }
    @Suppress("UNREACHABLE_CODE") error("Unreachable")
}

/** A transition-time tap belongs to its source entry, not whichever screen later becomes current. */
internal fun NavController.onResumedSource(action: () -> Unit): Boolean {
    val controller = this
    val source = currentBackStackEntry ?: run { action(); return true }
    if (source.lifecycle.currentState == Lifecycle.State.DESTROYED) return false
    source.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
        val request = currentCoroutineContext().job
        // Destination listeners run synchronously, so even leave-and-return in one turn cancels the request.
        val changed = NavController.OnDestinationChangedListener { _, _, _ ->
            if (controller.currentBackStackEntry !== source) request.cancel()
        }
        controller.addOnDestinationChangedListener(changed)
        try {
            val resumed = controller.awaitResumedNavigationEntry()
            controller.removeOnDestinationChangedListener(changed)
            if (resumed === source && controller.currentBackStackEntry === source) action()
        } finally { controller.removeOnDestinationChangedListener(changed) }
    }
    return true
}

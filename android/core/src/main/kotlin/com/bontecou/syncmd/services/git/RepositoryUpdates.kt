package com.bontecou.syncmd.services.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File

/** Process-local invalidation, not sync history. No work or paths are retained without subscribers. */
object RepositoryUpdates {
    private val listeners = mutableMapOf<String, MutableSet<Channel<Unit>>>()

    /** Register before the initial refresh, so a write during that read queues another refresh.
     * Each subscriber has one conflated pending signal; a slow reader cannot lose the final change.
     */
    fun observe(path: String): Flow<Unit> = flow {
        val canonical = withContext(Dispatchers.IO) { File(path).canonicalPath }
        val pending = Channel<Unit>(Channel.CONFLATED)
        synchronized(listeners) { listeners.getOrPut(canonical) { mutableSetOf() }.add(pending) }
        try {
            emit(Unit)
            for (signal in pending) emit(signal)
        } finally {
            synchronized(listeners) {
                listeners[canonical]?.let { channels ->
                    channels.remove(pending)
                    if (channels.isEmpty()) listeners.remove(canonical)
                }
            }
            pending.close()
        }
    }

    internal fun changed(path: String) {
        // Optional display invalidation cannot change the outcome of a completed Git transaction.
        runCatching {
            val canonical = File(path).canonicalPath
            synchronized(listeners) { listeners[canonical]?.forEach { it.trySend(Unit) } }
        }
    }

    internal fun subscriptionCount(): Int = synchronized(listeners) { listeners.values.sumOf { it.size } }
}

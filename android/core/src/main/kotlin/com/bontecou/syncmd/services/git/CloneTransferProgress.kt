package com.bontecou.syncmd.services.git

import org.eclipse.jgit.lib.ProgressMonitor
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** What a clone or push has done so far, as JGit reports it. */
data class TransferProgressUpdate(
    /** JGit's task title ("Receiving objects", "Resolving deltas", "Checking out files", "Writing objects"). */
    val task: String,
    val done: Int,
    /** 0 when the task's size is unknown. */
    val total: Int,
    /** Bytes written into the destination so far (clones measure their staging folder; null when not measured). */
    val bytesOnDisk: Long? = null,
) {
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    val phase: CloneTransferPhase get() = when {
        task.startsWith("Receiving", true) || task.startsWith("Counting", true) || task.startsWith("Compressing", true) -> CloneTransferPhase.RECEIVING
        task.startsWith("Resolving", true) -> CloneTransferPhase.RESOLVING
        task.startsWith("Checking out", true) || task.startsWith("Updating", true) -> CloneTransferPhase.CHECKOUT
        task.startsWith("Writing", true) -> CloneTransferPhase.SENDING
        else -> CloneTransferPhase.OTHER
    }
}

enum class CloneTransferPhase { RECEIVING, RESOLVING, CHECKOUT, SENDING, OTHER }

/**
 * A coroutine-context sink for transfer progress. A caller adds it with `withContext(TransferProgress { … })`;
 * the clone and publication push read it from their coroutine context, so no signatures change.
 */
class TransferProgress(val report: (TransferProgressUpdate) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TransferProgress>

    /** A JGit monitor that forwards updates at most every [intervalMs]; [measure] is polled for [TransferProgressUpdate.bytesOnDisk]. */
    fun monitor(measure: File? = null, intervalMs: Long = 250, isCancelled: () -> Boolean = { false }): ProgressMonitor =
        object : ProgressMonitor {
            private var task = ""
            private var total = 0
            private var done = 0
            private var last = 0L
            private var lastMeasure = 0L
            private var bytes: Long? = null
            override fun start(totalTasks: Int) = Unit
            override fun beginTask(title: String?, totalWork: Int) {
                task = title.orEmpty(); total = if (totalWork == ProgressMonitor.UNKNOWN) 0 else totalWork; done = 0
                emit(force = true)
            }
            override fun update(completed: Int) { done += completed; emit(force = false) }
            override fun endTask() { if (total > 0) done = total; emit(force = true) }
            override fun isCancelled(): Boolean = isCancelled()
            private fun emit(force: Boolean) {
                val now = System.currentTimeMillis()
                if (!force && now - last < intervalMs) return
                last = now
                if (measure != null && (force || now - lastMeasure >= 500)) { lastMeasure = now; bytes = transferDirectoryBytes(measure) }
                runCatching { report(TransferProgressUpdate(task, done, total, bytes)) }
            }
        }
}

/** Total size of the regular files below [root], without following links. */
fun transferDirectoryBytes(root: File): Long = runCatching {
    root.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.filter { it.isFile }.sumOf { it.length() }
}.getOrDefault(0L)

/** Folders a local repository scan has looked at so far ("Scanning Documents · 38 folders"). */
class DiscoveryProgress(val report: (folders: Int) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DiscoveryProgress>
}

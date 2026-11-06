package com.bontecou.syncmd.services.git

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ProgressMonitor
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Which part of a transfer is reporting. */
enum class TransferPhase { LFS_UPLOAD, LFS_DOWNLOAD, GIT }

/**
 * One progress report from a running fetch or push. [task] is JGit's own task title
 * ("Counting objects", "Writing objects", "Receiving objects"); [total] is 0 when unknown.
 */
data class TransferUpdate(val phase: TransferPhase, val task: String, val done: Int, val total: Int)

/**
 * Receives transfer progress for the coroutine it is installed in. Remote operations read it from
 * the coroutine context, so callers opt in with `withContext(sink) { push(...) }` and every other
 * caller keeps JGit's silent monitor. Cancelling the coroutine also cancels the JGit transfer:
 * the monitor reports `isCancelled` and JGit stops between packets.
 */
class TransferProgressSink(private val listener: (TransferUpdate) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TransferProgressSink>

    fun phase(phase: TransferPhase) = listener(TransferUpdate(phase, "", 0, 0))

    /** A JGit monitor that forwards to this sink and stops when [job] is cancelled. */
    fun monitor(job: Job?): ProgressMonitor = object : ProgressMonitor {
        private var task = ""
        private var total = 0
        private var done = 0
        override fun start(totalTasks: Int) {}
        override fun beginTask(title: String?, totalWork: Int) {
            task = title.orEmpty(); total = totalWork.coerceAtLeast(0); done = 0
            listener(TransferUpdate(TransferPhase.GIT, task, done, total))
        }
        override fun update(completed: Int) {
            done += completed
            listener(TransferUpdate(TransferPhase.GIT, task, done, total))
        }
        override fun endTask() {
            if (total > 0) done = total
            listener(TransferUpdate(TransferPhase.GIT, task, done, total))
        }
        override fun isCancelled(): Boolean = job?.isCancelled == true
    }
}

/** The calling coroutine's transfer monitor, or JGit's silent one. */
suspend fun currentTransferMonitor(): ProgressMonitor {
    val context = currentCoroutineContext()
    return context[TransferProgressSink]?.monitor(context[Job]) ?: NullProgressMonitor.INSTANCE
}

/** The calling coroutine's sink, for non-suspending helpers that report phases. */
suspend fun currentTransferSink(): TransferProgressSink? = currentCoroutineContext()[TransferProgressSink]

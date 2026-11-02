package com.bontecou.syncmd.services.diagnostics

import java.util.UUID

enum class DiagnosticOperation { APP, CLONE, FETCH, PULL, PUSH, STAGE, UNSTAGE, DISCARD, COMMIT, BRANCH, MERGE, REBASE, CONFLICT, LFS, STASH, TAG, REVERT }
enum class DiagnosticLevel { INFO, WARNING, ERROR }
enum class DiagnosticOutcome { STARTED, COMPLETED, STOPPED, ATTENTION, CANCELLED }
enum class DiagnosticReason { NONE, LOCAL_CHANGES, LFS_CONTENTS, LFS_LOCK, TRUST_REQUIRED, CANCELLED, NETWORK, STORAGE, REVIEW_REQUIRED, UNKNOWN }
data class DiagnosticEntry(val id: String = UUID.randomUUID().toString(), val time: Long = System.currentTimeMillis(),
    val operation: DiagnosticOperation, val outcome: DiagnosticOutcome, val reason: DiagnosticReason = DiagnosticReason.NONE) {
    val level: DiagnosticLevel get() = when (outcome) {
        DiagnosticOutcome.STOPPED -> DiagnosticLevel.ERROR
        DiagnosticOutcome.ATTENTION, DiagnosticOutcome.CANCELLED -> DiagnosticLevel.WARNING
        else -> DiagnosticLevel.INFO
    }
}

/** Only typed facts enter diagnostics: never exception text, paths, credentials, URLs or Git output. */
object Diagnostics {
    @Volatile private var sink: ((DiagnosticEntry) -> Unit)? = null
    fun install(writer: ((DiagnosticEntry) -> Unit)?): ((DiagnosticEntry) -> Unit)? {
        val previous = sink; sink = writer; return previous
    }
    fun record(operation: DiagnosticOperation, outcome: DiagnosticOutcome, reason: DiagnosticReason = DiagnosticReason.NONE) {
        runCatching { sink?.invoke(DiagnosticEntry(operation = operation, outcome = outcome, reason = reason)) }
    }
    suspend fun <T> track(operation: DiagnosticOperation?, action: suspend () -> T): T {
        if (operation == null) return action()
        return run {
            record(operation, DiagnosticOutcome.STARTED)
            try {
                val result = action()
                val failure = (result as? Result<*>)?.exceptionOrNull()
                val value = (result as? Result<*>)?.getOrNull() ?: result
                val incomplete = value as? com.bontecou.syncmd.data.models.MergeResult
                val stopped = incomplete?.success == false || (value as? com.bontecou.syncmd.data.models.RevertResult)?.success == false ||
                    (value as? com.bontecou.syncmd.data.models.StashResult)?.success == false
                val pull = value as? com.bontecou.syncmd.data.models.SafePullResult
                val rebase = value as? com.bontecou.syncmd.services.git.RebaseOutcome
                val lfsAttention = failure is com.bontecou.syncmd.services.git.lfs.LfsCheckoutIncompleteException || pull?.attention != null || rebase?.notice != null
                val paused = rebase?.session != null
                val outcome = when {
                    lfsAttention || paused -> DiagnosticOutcome.ATTENTION
                    failure is kotlinx.coroutines.CancellationException -> DiagnosticOutcome.CANCELLED
                    failure != null || stopped -> DiagnosticOutcome.STOPPED
                    else -> DiagnosticOutcome.COMPLETED
                }
                record(operation, outcome, if (lfsAttention) DiagnosticReason.LFS_CONTENTS else failure?.let(::reason)
                    ?: if (paused || stopped) DiagnosticReason.REVIEW_REQUIRED else DiagnosticReason.NONE)
                result
            } catch (failure: Throwable) {
                record(operation, if (failure is kotlinx.coroutines.CancellationException) DiagnosticOutcome.CANCELLED else DiagnosticOutcome.STOPPED, reason(failure))
                throw failure
            }
        }
    }
    private fun reason(failure: Throwable): DiagnosticReason = when (failure) {
        is com.bontecou.syncmd.services.git.lfs.LfsCheckoutIncompleteException -> DiagnosticReason.LFS_CONTENTS
        is com.bontecou.syncmd.services.git.lfs.LfsLockedFilesException -> DiagnosticReason.LFS_LOCK
        is com.bontecou.syncmd.data.models.DirtyRepoException -> DiagnosticReason.LOCAL_CHANGES
        is kotlinx.coroutines.CancellationException -> DiagnosticReason.CANCELLED
        is com.bontecou.syncmd.services.git.lfs.LfsEndpointApprovalRequired,
        is com.bontecou.syncmd.services.git.lfs.LfsEndpointReviewCancelled -> DiagnosticReason.TRUST_REQUIRED
        is java.net.SocketException, is java.net.SocketTimeoutException -> DiagnosticReason.NETWORK
        is java.nio.file.AccessDeniedException, is SecurityException -> DiagnosticReason.STORAGE
        is IllegalArgumentException, is IllegalStateException -> DiagnosticReason.REVIEW_REQUIRED
        else -> DiagnosticReason.UNKNOWN
    }
}

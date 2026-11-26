package com.bontecou.syncmd.services.diagnostics

import java.util.UUID

enum class DiagnosticOperation { APP, CLONE, FETCH, PULL, PUSH, STAGE, UNSTAGE, DISCARD, COMMIT, BRANCH, MERGE, REBASE, CONFLICT, LFS, STASH, TAG, REVERT, UNTRACK }
enum class DiagnosticLevel { INFO, WARNING, ERROR }
enum class DiagnosticOutcome { STARTED, COMPLETED, STOPPED, ATTENTION, CANCELLED }
enum class DiagnosticReason { NONE, LOCAL_CHANGES, LFS_CONTENTS, LFS_LOCK, TRUST_REQUIRED, CANCELLED, NETWORK, STORAGE, REVIEW_REQUIRED, UNKNOWN }
/** Which pull check found local changes in the way. */
enum class PullCheck {
    /** After the fetch and after edits to files the remote stopped tracking were set aside. */
    BEFORE_UPDATE,
    /** Right before the fast-forward, after the untracked-file protection ran. */
    BEFORE_MERGE,
}
/** A JGit status category; never a path. */
enum class LocalChangeKind { ADDED, CHANGED, REMOVED, MISSING, MODIFIED, UNTRACKED, UNTRACKED_FOLDER, CONFLICTING }

/**
 * Typed facts about local changes that stopped an operation: the check that stopped it, the kinds
 * and number of paths in the way, and how many edits to files the remote stopped tracking were set
 * aside first (issue #137). Holds no paths, names or Git output.
 */
data class LocalChangesFacts(val check: PullCheck, val kinds: Set<LocalChangeKind>, val blocking: Int, val setAside: Int) {
    init { require(blocking >= 0 && setAside >= 0) }
    fun describe(): String = "$check, ${kinds.sorted().joinToString("+").ifEmpty { "NONE" }}, blocking $blocking, set aside $setAside"
    companion object {
        fun of(check: PullCheck, status: org.eclipse.jgit.api.Status, setAside: Int): LocalChangesFacts {
            val kinds = buildMap {
                put(LocalChangeKind.ADDED, status.added); put(LocalChangeKind.CHANGED, status.changed)
                put(LocalChangeKind.REMOVED, status.removed); put(LocalChangeKind.MISSING, status.missing)
                put(LocalChangeKind.MODIFIED, status.modified); put(LocalChangeKind.UNTRACKED, status.untracked)
                put(LocalChangeKind.UNTRACKED_FOLDER, status.untrackedFolders); put(LocalChangeKind.CONFLICTING, status.conflicting)
            }.filterValues { it.isNotEmpty() }
            return LocalChangesFacts(check, kinds.keys, kinds.values.flatten().toSet().size, setAside)
        }
    }
}

/**
 * [vault] is a one-way key of the vault's folder ([Diagnostics.vaultKey]), never its name or path, so the
 * debug log can say which vault an entry belongs to without storing either. [background] marks work
 * Background Sync ran with the app closed.
 */
data class DiagnosticEntry(val id: String = UUID.randomUUID().toString(), val time: Long = System.currentTimeMillis(),
    val operation: DiagnosticOperation, val outcome: DiagnosticOutcome, val reason: DiagnosticReason = DiagnosticReason.NONE,
    val vault: String? = null, val background: Boolean = false, val localChanges: LocalChangesFacts? = null) {
    val level: DiagnosticLevel get() = when (outcome) {
        DiagnosticOutcome.STOPPED -> DiagnosticLevel.ERROR
        DiagnosticOutcome.ATTENTION, DiagnosticOutcome.CANCELLED -> DiagnosticLevel.WARNING
        else -> DiagnosticLevel.INFO
    }
}

/** Coroutine context marking Background Sync work, so its entries get the BG badge. */
class DiagnosticScope(val background: Boolean) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<DiagnosticScope>
}

/** Only typed facts enter diagnostics: never exception text, paths, credentials, URLs or Git output. */
object Diagnostics {
    /** One-way key for a vault folder: the first 16 hex digits of SHA-256 over its canonical path. */
    fun vaultKey(path: String): String {
        val canonical = runCatching { java.io.File(path).canonicalPath }.getOrDefault(path)
        return java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @Volatile private var sink: ((DiagnosticEntry) -> Unit)? = null
    fun install(writer: ((DiagnosticEntry) -> Unit)?): ((DiagnosticEntry) -> Unit)? {
        val previous = sink; sink = writer; return previous
    }
    fun record(operation: DiagnosticOperation, outcome: DiagnosticOutcome, reason: DiagnosticReason = DiagnosticReason.NONE,
        vault: String? = null, background: Boolean = false, localChanges: LocalChangesFacts? = null) {
        runCatching { sink?.invoke(DiagnosticEntry(operation = operation, outcome = outcome, reason = reason, vault = vault,
            background = background, localChanges = localChanges)) }
    }
    suspend fun <T> track(operation: DiagnosticOperation?, action: suspend () -> T): T = track(operation, null, action)
    suspend fun <T> track(operation: DiagnosticOperation?, vault: String?, action: suspend () -> T): T {
        if (operation == null) return action()
        val background = kotlinx.coroutines.currentCoroutineContext()[DiagnosticScope]?.background == true
        fun record(operation: DiagnosticOperation, outcome: DiagnosticOutcome, reason: DiagnosticReason = DiagnosticReason.NONE,
            localChanges: LocalChangesFacts? = null) = record(operation, outcome, reason, vault, background, localChanges)
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
                    ?: if (paused || stopped) DiagnosticReason.REVIEW_REQUIRED else DiagnosticReason.NONE,
                    (failure as? com.bontecou.syncmd.data.models.DirtyRepoException)?.facts)
                result
            } catch (failure: Throwable) {
                record(operation, if (failure is kotlinx.coroutines.CancellationException) DiagnosticOutcome.CANCELLED else DiagnosticOutcome.STOPPED, reason(failure),
                    (failure as? com.bontecou.syncmd.data.models.DirtyRepoException)?.facts)
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

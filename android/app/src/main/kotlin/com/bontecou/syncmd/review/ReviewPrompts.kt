package com.bontecou.syncmd.review

import com.bontecou.syncmd.services.automation.RepositoryActionOutcome
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant
import java.util.Collections

/**
 * The app's review-prompt hooks around [ReviewPromptPolicy].
 *
 * - [countSuccess]: any successful sync, including background, push-hint, automation and widget runs.
 *   It only counts; it never asks for a prompt.
 * - [userSyncSucceeded]: a pull, push or sync the person started in the app succeeded. It counts and,
 *   when the policy allows, asks [ReviewPromptHost] (via [requests]) for a prompt.
 * - [conflictResolved]: a merge was completed or a rebase finished in the app; the next user-started
 *   push of that vault becomes a [ReviewPromptMoment.CONFLICT_RESOLVED] moment.
 *
 * Callers never pass a moment for undo, re-auth or queued replays, Shortcuts/x-callback/widgets/tiles,
 * a Sync all with a failed or blocked vault, local-changes blocks, diverged or conflicted outcomes.
 *
 * [requests] has no replay: a request made while no resumed activity collects it is dropped, so a sync
 * that ends while the app is in the background never prompts later.
 */
class ReviewPrompts(
    val policy: ReviewPromptPolicy,
    private val appVersion: String,
    private val clock: () -> Instant = Instant::now,
) {
    private val pendingConflicts = Collections.synchronizedSet(mutableSetOf<String>())
    private val _requests = MutableSharedFlow<ReviewPromptMoment>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val requests: SharedFlow<ReviewPromptMoment> = _requests.asSharedFlow()

    /** Counts a success without ever prompting (background and automation runs). */
    fun countSuccess(): Boolean = runCatching { policy.recordSuccess(clock()) }.getOrDefault(false)

    /** Counts a user-started success and requests the matching moment. [path] is the vault, when one. */
    fun userSyncSucceeded(path: String?) {
        countSuccess()
        val moment = if (path != null && pendingConflicts.remove(path)) ReviewPromptMoment.CONFLICT_RESOLVED else ReviewPromptMoment.USER_SYNC
        request(moment)
    }

    /** The vault at [path] finished a merge or rebase with nothing left conflicting; its next push may prompt. */
    fun conflictResolved(path: String) { pendingConflicts += path }

    /** Play Billing reported the unlock purchase: starts the quiet period. */
    fun notePurchase() { runCatching { policy.notePurchase(clock()) } }

    fun isEligible(): Boolean = runCatching { policy.isEligible(clock(), appVersion) }.getOrDefault(false)

    /** Asks the foreground host for a prompt when the policy allows one now. True when the request was made. */
    fun request(moment: ReviewPromptMoment): Boolean {
        if (!isEligible()) return false
        return _requests.tryEmit(moment)
    }

    /** Called by the host right before it launches Play's review flow. */
    fun promptLaunched() { runCatching { policy.markPrompted(clock(), appVersion) } }

    companion object {
        /** The app's instance, installed in Application.onCreate; null in unit and device tests that skip it. */
        @Volatile var shared: ReviewPrompts? = null
            private set

        fun install(prompts: ReviewPrompts) { shared = prompts }
    }
}

/** Repository-action outcomes that mean the vault synced with its remote. */
val syncedOutcomes: Set<RepositoryActionOutcome> = setOf(
    RepositoryActionOutcome.PULLED, RepositoryActionOutcome.PUSHED, RepositoryActionOutcome.PULLED_AND_PUSHED, RepositoryActionOutcome.UP_TO_DATE)

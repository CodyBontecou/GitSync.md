package com.bontecou.syncmd.ui.viewmodels

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.repositories.GitTrackingRemovalPlan

/** The final consent owns both its reviewed repository and its backup choice. */
data class TrackingRemovalRequest(val plan: GitTrackingRemovalPlan, val keepBackup: Boolean)

/** Typed so the UI can present localized copy; the message is a diagnostic fallback only. */
class StaleTrackingRemovalException :
    IllegalStateException("Tracking-removal review changed. Review the repository and backup choice again.")

/** Check again after waiting: cancellation or a new review revokes a queued request. */
internal suspend fun <T> withTrackingRemovalRequest(request: TrackingRemovalRequest,
    current: () -> GitTrackingRemovalPlan?, action: suspend (GitTrackingRemovalPlan, Boolean) -> T): T =
    RepositoryOperationCoordinator.withRepository(request.plan.path) {
        if (current() !== request.plan) throw StaleTrackingRemovalException()
        action(request.plan, request.keepBackup)
    }

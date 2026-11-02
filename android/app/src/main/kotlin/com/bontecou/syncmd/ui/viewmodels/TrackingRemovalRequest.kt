package com.bontecou.syncmd.ui.viewmodels

import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import com.bontecou.syncmd.services.repositories.GitTrackingRemovalPlan

/** The final consent owns both its reviewed repository and its backup choice. */
data class TrackingRemovalRequest(val plan: GitTrackingRemovalPlan, val keepBackup: Boolean)

/** Check again after waiting: cancellation or a new review revokes a queued request. */
internal suspend fun <T> withTrackingRemovalRequest(request: TrackingRemovalRequest,
    current: () -> GitTrackingRemovalPlan?, action: suspend (GitTrackingRemovalPlan, Boolean) -> T): T =
    RepositoryOperationCoordinator.withRepository(request.plan.path) {
        check(current() === request.plan) { "Tracking-removal review changed. Review the repository and backup choice again." }
        action(request.plan, request.keepBackup)
    }

package com.bontecou.syncmd.ui.viewmodels

import com.bontecou.syncmd.services.repositories.ManagedRepositoryDeletionPlan
import com.bontecou.syncmd.services.git.RepositoryOperationCoordinator
import java.io.File

/** Catalog registration and checkout authority captured before presenting Delete. */
data class RepositoryDeletionReview(val repository: SavedRepository, val plan: ManagedRepositoryDeletionPlan)

/** Typed so the UI can present localized copy; the message is a diagnostic fallback only. */
class StaleRepositoryDeletionException :
    IllegalStateException("Repository deletion review changed. Review the clone again before deleting it.")

/** Revalidate consent and catalog authority after waiting for the checkout lease. */
internal suspend fun <T> withRepositoryDeletionReview(review: RepositoryDeletionReview,
    current: () -> RepositoryDeletionReview?, registered: () -> SavedRepository?,
    action: suspend (SavedRepository, ManagedRepositoryDeletionPlan) -> T): T =
    RepositoryOperationCoordinator.withRepository(review.plan.path) {
        if (!(current() === review && registered() == review.repository && review.repository.managedStorage &&
            File(review.repository.path).canonicalPath == review.plan.path)) throw StaleRepositoryDeletionException()
        action(review.repository, review.plan)
    }

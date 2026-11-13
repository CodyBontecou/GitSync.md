package com.bontecou.syncmd.storage

import com.bontecou.syncmd.services.repositories.RepositoryMovePlan
import com.bontecou.syncmd.ui.viewmodels.SavedRepository

/** Durable catalog intent; credentials remain exclusively in the encrypted credential store. */
data class RepositoryMoveMetadata(val original: SavedRepository, val remote: String?, val branch: String?,
    val sourceCredentialVersion: String?, val destinationCredentialVersion: String?, val recoveryCredentialVersion: String?,
    val restoringRecoveryId: String? = null, val version: Int = 1)
data class RepositoryMoveReview(val plan: RepositoryMovePlan, val metadata: RepositoryMoveMetadata)

/** Typed so the UI can present localized recovery; the message is a diagnostic fallback only. */
class StaleMoveReviewException : IllegalStateException("Repository move review changed. Review the destination again before moving it.")

/** Recheck the exact displayed review inside RepositoryMove.execute's checkout leases. */
internal fun requireCurrentMoveReview(review: RepositoryMoveReview, current: RepositoryMoveReview?) {
    if (current !== review || review.plan.source != review.metadata.original.path) throw StaleMoveReviewException()
}

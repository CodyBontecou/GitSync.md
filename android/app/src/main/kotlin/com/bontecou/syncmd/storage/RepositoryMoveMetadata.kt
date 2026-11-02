package com.bontecou.syncmd.storage

import com.bontecou.syncmd.services.repositories.RepositoryMovePlan
import com.bontecou.syncmd.ui.viewmodels.SavedRepository

/** Durable catalog intent; credentials remain exclusively in the encrypted credential store. */
data class RepositoryMoveMetadata(val original: SavedRepository, val remote: String?, val branch: String?,
    val sourceCredentialVersion: String?, val destinationCredentialVersion: String?, val recoveryCredentialVersion: String?,
    val restoringRecoveryId: String? = null, val version: Int = 1)
data class RepositoryMoveReview(val plan: RepositoryMovePlan, val metadata: RepositoryMoveMetadata)

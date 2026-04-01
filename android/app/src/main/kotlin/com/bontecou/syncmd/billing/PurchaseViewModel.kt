package com.bontecou.syncmd.billing

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.ProductDetails
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel wrapper around [PurchaseManager].
 *
 * Exposes purchase state as [StateFlow]s so Compose can observe them, and bridges
 * coroutine-based billing operations with [viewModelScope].
 */
@HiltViewModel
class PurchaseViewModel @Inject constructor(
    private val purchaseManager: PurchaseManager,
) : ViewModel() {

    // ── Observed state ─────────────────────────────────────────────────────

    val isUnlocked:     StateFlow<Boolean>         = purchaseManager.isUnlocked
    val isPurchasing:   StateFlow<Boolean>         = purchaseManager.isPurchasing
    val isRestoring:    StateFlow<Boolean>         = purchaseManager.isRestoring
    val purchaseError:  StateFlow<String?>         = purchaseManager.purchaseError
    val productDetails: StateFlow<ProductDetails?> = purchaseManager.productDetails

    // ── Repo-tracking pass-throughs ────────────────────────────────────────

    val uniqueReposEverAdded: Int
        get() = purchaseManager.uniqueReposEverAdded

    fun isNewRepoIdentifier(identifier: String): Boolean =
        purchaseManager.isNewRepoIdentifier(identifier)

    fun recordRepoAdded(identifier: String): Boolean =
        purchaseManager.recordRepoAdded(identifier)

    // ── Async operations ───────────────────────────────────────────────────

    fun refreshStatus() {
        viewModelScope.launch { purchaseManager.refreshStatus() }
    }

    fun loadProduct() {
        viewModelScope.launch { purchaseManager.loadProduct() }
    }

    fun purchase(activity: Activity) {
        viewModelScope.launch { purchaseManager.purchase(activity) }
    }

    fun restore() {
        viewModelScope.launch { purchaseManager.restore() }
    }

    fun clearPurchaseError() {
        purchaseManager.clearPurchaseError()
    }
}

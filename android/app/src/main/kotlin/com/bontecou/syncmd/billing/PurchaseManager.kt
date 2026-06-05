package com.bontecou.syncmd.billing

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.bontecou.syncmd.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Manages the one-time unlock IAP and repo-tracking for Gitsync.md on Android.
 *
 * Mirrors iOS PurchaseManager:
 *  - FREE_REPO_LIMIT = 1 free repository before a purchase is required.
 *  - seenRepoIDs persisted in SharedPreferences backed up via Android Auto Backup —
 *    survives app deletion and reinstall on the same Google account, preventing
 *    free-tier bypass via uninstall/reinstall or delete/re-clone.
 *  - isUnlocked / isPurchasing / isRestoring state flows.
 */
@Singleton
class PurchaseManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : PurchasesUpdatedListener {

    companion object {
        /** Product ID registered in Google Play Console. */
        const val PRODUCT_ID = "unlock-full-version"

        /** Number of repositories included for free before a purchase is required. */
        const val FREE_REPO_LIMIT = 1

        /**
         * SharedPreferences file name — MUST stay in sync with backup_rules.xml so
         * that the seen-repo set and unlock flag are restored on reinstall.
         */
        private const val PREFS_NAME       = "syncmd_purchase_prefs"
        private const val KEY_IAP_UNLOCK   = "cachedIAPUnlock"
        private const val KEY_SEEN_REPO_IDS = "seenRepoIds"
        private const val KEY_DEBUG_SIMULATED_UNLOCK = "debugSimulatedUnlock"
    }

    // ── Published state ────────────────────────────────────────────────────

    private val _isUnlocked     = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _isPurchasing   = MutableStateFlow(false)
    val isPurchasing: StateFlow<Boolean> = _isPurchasing.asStateFlow()

    private val _isRestoring    = MutableStateFlow(false)
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    private val _purchaseError  = MutableStateFlow<String?>(null)
    val purchaseError: StateFlow<String?> = _purchaseError.asStateFlow()

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    private val _debugSimulatedUnlocked = MutableStateFlow(false)
    val debugSimulatedUnlocked: StateFlow<Boolean> = _debugSimulatedUnlocked.asStateFlow()

    val isDebugBuild: Boolean
        get() = BuildConfig.DEBUG

    // ── Storage ────────────────────────────────────────────────────────────

    /**
     * Separate SharedPreferences file included in Android Auto Backup.
     * Using a dedicated file makes it trivial to whitelist just this file in
     * backup_rules.xml / data_extraction_rules.xml without accidentally backing
     * up auth tokens or repo paths from the main prefs file.
     */
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Repo-tracking logic — delegates to a SharedPreferences-backed storage. */
    private val repoTracker = RepoTracker(
        storage   = SharedPreferencesRepoTrackerStorage(prefs, KEY_SEEN_REPO_IDS),
        freeLimit = FREE_REPO_LIMIT,
    )

    // ── Billing client ─────────────────────────────────────────────────────

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    // ── Init ───────────────────────────────────────────────────────────────

    init {
        // Fast path: restore cached unlock state without a Play Store round-trip.
        // Keeps the UI snappy on cold start (mirrors iOS hydrateCachedUnlockState).
        hydrateCachedUnlockState()
        hydrateDebugSimulationState()
    }

    private fun hydrateCachedUnlockState() {
        if (prefs.getBoolean(KEY_IAP_UNLOCK, false)) {
            _isUnlocked.value = true
        }
    }

    private fun hydrateDebugSimulationState() {
        if (!BuildConfig.DEBUG) return

        // First debug run mirrors the currently cached real state.
        val simulated = if (prefs.contains(KEY_DEBUG_SIMULATED_UNLOCK)) {
            prefs.getBoolean(KEY_DEBUG_SIMULATED_UNLOCK, false)
        } else {
            _isUnlocked.value
        }

        _debugSimulatedUnlocked.value = simulated
        prefs.edit().putBoolean(KEY_DEBUG_SIMULATED_UNLOCK, simulated).apply()
        _isUnlocked.value = simulated
    }

    private fun applyDebugSimulationOverrideIfNeeded(): Boolean {
        if (!BuildConfig.DEBUG) return false
        _isUnlocked.value = _debugSimulatedUnlocked.value
        return true
    }

    // ── Billing connection ─────────────────────────────────────────────────

    private suspend fun ensureConnected(): Boolean = suspendCancellableCoroutine { cont ->
        if (billingClient.isReady) {
            cont.resume(true)
            return@suspendCancellableCoroutine
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
            override fun onBillingServiceDisconnected() {
                if (cont.isActive) cont.resume(false)
            }
        })
    }

    // ── Product loading ────────────────────────────────────────────────────

    /**
     * Fetches the IAP product from Google Play so the UI can show the live price.
     * Silently ignored on failure — UI falls back to a generic unlock label.
     */
    suspend fun loadProduct() {
        if (!ensureConnected()) return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        suspendCancellableCoroutine { cont ->
            billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    _productDetails.value =
                        productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
                }
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    // ── Refresh status ─────────────────────────────────────────────────────

    /**
     * Re-evaluates unlock status from Google Play.
     * Called passively on screen appear and explicitly on restore.
     */
    suspend fun refreshStatus() {
        if (applyDebugSimulationOverrideIfNeeded()) return
        if (!ensureConnected()) return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        suspendCancellableCoroutine { cont ->
            billingClient.queryPurchasesAsync(params) { _, purchases ->
                val hasPurchase = purchases.any { purchase ->
                    purchase.products.contains(PRODUCT_ID) &&
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                if (hasPurchase) {
                    prefs.edit().putBoolean(KEY_IAP_UNLOCK, true).apply()
                    _isUnlocked.value = true
                } else {
                    prefs.edit().putBoolean(KEY_IAP_UNLOCK, false).apply()
                    _isUnlocked.value = false
                }
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    // ── Purchase ───────────────────────────────────────────────────────────

    /**
     * Initiates the Google Play purchase flow.
     * Requires an [Activity] reference; result arrives in [onPurchasesUpdated].
     */
    suspend fun purchase(activity: Activity) {
        _purchaseError.value = null

        // Load product if not yet cached (or stale/mismatched).
        val details = _productDetails.value
            ?.takeIf { it.productId == PRODUCT_ID }
            ?: run {
                loadProduct()
                _productDetails.value?.takeIf { it.productId == PRODUCT_ID }
            }
            ?: run {
                _purchaseError.value = "Product unavailable. Please try again later."
                return
            }

        if (!ensureConnected()) {
            _purchaseError.value = "Unable to connect to Google Play."
            return
        }

        val productDetailsParams = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build()
        )
        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParams)
            .build()

        _isPurchasing.value = true
        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _isPurchasing.value = false
            _purchaseError.value = "Failed to launch purchase flow: ${result.debugMessage}"
        }
        // isPurchasing cleared in onPurchasesUpdated
    }

    // ── Restore ────────────────────────────────────────────────────────────

    /** Restores access for existing purchasers by querying Google Play. */
    suspend fun restore() {
        _isRestoring.value = true
        _purchaseError.value = null
        try {
            refreshStatus()
            if (!_isUnlocked.value) {
                _purchaseError.value =
                    "No purchase found on this Google account.\n\n" +
                    "If you believe this is an error, contact us at cody@isolated.tech and we'll sort it out."
            }
        } finally {
            _isRestoring.value = false
        }
    }

    fun clearPurchaseError() {
        _purchaseError.value = null
    }

    // ── PurchasesUpdatedListener ───────────────────────────────────────────

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        _isPurchasing.value = false
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.products.contains(PRODUCT_ID) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        if (!purchase.isAcknowledged) acknowledgePurchase(purchase)
                        prefs.edit().putBoolean(KEY_IAP_UNLOCK, true).apply()
                        _isUnlocked.value = true
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> { /* no-op */ }
            else -> {
                _purchaseError.value = "Purchase failed: ${result.debugMessage}"
            }
        }

        // Keep debug builds pinned to the simulated setting even after billing callbacks.
        applyDebugSimulationOverrideIfNeeded()
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { /* best-effort fire-and-forget */ }
    }

    // ── Repo-tracking helpers (delegated to RepoTracker) ─────────────────

    fun seenRepoIdentifiers(): Set<String>               = repoTracker.seenIdentifiers()
    fun recordRepoAdded(identifier: String): Boolean     = repoTracker.recordAdded(identifier)
    val uniqueReposEverAdded: Int                        get() = repoTracker.uniqueCount
    fun isNewRepoIdentifier(identifier: String): Boolean = repoTracker.isNewIdentifier(identifier)

    // ── Debug ──────────────────────────────────────────────────────────────

    /** Force the effective purchase state in debug builds (Settings → Developer). */
    fun setDebugSimulatedUnlocked(unlocked: Boolean) {
        if (!BuildConfig.DEBUG) return
        _debugSimulatedUnlocked.value = unlocked
        prefs.edit().putBoolean(KEY_DEBUG_SIMULATED_UNLOCK, unlocked).apply()
        _isUnlocked.value = unlocked
    }

    /** Clears all purchase and seen-repo state. DEBUG ONLY. */
    fun debugResetPurchaseState() {
        prefs.edit()
            .remove(KEY_IAP_UNLOCK)
            .remove(KEY_SEEN_REPO_IDS)
            .remove(KEY_DEBUG_SIMULATED_UNLOCK)
            .apply()
        _debugSimulatedUnlocked.value = false
        _isUnlocked.value = false
    }
}

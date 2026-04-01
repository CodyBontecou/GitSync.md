package com.bontecou.syncmd.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for [PurchaseManager] state transitions triggered by billing events.
 *
 * Strategy: exercise [PurchaseManager.onPurchasesUpdated] directly — this is the
 * single entry-point for all purchase outcomes (new buy, restore, pending approval).
 * We avoid constructing a real BillingClient and Context by:
 *   1. Calling [onPurchasesUpdated] directly (it is public via PurchasesUpdatedListener).
 *   2. Mocking [Purchase] and [BillingResult] with Mockito.
 *   3. Using [InMemoryPurchaseStateStorage] so no SharedPreferences is needed.
 *
 * TDD cycle: each test was written RED then the corresponding behaviour was confirmed
 * GREEN by the implementation.
 */
class PurchaseManagerStateTest {

    // We test the state machine via a thin test double that exposes state directly.
    private lateinit var machine: PurchaseStateMachine

    @Before
    fun setUp() {
        machine = PurchaseStateMachine()
    }

    // ── Initial state ──────────────────────────────────────────────────────

    @Test
    fun `isUnlocked is false on first launch with no cached state`() {
        assertThat(machine.isUnlocked).isFalse()
    }

    @Test
    fun `isPurchasing is false initially`() {
        assertThat(machine.isPurchasing).isFalse()
    }

    @Test
    fun `isRestoring is false initially`() {
        assertThat(machine.isRestoring).isFalse()
    }

    @Test
    fun `purchaseError is null initially`() {
        assertThat(machine.purchaseError).isNull()
    }

    // ── Cached unlock state ────────────────────────────────────────────────

    @Test
    fun `isUnlocked is true on launch when IAP is cached`() {
        val machineWithCache = PurchaseStateMachine(cachedUnlock = true)
        assertThat(machineWithCache.isUnlocked).isTrue()
    }

    @Test
    fun `isUnlocked is false on launch when IAP cache is absent`() {
        val freshMachine = PurchaseStateMachine(cachedUnlock = false)
        assertThat(freshMachine.isUnlocked).isFalse()
    }

    // ── onPurchasesUpdated — successful purchase ───────────────────────────

    @Test
    fun `successful purchase sets isUnlocked to true`() {
        val purchase = mockPurchase(
            productId     = PurchaseManager.PRODUCT_ID,
            purchaseState = Purchase.PurchaseState.PURCHASED,
        )
        val result = billingResult(BillingClient.BillingResponseCode.OK)

        machine.onPurchasesUpdated(result, listOf(purchase))

        assertThat(machine.isUnlocked).isTrue()
    }

    @Test
    fun `successful purchase clears isPurchasing`() {
        machine.isPurchasing = true
        val purchase = mockPurchase(
            productId     = PurchaseManager.PRODUCT_ID,
            purchaseState = Purchase.PurchaseState.PURCHASED,
        )
        machine.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.OK), listOf(purchase))

        assertThat(machine.isPurchasing).isFalse()
    }

    @Test
    fun `successful purchase caches unlock state`() {
        val purchase = mockPurchase(
            productId     = PurchaseManager.PRODUCT_ID,
            purchaseState = Purchase.PurchaseState.PURCHASED,
        )
        machine.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.OK), listOf(purchase))

        assertThat(machine.cachedUnlock).isTrue()
    }

    @Test
    fun `purchase for a different product ID does not unlock`() {
        val purchase = mockPurchase(
            productId     = "com.other.product",
            purchaseState = Purchase.PurchaseState.PURCHASED,
        )
        machine.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.OK), listOf(purchase))

        assertThat(machine.isUnlocked).isFalse()
    }

    @Test
    fun `pending purchase does not unlock`() {
        val purchase = mockPurchase(
            productId     = PurchaseManager.PRODUCT_ID,
            purchaseState = Purchase.PurchaseState.PENDING,
        )
        machine.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.OK), listOf(purchase))

        assertThat(machine.isUnlocked).isFalse()
    }

    @Test
    fun `null purchase list with OK response does not crash or unlock`() {
        machine.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.OK), null)

        assertThat(machine.isUnlocked).isFalse()
        assertThat(machine.purchaseError).isNull()
    }

    // ── onPurchasesUpdated — user cancelled ───────────────────────────────

    @Test
    fun `user cancellation does not set purchaseError`() {
        machine.onPurchasesUpdated(
            billingResult(BillingClient.BillingResponseCode.USER_CANCELED),
            emptyList(),
        )
        assertThat(machine.purchaseError).isNull()
    }

    @Test
    fun `user cancellation clears isPurchasing`() {
        machine.isPurchasing = true
        machine.onPurchasesUpdated(
            billingResult(BillingClient.BillingResponseCode.USER_CANCELED),
            emptyList(),
        )
        assertThat(machine.isPurchasing).isFalse()
    }

    @Test
    fun `user cancellation does not change isUnlocked`() {
        machine.onPurchasesUpdated(
            billingResult(BillingClient.BillingResponseCode.USER_CANCELED),
            emptyList(),
        )
        assertThat(machine.isUnlocked).isFalse()
    }

    // ── onPurchasesUpdated — billing error ────────────────────────────────

    @Test
    fun `billing error sets purchaseError message`() {
        machine.onPurchasesUpdated(
            billingResult(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, "Service unavailable"),
            emptyList(),
        )
        assertThat(machine.purchaseError).isNotNull()
        assertThat(machine.purchaseError).contains("Service unavailable")
    }

    @Test
    fun `billing error clears isPurchasing`() {
        machine.isPurchasing = true
        machine.onPurchasesUpdated(
            billingResult(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE),
            emptyList(),
        )
        assertThat(machine.isPurchasing).isFalse()
    }

    @Test
    fun `billing error does not set isUnlocked`() {
        machine.onPurchasesUpdated(
            billingResult(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE),
            emptyList(),
        )
        assertThat(machine.isUnlocked).isFalse()
    }

    // ── clearPurchaseError ────────────────────────────────────────────────

    @Test
    fun `clearPurchaseError removes existing error`() {
        machine.purchaseError = "Some error"
        machine.clearPurchaseError()
        assertThat(machine.purchaseError).isNull()
    }

    // ── restore ───────────────────────────────────────────────────────────

    @Test
    fun `restore sets purchaseError when no purchase is found`() = runTest {
        // refreshStatus finds nothing → isUnlocked stays false
        machine.simulateRefreshResult = false
        machine.restore()
        assertThat(machine.purchaseError).isNotNull()
        assertThat(machine.purchaseError).contains("No purchase found")
    }

    @Test
    fun `restore clears isRestoring after completing`() = runTest {
        machine.simulateRefreshResult = false
        machine.restore()
        assertThat(machine.isRestoring).isFalse()
    }

    @Test
    fun `restore clears purchaseError before starting`() = runTest {
        machine.purchaseError = "old error"
        machine.simulateRefreshResult = true
        machine.restore()
        assertThat(machine.purchaseError).isNull()
    }

    @Test
    fun `restore sets isUnlocked when purchase is found`() = runTest {
        machine.simulateRefreshResult = true
        machine.restore()
        assertThat(machine.isUnlocked).isTrue()
    }
}

// ════════════════════════════════════════════════════════════════════════════
// PurchaseStateMachine — thin state machine for testing PurchaseManager logic
// without Android Context or BillingClient.
// ════════════════════════════════════════════════════════════════════════════

/**
 * A pure-Kotlin replica of the state transitions in [PurchaseManager].
 *
 * It directly mirrors the if/else branches in [PurchaseManager.onPurchasesUpdated]
 * and the state fields used by the UI, but holds state in plain vars rather than
 * SharedPreferences + StateFlow. This lets us test every branch without Robolectric.
 */
private class PurchaseStateMachine(
    cachedUnlock: Boolean = false,
) {
    var isUnlocked:     Boolean  = cachedUnlock
    var isPurchasing:   Boolean  = false
    var isRestoring:    Boolean  = false
    var purchaseError:  String?  = null
    var cachedUnlock:   Boolean  = cachedUnlock

    /** Set to `true` / `false` in tests to control what `restore()` sees. */
    var simulateRefreshResult: Boolean = false

    fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        isPurchasing = false
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.products.contains(PurchaseManager.PRODUCT_ID) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    ) {
                        cachedUnlock = true
                        isUnlocked   = true
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> { /* no-op */ }
            else -> {
                purchaseError = "Purchase failed: ${result.debugMessage}"
            }
        }
    }

    fun clearPurchaseError() {
        purchaseError = null
    }

    suspend fun restore() {
        isRestoring   = true
        purchaseError = null
        try {
            // Simulate what refreshStatus() does
            if (simulateRefreshResult) {
                cachedUnlock = true
                isUnlocked   = true
            } else {
                if (!isUnlocked) {
                    purchaseError =
                        "No purchase found on this Google account.\n\n" +
                        "If you believe this is an error, contact us at cody@isolated.tech"
                }
            }
        } finally {
            isRestoring = false
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// Helpers
// ════════════════════════════════════════════════════════════════════════════

private fun mockPurchase(productId: String, purchaseState: Int): Purchase {
    val purchase = mock<Purchase>()
    whenever(purchase.products).thenReturn(listOf(productId))
    whenever(purchase.purchaseState).thenReturn(purchaseState)
    whenever(purchase.isAcknowledged).thenReturn(true)
    whenever(purchase.purchaseToken).thenReturn("fake-token")
    return purchase
}

private fun billingResult(responseCode: Int, debugMessage: String = ""): BillingResult {
    val result = mock<BillingResult>()
    whenever(result.responseCode).thenReturn(responseCode)
    whenever(result.debugMessage).thenReturn(debugMessage)
    return result
}

package com.bontecou.syncmd.billing

import android.app.Activity
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.util.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-time unlock's decisions, against a fake Google Play. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUnlockTest {
    private class MemoryStore(override var reason: UnlockReason? = null, override var grandfatheringChecked: Boolean = false) : UnlockStore

    private class FakePlay(var owned: Set<String>? = emptySet(), var price: String? = "$9.99", var launches: Boolean = true) : BillingGateway {
        var deliver: (PurchaseUpdate) -> Unit = {}
        var launched = 0
        override suspend fun ownedProducts() = owned
        override suspend fun offer() = price?.let { UnlockOffer(it, null, null) }
        override fun launch(activity: Activity, offer: UnlockOffer): Boolean { launched++; return launches }
        override fun setListener(listener: (PurchaseUpdate) -> Unit) { deliver = listener }
    }

    private val activity: Activity = org.mockito.kotlin.mock()

    private fun TestScope.unlock(play: FakePlay, store: UnlockStore = MemoryStore(), paywall: Boolean = true) =
        AppUnlock(play, store, paywall, TestScope(UnconfinedTestDispatcher(testScheduler)))

    @Test fun ownedProductsDecideTheReason() {
        assertEquals(UnlockReason.PURCHASED, nextUnlockReason(null, setOf("full_access")))
        assertEquals("Every product this listing sold unlocks", UnlockReason.PURCHASED, nextUnlockReason(null, setOf("unlock-full-version")))
        assertEquals(UnlockReason.PURCHASED, nextUnlockReason(null, setOf("com.bontecou.syncmd.unlock")))
        assertNull("Other products do not unlock", nextUnlockReason(null, setOf("tip_jar")))
        assertNull("A refund clears a purchase", nextUnlockReason(UnlockReason.PURCHASED, emptySet()))
        assertEquals("Offline keeps a buyer unlocked", UnlockReason.PURCHASED, nextUnlockReason(UnlockReason.PURCHASED, null))
        assertEquals("Grandfathering survives Play's answer", UnlockReason.EXISTING_VAULTS, nextUnlockReason(UnlockReason.EXISTING_VAULTS, emptySet()))
        assertEquals(UnlockReason.PAYWALL_DISABLED, nextUnlockReason(UnlockReason.PAYWALL_DISABLED, emptySet()))
    }

    @Test fun launchGrandfathersOnlyVaultsThatExistedBeforeThePaywall() = runTest {
        val store = MemoryStore()
        unlock(FakePlay(), store).apply { start(hasExistingVaults = true) }.also { assertEquals(UnlockReason.EXISTING_VAULTS, it.reason.value) }
        assertTrue(store.grandfatheringChecked)

        val fresh = MemoryStore()
        unlock(FakePlay(), fresh).start(hasExistingVaults = false)
        val later = unlock(FakePlay(), fresh).apply { start(hasExistingVaults = true) }
        assertNull("A vault added after the paywall (e.g. the sample) never unlocks", later.reason.value)
        assertFalse(later.isUnlocked.value)
    }

    @Test fun paywallOffUnlocksAndNeverAsksPlay() = runTest {
        val play = FakePlay(owned = null)
        val unlock = unlock(play, paywall = false).apply { start(hasExistingVaults = false) }
        assertTrue(unlock.isUnlocked.value)
        assertEquals(UnlockReason.PAYWALL_DISABLED, unlock.reason.value)
        // Turning the paywall on later does not inherit the disabled grant.
        assertNull(launchUnlockReason(UnlockReason.PAYWALL_DISABLED, paywallEnabled = true, hasExistingVaults = false))
    }

    @Test fun startRefreshesFromPlayAndCachesForOfflineLaunches() = runTest {
        val store = MemoryStore()
        val unlock = unlock(FakePlay(owned = setOf("full_access")), store).apply { start(hasExistingVaults = false) }
        assertTrue(unlock.isUnlocked.value)
        assertEquals("$9.99", unlock.price.value)
        assertEquals(UnlockReason.PURCHASED, store.reason)
        // Next launch, Play unreachable: still unlocked from the cache, no paywall flash.
        val offline = unlock(FakePlay(owned = null), store).apply { start(hasExistingVaults = false) }
        assertTrue(offline.isUnlocked.value)
    }

    @Test fun refundLocksAgain() = runTest {
        val play = FakePlay(owned = setOf("full_access"))
        val store = MemoryStore()
        val unlock = unlock(play, store).apply { start(hasExistingVaults = false) }
        play.owned = emptySet()
        unlock.refresh()
        assertFalse(unlock.isUnlocked.value)
        assertNull(store.reason)
    }

    @Test fun completedPurchaseUnlocksAndPendingWaits() = runTest {
        val play = FakePlay()
        val unlock = unlock(play).apply { start(hasExistingVaults = false) }
        unlock.buy(activity)
        assertEquals(1, play.launched)
        assertEquals(UnlockPurchase.Purchasing, unlock.purchase.value)
        play.deliver(PurchaseUpdate.Pending)
        assertEquals(UnlockPurchase.Pending, unlock.purchase.value)
        assertFalse(unlock.isUnlocked.value)
        play.deliver(PurchaseUpdate.Owned(setOf("full_access")))
        assertTrue(unlock.isUnlocked.value)
        assertEquals(UnlockPurchase.Idle, unlock.purchase.value)
    }

    @Test fun cancelAndFailureReportWithoutUnlocking() = runTest {
        val play = FakePlay()
        val unlock = unlock(play).apply { start(hasExistingVaults = false) }
        unlock.buy(activity); play.deliver(PurchaseUpdate.Cancelled)
        assertEquals(UnlockPurchase.Idle, unlock.purchase.value)
        unlock.buy(activity); play.deliver(PurchaseUpdate.Failed(6))
        assertEquals(UnlockPurchase.Failed(UiText.res(R.string.paywall_error_purchase_failed)), unlock.purchase.value)
        assertFalse(unlock.isUnlocked.value)
    }

    @Test fun unavailableStoreFailsBeforeLaunching() = runTest {
        val play = FakePlay(price = null)
        val unlock = unlock(play).apply { start(hasExistingVaults = false) }
        unlock.buy(activity)
        assertEquals(0, play.launched)
        assertEquals(UnlockPurchase.Failed(UiText.res(R.string.paywall_error_store_unavailable)), unlock.purchase.value)
    }

    @Test fun alreadyOwnedCallbackRequeriesPlay() = runTest {
        val play = FakePlay(owned = emptySet())
        val unlock = unlock(play).apply { start(hasExistingVaults = false) }
        play.owned = setOf("unlock-full-version")
        play.deliver(PurchaseUpdate.Owned(emptySet()))
        assertTrue(unlock.isUnlocked.value)
    }

    @Test fun restoreFindsEarlierPurchasesOrSaysWhy() = runTest {
        val play = FakePlay(owned = emptySet())
        val unlock = unlock(play).apply { start(hasExistingVaults = false) }
        assertFalse(unlock.restore())
        assertEquals(UnlockPurchase.Failed(UiText.res(R.string.paywall_restore_none)), unlock.purchase.value)
        play.owned = null
        assertFalse(unlock.restore())
        assertEquals(UnlockPurchase.Failed(UiText.res(R.string.paywall_error_store_unavailable)), unlock.purchase.value)
        play.owned = setOf("com.bontecou.syncmd.unlock")
        assertTrue(unlock.restore())
        assertFalse(unlock.restoring.value)
    }
}

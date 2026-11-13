package com.bontecou.syncmd.billing

import android.app.Activity
import android.content.Context
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.util.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the unlock reason survives restarts, so an offline launch never shows the paywall to a buyer. */
interface UnlockStore {
    var reason: UnlockReason?
    /** True once a paywall build has looked for pre-existing vaults; later vaults never grandfather. */
    var grandfatheringChecked: Boolean
}

class PrefsUnlockStore(context: Context) : UnlockStore {
    private val prefs = context.getSharedPreferences("syncmd_purchase_prefs", Context.MODE_PRIVATE)
    override var reason: UnlockReason?
        get() = prefs.getString(KEY, null)?.let { runCatching { UnlockReason.valueOf(it) }.getOrNull() }
            // Builds before the hard paywall cached a plain flag in the same file.
            ?: UnlockReason.PURCHASED.takeIf { prefs.getBoolean("cachedIAPUnlock", false) }
        set(value) { prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value.name) }.remove("cachedIAPUnlock").apply() }
    override var grandfatheringChecked: Boolean
        get() = prefs.getBoolean(CHECKED, false)
        set(value) { prefs.edit().putBoolean(CHECKED, value).apply() }

    private companion object { const val KEY = "unlockReason"; const val CHECKED = "grandfatheringChecked" }
}

/** The purchase sheet's progress. */
sealed interface UnlockPurchase {
    data object Idle : UnlockPurchase
    data object Purchasing : UnlockPurchase
    /** Play holds the purchase for approval (e.g. a family manager or a cash payment). */
    data object Pending : UnlockPurchase
    data class Failed(val message: UiText) : UnlockPurchase
}

/**
 * The one-time unlock of the free listing. Mirrors iOS `AppUnlock`: a cached reason,
 * Play as the source of truth for purchases, and grandfathering for anyone who
 * already had vaults when the paywall shipped.
 */
class AppUnlock(
    private val gateway: BillingGateway,
    private val store: UnlockStore,
    val paywallEnabled: Boolean,
    private val scope: CoroutineScope,
) {
    private val _reason = MutableStateFlow(launchUnlockReason(store.reason, paywallEnabled, hasExistingVaults = false))
    val reason: StateFlow<UnlockReason?> = _reason.asStateFlow()
    private val _unlocked = MutableStateFlow(_reason.value != null)
    val isUnlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _price = MutableStateFlow<String?>(null)
    /** Play's localized price, or null until Play answers (callers fall back to the listed price). */
    val price: StateFlow<String?> = _price.asStateFlow()
    private val _purchase = MutableStateFlow<UnlockPurchase>(UnlockPurchase.Idle)
    val purchase: StateFlow<UnlockPurchase> = _purchase.asStateFlow()
    private val _restoring = MutableStateFlow(false)
    val restoring: StateFlow<Boolean> = _restoring.asStateFlow()

    @Volatile private var offer: UnlockOffer? = null

    init { gateway.setListener(::onUpdate) }

    /**
     * Called at launch with whether the catalog holds vaults. Only the first launch of a
     * paywall build can grandfather them: vaults added later (e.g. the sample) never unlock.
     */
    fun start(hasExistingVaults: Boolean) {
        val grandfather = hasExistingVaults && !store.grandfatheringChecked
        if (paywallEnabled) store.grandfatheringChecked = true
        setReason(launchUnlockReason(store.reason, paywallEnabled, grandfather))
        if (_reason.value == UnlockReason.PAYWALL_DISABLED) return
        scope.launch { loadOffer(); refresh() }
    }

    suspend fun refresh() {
        if (_reason.value == UnlockReason.PAYWALL_DISABLED) return
        setReason(nextUnlockReason(_reason.value, gateway.ownedProducts()))
    }

    suspend fun loadOffer(): UnlockOffer? = (offer ?: gateway.offer())?.also { offer = it; _price.value = it.formattedPrice }

    /** Opens Play's purchase sheet. The result arrives in [purchase] and [isUnlocked]. */
    suspend fun buy(activity: Activity) {
        if (_unlocked.value || _purchase.value == UnlockPurchase.Purchasing) return
        _purchase.value = UnlockPurchase.Purchasing
        val current = loadOffer()
        if (current == null || !gateway.launch(activity, current)) {
            _purchase.value = UnlockPurchase.Failed(UiText.res(R.string.paywall_error_store_unavailable))
        }
    }

    /** Asks Play again for earlier purchases; true when the app is unlocked afterwards. */
    suspend fun restore(): Boolean {
        _restoring.value = true
        try {
            val owned = gateway.ownedProducts()
            setReason(nextUnlockReason(_reason.value, owned))
            if (!_unlocked.value) _purchase.value = UnlockPurchase.Failed(UiText.res(
                if (owned == null) R.string.paywall_error_store_unavailable else R.string.paywall_restore_none))
            return _unlocked.value
        } finally { _restoring.value = false }
    }

    fun clearFailure() { if (_purchase.value is UnlockPurchase.Failed) _purchase.value = UnlockPurchase.Idle }

    private fun onUpdate(update: PurchaseUpdate) {
        when (update) {
            is PurchaseUpdate.Owned -> if (update.products.any { it in UnlockProducts.OWNED }) {
                _purchase.value = UnlockPurchase.Idle
                setReason(UnlockReason.PURCHASED)
            } else scope.launch { refresh(); if (_purchase.value == UnlockPurchase.Purchasing) _purchase.value = UnlockPurchase.Idle }
            PurchaseUpdate.Pending -> _purchase.value = UnlockPurchase.Pending
            PurchaseUpdate.Cancelled -> _purchase.value = UnlockPurchase.Idle
            is PurchaseUpdate.Failed -> _purchase.value = UnlockPurchase.Failed(UiText.res(R.string.paywall_error_purchase_failed))
        }
    }

    private fun setReason(value: UnlockReason?) {
        if (value != store.reason) store.reason = value
        _reason.value = value
        _unlocked.value = value != null
    }
}

package com.bontecou.syncmd.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The unlock product as Play offers it to this account. */
class UnlockOffer(val formattedPrice: String, internal val details: ProductDetails?, internal val offerToken: String?)

/** A purchase change Play reported outside a query. */
sealed interface PurchaseUpdate {
    data class Owned(val products: Set<String>) : PurchaseUpdate
    data object Pending : PurchaseUpdate
    data object Cancelled : PurchaseUpdate
    data class Failed(val code: Int) : PurchaseUpdate
}

/** The parts of Google Play Billing that [AppUnlock] needs; replaced by a fake in tests. */
interface BillingGateway {
    /** Products owned (PURCHASED, acknowledged here if needed), or null when Play is unreachable. */
    suspend fun ownedProducts(): Set<String>?
    suspend fun offer(): UnlockOffer?
    /** Opens Play's purchase sheet; false when it could not be shown. Results arrive through [setListener]. */
    fun launch(activity: Activity, offer: UnlockOffer): Boolean
    fun setListener(listener: (PurchaseUpdate) -> Unit)
}

class PlayBillingGateway(context: Context) : BillingGateway {
    @Volatile private var listener: (PurchaseUpdate) -> Unit = {}

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override fun setListener(listener: (PurchaseUpdate) -> Unit) { this.listener = listener }

    private suspend fun connected(): Boolean = client.isReady || suspendCancellableCoroutine { cont ->
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
            override fun onBillingServiceDisconnected() { if (cont.isActive) cont.resume(false) }
        })
    }

    override suspend fun ownedProducts(): Set<String>? {
        if (!connected()) return null
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        val purchases = suspendCancellableCoroutine<List<Purchase>?> { cont ->
            client.queryPurchasesAsync(params) { result, list ->
                if (cont.isActive) cont.resume(list.takeIf { result.responseCode == BillingClient.BillingResponseCode.OK })
            }
        } ?: return null
        val owned = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        // Play refunds a purchase that stays unacknowledged for three days.
        owned.filter { !it.isAcknowledged }.forEach { acknowledge(it) }
        return owned.flatMap { it.products }.toSet()
    }

    override suspend fun offer(): UnlockOffer? {
        if (!connected()) return null
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(UnlockProducts.FULL_ACCESS).setProductType(BillingClient.ProductType.INAPP).build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        val details = suspendCancellableCoroutine<ProductDetails?> { cont ->
            client.queryProductDetailsAsync(params) { result, response ->
                val found = response.productDetailsList.firstOrNull { it.productId == UnlockProducts.FULL_ACCESS }
                if (cont.isActive) cont.resume(found.takeIf { result.responseCode == BillingClient.BillingResponseCode.OK })
            }
        } ?: return null
        val option = details.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: details.oneTimePurchaseOfferDetails ?: return null
        return UnlockOffer(option.formattedPrice, details, option.offerToken)
    }

    override fun launch(activity: Activity, offer: UnlockOffer): Boolean {
        val details = offer.details ?: return false
        val product = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
            .apply { offer.offerToken?.let(::setOfferToken) }.build()
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(product)).build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        val update = when (result.responseCode) {
            BillingClient.BillingResponseCode.OK, BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                val list = purchases.orEmpty()
                val owned = list.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                owned.filter { !it.isAcknowledged }.forEach { purchase -> client.acknowledgePurchase(acknowledgement(purchase)) {} }
                when {
                    owned.isNotEmpty() -> PurchaseUpdate.Owned(owned.flatMap { it.products }.toSet())
                    list.any { it.purchaseState == Purchase.PurchaseState.PENDING } -> PurchaseUpdate.Pending
                    // Already owned but not in this callback: let the caller re-query.
                    else -> PurchaseUpdate.Owned(emptySet())
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseUpdate.Cancelled
            else -> PurchaseUpdate.Failed(result.responseCode)
        }
        listener(update)
    }

    private suspend fun acknowledge(purchase: Purchase) = suspendCancellableCoroutine { cont ->
        client.acknowledgePurchase(acknowledgement(purchase)) { if (cont.isActive) cont.resume(Unit) }
    }

    private fun acknowledgement(purchase: Purchase) =
        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
}

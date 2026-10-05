package com.gbxps.fasea

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google Play Billing wrapper for the two products:
 *  - [PRODUCT_MONTHLY]  auto-renewing subscription
 *  - [PRODUCT_LIFETIME] one-time (non-consumable) purchase
 *
 * [hasActivePurchase] is called from the WebView's JS thread, so state is kept
 * in thread-safe holders and the last known result is cached in SharedPreferences
 * so a paid user is unlocked immediately on a cold or offline start.
 */
class BillingManager(
    private val activity: Activity,
    private val listener: Listener,
) : PurchasesUpdatedListener {

    interface Listener {
        fun onEntitlementsChanged()
        fun onPurchaseComplete(productId: String)
        fun onPurchaseFailed(message: String?)
        fun onPricesLoaded(prices: Map<String, String>)
    }

    companion object {
        const val PRODUCT_MONTHLY = "fasea_monthly"
        const val PRODUCT_LIFETIME = "fasea_lifetime"
        val ALL_PRODUCTS = setOf(PRODUCT_MONTHLY, PRODUCT_LIFETIME)

        private const val PREFS = "fasea_prefs"
        private const val KEY_OWNED = "iap_owned_products"
    }

    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val owned: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(prefs.getStringSet(KEY_OWNED, emptySet()).orEmpty())
    }
    private val productDetails = ConcurrentHashMap<String, ProductDetails>()

    private val client = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun start() = withConnection { }

    fun hasActivePurchase(): Boolean = owned.isNotEmpty()

    /** The product that grants access, preferring lifetime over the subscription. */
    fun activeProductId(): String? =
        if (PRODUCT_LIFETIME in owned) PRODUCT_LIFETIME else owned.firstOrNull()

    fun launchPurchaseFlow(productId: String) {
        if (productId !in ALL_PRODUCTS) return
        withConnection {
            val details = productDetails[productId]
            if (details != null) launch(details)
            else queryProductDetails {
                productDetails[productId]?.let { launch(it) }
                    ?: listener.onPurchaseFailed("This product isn't available yet. Please try again later.")
            }
        }
    }

    fun restorePurchases() = withConnection { refreshPurchases() }

    fun endConnection() = client.endConnection()

    // ── Connection ────────────────────────────────────────────────────

    private fun withConnection(block: () -> Unit) {
        if (client.isReady) {
            block()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    refreshPurchases()
                    queryProductDetails()
                    block()
                } else {
                    listener.onPurchaseFailed("Google Play Billing is unavailable (${result.debugMessage}).")
                }
            }

            override fun onBillingServiceDisconnected() {
                // Reconnected lazily by the next call to withConnection().
            }
        })
    }

    // ── Products ──────────────────────────────────────────────────────

    private fun queryProductDetails(then: (() -> Unit)? = null) {
        val queries = listOf(
            BillingClient.ProductType.SUBS to PRODUCT_MONTHLY,
            BillingClient.ProductType.INAPP to PRODUCT_LIFETIME,
        )
        val remaining = AtomicInteger(queries.size)
        for ((type, id) in queries) {
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(id)
                            .setProductType(type)
                            .build()
                    )
                )
                .build()
            client.queryProductDetailsAsync(params) { result, queryResult ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryResult.productDetailsList.forEach { productDetails[it.productId] = it }
                }
                if (remaining.decrementAndGet() == 0) {
                    listener.onPricesLoaded(prices())
                    then?.let { activity.runOnUiThread(it) }
                }
            }
        }
    }

    private fun prices(): Map<String, String> = productDetails.mapNotNull { (id, d) ->
        val price = d.oneTimePurchaseOfferDetails?.formattedPrice
            ?: d.subscriptionOfferDetails?.firstOrNull()
                ?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
        price?.let { id to it }
    }.toMap()

    private fun launch(details: ProductDetails) {
        val paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
        details.subscriptionOfferDetails?.firstOrNull()?.offerToken?.let { paramsBuilder.setOfferToken(it) }
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(paramsBuilder.build()))
            .build()
        activity.runOnUiThread {
            val result = client.launchBillingFlow(activity, flowParams)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                listener.onPurchaseFailed("Couldn't start the purchase (${result.debugMessage}).")
            }
        }
    }

    // ── Purchases ─────────────────────────────────────────────────────

    /** Rebuilds [owned] from Play's record of active subscriptions and one-time purchases. */
    private fun refreshPurchases() {
        val found = ConcurrentHashMap.newKeySet<String>()
        val types = listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)
        val remaining = AtomicInteger(types.size)
        val failed = AtomicInteger(0)
        for (type in types) {
            val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
            client.queryPurchasesAsync(params) { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach {
                        found.addAll(it.products.filter { p -> p in ALL_PRODUCTS })
                        acknowledgeIfNeeded(it)
                    }
                } else {
                    failed.incrementAndGet()
                }
                // Only replace the cached state when both queries succeeded
                if (remaining.decrementAndGet() == 0 && failed.get() == 0) setOwned(found)
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach { purchase ->
                when (purchase.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> {
                        acknowledgeIfNeeded(purchase)
                        val ids = purchase.products.filter { it in ALL_PRODUCTS }
                        setOwned(owned + ids)
                        ids.firstOrNull()?.let { listener.onPurchaseComplete(it) }
                    }
                    Purchase.PurchaseState.PENDING ->
                        listener.onPurchaseFailed("Your payment is pending. Access unlocks once it completes.")
                    else -> Unit
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> listener.onPurchaseFailed(null)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refreshPurchases()
            else -> listener.onPurchaseFailed("Purchase failed (${result.debugMessage}).")
        }
    }

    // Unacknowledged purchases are refunded by Google Play after three days.
    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED || purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { }
    }

    private fun setOwned(ids: Set<String>) {
        val changed = ids != owned.toSet()
        owned.retainAll(ids)
        owned.addAll(ids)
        prefs.edit { putStringSet(KEY_OWNED, ids.toSet()) }
        if (changed) listener.onEntitlementsChanged()
    }
}

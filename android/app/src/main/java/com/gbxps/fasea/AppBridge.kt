package com.gbxps.fasea

import android.app.Activity
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.edit
import org.json.JSONObject

/**
 * The `window.AppBridge` object exposed to shared/index.html.
 * Keep the method names in step with the iOS AppBridge.swift and the
 * "PAYWALL & NATIVE BRIDGE" section of the HTML.
 *
 * @JavascriptInterface methods run on a WebView background thread, so anything
 * touching UI, ads or billing flows is posted to the main thread.
 */
class AppBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val billing: BillingManager,
    private val ads: AdManager,
) {
    companion object {
        private const val KEY_AD_UNLOCK_EXPIRES = "ad_unlock_expires"
        private const val AD_UNLOCK_MS = 24 * 60 * 60 * 1000L
    }

    private val prefs = activity.getSharedPreferences("fasea_prefs", Context.MODE_PRIVATE)

    private fun adUnlockExpires(): Long = prefs.getLong(KEY_AD_UNLOCK_EXPIRES, 0L)

    // ── JS → native ───────────────────────────────────────────────────

    @JavascriptInterface
    fun isUnlocked(): Boolean =
        billing.hasActivePurchase() || System.currentTimeMillis() < adUnlockExpires()

    @JavascriptInterface
    fun ready() {
        pushEntitlement()
        billing.start()
    }

    @JavascriptInterface
    fun showRewardedAd() = activity.runOnUiThread {
        ads.showRewardedAd(
            onReward = {
                val expires = System.currentTimeMillis() + AD_UNLOCK_MS
                prefs.edit { putLong(KEY_AD_UNLOCK_EXPIRES, expires) }
                callJs("grantAdUnlock", expires)
            },
            onUnavailable = { callJs("onAdUnavailable") },
        )
    }

    @JavascriptInterface
    fun showInterstitialAd() = activity.runOnUiThread {
        if (!isUnlocked()) ads.showInterstitialAd()
    }

    @JavascriptInterface
    fun purchaseProduct(productId: String) {
        if (productId in BillingManager.ALL_PRODUCTS) billing.launchPurchaseFlow(productId)
    }

    @JavascriptInterface
    fun restorePurchases() = billing.restorePurchases()

    // ── native → JS ───────────────────────────────────────────────────

    fun pushEntitlement() {
        val state = JSONObject()
            .put("purchased", billing.hasActivePurchase())
            .put("productId", billing.activeProductId() ?: JSONObject.NULL)
            .put("adUnlockExpires", adUnlockExpires())
        callJs("onEntitlementChanged", state)
    }

    fun onPurchaseComplete(productId: String) = callJs("onIAPComplete", productId)

    fun onPurchaseFailed(message: String?) = callJs("onPurchaseFailed", message)

    fun onPricesLoaded(prices: Map<String, String>) = callJs("onProductsLoaded", JSONObject(prices))

    /** Calls a global JS function if the page defines it; args are JSON-encoded. */
    private fun callJs(function: String, vararg args: Any?) {
        val argList = args.joinToString(",") { arg ->
            when (arg) {
                null -> "null"
                is JSONObject -> arg.toString()
                is String -> JSONObject.quote(arg)
                else -> arg.toString()
            }
        }
        val script = "typeof $function === 'function' && $function($argList);"
        webView.post { webView.evaluateJavascript(script, null) }
    }
}

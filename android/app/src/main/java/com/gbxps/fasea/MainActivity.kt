package com.gbxps.fasea

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : ComponentActivity() {

    companion object {
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$ASSET_HOST/assets/index.html"
    }

    private lateinit var web: WebView
    private lateinit var billingManager: BillingManager
    private lateinit var adManager: AdManager
    private lateinit var bridge: AppBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        billingManager = BillingManager(this, object : BillingManager.Listener {
            override fun onEntitlementsChanged() = bridge.pushEntitlement()
            override fun onPurchaseComplete(productId: String) = bridge.onPurchaseComplete(productId)
            override fun onPurchaseFailed(message: String?) = bridge.onPurchaseFailed(message)
            override fun onPricesLoaded(prices: Map<String, String>) = bridge.onPricesLoaded(prices)
        })
        adManager = AdManager(this)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            // Lets the HTML detect it is running inside the app
            userAgentString = "$userAgentString FASEATrainerApp/${BuildConfig.VERSION_NAME}"
        }

        bridge = AppBridge(this, web, billingManager, adManager)
        web.addJavascriptInterface(bridge, "AppBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            // Resource links (legislation, ASIC guides) and mailto: open outside the app
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == ASSET_HOST) return false
                openExternally(request.url)
                return true
            }

            // The WebView renderer was killed (e.g. low memory): rebuild the screen
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                recreate()
                return true
            }
        }

        // Keep content clear of the status and navigation bars (edge-to-edge is
        // enforced when targeting SDK 35+).
        val container = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.navy))
            addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(container)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                web,
                "window.__FASEA_APP_VERSION__='android-${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})';",
                setOf("https://$ASSET_HOST"),
            )
        }
        web.loadUrl(START_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Picks up purchases made or subscriptions cancelled outside the app
        billingManager.restorePurchases()
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
        }
    }

    override fun onDestroy() {
        web.destroy()
        billingManager.endConnection()
        super.onDestroy()
    }
}

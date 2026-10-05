# FASEA Financial Adviser Exam Trainer — Claude Code Session Handoff

## Project Overview

**Repo:** https://github.com/gbxcaillin/financialadviserexamtrainer  
**Owner:** gbxcaillin (GBX PS Pty Ltd)  
**Partners:** Pat Gray  
**Goal:** A cross-platform financial adviser exam trainer app targeting the FASEA ethics and compliance exam, monetised via a free tier, watch-ad-to-unlock (AdMob rewarded ads), and paid subscription/lifetime unlock via native IAP. Deploy to Google Play Store and Apple App Store.

---

## What Has Been Built (Prior Sessions)

### Question Bank
- **File:** `FASEA_Question_Bank_v12.xlsx`
- **Standard Questions tab:** 397 questions across 19 sections, all corrected and balanced (correct answer within ~35% of average option length)
- **Applied Values & Investment tab:** 33 new scenario-based questions (18 Applied Values + 15 Investment Concepts), real-exam style matching the ASIC practice exam format
- **Other tabs in the xlsx (not yet loaded into app):** Value Questions (130q), True/False (134q), Secondary Values (179q), Complex Questions (55q), Scenario Questions (134q)
- **Total questions across all tabs in the live Google Sheet:** 1,062

### HTML App (ExamDesktop / ExamApp)
- **Desktop repo:** https://github.com/gbxcaillin/ExamDesktop — live at gbxcaillin.github.io/ExamDesktop
- **Mobile repo:** https://github.com/gbxcaillin/ExamApp — live at gbxcaillin.github.io/ExamApp
- **Feature branch:** `feature/v2-question-bank` on ExamDesktop — has all 1,062 questions embedded, no Google Sheets fetch on load
- **Commit:** 393a352614be65513dcc70956bf8d9c6366e2cf3
- **Main branch is untouched**

### Android Build
- **File:** `Android_Financial_Adviser_Exam_Trainer.zip` (existing build)
- **Framework:** Kotlin WebView wrapper (NOT React Native, NOT Flutter)
- **Package:** `com.gbxps.fasea`
- **How it works:** `MainActivity.kt` loads a bundled HTML file (`fasea_mobile_android_v21.html`) via `WebViewAssetLoader` using local asset URL `https://appassets.androidplatform.net/assets/fasea_mobile_android_v21.html`
- **What it has:** WebView, back-press handling, DOM storage enabled, JavaScript enabled
- **What it does NOT have:** IAP, AdMob, paywall logic, any native bridge to JS

### Key Design Decisions Made
1. Keep the WebView wrapper approach (do not rebuild in React Native)
2. Add a native `JavascriptInterface` bridge (Kotlin for Android, WKScriptMessageHandler for iOS) that the HTML calls to check unlock status
3. Gate question access in JavaScript via `window.AppBridge.isUnlocked()`
4. AdMob rewarded ads rendered as a native overlay, completion callback passed back to WebView
5. Free tier: 50 questions, practice only, ads between sessions
6. Watch ad: 24-hour full unlock
7. Paid: monthly sub (~$9.99 AUD) or lifetime unlock (~$29.99 AUD) via Google Play Billing / Apple IAP
8. Google Play Billing and Apple StoreKit are the only permitted payment mechanisms for digital goods in-app

---

## Architecture for New Repo

### Recommended Structure

```
financialadviserexamtrainer/
├── android/                    # Android Studio project (Kotlin)
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/gbxps/fasea/
│   │   │   │   ├── MainActivity.kt          # WebView host, JS bridge registration
│   │   │   │   ├── AppBridge.kt             # JavascriptInterface — IAP + unlock checks
│   │   │   │   ├── BillingManager.kt        # Google Play Billing Library wrapper
│   │   │   │   └── AdManager.kt             # AdMob rewarded ad loader/presenter
│   │   │   ├── assets/
│   │   │   │   └── index.html               # The full HTML app (1,062 questions embedded)
│   │   │   ├── res/
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
│   └── build.gradle.kts
│
├── ios/                        # Xcode project (Swift)
│   ├── FinancialAdviserExamTrainer/
│   │   ├── AppDelegate.swift
│   │   ├── ViewController.swift             # WKWebView host
│   │   ├── AppBridge.swift                  # WKScriptMessageHandler — IAP + unlock
│   │   ├── StoreManager.swift               # StoreKit 2 wrapper
│   │   ├── AdManager.swift                  # AdMob rewarded ad presenter
│   │   └── Resources/
│   │       └── index.html                   # Same HTML file as Android
│   └── FinancialAdviserExamTrainer.xcodeproj
│
├── shared/
│   └── index.html              # Master HTML app — source of truth
│       (copied to android/app/src/main/assets/ and ios/.../Resources/)
│
├── questions/
│   ├── FASEA_Question_Bank_v12.xlsx    # Master question bank
│   └── README.md                        # Column definitions and section list
│
└── README.md
```

---

## The HTML App (shared/index.html)

The HTML file is the entire app. It runs identically on both platforms via WebView. It needs to be updated to include the paywall bridge calls.

### Current State of the HTML
- Loads 1,062 questions from embedded JSON (compact keys: s/q/o/c/e/t/sk/sc, normalised on load)
- Practice Questions engine: `startPracticeSession`, `renderPQ`, `pPick`, `pNav`
- Timed Exam engine: `startExam`, `doShowResults`, `buildReviewList`
- Stats persistence via `localStorage` (streak, exams done, top score)
- `switchTab`, `togSec` for navigation
- Section filtering wired to practice session pool
- Branding: Clime / GBX PS navy + action orange colour scheme

### Paywall Logic to Add to HTML

The HTML needs to call the native bridge to check unlock status before serving questions. Add this to the JS:

```javascript
// ── PAYWALL BRIDGE ────────────────────────────────────────────────
// Called by the HTML to check if the user has full access.
// Returns true if: (1) native IAP says purchased, or (2) a 24hr ad unlock token is active.
// On web (no bridge), defaults to free tier.

const FREE_TIER_LIMIT = 50; // questions accessible without unlock

function isUnlocked() {
  try {
    // Android bridge
    if (window.AppBridge && typeof window.AppBridge.isUnlocked === 'function') {
      return window.AppBridge.isUnlocked() === true ||
             window.AppBridge.isUnlocked() === 'true';
    }
    // iOS bridge (webkit message handler uses async pattern — check localStorage token instead)
    const token = localStorage.getItem('fasea_unlock_token');
    if (token) {
      const t = JSON.parse(token);
      if (t.expires && Date.now() < t.expires) return true;
    }
    return false;
  } catch(e) { return false; }
}

function requestAdUnlock() {
  try {
    if (window.AppBridge && typeof window.AppBridge.showRewardedAd === 'function') {
      window.AppBridge.showRewardedAd(); // Android — native shows ad, calls onAdComplete on finish
    } else if (window.webkit && window.webkit.messageHandlers.AppBridge) {
      window.webkit.messageHandlers.AppBridge.postMessage({ action: 'showRewardedAd' });
    } else {
      // Dev/web fallback: simulate unlock for testing
      grantAdUnlock();
    }
  } catch(e) { console.error('Ad unlock request failed', e); }
}

function grantAdUnlock() {
  // Called by native layer after ad completes (Android: via evaluateJavascript, iOS: via evaluateJavaScript)
  const expires = Date.now() + (24 * 60 * 60 * 1000); // 24 hours
  localStorage.setItem('fasea_unlock_token', JSON.stringify({ expires }));
  renderPaywallState();
  // Resume whatever the user was doing
  if (typeof pendingActionAfterUnlock === 'function') pendingActionAfterUnlock();
}

function requestIAPUnlock(productId) {
  // productId: 'fasea_monthly' or 'fasea_lifetime'
  try {
    if (window.AppBridge && typeof window.AppBridge.purchaseProduct === 'function') {
      window.AppBridge.purchaseProduct(productId);
    } else if (window.webkit && window.webkit.messageHandlers.AppBridge) {
      window.webkit.messageHandlers.AppBridge.postMessage({ action: 'purchase', productId });
    }
  } catch(e) { console.error('IAP request failed', e); }
}

function onIAPComplete(productId) {
  // Called by native layer after successful purchase verification
  localStorage.setItem('fasea_iap_product', productId);
  localStorage.setItem('fasea_iap_purchased', 'true');
  renderPaywallState();
  if (typeof pendingActionAfterUnlock === 'function') pendingActionAfterUnlock();
}

function renderPaywallState() {
  const unlocked = isUnlocked();
  const paywallEl = document.getElementById('paywall-overlay');
  if (paywallEl) paywallEl.style.display = unlocked ? 'none' : 'flex';
}
```

### Paywall Gate (add before serving questions beyond the free tier)

In `startPracticeSession` and `startExam`, check before starting:

```javascript
function checkUnlockBeforeStart(questionsRequested, action) {
  if (isUnlocked() || questionsRequested <= FREE_TIER_LIMIT) {
    action(); // proceed
  } else {
    pendingActionAfterUnlock = action;
    showPaywallScreen();
  }
}
```

### Paywall Screen HTML to add

```html
<div id="paywall-overlay" style="display:none; position:fixed; inset:0; background:rgba(0,29,52,0.96);
  z-index:9999; flex-direction:column; align-items:center; justify-content:center; padding:32px;">
  <div style="max-width:360px; text-align:center; color:#fff;">
    <div style="font-size:32px; margin-bottom:16px;">🔒</div>
    <h2 style="font-size:22px; font-weight:700; margin-bottom:8px;">Unlock Full Access</h2>
    <p style="font-size:14px; color:rgba(255,255,255,0.6); margin-bottom:28px; line-height:1.6;">
      The free tier includes 50 practice questions. Unlock all 1,062 questions and all exam modes.
    </p>
    <button onclick="requestAdUnlock()" style="width:100%; background:#EC5C3B; color:#fff; border:none;
      border-radius:10px; padding:14px; font-size:15px; font-weight:700; margin-bottom:12px; cursor:pointer;">
      Watch an ad — unlock for 24 hours
    </button>
    <button onclick="requestIAPUnlock('fasea_monthly')" style="width:100%; background:rgba(255,255,255,0.1);
      color:#fff; border:1px solid rgba(255,255,255,0.2); border-radius:10px; padding:14px;
      font-size:15px; font-weight:700; margin-bottom:12px; cursor:pointer;">
      Subscribe — $9.99 / month
    </button>
    <button onclick="requestIAPUnlock('fasea_lifetime')" style="width:100%; background:rgba(255,255,255,0.1);
      color:#fff; border:1px solid rgba(255,255,255,0.2); border-radius:10px; padding:14px;
      font-size:15px; font-weight:600; cursor:pointer;">
      Buy once — $29.99 lifetime
    </button>
    <button onclick="document.getElementById('paywall-overlay').style.display='none'"
      style="margin-top:16px; background:none; border:none; color:rgba(255,255,255,0.35);
      font-size:13px; cursor:pointer;">
      Continue with free tier
    </button>
  </div>
</div>
```

---

## Android — Native Bridge (Kotlin)

### AppBridge.kt

```kotlin
package com.gbxps.fasea

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView

class AppBridge(
    private val context: Context,
    private val webView: WebView,
    private val billingManager: BillingManager,
    private val adManager: AdManager
) {

    @JavascriptInterface
    fun isUnlocked(): Boolean {
        // Check IAP purchase status first
        if (billingManager.hasActivePurchase()) return true
        // Check 24hr ad unlock token in SharedPreferences
        val prefs = context.getSharedPreferences("fasea_prefs", Context.MODE_PRIVATE)
        val expires = prefs.getLong("ad_unlock_expires", 0L)
        return System.currentTimeMillis() < expires
    }

    @JavascriptInterface
    fun showRewardedAd() {
        adManager.showRewardedAd(
            onAdComplete = {
                // Grant 24hr token
                val prefs = context.getSharedPreferences("fasea_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong("ad_unlock_expires", System.currentTimeMillis() + 86_400_000L)
                    .apply()
                // Call back into JS
                webView.post {
                    webView.evaluateJavascript("grantAdUnlock();", null)
                }
            }
        )
    }

    @JavascriptInterface
    fun purchaseProduct(productId: String) {
        billingManager.launchPurchaseFlow(productId)
        // billingManager calls onPurchaseComplete -> evaluateJavascript("onIAPComplete('$productId');")
    }

    @JavascriptInterface
    fun restorePurchases() {
        billingManager.restorePurchases()
    }
}
```

### MainActivity.kt (updated)

```kotlin
package com.gbxps.fasea

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.webkit.WebViewAssetLoader

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var billingManager: BillingManager
    private lateinit var adManager: AdManager

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        billingManager = BillingManager(this) { productId ->
            web.post { web.evaluateJavascript("onIAPComplete('$productId');", null) }
        }
        adManager = AdManager(this)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // Register the native bridge
        val bridge = AppBridge(this, web, billingManager, adManager)
        web.addJavascriptInterface(bridge, "AppBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
        }

        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        setContentView(web)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
    }

    override fun onDestroy() {
        web.destroy()
        billingManager.endConnection()
        super.onDestroy()
    }
}
```

### BillingManager.kt

```kotlin
package com.gbxps.fasea

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*

class BillingManager(
    private val activity: Activity,
    private val onPurchaseComplete: (productId: String) -> Unit
) : PurchasesUpdatedListener {

    private val billingClient = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    private val purchasedProductIds = mutableSetOf<String>()

    init {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    restorePurchases()
                }
            }
            override fun onBillingServiceDisconnected() {
                // Retry connection as needed
            }
        })
    }

    fun hasActivePurchase(): Boolean = purchasedProductIds.isNotEmpty()

    fun launchPurchaseFlow(productId: String) {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(
                    if (productId == "fasea_lifetime") BillingClient.ProductType.INAPP
                    else BillingClient.ProductType.SUBS
                )
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK &&
                productDetailsList.isNotEmpty()) {
                val productDetails = productDetailsList[0]
                val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
                val productDetailsParamsList = listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .apply { offerToken?.let { setOfferToken(it) } }
                        .build()
                )
                val billingFlowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(productDetailsParamsList)
                    .build()
                billingClient.launchBillingFlow(activity, billingFlowParams)
            }
        }
    }

    fun restorePurchases() {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { _, purchases ->
            purchases.forEach { purchase ->
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    purchasedProductIds.addAll(purchase.products)
                    if (!purchase.isAcknowledged) acknowledgePurchase(purchase)
                }
            }
        }
        // Also check one-time purchases
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { _, purchases ->
            purchases.forEach { purchase ->
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    purchasedProductIds.addAll(purchase.products)
                }
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { purchase ->
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    purchasedProductIds.addAll(purchase.products)
                    if (!purchase.isAcknowledged) acknowledgePurchase(purchase)
                    purchase.products.firstOrNull()?.let { onPurchaseComplete(it) }
                }
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { }
    }

    fun endConnection() { billingClient.endConnection() }
}
```

### AdManager.kt

```kotlin
package com.gbxps.fasea

import android.app.Activity
import com.google.android.gms.ads.*
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

class AdManager(private val activity: Activity) {

    // Replace with real AdMob unit ID from your AdMob account
    private val REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917" // test ID

    private var rewardedAd: RewardedAd? = null
    private var pendingCallback: (() -> Unit)? = null

    init {
        MobileAds.initialize(activity)
        loadRewardedAd()
    }

    private fun loadRewardedAd() {
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(activity, REWARDED_AD_UNIT_ID, adRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                }
            })
    }

    fun showRewardedAd(onAdComplete: () -> Unit) {
        val ad = rewardedAd
        if (ad != null) {
            pendingCallback = onAdComplete
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    rewardedAd = null
                    loadRewardedAd() // preload next
                }
            }
            ad.show(activity) { _ ->
                // User earned reward
                onAdComplete()
                pendingCallback = null
            }
        } else {
            // Ad not loaded yet — grant unlock anyway in dev, show error in prod
            onAdComplete()
            loadRewardedAd()
        }
    }
}
```

### build.gradle.kts (app level — add these dependencies)

```kotlin
dependencies {
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.android.material:material:1.12.0")
    // Google Play Billing
    implementation("com.android.billingclient:billing-ktx:7.0.0")
    // AdMob
    implementation("com.google.android.gms:play-services-ads:23.0.0")
}
```

### AndroidManifest.xml additions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="com.android.vending.BILLING" />

<application ...>
    <!-- AdMob App ID — replace with your real ID from AdMob console -->
    <meta-data
        android:name="com.google.android.gms.ads.APPLICATION_ID"
        android:value="ca-app-pub-xxxxxxxxxxxxxxxx~yyyyyyyyyy"/>
</application>
```

---

## iOS — Native Bridge (Swift)

### ViewController.swift

```swift
import UIKit
import WebKit

class ViewController: UIViewController, WKScriptMessageHandler {

    var webView: WKWebView!
    let storeManager = StoreManager.shared
    let adManager = AdManager()

    override func viewDidLoad() {
        super.viewDidLoad()

        let config = WKWebViewConfiguration()
        config.userContentController.add(self, name: "AppBridge")
        // Allow localStorage
        config.preferences.javaScriptEnabled = true

        webView = WKWebView(frame: view.bounds, configuration: config)
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(webView)

        if let url = Bundle.main.url(forResource: "index", withExtension: "html") {
            webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        }

        // Inject unlock status on load
        webView.evaluateJavaScript("window._iosUnlocked = \(isUnlocked());")
    }

    // Handle messages from JS: window.webkit.messageHandlers.AppBridge.postMessage(...)
    func userContentController(_ userContentController: WKUserContentController,
                                didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any],
              let action = body["action"] as? String else { return }

        switch action {
        case "isUnlocked":
            webView.evaluateJavaScript("window._iosUnlocked = \(isUnlocked());")
        case "showRewardedAd":
            adManager.showRewardedAd(from: self) { [weak self] in
                self?.grantAdUnlock()
            }
        case "purchase":
            if let productId = body["productId"] as? String {
                Task { await storeManager.purchase(productId: productId) { [weak self] in
                    self?.webView.evaluateJavaScript("onIAPComplete('\(productId)');")
                }}
            }
        case "restorePurchases":
            Task { await storeManager.restorePurchases() }
        default: break
        }
    }

    func isUnlocked() -> Bool {
        if storeManager.hasActivePurchase() { return true }
        if let expires = UserDefaults.standard.object(forKey: "fasea_ad_unlock_expires") as? Double {
            return Date().timeIntervalSince1970 < expires
        }
        return false
    }

    func grantAdUnlock() {
        let expires = Date().timeIntervalSince1970 + 86400 // 24 hours
        UserDefaults.standard.set(expires, forKey: "fasea_ad_unlock_expires")
        webView.evaluateJavaScript("grantAdUnlock();")
    }
}
```

### StoreManager.swift (StoreKit 2)

```swift
import StoreKit

@MainActor
class StoreManager: ObservableObject {
    static let shared = StoreManager()

    private var purchasedProductIds = Set<String>()
    private var transactionUpdates: Task<Void, Never>?

    init() {
        transactionUpdates = Task {
            for await result in Transaction.updates {
                if case .verified(let transaction) = result {
                    purchasedProductIds.insert(transaction.productID)
                    await transaction.finish()
                }
            }
        }
        Task { await restorePurchases() }
    }

    func hasActivePurchase() -> Bool { !purchasedProductIds.isEmpty }

    func purchase(productId: String, onComplete: @escaping () -> Void) async {
        do {
            let products = try await Product.products(for: [productId])
            guard let product = products.first else { return }
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                if case .verified(let transaction) = verification {
                    purchasedProductIds.insert(transaction.productID)
                    await transaction.finish()
                    onComplete()
                }
            default: break
            }
        } catch { print("Purchase error: \(error)") }
    }

    func restorePurchases() async {
        for await result in Transaction.currentEntitlements {
            if case .verified(let transaction) = result {
                purchasedProductIds.insert(transaction.productID)
            }
        }
    }
}
```

---

## Product IDs for App Stores

Set these up in App Store Connect and Google Play Console before building:

| Product | Type | ID | Price |
|---|---|---|---|
| Monthly subscription | Subscription | `fasea_monthly` | $9.99 AUD |
| Lifetime unlock | One-time | `fasea_lifetime` | $29.99 AUD |

---

## Question Bank — Section Reference

All 1,062 questions across the following sections:

| Section | Count | Source Tab |
|---|---|---|
| Ethics & Corps Act | 72 | Standard |
| Behavioural Finance | 77 | Standard |
| Privacy & AML/CTF | 47 | Standard |
| RG 104 – Licensee Obligations | 24 | Standard |
| SOA/FDS/FSG Obligations | 29 | Standard |
| Corporations Act | 27 | Standard |
| Mixed – Advanced | 26 | Standard |
| Conflicted Remuneration | 5 | Standard |
| Scaled Advice | 6 | Standard |
| General vs Personal Advice | 9 | Standard |
| Complaints & Disputes | 7 | Standard |
| TASA | 6 | Standard |
| DDO & TMD | 5 | Standard |
| Insurance Advice | 7 | Standard |
| Superannuation | 9 | Standard |
| Estate Planning | 5 | Standard |
| MISCONCEPTION TRAP | 13 | Standard |
| Scope of Advice | 20 | Standard |
| Client Scenarios | 3 | Standard |
| Applied Values | 18 | Applied Values & Investment tab |
| Investment Concepts | 15 | Applied Values & Investment tab |
| True/False | 134 | TrueFalse tab |
| Code Values | 304 | Values + SecondaryValues tabs |
| Complex Questions | 55 | Complex tab |
| Scenario Questions | 134 | Scenario tab |

The compact JSON format (used in the embedded HTML) uses these keys:
- `s` = section
- `q` = question text
- `o` = options array [A, B, C, D]
- `c` = correct answer index (0-3)
- `e` = explanation text (✓/✗ per-option format)
- `t` = type (omitted when MCQ, present for TrueFalse/Values/Applied/Scenario/Complex)
- `sk` = scenario key (Scenario questions only)
- `sc` = scenario narrative text (Scenario questions only)

A normaliser function expands these back to full keys on load.

---

## What Claude Code Should Build First

### Priority 1 — Android (existing build to upgrade)

Starting point is the existing `Android_Financial_Adviser_Exam_Trainer.zip`. The Kotlin source structure is clean and minimal:

1. Add `AppBridge.kt` with `@JavascriptInterface` methods: `isUnlocked()`, `showRewardedAd()`, `purchaseProduct(productId)`, `restorePurchases()`
2. Add `BillingManager.kt` using Google Play Billing Library 7.x
3. Add `AdManager.kt` using AdMob SDK (use test ad unit IDs until live)
4. Update `MainActivity.kt` to register the bridge and wire up managers
5. Update `build.gradle.kts` to add billing and AdMob dependencies
6. Update `AndroidManifest.xml` to add BILLING permission and AdMob app ID
7. Replace `fasea_mobile_android_v21.html` with the updated `index.html` from the feature branch (with paywall JS added)
8. Test on emulator: free tier limit, ad unlock flow, IAP flow

### Priority 2 — HTML Paywall Integration

Update `shared/index.html` (sourced from the `feature/v2-question-bank` branch):

1. Add the paywall JS functions (`isUnlocked`, `requestAdUnlock`, `grantAdUnlock`, `requestIAPUnlock`, `onIAPComplete`, `renderPaywallState`)
2. Add paywall overlay HTML
3. Wire `checkUnlockBeforeStart` into `startPracticeSession` and `startExam`
4. Test that free tier (50 questions) works without any bridge present

### Priority 3 — iOS

1. Create new Xcode project: `FinancialAdviserExamTrainer`, Swift, minimum iOS 16
2. Add `WKWebView` in `ViewController.swift`, load `index.html` from bundle
3. Add `WKScriptMessageHandler` as the AppBridge
4. Add `StoreManager.swift` using StoreKit 2
5. Add `AdManager.swift` using AdMob (GADRewardedAd)
6. Wire up the same JS bridge protocol as Android

### Priority 4 — Store submission prep

- App icons (1024x1024 for iOS, adaptive icon for Android)
- Screenshots (6.5" iPhone, 12.9" iPad, Android phone)
- Store descriptions
- Privacy policy URL (required by both stores for any app that collects data or uses ads)
- AdMob account setup + real ad unit IDs
- Google Play Console setup + test track upload
- App Store Connect setup + TestFlight

---

## Key Files to Bring Into New Repo

| File | Source | Destination |
|---|---|---|
| `FASEA_Question_Bank_v12.xlsx` | Session outputs | `questions/` |
| `index.html` (1,062q version) | ExamDesktop `feature/v2-question-bank` | `shared/` |
| Android source files | `Android_Financial_Adviser_Exam_Trainer.zip` | `android/` |
| This session document | Session outputs | Repo root |

The ExamDesktop feature branch content can be fetched via:
```
https://raw.githubusercontent.com/gbxcaillin/ExamDesktop/feature/v2-question-bank/index.html
```

---

## Notes for Claude Code

- Do not push anything to the `main` branch of ExamDesktop — all existing HTML work lives on `feature/v2-question-bank`
- The new repo `financialadviserexamtrainer` is the clean start
- The Android package name stays `com.gbxps.fasea` for Play Store continuity
- Use test ad unit IDs from AdMob during development (`ca-app-pub-3940256099942544/5224354917` for rewarded video)
- The IAP product IDs (`fasea_monthly`, `fasea_lifetime`) must be created in Play Console and App Store Connect before they will work on device
- The HTML runs on both platforms from the same file — any changes to the app UI/logic go in `shared/index.html` and are then copied to both native asset folders
- localStorage works in WebView on both platforms with `domStorageEnabled = true` (Android) and `WKWebViewConfiguration` defaults (iOS) — stats persistence will carry over from the existing Android build if the same WebView storage is retained

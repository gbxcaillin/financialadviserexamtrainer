import UIKit
import WebKit

/// Hosts the shared web app and implements the `AppBridge` message handler
/// that index.html talks to. Keep the actions in step with the Android
/// AppBridge.kt and the "PAYWALL & NATIVE BRIDGE" section of the HTML.
///
/// JS → native: `window.webkit.messageHandlers.AppBridge.postMessage({action, productId})`
///   actions: ready, showRewardedAd, showInterstitialAd, purchaseProduct, restorePurchases
/// native → JS: onEntitlementChanged, grantAdUnlock, onIAPComplete, onProductsLoaded,
///   onAdUnavailable, onPurchaseFailed
final class AppBridge: NSObject, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {

    private static let adUnlockKey = "fasea_ad_unlock_expires"   // milliseconds since 1970
    private static let adUnlockDuration: TimeInterval = 24 * 60 * 60

    private weak var webView: WKWebView?
    private let store = StoreManager.shared
    private let ads = AdManager.shared

    private var adUnlockExpiresMs: Double {
        get { UserDefaults.standard.double(forKey: Self.adUnlockKey) }
        set { UserDefaults.standard.set(newValue, forKey: Self.adUnlockKey) }
    }

    private var isUnlocked: Bool {
        store.hasActivePurchase || Date().timeIntervalSince1970 * 1000 < adUnlockExpiresMs
    }

    func makeWebView() -> WKWebView {
        let contentController = WKUserContentController()
        // WKUserContentController retains its handlers, so register through a weak proxy
        contentController.add(WeakScriptMessageHandler(self), name: "AppBridge")
        // Entitlement and version are available before the page's scripts run
        contentController.addUserScript(WKUserScript(
            source: "window.__FASEA_ENTITLEMENT__ = \(entitlementJSON());" +
                    "window.__FASEA_APP_VERSION__ = \(Self.jsonLiteral(Self.appVersion));",
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true))

        let config = WKWebViewConfiguration()
        config.userContentController = contentController
        config.applicationNameForUserAgent = "FASEATrainerApp/\(Self.shortVersion)"
        let prefs = WKWebpagePreferences()
        prefs.allowsContentJavaScript = true
        config.defaultWebpagePreferences = prefs

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = false
        webView.scrollView.bounces = false
        webView.scrollView.isScrollEnabled = false
        webView.isOpaque = false
        webView.backgroundColor = UIColor(red: 0, green: 29 / 255, blue: 52 / 255, alpha: 1)
        self.webView = webView

        if let url = Bundle.main.url(forResource: "index", withExtension: "html") {
            webView.loadFileURL(url, allowingReadAccessTo: url.deletingLastPathComponent())
        }

        store.onChange = { [weak self] in
            self?.pushEntitlement()
            self?.pushPrices()
        }
        ads.start()
        return webView
    }

    // MARK: JS → native

    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any],
              let action = body["action"] as? String else { return }

        switch action {
        case "ready":
            pushEntitlement()
            pushPrices()

        case "showRewardedAd":
            guard let presenter = topViewController() else { return }
            ads.showRewardedAd(from: presenter, onReward: { [weak self] in
                self?.grantAdUnlock()
            }, onUnavailable: { [weak self] in
                self?.callJS("onAdUnavailable")
            })

        case "showInterstitialAd":
            if !isUnlocked, let presenter = topViewController() {
                ads.showInterstitialAd(from: presenter)
            }

        case "purchaseProduct":
            guard let productID = body["productId"] as? String,
                  StoreManager.productIDs.contains(productID) else { return }
            Task {
                switch await store.purchase(productID) {
                case .purchased(let id):
                    callJS("onIAPComplete", id)
                case .pending:
                    callJS("onPurchaseFailed", "Your purchase is pending approval. Access unlocks once it completes.")
                case .cancelled:
                    break
                case .failed(let message):
                    callJS("onPurchaseFailed", message)
                }
            }

        case "restorePurchases":
            Task {
                let restored = await store.restore()
                pushEntitlement()
                if !restored { callJS("onPurchaseFailed", "No previous purchases were found for this Apple ID.") }
            }

        default:
            break
        }
    }

    // MARK: native → JS

    private func grantAdUnlock() {
        let expires = (Date().timeIntervalSince1970 + Self.adUnlockDuration) * 1000
        adUnlockExpiresMs = expires
        callJS("grantAdUnlock", expires)
    }

    private func pushEntitlement() {
        callJSRaw("onEntitlementChanged", entitlementJSON())
    }

    private func pushPrices() {
        let prices = store.displayPrices()
        guard !prices.isEmpty,
              let data = try? JSONSerialization.data(withJSONObject: prices),
              let json = String(data: data, encoding: .utf8) else { return }
        callJSRaw("onProductsLoaded", json)
    }

    private func entitlementJSON() -> String {
        let state: [String: Any] = [
            "purchased": store.hasActivePurchase,
            "productId": store.activeProductID ?? NSNull(),
            "adUnlockExpires": adUnlockExpiresMs,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: state),
              let json = String(data: data, encoding: .utf8) else { return "{}" }
        return json
    }

    /// Calls a global JS function if the page defines it; arguments are JSON-encoded.
    private func callJS(_ function: String, _ args: Any...) {
        callJSRaw(function, args.map(Self.jsonLiteral).joined(separator: ","))
    }

    private func callJSRaw(_ function: String, _ argsJSON: String) {
        webView?.evaluateJavaScript("typeof \(function) === 'function' && \(function)(\(argsJSON));")
    }

    private static func jsonLiteral(_ value: Any) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: .fragmentsAllowed),
              let json = String(data: data, encoding: .utf8) else { return "null" }
        return json
    }

    // MARK: Navigation — resource links and mailto: open outside the app

    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = navigationAction.request.url, !url.isFileURL {
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
        } else {
            decisionHandler(.allow)
        }
    }

    // target="_blank" links
    func webView(_ webView: WKWebView,
                 createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = navigationAction.request.url { UIApplication.shared.open(url) }
        return nil
    }

    // The web content process was terminated (e.g. memory pressure): reload
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        webView.reload()
    }

    // MARK: Helpers

    private func topViewController() -> UIViewController? {
        var top = webView?.window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }

    private static var shortVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
    }

    private static var appVersion: String {
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "0"
        return "ios-\(shortVersion) (\(build))"
    }
}

/// Breaks the retain cycle between WKUserContentController and the bridge.
private final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    private weak var target: WKScriptMessageHandler?

    init(_ target: WKScriptMessageHandler) {
        self.target = target
    }

    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        target?.userContentController(userContentController, didReceive: message)
    }
}

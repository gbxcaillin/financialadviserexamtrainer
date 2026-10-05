import GoogleMobileAds
import UIKit

/// AdMob rewarded (24-hour unlock) and interstitial (between free practice sessions) ads.
/// Ad unit IDs are read from Info.plist; the committed values are Google's test IDs.
final class AdManager: NSObject, FullScreenContentDelegate {
    static let shared = AdManager()

    private static let interstitialMinInterval: TimeInterval = 3 * 60

    private let rewardedUnitID = AdManager.infoString("FASEARewardedAdUnitID",
                                                      fallback: "ca-app-pub-3940256099942544/1712485313")
    private let interstitialUnitID = AdManager.infoString("FASEAInterstitialAdUnitID",
                                                          fallback: "ca-app-pub-3940256099942544/4411468910")

    private var started = false
    private var rewardedAd: RewardedAd?
    private var interstitialAd: InterstitialAd?
    private var loadingRewarded = false
    private var loadingInterstitial = false
    private var lastInterstitial = Date.distantPast

    func start() {
        guard !started else { return }
        started = true
        MobileAds.shared.start(completionHandler: nil)
        loadRewardedAd()
        loadInterstitialAd()
    }

    private func loadRewardedAd() {
        guard rewardedAd == nil, !loadingRewarded else { return }
        loadingRewarded = true
        Task {
            let ad = try? await RewardedAd.load(with: rewardedUnitID, request: Request())
            ad?.fullScreenContentDelegate = self
            rewardedAd = ad
            loadingRewarded = false
        }
    }

    private func loadInterstitialAd() {
        guard interstitialAd == nil, !loadingInterstitial else { return }
        loadingInterstitial = true
        Task {
            let ad = try? await InterstitialAd.load(with: interstitialUnitID, request: Request())
            ad?.fullScreenContentDelegate = self
            interstitialAd = ad
            loadingInterstitial = false
        }
    }

    /// Shows a rewarded ad; `onReward` runs only if the user earned the reward.
    func showRewardedAd(from viewController: UIViewController,
                        onReward: @escaping () -> Void,
                        onUnavailable: @escaping () -> Void) {
        guard let ad = rewardedAd else {
            loadRewardedAd()
            #if DEBUG
            onReward()   // Debug builds unlock anyway so the flow can be tested without fill
            #else
            onUnavailable()
            #endif
            return
        }
        rewardedAd = nil
        ad.present(from: viewController) {
            onReward()
        }
    }

    /// Shows an interstitial if one is loaded and the frequency cap allows it.
    func showInterstitialAd(from viewController: UIViewController) {
        guard let ad = interstitialAd else {
            loadInterstitialAd()
            return
        }
        guard Date().timeIntervalSince(lastInterstitial) >= Self.interstitialMinInterval else { return }
        interstitialAd = nil
        lastInterstitial = Date()
        ad.present(from: viewController)
    }

    // MARK: FullScreenContentDelegate — preload the next ad

    func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) {
        loadRewardedAd()
        loadInterstitialAd()
    }

    func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
        loadRewardedAd()
        loadInterstitialAd()
    }

    private static func infoString(_ key: String, fallback: String) -> String {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String).flatMap { $0.isEmpty ? nil : $0 } ?? fallback
    }
}

# Financial Adviser Exam Trainer

FASEA ethics and compliance exam trainer for Android (Google Play) and iOS (App Store).
Both apps are thin native shells around one shared web app. Monetisation is a free tier,
a watch-an-ad 24-hour unlock (AdMob) and paid access through the platform stores.

```
shared/index.html      The app (UI, 1,062 embedded questions, paywall). Edit only this copy.
android/               Android Studio project (Kotlin, package com.gbxps.fasea)
ios/                   Xcode project (SwiftUI, bundle com.gbxps.Financial-Adviser-Exam-Trainer)
scripts/sync-web.sh    Copies shared/index.html into both native projects
tests/                 Paywall/bridge tests for the shared app (npm test)
questions/             Question bank notes (add FASEA_Question_Bank_v12.xlsx here)
docs/                  Original session handoff
```

## Keeping the two apps in step

The Android and iOS projects are separate and each builds on its own, but app behaviour lives
in `shared/index.html`, which both bundle:

1. Change `shared/index.html`.
2. Run `scripts/sync-web.sh` (copies it to `android/app/src/main/assets/` and `ios/Financial Adviser Exam Trainer/`).
3. Run `npm test`.
4. Commit all three copies together. CI fails if a copy is out of date.

Native code changes must be mirrored by hand. The bridge contract is the same on both:

| Direction | Name | Android | iOS |
|---|---|---|---|
| JS → native | `isUnlocked()` | sync `@JavascriptInterface` | injected `window.__FASEA_ENTITLEMENT__` + `onEntitlementChanged` |
| JS → native | `ready`, `showRewardedAd`, `showInterstitialAd`, `purchaseProduct(id)`, `restorePurchases` | `window.AppBridge.<name>()` | `webkit.messageHandlers.AppBridge.postMessage({action, productId})` |
| native → JS | `onEntitlementChanged`, `grantAdUnlock`, `onIAPComplete`, `onProductsLoaded`, `onAdUnavailable`, `onPurchaseFailed` | `AppBridge.kt` | `AppBridge.swift` |

## Access tiers

| Tier | Gets |
|---|---|
| Free | 50 practice questions (fixed sample), interstitial ad between sessions (max one per 3 minutes) |
| Watch an ad | Everything for 24 hours |
| `fasea_monthly` subscription ($9.99 AUD) | Everything while active |
| `fasea_lifetime` one-time purchase ($29.99 AUD) | Everything, permanently |

Section practice and timed exams are full-access features. Create both product IDs in Google
Play Console and App Store Connect before testing purchases on a device.

## Android

Open `android/` in Android Studio, or build from the command line (JDK 17+):

```
cd android && ./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew bundleRelease                      # needs android/keystore.properties for signing
```

- Targets SDK 36 and uses Play Billing Library 8 (both required for Play updates from 31 Aug 2026).
- Debug builds use Google's AdMob test IDs. Put the live IDs in `android/gradle.properties` for release.
- Set `versionCode` in `app/build.gradle.kts` above the version currently live on Play.
- Store icon: `android/store/play_icon_512.png` (same artwork as the iOS icon).

## iOS

Open `ios/Financial Adviser Exam Trainer.xcodeproj` in Xcode 26. Xcode fetches the Google Mobile
Ads Swift package on first open. The project uses a synchronised folder, so new files dropped in
`ios/Financial Adviser Exam Trainer/` are included automatically.

- AdMob IDs are in `ios/Financial Adviser Exam Trainer/Info.plist` (test IDs committed).
- To test purchases in the simulator, add a StoreKit Configuration file (File → New → StoreKit
  Configuration File, synced from App Store Connect) and select it in the scheme's Run options.

## Before submitting to the stores

- Replace the AdMob test IDs (Android `gradle.properties`, iOS `Info.plist`).
- Privacy policy URL (both stores require one for apps with ads), App Privacy / Data safety forms.
- Terms of use and privacy links on the paywall (Apple requires them for subscriptions).
- Consider server-side receipt/purchase-token verification; entitlements are currently verified on device.
- Screenshots and store descriptions.

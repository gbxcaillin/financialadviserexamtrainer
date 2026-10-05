import StoreKit

/// StoreKit 2 wrapper for the two products:
///  - `fasea_monthly`  auto-renewing subscription
///  - `fasea_lifetime` non-consumable
///
/// The last known entitlement is cached in UserDefaults so a paid user is
/// unlocked immediately on launch, before StoreKit has answered.
final class StoreManager {
    static let shared = StoreManager()

    static let monthlyID = "fasea_monthly"
    static let lifetimeID = "fasea_lifetime"
    static let productIDs: Set<String> = [monthlyID, lifetimeID]

    private static let cacheKey = "fasea_active_product"

    enum PurchaseOutcome {
        case purchased(String)
        case pending
        case cancelled
        case failed(String)
    }

    /// The product granting access (lifetime preferred), or nil on the free tier.
    private(set) var activeProductID: String?
    private var products: [String: Product] = [:]
    private var updatesTask: Task<Void, Never>?

    /// Called whenever entitlements or prices change.
    var onChange: (() -> Void)?

    var hasActivePurchase: Bool { activeProductID != nil }

    private init() {
        activeProductID = UserDefaults.standard.string(forKey: Self.cacheKey)
        // Renewals, refunds, Ask to Buy approvals and purchases made on other devices
        updatesTask = Task { [weak self] in
            for await update in Transaction.updates {
                if case .verified(let transaction) = update {
                    await transaction.finish()
                }
                await self?.refreshEntitlements()
            }
        }
        Task {
            await refreshEntitlements()
            await loadProducts()
        }
    }

    func displayPrices() -> [String: String] {
        products.mapValues { $0.displayPrice }
    }

    func loadProducts() async {
        guard let loaded = try? await Product.products(for: Self.productIDs) else { return }
        products = Dictionary(uniqueKeysWithValues: loaded.map { ($0.id, $0) })
        onChange?()
    }

    func refreshEntitlements() async {
        var active: String?
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result,
                  transaction.revocationDate == nil,
                  Self.productIDs.contains(transaction.productID) else { continue }
            if transaction.productID == Self.lifetimeID || active == nil {
                active = transaction.productID
            }
        }
        let changed = active != activeProductID
        activeProductID = active
        UserDefaults.standard.set(active, forKey: Self.cacheKey)
        if changed { onChange?() }
    }

    func purchase(_ productID: String) async -> PurchaseOutcome {
        do {
            if products[productID] == nil { await loadProducts() }
            guard let product = products[productID] else {
                return .failed("This product isn't available yet. Please try again later.")
            }
            switch try await product.purchase() {
            case .success(let verification):
                guard case .verified(let transaction) = verification else {
                    return .failed("The purchase couldn't be verified.")
                }
                await transaction.finish()
                await refreshEntitlements()
                return .purchased(transaction.productID)
            case .pending:
                return .pending
            case .userCancelled:
                return .cancelled
            @unknown default:
                return .failed("The purchase didn't complete.")
            }
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    /// Restore button (required by App Review): syncs with the App Store, then re-reads entitlements.
    func restore() async -> Bool {
        try? await AppStore.sync()
        await refreshEntitlements()
        return hasActivePurchase
    }
}

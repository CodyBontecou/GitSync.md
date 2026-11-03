import Foundation
import Observation
import StoreKit

/// The one-time "unlock" that lets this install add vaults.
///
/// GitSync.md is free to download. Everything up to and including the vault
/// check is free; cloning, linking an existing clone, or publishing a folder
/// needs the lifetime unlock. People who paid for the app before it went free
/// are recognised from their App Store receipt and never see the paywall.
@MainActor
@Observable
final class AppUnlock {
    /// Non-consumable lifetime unlock in App Store Connect.
    static let productID = "gitsync.md.lifetime"

    /// First `CFBundleVersion` downloaded as a free app. Anyone whose
    /// `AppTransaction.originalAppVersion` (the BUILD number on iOS, not the
    /// marketing version) is lower bought the paid app and is unlocked.
    /// Set this to the build that ships alongside the price change.
    static let firstFreeBuild: Int64 = 202611030000

    enum Reason: String, Codable, Sendable {
        case purchased
        case paidAppPurchase
        case existingVaults
        case paywallDisabled
    }

    enum PurchaseOutcome: Equatable {
        case unlocked
        case cancelled
        case pending
        case failed(String)
    }

    private(set) var reason: Reason?
    private(set) var product: Product?
    private(set) var isPurchasing = false
    private(set) var isRestoring = false

    var isUnlocked: Bool { reason != nil }

    /// Localized one-time price, or a fallback while the product loads.
    var displayPrice: String { product?.displayPrice ?? "$19.99" }

    private static let cacheKey = "appUnlock.reason"
    private let defaults: UserDefaults
    private var updatesTask: Task<Void, Never>?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if !FeatureFlags.hardPaywallEnabled {
            reason = .paywallDisabled
        } else if let raw = defaults.string(forKey: Self.cacheKey) {
            // Last known answer, so an offline launch does not flash a paywall.
            reason = Reason(rawValue: raw)
        }
    }

    /// Loads the product, checks the receipt, and listens for purchases made
    /// elsewhere (Ask to Buy approvals, another device, refunds).
    func start(hasExistingVaults: Bool) {
        guard FeatureFlags.hardPaywallEnabled, updatesTask == nil else { return }
        if hasExistingVaults, reason == nil {
            // Vaults could only be added by someone who paid; keep them working
            // even if the receipt cannot be read right now.
            setReason(.existingVaults)
        }
        updatesTask = Task { [weak self] in
            for await update in Transaction.updates {
                guard let self else { return }
                if case .verified(let transaction) = update {
                    await transaction.finish()
                }
                await self.refresh()
            }
        }
        Task {
            await loadProduct()
            await refresh()
        }
    }

    func loadProduct() async {
        guard product == nil else { return }
        product = try? await Product.products(for: [Self.productID]).first
    }

    /// Re-derives the unlock from the App Store. Never removes the
    /// `existingVaults` grace once granted.
    func refresh() async {
        guard FeatureFlags.hardPaywallEnabled else { return }
        if await hasLifetimeEntitlement() {
            setReason(.purchased)
        } else if await boughtPaidApp() {
            setReason(.paidAppPurchase)
        } else if reason == .purchased || reason == .paidAppPurchase {
            // Refunded or revoked.
            setReason(nil)
        }
    }

    func purchase() async -> PurchaseOutcome {
        guard !isPurchasing else { return .cancelled }
        isPurchasing = true
        defer { isPurchasing = false }
        await loadProduct()
        guard let product else {
            return .failed(String(localized: "The App Store isn't reachable right now. Try again in a moment."))
        }
        do {
            switch try await product.purchase() {
            case .success(.verified(let transaction)):
                await transaction.finish()
                setReason(.purchased)
                return .unlocked
            case .success(.unverified):
                return .failed(String(localized: "The App Store couldn't verify this purchase."))
            case .pending:
                return .pending
            case .userCancelled:
                return .cancelled
            @unknown default:
                return .cancelled
            }
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    /// Syncs with the App Store, then re-checks both the in-app purchase and
    /// the original paid-app receipt.
    func restore() async -> Bool {
        guard !isRestoring else { return isUnlocked }
        isRestoring = true
        defer { isRestoring = false }
        try? await AppStore.sync()
        await refresh()
        return isUnlocked
    }

    // MARK: - Receipts

    private func hasLifetimeEntitlement() async -> Bool {
        for await result in Transaction.currentEntitlements {
            if case .verified(let transaction) = result,
               transaction.productID == Self.productID,
               transaction.revocationDate == nil {
                return true
            }
        }
        return false
    }

    private func boughtPaidApp() async -> Bool {
        guard let result = try? await AppTransaction.shared,
              case .verified(let app) = result else { return false }
        return Self.isPaidAppVersion(app.originalAppVersion)
    }

    /// `true` when `originalAppVersion` names a build from before the app
    /// went free. Every earlier build counts, including the pre-June-2026
    /// free builds (build "1"): those people already had the app. The
    /// sandbox reports "1.0", which is not an integer and so reads as new.
    nonisolated static func isPaidAppVersion(_ originalAppVersion: String) -> Bool {
        guard let build = Int64(originalAppVersion.trimmingCharacters(in: .whitespaces)) else { return false }
        return build < firstFreeBuild
    }

    private func setReason(_ newReason: Reason?) {
        reason = newReason
        if let newReason {
            defaults.set(newReason.rawValue, forKey: Self.cacheKey)
        } else {
            defaults.removeObject(forKey: Self.cacheKey)
        }
    }
}

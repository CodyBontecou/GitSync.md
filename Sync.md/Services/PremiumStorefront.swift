import Foundation
import Observation
import StoreKit

struct PremiumFinishableTransaction: Sendable {
    let value: PremiumVerifiedTransaction
    private let finishOperation: @Sendable () async -> Void

    init(value: PremiumVerifiedTransaction, finish: @escaping @Sendable () async -> Void) {
        self.value = value
        self.finishOperation = finish
    }

    func finish() async { await finishOperation() }
}

protocol PremiumStorefront: Sendable {
    func setAppAccountToken(_ token: UUID) async
    func products(identifiers: [String]) async throws -> [PremiumProduct]
    /// StoreKit's verified current-entitlement sequence is authoritative,
    /// including grace-period access. Values here are not independently expired.
    func currentEntitlements() async -> [PremiumVerifiedTransaction]
    func purchase(productID: String) async throws -> PremiumPurchaseOutcome
    func sync() async throws
    func transactionUpdates() -> AsyncStream<PremiumFinishableTransaction>
}

enum PremiumPurchaseOutcome: Sendable {
    case verified(PremiumFinishableTransaction)
    case pending
    case cancelled
}

actor StoreKitPremiumStorefront: PremiumStorefront {
    private var appAccountToken: UUID?

    func setAppAccountToken(_ token: UUID) { appAccountToken = token }
    func products(identifiers: [String]) async throws -> [PremiumProduct] {
        try await Product.products(for: identifiers).map { product in
            PremiumProduct(id: product.id, displayName: product.displayName,
                           displayPrice: product.displayPrice,
                           period: Self.period(product.subscription?.subscriptionPeriod.unit))
        }
    }

    func currentEntitlements() async -> [PremiumVerifiedTransaction] {
        var values: [PremiumVerifiedTransaction] = []
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result else { continue }
            values.append(Self.dto(transaction, signedTransaction: result.jwsRepresentation))
        }
        return values
    }

    func purchase(productID: String) async throws -> PremiumPurchaseOutcome {
        guard let product = try await Product.products(for: [productID]).first else {
            throw PremiumStorefrontError.productNotFound
        }
        guard let appAccountToken else { throw PremiumStorefrontError.missingAccountToken }
        switch try await product.purchase(options: [.appAccountToken(appAccountToken)]) {
        case .success(let verification):
            guard case .verified(let transaction) = verification else {
                throw PremiumStorefrontError.unverifiedTransaction
            }
            return .verified(Self.finishable(transaction, signedTransaction: verification.jwsRepresentation))
        case .pending: return .pending
        case .userCancelled: return .cancelled
        @unknown default: return .cancelled
        }
    }

    func sync() async throws { try await AppStore.sync() }

    func transactionUpdates() -> AsyncStream<PremiumFinishableTransaction> {
        AsyncStream { continuation in
            let task = Task {
                for await result in Transaction.updates {
                    guard !Task.isCancelled, case .verified(let transaction) = result else { continue }
                    continuation.yield(Self.finishable(transaction, signedTransaction: result.jwsRepresentation))
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private static func finishable(_ transaction: Transaction, signedTransaction: String) -> PremiumFinishableTransaction {
        PremiumFinishableTransaction(value: dto(transaction, signedTransaction: signedTransaction)) {
            await transaction.finish()
        }
    }

    private static func dto(_ transaction: Transaction, signedTransaction: String) -> PremiumVerifiedTransaction {
        PremiumVerifiedTransaction(productID: transaction.productID, transactionID: transaction.id,
            originalTransactionID: transaction.originalID, purchaseDate: transaction.purchaseDate,
            expirationDate: transaction.expirationDate, revocationDate: transaction.revocationDate,
            appAccountToken: transaction.appAccountToken,
            environment: environment(transaction.environment), signedTransaction: signedTransaction)
    }

    private static func environment(_ value: AppStore.Environment) -> PremiumStoreEnvironment {
        switch value { case .sandbox: .sandbox; case .production: .production; case .xcode: .xcode; default: .unknown }
    }

    private static func period(_ unit: Product.SubscriptionPeriod.Unit?) -> PremiumBillingPeriod {
        switch unit { case .month: .month; case .year: .year; default: .unknown }
    }
}

enum PremiumStorefrontError: LocalizedError {
    case productNotFound, unverifiedTransaction, missingAccountToken
    var errorDescription: String? {
        switch self {
        case .productNotFound: String(localized: "Subscription product is unavailable.")
        case .unverifiedTransaction: String(localized: "The App Store transaction could not be verified.")
        case .missingAccountToken: String(localized: "GitSync Assist could not bind this purchase to the current installation.")
        }
    }
}

@MainActor
@Observable
final class PremiumEntitlementStore {
    private(set) var state: PremiumEntitlementState = .loading
    private(set) var products: [PremiumProduct] = []
    var onChange: (@MainActor (PremiumEntitlementState) -> Void)?

    private let storefront: any PremiumStorefront
    private let identifiers: PremiumProductIdentifiers
    private let defaults: UserDefaults
    private let cachedProofKey: String
    private var updatesTask: Task<Void, Never>?
    private var startTask: Task<Void, Never>?
    private var refreshGeneration: UInt64 = 0

    init(storefront: any PremiumStorefront, identifiers: PremiumProductIdentifiers,
         defaults: UserDefaults = .standard, cachedProofKey: String = "premium.verified-proof.v1") {
        self.storefront = storefront; self.identifiers = identifiers
        self.defaults = defaults; self.cachedProofKey = cachedProofKey
    }
    convenience init() { self.init(storefront: StoreKitPremiumStorefront(), identifiers: .default) }

    func bindAppAccountToken(_ token: UUID) async { await storefront.setAppAccountToken(token) }

    func start() async {
        if let startTask { await startTask.value; return }
        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            await self.performStart()
        }
        startTask = task
        await task.value
    }

    private func performStart() async {
        if updatesTask == nil {
            updatesTask = Task { [weak self, storefront] in
                for await transaction in storefront.transactionUpdates() {
                    guard !Task.isCancelled else { return }
                    await self?.consumeEvent(transaction)
                }
            }
        }
        await refresh()
        do { products = try await storefront.products(identifiers: identifiers.all) }
        catch { if !state.isActive { setState(.error(storefrontErrorDescription(error))) } }
    }

    func refresh() async {
        refreshGeneration &+= 1
        let generation = refreshGeneration
        setState(.loading)
        let known = Set(identifiers.all)
        // Do not apply transaction expiration here. Presence in StoreKit's
        // verified currentEntitlements is the authority (including grace period).
        let current = await storefront.currentEntitlements().filter {
            known.contains($0.productID) && $0.revocationDate == nil
        }.sorted { ($0.expirationDate ?? .distantFuture) > ($1.expirationDate ?? .distantFuture) }
        // An older StoreKit query must never overwrite a newer entitlement
        // snapshot that completed first.
        guard generation == refreshGeneration else { return }
        guard let transaction = current.first else {
            clearCachedProof(); setState(.inactive); return
        }
        activate(transaction)
    }

    func purchase(productID: String) async {
        guard identifiers.all.contains(productID) else { setState(.error(PremiumStorefrontError.productNotFound.localizedDescription)); return }
        do {
            switch try await storefront.purchase(productID: productID) {
            case .verified(let transaction): await consumeEvent(transaction)
            case .pending: setState(.pending)
            case .cancelled: await refresh()
            }
        } catch { setState(.error(storefrontErrorDescription(error))) }
    }

    func restore() async {
        do { try await storefront.sync(); await refresh() }
        catch { setState(.error(storefrontErrorDescription(error))) }
    }

    private func storefrontErrorDescription(_ error: Error) -> String {
        if let error = error as? PremiumStorefrontError {
            return error.localizedDescription
        }
        return String(localized: "The App Store request failed: \(error.localizedDescription)")
    }

    /// Retry/UI continuity only. This value is never consulted by `start`,
    /// `refresh`, or any authorization gate and cannot grant access.
    func cachedVerifiedProof() -> PremiumEntitlementProof? {
        guard let data = defaults.data(forKey: cachedProofKey) else { return nil }
        return try? JSONDecoder().decode(PremiumEntitlementProof.self, from: data)
    }

    private func consumeEvent(_ transaction: PremiumFinishableTransaction) async {
        // Finish the exact object delivered by StoreKit, then derive access only
        // from a fresh verified current-entitlement snapshot. A queued event is
        // never itself an authorization grant.
        await transaction.finish()
        await refresh()
    }

    private func activate(_ transaction: PremiumVerifiedTransaction) {
        let proof = PremiumEntitlementProof(transaction: transaction)
        if let data = try? JSONEncoder().encode(proof) { defaults.set(data, forKey: cachedProofKey) }
        setState(.active(proof))
    }
    private func clearCachedProof() { defaults.removeObject(forKey: cachedProofKey) }
    private func setState(_ state: PremiumEntitlementState) { self.state = state; onChange?(state) }
}

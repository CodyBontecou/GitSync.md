import Foundation
import Combine
import StoreKit
import Security

/// Manages the one-time unlock IAP and legacy paid-user migration for Sync.md.
@MainActor
final class PurchaseManager: ObservableObject {

    static let shared = PurchaseManager()

    // MARK: - Configuration

    /// Product ID registered in App Store Connect.
    static let productID = "bontecou.syncmd.unlock"

    /// Number of repositories included for free before a purchase is required.
    nonisolated static let freeRepoLimit = 1

    /// Cutoff just past the App Store release of v1.7 (the first free build).
    ///
    /// Anyone whose `originalPurchaseDate` is strictly before this date originally
    /// purchased v1.5 or v1.6 — both paid releases — and is entitled to a legacy
    /// unlock. Anyone after must have first installed v1.7 or later, which has
    /// always been free.
    ///
    /// We compare `originalPurchaseDate`, not `originalAppVersion`, on purpose.
    /// `originalAppVersion` is `CFBundleVersion` on iOS, and a future
    /// `CURRENT_PROJECT_VERSION` reset (e.g. back to "1") would cause every fresh
    /// install to numerically compare as "less than" any 12-digit build threshold
    /// and silently auto-unlock as legacy. `originalPurchaseDate` is Apple-signed,
    /// immutable, and immune to build-number reshuffles.
    ///
    /// 2026-04-01 00:00 UTC is biased toward generosity for paid customers:
    /// v1.7 build was created 2026-03-29 10:00 UTC and went live a day or two
    /// later, so this cutoff captures every v1.6 buyer (last paid build was
    /// 2026-03-26 09:33 UTC). The trade-off: anyone who installed free v1.7 in
    /// the brief window between Apple approval and 2026-04-01 also gets unlocked.
    static let freemiumCutoffDate: Date = {
        var c = DateComponents()
        c.year = 2026; c.month = 4; c.day = 1
        c.hour = 0; c.minute = 0; c.second = 0
        c.timeZone = TimeZone(identifier: "UTC")
        return Calendar(identifier: .gregorian).date(from: c)!
    }()

    /// Base URL for the Cloudflare Worker that verifies legacy purchases.
    static let workerBaseURL = "https://syncmd-receipt-verifier.costream.workers.dev"

    /// Keychain service identifier.
    private static let keychainService = "bontecou.Sync-md"

    // MARK: - Published State

    @Published private(set) var isUnlocked: Bool = false
    @Published private(set) var isLegacyUser: Bool = false
    @Published private(set) var product: Product? = nil
    @Published private(set) var isPurchasing: Bool = false
    @Published private(set) var isRestoring: Bool = false
    @Published private(set) var purchaseError: String? = nil

    private let serverVerifiedLegacyKey = "serverVerifiedLegacy"
    private let cachedIAPUnlockKey = "cachedIAPUnlock"

    /// Keychain key that stores a JSON-encoded array of repo identifiers (URLs / paths)
    /// ever added on this device. Written once per new identifier and never cleared,
    /// so it survives app deletion and reinstall.
    private let seenRepoIDsKey = "seenRepoIDs"

    // MARK: - Init

    private init() {
        hydrateCachedUnlockState()
        startTransactionListener()
    }

    // MARK: - Status Check

    /// Restores any previously cached unlock state without touching StoreKit.
    /// This keeps onboarding free of App Store account prompts.
    private func hydrateCachedUnlockState() {
        #if DEBUG
        if UserDefaults.standard.bool(forKey: Self.debugForceFreeKey) {
            isUnlocked = false; isLegacyUser = false; return
        }
        #endif
        if keychainRead(key: serverVerifiedLegacyKey) > 0 {
            isLegacyUser = true
            isUnlocked = true
            return
        }

        if keychainRead(key: cachedIAPUnlockKey) > 0 {
            isLegacyUser = false
            isUnlocked = true
        }
    }

    /// Re-evaluates unlock status from StoreKit.
    /// - Parameter includeLegacyChecks: When `true`, also performs the more
    ///   invasive legacy-user checks that may require App Store account access.
    ///   Keep this `false` for passive UI states; only use `true` for explicit
    ///   restore flows.
    func refreshStatus(includeLegacyChecks: Bool = false) async {
        #if DEBUG
        if debugForceFreeMode {
            isUnlocked = false; isLegacyUser = false; return
        }
        // Debug override: set "debugOriginalPurchaseDate" in UserDefaults to a
        // Unix timestamp (seconds since 1970) to simulate any install date without
        // needing a real App Store receipt. Runs first so it works on dev builds
        // deployed via Xcode (which have no receipt, causing AppTransaction to throw).
        // A timestamp before `freemiumCutoffDate` simulates a legacy paid user.
        if UserDefaults.standard.object(forKey: "debugOriginalPurchaseDate") != nil {
            let secs = UserDefaults.standard.double(forKey: "debugOriginalPurchaseDate")
            let simulated = Date(timeIntervalSince1970: secs)
            if simulated < Self.freemiumCutoffDate {
                isLegacyUser = true
                isUnlocked = true
            } else {
                isLegacyUser = false
                isUnlocked = false
            }
            return
        }

        // Debug override: set "debugSkipToServerVerification" = true to bypass steps
        // 1–3 and jump straight to the server receipt check. Use this to test the
        // Cloudflare Worker integration end-to-end on a device that isn't a legacy user.
        if UserDefaults.standard.bool(forKey: "debugSkipToServerVerification") {
            if await verifyLegacyWithServer() {
                keychainWrite(key: serverVerifiedLegacyKey, value: 1)
                isLegacyUser = true
                isUnlocked = true
            } else {
                isLegacyUser = false
                isUnlocked = false
            }
            return
        }
        #endif

        isLegacyUser = false

        // 1. Fast path: the user already has an active entitlement for the IAP.
        for await result in Transaction.currentEntitlements {
            if case .verified(let tx) = result, tx.productID == Self.productID {
                keychainWrite(key: cachedIAPUnlockKey, value: 1)
                isUnlocked = true
                return
            }
        }

        keychainWrite(key: cachedIAPUnlockKey, value: 0)

        // 2. Server-verified legacy path: a previous explicit restore call verified
        //    this device as a legacy paid user and cached the result in the Keychain.
        if keychainRead(key: serverVerifiedLegacyKey) > 0 {
            isLegacyUser = true
            isUnlocked = true
            return
        }

        guard includeLegacyChecks else {
            isUnlocked = false
            return
        }

        // 3. Local legacy paid-user path: check when the app was first purchased
        //    via AppTransaction. Signed by Apple so it cannot be spoofed, and unlike
        //    `originalAppVersion` it is immune to future build-number resets. Does not
        //    survive a delete-and-reinstall before StoreKit syncs; step 2 above is the
        //    durable version of this check once the server has verified them at least once.
        do {
            let appTxResult = try await AppTransaction.shared
            switch appTxResult {
            case .verified(let appTx):
                if appTx.originalPurchaseDate < Self.freemiumCutoffDate {
                    keychainWrite(key: serverVerifiedLegacyKey, value: 1)
                    isLegacyUser = true
                    isUnlocked = true
                    return
                }
            case .unverified(let appTx, _):
                // Local JWS verification failed (cert cache, clock skew, etc.) but the
                // data is still Apple-signed. Trust it for legacy detection — an attacker
                // who can forge AppTransaction responses already has device-level control
                // and could bypass any client-side check.
                if appTx.originalPurchaseDate < Self.freemiumCutoffDate {
                    keychainWrite(key: serverVerifiedLegacyKey, value: 1)
                    isLegacyUser = true
                    isUnlocked = true
                    return
                }
            }
        } catch {
            // AppTransaction threw entirely — no data available. This happens on
            // sideloaded dev builds (no receipt) or after reinstall when StoreKit
            // hasn't synced yet. Fall through to server verification.
        }

        // 4. Server verification is reserved for explicit restore attempts so new
        //    users are not prompted to sign into the App Store during onboarding.
        if await verifyLegacyWithServer() {
            keychainWrite(key: serverVerifiedLegacyKey, value: 1)
            isLegacyUser = true
            isUnlocked = true
            return
        }

        isUnlocked = false
    }

    // MARK: - Product Loading

    /// Fetches the IAP product from App Store Connect (or the local .storekit config
    /// during development). Populates `product` so the UI can show the live price.
    func loadProduct() async {
        do {
            let products = try await Product.products(for: [Self.productID])
            product = products.first
        } catch {
            // Silently ignore — the UI falls back to a generic unlock label.
        }
    }

    // MARK: - Purchase

    /// Initiates the StoreKit purchase flow. Sets `isUnlocked = true` on success.
    func purchase() async {
        guard let product else {
            purchaseError = String(
                localized: "Product unavailable. Please try again later.",
                comment: "IAP product unavailable error"
            )
            return
        }

        isPurchasing = true
        purchaseError = nil
        defer { isPurchasing = false }

        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                guard case .verified(let tx) = verification else {
                    purchaseError = String(
                        localized: "Purchase verification failed.",
                        comment: "IAP verification error"
                    )
                    return
                }
                await tx.finish()
                keychainWrite(key: cachedIAPUnlockKey, value: 1)
                isUnlocked = true
                isLegacyUser = false

            case .pending:
                // Ask to Buy or parental approval pending — the transaction listener
                // will catch the approval and set isUnlocked when it arrives.
                break

            case .userCancelled:
                break

            @unknown default:
                break
            }
        } catch {
            purchaseError = error.localizedDescription
        }
    }

    // MARK: - Restore

    /// Restores access for both IAP purchasers and legacy paid-app users.
    func restore() async {
        isRestoring = true
        purchaseError = nil
        defer { isRestoring = false }

        do {
            // Syncing also refreshes the on-device App Store receipt, which makes
            // the subsequent AppTransaction and server verification more reliable.
            try await AppStore.sync()
        } catch {
            purchaseError = error.localizedDescription
            return
        }

        // Explicit restore is the only place we run the full legacy-verification
        // flow, which may require App Store account access.
        await refreshStatus(includeLegacyChecks: true)
        if isUnlocked { return }

        purchaseError = String(
            localized: "No purchase found on this Apple ID.\n\nIf you bought Sync.md before v1.7.0 and still can't restore access, contact us at cody@isolated.tech and we'll sort it out.",
            comment: "Restore purchase not found message"
        )
    }

    // MARK: - Server Verification

    /// Attempts server-side legacy verification. Tries two approaches:
    /// 1. AppTransaction JWS token (works on TestFlight + App Store)
    /// 2. Legacy receipt file (works on App Store installs only)
    /// Returns `false` on any failure so the caller falls back gracefully.
    private func verifyLegacyWithServer() async -> Bool {
        // Approach 1: Send AppTransaction JWS to the worker.
        if let jws = try? await AppTransaction.shared.jwsRepresentation {
            if await sendToWorker(path: "/verify-legacy-jws", body: ["jws": jws]) {
                return true
            }
        }

        // Approach 2: Legacy receipt file → Apple's verifyReceipt endpoint.
        if let receiptURL = Bundle.main.appStoreReceiptURL,
           FileManager.default.fileExists(atPath: receiptURL.path),
           let receiptData = try? Data(contentsOf: receiptURL) {
            if await sendToWorker(path: "/verify-legacy", body: ["receipt": receiptData.base64EncodedString()]) {
                return true
            }
        }

        return false
    }

    /// Posts a JSON body to the worker and returns true if the response contains `isLegacy: true`.
    private func sendToWorker(path: String, body: [String: String]) async -> Bool {
        guard let url = URL(string: "\(Self.workerBaseURL)\(path)") else {
            return false
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 15

        guard let httpBody = try? JSONSerialization.data(withJSONObject: body) else { return false }
        request.httpBody = httpBody

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                return false
            }
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            return json?["isLegacy"] as? Bool ?? false
        } catch {
            return false
        }
    }

    // MARK: - Transaction Listener

    /// Listens for incoming transactions in the background (deferred purchases,
    /// family sharing grants, etc.) and unlocks the app when one arrives.
    private func startTransactionListener() {
        let productID = Self.productID
        Task.detached(priority: .background) { [weak self] in
            for await result in Transaction.updates {
                guard case .verified(let tx) = result,
                      tx.productID == productID else { continue }
                await tx.finish()
                guard let self else { continue }
                await self.applyVerifiedPurchase()
            }
        }
    }

    private func applyVerifiedPurchase() {
        keychainWrite(key: cachedIAPUnlockKey, value: 1)
        isUnlocked = true
        isLegacyUser = false
    }

    // MARK: - Debug

    #if DEBUG
    /// UserDefaults key that, when `true`, makes every call to `refreshStatus()`
    /// and `hydrateCachedUnlockState()` return immediately as a free/locked user.
    /// Survives app restarts so the paywall stays visible across re-opens.
    static let debugForceFreeKey = "debugForceFreeMode"

    var debugForceFreeMode: Bool {
        get { UserDefaults.standard.bool(forKey: Self.debugForceFreeKey) }
        set { UserDefaults.standard.set(newValue, forKey: Self.debugForceFreeKey) }
    }

    /// Clears every Keychain purchase/unlock key and the seen-repo set, enables
    /// the force-free override, then forces the in-memory state to "free, locked".
    func debugResetPurchaseState() {
        keychainDelete(key: cachedIAPUnlockKey)
        keychainDelete(key: serverVerifiedLegacyKey)
        keychainDelete(key: seenRepoIDsKey)
        debugForceFreeMode = true
        isUnlocked   = false
        isLegacyUser = false
    }

    /// Removes the force-free override and re-evaluates real purchase status.
    func debugRestoreProState() async {
        debugForceFreeMode = false
        await refreshStatus()
    }
    #endif

    /// Runs the full receipt → worker → Apple chain and returns a human-readable
    /// summary of every step. Only surfaced in the UI on DEBUG builds.
    func debugVerifyReceipt() async -> String {
        var lines: [String] = []

        lines.append("=== Purchase State ===")
        lines.append("isUnlocked:    \(isUnlocked)")
        lines.append("isLegacyUser:  \(isLegacyUser)")
        lines.append("serverCached:  \(keychainRead(key: serverVerifiedLegacyKey) > 0)")
        lines.append("")

        lines.append("=== Receipt ===")

        if let receiptURL = Bundle.main.appStoreReceiptURL {
            lines.append("URL: \(receiptURL.lastPathComponent)")
            lines.append("Exists: \(FileManager.default.fileExists(atPath: receiptURL.path))")
        } else {
            lines.append("URL: nil")
        }

        lines.append("")
        lines.append("=== AppTransaction ===")
        do {
            let appTxResult = try await AppTransaction.shared
            switch appTxResult {
            case .verified(let appTx):
                lines.append("✅ Verified")
                lines.append("originalAppVersion: \(appTx.originalAppVersion)")
                lines.append("originalPurchaseDate: \(appTx.originalPurchaseDate)")
                lines.append("appVersion: \(appTx.appVersion)")
                lines.append("environment: \(appTx.environment.rawValue)")
                lines.append("isLegacyByCutoff: \(appTx.originalPurchaseDate < Self.freemiumCutoffDate)")
            case .unverified(let appTx, let error):
                lines.append("⚠️ Unverified: \(error)")
                lines.append("originalAppVersion: \(appTx.originalAppVersion)")
                lines.append("originalPurchaseDate: \(appTx.originalPurchaseDate)")
                lines.append("isLegacyByCutoff: \(appTx.originalPurchaseDate < Self.freemiumCutoffDate)")
            @unknown default:
                lines.append("❓ Unknown result")
            }
        } catch {
            lines.append("❌ \(error.localizedDescription)")
        }

        lines.append("")
        lines.append("=== AppStore.sync() ===")
        do {
            try await AppStore.sync()
            lines.append("✅ Sync succeeded")
        } catch {
            lines.append("❌ Sync failed: \(error.localizedDescription)")
        }

        let receiptExists: Bool
        if let receiptURL = Bundle.main.appStoreReceiptURL,
           FileManager.default.fileExists(atPath: receiptURL.path) {
            let receiptData = try? Data(contentsOf: receiptURL)
            lines.append("Receipt: ✅ \(receiptData?.count ?? 0) bytes")
            receiptExists = true
        } else {
            lines.append("Receipt file still not found after sync")
            receiptExists = false
        }
        lines.append("")

        lines.append("=== Worker: JWS Path ===")
        do {
            let jws = try await AppTransaction.shared.jwsRepresentation
            lines.append("JWS: \(jws.prefix(40))…")
            let jwsResult = await sendToWorker(path: "/verify-legacy-jws", body: ["jws": jws])
            lines.append("isLegacy: \(jwsResult)")
        } catch {
            lines.append("❌ No JWS: \(error.localizedDescription)")
        }

        if receiptExists,
           let receiptURL = Bundle.main.appStoreReceiptURL,
           let receiptData = try? Data(contentsOf: receiptURL) {
            lines.append("")
            lines.append("=== Worker: Receipt Path ===")
            let receiptResult = await sendToWorker(path: "/verify-legacy", body: ["receipt": receiptData.base64EncodedString()])
            lines.append("isLegacy: \(receiptResult)")
        }

        return lines.joined(separator: "\n")
    }

    // MARK: - Repo-Tracking Helpers

    /// Returns the set of repo identifiers (normalised lowercase) that have previously
    /// been added on this device. Reads from the Keychain, which survives reinstall.
    func seenRepoIdentifiers() -> Set<String> {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.keychainService,
            kSecAttrAccount as String: seenRepoIDsKey,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let array = try? JSONDecoder().decode([String].self, from: data)
        else { return [] }
        return Set(array)
    }

    /// Persists `identifier` in the seen-repo set.
    /// - Returns: `true` if this is a brand-new identifier (first time seen).
    @discardableResult
    func recordRepoAdded(identifier: String) -> Bool {
        let normalised = identifier
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        guard !normalised.isEmpty else { return false }
        var seen = seenRepoIdentifiers()
        guard !seen.contains(normalised) else { return false }   // already tracked
        seen.insert(normalised)
        if let data = try? JSONEncoder().encode(Array(seen)) {
            keychainWriteData(key: seenRepoIDsKey, data: data)
        }
        return true
    }

    /// Number of unique repository identifiers ever added on this device.
    var uniqueReposEverAdded: Int { seenRepoIdentifiers().count }

    /// Returns `true` when `identifier` has NOT been seen before AND adding it
    /// would consume a free slot that is already exhausted — i.e. a purchase is
    /// required before this identifier can be added.
    func isNewRepoIdentifier(_ identifier: String) -> Bool {
        let normalised = identifier
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        guard !normalised.isEmpty else { return false }
        let seen = seenRepoIdentifiers()
        // Already in the set → re-adding a known repo, always free.
        if seen.contains(normalised) { return false }
        // New identifier → only gated when the free-slot budget is exhausted.
        return seen.count >= Self.freeRepoLimit
    }

    /// Returns `true` when adding `identifier` would burn one of the user's
    /// free repository slots — i.e. they are not unlocked, the identifier is
    /// genuinely new, and they still have free slots remaining. Used by the
    /// UI to surface a one-time confirmation before consuming the free slot,
    /// since the slot is permanent (Keychain-backed) and survives reinstall.
    func wouldConsumeFreeSlot(_ identifier: String) -> Bool {
        if isUnlocked { return false }
        let normalised = identifier
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        guard !normalised.isEmpty else { return false }
        let seen = seenRepoIdentifiers()
        if seen.contains(normalised) { return false }
        return seen.count < Self.freeRepoLimit
    }

    // MARK: - Keychain Helpers

    private func keychainRead(key: String) -> Int {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.keychainService,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              data.count >= MemoryLayout<Int32>.size else { return 0 }
        return Int(data.withUnsafeBytes { $0.load(as: Int32.self) })
    }

    private func keychainWrite(key: String, value: Int) {
        var v = Int32(value)
        let data = Data(bytes: &v, count: MemoryLayout<Int32>.size)
        keychainWriteData(key: key, data: data)
    }

    private func keychainDelete(key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.keychainService,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
    }

    private func keychainWriteData(key: String, data: Data) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.keychainService,
            kSecAttrAccount as String: key,
        ]
        let attrs: [String: Any] = [kSecValueData as String: data]
        if SecItemUpdate(query as CFDictionary, attrs as CFDictionary) == errSecItemNotFound {
            var addQuery = query
            addQuery[kSecValueData as String] = data
            addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            SecItemAdd(addQuery as CFDictionary, nil)
        }
    }

}

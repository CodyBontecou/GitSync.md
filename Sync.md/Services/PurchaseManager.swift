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
    static let freeRepoLimit = 1

    /// First marketing version shipped as freemium/free (CFBundleShortVersionString).
    /// On macOS, `AppTransaction.originalAppVersion` returns this format.
    /// v1.6 was accidentally shipped as a paid app, so v1.7 is the first truly free version.
    static let freemiumIntroVersion = "1.7"

    /// First build number shipped as freemium/free (CFBundleVersion).
    /// On iOS, `AppTransaction.originalAppVersion` returns `CFBundleVersion`
    /// (the build number), NOT `CFBundleShortVersionString`.
    /// Any build number strictly less than this value is a legacy paid install.
    ///
    /// History:
    ///   v1.5  build "1"            — paid app
    ///   v1.6  build "202603260933" — accidentally still paid; all v1.6 users get legacy access
    ///   v1.7  build ≥ "202603270000" — first free (freemium) release
    static let freemiumIntroBuildNumber = "202603270000"

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

    // MARK: - Init

    private init() {
        hydrateCachedUnlockState()
        startTransactionListener()
    }

    // MARK: - Status Check

    /// Restores any previously cached unlock state without touching StoreKit.
    /// This keeps onboarding free of App Store account prompts.
    private func hydrateCachedUnlockState() {
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
        // Debug override: set "debugOriginalAppVersion" in UserDefaults to simulate
        // any install version without needing a real App Store receipt.
        // This runs first so it works on dev builds deployed via Xcode (which have
        // no receipt, causing AppTransaction to throw).
        if let debugVersion = UserDefaults.standard.string(forKey: "debugOriginalAppVersion") {
            if isLegacyVersion(debugVersion) {
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

        // 3. Local legacy paid-user path: check the version the app was first downloaded
        //    at via AppTransaction. Signed by Apple so it cannot be spoofed, but does
        //    not survive a delete-and-reinstall after v1.6.0. Step 2 above is the durable
        //    version of this check once the server has verified them at least once.
        //
        //    NOTE: On iOS, originalAppVersion is CFBundleVersion (build number).
        //    On macOS, it is CFBundleShortVersionString (marketing version).
        //    isLegacyVersion() handles both formats.
        do {
            let appTxResult = try await AppTransaction.shared
            switch appTxResult {
            case .verified(let appTx):
                if isLegacyVersion(appTx.originalAppVersion) {
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
                if isLegacyVersion(appTx.originalAppVersion) {
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
                lines.append("appVersion: \(appTx.appVersion)")
                lines.append("environment: \(appTx.environment.rawValue)")
            case .unverified(let appTx, let error):
                lines.append("⚠️ Unverified: \(error)")
                lines.append("originalAppVersion: \(appTx.originalAppVersion)")
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

    // MARK: - Version Comparison

    /// Returns `true` if `v1` is strictly less than `v2`.
    private func versionIsLessThan(_ v1: String, _ v2: String) -> Bool {
        let a = v1.split(separator: ".").compactMap { Int($0) }
        let b = v2.split(separator: ".").compactMap { Int($0) }
        for i in 0..<max(a.count, b.count) {
            let x = i < a.count ? a[i] : 0
            let y = i < b.count ? b[i] : 0
            if x < y { return true }
            if x > y { return false }
        }
        return false
    }

    /// Detects whether a version string is a `CFBundleVersion` build number
    /// versus a `CFBundleShortVersionString` marketing version.
    private func isBuildNumber(_ version: String) -> Bool {
        !version.contains(".") && !version.isEmpty && version.allSatisfy(\.isNumber)
    }

    /// Returns `true` when the given original-app-version indicates a legacy
    /// (pre-freemium) install, handling both build numbers (iOS) and
    /// marketing versions (macOS).
    private func isLegacyVersion(_ version: String) -> Bool {
        if isBuildNumber(version) {
            return versionIsLessThan(version, Self.freemiumIntroBuildNumber)
        } else {
            return versionIsLessThan(version, Self.freemiumIntroVersion)
        }
    }
}

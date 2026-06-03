import Foundation
import Combine
import Security

/// Maintains the device-local history of repositories that have been added to GitSync.md.
///
/// The app now uses a paid-up-front model, so there is no in-app unlock state or
/// repository quota. We still keep this Keychain-backed history so previously
/// cloned remote repositories can be shown on the home screen after deletion or
/// reinstall.
@MainActor
final class RepositoryHistoryStore: ObservableObject {

    static let shared = RepositoryHistoryStore()

    /// Keychain service identifier.
    private static let keychainService = "bontecou.Sync-md"

    /// Keychain key that stores a JSON-encoded array of repo identifiers (URLs / paths)
    /// ever added on this device. Written once per new identifier and never cleared
    /// automatically, so it survives app deletion and reinstall.
    private let seenRepoIDsKey = "seenRepoIDs"

    private init() {}

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
        let normalised = normalisedIdentifier(identifier)
        guard !normalised.isEmpty else { return false }

        var seen = seenRepoIdentifiers()
        guard !seen.contains(normalised) else { return false }

        seen.insert(normalised)
        if let data = try? JSONEncoder().encode(Array(seen)) {
            keychainWriteData(key: seenRepoIDsKey, data: data)
            objectWillChange.send()
        }
        return true
    }

    /// Removes `identifier` from the seen-repo set so it no longer appears in
    /// the "previously cloned" list. This does not delete local files or revoke
    /// GitHub access.
    /// - Returns: `true` when the identifier was present and removed.
    @discardableResult
    func forgetSeenRepoIdentifier(_ identifier: String) -> Bool {
        let normalised = normalisedIdentifier(identifier)
        guard !normalised.isEmpty else { return false }

        var seen = seenRepoIdentifiers()
        guard seen.remove(normalised) != nil else { return false }

        if seen.isEmpty {
            keychainDelete(key: seenRepoIDsKey)
        } else if let data = try? JSONEncoder().encode(Array(seen)) {
            keychainWriteData(key: seenRepoIDsKey, data: data)
        }
        objectWillChange.send()
        return true
    }

    /// Number of unique repository identifiers ever added on this device.
    var uniqueReposEverAdded: Int { seenRepoIdentifiers().count }

    private func normalisedIdentifier(_ identifier: String) -> String {
        identifier
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
    }

    // MARK: - Keychain Helpers

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

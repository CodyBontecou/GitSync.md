import Foundation
import Security

enum KeychainService {
    static let service = "com.bontecou.Sync-md"
    static let accessibilityMigrationDefaultsKey = "gitCredentialAccessibilityMigration.v1"
    /// Git credentials must remain available to an opted-in background task after
    /// the first device unlock. Device-only prevents them migrating in backups.
    static let credentialAccessibility = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

    @discardableResult
    static func save(key: String, value: String) -> OSStatus {
        guard let data = value.data(using: .utf8) else { return errSecParam }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        // Update in place first so a failed write cannot erase an existing
        // fail-closed credential or deletion marker.
        let updated = SecItemUpdate(query as CFDictionary, [
            kSecValueData as String: data,
            kSecAttrAccessible as String: credentialAccessibility
        ] as CFDictionary)
        if updated == errSecSuccess { return updated }
        guard updated == errSecItemNotFound else { return updated }
        var addQuery = query
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = credentialAccessibility
        return SecItemAdd(addQuery as CFDictionary, nil)
    }

    static func load(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        migrateAccessibilityIfNeeded(key: key)
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    static func migrateAccessibilityIfNeeded(key: String) -> OSStatus {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        let attributes: [String: Any] = [
            kSecAttrAccessible as String: credentialAccessibility
        ]
        return SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
    }

    static func migrateKnownGitCredentialsIfNeeded(
        keys: [String],
        defaults: UserDefaults = .standard
    ) {
        // Deliberately rerun for every currently known Git credential. A single
        // global completion marker can miss repository keys discovered later.
        // `defaults` remains accepted for source compatibility with older tests.
        _ = defaults
        for key in Set(keys) {
            let status = migrateAccessibilityIfNeeded(key: key)
            guard status != errSecSuccess, status != errSecItemNotFound else { continue }
            DebugLogger.shared.error(
                "keychain",
                "Could not update Git credential accessibility",
                detail: "account: \(key), status: \(status)"
            )
        }
    }

    static func attributes(key: String) -> [String: Any]? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? [String: Any]
    }

    static func delete(key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
    }
}

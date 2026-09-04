import Combine
import Foundation
import UIKit

/// Manages Push Sync registration: APNs device token, the relay worker
/// registration, and the local "push sync enabled" state.
///
/// The flow is deliberately thin: a GitHub webhook hits our worker, the
/// worker sends a **visible** APNs notification, the user taps it, the app
/// opens, and the existing foreground reconciliation pulls. We never use
/// silent pushes (Apple throttles them to a few per hour and drops them in
/// Low Power Mode — a visible alert is the dependable path).
@MainActor
final class PushSyncManager: ObservableObject {
    static let shared = PushSyncManager()

    static let defaultWorkerURL = URL(string: "https://syncmd-push.codybontecou.workers.dev")!

    private static let enabledKey = "pushSyncEnabled"
    private static let workerURLKey = "pushSyncWorkerURL"
    private static let deviceSecretKeychainKey = "push_sync_device_secret"
    private static let lastRegistrationKey = "pushSyncLastRegistrationDate"

    @Published private(set) var isEnabled: Bool
    @Published private(set) var isRegistering = false
    @Published private(set) var lastError: String?
    @Published private(set) var lastRegistrationDate: Date?

    /// Injectable so tests can verify request building without network I/O.
    var urlSession: URLSession = .shared

    private init() {
        isEnabled = UserDefaults.standard.bool(forKey: Self.enabledKey)
        lastRegistrationDate = UserDefaults.standard.object(forKey: Self.lastRegistrationKey) as? Date
    }

    var workerURL: URL {
        get {
            if let raw = UserDefaults.standard.string(forKey: Self.workerURLKey), let url = URL(string: raw) {
                return url
            }
            return Self.defaultWorkerURL
        }
        set {
            UserDefaults.standard.set(newValue.absoluteString, forKey: Self.workerURLKey)
        }
    }

    // MARK: - Enable / disable

    func setEnabled(_ enabled: Bool) async {
        isEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: Self.enabledKey)
        guard enabled else {
            await unregister()
            UIApplication.shared.unregisterForRemoteNotifications()
            return
        }
        let center = UNUserNotificationCenter.current()
        let granted = (try? await center.requestAuthorization(options: [.alert, .badge, .sound])) ?? false
        guard granted else {
            lastError = String(localized: "Notifications were denied in Settings.")
            isEnabled = false
            UserDefaults.standard.set(false, forKey: Self.enabledKey)
            return
        }
        UIApplication.shared.registerForRemoteNotifications()
    }

    // MARK: - APNs token

    func handleDeviceToken(_ token: Data) async {
        guard isEnabled else { return }
        await register(tokenHex: Self.hexString(from: token))
    }

    func handleRegistrationFailure(_ error: Error) {
        lastError = error.localizedDescription
        DebugLogger.shared.warning("push-sync", "APNs registration failed", detail: error.localizedDescription)
    }

    // MARK: - Worker registration

    /// Re-registers the current repo set. Called on launch, when repos
    /// change, and after each APNs token delivery.
    func refreshRegistration(repos: [RepoConfig]) async {
        guard isEnabled else { return }
        guard let token = PushSyncManager.cachedDeviceTokenHex() else { return }
        await register(tokenHex: token, repos: repos)
    }

    private func register(tokenHex: String, repos: [RepoConfig]? = nil) async {
        let repos = repos ?? SyncRuntimeLocator.currentRepos()
        isRegistering = true
        defer { isRegistering = false }

        let body = Self.makeRegistrationBody(
            tokenHex: tokenHex,
            repos: repos,
            deviceSecret: deviceSecret()
        )
        var request = URLRequest(url: workerURL.appendingPathComponent("v1/register"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body

        do {
            let (_, response) = try await urlSession.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                throw URLError(.badServerResponse)
            }
            lastError = nil
            lastRegistrationDate = Date()
            UserDefaults.standard.set(lastRegistrationDate, forKey: Self.lastRegistrationKey)
            Self.cacheDeviceTokenHex(tokenHex)
        } catch {
            lastError = error.localizedDescription
            DebugLogger.shared.warning("push-sync", "Registration failed", detail: error.localizedDescription)
        }
    }

    private func unregister() async {
        guard let secret = KeychainService.load(key: Self.deviceSecretKeychainKey) else { return }
        struct UnregisterBody: Codable { let deviceSecret: String }
        var request = URLRequest(url: workerURL.appendingPathComponent("v1/unregister"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONEncoder().encode(UnregisterBody(deviceSecret: secret))
        _ = try? await urlSession.data(for: request)
        UserDefaults.standard.removeObject(forKey: Self.lastRegistrationKey)
        lastRegistrationDate = nil
    }

    private func deviceSecret() -> String {
        if let existing = KeychainService.load(key: Self.deviceSecretKeychainKey) {
            return existing
        }
        let secret = UUID().uuidString
        KeychainService.save(key: Self.deviceSecretKeychainKey, value: secret)
        return secret
    }

    // MARK: - Pure helpers (unit tested)

    /// Builds the JSON registration payload. Pure and testable.
    static func makeRegistrationBody(tokenHex: String, repos: [RepoConfig], deviceSecret: String) -> Data {
        struct Body: Codable {
            struct Repo: Codable { let name: String }
            let token: String
            let environment: String
            let repos: [Repo]
            let deviceSecret: String
        }
        #if DEBUG
        let environment = "development"
        #else
        let environment = "production"
        #endif
        let names = repos
            .filter(\.isCloned)
            .compactMap { repo -> String? in
                guard let remote = GitRemoteURL.parse(repo.repoURL),
                      remote.isGitHub,
                      let owner = remote.ownerName else { return nil }
                return "\(owner)/\(remote.repoName)"
            }
        let body = Body(
            token: tokenHex,
            environment: environment,
            repos: names.map { .init(name: $0) },
            deviceSecret: deviceSecret
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return (try? encoder.encode(body)) ?? Data()
    }

    static func hexString(from data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    private static let tokenCacheKey = "pushSyncCachedTokenHex"
    static func cacheDeviceTokenHex(_ hex: String) {
        UserDefaults.standard.set(hex, forKey: tokenCacheKey)
    }
    static func cachedDeviceTokenHex() -> String? {
        UserDefaults.standard.string(forKey: tokenCacheKey)
    }
}

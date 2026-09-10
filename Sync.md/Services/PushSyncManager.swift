import Combine
import CryptoKit
import Foundation
import UIKit
import UserNotifications

/// Validated routing hint carried by the relay's APNs notification.
///
/// The payload is never trusted as Git data: it only selects a configured
/// repository and branch. The app still authenticates to the configured
/// remote and fetches its authoritative state before changing the checkout.
struct PushSyncEvent: Sendable, Equatable {
    let repositoryFullName: String
    let branch: String
    let headSHA: String?
    let hintID: String

    static func parse(_ userInfo: [AnyHashable: Any]) -> PushSyncEvent? {
        guard let aps = userInfo["aps"] as? [String: Any],
              let contentAvailable = aps["content-available"] as? NSNumber,
              contentAvailable.intValue == 1,
              let rawRepository = userInfo["repo"] as? String,
              rawRepository.range(
                of: #"^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$"#,
                options: .regularExpression
              ) != nil,
              rawRepository.split(separator: "/", omittingEmptySubsequences: false)
                .allSatisfy({ $0 != "." && $0 != ".." }),
              let rawBranch = userInfo["branch"] as? String,
              !rawBranch.isEmpty,
              rawBranch.utf8.count <= 255,
              rawBranch.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }),
              let hintID = userInfo["hint"] as? String,
              hintID.range(of: #"^[A-Za-z0-9._:-]{1,128}$"#, options: .regularExpression) != nil else {
            return nil
        }

        let headSHA: String?
        if let rawHead = userInfo["head"] as? String {
            guard rawHead.range(
                of: #"^(?:[0-9A-Fa-f]{40}|[0-9A-Fa-f]{64})$"#,
                options: .regularExpression
            ) != nil else { return nil }
            headSHA = rawHead.lowercased()
        } else {
            headSHA = nil
        }

        return PushSyncEvent(
            repositoryFullName: rawRepository.lowercased(),
            branch: rawBranch,
            headSHA: headSHA,
            hintID: hintID
        )
    }
}

/// Manages Push Sync registration: APNs device token, the relay worker
/// registration, and the local "push sync enabled" state.
///
/// A GitHub webhook produces a visible APNs alert with a background-content
/// flag. iOS may wake the app to reconcile the affected repository without
/// presenting its UI; the alert remains the dependable tap-to-sync fallback
/// when iOS suppresses background execution.
@MainActor
final class PushSyncManager: ObservableObject {
    static let shared = PushSyncManager()

    static let defaultWorkerURL = URL(string: "https://syncmd-push.costream.workers.dev")!

    private static let enabledKey = "pushSyncEnabled"
    private static let workerURLKey = "pushSyncWorkerURL"
    private static let deviceSecretKeychainKey = "push_sync_device_secret"
    private static let lastRegistrationKey = "pushSyncLastRegistrationDate"
    private static let registrationFingerprintKey = "pushSyncRegistrationFingerprint"
    private static let registrationRefreshInterval: TimeInterval = 7 * 24 * 60 * 60

    @Published private(set) var isEnabled: Bool
    @Published private(set) var isRegistering = false
    @Published private(set) var isConnectingGitHubApp = false
    @Published private(set) var isLoadingGitHubAppStatus = false
    @Published private(set) var linkedGitHubInstallations: [PushSyncGitHubInstallation] = []
    @Published private(set) var lastError: String?
    @Published private(set) var lastRegistrationDate: Date?

    /// Injectable seams keep request/callback validation testable without
    /// presenting system UI or contacting production services.
    var urlSession: URLSession = .shared
    var githubAppConnector: (GitHubAppLinkStartResponse) async throws -> Void = { start in
        try await GitHubAppLinkService.shared.connect(start: start)
    }

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
            linkedGitHubInstallations = []
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

    /// Restores an opted-in installation after process launch. Apple advises
    /// registering on every launch because the device token may change; the
    /// cached token lets the relay inventory refresh while APNs replies.
    func resumeRegistration(repos: [RepoConfig]) async {
        guard isEnabled else { return }
        UIApplication.shared.registerForRemoteNotifications()
        _ = await refreshRegistration(repos: repos)
        // Status refresh must not depend on winning the APNs registration race.
        // didRegisterForRemoteNotifications can enter register(_:) first; in that
        // case refreshRegistration returns early even though an existing signed
        // device record is available for status lookup.
        await refreshGitHubAppStatus()
    }

    func handleDeviceToken(_ token: Data) async {
        guard isEnabled else { return }
        if await register(tokenHex: Self.hexString(from: token)),
           UIApplication.shared.applicationState == .active {
            await refreshGitHubAppStatus()
        }
    }

    func handleRegistrationFailure(_ error: Error) {
        lastError = error.localizedDescription
        DebugLogger.shared.warning("push-sync", "APNs registration failed", detail: error.localizedDescription)
    }

    // MARK: - Worker registration

    /// Re-registers the current repo set. Called on launch, when repos
    /// change, and after each APNs token delivery.
    @discardableResult
    func refreshRegistration(repos: [RepoConfig]) async -> Bool {
        guard isEnabled else { return false }
        guard let token = PushSyncManager.cachedDeviceTokenHex() else { return false }
        return await register(tokenHex: token, repos: repos)
    }

    @discardableResult
    private func register(tokenHex: String, repos: [RepoConfig]? = nil) async -> Bool {
        let repos = repos ?? SyncRuntimeLocator.currentRepos()
        isRegistering = true
        defer { isRegistering = false }

        let secret = deviceSecret()
        let body = Self.makeRegistrationBody(
            tokenHex: tokenHex,
            repos: repos,
            deviceSecret: secret
        )
        let fingerprint = Self.registrationFingerprint(for: body)
        let defaults = UserDefaults.standard
        if defaults.string(forKey: Self.registrationFingerprintKey) == fingerprint,
           let lastRegistrationDate,
           Date().timeIntervalSince(lastRegistrationDate) >= 0,
           Date().timeIntervalSince(lastRegistrationDate) < Self.registrationRefreshInterval {
            Self.cacheDeviceTokenHex(tokenHex)
            return true
        }
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
            defaults.set(lastRegistrationDate, forKey: Self.lastRegistrationKey)
            defaults.set(fingerprint, forKey: Self.registrationFingerprintKey)
            Self.cacheDeviceTokenHex(tokenHex)
            return true
        } catch {
            lastError = error.localizedDescription
            DebugLogger.shared.warning("push-sync", "Registration failed", detail: error.localizedDescription)
            return false
        }
    }

    // MARK: - GitHub App connection

    func connectGitHubApp() async {
        guard isEnabled, !isConnectingGitHubApp else { return }
        guard let token = Self.cachedDeviceTokenHex() else {
            lastError = GitHubAppLinkError.registrationRequired.localizedDescription
            return
        }
        isConnectingGitHubApp = true
        defer { isConnectingGitHubApp = false }

        guard await register(tokenHex: token) else { return }
        var request = URLRequest(url: workerURL.appendingPathComponent("v1/github-app/link/start"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Self.makeDeviceRequestBody(deviceSecret: deviceSecret())

        do {
            let (data, response) = try await urlSession.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw GitHubAppLinkError.unavailable
            }
            if http.statusCode == 409 { throw GitHubAppLinkError.registrationRequired }
            if http.statusCode == 503 { throw GitHubAppLinkError.unavailable }
            guard (200..<300).contains(http.statusCode) else {
                throw GitHubAppLinkError.authorizationFailed
            }
            let start = try JSONDecoder().decode(GitHubAppLinkStartResponse.self, from: data)
            try GitHubAppLinkService.validateStart(start)
            try await githubAppConnector(start)
            var statusRefreshed = false
            for delay in [UInt64(0), 500_000_000, 1_500_000_000, 3_000_000_000] {
                if delay > 0 { try? await Task.sleep(nanoseconds: delay) }
                statusRefreshed = await refreshGitHubAppStatus()
                if statusRefreshed, !linkedGitHubInstallations.isEmpty { break }
            }
            guard statusRefreshed, !linkedGitHubInstallations.isEmpty else {
                throw GitHubAppLinkError.verificationFailed
            }
            lastError = nil
            DebugLogger.shared.info("push-sync", "GitHub App connection verified")
        } catch let error as GitHubAppLinkError {
            lastError = error.localizedDescription
            if error != .cancelled {
                DebugLogger.shared.warning("push-sync", "GitHub App connection failed", detail: error.localizedDescription)
            }
        } catch {
            lastError = GitHubAppLinkError.authorizationFailed.localizedDescription
            DebugLogger.shared.warning("push-sync", "GitHub App connection failed", detail: error.localizedDescription)
        }
    }

    @discardableResult
    func refreshGitHubAppStatus() async -> Bool {
        guard isEnabled, !isLoadingGitHubAppStatus else { return false }
        isLoadingGitHubAppStatus = true
        defer { isLoadingGitHubAppStatus = false }

        var request = URLRequest(url: workerURL.appendingPathComponent("v1/github-app/status"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Self.makeDeviceRequestBody(deviceSecret: deviceSecret())
        do {
            let (data, response) = try await urlSession.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
            if http.statusCode == 404 {
                linkedGitHubInstallations = []
                UserDefaults.standard.removeObject(forKey: Self.registrationFingerprintKey)
                UserDefaults.standard.removeObject(forKey: Self.lastRegistrationKey)
                lastRegistrationDate = nil
                return true
            }
            guard (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
            let status = try JSONDecoder().decode(GitHubAppLinkStatusResponse.self, from: data)
            let installations = try GitHubAppLinkService.validateStatus(status)
            linkedGitHubInstallations = installations.sorted {
                $0.accountLogin.localizedCaseInsensitiveCompare($1.accountLogin) == .orderedAscending
            }
            return true
        } catch {
            // Keep the most recently verified local status on a transient read
            // failure; registration and delivery remain authoritative remotely.
            DebugLogger.shared.warning("push-sync", "GitHub App status refresh failed", detail: error.localizedDescription)
            return false
        }
    }

    func unlinkGitHubAppInstallation(id: Int64) async {
        guard isEnabled else { return }
        var request = URLRequest(url: workerURL.appendingPathComponent("v1/github-app/unlink"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Self.makeInstallationRequestBody(
            deviceSecret: deviceSecret(),
            installationID: id
        )
        do {
            let (_, response) = try await urlSession.data(for: request)
            guard let http = response as? HTTPURLResponse,
                  (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
            linkedGitHubInstallations.removeAll { $0.id == id }
        } catch {
            lastError = error.localizedDescription
            DebugLogger.shared.warning("push-sync", "GitHub App unlink failed", detail: error.localizedDescription)
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
        UserDefaults.standard.removeObject(forKey: Self.registrationFingerprintKey)
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

    nonisolated static func makeDeviceRequestBody(deviceSecret: String) -> Data {
        struct Body: Codable { let deviceSecret: String }
        return (try? JSONEncoder().encode(Body(deviceSecret: deviceSecret))) ?? Data()
    }

    nonisolated static func makeInstallationRequestBody(deviceSecret: String, installationID: Int64) -> Data {
        struct Body: Codable {
            let deviceSecret: String
            let installationID: Int64
        }
        return (try? JSONEncoder().encode(Body(
            deviceSecret: deviceSecret,
            installationID: installationID
        ))) ?? Data()
    }

    /// Builds the JSON registration payload. Pure and testable.
    static func makeRegistrationBody(tokenHex: String, repos: [RepoConfig], deviceSecret: String) -> Data {
        struct Body: Codable {
            let token: String
            let environment: String
            let repos: [String]
            let deviceSecret: String
        }
        #if DEBUG
        let environment = "development"
        #else
        let environment = "production"
        #endif
        let names = Array(Set(repos
            .filter(\.isCloned)
            .compactMap { repo -> String? in
                guard let remote = GitRemoteURL.parse(repo.repoURL),
                      remote.isGitHub,
                      let owner = remote.ownerName else { return nil }
                return "\(owner)/\(remote.repoName)".lowercased()
            }))
            .sorted()
        let body = Body(
            token: tokenHex,
            environment: environment,
            repos: names,
            deviceSecret: deviceSecret
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return (try? encoder.encode(body)) ?? Data()
    }

    nonisolated static func registrationFingerprint(for body: Data) -> String {
        SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined()
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

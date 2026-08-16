import Foundation
import Observation
import Security
import UIKit

protocol RemoteNotificationRegistering: Sendable { @MainActor func register(); @MainActor func unregister() }
struct UIApplicationRemoteNotificationRegistrar: RemoteNotificationRegistering {
    @MainActor func register() { UIApplication.shared.registerForRemoteNotifications() }
    @MainActor func unregister() { UIApplication.shared.unregisterForRemoteNotifications() }
}

protocol PremiumKeychainStoring {
    func load(key: String) -> String?
    @discardableResult func save(key: String, value: String) -> OSStatus
    func delete(key: String)
}

struct SystemPremiumKeychainStore: PremiumKeychainStoring {
    func load(key: String) -> String? { KeychainService.load(key: key) }
    func save(key: String, value: String) -> OSStatus { KeychainService.save(key: key, value: value) }
    func delete(key: String) { KeychainService.delete(key: key) }
}

@MainActor
final class PremiumNotificationBridge {
    static let shared = PremiumNotificationBridge()
    private weak var runtime: PremiumRuntime?
    private var timeoutNanoseconds: UInt64 = 25_000_000_000
    private var processPushOverride: (([AnyHashable: Any]) async -> BackgroundSyncDisposition)?
    private var cancelPushOverride: (([AnyHashable: Any]) -> Void)?

    func connect(runtime: PremiumRuntime, timeoutNanoseconds: UInt64 = 25_000_000_000) {
        self.runtime = runtime; self.timeoutNanoseconds = timeoutNanoseconds
        processPushOverride = nil; cancelPushOverride = nil
    }

    /// Deterministic bridge harness used only by unit tests. Production always
    /// connects a `PremiumRuntime` through `connect(runtime:)`.
    func connectForTesting(
        timeoutNanoseconds: UInt64,
        processPush: @escaping ([AnyHashable: Any]) async -> BackgroundSyncDisposition,
        cancelPush: @escaping ([AnyHashable: Any]) -> Void
    ) {
        runtime = nil; self.timeoutNanoseconds = timeoutNanoseconds
        processPushOverride = processPush; cancelPushOverride = cancelPush
    }
    func didRegister(token: Data) { runtime?.didRegister(token: token) }
    func didFail(error: Error) { runtime?.didFailToRegister(error: error) }

    func didReceive(userInfo: [AnyHashable: Any], completion: @escaping (UIBackgroundFetchResult) -> Void) {
        guard runtime != nil || processPushOverride != nil else { completion(.failed); return }
        let timeout = timeoutNanoseconds
        let gate = PremiumPushCompletionGate()
        let operation = Task { @MainActor [weak self] in
            guard let self else { return }
            let result: BackgroundSyncDisposition
            if let processPushOverride { result = await processPushOverride(userInfo) }
            else if let runtime { result = await runtime.processPush(userInfo) }
            else { result = .ignored }
            guard await gate.claim() else { return }
            completion(Self.fetchResult(.result(result)))
        }
        // This is intentionally not a structured task-group race. A task group
        // waits for a cancelled child to unwind before leaving its scope, which
        // can delay Apple's completion handler past the background deadline if
        // a provider fetch is slow. The one-shot gate bounds and de-duplicates
        // completion while cancellation continues independently.
        Task { @MainActor in
            do { try await Task.sleep(nanoseconds: timeout) }
            catch { return }
            guard await gate.claim() else { return }
            operation.cancel()
            if let cancelPushOverride { cancelPushOverride(userInfo) }
            else { runtime?.cancelPush(userInfo) }
            completion(.failed)
        }
    }

    private enum BridgeOutcome: Sendable { case result(BackgroundSyncDisposition) }
    private static func fetchResult(_ outcome: BridgeOutcome) -> UIBackgroundFetchResult {
        switch outcome {
        case .result(.completed(.updated)): return .newData
        case .result(.completed(.failed)): return .failed
        case .result(.completed), .result(.deferred), .result(.ignored): return .noData
        }
    }
}

actor PremiumPushCompletionGate {
    private var completed = false
    func claim() -> Bool {
        guard !completed else { return false }
        completed = true
        return true
    }
}

final class SyncMDApplicationDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken token: Data) {
        Task { @MainActor in PremiumNotificationBridge.shared.didRegister(token: token) }
    }
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        Task { @MainActor in PremiumNotificationBridge.shared.didFail(error: error) }
    }
    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completion: @escaping (UIBackgroundFetchResult) -> Void) {
        Task { @MainActor in PremiumNotificationBridge.shared.didReceive(userInfo: userInfo, completion: completion) }
    }
}

@MainActor
@Observable
final class PremiumRuntime {
    private(set) var registrationError: String?
    private(set) var latestToken: String?
    private(set) var isRegistered = false
    private(set) var githubInstallations: [PremiumGitHubInstallationSummary] = []
    private(set) var hasRelayConsent: Bool
    private(set) var relayDataWasDeleted = false
    var relayIsConfigured: Bool { api is PremiumAPIClient && PremiumAPIConfiguration().baseURL != nil }
    var canDeleteRelayData: Bool { !relayDataWasDeleted && credential?.canDelete(for: installation.installationID) == true }

    let entitlementStore: PremiumEntitlementStore
    let coordinator: BackgroundSyncCoordinator
    private let api: any PremiumAPIClientProtocol
    private let registrar: any RemoteNotificationRegistering
    private let installation: PremiumInstallation
    private let environment: APNsEnvironment
    private let keychain: any PremiumKeychainStoring
    private weak var repositoryProvider: (any AssistRepositoryProviding)?
    private var startupTask: Task<Void, Never>?
    private var credential: PremiumInstallationCredential?
    private var registrationTask: Task<Void, Never>?
    private var tokenGeneration: UInt64
    private var deletionInProgress = false
    private let tokenKey: String
    private let tokenGenerationKey: String
    private let tokenGenerationKeychainKey: String
    private let relayConsentKey: String
    private let deletionBarrierKey: String
    private let deletionCredentialKey: String
    private let deletionStateKey: String

    init(entitlementStore: PremiumEntitlementStore, coordinator: BackgroundSyncCoordinator,
         repositoryProvider: any AssistRepositoryProviding, api: any PremiumAPIClientProtocol,
         registrar: any RemoteNotificationRegistering, installation: PremiumInstallation,
         environment: APNsEnvironment, bridge: PremiumNotificationBridge = .shared,
         keychain: (any PremiumKeychainStoring)? = nil) {
        self.entitlementStore = entitlementStore; self.coordinator = coordinator; self.repositoryProvider = repositoryProvider
        self.api = api; self.registrar = registrar; self.installation = installation; self.environment = environment
        let resolvedKeychain = keychain ?? SystemPremiumKeychainStore()
        self.keychain = resolvedKeychain
        tokenKey = "premium.apns-token.\(environment.rawValue).\(installation.installationID.uuidString)"
        tokenGenerationKey = "premium.apns-token-generation.\(environment.rawValue).\(installation.installationID.uuidString)"
        tokenGenerationKeychainKey = "premium.apns-token-generation.keychain.\(environment.rawValue).\(installation.installationID.uuidString)"
        let defaultsGeneration = (UserDefaults.standard.object(forKey: tokenGenerationKey) as? NSNumber)?.uint64Value ?? 0
        let keychainGeneration = resolvedKeychain.load(key: tokenGenerationKeychainKey).flatMap(UInt64.init) ?? 0
        let restoredTokenGeneration = max(defaultsGeneration, keychainGeneration)
        tokenGeneration = restoredTokenGeneration
        relayConsentKey = "premium.relay-consent.\(installation.installationID.uuidString)"
        deletionBarrierKey = "premium.relay-deletion-barrier.\(installation.installationID.uuidString)"
        deletionCredentialKey = "premium.relay-deletion-credential.\(installation.installationID.uuidString)"
        deletionStateKey = "premium.relay-deletion-state.\(installation.installationID.uuidString)"
        if restoredTokenGeneration > 0 {
            UserDefaults.standard.set(NSNumber(value: restoredTokenGeneration), forKey: tokenGenerationKey)
            resolvedKeychain.save(key: tokenGenerationKeychainKey, value: String(restoredTokenGeneration))
        }
        if let encoded = resolvedKeychain.load(key: deletionCredentialKey),
           let data = Data(base64Encoded: encoded),
           let restored = try? JSONDecoder().decode(PremiumInstallationCredential.self, from: data),
           restored.canDelete(for: installation.installationID) {
            credential = restored
        }
        let deletionState = resolvedKeychain.load(key: deletionStateKey)
        let persistedDeletionBarrier = UserDefaults.standard.bool(forKey: deletionBarrierKey) || deletionState == "pending"
        let completedDeletion = deletionState == "completed"
        deletionInProgress = persistedDeletionBarrier
        relayDataWasDeleted = completedDeletion
        hasRelayConsent = !persistedDeletionBarrier && !completedDeletion
            && (UserDefaults.standard.bool(forKey: relayConsentKey)
                || repositoryProvider.assistRepositories().contains { $0.assist.channel != nil })
        latestToken = resolvedKeychain.load(key: tokenKey)
        bridge.connect(runtime: self)
        repositoryProvider.setAssistConfigurationChangeHandler { [weak self] in self?.configurationChanged() }
        (repositoryProvider as? AppState)?.assistRepositoryRemovalHandler = { [weak self] repo in
            self?.repositoryWillBeRemoved(repo)
        }
    }
    convenience init(entitlementStore: PremiumEntitlementStore, coordinator: BackgroundSyncCoordinator,
                     repositoryProvider: any AssistRepositoryProviding) {
        self.init(entitlementStore: entitlementStore, coordinator: coordinator, repositoryProvider: repositoryProvider,
                  api: PremiumAPIClient(), registrar: UIApplicationRemoteNotificationRegistrar(),
                  installation: PremiumInstallationIdentity.current(), environment: APNsDeviceToken.buildEnvironment)
    }
    func start() async {
        if let startupTask { await startupTask.value; return }
        if deletionInProgress {
            await retryPersistedDeletionIfNeeded()
            if deletionInProgress { return }
        }
        await entitlementStore.bindAppAccountToken(installation.installationID)
        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            entitlementStore.onChange = { [weak self] state in self?.entitlementChanged(state) }
            await entitlementStore.start()
        }
        startupTask = task; await task.value
    }

    func didRegister(token data: Data) {
        guard hasRelayConsent, !deletionInProgress else { return }
        let newToken = APNsDeviceToken.hex(data), oldToken = latestToken
        tokenGeneration &+= 1
        persistTokenGeneration()
        let generation = tokenGeneration
        latestToken = newToken; keychain.save(key: tokenKey, value: newToken); registrationError = nil
        registrationTask?.cancel()
        registrationTask = Task { [weak self] in
            await self?.replaceAndRegister(oldToken: oldToken, newToken: newToken, generation: generation)
        }
    }
    func didFailToRegister(error: Error) { registrationError = error.localizedDescription; isRegistered = false }

    func processPush(_ userInfo: [AnyHashable: Any]) async -> BackgroundSyncDisposition {
        await start(); await entitlementStore.refresh()
        guard hasRelayConsent, entitlementStore.state.isActive else { coordinator.cancelAll(); return .ignored }
        await ensureAuthorizedAndRegistered()
        return await coordinator.handlePush(userInfo)
    }
    func cancelPush(_ userInfo: [AnyHashable: Any]) { coordinator.cancelPush(userInfo) }

    func reconcileForeground() async {
        await start(); await entitlementStore.refresh()
        guard hasRelayConsent, entitlementStore.state.isActive else { coordinator.cancelAll(); return }
        await ensureAuthorizedAndRegistered()
        _ = await coordinator.reconcileForeground()
    }

    func prepareForSettings() async {
        await start()
        await entitlementStore.refresh()
        if hasRelayConsent, entitlementStore.state.isActive {
            await ensureAuthorizedAndRegistered()
            await refreshGitHubInstallations()
        }
    }

    func startGitHubLink() async -> URL? {
        await start()
        await entitlementStore.refresh()
        guard entitlementStore.state.isActive, !deletionInProgress else { return nil }
        guard !relayDataWasDeleted else {
            registrationError = String(localized: "Assist relay data was permanently deleted for this installation. Contact support if you need to use Assist again.")
            return nil
        }
        setRelayConsent(true)
        registrar.register()
        await ensureAuthorizedAndRegistered()
        guard let relay = api as? any PremiumRelayManaging, let credential else { return nil }
        do { return try await relay.startGitHubLink(credential: credential).url }
        catch {
            if isAuthorizationRejection(error), hasRelayConsent, !deletionInProgress,
               case .active(let proof) = entitlementStore.state {
                self.credential = nil
                do {
                    let renewed = try await authorizeAndPersist(proof: proof)
                    guard hasRelayConsent, !deletionInProgress, entitlementStore.state.isActive else { return nil }
                    return try await relay.startGitHubLink(credential: renewed).url
                } catch { registrationError = error.localizedDescription; return nil }
            }
            registrationError = error.localizedDescription; return nil
        }
    }

    func refreshGitHubInstallations() async {
        guard let relay = api as? any PremiumRelayManaging, let credential else { return }
        do { githubInstallations = try await relay.githubInstallations(credential: credential) }
        catch { registrationError = error.localizedDescription }
    }

    func enroll(repoID: UUID, githubInstallationID: Int64, repositoryID: Int64, branch: String) async -> Bool {
        guard hasRelayConsent, !deletionInProgress, entitlementStore.state.isActive,
              let relay = api as? any PremiumRelayManaging, let credential, let provider = repositoryProvider,
              provider.assistRepository(id: repoID) != nil else { return false }
        do {
            let enrollment = try await relay.createEnrollment(
                .init(githubInstallationID: githubInstallationID, repositoryID: repositoryID, branch: branch),
                credential: credential
            )
            guard hasRelayConsent, !deletionInProgress, entitlementStore.state.isActive,
                  credential.isValid(for: installation.installationID), self.credential == credential,
                  OpaqueAssistIdentifier.isValid(enrollment.channel),
                  var repo = provider.assistRepository(id: repoID) else {
                // The relay response may arrive after entitlement loss or an
                // installation purge. Never restore local consent/enrollment
                // from that stale response; a completed purge already removed
                // the remote row, and an in-flight purge remains fail-closed.
                return false
            }
            repo.assist.channel = enrollment.channel
            repo.assist.selectedBranch = branch
            repo.assist.enabled = true
            repo.assist.health = .never
            provider.updateAssistSettings(repoID: repoID, repo.assist)
            await ensureAuthorizedAndRegistered()
            return hasRelayConsent && !deletionInProgress && entitlementStore.state.isActive
        } catch { registrationError = error.localizedDescription; return false }
    }

    func unenroll(repoID: UUID) async -> Bool {
        guard let relay = api as? any PremiumRelayManaging, let credential, let provider = repositoryProvider,
              let repo = provider.assistRepository(id: repoID), let channel = repo.assist.channel else { return false }
        do {
            try await relay.deleteEnrollment(channel: channel, credential: credential)
            provider.updateAssistSettings(repoID: repoID, .disabled)
            await ensureAuthorizedAndRegistered()
            return true
        } catch { registrationError = error.localizedDescription; return false }
    }

    func deleteRelayData() async {
        guard !relayDataWasDeleted else { return }
        if deletionInProgress {
            await retryPersistedDeletionIfNeeded()
            return
        }
        guard let credential, let provider = repositoryProvider,
              let relay = api as? any PremiumRelayManaging else { return }
        deletionInProgress = true
        UserDefaults.standard.set(true, forKey: deletionBarrierKey)
        setRelayConsent(false)
        tokenGeneration &+= 1
        persistTokenGeneration()
        registrationTask?.cancel()
        registrationTask = nil
        coordinator.cancelAll()
        registrar.unregister()
        guard ensurePendingDeletionState(), persistDeletionCredential(credential) else {
            // The deletion intent is fail-closed. Never erase a pre-existing
            // pending/completed marker or reopen consent when persistence is
            // unavailable; restart/support recovery must retain the barrier.
            if !relayDataWasDeleted {
                registrationError = String(localized: "Could not securely save the relay deletion authorization. Assist remains disabled; retry or contact support.")
            }
            return
        }
        do {
            try await relay.deleteInstallation(credential: credential)
            for repo in provider.assistRepositories() {
                provider.updateAssistSettings(repoID: repo.id, .disabled)
            }
            self.credential = nil
            isRegistered = false
            githubInstallations = []
            guard completeDeletionBarrier() else {
                registrationError = String(localized: "Relay deletion completed, but its local receipt could not be saved. Retry to finish securely.")
                return
            }
            registrationError = nil
        } catch {
            registrationError = error.localizedDescription
            // Remain in the deletion barrier so configuration callbacks cannot
            // resurrect relay data. A later explicit deletion call can retry.
        }
    }

    private func persistDeletionCredential(_ credential: PremiumInstallationCredential) -> Bool {
        guard let data = try? JSONEncoder().encode(credential) else { return false }
        let encoded = data.base64EncodedString()
        guard keychain.save(key: deletionCredentialKey, value: encoded) == errSecSuccess else { return false }
        return keychain.load(key: deletionCredentialKey) == encoded
    }

    private func persistedDeletionCredential() -> PremiumInstallationCredential? {
        guard let encoded = keychain.load(key: deletionCredentialKey),
              let data = Data(base64Encoded: encoded),
              let credential = try? JSONDecoder().decode(PremiumInstallationCredential.self, from: data),
              credential.canDelete(for: installation.installationID) else { return nil }
        return credential
    }

    private func ensurePendingDeletionState() -> Bool {
        switch keychain.load(key: deletionStateKey) {
        case "completed":
            adoptCompletedDeletionState()
            return false
        case "pending":
            return true
        default:
            guard keychain.save(key: deletionStateKey, value: "pending") == errSecSuccess,
                  keychain.load(key: deletionStateKey) == "pending" else { return false }
            return true
        }
    }

    private func adoptCompletedDeletionState() {
        credential = nil
        deletionInProgress = false
        relayDataWasDeleted = true
        setRelayConsent(false)
        UserDefaults.standard.removeObject(forKey: deletionBarrierKey)
        keychain.delete(key: deletionCredentialKey)
    }

    @discardableResult
    private func completeDeletionBarrier() -> Bool {
        guard keychain.save(key: deletionStateKey, value: "completed") == errSecSuccess,
              keychain.load(key: deletionStateKey) == "completed" else { return false }
        adoptCompletedDeletionState()
        return true
    }

    private func retryPersistedDeletionIfNeeded() async {
        guard deletionInProgress, let relay = api as? any PremiumRelayManaging else { return }
        guard ensurePendingDeletionState() else {
            if !relayDataWasDeleted {
                registrationError = String(localized: "Relay deletion is pending but its durable state could not be saved. Assist remains disabled; retry or contact support.")
            }
            return
        }
        guard let credential = persistedDeletionCredential() else {
            // A session may expire locally even though an earlier idempotent
            // delete reached the relay before the process died. Preserve the
            // fail-closed barrier and expose an explicit recovery path rather
            // than recreating relay data automatically.
            registrationError = String(localized: "Relay deletion is pending but its authorization expired. Contact support to verify deletion and reset this installation.")
            return
        }
        do {
            try await relay.deleteInstallation(credential: credential)
            if let provider = repositoryProvider {
                for repo in provider.assistRepositories() {
                    provider.updateAssistSettings(repoID: repo.id, .disabled)
                }
            }
            isRegistered = false
            githubInstallations = []
            guard completeDeletionBarrier() else {
                registrationError = String(localized: "Relay deletion completed, but its local receipt could not be saved. Retry to finish securely.")
                return
            }
            registrationError = nil
        } catch {
            registrationError = error.localizedDescription
        }
    }

    private func entitlementChanged(_ state: PremiumEntitlementState) {
        switch state {
        case .active:
            if hasRelayConsent { registrar.register() }
        case .inactive:
            registrar.unregister(); coordinator.cancelAll(); isRegistered = false
            // Retain an unexpired installation credential only for authenticated
            // deletion after subscription expiry. Active operations still gate
            // on current StoreKit entitlement and server-side entitlement state.
            let oldCredential = credential
            if let oldCredential {
                let deletion = PremiumDeviceDeletionRequest(installationID: installation.installationID, token: latestToken, environment: environment)
                Task {
                    // Keep the last credential in memory when deletion fails so
                    // a later inactive refresh can retry rather than orphaning
                    // a remotely registered token.
                    do { try await api.deleteDevice(deletion, credential: oldCredential) }
                    catch {
                        if case .inactive = self.entitlementStore.state,
                           oldCredential.isValid(for: installation.installationID) {
                            self.registrationError = error.localizedDescription
                        }
                    }
                }
            }
        case .loading, .pending, .error:
            coordinator.cancelAll(); isRegistered = false
        }
    }

    @discardableResult
    private func ensureAuthorizedAndRegistered(
        expectedToken: String? = nil,
        generation: UInt64? = nil,
        mayReauthorize: Bool = true
    ) async -> String? {
        guard hasRelayConsent, !deletionInProgress, !relayDataWasDeleted,
              case .active(let proof) = entitlementStore.state else { return nil }
        do {
            if credential?.isValid(for: installation.installationID) != true {
                let authorized = try await authorizeAndPersist(proof: proof)
                guard hasRelayConsent, !deletionInProgress, entitlementStore.state.isActive,
                      generation == nil || generation == tokenGeneration else { return nil }
                credential = authorized
            }
            let registered = try await registerLatestTokenIfPossible(expectedToken: expectedToken, generation: generation)
            if let generation, generation != tokenGeneration { return nil }
            if let registered, registered == latestToken, generation == nil || generation == tokenGeneration {
                isRegistered = true
                registrationError = nil
            }
            return registered
        } catch {
            if mayReauthorize, isAuthorizationRejection(error),
               credential?.isValid(for: installation.installationID) == true {
                credential = nil
                return await ensureAuthorizedAndRegistered(
                    expectedToken: expectedToken,
                    generation: generation,
                    mayReauthorize: false
                )
            }
            if generation == nil || generation == tokenGeneration {
                registrationError = error.localizedDescription
                isRegistered = false
            }
            return nil
        }
    }

    private func authorizeAndPersist(proof: PremiumEntitlementProof) async throws -> PremiumInstallationCredential {
        let authorized = try await api.authorizeEntitlement(.init(installation: installation, proof: proof))
        guard persistDeletionCredential(authorized) else { throw PremiumAPIError.invalidCredential }
        credential = authorized
        return authorized
    }

    private func isAuthorizationRejection(_ error: Error) -> Bool {
        guard case PremiumAPIError.rejected(let status) = error else { return false }
        return status == 401
    }

    private func replaceAndRegister(oldToken: String?, newToken: String, generation: UInt64) async {
        guard entitlementStore.state.isActive else { return }
        let registered = await ensureAuthorizedAndRegistered(expectedToken: newToken, generation: generation)
        guard registered == newToken, generation == tokenGeneration, latestToken == newToken,
              let oldToken, oldToken != newToken, let credential else { return }
        let deletion = PremiumDeviceDeletionRequest(installationID: installation.installationID, token: oldToken, environment: environment)
        do { try await api.deleteDevice(deletion, credential: credential) }
        catch {
            guard generation == tokenGeneration else { return }
            registrationError = error.localizedDescription
        }
    }

    private func registerLatestTokenIfPossible(expectedToken: String? = nil, generation: UInt64? = nil) async throws -> String? {
        guard entitlementStore.state.isActive, let token = latestToken, let provider = repositoryProvider,
              expectedToken == nil || expectedToken == token,
              let credential, credential.isValid(for: installation.installationID) else { return nil }
        let channels = provider.assistRepositories().compactMap { repo -> String? in
            guard repo.assist.enabled, let channel = repo.assist.channel, OpaqueAssistIdentifier.isValid(channel) else { return nil }
            return channel
        }
        let registrationGeneration: UInt64
        if let generation { registrationGeneration = generation }
        else {
            tokenGeneration &+= 1
            persistTokenGeneration()
            registrationGeneration = tokenGeneration
        }
        let request = PremiumDeviceRegistrationRequest(installation: installation, token: token,
            environment: environment, channels: Array(Set(channels)).sorted(),
            registrationGeneration: registrationGeneration)
        try await api.registerDevice(request, credential: credential)
        guard hasRelayConsent, !deletionInProgress, entitlementStore.state.isActive, latestToken == token,
              expectedToken == nil || expectedToken == token else { return nil }
        return token
    }

    private func persistTokenGeneration() {
        let value = String(tokenGeneration)
        UserDefaults.standard.set(NSNumber(value: tokenGeneration), forKey: tokenGenerationKey)
        keychain.save(key: tokenGenerationKeychainKey, value: value)
    }

    private func repositoryWillBeRemoved(_ repo: RepoConfig) {
        guard let channel = repo.assist.channel, let relay = api as? any PremiumRelayManaging,
              let credential else { return }
        Task {
            do { try await relay.deleteEnrollment(channel: channel, credential: credential) }
            catch {
                // Local removal must never be blocked. The authenticated
                // installation purge remains available to remove orphaned
                // server records when connectivity returns.
                self.registrationError = error.localizedDescription
            }
        }
    }

    private func setRelayConsent(_ enabled: Bool) {
        hasRelayConsent = enabled
        UserDefaults.standard.set(enabled, forKey: relayConsentKey)
    }

    private func configurationChanged() {
        guard hasRelayConsent, !deletionInProgress else { return }
        registrationTask?.cancel()
        registrationTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled else { return }
            await self?.ensureAuthorizedAndRegistered()
        }
    }
}

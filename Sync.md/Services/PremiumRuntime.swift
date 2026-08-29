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

private enum GitHubInstallationRefreshResponse: Sendable {
    case success(PremiumInstallationCredential, [PremiumGitHubInstallationSummary])
    case failure(PremiumInstallationCredential, String)
}

private struct GitHubInstallationRefreshWaiter {
    let generation: UInt64
    let continuation: CheckedContinuation<Bool, Never>
}

final class SyncMDApplicationDelegate: NSObject, UIApplicationDelegate {
    static func shouldForwardAssistCallbacks(
        assistFeatureIsEnabled: () -> Bool = { FeatureFlags.gitSyncAssistEnabled }
    ) -> Bool {
        assistFeatureIsEnabled()
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken token: Data) {
        guard Self.shouldForwardAssistCallbacks() else { return }
        Task { @MainActor in PremiumNotificationBridge.shared.didRegister(token: token) }
    }
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        guard Self.shouldForwardAssistCallbacks() else { return }
        Task { @MainActor in PremiumNotificationBridge.shared.didFail(error: error) }
    }
    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completion: @escaping (UIBackgroundFetchResult) -> Void) {
        guard Self.shouldForwardAssistCallbacks() else { completion(.noData); return }
        Task { @MainActor in PremiumNotificationBridge.shared.didReceive(userInfo: userInfo, completion: completion) }
    }
}

@MainActor
@Observable
final class PremiumRuntime {
    private(set) var deviceRegistrationError: String?
    private(set) var githubError: String?
    private(set) var relayError: String?
    private(set) var deletionError: String?
    /// Backward-compatible aggregate for existing callers. Category-specific
    /// properties remain readable so one error can never hide another.
    var registrationError: String? {
        deletionError ?? deviceRegistrationError ?? githubError ?? relayError
    }
    private(set) var latestToken: String?
    private(set) var isRegistered = false
    private(set) var githubInstallations: [PremiumGitHubInstallationSummary] = []
    private(set) var hasRelayConsent: Bool
    private(set) var automaticallySyncAllRepositories: Bool
    private(set) var isReconcilingAutomaticSync = false
    /// Durable terminal-deletion barrier, including retryable failed requests.
    private(set) var deletionInProgress = false
    /// True only while an installation DELETE request is on the wire.
    private(set) var deletionRequestInFlight = false
    private(set) var relayDataWasDeleted = false
    var automaticSyncSummary: PremiumAssistSummary {
        let repos = repositoryProvider?.assistRepositories() ?? []
        return PremiumAssistSummary(
            total: repos.count,
            enrolled: repos.filter { $0.assist.enrollmentStatus == .enrolled }.count,
            foregroundOnly: repos.filter { $0.assist.enrollmentStatus == .foregroundOnly }.count,
            excluded: repos.filter { $0.assist.enrollmentStatus == .excluded }.count,
            disabled: repos.filter { $0.assist.enrollmentStatus == .disabled }.count,
            failed: repos.filter { $0.assist.enrollmentStatus == .failed }.count
        )
    }
    var relayIsConfigured: Bool {
        guard let client = api as? PremiumAPIClient else {
            // Injected relay implementations are explicit dependencies used by
            // tests and previews; only the production client has configuration.
            return true
        }
        return client.configuration.baseURL != nil
    }
    var canDeleteRelayData: Bool {
        !deletionInProgress && !relayDataWasDeleted
            && credential?.canDelete(for: installation.installationID) == true
    }
    var canRetryRelayDeletion: Bool {
        deletionInProgress && !deletionRequestInFlight && !relayDataWasDeleted
    }

    let entitlementStore: PremiumEntitlementStore
    let coordinator: BackgroundSyncCoordinator
    private let api: any PremiumAPIClientProtocol
    private let registrar: any RemoteNotificationRegistering
    private let installation: PremiumInstallation
    private let environment: APNsEnvironment
    private let keychain: any PremiumKeychainStoring
    private let defaults: UserDefaults
    private weak var repositoryProvider: (any AssistRepositoryProviding)?
    private var startupTask: Task<Void, Never>?
    private var credential: PremiumInstallationCredential?
    private var registrationTask: Task<Void, Never>?
    private var configurationTask: Task<Void, Never>?
    private var reconciliationTask: Task<Void, Never>?
    private var githubInstallationRefreshGeneration: UInt64 = 0
    private var githubInstallationRefreshRequestTasks: [Task<Void, Never>] = []
    private var githubInstallationRefreshWaiters: [GitHubInstallationRefreshWaiter] = []
    private var staleCleanupTask: Task<Void, Never>?
    private var staleCleanupInProgress = false
    private var staleCleanupRequested = false
    private var reconciliationRequested = false
    private var authoritativeInstallationDiscoveryRequested = false
    private var tokenGeneration: UInt64
    private let tokenKey: String
    private let tokenGenerationKey: String
    private let tokenGenerationKeychainKey: String
    private let relayConsentKey: String
    private let automaticSyncKey: String
    private let staleChannelsKey: String
    private let deletionBarrierKey: String
    private let deletionCredentialKey: String
    private let deletionStateKey: String
    private let assistFeatureIsEnabled: () -> Bool

    init(entitlementStore: PremiumEntitlementStore, coordinator: BackgroundSyncCoordinator,
         repositoryProvider: any AssistRepositoryProviding, api: any PremiumAPIClientProtocol,
         registrar: any RemoteNotificationRegistering, installation: PremiumInstallation,
         environment: APNsEnvironment, bridge: PremiumNotificationBridge = .shared,
         assistFeatureIsEnabled: @escaping () -> Bool = { FeatureFlags.gitSyncAssistEnabled },
         keychain: (any PremiumKeychainStoring)? = nil, defaults: UserDefaults = .standard) {
        self.entitlementStore = entitlementStore; self.coordinator = coordinator; self.repositoryProvider = repositoryProvider
        self.api = api; self.registrar = registrar; self.installation = installation; self.environment = environment
        self.assistFeatureIsEnabled = assistFeatureIsEnabled
        self.defaults = defaults
        let resolvedKeychain = keychain ?? SystemPremiumKeychainStore()
        self.keychain = resolvedKeychain
        tokenKey = "premium.apns-token.\(environment.rawValue).\(installation.installationID.uuidString)"
        tokenGenerationKey = "premium.apns-token-generation.\(environment.rawValue).\(installation.installationID.uuidString)"
        tokenGenerationKeychainKey = "premium.apns-token-generation.keychain.\(environment.rawValue).\(installation.installationID.uuidString)"
        let defaultsGeneration = (defaults.object(forKey: tokenGenerationKey) as? NSNumber)?.uint64Value ?? 0
        let keychainGeneration = resolvedKeychain.load(key: tokenGenerationKeychainKey).flatMap(UInt64.init) ?? 0
        let restoredTokenGeneration = max(defaultsGeneration, keychainGeneration)
        tokenGeneration = restoredTokenGeneration
        relayConsentKey = "premium.relay-consent.\(installation.installationID.uuidString)"
        automaticSyncKey = "premium.automatic-sync.v1.\(installation.installationID.uuidString)"
        staleChannelsKey = "premium.stale-channels.v1.\(installation.installationID.uuidString)"
        deletionBarrierKey = "premium.relay-deletion-barrier.\(installation.installationID.uuidString)"
        deletionCredentialKey = "premium.relay-deletion-credential.\(installation.installationID.uuidString)"
        deletionStateKey = "premium.relay-deletion-state.\(installation.installationID.uuidString)"
        if restoredTokenGeneration > 0 {
            defaults.set(NSNumber(value: restoredTokenGeneration), forKey: tokenGenerationKey)
            resolvedKeychain.save(key: tokenGenerationKeychainKey, value: String(restoredTokenGeneration))
        }
        if let encoded = resolvedKeychain.load(key: deletionCredentialKey),
           let data = Data(base64Encoded: encoded),
           let restored = try? JSONDecoder().decode(PremiumInstallationCredential.self, from: data),
           restored.canDelete(for: installation.installationID) {
            credential = restored
        }
        let deletionState = resolvedKeychain.load(key: deletionStateKey)
        let persistedDeletionBarrier = defaults.bool(forKey: deletionBarrierKey) || deletionState == "pending"
        let completedDeletion = deletionState == "completed"
        deletionInProgress = persistedDeletionBarrier
        relayDataWasDeleted = completedDeletion
        hasRelayConsent = !persistedDeletionBarrier && !completedDeletion && defaults.bool(forKey: relayConsentKey)
        automaticallySyncAllRepositories = !persistedDeletionBarrier && !completedDeletion && defaults.bool(forKey: automaticSyncKey)
        latestToken = resolvedKeychain.load(key: tokenKey)
        bridge.connect(runtime: self)
        repositoryProvider.setAssistConfigurationChangeHandler { [weak self] in self?.configurationChanged() }
        repositoryProvider.setAssistInventoryChangeHandler { [weak self] in self?.inventoryChanged() }
        (repositoryProvider as? AppState)?.assistRepositoryRemovalHandler = { [weak self] repo in
            self?.repositoryWillBeRemoved(repo)
        }
        if deletionInProgress || relayDataWasDeleted {
            coordinator.cancelAll()
            for repo in repositoryProvider.assistRepositories() {
                repositoryProvider.updateAssistSettings(repoID: repo.id, .disabled)
            }
        }
    }
    convenience init(entitlementStore: PremiumEntitlementStore, coordinator: BackgroundSyncCoordinator,
                     repositoryProvider: any AssistRepositoryProviding) {
        self.init(entitlementStore: entitlementStore, coordinator: coordinator, repositoryProvider: repositoryProvider,
                  api: PremiumAPIClient(), registrar: UIApplicationRemoteNotificationRegistrar(),
                  installation: PremiumInstallationIdentity.current(), environment: APNsDeviceToken.buildEnvironment)
    }
    /// Retries only a previously persisted terminal deletion. This entry point
    /// deliberately bypasses the release gate and performs no entitlement,
    /// registration, linking, reconciliation, or pull work.
    func recoverPendingDeletion() async {
        guard !Task.isCancelled, deletionInProgress else { return }
        await retryPersistedDeletionIfNeeded()
    }

    func start() async {
        guard !Task.isCancelled, assistFeatureIsEnabled() else { return }
        if deletionInProgress {
            await recoverPendingDeletion()
            guard !Task.isCancelled, !deletionInProgress, !relayDataWasDeleted else { return }
        }
        if let startupTask {
            await startupTask.value
            guard !Task.isCancelled, !deletionInProgress, !relayDataWasDeleted else { return }
            return
        }
        await entitlementStore.bindAppAccountToken(installation.installationID)
        guard !Task.isCancelled, !deletionInProgress, !relayDataWasDeleted else { return }
        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            entitlementStore.onChange = { [weak self] state in self?.entitlementChanged(state) }
            await entitlementStore.start()
        }
        startupTask = task
        await task.value
    }

    /// Explicit installation-scoped automatic mode. Enabling never derives
    /// consent from historical per-repository channels.
    @discardableResult
    func setAutomaticallySyncAllRepositories(_ enabled: Bool) async -> URL? {
        if !enabled {
            await disableAutomaticSync()
            return nil
        }
        guard assistFeatureIsEnabled() else { return nil }
        guard relayIsConfigured else {
            relayError = String(localized: "GitSync Assist relay is not configured.")
            return nil
        }
        await start()
        guard !Task.isCancelled, !deletionInProgress, !relayDataWasDeleted else { return nil }
        await entitlementStore.refresh()
        guard !Task.isCancelled, entitlementStore.state.isActive, !deletionInProgress, !relayDataWasDeleted else {
            relayError = String(localized: "An active subscription is required to enable automatic sync.")
            return nil
        }
        automaticallySyncAllRepositories = true
        defaults.set(true, forKey: automaticSyncKey)
        setRelayConsent(true)
        registrar.register()
        _ = await ensureAuthorizedAndRegistered()
        guard automaticOperationsAllowed,
              credential?.isValid(for: installation.installationID) == true else { return nil }
        let installationDiscoveryIsAuthoritative = await refreshGitHubInstallations()
        guard automaticOperationsAllowed else { return nil }
        await reconcileAutomaticRepositories(
            installationDiscoveryIsAuthoritative: installationDiscoveryIsAuthoritative
        )
        guard automaticOperationsAllowed else { return nil }
        if installationDiscoveryIsAuthoritative, githubInstallations.isEmpty {
            return await startGitHubLink()
        }
        return nil
    }

    func setAutomaticSyncExcluded(repoID: UUID, excluded: Bool) async {
        guard assistFeatureIsEnabled(),
              let provider = repositoryProvider, var repo = provider.assistRepository(id: repoID) else { return }
        if excluded {
            if let channel = repo.assist.channel { enqueueStaleChannel(channel) }
            coordinator.cancel(repoID: repoID)
            repo.assist.excludedFromAutomaticSync = true
            repo.assist.normalizeAutomaticExclusion()
            repo.assist.enrollmentMessage = String(localized: "Excluded from automatic sync.")
            provider.updateAssistSettings(repoID: repoID, repo.assist)
            await cleanupStaleChannels()
        } else {
            repo.assist.excludedFromAutomaticSync = false
            repo.assist.enrollmentStatus = .disabled
            repo.assist.enrollmentMessage = nil
            provider.updateAssistSettings(repoID: repoID, repo.assist)
            scheduleAutomaticReconciliation()
        }
    }

    private func disableAutomaticSync() async {
        let deletionGeneration = tokenGeneration
        let deletionToken = latestToken
        automaticallySyncAllRepositories = false
        defaults.set(false, forKey: automaticSyncKey)
        setRelayConsent(false)
        reconciliationTask?.cancel()
        reconciliationTask = nil
        reconciliationRequested = false
        authoritativeInstallationDiscoveryRequested = false
        resetGitHubInstallationRefresh(clearInventory: true)
        configurationTask?.cancel()
        configurationTask = nil
        let pendingRegistration = registrationTask
        pendingRegistration?.cancel()
        registrationTask = nil
        coordinator.cancelAll()
        if let provider = repositoryProvider {
            for var repo in provider.assistRepositories() {
                repo.assist.enabled = false
                if repo.assist.excludedFromAutomaticSync { repo.assist.enrollmentStatus = .excluded }
                else { repo.assist.enrollmentStatus = .disabled }
                provider.updateAssistSettings(repoID: repo.id, repo.assist)
            }
        }
        registrar.unregister()
        isRegistered = false
        if let pendingRegistration { await pendingRegistration.value }
        if let credential {
            let request = PremiumDeviceDeletionRequest(
                installationID: installation.installationID,
                token: deletionToken,
                environment: environment,
                maximumRegistrationGeneration: deletionGeneration
            )
            do {
                try await api.deleteDevice(request, credential: credential)
                deviceRegistrationError = nil
            } catch { deviceRegistrationError = error.localizedDescription }
        }
        await cleanupStaleChannels()
    }

    func didRegister(token data: Data) {
        guard assistFeatureIsEnabled(), automaticallySyncAllRepositories,
              hasRelayConsent, !deletionInProgress else { return }
        let newToken = APNsDeviceToken.hex(data), oldToken = latestToken
        tokenGeneration &+= 1
        persistTokenGeneration()
        let generation = tokenGeneration
        latestToken = newToken; keychain.save(key: tokenKey, value: newToken)
        deviceRegistrationError = nil
        registrationTask?.cancel()
        registrationTask = Task { [weak self] in
            await self?.replaceAndRegister(oldToken: oldToken, newToken: newToken, generation: generation)
        }
    }
    func didFailToRegister(error: Error) {
        guard assistFeatureIsEnabled() else { return }
        deviceRegistrationError = error.localizedDescription; isRegistered = false
    }

    func processPush(_ userInfo: [AnyHashable: Any]) async -> BackgroundSyncDisposition {
        guard assistFeatureIsEnabled() else { return .ignored }
        await start()
        guard automaticOperationsAllowed else { coordinator.cancelAll(); return .ignored }
        await entitlementStore.refresh()
        guard automaticOperationsAllowed else { coordinator.cancelAll(); return .ignored }
        await ensureAuthorizedAndRegistered()
        guard automaticOperationsAllowed else { coordinator.cancelAll(); return .ignored }
        return await coordinator.handlePush(userInfo)
    }
    func cancelPush(_ userInfo: [AnyHashable: Any]) { coordinator.cancelPush(userInfo) }

    func cancelForegroundReconciliation() {
        coordinator.cancelForegroundReconciliation()
    }

    func reconcileForeground() async {
        guard assistFeatureIsEnabled() else { return }
        await start()
        guard automaticOperationsAllowed else { coordinator.cancelAll(); return }
        await entitlementStore.refresh()
        guard automaticOperationsAllowed else { coordinator.cancelAll(); return }
        await ensureAuthorizedAndRegistered()
        guard automaticOperationsAllowed else { coordinator.cancelAll(); return }
        let installationDiscoveryIsAuthoritative = await refreshGitHubInstallations()
        guard automaticOperationsAllowed else { coordinator.cancelAll(); return }
        await cleanupStaleChannels()
        guard automaticOperationsAllowed else { coordinator.cancelAll(); return }
        await reconcileAutomaticRepositories(
            installationDiscoveryIsAuthoritative: installationDiscoveryIsAuthoritative
        )
        guard automaticOperationsAllowed else { coordinator.cancelAll(); return }
        _ = await coordinator.reconcileForeground()
    }

    func prepareForSettings() async {
        guard assistFeatureIsEnabled() else { return }
        await start()
        guard !Task.isCancelled else { return }
        await entitlementStore.refresh()
        guard !Task.isCancelled else { return }
        await cleanupStaleChannels()
        guard automaticOperationsAllowed else { return }
        await ensureAuthorizedAndRegistered()
        guard automaticOperationsAllowed else { return }
        let installationDiscoveryIsAuthoritative = await refreshGitHubInstallations()
        guard automaticOperationsAllowed else { return }
        await reconcileAutomaticRepositories(
            installationDiscoveryIsAuthoritative: installationDiscoveryIsAuthoritative
        )
    }

    func startGitHubLink() async -> URL? {
        guard assistFeatureIsEnabled() else { return nil }
        guard !relayDataWasDeleted else {
            deletionError = String(localized: "Assist relay data was permanently deleted for this installation. Contact support if you need to use Assist again.")
            return nil
        }
        await start()
        guard !Task.isCancelled, automaticallySyncAllRepositories, !deletionInProgress else { return nil }
        await entitlementStore.refresh()
        guard automaticOperationsAllowed else { return nil }
        setRelayConsent(true)
        registrar.register()
        await ensureAuthorizedAndRegistered()
        guard automaticOperationsAllowed, let relay = api as? any PremiumRelayManaging,
              let credential else { return nil }
        do {
            let url = try await relay.startGitHubLink(credential: credential).url
            githubError = nil
            return url
        } catch {
            if isAuthorizationRejection(error), hasRelayConsent, !deletionInProgress,
               case .active(let proof) = entitlementStore.state {
                self.credential = nil
                do {
                    let renewed = try await authorizeAndPersist(proof: proof)
                    guard automaticOperationsAllowed else { return nil }
                    let url = try await relay.startGitHubLink(credential: renewed).url
                    githubError = nil
                    return url
                } catch { githubError = error.localizedDescription; return nil }
            }
            githubError = error.localizedDescription; return nil
        }
    }

    @discardableResult
    func refreshGitHubInstallations() async -> Bool {
        guard automaticOperationsAllowed, let relay = api as? any PremiumRelayManaging,
              let capturedCredential = credential else { return false }
        githubInstallationRefreshGeneration &+= 1
        let generation = githubInstallationRefreshGeneration
        return await withCheckedContinuation { continuation in
            githubInstallationRefreshWaiters.append(.init(generation: generation, continuation: continuation))
            let task = Task { @MainActor [weak self] in
                let response: GitHubInstallationRefreshResponse
                do {
                    response = .success(
                        capturedCredential,
                        try await relay.githubInstallations(credential: capturedCredential)
                    )
                } catch {
                    response = .failure(capturedCredential, error.localizedDescription)
                }
                self?.completeGitHubInstallationRefresh(response, generation: generation)
            }
            githubInstallationRefreshRequestTasks.append(task)
        }
    }

    private func completeGitHubInstallationRefresh(
        _ response: GitHubInstallationRefreshResponse,
        generation: UInt64
    ) {
        // A stale request never chooses another request to await. Every caller
        // remains in the shared waiter set until the newest request completes,
        // so a later authoritative response can release callers even when an
        // intermediate transport request is suspended indefinitely.
        guard generation == githubInstallationRefreshGeneration else { return }
        let responseCredential: PremiumInstallationCredential
        switch response {
        case .success(let capturedCredential, _), .failure(let capturedCredential, _):
            responseCredential = capturedCredential
        }
        guard automaticOperationsAllowed, credential == responseCredential else {
            finishGitHubInstallationRefresh(generation: generation, authoritative: false)
            return
        }
        let authoritative: Bool
        switch response {
        case .success(_, let installations):
            githubInstallations = installations
            githubError = nil
            authoritative = true
        case .failure(_, let message):
            githubError = message
            authoritative = false
        }
        finishGitHubInstallationRefresh(generation: generation, authoritative: authoritative)
    }

    private func finishGitHubInstallationRefresh(generation: UInt64, authoritative: Bool) {
        let completed = githubInstallationRefreshWaiters.filter { $0.generation <= generation }
        githubInstallationRefreshWaiters.removeAll { $0.generation <= generation }
        githubInstallationRefreshRequestTasks.forEach { $0.cancel() }
        githubInstallationRefreshRequestTasks.removeAll()
        completed.forEach { $0.continuation.resume(returning: authoritative) }
    }

    private func resetGitHubInstallationRefresh(clearInventory: Bool) {
        githubInstallationRefreshGeneration &+= 1
        githubInstallationRefreshRequestTasks.forEach { $0.cancel() }
        githubInstallationRefreshRequestTasks.removeAll()
        let pending = githubInstallationRefreshWaiters
        githubInstallationRefreshWaiters.removeAll()
        pending.forEach { $0.continuation.resume(returning: false) }
        if clearInventory { githubInstallations = [] }
    }

    func enroll(repoID: UUID, githubInstallationID: Int64, repositoryID: Int64, branch: String) async -> Bool {
        guard automaticOperationsAllowed,
              let relay = api as? any PremiumRelayManaging, let credential, let provider = repositoryProvider,
              provider.assistRepository(id: repoID) != nil,
              let canonical = provider.assistCanonicalGitHubFullName(repoID: repoID) else { return false }
        do {
            let enrollment = try await relay.createEnrollment(
                .init(githubInstallationID: githubInstallationID, repositoryID: repositoryID, branch: branch),
                credential: credential
            )
            // Trust the returned channel only after validating the complete
            // echoed request. A mismatched response is not safe to delete.
            guard OpaqueAssistIdentifier.isValid(enrollment.channel),
                  enrollment.githubInstallationID == githubInstallationID,
                  enrollment.repositoryID == repositoryID,
                  enrollment.branch == branch else { return false }
            guard automaticOperationsAllowed, credential.isValid(for: installation.installationID),
                  self.credential == credential,
                  var repo = provider.assistRepository(id: repoID),
                  !repo.assist.excludedFromAutomaticSync,
                  repo.branch == branch,
                  provider.assistCanonicalGitHubFullName(repoID: repoID)?.caseInsensitiveCompare(canonical) == .orderedSame else {
                enqueueStaleChannel(enrollment.channel)
                scheduleStaleCleanup()
                return false
            }
            if let old = repo.assist.channel, old != enrollment.channel { enqueueStaleChannel(old) }
            repo.assist.channel = enrollment.channel
            repo.assist.selectedBranch = branch
            repo.assist.enabled = true
            repo.assist.githubRepositoryID = repositoryID
            repo.assist.githubRepositoryFullName = canonical
            repo.assist.linkedGitHubInstallationID = githubInstallationID
            repo.assist.enrolledBranch = branch
            repo.assist.enrollmentStatus = .enrolled
            repo.assist.enrollmentMessage = nil
            repo.assist.enrollmentLastAttemptDate = Date()
            repo.assist.health = .never
            provider.updateAssistSettings(repoID: repoID, repo.assist)
            relayError = nil
            await ensureAuthorizedAndRegistered()
            return automaticOperationsAllowed
        } catch { relayError = error.localizedDescription; return false }
    }

    func unenroll(repoID: UUID) async -> Bool {
        guard assistFeatureIsEnabled(),
              let relay = api as? any PremiumRelayManaging, let credential, let provider = repositoryProvider,
              let repo = provider.assistRepository(id: repoID), let channel = repo.assist.channel else { return false }
        let expectedSettings = repo.assist
        let expectedBranch = repo.branch
        let expectedCanonicalTarget = provider.assistCanonicalGitHubFullName(repoID: repoID)
        do {
            try await relay.deleteEnrollment(channel: channel, credential: credential)
            if let current = provider.assistRepository(id: repoID),
               current.assist == expectedSettings,
               current.branch == expectedBranch,
               provider.assistCanonicalGitHubFullName(repoID: repoID) == expectedCanonicalTarget {
                provider.updateAssistSettings(repoID: repoID, .disabled)
            }
            relayError = nil
            if automaticOperationsAllowed { await ensureAuthorizedAndRegistered() }
            return true
        } catch { relayError = error.localizedDescription; return false }
    }

    func deleteRelayData() async {
        guard !relayDataWasDeleted else { return }
        if deletionInProgress {
            guard !deletionRequestInFlight else { return }
            await retryPersistedDeletionIfNeeded()
            return
        }
        guard let credential, let provider = repositoryProvider,
              let relay = api as? any PremiumRelayManaging else { return }
        deletionInProgress = true
        defaults.set(true, forKey: deletionBarrierKey)
        automaticallySyncAllRepositories = false
        defaults.set(false, forKey: automaticSyncKey)
        setRelayConsent(false)

        // Enter the local terminal barrier synchronously. No registration,
        // configuration reconciliation, cleanup, or pull flight may survive
        // until the first network deletion begins.
        startupTask?.cancel()
        registrationTask?.cancel()
        registrationTask = nil
        configurationTask?.cancel()
        configurationTask = nil
        reconciliationTask?.cancel()
        reconciliationTask = nil
        reconciliationRequested = false
        authoritativeInstallationDiscoveryRequested = false
        resetGitHubInstallationRefresh(clearInventory: true)
        staleCleanupTask?.cancel()
        staleCleanupTask = nil
        coordinator.cancelAll()
        for repo in provider.assistRepositories() {
            provider.updateAssistSettings(repoID: repo.id, .disabled)
        }
        tokenGeneration &+= 1
        persistTokenGeneration()
        registrar.unregister()
        isRegistered = false
        guard ensurePendingDeletionState(), persistDeletionCredential(credential) else {
            // The deletion intent is fail-closed. Never erase a pre-existing
            // pending/completed marker or reopen consent when persistence is
            // unavailable; restart/support recovery must retain the barrier.
            if !relayDataWasDeleted {
                deletionError = String(localized: "Could not securely save the relay deletion authorization. Assist remains disabled; retry or contact support.")
            }
            return
        }
        deletionRequestInFlight = true
        defer { deletionRequestInFlight = false }
        do {
            try await relay.deleteInstallation(credential: credential)
            self.credential = nil
            isRegistered = false
            githubInstallations = []
            guard completeDeletionBarrier() else {
                deletionError = String(localized: "Relay deletion completed, but its local receipt could not be saved. Retry to finish securely.")
                return
            }
            deletionError = nil
        } catch {
            deletionError = error.localizedDescription
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
        defaults.removeObject(forKey: deletionBarrierKey)
        automaticallySyncAllRepositories = false
        defaults.set(false, forKey: automaticSyncKey)
        defaults.removeObject(forKey: staleChannelsKey)
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
        guard deletionInProgress, !deletionRequestInFlight else { return }
        // Reassert the local notification boundary on every recovery attempt.
        // This is deletion cleanup, not feature registration, and remains safe
        // when a prior process died after persisting the terminal barrier.
        registrar.unregister()
        isRegistered = false
        guard let relay = api as? any PremiumRelayManaging else { return }
        startupTask?.cancel()
        registrationTask?.cancel()
        registrationTask = nil
        configurationTask?.cancel()
        configurationTask = nil
        reconciliationTask?.cancel()
        reconciliationTask = nil
        reconciliationRequested = false
        authoritativeInstallationDiscoveryRequested = false
        resetGitHubInstallationRefresh(clearInventory: true)
        staleCleanupTask?.cancel()
        staleCleanupTask = nil
        coordinator.cancelAll()
        if let provider = repositoryProvider {
            for repo in provider.assistRepositories() {
                provider.updateAssistSettings(repoID: repo.id, .disabled)
            }
        }
        guard ensurePendingDeletionState() else {
            if !relayDataWasDeleted {
                deletionError = String(localized: "Relay deletion is pending but its durable state could not be saved. Assist remains disabled; retry or contact support.")
            }
            return
        }
        guard let credential = persistedDeletionCredential() else {
            // A session may expire locally even though an earlier idempotent
            // delete reached the relay before the process died. Preserve the
            // fail-closed barrier and expose an explicit recovery path rather
            // than recreating relay data automatically.
            deletionError = String(localized: "Relay deletion is pending but its authorization expired. Contact support to verify deletion and reset this installation.")
            return
        }
        deletionRequestInFlight = true
        defer { deletionRequestInFlight = false }
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
                deletionError = String(localized: "Relay deletion completed, but its local receipt could not be saved. Retry to finish securely.")
                return
            }
            deletionError = nil
        } catch {
            deletionError = error.localizedDescription
        }
    }

    private func entitlementChanged(_ state: PremiumEntitlementState) {
        guard assistFeatureIsEnabled() else {
            coordinator.cancelAll()
            isRegistered = false
            return
        }
        switch state {
        case .active:
            if automaticallySyncAllRepositories, hasRelayConsent, !deletionInProgress { registrar.register() }
        case .inactive:
            registrar.unregister(); coordinator.cancelAll(); isRegistered = false
            resetGitHubInstallationRefresh(clearInventory: true)
            // Retain an unexpired installation credential only for authenticated
            // deletion after subscription expiry. Capture the complete old
            // snapshot so a delayed DELETE cannot remove a later generation.
            let oldCredential = credential
            let deletion = PremiumDeviceDeletionRequest(
                installationID: installation.installationID,
                token: latestToken,
                environment: environment,
                maximumRegistrationGeneration: tokenGeneration
            )
            if let oldCredential {
                Task {
                    do {
                        try await api.deleteDevice(deletion, credential: oldCredential)
                        if case .inactive = self.entitlementStore.state {
                            self.deviceRegistrationError = nil
                        }
                    } catch {
                        if case .inactive = self.entitlementStore.state,
                           oldCredential.isValid(for: installation.installationID) {
                            self.deviceRegistrationError = error.localizedDescription
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
        guard automaticOperationsAllowed,
              case .active(let proof) = entitlementStore.state else { return nil }
        let registrationGeneration: UInt64?
        if let generation {
            registrationGeneration = generation
        } else if let token = latestToken, expectedToken == nil || expectedToken == token {
            tokenGeneration &+= 1
            persistTokenGeneration()
            registrationGeneration = tokenGeneration
        } else {
            registrationGeneration = nil
        }
        do {
            if credential?.isValid(for: installation.installationID) != true {
                let authorized = try await authorizeAndPersist(proof: proof)
                guard automaticOperationsAllowed,
                      registrationGeneration == nil || registrationGeneration == tokenGeneration else { return nil }
                credential = authorized
            }
            let registered: String?
            if let registrationGeneration {
                registered = try await registerLatestTokenIfPossible(
                    expectedToken: expectedToken,
                    generation: registrationGeneration
                )
            } else {
                registered = nil
            }
            guard automaticOperationsAllowed else { return nil }
            if let registrationGeneration, registrationGeneration != tokenGeneration { return nil }
            if let registered, registered == latestToken {
                isRegistered = true
                deviceRegistrationError = nil
            }
            return registered
        } catch {
            if mayReauthorize, isAuthorizationRejection(error),
               credential?.isValid(for: installation.installationID) == true {
                credential = nil
                return await ensureAuthorizedAndRegistered(
                    expectedToken: expectedToken,
                    generation: registrationGeneration,
                    mayReauthorize: false
                )
            }
            if registrationGeneration == nil || registrationGeneration == tokenGeneration {
                deviceRegistrationError = error.localizedDescription
                isRegistered = false
            }
            return nil
        }
    }

    private func authorizeAndPersist(proof: PremiumEntitlementProof) async throws -> PremiumInstallationCredential {
        let authorized = try await api.authorizeEntitlement(.init(installation: installation, proof: proof))
        guard automaticOperationsAllowed,
              persistDeletionCredential(authorized) else { throw PremiumAPIError.invalidCredential }
        credential = authorized
        relayError = nil
        return authorized
    }

    private func isAuthorizationRejection(_ error: Error) -> Bool {
        guard case PremiumAPIError.rejected(let status) = error else { return false }
        return status == 401
    }

    private func replaceAndRegister(oldToken: String?, newToken: String, generation: UInt64) async {
        guard automaticOperationsAllowed else { return }
        let registered = await ensureAuthorizedAndRegistered(expectedToken: newToken, generation: generation)
        guard automaticOperationsAllowed, registered == newToken, generation == tokenGeneration,
              latestToken == newToken, let oldToken, oldToken != newToken, let credential else { return }
        let deletion = PremiumDeviceDeletionRequest(
            installationID: installation.installationID,
            token: oldToken,
            environment: environment,
            maximumRegistrationGeneration: generation
        )
        do {
            try await api.deleteDevice(deletion, credential: credential)
            guard generation == tokenGeneration else { return }
            deviceRegistrationError = nil
        } catch {
            guard generation == tokenGeneration else { return }
            deviceRegistrationError = error.localizedDescription
        }
    }

    private func registerLatestTokenIfPossible(
        expectedToken: String? = nil,
        generation: UInt64
    ) async throws -> String? {
        guard automaticOperationsAllowed, generation == tokenGeneration, let token = latestToken,
              expectedToken == nil || expectedToken == token,
              let credential, credential.isValid(for: installation.installationID) else { return nil }
        let request = PremiumDeviceRegistrationRequest(installation: installation, token: token,
            environment: environment, registrationGeneration: generation)
        try await api.registerDevice(request, credential: credential)
        guard automaticOperationsAllowed, latestToken == token,
              expectedToken == nil || expectedToken == token else { return nil }
        return token
    }

    private func persistTokenGeneration() {
        let value = String(tokenGeneration)
        defaults.set(NSNumber(value: tokenGeneration), forKey: tokenGenerationKey)
        keychain.save(key: tokenGenerationKeychainKey, value: value)
    }

    private func repositoryWillBeRemoved(_ repo: RepoConfig) {
        // Pull cancellation must be synchronous with AppState removal. Channel
        // cleanup is optional and may safely continue after the local record is gone.
        coordinator.cancel(repoID: repo.id)
        guard let channel = repo.assist.channel else { return }
        enqueueStaleChannel(channel)
        scheduleStaleCleanup()
    }

    private var automaticOperationsAllowed: Bool {
        !Task.isCancelled && assistFeatureIsEnabled()
            && automaticallySyncAllRepositories && hasRelayConsent
            && !deletionInProgress && !relayDataWasDeleted && entitlementStore.state.isActive
    }

    private func setRelayConsent(_ enabled: Bool) {
        hasRelayConsent = enabled
        defaults.set(enabled, forKey: relayConsentKey)
    }

    private func configurationChanged() {
        guard assistFeatureIsEnabled(), !deletionInProgress, !relayDataWasDeleted else { return }
        scheduleStaleCleanup()
        guard automaticallySyncAllRepositories, hasRelayConsent else { return }
        scheduleAutomaticReconciliation()
    }

    private func inventoryChanged() {
        guard assistFeatureIsEnabled(), !deletionInProgress, !relayDataWasDeleted else { return }
        scheduleStaleCleanup()
        guard automaticallySyncAllRepositories, hasRelayConsent else { return }
        scheduleAutomaticReconciliation()
    }

    private func scheduleStaleCleanup() {
        guard assistFeatureIsEnabled() else { return }
        staleCleanupTask?.cancel()
        staleCleanupTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled, let self else { return }
            staleCleanupTask = nil
            await cleanupStaleChannels()
        }
    }

    private func scheduleAutomaticReconciliation() {
        guard assistFeatureIsEnabled() else { return }
        configurationTask?.cancel()
        configurationTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled, let self, automaticOperationsAllowed else { return }
            let installationDiscoveryIsAuthoritative = await refreshGitHubInstallations()
            guard !Task.isCancelled, automaticOperationsAllowed else { return }
            await reconcileAutomaticRepositories(
                installationDiscoveryIsAuthoritative: installationDiscoveryIsAuthoritative
            )
        }
    }

    func reconcileAutomaticRepositories(
        installationDiscoveryIsAuthoritative: Bool = false
    ) async {
        guard automaticOperationsAllowed else { return }
        authoritativeInstallationDiscoveryRequested =
            authoritativeInstallationDiscoveryRequested || installationDiscoveryIsAuthoritative
        if let reconciliationTask {
            reconciliationRequested = true
            await reconciliationTask.value
            guard automaticOperationsAllowed else { return }
            return
        }
        reconciliationRequested = true
        let task = Task { @MainActor [weak self] in
            guard let self, automaticOperationsAllowed else { return }
            isReconcilingAutomaticSync = true
            defer { isReconcilingAutomaticSync = false }
            repeat {
                reconciliationRequested = false
                let installationDiscoveryIsAuthoritative = authoritativeInstallationDiscoveryRequested
                authoritativeInstallationDiscoveryRequested = false
                await performAutomaticReconciliation(
                    installationDiscoveryIsAuthoritative: installationDiscoveryIsAuthoritative
                )
            } while reconciliationRequested && automaticOperationsAllowed
        }
        reconciliationTask = task
        await task.value
        reconciliationTask = nil
        if !automaticOperationsAllowed {
            reconciliationRequested = false
            authoritativeInstallationDiscoveryRequested = false
        }
    }

    private func performAutomaticReconciliation(
        installationDiscoveryIsAuthoritative: Bool
    ) async {
        guard automaticOperationsAllowed, let provider = repositoryProvider else { return }
        for repoID in provider.assistRepositories().map(\.id) {
            guard automaticOperationsAllowed else { return }
            await reconcileAutomaticRepository(
                repoID: repoID,
                installationDiscoveryIsAuthoritative: installationDiscoveryIsAuthoritative
            )
            guard automaticOperationsAllowed else { return }
        }
        await cleanupStaleChannels()
    }

    private func reconcileAutomaticRepository(
        repoID: UUID,
        installationDiscoveryIsAuthoritative: Bool
    ) async {
        guard automaticOperationsAllowed, let provider = repositoryProvider,
              var repo = provider.assistRepository(id: repoID) else { return }
        let attempt = Date()
        if repo.assist.excludedFromAutomaticSync {
            transitionLocally(&repo, status: .excluded, enabled: false,
                              message: String(localized: "Excluded from automatic sync."), clearEnrollment: true)
            provider.updateAssistSettings(repoID: repoID, repo.assist)
            return
        }
        guard repo.isCloned else {
            transitionLocally(&repo, status: .disabled, enabled: false,
                              message: String(localized: "Clone this repository to enable automatic sync."), clearEnrollment: true)
            provider.updateAssistSettings(repoID: repoID, repo.assist)
            return
        }
        let branch = repo.branch.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !branch.isEmpty, let canonical = provider.assistCanonicalGitHubFullName(repoID: repoID) else {
            transitionLocally(&repo, status: .foregroundOnly, enabled: true,
                              message: String(localized: "Foreground-only: the origin is not a GitHub repository."), clearEnrollment: true)
            repo.assist.selectedBranch = branch.isEmpty ? repo.branch : branch
            repo.assist.enrollmentLastAttemptDate = attempt
            provider.updateAssistSettings(repoID: repoID, repo.assist)
            return
        }

        // A persisted numeric identity for the exact current target is enough
        // to re-POST the tuple. The relay's installation access check is the
        // authority, and this avoids making local GitHub credentials a repair
        // prerequisite.
        let identity: GitHubRepositoryIdentity
        if let repositoryID = repo.assist.githubRepositoryID,
           repo.assist.githubRepositoryFullName?.caseInsensitiveCompare(canonical) == .orderedSame,
           repo.assist.enrolledBranch == branch {
            identity = GitHubRepositoryIdentity(repositoryID: repositoryID, fullName: canonical)
        } else {
            let resolution = await provider.resolveAssistGitHubIdentity(repoID: repoID)
            guard automaticTargetStillValid(repoID: repoID, canonical: canonical, branch: branch) else { return }
            switch resolution {
            case .resolved(let resolved) where resolved.fullName.caseInsensitiveCompare(canonical) == .orderedSame:
                identity = resolved
            case .definitiveNoAccess, .resolved:
                guard var current = provider.assistRepository(id: repoID) else { return }
                transitionLocally(&current, status: .foregroundOnly, enabled: true,
                                  message: String(localized: "Foreground-only: GitHub access could not be resolved."), clearEnrollment: true)
                current.assist.enrollmentLastAttemptDate = attempt
                provider.updateAssistSettings(repoID: repoID, current.assist)
                return
            case .transientFailure(let message):
                markReconciliationFailure(repoID: repoID, attempt: attempt, detail: message)
                return
            }
        }

        guard automaticOperationsAllowed, var current = provider.assistRepository(id: repoID),
              let relay = api as? any PremiumRelayManaging, let capturedCredential = credential,
              capturedCredential.isValid(for: installation.installationID) else { return }
        current.assist.enrollmentStatus = .enrolling
        current.assist.enrollmentMessage = String(localized: "Authorizing GitHub wake notifications…")
        current.assist.enrollmentLastAttemptDate = attempt
        provider.updateAssistSettings(repoID: repoID, current.assist)

        var installationIDs: [Int64] = []
        if let stored = current.assist.linkedGitHubInstallationID { installationIDs.append(stored) }
        if installationDiscoveryIsAuthoritative {
            for linked in githubInstallations.map(\.githubInstallationID) where !installationIDs.contains(linked) {
                installationIDs.append(linked)
            }
        }
        var sawTransientFailure = false
        var transientMessage: String?
        for githubInstallationID in installationIDs {
            guard automaticTargetStillValid(repoID: repoID, canonical: canonical, branch: branch),
                  credential == capturedCredential else { return }
            let request = PremiumRepositoryEnrollmentRequest(
                githubInstallationID: githubInstallationID, repositoryID: identity.repositoryID, branch: branch
            )
            do {
                let enrollment = try await relay.createEnrollment(request, credential: capturedCredential)
                // Validate the channel and exact echo before using any part of
                // a successful response, including stale-response cleanup.
                guard OpaqueAssistIdentifier.isValid(enrollment.channel),
                      enrollment.githubInstallationID == request.githubInstallationID,
                      enrollment.repositoryID == request.repositoryID,
                      enrollment.branch == request.branch else {
                    sawTransientFailure = true
                    transientMessage = String(localized: "The relay returned an invalid enrollment response. Retry later.")
                    continue
                }
                guard automaticTargetStillValid(repoID: repoID, canonical: canonical, branch: branch),
                      credential == capturedCredential,
                      var latest = provider.assistRepository(id: repoID) else {
                    enqueueStaleChannel(enrollment.channel)
                    scheduleStaleCleanup()
                    return
                }
                if let old = latest.assist.channel, old != enrollment.channel { enqueueStaleChannel(old) }
                latest.assist.enabled = true
                latest.assist.channel = enrollment.channel
                latest.assist.selectedBranch = branch
                latest.assist.githubRepositoryID = identity.repositoryID
                latest.assist.githubRepositoryFullName = identity.fullName
                latest.assist.linkedGitHubInstallationID = githubInstallationID
                latest.assist.enrolledBranch = branch
                latest.assist.enrollmentStatus = .enrolled
                latest.assist.enrollmentMessage = nil
                latest.assist.enrollmentLastAttemptDate = attempt
                latest.assist.health = .never
                provider.updateAssistSettings(repoID: repoID, latest.assist)
                return
            } catch PremiumAPIError.rejected(let status) where status == 403 || status == 404 || status == 409 {
                guard automaticTargetStillValid(repoID: repoID, canonical: canonical, branch: branch),
                      credential == capturedCredential else { return }
                continue
            } catch {
                guard automaticTargetStillValid(repoID: repoID, canonical: canonical, branch: branch),
                      credential == capturedCredential else { return }
                sawTransientFailure = true
                transientMessage = error.localizedDescription
            }
        }

        guard automaticTargetStillValid(repoID: repoID, canonical: canonical, branch: branch) else { return }
        if sawTransientFailure || !installationDiscoveryIsAuthoritative {
            markReconciliationFailure(
                repoID: repoID,
                attempt: attempt,
                detail: transientMessage ?? String(localized: "Linked GitHub installations could not be refreshed. Automatic enrollment will retry.")
            )
            return
        }
        guard var latest = provider.assistRepository(id: repoID) else { return }
        transitionLocally(&latest, status: .foregroundOnly, enabled: true,
                          message: installationIDs.isEmpty
                            ? String(localized: "Foreground-only: link the GitHub App to enable wake notifications.")
                            : String(localized: "Foreground-only: no linked GitHub installation can access this repository."),
                          clearEnrollment: true)
        latest.assist.enrollmentLastAttemptDate = attempt
        provider.updateAssistSettings(repoID: repoID, latest.assist)
    }

    private func markReconciliationFailure(repoID: UUID, attempt: Date, detail: String) {
        guard let provider = repositoryProvider, var latest = provider.assistRepository(id: repoID) else { return }
        latest.assist.enrollmentStatus = .failed
        latest.assist.enrollmentMessage = String(localized: "Automatic enrollment failed and will retry: \(detail)")
        latest.assist.enrollmentLastAttemptDate = attempt
        provider.updateAssistSettings(repoID: repoID, latest.assist)
    }

    private func automaticTargetStillValid(repoID: UUID, canonical: String, branch: String) -> Bool {
        guard automaticOperationsAllowed, let credential,
              credential.isValid(for: installation.installationID),
              let provider = repositoryProvider, let repo = provider.assistRepository(id: repoID),
              repo.isCloned, !repo.assist.excludedFromAutomaticSync,
              repo.branch.trimmingCharacters(in: .whitespacesAndNewlines) == branch,
              provider.assistCanonicalGitHubFullName(repoID: repoID)?.caseInsensitiveCompare(canonical) == .orderedSame else { return false }
        return true
    }

    private func transitionLocally(_ repo: inout RepoConfig, status: RepoAssistEnrollmentStatus,
                                   enabled: Bool, message: String?, clearEnrollment: Bool) {
        if clearEnrollment, let channel = repo.assist.channel { enqueueStaleChannel(channel) }
        repo.assist.enabled = enabled
        repo.assist.enrollmentStatus = status
        repo.assist.enrollmentMessage = message
        if clearEnrollment {
            repo.assist.channel = nil
            repo.assist.githubRepositoryID = nil
            repo.assist.githubRepositoryFullName = nil
            repo.assist.linkedGitHubInstallationID = nil
            repo.assist.enrolledBranch = nil
        }
    }

    private func enqueueStaleChannel(_ channel: String) {
        guard OpaqueAssistIdentifier.isValid(channel) else { return }
        var channels = persistedStaleChannels()
        channels.insert(channel)
        persistStaleChannels(channels)
    }

    private func persistedStaleChannels() -> Set<String> {
        guard let data = defaults.data(forKey: staleChannelsKey),
              let values = try? JSONDecoder().decode([String].self, from: data) else { return [] }
        return Set(values.filter(OpaqueAssistIdentifier.isValid))
    }

    private func persistStaleChannels(_ channels: Set<String>) {
        if channels.isEmpty { defaults.removeObject(forKey: staleChannelsKey); return }
        if let data = try? JSONEncoder().encode(channels.sorted()) { defaults.set(data, forKey: staleChannelsKey) }
    }

    private func cleanupStaleChannels() async {
        if staleCleanupInProgress {
            staleCleanupRequested = true
            return
        }
        staleCleanupInProgress = true
        defer {
            staleCleanupInProgress = false
            staleCleanupRequested = false
        }

        repeat {
            staleCleanupRequested = false
            await performStaleChannelCleanupPass()
        } while staleCleanupRequested && !Task.isCancelled && assistFeatureIsEnabled()
            && !deletionInProgress && !relayDataWasDeleted
    }

    private func performStaleChannelCleanupPass() async {
        guard !Task.isCancelled, assistFeatureIsEnabled(),
              !deletionInProgress, !relayDataWasDeleted,
              let relay = api as? any PremiumRelayManaging,
              let capturedCredential = credential,
              capturedCredential.isValid(for: installation.installationID) else { return }
        let pending = persistedStaleChannels()
        for channel in pending {
            let referenced = Set(repositoryProvider?.assistRepositories().compactMap(\.assist.channel) ?? [])
            guard !referenced.contains(channel) else { continue }
            guard !Task.isCancelled, !deletionInProgress, credential == capturedCredential else { return }
            do {
                try await relay.deleteEnrollment(channel: channel, credential: capturedCredential)
                guard !Task.isCancelled, !deletionInProgress, !relayDataWasDeleted,
                      credential == capturedCredential else { return }
                removePersistedStaleChannel(channel)
            } catch PremiumAPIError.rejected(let status) where status == 404 {
                guard !Task.isCancelled, !deletionInProgress, !relayDataWasDeleted,
                      credential == capturedCredential else { return }
                removePersistedStaleChannel(channel)
            } catch {
                guard !Task.isCancelled, !deletionInProgress, credential == capturedCredential else { return }
                relayError = error.localizedDescription
            }
        }
    }

    private func removePersistedStaleChannel(_ channel: String) {
        var current = persistedStaleChannels()
        current.remove(channel)
        persistStaleChannels(current)
    }
}

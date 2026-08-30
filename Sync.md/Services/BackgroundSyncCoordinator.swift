import Foundation
import Network
import UIKit

struct BackgroundSyncConditions: Sendable, Equatable { let isWiFi: Bool; let isExternalPower: Bool }
protocol BackgroundSyncConditionsProviding: Sendable { func current() async -> BackgroundSyncConditions }
struct PermissiveBackgroundSyncConditions: BackgroundSyncConditionsProviding {
    func current() async -> BackgroundSyncConditions { .init(isWiFi: true, isExternalPower: true) }
}

final class SystemBackgroundSyncConditions: BackgroundSyncConditionsProviding, @unchecked Sendable {
    private let monitor = NWPathMonitor(); private let queue = DispatchQueue(label: "com.bontecou.Sync-md.assist-network")
    private let lock = NSLock(); private var path: NWPath?
    init() { monitor.pathUpdateHandler = { [weak self] path in self?.lock.withLock { self?.path = path } }; monitor.start(queue: queue) }
    deinit { monitor.cancel() }
    func current() async -> BackgroundSyncConditions {
        let wifi = lock.withLock { path?.status == .satisfied && path?.usesInterfaceType(.wifi) == true }
        let power = await MainActor.run { UIDevice.current.isBatteryMonitoringEnabled = true; return UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full }
        return .init(isWiFi: wifi, isExternalPower: power)
    }
}
private extension NSLock { func withLock<T>(_ operation: () -> T) -> T { lock(); defer { unlock() }; return operation() } }

enum AssistGitHubIdentityResolution: Sendable, Equatable {
    case resolved(GitHubRepositoryIdentity)
    case definitiveNoAccess
    case transientFailure(String)
}

@MainActor
protocol AssistRepositoryProviding: AnyObject {
    func assistRepositories() -> [RepoConfig]
    func assistRepository(id: UUID) -> RepoConfig?
    func assistRepositoryInstance(id: UUID) throws -> SerializedGitRepository
    func assistCredentials(for repo: RepoConfig) -> String
    func recordAssist(result: RepositoryReconciliationResult?, health: RepoAssistHealth, repoID: UUID)
    func updateAssistSettings(repoID: UUID, _ settings: RepoAssistSettings)
    func assistCanonicalGitHubFullName(repoID: UUID) -> String?
    func resolveAssistGitHubIdentity(repoID: UUID) async -> AssistGitHubIdentityResolution
    func setAssistConfigurationChangeHandler(_ handler: (@MainActor @Sendable () -> Void)?)
    func setAssistInventoryChangeHandler(_ handler: (@MainActor @Sendable () -> Void)?)
}

@MainActor
extension AppState: AssistRepositoryProviding {
    func assistRepositories() -> [RepoConfig] { repos }
    func assistRepository(id: UUID) -> RepoConfig? { repo(id: id) }
    func assistRepositoryInstance(id: UUID) throws -> SerializedGitRepository { try serializedRepository(repoID: id) }
    func assistCredentials(for repo: RepoConfig) -> String { authPayload(for: repo) }
    func updateAssistSettings(repoID: UUID, _ settings: RepoAssistSettings) {
        updateRepo(id: repoID) { $0.assist = settings }
    }
    func assistCanonicalGitHubFullName(repoID: UUID) -> String? {
        guard let repo = repo(id: repoID) else { return nil }
        let origin = repo.isCloned ? Self.readGitRemoteURL(at: vaultURL(for: repoID)) : nil
        return GitRemoteURL.parse(origin ?? repo.repoURL)?.canonicalGitHubFullName
    }
    func resolveAssistGitHubIdentity(repoID: UUID) async -> AssistGitHubIdentityResolution {
        guard let repo = repo(id: repoID), let fullName = assistCanonicalGitHubFullName(repoID: repoID) else {
            return .definitiveNoAccess
        }
        if let cached = gitHubRepos.first(where: { $0.fullName.caseInsensitiveCompare(fullName) == .orderedSame }) {
            return .resolved(GitHubRepositoryIdentity(repositoryID: cached.id, fullName: cached.fullName))
        }
        let parts = fullName.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return .definitiveNoAccess }
        var tokens: [String?] = []
        if let token = gitHubToken(for: repo.gitHubAccountLogin), !token.isEmpty { tokens.append(token) }
        if let token = gitHubToken(for: activeGitHubAccountLogin), !token.isEmpty,
           !tokens.contains(where: { $0 == token }) { tokens.append(token) }
        for account in gitHubAccounts {
            if let token = gitHubToken(for: account.login), !token.isEmpty,
               !tokens.contains(where: { $0 == token }) { tokens.append(token) }
        }
        if !pat.isEmpty, !tokens.contains(where: { $0 == pat }) { tokens.append(pat) }
        tokens.append(nil) // Public-repository access is a final candidate.

        var transientMessage: String?
        for token in tokens {
            guard !Task.isCancelled else { return .transientFailure(String(localized: "Cancelled")) }
            do {
                let identity = try await GitHubService.fetchRepository(
                    owner: String(parts[0]), repo: String(parts[1]), token: token
                )
                guard identity.fullName.caseInsensitiveCompare(fullName) == .orderedSame else {
                    return .definitiveNoAccess
                }
                return .resolved(identity)
            } catch GitHubError.notFound, GitHubError.forbidden, GitHubError.conflict {
                continue
            } catch {
                transientMessage = error.localizedDescription
            }
        }
        if let transientMessage { return .transientFailure(transientMessage) }
        return .definitiveNoAccess
    }
    func setAssistConfigurationChangeHandler(_ handler: (@MainActor @Sendable () -> Void)?) { assistConfigurationChangeHandler = handler }
    func setAssistInventoryChangeHandler(_ handler: (@MainActor @Sendable () -> Void)?) { assistInventoryChangeHandler = handler }
    func recordAssist(result: RepositoryReconciliationResult?, health: RepoAssistHealth, repoID: UUID) {
        guard let index = repoIndex(id: repoID) else { return }
        if let sha = result?.finalLocalCommitSHA, !sha.isEmpty {
            repos[index].gitState.commitSHA = sha
            commitHistoryByRepo[repoID] = []; commitHistoryHasMoreByRepo[repoID] = true; commitDetailByRepo[repoID] = [:]
        }
        if result?.outcome == .upToDate || result?.didTransferData == true {
            repos[index].gitState.lastSyncDate = health.lastSuccessDate ?? repos[index].gitState.lastSyncDate
        }
        repos[index].assist.health = health; saveRepos(); detectChanges(repoID: repoID)
    }
}

enum BackgroundSyncTrigger: Sendable, Equatable { case push(PremiumSilentPush), foreground, processing }
enum BackgroundSyncDisposition: Sendable, Equatable {
    case completed(RepositoryReconciliationResult)
    case deferred(String)
    case ignored

    var didTransferData: Bool {
        if case .completed(let result) = self { return result.didTransferData }
        return false
    }
    var isFailure: Bool {
        if case .completed(let result) = self { return result.isFailure }
        return false
    }
}

@MainActor
final class BackgroundSyncCoordinator {
    private struct Flight { let generation: UUID; let task: Task<BackgroundSyncDisposition, Never> }
    private struct PushFlightKey: Hashable { let repoID: UUID; let hintID: String }
    private let entitlementIsActive: @MainActor () -> Bool
    private weak var repositoryProvider: (any AssistRepositoryProviding)?
    private let conditionsProvider: any BackgroundSyncConditionsProviding
    private let now: @Sendable () -> Date
    private var inFlight: [UUID: Flight] = [:]
    private var foregroundFlightGenerations: [UUID: UUID] = [:]
    private var processingFlightGenerations: [UUID: UUID] = [:]
    private var pushFlightGenerations: [PushFlightKey: UUID] = [:]
    /// Standalone coordinators retain the historical pull-only default. The
    /// installation runtime immediately replaces this with the persisted user
    /// preference during initialization.
    private var automaticallyPullRemoteChanges = true
    private var automaticallyPushLocalChanges = false
    private var seenHints: Set<String> = []; private var hintOrder: [String] = []; private let hintLimit = 256

    init(entitlementIsActive: @escaping @MainActor () -> Bool, repositoryProvider: any AssistRepositoryProviding,
         conditionsProvider: any BackgroundSyncConditionsProviding, now: @escaping @Sendable () -> Date = { Date() }) {
        self.entitlementIsActive = entitlementIsActive; self.repositoryProvider = repositoryProvider
        self.conditionsProvider = conditionsProvider; self.now = now
    }

    func setAutomaticallyPullRemoteChanges(_ enabled: Bool) {
        if automaticallyPullRemoteChanges && !enabled {
            // Revoking pull consent must stop any flight that already captured
            // `allowsPull = true` before it reaches checkout.
            cancelAll()
        }
        automaticallyPullRemoteChanges = enabled
    }

    func setAutomaticallyPushLocalChanges(_ enabled: Bool) {
        if automaticallyPushLocalChanges && !enabled {
            // Revoking publishing consent must stop any flight that already
            // captured `allowsPush = true` before it reaches remote transport.
            cancelAll()
        }
        automaticallyPushLocalChanges = enabled
    }

    func handlePush(_ userInfo: [AnyHashable: Any]) async -> BackgroundSyncDisposition {
        guard !Task.isCancelled, let push = try? PremiumSilentPush.parse(userInfo), entitlementIsActive(),
              let provider = repositoryProvider else { return .ignored }
        let ids = provider.assistRepositories()
            .filter { $0.assist.enabled && $0.assist.channel == push.channel }
            .map(\.id)
        guard !ids.isEmpty, remember(channel: push.channel, hint: push.hintID) else { return .ignored }
        let results = await reconcileMany(ids, limit: 3, trigger: .push(push))
        if let updated = results.values.first(where: \.didTransferData) { return updated }
        if let failed = results.values.first(where: \.isFailure) { return failed }
        return results.values.first ?? .ignored
    }

    func cancelPush(_ userInfo: [AnyHashable: Any]) {
        guard let push = try? PremiumSilentPush.parse(userInfo), let provider = repositoryProvider else { return }
        for repo in provider.assistRepositories() where repo.assist.channel == push.channel {
            let key = PushFlightKey(repoID: repo.id, hintID: push.hintID)
            guard let generation = pushFlightGenerations.removeValue(forKey: key),
                  inFlight[repo.id]?.generation == generation else { continue }
            inFlight.removeValue(forKey: repo.id)?.task.cancel()
        }
    }

    func reconcileForeground() async -> [UUID: BackgroundSyncDisposition] {
        guard entitlementIsActive(), let provider = repositoryProvider else { return [:] }
        return await reconcileMany(
            provider.assistRepositories().filter(\.assist.enabled).map(\.id),
            limit: 3,
            trigger: .foreground
        )
    }

    func reconcileProcessing() async -> [UUID: BackgroundSyncDisposition] {
        guard entitlementIsActive(), let provider = repositoryProvider else { return [:] }
        return await reconcileMany(
            provider.assistRepositories().filter(\.assist.enabled).map(\.id),
            limit: 3,
            trigger: .processing
        )
    }

    private func reconcileMany(
        _ ids: [UUID],
        limit: Int,
        trigger: BackgroundSyncTrigger? = nil
    ) async -> [UUID: BackgroundSyncDisposition] {
        guard !Task.isCancelled else { return [:] }
        var results: [UUID: BackgroundSyncDisposition] = [:]
        for start in stride(from: 0, to: ids.count, by: max(1, limit)) {
            guard !Task.isCancelled else { break }
            let batch = Array(ids[start..<min(ids.count, start + max(1, limit))])
            await withTaskGroup(of: (UUID, BackgroundSyncDisposition).self) { group in
                for id in batch {
                    guard !Task.isCancelled else { break }
                    group.addTask { @MainActor [weak self] in
                        guard !Task.isCancelled, let self else { return (id, .ignored) }
                        return (id, await self.reconcile(repoID: id, trigger: trigger))
                    }
                }
                for await (id, result) in group { results[id] = result }
            }
        }
        return results
    }

    func reconcile(repoID: UUID) async -> BackgroundSyncDisposition {
        await reconcile(repoID: repoID, trigger: nil)
    }

    private func reconcile(repoID: UUID, trigger: BackgroundSyncTrigger?) async -> BackgroundSyncDisposition {
        guard !Task.isCancelled, entitlementIsActive(), let provider = repositoryProvider,
              provider.assistRepository(id: repoID)?.assist.enabled == true else { return .ignored }
        if let existing = inFlight[repoID] { return await existing.task.value }
        guard !Task.isCancelled, entitlementIsActive(),
              provider.assistRepository(id: repoID)?.assist.enabled == true else { return .ignored }
        let generation = UUID(); let conditions = conditionsProvider
        let task = Task<BackgroundSyncDisposition, Never> { [weak self] in
            guard let self else { return .ignored }
            return await self.execute(repoID: repoID, conditionsProvider: conditions)
        }
        inFlight[repoID] = Flight(generation: generation, task: task)
        if trigger == .foreground { foregroundFlightGenerations[repoID] = generation }
        if trigger == .processing { processingFlightGenerations[repoID] = generation }
        let pushKey: PushFlightKey?
        if case .push(let push)? = trigger {
            let key = PushFlightKey(repoID: repoID, hintID: push.hintID)
            pushFlightGenerations[key] = generation
            pushKey = key
        } else {
            pushKey = nil
        }
        let result = await task.value
        if inFlight[repoID]?.generation == generation { inFlight.removeValue(forKey: repoID) }
        if foregroundFlightGenerations[repoID] == generation { foregroundFlightGenerations.removeValue(forKey: repoID) }
        if processingFlightGenerations[repoID] == generation { processingFlightGenerations.removeValue(forKey: repoID) }
        if let pushKey, pushFlightGenerations[pushKey] == generation { pushFlightGenerations.removeValue(forKey: pushKey) }
        return result
    }

    func cancel(repoID: UUID) {
        foregroundFlightGenerations.removeValue(forKey: repoID)
        processingFlightGenerations.removeValue(forKey: repoID)
        let pushKeys = pushFlightGenerations.keys.filter { $0.repoID == repoID }
        for key in pushKeys { pushFlightGenerations.removeValue(forKey: key) }
        inFlight.removeValue(forKey: repoID)?.task.cancel()
    }

    func cancelForegroundReconciliation() {
        let foreground = foregroundFlightGenerations
        foregroundFlightGenerations.removeAll()
        for (repoID, generation) in foreground where inFlight[repoID]?.generation == generation {
            inFlight.removeValue(forKey: repoID)?.task.cancel()
        }
    }

    func cancelProcessingReconciliation() {
        let processing = processingFlightGenerations
        processingFlightGenerations.removeAll()
        for (repoID, generation) in processing where inFlight[repoID]?.generation == generation {
            inFlight.removeValue(forKey: repoID)?.task.cancel()
        }
    }

    func cancelAll() {
        let flights = inFlight.values
        inFlight.removeAll()
        foregroundFlightGenerations.removeAll()
        processingFlightGenerations.removeAll()
        pushFlightGenerations.removeAll()
        flights.forEach { $0.task.cancel() }
    }

    private func execute(repoID: UUID, conditionsProvider: any BackgroundSyncConditionsProviding) async -> BackgroundSyncDisposition {
        guard !Task.isCancelled else { return .deferred(String(localized: "Cancelled")) }
        let conditions = await conditionsProvider.current()
        guard !Task.isCancelled, entitlementIsActive(), let provider = repositoryProvider,
              let repo = provider.assistRepository(id: repoID), repo.assist.enabled else { return .ignored }
        let allowsPull = automaticallyPullRemoteChanges
        let allowsPush = automaticallyPushLocalChanges
        guard allowsPull || allowsPush else { return .ignored }
        if repo.assist.networkPolicy == .wifiOnly && !conditions.isWiFi {
            return recordDeferred(repoID: repoID, message: String(localized: "Waiting for Wi-Fi."))
        }
        if repo.assist.powerPolicy == .externalPowerOnly && !conditions.isExternalPower {
            return recordDeferred(repoID: repoID, message: String(localized: "Waiting for external power."))
        }
        let repository: SerializedGitRepository
        do { repository = try provider.assistRepositoryInstance(id: repoID) }
        catch { return recordDeferred(repoID: repoID, message: error.localizedDescription) }
        let selected = repo.assist.selectedBranch?.trimmingCharacters(in: .whitespacesAndNewlines)
        let expectedBranch = (selected?.isEmpty == false ? selected : repo.branch)
        let result = await RepositoryReconciliationRunner().run(
            serialized: repository,
            repo: repo,
            credentials: provider.assistCredentials(for: repo),
            expectedBranch: expectedBranch,
            allowsPull: allowsPull,
            allowsPush: allowsPush
        )
        let cancelledAfterRun = Task.isCancelled
        if cancelledAfterRun, !result.retainsCompletedWorkOnCancellation {
            return .deferred(String(localized: "Cancelled"))
        }
        let timestamp = now(); let prior = provider.assistRepository(id: repoID)?.assist.health ?? .never
        let health: RepoAssistHealth
        if case .updatedWithAttention(_, let commitSHA, let attention) = result.pull {
            health = postUpdateAttention(prior, attention: attention, commitSHA: commitSHA, at: timestamp)
        } else {
            switch result.outcome {
            case .upToDate:
                health = .init(kind: .upToDate, lastAttemptDate: timestamp, lastSuccessDate: timestamp, commitSHA: result.finalLocalCommitSHA)
            case .pulled, .pushed, .pulledAndPushed:
                health = .init(kind: .updated, lastAttemptDate: timestamp, lastSuccessDate: timestamp, commitSHA: result.finalLocalCommitSHA)
            case .authenticationOrTrustRequired:
                health = preserved(prior, kind: .attention, attention: .authenticationOrTrust, message: result.message ?? String(localized: "Authentication or trust needs attention."), at: timestamp, commitSHA: result.finalLocalCommitSHA, transferred: result.didTransferData)
            case .failed:
                health = preserved(prior, kind: .failed, attention: .failed, message: result.message ?? String(localized: "Background Sync failed."), at: timestamp, commitSHA: result.finalLocalCommitSHA, transferred: result.didTransferData)
            case .blocked:
                let unpushed: Bool
                if case .commitSavedNotPushed = result.push { unpushed = true } else { unpushed = false }
                health = preserved(
                    prior,
                    kind: .attention,
                    attention: unpushed ? .unpushedCommit : attention(for: result.pull),
                    message: result.message ?? String(localized: "Background Sync needs attention."),
                    at: timestamp,
                    commitSHA: result.finalLocalCommitSHA,
                    transferred: result.didTransferData
                )
            }
        }
        provider.recordAssist(result: result, health: health, repoID: repoID)
        return .completed(result)
    }

    private func postUpdateAttention(
        _ old: RepoAssistHealth,
        attention: PullPostUpdateAttention,
        commitSHA: String,
        at date: Date
    ) -> RepoAssistHealth {
        let kind: RepoAssistAttention
        switch attention {
        case .lfsHydrationBlockedByLocalChanges, .lfsHydrationFailed, .cancelledAfterUpdate:
            kind = .lfsHydration
        case .lfsAuthenticationOrTrustRequired:
            kind = .authenticationOrTrust
        case .checkoutIncomplete:
            kind = .localChanges
        }
        return .init(
            kind: .attention,
            attention: kind,
            message: attention.localizedDescription,
            lastAttemptDate: date,
            lastSuccessDate: old.lastSuccessDate,
            commitSHA: commitSHA
        )
    }

    private func attention(for pull: RepositoryPullResult?) -> RepoAssistAttention {
        switch pull {
        case .blockedByLocalChanges: .localChanges
        case .diverged: .diverged
        case .remoteBranchMissing: .remoteBranchMissing
        case .wrongBranch: .wrongBranch
        case .unavailable: .unavailable
        case .updatedWithAttention: .lfsHydration
        default: .failed
        }
    }

    private func preserved(_ old: RepoAssistHealth, kind: RepoAssistHealthKind, attention: RepoAssistAttention? = nil, message: String, at: Date, commitSHA: String? = nil, transferred: Bool = false) -> RepoAssistHealth {
        .init(kind: kind, attention: attention, message: message, lastAttemptDate: at, lastSuccessDate: transferred ? at : old.lastSuccessDate, commitSHA: commitSHA ?? old.commitSHA)
    }
    private func recordDeferred(repoID: UUID, message: String) -> BackgroundSyncDisposition {
        let old = repositoryProvider?.assistRepository(id: repoID)?.assist.health ?? .never
        repositoryProvider?.recordAssist(result: nil, health: preserved(old, kind: .deferred, message: message, at: now()), repoID: repoID)
        return .deferred(message)
    }
    private func remember(channel: String, hint: String) -> Bool {
        let key = channel + "\u{1f}" + hint; guard seenHints.insert(key).inserted else { return false }
        hintOrder.append(key); if hintOrder.count > hintLimit { seenHints.remove(hintOrder.removeFirst()) }; return true
    }
}

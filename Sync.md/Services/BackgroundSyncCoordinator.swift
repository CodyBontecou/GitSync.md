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

@MainActor
protocol AssistRepositoryProviding: AnyObject {
    func assistRepositories() -> [RepoConfig]
    func assistRepository(id: UUID) -> RepoConfig?
    func assistRepositoryInstance(id: UUID) throws -> any GitRepositoryProtocol
    func assistCredentials(for repo: RepoConfig) -> String
    func recordAssist(result: RepositoryPullResult?, health: RepoAssistHealth, repoID: UUID)
    func updateAssistSettings(repoID: UUID, _ settings: RepoAssistSettings)
    func setAssistConfigurationChangeHandler(_ handler: (@MainActor @Sendable () -> Void)?)
}

@MainActor
extension AppState: AssistRepositoryProviding {
    func assistRepositories() -> [RepoConfig] { repos }
    func assistRepository(id: UUID) -> RepoConfig? { repo(id: id) }
    func assistRepositoryInstance(id: UUID) throws -> any GitRepositoryProtocol { try serializedRepository(repoID: id) }
    func assistCredentials(for repo: RepoConfig) -> String { authPayload(for: repo) }
    func updateAssistSettings(repoID: UUID, _ settings: RepoAssistSettings) {
        updateRepo(id: repoID) { $0.assist = settings }
    }
    func setAssistConfigurationChangeHandler(_ handler: (@MainActor @Sendable () -> Void)?) { assistConfigurationChangeHandler = handler }
    func recordAssist(result: RepositoryPullResult?, health: RepoAssistHealth, repoID: UUID) {
        guard let index = repoIndex(id: repoID) else { return }
        switch result {
        case .updated(_, let sha), .upToDate(_, let sha):
            repos[index].gitState.commitSHA = sha; repos[index].gitState.lastSyncDate = health.lastSuccessDate ?? Date()
            commitHistoryByRepo[repoID] = []; commitHistoryHasMoreByRepo[repoID] = true; commitDetailByRepo[repoID] = [:]
        default: break
        }
        repos[index].assist.health = health; saveRepos(); detectChanges(repoID: repoID)
    }
}

enum BackgroundSyncTrigger: Sendable, Equatable { case push(PremiumSilentPush), foreground }
enum BackgroundSyncDisposition: Sendable, Equatable { case completed(RepositoryPullResult); case deferred(String); case ignored }

@MainActor
final class BackgroundSyncCoordinator {
    private struct Flight { let generation: UUID; let task: Task<BackgroundSyncDisposition, Never> }
    private let entitlementIsActive: @MainActor () -> Bool
    private weak var repositoryProvider: (any AssistRepositoryProviding)?
    private let conditionsProvider: any BackgroundSyncConditionsProviding
    private let now: @Sendable () -> Date
    private var inFlight: [UUID: Flight] = [:]
    private var seenHints: Set<String> = []; private var hintOrder: [String] = []; private let hintLimit = 256

    init(entitlementIsActive: @escaping @MainActor () -> Bool, repositoryProvider: any AssistRepositoryProviding,
         conditionsProvider: any BackgroundSyncConditionsProviding, now: @escaping @Sendable () -> Date = { Date() }) {
        self.entitlementIsActive = entitlementIsActive; self.repositoryProvider = repositoryProvider
        self.conditionsProvider = conditionsProvider; self.now = now
    }

    func handlePush(_ userInfo: [AnyHashable: Any]) async -> BackgroundSyncDisposition {
        guard let push = try? PremiumSilentPush.parse(userInfo), entitlementIsActive(), let provider = repositoryProvider,
              let repo = provider.assistRepositories().first(where: { $0.assist.enabled && $0.assist.channel == push.channel }),
              remember(channel: push.channel, hint: push.hintID) else { return .ignored }
        return await reconcile(repoID: repo.id)
    }

    func cancelPush(_ userInfo: [AnyHashable: Any]) {
        guard let push = try? PremiumSilentPush.parse(userInfo), let provider = repositoryProvider,
              let repo = provider.assistRepositories().first(where: { $0.assist.channel == push.channel }) else { return }
        cancel(repoID: repo.id)
    }

    func reconcileForeground() async -> [UUID: BackgroundSyncDisposition] {
        guard entitlementIsActive(), let provider = repositoryProvider else { return [:] }
        var results: [UUID: BackgroundSyncDisposition] = [:]
        for repo in provider.assistRepositories() where repo.assist.enabled { results[repo.id] = await reconcile(repoID: repo.id) }
        return results
    }

    func reconcile(repoID: UUID) async -> BackgroundSyncDisposition {
        guard entitlementIsActive(), let provider = repositoryProvider,
              provider.assistRepository(id: repoID)?.assist.enabled == true else { return .ignored }
        if let existing = inFlight[repoID] { return await existing.task.value }
        let generation = UUID(); let conditions = conditionsProvider
        let task = Task<BackgroundSyncDisposition, Never> { [weak self] in
            guard let self else { return .ignored }
            return await self.execute(repoID: repoID, conditionsProvider: conditions)
        }
        inFlight[repoID] = Flight(generation: generation, task: task)
        let result = await task.value
        if inFlight[repoID]?.generation == generation { inFlight.removeValue(forKey: repoID) }
        return result
    }

    func cancel(repoID: UUID) { inFlight.removeValue(forKey: repoID)?.task.cancel() }
    func cancelAll() { let flights = inFlight.values; inFlight.removeAll(); flights.forEach { $0.task.cancel() } }

    private func execute(repoID: UUID, conditionsProvider: any BackgroundSyncConditionsProviding) async -> BackgroundSyncDisposition {
        guard !Task.isCancelled else { return .deferred("Cancelled") }
        let conditions = await conditionsProvider.current()
        guard !Task.isCancelled, entitlementIsActive(), let provider = repositoryProvider,
              let repo = provider.assistRepository(id: repoID), repo.assist.enabled else { return .ignored }
        if repo.assist.networkPolicy == .wifiOnly && !conditions.isWiFi { return recordDeferred(repoID: repoID, message: "Waiting for Wi-Fi.") }
        if repo.assist.powerPolicy == .externalPowerOnly && !conditions.isExternalPower { return recordDeferred(repoID: repoID, message: "Waiting for external power.") }
        let repository: any GitRepositoryProtocol
        do { repository = try provider.assistRepositoryInstance(id: repoID) }
        catch { return recordDeferred(repoID: repoID, message: error.localizedDescription) }
        let selected = repo.assist.selectedBranch?.trimmingCharacters(in: .whitespacesAndNewlines)
        let expectedBranch = (selected?.isEmpty == false ? selected : repo.branch)
        let result = await RepositoryPullRunner().run(repository: repository, credentials: provider.assistCredentials(for: repo), expectedBranch: expectedBranch)
        guard !Task.isCancelled else { return .deferred("Cancelled") }
        let timestamp = now(); let prior = provider.assistRepository(id: repoID)?.assist.health ?? .never
        let health: RepoAssistHealth
        switch result {
        case .updated(_, let sha): health = .init(kind: .updated, lastAttemptDate: timestamp, lastSuccessDate: timestamp, commitSHA: sha)
        case .upToDate(_, let sha): health = .init(kind: .upToDate, lastAttemptDate: timestamp, lastSuccessDate: timestamp, commitSHA: sha)
        case .blockedByLocalChanges: health = preserved(prior, kind: .attention, attention: .localChanges, message: "Local changes need attention.", at: timestamp)
        case .diverged: health = preserved(prior, kind: .attention, attention: .diverged, message: "Local and remote history diverged.", at: timestamp)
        case .remoteBranchMissing: health = preserved(prior, kind: .attention, attention: .remoteBranchMissing, message: "Remote branch is missing.", at: timestamp)
        case .authenticationOrTrustRequired(let message, _): health = preserved(prior, kind: .attention, attention: .authenticationOrTrust, message: message, at: timestamp)
        case .wrongBranch: health = preserved(prior, kind: .attention, attention: .wrongBranch, message: "Selected branch is not currently checked out.", at: timestamp)
        case .unavailable(let message): health = preserved(prior, kind: .attention, attention: .unavailable, message: message, at: timestamp)
        case .failed(let message): health = preserved(prior, kind: .failed, attention: .failed, message: message, at: timestamp)
        }
        provider.recordAssist(result: result, health: health, repoID: repoID); return .completed(result)
    }

    private func preserved(_ old: RepoAssistHealth, kind: RepoAssistHealthKind, attention: RepoAssistAttention? = nil, message: String, at: Date) -> RepoAssistHealth {
        .init(kind: kind, attention: attention, message: message, lastAttemptDate: at, lastSuccessDate: old.lastSuccessDate, commitSHA: old.commitSHA)
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

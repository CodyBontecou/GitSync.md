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
    func assistRepositoryInstance(id: UUID) throws -> SerializedGitRepository
    func assistCredentials(for repo: RepoConfig) -> String
    func recordAssist(result: RepositoryReconciliationResult?, health: RepoAssistHealth, repoID: UUID)
    func updateAssistSettings(repoID: UUID, _ settings: RepoAssistSettings)
    func setAssistConfigurationChangeHandler(_ handler: (@MainActor @Sendable () -> Void)?)
    func setAssistInventoryChangeHandler(_ handler: (@MainActor @Sendable () -> Void)?)
    /// Called when a reconciliation flight begins actual Git work (fetch,
    /// pull, push). Pairs with `backgroundSyncDidFinish(repoID:)` so UI can
    /// surface an activity indicator around the exact window of lag.
    func backgroundSyncDidBegin(repoID: UUID)
    /// Called exactly once per `backgroundSyncDidBegin(repoID:)`, including
    /// when the flight is cancelled mid-run.
    func backgroundSyncDidFinish(repoID: UUID)
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
    func setAssistConfigurationChangeHandler(_ handler: (@MainActor @Sendable () -> Void)?) { assistConfigurationChangeHandler = handler }
    func setAssistInventoryChangeHandler(_ handler: (@MainActor @Sendable () -> Void)?) { assistInventoryChangeHandler = handler }
    func backgroundSyncDidBegin(repoID: UUID) { backgroundSyncFlightCounts[repoID, default: 0] += 1 }
    func backgroundSyncDidFinish(repoID: UUID) {
        let remaining = (backgroundSyncFlightCounts[repoID] ?? 0) - 1
        if remaining > 0 { backgroundSyncFlightCounts[repoID] = remaining }
        else { backgroundSyncFlightCounts.removeValue(forKey: repoID) }
    }
    func recordAssist(result: RepositoryReconciliationResult?, health: RepoAssistHealth, repoID: UUID) {
        guard let index = repoIndex(id: repoID) else { return }
        if let sha = result?.finalLocalCommitSHA, !sha.isEmpty {
            repos[index].gitState.commitSHA = sha
            commitHistoryByRepo[repoID] = []; commitHistoryHasMoreByRepo[repoID] = true; commitDetailByRepo[repoID] = [:]
        }
        // Only a pass that moved data (pulled/pushed commits) advances the
        // "last sync" clock — an up-to-date verification must not, or the
        // repo-card chip would reset to "just now" on every app open.
        if result?.didTransferData == true {
            repos[index].gitState.lastSyncDate = health.lastSuccessDate ?? repos[index].gitState.lastSyncDate
        }
        repos[index].assist.health = health; saveRepos()
        // A deferred pass (nil result) or an up-to-date verification touched
        // neither the working tree nor HEAD, so the cached status entries are
        // still valid. Skipping the rescan halves the Git work of a no-op
        // pass — the remaining fetch-only plan is the fast change check.
        let treeUntouched = result == nil || result?.outcome == .upToDate
        if !treeUntouched { detectChanges(repoID: repoID) }
    }
}

enum BackgroundSyncTrigger: Sendable, Equatable { case foreground, processing }
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
    private weak var repositoryProvider: (any AssistRepositoryProviding)?
    private let conditionsProvider: any BackgroundSyncConditionsProviding
    private let now: @Sendable () -> Date
    private var inFlight: [UUID: Flight] = [:]
    private var foregroundFlightGenerations: [UUID: UUID] = [:]
    private var processingFlightGenerations: [UUID: UUID] = [:]
    /// Standalone coordinators retain the historical pull-only default. The
    /// installation runtime immediately replaces this with the persisted user
    /// preference during initialization.
    private var automaticallyPullRemoteChanges = true
    private var automaticallyPushLocalChanges = false

    init(repositoryProvider: any AssistRepositoryProviding,
         conditionsProvider: any BackgroundSyncConditionsProviding, now: @escaping @Sendable () -> Date = { Date() }) {
        self.repositoryProvider = repositoryProvider
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

    func reconcileForeground() async -> [UUID: BackgroundSyncDisposition] {
        guard let provider = repositoryProvider else { return [:] }
        return await reconcileMany(
            provider.assistRepositories().filter(\.assist.enabled).map(\.id),
            // Foreground work is deliberately serialized. Multiple concurrent
            // libgit2 scans can saturate device I/O even when they are off the
            // main actor, which still makes scrolling and navigation stutter.
            limit: 1,
            trigger: .foreground
        )
    }

    func reconcileProcessing() async -> [UUID: BackgroundSyncDisposition] {
        guard let provider = repositoryProvider else { return [:] }
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
        guard !Task.isCancelled, let provider = repositoryProvider,
              provider.assistRepository(id: repoID)?.assist.enabled == true else { return .ignored }
        if let existing = inFlight[repoID] { return await existing.task.value }
        guard !Task.isCancelled,
              provider.assistRepository(id: repoID)?.assist.enabled == true else { return .ignored }
        let generation = UUID(); let conditions = conditionsProvider
        let task = Task<BackgroundSyncDisposition, Never> { [weak self] in
            guard let self else { return .ignored }
            return await self.execute(repoID: repoID, conditionsProvider: conditions)
        }
        inFlight[repoID] = Flight(generation: generation, task: task)
        if trigger == .foreground { foregroundFlightGenerations[repoID] = generation }
        if trigger == .processing { processingFlightGenerations[repoID] = generation }
        let result = await task.value
        if inFlight[repoID]?.generation == generation { inFlight.removeValue(forKey: repoID) }
        if foregroundFlightGenerations[repoID] == generation { foregroundFlightGenerations.removeValue(forKey: repoID) }
        if processingFlightGenerations[repoID] == generation { processingFlightGenerations.removeValue(forKey: repoID) }
        return result
    }

    func cancel(repoID: UUID) {
        foregroundFlightGenerations.removeValue(forKey: repoID)
        processingFlightGenerations.removeValue(forKey: repoID)
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
        flights.forEach { $0.task.cancel() }
    }

    private func execute(repoID: UUID, conditionsProvider: any BackgroundSyncConditionsProviding) async -> BackgroundSyncDisposition {
        guard !Task.isCancelled else { return .deferred(String(localized: "Cancelled")) }
        let conditions = await conditionsProvider.current()
        guard !Task.isCancelled, let provider = repositoryProvider,
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
        // Actual Git work starts here — surface it so foreground lag during
        // the fetch/pull/push is explained. `defer` guarantees the matching
        // finish signal even when the flight is cancelled mid-run.
        provider.backgroundSyncDidBegin(repoID: repoID)
        defer { provider.backgroundSyncDidFinish(repoID: repoID) }
        let credentials = provider.assistCredentials(for: repo)
        // `BackgroundSyncCoordinator` owns UI-facing state and is therefore
        // main-actor isolated. Keep only that state bookkeeping here: the
        // complete Git workflow runs in a detached utility task so synchronous
        // filesystem/LFS work between suspension points can never occupy the
        // UI executor. Explicit cancellation forwarding is required because a
        // detached task does not inherit its parent's cancellation.
        let gitTask = Task.detached(priority: .utility) {
            await RepositoryReconciliationRunner().run(
                serialized: repository,
                repo: repo,
                credentials: credentials,
                expectedBranch: expectedBranch,
                allowsPull: allowsPull,
                allowsPush: allowsPush
            )
        }
        let result = await withTaskCancellationHandler {
            await gitTask.value
        } onCancel: {
            gitTask.cancel()
        }
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
}

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
    func assistRepositoryInstance(id: UUID) throws -> any GitRepositoryProtocol
    func assistCredentials(for repo: RepoConfig) -> String
    func recordAssist(result: RepositoryPullResult?, health: RepoAssistHealth, repoID: UUID)
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
    func assistRepositoryInstance(id: UUID) throws -> any GitRepositoryProtocol { try serializedRepository(repoID: id) }
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
    func recordAssist(result: RepositoryPullResult?, health: RepoAssistHealth, repoID: UUID) {
        guard let index = repoIndex(id: repoID) else { return }
        switch result {
        case .updated(_, let sha), .upToDate(_, let sha):
            repos[index].gitState.commitSHA = sha; repos[index].gitState.lastSyncDate = health.lastSuccessDate ?? Date()
            commitHistoryByRepo[repoID] = []; commitHistoryHasMoreByRepo[repoID] = true; commitDetailByRepo[repoID] = [:]
        case .updatedWithAttention(_, let sha, _):
            repos[index].gitState.commitSHA = sha
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
    private var foregroundFlightGenerations: [UUID: UUID] = [:]
    private var seenHints: Set<String> = []; private var hintOrder: [String] = []; private let hintLimit = 256

    init(entitlementIsActive: @escaping @MainActor () -> Bool, repositoryProvider: any AssistRepositoryProviding,
         conditionsProvider: any BackgroundSyncConditionsProviding, now: @escaping @Sendable () -> Date = { Date() }) {
        self.entitlementIsActive = entitlementIsActive; self.repositoryProvider = repositoryProvider
        self.conditionsProvider = conditionsProvider; self.now = now
    }

    func handlePush(_ userInfo: [AnyHashable: Any]) async -> BackgroundSyncDisposition {
        guard !Task.isCancelled, let push = try? PremiumSilentPush.parse(userInfo), entitlementIsActive(),
              let provider = repositoryProvider else { return .ignored }
        let ids = provider.assistRepositories()
            .filter { $0.assist.enabled && $0.assist.channel == push.channel }
            .map(\.id)
        guard !ids.isEmpty, remember(channel: push.channel, hint: push.hintID) else { return .ignored }
        let results = await reconcileMany(ids, limit: 3)
        if let updated = results.values.first(where: { if case .completed(.updated) = $0 { true } else { false } }) { return updated }
        if let failed = results.values.first(where: { if case .completed(.failed) = $0 { true } else { false } }) { return failed }
        return results.values.first ?? .ignored
    }

    func cancelPush(_ userInfo: [AnyHashable: Any]) {
        guard let push = try? PremiumSilentPush.parse(userInfo), let provider = repositoryProvider else { return }
        provider.assistRepositories()
            .filter { $0.assist.channel == push.channel }
            .forEach { cancel(repoID: $0.id) }
    }

    func reconcileForeground() async -> [UUID: BackgroundSyncDisposition] {
        guard entitlementIsActive(), let provider = repositoryProvider else { return [:] }
        return await reconcileMany(
            provider.assistRepositories().filter(\.assist.enabled).map(\.id),
            limit: 3,
            foreground: true
        )
    }

    private func reconcileMany(
        _ ids: [UUID],
        limit: Int,
        foreground: Bool = false
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
                        return (id, await self.reconcile(repoID: id, foreground: foreground))
                    }
                }
                for await (id, result) in group { results[id] = result }
            }
        }
        return results
    }

    func reconcile(repoID: UUID) async -> BackgroundSyncDisposition {
        await reconcile(repoID: repoID, foreground: false)
    }

    private func reconcile(repoID: UUID, foreground: Bool) async -> BackgroundSyncDisposition {
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
        if foreground { foregroundFlightGenerations[repoID] = generation }
        let result = await task.value
        if inFlight[repoID]?.generation == generation { inFlight.removeValue(forKey: repoID) }
        if foregroundFlightGenerations[repoID] == generation {
            foregroundFlightGenerations.removeValue(forKey: repoID)
        }
        return result
    }

    func cancel(repoID: UUID) {
        foregroundFlightGenerations.removeValue(forKey: repoID)
        inFlight.removeValue(forKey: repoID)?.task.cancel()
    }

    func cancelForegroundReconciliation() {
        let foreground = foregroundFlightGenerations
        foregroundFlightGenerations.removeAll()
        for (repoID, generation) in foreground where inFlight[repoID]?.generation == generation {
            inFlight.removeValue(forKey: repoID)?.task.cancel()
        }
    }

    func cancelAll() {
        let flights = inFlight.values
        inFlight.removeAll()
        foregroundFlightGenerations.removeAll()
        flights.forEach { $0.task.cancel() }
    }

    private func execute(repoID: UUID, conditionsProvider: any BackgroundSyncConditionsProviding) async -> BackgroundSyncDisposition {
        guard !Task.isCancelled else { return .deferred(String(localized: "Cancelled")) }
        let conditions = await conditionsProvider.current()
        guard !Task.isCancelled, entitlementIsActive(), let provider = repositoryProvider,
              let repo = provider.assistRepository(id: repoID), repo.assist.enabled else { return .ignored }
        if repo.assist.networkPolicy == .wifiOnly && !conditions.isWiFi {
            return recordDeferred(repoID: repoID, message: String(localized: "Waiting for Wi-Fi."))
        }
        if repo.assist.powerPolicy == .externalPowerOnly && !conditions.isExternalPower {
            return recordDeferred(repoID: repoID, message: String(localized: "Waiting for external power."))
        }
        let repository: any GitRepositoryProtocol
        do { repository = try provider.assistRepositoryInstance(id: repoID) }
        catch { return recordDeferred(repoID: repoID, message: error.localizedDescription) }
        let selected = repo.assist.selectedBranch?.trimmingCharacters(in: .whitespacesAndNewlines)
        let expectedBranch = (selected?.isEmpty == false ? selected : repo.branch)
        let result = await RepositoryPullRunner().run(repository: repository, credentials: provider.assistCredentials(for: repo), expectedBranch: expectedBranch)
        let cancelledAfterRun = Task.isCancelled
        if cancelledAfterRun {
            switch result {
            case .updated, .updatedWithAttention:
                // The Git transaction already committed. Persist its SHA and
                // attention before returning a cancelled disposition.
                break
            default:
                return .deferred(String(localized: "Cancelled"))
            }
        }
        let timestamp = now(); let prior = provider.assistRepository(id: repoID)?.assist.health ?? .never
        let health: RepoAssistHealth
        switch result {
        case .updated(_, let sha): health = .init(kind: .updated, lastAttemptDate: timestamp, lastSuccessDate: timestamp, commitSHA: sha)
        case .updatedWithAttention(_, let sha, let attention):
            health = postUpdateAttention(prior, attention: attention, commitSHA: sha, at: timestamp)
        case .upToDate(_, let sha): health = .init(kind: .upToDate, lastAttemptDate: timestamp, lastSuccessDate: timestamp, commitSHA: sha)
        case .blockedByLocalChanges:
            health = preserved(prior, kind: .attention, attention: .localChanges,
                               message: String(localized: "Local changes need attention."), at: timestamp)
        case .diverged:
            health = preserved(prior, kind: .attention, attention: .diverged,
                               message: String(localized: "Local and remote history diverged."), at: timestamp)
        case .remoteBranchMissing:
            health = preserved(prior, kind: .attention, attention: .remoteBranchMissing,
                               message: String(localized: "Remote branch is missing."), at: timestamp)
        case .authenticationOrTrustRequired(let message, _): health = preserved(prior, kind: .attention, attention: .authenticationOrTrust, message: message, at: timestamp)
        case .wrongBranch:
            health = preserved(prior, kind: .attention, attention: .wrongBranch,
                               message: String(localized: "Selected branch is not currently checked out."), at: timestamp)
        case .unavailable(let message): health = preserved(prior, kind: .attention, attention: .unavailable, message: message, at: timestamp)
        case .failed(let message): health = preserved(prior, kind: .failed, attention: .failed, message: message, at: timestamp)
        }
        provider.recordAssist(result: result, health: health, repoID: repoID)
        return cancelledAfterRun ? .deferred(String(localized: "Cancelled")) : .completed(result)
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

import Foundation
import Network
import Observation
import SwiftUI
import UIKit
import UserNotifications
import WidgetKit

/// Runtime-only collaborators for the sync-state features (not observed).
@MainActor
final class SyncStateRuntime {
    var started = false
    var pathMonitor: NWPathMonitor?
    /// Cancels the foreground Git task behind `AppState.syncActivity`.
    var cancelHandle: (() -> Void)?
    var observedProgressPath: String?
    var liveActivity = SyncLiveActivityController()
    var toastDismissTask: Task<Void, Never>?
    var snapshotTask: Task<Void, Never>?
    var replayTask: Task<Void, Never>?
    weak var premiumRuntime: PremiumRuntime?
    /// Vault the most recent foreground Git operation targeted, for errors
    /// reported after `syncingRepoID` was cleared.
    var lastOperationRepoID: UUID?
    var lastOperationKind: SyncActivityKind?
    var activationObserver: NSObjectProtocol?
}

private enum SyncStatesDefaults {
    static let authExpired = "syncStates.authExpiredRepoIDs.v1"
    static let authExpiredAt = "syncStates.authExpiredAt.v1"
    static let offlineQueue = "syncStates.offlineQueue.v1"
    static let backgroundSummary = "syncStates.backgroundSummary.v1"
    static let backgroundStopped = "syncStates.backgroundStopped.v1"
    static let backgroundStopReasons = "syncStates.backgroundStopReasons.v1"
}

extension AppState {
    // MARK: - Startup

    /// Starts connectivity monitoring, widget snapshot mirroring and restores
    /// persisted sync states. Called once by the app (not by unit tests).
    func startSyncStateServices(premiumRuntime: PremiumRuntime?) {
        guard !syncStateRuntime.started else { return }
        syncStateRuntime.started = true
        syncStateRuntime.premiumRuntime = premiumRuntime
        restoreSyncStates()
        startConnectivityMonitor()
        observeStatusForWidgets()
        applyPendingBackgroundSyncToggle()
        syncStateRuntime.activationObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.applicationDidBecomeActiveForSyncStates() }
        }
        if premiumRuntime?.automaticallySyncAllRepositories == true {
            requestNotificationAuthorizationIfNeeded()
        }
    }

    private func applicationDidBecomeActiveForSyncStates() {
        applyPendingBackgroundSyncToggle()
        scheduleWidgetSnapshot()
    }

    private func restoreSyncStates() {
        let defaults = UserDefaults.standard
        if let ids = defaults.stringArray(forKey: SyncStatesDefaults.authExpired) {
            authExpiredRepoIDs = Set(ids.compactMap(UUID.init(uuidString:))).filter { repo(id: $0) != nil }
        }
        authExpiredAt = defaults.object(forKey: SyncStatesDefaults.authExpiredAt) as? Date
        if let data = defaults.data(forKey: SyncStatesDefaults.offlineQueue),
           let queue = try? JSONDecoder().decode([UUID: OfflineQueuedOperation].self, from: data) {
            offlineQueue = queue.filter { repo(id: $0.key) != nil }
        }
        if let data = defaults.data(forKey: SyncStatesDefaults.backgroundSummary),
           let summary = try? JSONDecoder().decode(BackgroundRunSummary.self, from: data) {
            backgroundRunSummary = summary
        }
        if let ids = defaults.stringArray(forKey: SyncStatesDefaults.backgroundStopped) {
            backgroundStoppedRepoIDs = ids.compactMap(UUID.init(uuidString:)).filter { repo(id: $0) != nil }
        }
        if let reasons = defaults.dictionary(forKey: SyncStatesDefaults.backgroundStopReasons) as? [String: String] {
            for (key, value) in reasons {
                if let id = UUID(uuidString: key), let reason = RepoAssistAttention(rawValue: value) {
                    backgroundStopReasons[id] = reason
                }
            }
        }
    }

    private func persistSyncStates() {
        let defaults = UserDefaults.standard
        defaults.set(authExpiredRepoIDs.map(\.uuidString), forKey: SyncStatesDefaults.authExpired)
        defaults.set(authExpiredAt, forKey: SyncStatesDefaults.authExpiredAt)
        defaults.set(try? JSONEncoder().encode(offlineQueue), forKey: SyncStatesDefaults.offlineQueue)
        defaults.set(try? JSONEncoder().encode(backgroundRunSummary), forKey: SyncStatesDefaults.backgroundSummary)
        defaults.set(backgroundStoppedRepoIDs.map(\.uuidString), forKey: SyncStatesDefaults.backgroundStopped)
        defaults.set(
            Dictionary(uniqueKeysWithValues: backgroundStopReasons.map { ($0.key.uuidString, $0.value.rawValue) }),
            forKey: SyncStatesDefaults.backgroundStopReasons
        )
    }

    // MARK: - Helpers

    func remoteHostName(for repo: RepoConfig) -> String {
        guard let remote = GitRemoteURL.parse(repo.repoURL) else { return String(localized: "the remote") }
        if remote.isGitHub { return "GitHub" }
        return remote.host ?? String(localized: "the remote")
    }

    func isGitHubTokenVault(_ repo: RepoConfig) -> Bool {
        repo.authMethod == .gitHubPAT
    }

    private func currentCommitsAhead(repoID: UUID) -> Int {
        branchesByRepo[repoID]?.local.first(where: \.isCurrent)?.aheadBy ?? 0
    }

    // MARK: - Structured progress

    /// Starts structured progress for a foreground operation and routes
    /// libgit2 transfer callbacks for this vault into it.
    func beginSyncActivity(repoID: UUID, kind: SyncActivityKind) {
        guard let repo = repo(id: repoID) else { return }
        syncStateRuntime.lastOperationRepoID = repoID
        syncStateRuntime.lastOperationKind = kind
        if let previous = syncStateRuntime.observedProgressPath {
            GitTransferProgressCenter.shared.stopObserving(path: previous)
        }
        let activity = SyncActivity(
            repoID: repoID,
            kind: kind,
            vaultName: repo.displayName,
            branch: repo.gitState.branch.isEmpty ? repo.branch : repo.gitState.branch,
            host: remoteHostName(for: repo),
            commitsAhead: currentCommitsAhead(repoID: repoID)
        )
        syncActivity = activity
        let path = vaultURL(for: repoID).path
        syncStateRuntime.observedProgressPath = path
        let activityID = activity.id
        // AppState lives for the whole process; capture it directly.
        GitTransferProgressCenter.shared.observe(path: path) { event in
            Task { @MainActor in self.applyTransferEvent(event, activityID: activityID) }
        }
        syncStateRuntime.liveActivity.scheduleStart(for: activity)
    }

    private func applyTransferEvent(_ event: GitTransferEvent, activityID: UUID) {
        guard var activity = syncActivity, activity.id == activityID, !activity.finished else { return }
        activity.apply(event)
        syncActivity = activity
        syncStateRuntime.liveActivity.update(with: activity)
    }

    /// Settles progress; the hero lingers briefly on the finished state.
    func finishSyncActivity(repoID: UUID, success: Bool) {
        if let path = syncStateRuntime.observedProgressPath {
            GitTransferProgressCenter.shared.stopObserving(path: path)
            syncStateRuntime.observedProgressPath = nil
        }
        guard var activity = syncActivity, activity.repoID == repoID else { return }
        activity.finish(success: success)
        syncActivity = activity
        syncStateRuntime.liveActivity.end(with: activity)
        let finishedID = activity.id
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(1.2))
            if self?.syncActivity?.id == finishedID { self?.syncActivity = nil }
        }
        scheduleWidgetSnapshot()
    }

    /// Cancel button: cancels the running Git task. libgit2 transport stops
    /// through the services' existing cancellation signals.
    func cancelActiveSync() {
        guard var activity = syncActivity, !activity.finished else { return }
        activity.isCancelling = true
        syncActivity = activity
        syncStateRuntime.cancelHandle?()
    }

    var canCancelActiveSync: Bool { syncStateRuntime.cancelHandle != nil }

    /// Runs foreground Git work in a child task that the Cancel button can
    /// cancel without cancelling the caller's own task.
    func runCancellable<T: Sendable>(_ operation: @escaping @MainActor () async -> T) async -> T {
        let task = Task { @MainActor in await operation() }
        syncStateRuntime.cancelHandle = { task.cancel() }
        defer { syncStateRuntime.cancelHandle = nil }
        return await withTaskCancellationHandler {
            await task.value
        } onCancel: {
            task.cancel()
        }
    }

    func runCancellableThrowing<T: Sendable>(_ operation: @escaping @MainActor () async throws -> T) async throws -> T {
        let task = Task { @MainActor in try await operation() }
        syncStateRuntime.cancelHandle = { task.cancel() }
        defer { syncStateRuntime.cancelHandle = nil }
        return try await withTaskCancellationHandler {
            try await task.value
        } onCancel: {
            task.cancel()
        }
    }

    // MARK: - Error classification

    nonisolated static func isAuthenticationFailure(_ message: String) -> Bool {
        let lower = message.lowercased()
        if lower.range(of: #"\b401\b"#, options: .regularExpression) != nil { return true }
        let needles = [
            "authentication failed", "authentication replays", "unauthorized",
            "invalid or expired token", "bad credentials", "rejected the saved token",
            "remote authentication required", "invalid username or password",
        ]
        return needles.contains { lower.contains($0) }
    }

    /// Non-fast-forward rejection reported by `push_update_reference`.
    nonisolated static func isPushRejection(_ message: String) -> Bool {
        let lower = message.lowercased()
        guard lower.contains("refs/heads/") || lower.contains("push failed") else { return false }
        return ["fetch first", "non-fast-forward", "non fast forward", "rejected", "stale info", "failed to update ref"]
            .contains { lower.contains($0) }
    }

    nonisolated static func isNetworkFailure(_ message: String) -> Bool {
        let lower = message.lowercased()
        let needles = [
            "timed out", "timeout", "could not resolve", "failed to resolve", "network is unreachable",
            "connection refused", "no route to host", "network connection was lost", "not connected to the internet",
            "failed to connect", "could not connect", "connection reset", "host is down", "offline",
        ]
        return needles.contains { lower.contains($0) }
    }

    /// Returns true when the error was turned into a dedicated sync state.
    func interceptSyncError(message: String, category: String) -> Bool {
        // GitHub API 401 outside a sync (repo list refresh, profile).
        if syncingRepoID == nil, ["general", "oauth"].contains(category),
           message == GitHubError.unauthorized.localizedDescription {
            let affected = repos.filter { isGitHubTokenVault($0) && $0.isCloned }.map(\.id)
            guard !affected.isEmpty else { return false }
            markAuthExpired(repoIDs: affected)
            return true
        }
        let syncCategories: Set<String> = ["pull", "push", "clone", "rebase", "general"]
        guard syncCategories.contains(category),
              let repoID = syncingRepoID ?? (category == "general" ? nil : syncStateRuntime.lastOperationRepoID),
              let repo = repo(id: repoID) else { return false }

        if Self.isAuthenticationFailure(message), isGitHubTokenVault(repo) {
            markAuthExpired(repoIDs: [repoID])
            return true
        }
        if Self.isPushRejection(message), category == "push" || category == "general" {
            recordPushRejection(repoID: repoID, message: message)
            return true
        }
        if Self.isNetworkFailure(message) {
            if isOffline {
                enqueueOffline(repoID: repoID, operation: queuedOperation(for: syncStateRuntime.lastOperationKind))
            } else {
                showToast(TideToastItem(
                    kind: .error,
                    title: String(localized: "Couldn't reach \(remoteHostName(for: repo))"),
                    detail: "\(repo.displayName) · \(Self.shortReason(message))",
                    action: .details(message: message)
                ))
                DebugLogger.shared.error(category, message)
            }
            return true
        }
        return false
    }

    nonisolated static func shortReason(_ message: String) -> String {
        let lower = message.lowercased()
        if lower.contains("timed out") || lower.contains("timeout") {
            return lower.contains("ssh") ? String(localized: "ssh timed out") : String(localized: "timed out")
        }
        if lower.contains("resolve") { return String(localized: "host not found") }
        if lower.contains("refused") { return String(localized: "connection refused") }
        if lower.contains("unreachable") || lower.contains("no route") { return String(localized: "network unreachable") }
        let last = message.split(separator: ":").last.map(String.init) ?? message
        let trimmed = last.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.count > 40 ? String(trimmed.prefix(40)) + "…" : trimmed
    }

    private func queuedOperation(for kind: SyncActivityKind?) -> OfflineQueuedOperation {
        switch kind {
        case .commitAndPush, .push: return .push
        case .sync: return .sync
        default: return .pull
        }
    }

    // MARK: - Sign-in expired

    var isSyncPausedForSignIn: Bool { !authExpiredRepoIDs.isEmpty }

    func markAuthExpired(repoIDs: [UUID]) {
        let fresh = Set(repoIDs).subtracting(authExpiredRepoIDs)
        guard !fresh.isEmpty else { return }
        if authExpiredRepoIDs.isEmpty { authExpiredAt = Date() }
        authExpiredRepoIDs.formUnion(fresh)
        // Every GitHub-token vault on the same account is equally affected.
        let logins = Set(repoIDs.compactMap { repo(id: $0)?.gitHubAccountLogin?.lowercased() })
        for other in repos where isGitHubTokenVault(other) && other.isCloned {
            let login = other.gitHubAccountLogin?.lowercased()
            if logins.isEmpty || login == nil || logins.contains(login!) {
                authExpiredRepoIDs.insert(other.id)
            }
        }
        DebugLogger.shared.warning("auth", "GitHub sign-in expired", detail: "\(authExpiredRepoIDs.count) vaults paused")
        persistSyncStates()
        scheduleWidgetSnapshot()
    }

    func clearAuthExpired() {
        guard !authExpiredRepoIDs.isEmpty else { return }
        authExpiredRepoIDs = []
        authExpiredAt = nil
        persistSyncStates()
        scheduleWidgetSnapshot()
    }

    /// "Continue with GitHub": re-run OAuth, then resume held vaults.
    func reauthenticateGitHub() async {
        let before = pat
        await signInWithGitHub()
        let after = pat
        guard isSignedIn, !after.isEmpty, after != before else { return }
        let waiting = authExpiredRepoIDs
        clearAuthExpired()
        for repoID in waiting {
            guard let repo = repo(id: repoID), repo.isCloned else { continue }
            if currentCommitsAhead(repoID: repoID) > 0 {
                _ = await pushCurrentBranch(repoID: repoID)
            } else {
                _ = await pullOnly(repoID: repoID, showsProgressDelay: false)
            }
        }
    }

    // MARK: - Push rejected

    func recordPushRejection(repoID: UUID, message: String) {
        guard let repo = repo(id: repoID) else { return }
        let branch = repo.gitState.branch.isEmpty ? repo.branch : repo.gitState.branch
        let lower = message.lowercased()
        let reason = lower.contains("fetch first") ? "fetch first"
            : lower.contains("non-fast-forward") ? "non-fast-forward"
            : lower.contains("stale info") ? "stale info"
            : "rejected"
        pushRejectionByRepo[repoID] = PushRejectionState(
            repoID: repoID,
            branch: branch,
            host: remoteHostName(for: repo),
            rawLine: "! [rejected] \(branch) -> \(branch) (\(reason))",
            rejectedAt: Date()
        )
        DebugLogger.shared.warning("push", "Push rejected", detail: message)
        Task { await analyzePushRejection(repoID: repoID) }
    }

    func analyzePushRejection(repoID: UUID) async {
        guard var state = pushRejectionByRepo[repoID], let repo = repo(id: repoID) else { return }
        state.isAnalyzing = true
        state.analysisError = nil
        pushRejectionByRepo[repoID] = state
        let url = vaultURL(for: repoID)
        let credentials = authPayload(for: repo)
        do {
            let serialized = try serializedRepository(repoID: repoID)
            let analysis = try await serialized.withLease { _ in
                try await LocalGitService(localURL: url).analyzeIncomingChanges(pat: credentials)
            }
            guard var current = pushRejectionByRepo[repoID] else { return }
            current.analysis = analysis
            current.isAnalyzing = false
            pushRejectionByRepo[repoID] = current
            if analysis.behindBy == 0 {
                // The remote no longer has anything new (e.g. someone reverted).
                pushRejectionByRepo[repoID] = nil
            }
            await loadBranches(repoID: repoID)
        } catch {
            guard var current = pushRejectionByRepo[repoID] else { return }
            current.isAnalyzing = false
            current.analysisError = error.localizedDescription
            pushRejectionByRepo[repoID] = current
        }
    }

    /// "Bring theirs in, then push": merge the fetched remote branch, then push.
    /// Conflicts fall through to the existing conflict flow.
    func bringTheirsInThenPush(repoID: UUID) async {
        guard var state = pushRejectionByRepo[repoID] else { return }
        state.isResolving = true
        pushRejectionByRepo[repoID] = state
        if isOffline {
            pushRejectionByRepo[repoID]?.isResolving = false
            enqueueOffline(repoID: repoID, operation: .sync)
            return
        }
        // Make sure origin/<branch> is current before merging it.
        if state.analysis == nil { await analyzePushRejection(repoID: repoID) }
        pullOutcomeByRepo[repoID] = nil
        await mergeWithRemote(repoID: repoID)
        if conflictSessionByRepo[repoID]?.isActive == true {
            // Hand over to the existing conflict flow.
            pushRejectionByRepo[repoID] = nil
            return
        }
        switch pullOutcomeByRepo[repoID]?.kind {
        case .fastForwarded:
            break
        case .upToDate:
            // Nothing to merge any more; just publish the local commits.
            guard await pushCurrentBranch(repoID: repoID) else {
                pushRejectionByRepo[repoID]?.isResolving = false
                return
            }
        default:
            pushRejectionByRepo[repoID]?.isResolving = false
            return
        }
        pushRejectionByRepo[repoID] = nil
        if let repo = repo(id: repoID) {
            showToast(TideToastItem(
                kind: .pushed,
                title: String(localized: "Merged and pushed"),
                detail: "\(repo.displayName) · \(String(repo.gitState.commitSHA.prefix(7)))",
                action: nil
            ))
        }
    }

    /// "Push to a branch": publish the local commits as `<branch>-iphone-<date>`.
    func pushRejectedCommitsToNewBranch(repoID: UUID) async {
        guard let state = pushRejectionByRepo[repoID], let repo = repo(id: repoID) else { return }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmm"
        let name = "\(state.branch)-iphone-\(formatter.string(from: Date()))"
        pushRejectionByRepo[repoID]?.isResolving = true
        isSyncing = true
        syncingRepoID = repoID
        syncProgress = String(localized: "Pushing to \(name)…")
        beginSyncActivity(repoID: repoID, kind: .push)
        defer { isSyncing = false; syncingRepoID = nil }
        let url = vaultURL(for: repoID)
        let credentials = authPayload(for: repo)
        do {
            let serialized = try serializedRepository(repoID: repoID)
            try await runCancellableThrowing {
                try await serialized.withLease { _ in
                    try await LocalGitService(localURL: url).pushHeadToNewBranch(name: name, pat: credentials)
                }
            }
            finishSyncActivity(repoID: repoID, success: true)
            pushRejectionByRepo[repoID] = nil
            await loadBranches(repoID: repoID)
            showToast(TideToastItem(
                kind: .pushed,
                title: String(localized: "Pushed to \(name)"),
                detail: "\(repo.displayName) · \(remoteHostName(for: repo))",
                action: nil
            ))
        } catch {
            finishSyncActivity(repoID: repoID, success: false)
            pushRejectionByRepo[repoID]?.isResolving = false
            if !(error is CancellationError) {
                lastError = error.localizedDescription
                showError = true
            }
        }
    }

    func dismissPushRejection(repoID: UUID) {
        pushRejectionByRepo[repoID] = nil
    }

    func incomingPatch(repoID: UUID, path: String?) async -> String {
        let url = vaultURL(for: repoID)
        return (try? await LocalGitService(localURL: url).incomingPatch(path: path)) ?? ""
    }

    // MARK: - Offline

    private func startConnectivityMonitor() {
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { path in
            let online = path.status == .satisfied
            Task { @MainActor in self.connectivityChanged(online: online) }
        }
        monitor.start(queue: DispatchQueue(label: "com.bontecou.Sync-md.connectivity"))
        syncStateRuntime.pathMonitor = monitor
    }

    private func connectivityChanged(online: Bool) {
        let wasOffline = isOffline
        if online { lastOnlineAt = Date() }
        isOffline = !online
        if wasOffline && online { replayOfflineQueue() }
    }

    /// Records that a vault wants to sync once the connection returns.
    func enqueueOffline(repoID: UUID, operation: OfflineQueuedOperation) {
        let existing = offlineQueue[repoID]
        let merged: OfflineQueuedOperation
        switch (existing, operation) {
        case (nil, let op): merged = op
        case (.some(let old), let new) where old == new: merged = old
        default: merged = .sync
        }
        offlineQueue[repoID] = merged
        persistSyncStates()
        scheduleWidgetSnapshot()
        if existing == nil, let repo = repo(id: repoID), UIApplication.shared.applicationState == .active {
            showToast(TideToastItem(
                kind: .info,
                title: String(localized: "You're offline"),
                detail: String(localized: "\(repo.displayName) · syncs when you're back online"),
                action: nil
            ))
        }
    }

    /// Guard used at the top of network operations. Returns true when the
    /// operation was queued instead of attempted.
    func queueIfOffline(repoID: UUID, operation: OfflineQueuedOperation) -> Bool {
        guard isOffline, !isDemoMode else { return false }
        enqueueOffline(repoID: repoID, operation: operation)
        return true
    }

    /// Offline "Commit & push": commit locally now, push when back online.
    /// `stageEverything: false` commits only what is already staged (the
    /// commit sheet stages exactly the files the user kept ticked).
    func commitOfflineAndQueue(repoID: UUID, message: String, stageEverything: Bool = true) async -> Bool {
        guard let repo = repo(id: repoID) else { return false }
        let commitMessage = message.isEmpty ? String(localized: "Update from GitSync.md") : message
        do {
            let serialized = try serializedRepository(repoID: repoID)
            let sha = try await withCheckoutMutation(repoID: repoID) {
                try await serialized.withLease { repository in
                    if stageEverything { try await repository.stageAll() }
                    return try await repository.commitLocal(
                        message: commitMessage,
                        authorName: repo.authorName,
                        authorEmail: repo.authorEmail
                    )
                }
            }
            if let index = repoIndex(id: repoID) {
                repos[index].gitState.commitSHA = sha
                _ = saveRepos()
            }
            clearCommitHistoryCache(for: repoID)
            enqueueOffline(repoID: repoID, operation: .push)
            detectChanges(repoID: repoID)
            await loadBranches(repoID: repoID)
            showToast(TideToastItem(
                kind: .committed,
                title: String(localized: "Committed · syncs when online"),
                detail: "\(repo.displayName) · \(String(sha.prefix(7)))",
                action: .undoCommit(repoID: repoID, sha: sha)
            ))
            return true
        } catch LocalGitError.noChanges {
            enqueueOffline(repoID: repoID, operation: .push)
            return true
        } catch {
            lastError = error.localizedDescription
            showError = true
            return false
        }
    }

    private func replayOfflineQueue() {
        guard !offlineQueue.isEmpty, syncStateRuntime.replayTask == nil else { return }
        syncStateRuntime.replayTask = Task { @MainActor [weak self] in
            // Let the new path settle (DNS, captive portals) before Git work.
            try? await Task.sleep(for: .seconds(2))
            guard let self else { return }
            defer { self.syncStateRuntime.replayTask = nil }
            let queue = self.offlineQueue
            for (repoID, operation) in queue {
                guard !self.isOffline else { return }
                guard self.repo(id: repoID)?.isCloned == true else {
                    self.offlineQueue[repoID] = nil
                    continue
                }
                self.offlineQueue[repoID] = nil
                self.persistSyncStates()
                switch operation {
                case .push:
                    if (self.changeCounts[repoID] ?? 0) > 0 {
                        _ = await self.pushOnly(repoID: repoID)
                    } else {
                        _ = await self.pushCurrentBranch(repoID: repoID)
                    }
                case .pull:
                    _ = await self.pullOnly(repoID: repoID, showsProgressDelay: false)
                case .sync:
                    _ = await self.syncRepository(repoID: repoID)
                }
            }
            self.scheduleWidgetSnapshot()
        }
    }

    // MARK: - Toasts

    func showToast(_ toast: TideToastItem) {
        currentToast = toast
        syncStateRuntime.toastDismissTask?.cancel()
        let id = toast.id
        let seconds: Double = toast.kind == .error ? 8 : 5
        syncStateRuntime.toastDismissTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled, self?.currentToast?.id == id else { return }
            self?.currentToast = nil
        }
    }

    func dismissToast() {
        syncStateRuntime.toastDismissTask?.cancel()
        currentToast = nil
    }

    /// Undo on an unpushed commit is a soft reset (changes kept). Undo on a
    /// single pushed commit pushes a revert commit instead — published history
    /// is never rewritten, since a force-push could discard someone else's work.
    func performToastAction(_ action: TideToastItem.Action) async {
        dismissToast()
        switch action {
        case .undoCommit(let repoID, let sha):
            do {
                let serialized = try serializedRepository(repoID: repoID)
                let url = vaultURL(for: repoID)
                try await withCheckoutMutation(repoID: repoID) {
                    try await serialized.withLease { _ in
                        try await LocalGitService(localURL: url).undoUnpushedCommit(expectedHeadSHA: sha)
                    }
                }
                if let info = try? await serialized.repoInfo(), let index = repoIndex(id: repoID) {
                    repos[index].gitState.commitSHA = info.commitSHA
                    _ = saveRepos()
                }
                if offlineQueue[repoID] == .push, currentCommitsAhead(repoID: repoID) <= 1 {
                    offlineQueue[repoID] = nil
                    persistSyncStates()
                }
                clearCommitHistoryCache(for: repoID)
                detectChanges(repoID: repoID)
                await loadBranches(repoID: repoID)
                showToast(TideToastItem(kind: .info, title: String(localized: "Commit undone"),
                                        detail: String(localized: "Your changes are still here."), action: nil))
            } catch {
                lastError = error.localizedDescription
                showError = true
            }
        case .undoPush(let repoID, let sha):
            let before = repo(id: repoID)?.gitState.commitSHA
            await revertCommit(repoID: repoID, oid: sha)
            // revertCommit reports its own errors and conflicts; push only a clean revert.
            guard let after = repo(id: repoID)?.gitState.commitSHA, after != before,
                  conflictSessionByRepo[repoID]?.isActive != true else { return }
            if await pushCurrentBranch(repoID: repoID) {
                showToast(TideToastItem(kind: .info, title: String(localized: "Push undone"),
                                        detail: String(localized: "A revert commit was pushed."), action: nil))
            }
        case .view(let repoID):
            vaultNavigationRequest = repoID
        case .details(let message):
            lastError = message
            showError = true
        }
    }

    // MARK: - Foreground feedback for completed operations

    /// Called by the sync paths after a pull finished.
    func didFinishPull(repoID: UUID, result: RepositoryPullResult, previousSHA: String) {
        switch result {
        case .updated(_, let newSHA), .updatedWithAttention(_, let newSHA, _):
            didSyncSuccessfully(repoID: repoID)
            guard let repo = repo(id: repoID) else { return }
            let url = vaultURL(for: repoID)
            Task { @MainActor [weak self] in
                let summary = await LocalGitService(localURL: url).commitRange(from: previousSHA, to: newSHA)
                let count = max(summary?.count ?? 1, 1)
                let title = count == 1 ? String(localized: "Pulled 1 change") : String(localized: "Pulled \(count) changes")
                var detail = repo.displayName
                if let author = summary?.newestAuthor, !author.isEmpty { detail += " · " + String(localized: "from \(author)") }
                self?.showToast(TideToastItem(kind: .pulled, title: title, detail: detail, action: nil))
            }
        case .upToDate, .blockedByLocalChanges, .diverged, .remoteBranchMissing:
            didSyncSuccessfully(repoID: repoID)
        default:
            break
        }
    }

    /// Called by the sync paths after a push published commits.
    func didFinishPush(repoID: UUID, commitSHA: String, commits: Int) {
        didSyncSuccessfully(repoID: repoID)
        guard let repo = repo(id: repoID) else { return }
        let count = max(commits, 1)
        showToast(TideToastItem(
            kind: .pushed,
            title: count == 1 ? String(localized: "Pushed 1 commit") : String(localized: "Pushed \(count) commits"),
            detail: "\(repo.displayName) · \(String(commitSHA.prefix(7)))",
            action: count == 1 ? .undoPush(repoID: repoID, sha: commitSHA) : .view(repoID: repoID)
        ))
    }

    /// Any successful network round trip clears stale blocking states.
    func didSyncSuccessfully(repoID: UUID) {
        if authExpiredRepoIDs.contains(repoID) { clearAuthExpired() }
        if offlineQueue[repoID] != nil {
            offlineQueue[repoID] = nil
            persistSyncStates()
        }
        if let index = backgroundStoppedRepoIDs.firstIndex(of: repoID) {
            backgroundStoppedRepoIDs.remove(at: index)
            persistSyncStates()
        }
    }

    // MARK: - Background runs (banners + notifications)

    /// Hook from `recordAssist`, before the repo's stored SHA is replaced.
    func recordBackgroundRun(result: RepositoryReconciliationResult?, health: RepoAssistHealth, repoID: UUID) {
        guard let repo = repo(id: repoID) else { return }
        let previousSHA = repo.gitState.commitSHA
        let appIsActive = UIApplication.shared.applicationState == .active

        if health.kind == .attention || health.kind == .failed,
           let attention = health.attention, [.diverged, .localChanges, .unpushedCommit, .wrongBranch].contains(attention) {
            if !backgroundStoppedRepoIDs.contains(repoID) { backgroundStoppedRepoIDs.append(repoID) }
            backgroundStopReasons[repoID] = attention
            persistSyncStates()
        } else if result?.outcome == .upToDate || result?.didTransferData == true {
            didSyncSuccessfully(repoID: repoID)
        }
        if result?.outcome == .authenticationOrTrustRequired, isGitHubTokenVault(repo),
           Self.isAuthenticationFailure(result?.message ?? "") {
            markAuthExpired(repoIDs: [repoID])
        }

        guard let result, result.didTransferData, !appIsActive else { return }
        var summary = backgroundRunSummary ?? BackgroundRunSummary()
        if summary.dismissed { summary = BackgroundRunSummary() }
        summary.vaultIDs.insert(repoID)
        summary.lastRunAt = Date()
        if result.push?.didPush == true { summary.pushedRuns += 1 }
        backgroundRunSummary = summary
        persistSyncStates()

        let newSHA: String?
        switch result.pull {
        case .updated(_, let sha), .updatedWithAttention(_, let sha, _): newSHA = sha
        default: newSHA = nil
        }
        guard let newSHA, newSHA != previousSHA else { return }
        let url = vaultURL(for: repoID)
        let name = repo.displayName
        Task { @MainActor [weak self] in
            let range = await LocalGitService(localURL: url).commitRange(from: previousSHA, to: newSHA)
            let count = max(range?.count ?? 1, 1)
            if var summary = self?.backgroundRunSummary {
                summary.pulledCommits += count
                self?.backgroundRunSummary = summary
                self?.persistSyncStates()
            }
            await Self.postBackgroundPullNotification(
                repoID: repoID, vaultName: name, commits: count, author: range?.newestAuthor
            )
        }
    }

    private static func postBackgroundPullNotification(repoID: UUID, vaultName: String, commits: Int, author: String?) async {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "\(vaultName) was updated")
        let from = author.map { String(localized: " from \($0)") } ?? ""
        content.body = commits == 1
            ? String(localized: "1 commit\(from) pulled in the background. Tap to see what changed.")
            : String(localized: "\(commits) commits\(from) pulled in the background. Tap to see what changed.")
        content.threadIdentifier = repoID.uuidString
        content.userInfo = ["vaultID": repoID.uuidString]
        let request = UNNotificationRequest(identifier: "background-pull-\(repoID.uuidString)", content: content, trigger: nil)
        try? await center.add(request)
    }

    func requestNotificationAuthorizationIfNeeded() {
        Task {
            let center = UNUserNotificationCenter.current()
            let settings = await center.notificationSettings()
            guard settings.authorizationStatus == .notDetermined else { return }
            _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge])
        }
    }

    func dismissBackgroundRunSummary() {
        backgroundRunSummary?.dismissed = true
        persistSyncStates()
    }

    func dismissBackgroundStopped(repoID: UUID) {
        backgroundStoppedRepoIDs.removeAll { $0 == repoID }
        persistSyncStates()
    }

    // MARK: - Background Sync control

    private func applyPendingBackgroundSyncToggle() {
        guard let pending = SyncStatusSnapshotStore.pendingBackgroundSyncToggle else { return }
        SyncStatusSnapshotStore.pendingBackgroundSyncToggle = nil
        guard FeatureFlags.gitSyncAssistEnabled else { return }
        Task { await setBackgroundSync(enabled: pending) }
    }

    func setBackgroundSync(enabled: Bool) async {
        // The Control Center control exists in every build; it must not
        // switch Background Sync on in builds where the feature is off.
        guard FeatureFlags.gitSyncAssistEnabled,
              let runtime = syncStateRuntime.premiumRuntime else { return }
        await runtime.setAutomaticallySyncAllRepositories(enabled)
        if enabled { requestNotificationAuthorizationIfNeeded() }
        scheduleWidgetSnapshot()
    }

    // MARK: - Widget snapshot

    private func observeStatusForWidgets() {
        withObservationTracking {
            _ = repos
            _ = changeCounts
            _ = syncStateByRepo
            _ = isSyncing
            _ = syncingRepoID
            _ = authExpiredRepoIDs
            _ = offlineQueue
            _ = backgroundSyncFlightCounts
            if let runtime = syncStateRuntime.premiumRuntime {
                _ = runtime.automaticallySyncAllRepositories
                _ = runtime.automaticallyPullRemoteChanges
                _ = runtime.automaticallyPushLocalChanges
            }
        } onChange: {
            Task { @MainActor in
                self.scheduleWidgetSnapshot()
                self.observeStatusForWidgets()
                if self.syncStateRuntime.premiumRuntime?.automaticallySyncAllRepositories == true {
                    self.requestNotificationAuthorizationIfNeeded()
                }
            }
        }
        scheduleWidgetSnapshot()
    }

    func scheduleWidgetSnapshot() {
        guard syncStateRuntime.started else { return }
        syncStateRuntime.snapshotTask?.cancel()
        syncStateRuntime.snapshotTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(800))
            guard !Task.isCancelled, let self else { return }
            await self.writeWidgetSnapshot()
        }
    }

    private func writeWidgetSnapshot() async {
        var vaults: [VaultStatusSnapshot] = []
        for repo in visibleRepos {
            var ahead = 0
            var behind = 0
            if repo.isCloned, !isDemoMode,
               let counts = await LocalGitService(localURL: vaultURL(for: repo.id)).localAheadBehind() {
                ahead = counts.ahead
                behind = counts.behind
            }
            let changes = changeCounts[repo.id] ?? 0
            let syncing = (isSyncing && syncingRepoID == repo.id) || (backgroundSyncFlightCounts[repo.id] ?? 0) > 0
            let state: VaultStatusSnapshot.State
            if !repo.isCloned { state = .notCloned }
            else if syncing { state = .syncing }
            else if authExpiredRepoIDs.contains(repo.id) { state = .paused }
            else if ahead > 0 && behind > 0 { state = .diverged }
            else if behind > 0 { state = .behind }
            else if ahead > 0 || changes > 0 { state = .ahead }
            else {
                switch syncStateByRepo[repo.id] ?? .unknown {
                case .diverged: state = .diverged
                case .behind: state = .behind
                case .ahead: state = .ahead
                case .upToDate, .unknown: state = .inSync
                }
            }
            let last = repo.gitState.lastSyncDate
            vaults.append(VaultStatusSnapshot(
                id: repo.id,
                name: repo.displayName,
                owner: repo.ownerName,
                branch: repo.gitState.branch.isEmpty ? repo.branch : repo.gitState.branch,
                ahead: ahead,
                behind: behind,
                changes: changes,
                state: state,
                lastSync: last == .distantPast ? nil : last
            ))
        }
        let runtime = syncStateRuntime.premiumRuntime
        let background = BackgroundSyncSnapshot(
            available: FeatureFlags.gitSyncAssistEnabled,
            enabled: runtime?.automaticallySyncAllRepositories ?? false,
            pull: runtime?.automaticallyPullRemoteChanges ?? false,
            push: runtime?.automaticallyPushLocalChanges ?? false
        )
        let snapshot = SyncStatusSnapshot(vaults: vaults, updatedAt: Date(), backgroundSync: background)
        if SyncStatusSnapshotStore.write(snapshot) {
            WidgetCenter.shared.reloadAllTimelines()
            if #available(iOS 18.0, *) {
                ControlCenter.shared.reloadAllControls()
            }
        }
    }
}

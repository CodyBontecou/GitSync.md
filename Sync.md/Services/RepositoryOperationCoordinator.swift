import Foundation

/// Process-wide, cancellation-aware serialization for repository working copies.
/// Every call acquires the path lease. Composite workflows must use the unwrapped
/// repository supplied by `SerializedGitRepository.withLease`.
actor RepositoryOperationCoordinator {
    static let shared = RepositoryOperationCoordinator()

    private struct Waiter {
        let id: UUID
        let continuation: CheckedContinuation<Void, Error>
    }

    private var activeKeys: Set<String> = []
    private var waiters: [String: [Waiter]] = [:]

    func withRepository<T: Sendable>(
        at url: URL,
        operation: @Sendable () async throws -> T
    ) async throws -> T {
        let key = Self.canonicalKey(for: url)
        try await acquire(key)
        do {
            try Task.checkCancellation()
            let result = try await operation()
            release(key)
            return result
        } catch {
            release(key)
            throw error
        }
    }

    nonisolated static func canonicalKey(for url: URL) -> String {
        url.standardizedFileURL.resolvingSymlinksInPath().path
    }

    private func acquire(_ key: String) async throws {
        try Task.checkCancellation()
        if activeKeys.insert(key).inserted { return }

        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                if Task.isCancelled {
                    continuation.resume(throwing: CancellationError())
                } else {
                    waiters[key, default: []].append(Waiter(id: id, continuation: continuation))
                }
            }
        } onCancel: {
            Task { await self.cancelWaiter(id: id, key: key) }
        }
    }

    private func cancelWaiter(id: UUID, key: String) {
        guard var queued = waiters[key],
              let index = queued.firstIndex(where: { $0.id == id }) else { return }
        let waiter = queued.remove(at: index)
        waiters[key] = queued.isEmpty ? nil : queued
        waiter.continuation.resume(throwing: CancellationError())
    }

    private func release(_ key: String) {
        guard var queued = waiters[key], !queued.isEmpty else {
            activeKeys.remove(key)
            waiters.removeValue(forKey: key)
            return
        }
        let next = queued.removeFirst()
        waiters[key] = queued.isEmpty ? nil : queued
        next.continuation.resume()
    }
}

/// Decorates a repository implementation so independent callers share the same
/// process-wide serialization boundary. Operations do not call back through this
/// decorator, so nested AppState workflows cannot deadlock by re-acquiring a lock.
final class SerializedGitRepository: GitRepositoryProtocol, @unchecked Sendable {
    private let base: any GitRepositoryProtocol
    private let localURL: URL
    private let coordinator: RepositoryOperationCoordinator

    init(
        base: any GitRepositoryProtocol,
        localURL: URL,
        coordinator: RepositoryOperationCoordinator = .shared
    ) {
        self.base = base
        self.localURL = localURL
        self.coordinator = coordinator
    }

    var hasGitDirectory: Bool { base.hasGitDirectory }

    func withLease<T: Sendable>(
        _ operation: @escaping @Sendable (any GitRepositoryProtocol) async throws -> T
    ) async throws -> T {
        try await coordinator.withRepository(at: localURL) { [base] in
            try await operation(base)
        }
    }

    private func run<T: Sendable>(
        _ operation: @escaping @Sendable (any GitRepositoryProtocol) async throws -> T
    ) async throws -> T {
        try await coordinator.withRepository(at: localURL) { [base] in
            try await operation(base)
        }
    }

    func clone(remoteURL: String, pat: String) async throws -> LocalCloneResult {
        try await run { try await $0.clone(remoteURL: remoteURL, pat: pat) }
    }

    func setRemoteURL(name: String, url: String) async throws {
        try await run { try await $0.setRemoteURL(name: name, url: url) }
    }

    func pullPlan(pat: String) async throws -> PullPlan {
        try await run { try await $0.pullPlan(pat: pat) }
    }

    func pull(pat: String) async throws -> LocalPullResult {
        try await run { try await $0.pull(pat: pat) }
    }

    func executePullOnly(pat: String, expectedBranch: String?) async throws -> PullExecutionResult {
        try await run { try await $0.executePullOnly(pat: pat, expectedBranch: expectedBranch) }
    }

    func pullFastForward(branch: String, pat: String) async throws -> LocalPullResult {
        try await run { try await $0.pullFastForward(branch: branch, pat: pat) }
    }

    func pullRebase(branch: String, pat: String, authorName: String, authorEmail: String) async throws -> LocalPullResult {
        try await run { try await $0.pullRebase(branch: branch, pat: pat, authorName: authorName, authorEmail: authorEmail) }
    }

    func unifiedDiff(path: String?) async throws -> UnifiedDiffResult {
        try await run { try await $0.unifiedDiff(path: path) }
    }

    func listBranches() async throws -> BranchInventory {
        try await run { try await $0.listBranches() }
    }

    func createBranch(name: String) async throws {
        try await run { try await $0.createBranch(name: name) }
    }

    func switchBranch(name: String) async throws {
        try await run { try await $0.switchBranch(name: name) }
    }

    func deleteBranch(name: String) async throws {
        try await run { try await $0.deleteBranch(name: name) }
    }

    func mergeBranch(name: String, authorName: String, authorEmail: String) async throws -> MergeResult {
        try await run { try await $0.mergeBranch(name: name, authorName: authorName, authorEmail: authorEmail) }
    }

    func pushCurrentBranch(pat: String) async throws {
        try await run { try await $0.pushCurrentBranch(pat: pat) }
    }

    func revertCommit(oid: String, message: String, authorName: String, authorEmail: String) async throws -> RevertResult {
        try await run { try await $0.revertCommit(oid: oid, message: message, authorName: authorName, authorEmail: authorEmail) }
    }

    func completeMerge(message: String, authorName: String, authorEmail: String) async throws -> MergeFinalizeResult {
        try await run { try await $0.completeMerge(message: message, authorName: authorName, authorEmail: authorEmail) }
    }

    func abortMerge() async throws { try await run { try await $0.abortMerge() } }

    func continueRebase(pat: String, authorName: String, authorEmail: String) async throws -> LocalPullResult {
        try await run { try await $0.continueRebase(pat: pat, authorName: authorName, authorEmail: authorEmail) }
    }

    func abortRebase() async throws { try await run { try await $0.abortRebase() } }

    func conflictSession() async throws -> ConflictSession {
        try await run { try await $0.conflictSession() }
    }

    func conflictDetail(path: String) async throws -> ConflictFileDetail {
        try await run { try await $0.conflictDetail(path: path) }
    }

    func resolveConflict(path: String, strategy: ConflictResolutionStrategy) async throws {
        try await run { try await $0.resolveConflict(path: path, strategy: strategy) }
    }

    func resolveConflictWithContent(path: String, content: Data, additionalPathsToRemove: [String]) async throws {
        try await run {
            try await $0.resolveConflictWithContent(
                path: path,
                content: content,
                additionalPathsToRemove: additionalPathsToRemove
            )
        }
    }

    func commitLocal(message: String, authorName: String, authorEmail: String) async throws -> String {
        try await run { try await $0.commitLocal(message: message, authorName: authorName, authorEmail: authorEmail) }
    }

    func lfsAutoTrackingCandidates(paths: [String]?) async throws -> [GitLFSAutoTrackingCandidate] {
        try await run { try await $0.lfsAutoTrackingCandidates(paths: paths) }
    }

    func stage(path: String, oldPath: String?) async throws {
        try await run { try await $0.stage(path: path, oldPath: oldPath) }
    }

    func stage(path: String, oldPath: String?, lfsAutoTrack: Bool) async throws {
        try await run { try await $0.stage(path: path, oldPath: oldPath, lfsAutoTrack: lfsAutoTrack) }
    }

    func stageAll() async throws { try await run { try await $0.stageAll() } }

    func stageAll(lfsAutoTrack: Bool) async throws {
        try await run { try await $0.stageAll(lfsAutoTrack: lfsAutoTrack) }
    }

    func unstage(path: String, oldPath: String?) async throws {
        try await run { try await $0.unstage(path: path, oldPath: oldPath) }
    }

    func discardChanges(path: String) async throws {
        try await run { try await $0.discardChanges(path: path) }
    }

    func discardAllChanges() async throws { try await run { try await $0.discardAllChanges() } }

    func commitAndPush(message: String, authorName: String, authorEmail: String, pat: String) async throws -> LocalPushResult {
        try await run {
            try await $0.commitAndPush(
                message: message,
                authorName: authorName,
                authorEmail: authorEmail,
                pat: pat
            )
        }
    }

    func listStashes() async throws -> [GitStashEntry] {
        try await run { try await $0.listStashes() }
    }

    func saveStash(message: String, authorName: String, authorEmail: String, includeUntracked: Bool) async throws -> GitStashEntry {
        try await run {
            try await $0.saveStash(
                message: message,
                authorName: authorName,
                authorEmail: authorEmail,
                includeUntracked: includeUntracked
            )
        }
    }

    func applyStash(index: Int, reinstateIndex: Bool) async throws -> StashApplyResult {
        try await run { try await $0.applyStash(index: index, reinstateIndex: reinstateIndex) }
    }

    func popStash(index: Int, reinstateIndex: Bool) async throws -> StashApplyResult {
        try await run { try await $0.popStash(index: index, reinstateIndex: reinstateIndex) }
    }

    func dropStash(index: Int) async throws { try await run { try await $0.dropStash(index: index) } }

    func listTags() async throws -> [GitTag] { try await run { try await $0.listTags() } }

    func createTag(name: String, targetOID: String?, message: String?, authorName: String, authorEmail: String) async throws -> GitTag {
        try await run {
            try await $0.createTag(
                name: name,
                targetOID: targetOID,
                message: message,
                authorName: authorName,
                authorEmail: authorEmail
            )
        }
    }

    func deleteTag(name: String) async throws { try await run { try await $0.deleteTag(name: name) } }

    func pushTag(name: String, pat: String) async throws {
        try await run { try await $0.pushTag(name: name, pat: pat) }
    }

    func fetchRemote(pat: String) async throws { try await run { try await $0.fetchRemote(pat: pat) } }

    func commitHistory(limit: Int, skip: Int) async throws -> [GitCommitSummary] {
        try await run { try await $0.commitHistory(limit: limit, skip: skip) }
    }

    func commitDetail(oid: String) async throws -> GitCommitDetail {
        try await run { try await $0.commitDetail(oid: oid) }
    }

    func repoInfo() async throws -> LocalRepoInfo { try await run { try await $0.repoInfo() } }
}

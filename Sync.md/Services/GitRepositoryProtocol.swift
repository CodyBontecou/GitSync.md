import Foundation

struct PullExecutionResult: Sendable {
    let plan: PullPlan
    let pullResult: LocalPullResult?
}

/// A lease captured by automatic push safety validation and enforced again against the
/// push server's live advertisement. Manual foreground pushes may omit it.
struct PushSafetyExpectation: Sendable, Equatable {
    let branch: String
    let remoteCommitSHA: String
    let remoteIdentity: GitRemoteIdentity

    init(branch: String, remoteCommitSHA: String, remoteIdentity: GitRemoteIdentity) {
        self.branch = branch
        self.remoteCommitSHA = remoteCommitSHA
        self.remoteIdentity = remoteIdentity
    }

    /// Convenience for remotes without a distinct pushurl.
    init(branch: String, remoteCommitSHA: String, remoteURL: String) {
        self.init(
            branch: branch,
            remoteCommitSHA: remoteCommitSHA,
            remoteIdentity: GitRemoteIdentity(fetchURL: remoteURL, pushURL: remoteURL)
        )
    }
}

protocol GitRepositoryProtocol: Sendable {
    var hasGitDirectory: Bool { get }

    func clone(remoteURL: String, pat: String) async throws -> LocalCloneResult
    /// Re-runs Git LFS hydration for the whole worktree (retry after an SSH
    /// host-key trust prompt unblocked hydration).
    func hydrateLFSObjects(pat: String) async throws -> GitLFSHydrateResult
    func setRemoteURL(name: String, url: String) async throws
    func pullPlan(pat: String) async throws -> PullPlan
    func pull(pat: String) async throws -> LocalPullResult
    /// Fetch, classify, and conditionally fast-forward under one repository operation.
    func executePullOnly(pat: String, expectedBranch: String?) async throws -> PullExecutionResult
    /// Apply a fast-forward after `pullPlan` has already fetched origin.
    func pullFastForward(branch: String, pat: String) async throws -> LocalPullResult
    /// Rebase local commits onto origin/<branch> after `pullPlan` has already fetched origin.
    func pullRebase(branch: String, pat: String, authorName: String, authorEmail: String) async throws -> LocalPullResult
    func unifiedDiff(path: String?) async throws -> UnifiedDiffResult
    func listBranches() async throws -> BranchInventory
    func createBranch(name: String) async throws
    func switchBranch(name: String) async throws
    func deleteBranch(name: String) async throws
    func mergeBranch(name: String, authorName: String, authorEmail: String) async throws -> MergeResult
    func pushCurrentBranch(
        pat: String,
        expectedBranch: String?,
        safetyExpectation: PushSafetyExpectation?
    ) async throws
    func revertCommit(oid: String, message: String, authorName: String, authorEmail: String) async throws -> RevertResult
    func completeMerge(message: String, authorName: String, authorEmail: String) async throws -> MergeFinalizeResult
    func abortMerge() async throws
    func continueRebase(pat: String, authorName: String, authorEmail: String) async throws -> LocalPullResult
    func abortRebase() async throws
    func conflictSession() async throws -> ConflictSession
    func conflictDetail(path: String) async throws -> ConflictFileDetail
    func resolveConflict(path: String, strategy: ConflictResolutionStrategy) async throws
    func resolveConflictWithContent(
        path: String,
        content: Data,
        additionalPathsToRemove: [String]
    ) async throws
    func commitLocal(message: String, authorName: String, authorEmail: String) async throws -> String
    func lfsAutoTrackingCandidates(paths: [String]?) async throws -> [GitLFSAutoTrackingCandidate]
    func stage(path: String, oldPath: String?) async throws
    func stage(path: String, oldPath: String?, lfsAutoTrack: Bool) async throws
    func stageAll() async throws
    func stageAll(lfsAutoTrack: Bool) async throws
    func unstage(path: String, oldPath: String?) async throws
    func discardChanges(path: String) async throws
    func discardAllChanges() async throws
    func commitAndPush(
        message: String,
        authorName: String,
        authorEmail: String,
        pat: String,
        expectedBranch: String?,
        safetyExpectation: PushSafetyExpectation?
    ) async throws -> LocalPushResult
    func listStashes() async throws -> [GitStashEntry]
    func saveStash(message: String, authorName: String, authorEmail: String, includeUntracked: Bool) async throws -> GitStashEntry
    func applyStash(index: Int, reinstateIndex: Bool) async throws -> StashApplyResult
    func popStash(index: Int, reinstateIndex: Bool) async throws -> StashApplyResult
    func dropStash(index: Int) async throws
    func listTags() async throws -> [GitTag]
    func createTag(name: String, targetOID: String?, message: String?, authorName: String, authorEmail: String) async throws -> GitTag
    func deleteTag(name: String) async throws
    func pushTag(name: String, pat: String) async throws
    func fetchRemote(pat: String) async throws
    func commitHistory(limit: Int, skip: Int) async throws -> [GitCommitSummary]
    func commitDetail(oid: String) async throws -> GitCommitDetail
    func repoInfo() async throws -> LocalRepoInfo
}

extension GitRepositoryProtocol {
    func pushCurrentBranch(pat: String) async throws {
        try await pushCurrentBranch(pat: pat, expectedBranch: nil, safetyExpectation: nil)
    }

    func pushCurrentBranch(pat: String, expectedBranch: String?) async throws {
        try await pushCurrentBranch(
            pat: pat,
            expectedBranch: expectedBranch,
            safetyExpectation: nil
        )
    }

    func commitAndPush(
        message: String,
        authorName: String,
        authorEmail: String,
        pat: String
    ) async throws -> LocalPushResult {
        try await commitAndPush(
            message: message,
            authorName: authorName,
            authorEmail: authorEmail,
            pat: pat,
            expectedBranch: nil,
            safetyExpectation: nil
        )
    }

    func commitAndPush(
        message: String,
        authorName: String,
        authorEmail: String,
        pat: String,
        expectedBranch: String?
    ) async throws -> LocalPushResult {
        try await commitAndPush(
            message: message,
            authorName: authorName,
            authorEmail: authorEmail,
            pat: pat,
            expectedBranch: expectedBranch,
            safetyExpectation: nil
        )
    }
}

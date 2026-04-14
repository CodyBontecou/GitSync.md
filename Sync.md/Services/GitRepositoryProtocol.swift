import Foundation

protocol GitRepositoryProtocol: Sendable {
    var hasGitDirectory: Bool { get }

    func clone(remoteURL: String, pat: String) async throws -> LocalCloneResult
    func pullPlan(pat: String) async throws -> PullPlan
    func pull(pat: String) async throws -> LocalPullResult
    func unifiedDiff(path: String?) async throws -> UnifiedDiffResult
    func listBranches() async throws -> BranchInventory
    func createBranch(name: String) async throws
    func switchBranch(name: String) async throws
    func deleteBranch(name: String) async throws
    func mergeBranch(name: String, authorName: String, authorEmail: String) async throws -> MergeResult
    func pushCurrentBranch(pat: String) async throws
    func revertCommit(oid: String, message: String, authorName: String, authorEmail: String) async throws -> RevertResult
    func completeMerge(message: String, authorName: String, authorEmail: String) async throws -> MergeFinalizeResult
    func abortMerge() async throws
    func conflictSession() async throws -> ConflictSession
    func resolveConflict(path: String, strategy: ConflictResolutionStrategy) async throws
    func stage(path: String) async throws
    func unstage(path: String) async throws
    func discardChanges(path: String) async throws
    func discardAllChanges() async throws
    func commitAndPush(
        message: String,
        authorName: String,
        authorEmail: String,
        pat: String
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
    func commitHistory(limit: Int, skip: Int) async throws -> [GitCommitSummary]
    func commitDetail(oid: String) async throws -> GitCommitDetail
    func repoInfo() async throws -> LocalRepoInfo
}

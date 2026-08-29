import Foundation

enum RepositoryPullResult: Sendable, Equatable {
    case updated(branch: String, commitSHA: String)
    case updatedWithAttention(branch: String, commitSHA: String, attention: PullPostUpdateAttention)
    case upToDate(branch: String, commitSHA: String)
    case blockedByLocalChanges(branch: String)
    case diverged(branch: String, aheadBy: Int, behindBy: Int)
    case remoteBranchMissing(branch: String)
    case authenticationOrTrustRequired(message: String, trustError: GitLFSSSHHostKeyTrustError?)
    case wrongBranch(expected: String, actual: String)
    case unavailable(message: String)
    case failed(message: String)

    var completedWithoutAttention: Bool {
        switch self {
        case .updated, .upToDate: true
        default: false
        }
    }
}

/// Pure, presentation-independent pull-only policy. Foreground UI adaptation
/// belongs in `AppState`; App Intents and future background triggers consume the
/// typed result directly. This never stages, commits, rebases, merges, or pushes.
struct RepositoryPullRunner: Sendable {
    func run(
        repository: any GitRepositoryProtocol,
        credentials: String,
        expectedBranch: String? = nil
    ) async -> RepositoryPullResult {
        do {
            // Fetch, classification, and optional fast-forward are returned as
            // one typed result from one serialized repository operation.
            let execution = try await repository.executePullOnly(pat: credentials, expectedBranch: expectedBranch)
            let plan = execution.plan
            switch plan.action {
            case .fastForward:
                guard let result = execution.pullResult else {
                    return .failed(message: String(localized: "Pull did not return a fast-forward result."))
                }
                if let attention = result.attention {
                    return .updatedWithAttention(
                        branch: plan.branch,
                        commitSHA: result.newCommitSHA,
                        attention: attention
                    )
                }
                return result.updated
                    ? .updated(branch: plan.branch, commitSHA: result.newCommitSHA)
                    : .upToDate(branch: plan.branch, commitSHA: result.newCommitSHA)
            case .upToDate:
                return .upToDate(branch: plan.branch, commitSHA: plan.localCommitSHA)
            case .blockedByLocalChanges:
                return .blockedByLocalChanges(branch: plan.branch)
            case .diverged:
                return .diverged(branch: plan.branch, aheadBy: plan.aheadBy, behindBy: plan.behindBy)
            case .remoteBranchMissing:
                return .remoteBranchMissing(branch: plan.branch)
            }
        } catch LocalGitError.sshHostKeyTrustRequired(let error) {
            return .authenticationOrTrustRequired(message: error.localizedDescription, trustError: error)
        } catch let error as LocalGitError {
            switch error {
            case .authenticationFailed:
                return .authenticationOrTrustRequired(message: error.localizedDescription, trustError: nil)
            case .pullBlockedByLocalChanges, .lfsHydrationBlockedByLocalChanges:
                return .blockedByLocalChanges(branch: expectedBranch ?? "HEAD")
            case .pullDiverged:
                // A second ancestry check immediately before checkout protects
                // against another process changing same-named refs after the
                // initial plan. Exact counts are unavailable at this boundary,
                // but the typed diverged outcome still prevents mutation.
                return .diverged(branch: expectedBranch ?? "HEAD", aheadBy: 0, behindBy: 0)
            case .pullRemoteBranchMissing(let branch):
                return .remoteBranchMissing(branch: branch)
            case .wrongBranch(let expected, let actual):
                return .wrongBranch(expected: expected, actual: actual)
            case .notCloned, .repositoryCorrupted:
                return .unavailable(message: error.localizedDescription)
            default:
                return .failed(message: error.localizedDescription)
            }
        } catch {
            return .failed(message: error.localizedDescription)
        }
    }
}

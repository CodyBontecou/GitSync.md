import Foundation

enum GitFileStatusKind: String, Codable, Sendable, CaseIterable {
    case added
    case modified
    case deleted
    case renamed
    case typeChanged
    case untracked
    case conflicted
}

struct GitStatusEntry: Identifiable, Codable, Sendable, Equatable {
    let path: String
    let indexStatus: GitFileStatusKind?
    let workTreeStatus: GitFileStatusKind?
    let oldPath: String?

    init(
        path: String,
        indexStatus: GitFileStatusKind?,
        workTreeStatus: GitFileStatusKind?,
        oldPath: String? = nil
    ) {
        self.path = path
        self.indexStatus = indexStatus
        self.workTreeStatus = workTreeStatus
        self.oldPath = oldPath
    }

    var id: String { path }

    var isConflicted: Bool {
        indexStatus == .conflicted || workTreeStatus == .conflicted
    }
}

enum RepoSyncState: String, Codable, Sendable {
    case upToDate
    case ahead
    case behind
    case diverged
    case unknown
}

enum PullPlanAction: String, Codable, Sendable {
    case upToDate
    case fastForward
    case blockedByLocalChanges
    case diverged
    case remoteBranchMissing
}

struct PullPlan: Codable, Sendable, Equatable {
    let action: PullPlanAction
    let branch: String
    let localCommitSHA: String
    let remoteCommitSHA: String
    let hasLocalChanges: Bool
    let aheadBy: Int
    let behindBy: Int
}

enum PullOutcomeKind: String, Codable, Sendable {
    case upToDate
    case fastForwarded
    case rebased
    case rebaseConflicts
    case blockedByLocalChanges
    case lfsHydrationBlocked
    case diverged
    case remoteBranchMissing
    case failed
}

struct PullOutcomeState: Codable, Sendable, Equatable {
    let kind: PullOutcomeKind
    let message: String
    let date: Date
}

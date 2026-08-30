import Foundation

/// Typed outcome of a headless stage-all + commit + push operation.
enum RepositoryPushResult: Sendable, Equatable {
    /// Local changes (or an already-ahead branch) were pushed successfully.
    case pushed(commitSHA: String)
    /// Nothing to commit and HEAD already matches origin.
    case noChanges
    /// Automatic push safety policy prevented publication.
    case blocked(message: String)
    /// A commit was created locally, but publication did not complete. The SHA
    /// is retained so retries and health UI never misreport the work as absent.
    case commitSavedNotPushed(commitSHA: String, message: String, trustError: GitLFSSSHHostKeyTrustError?)
    /// Credentials or SSH host-key trust must be resolved in the app first.
    case authenticationOrTrustRequired(message: String, trustError: GitLFSSSHHostKeyTrustError?)
    /// The operation failed before a new local commit was created.
    case failed(message: String)

    var finalLocalCommitSHA: String? {
        switch self {
        case .pushed(let sha), .commitSavedNotPushed(let sha, _, _): sha
        default: nil
        }
    }

    var didPush: Bool {
        if case .pushed = self { return true }
        return false
    }
}

/// Typed outcome of a composed foreground pull-then-push sync.
enum RepositorySyncOutcome: Sendable, Equatable {
    case synced
    case pushSkipped
    case blocked
    case authenticationOrTrustRequired(message: String, trustError: GitLFSSSHHostKeyTrustError?)
    case failed(message: String)
}

struct RepositorySyncResult: Sendable, Equatable {
    let outcome: RepositorySyncOutcome
    let pull: RepositoryPullResult?
    let push: RepositoryPushResult?
    let message: String
}

/// Composite result used by premium background reconciliation.
enum RepositoryReconciliationOutcome: Sendable, Equatable {
    case upToDate
    case pulled
    case pushed
    case pulledAndPushed
    case blocked
    case authenticationOrTrustRequired
    case failed
}

struct RepositoryReconciliationResult: Sendable, Equatable {
    let outcome: RepositoryReconciliationOutcome
    let pull: RepositoryPullResult?
    let push: RepositoryPushResult?
    let finalLocalCommitSHA: String?
    let message: String?

    var didTransferData: Bool {
        let didPull: Bool
        switch pull {
        case .updated, .updatedWithAttention: didPull = true
        default: didPull = false
        }
        return didPull || push?.didPush == true
    }

    var isFailure: Bool { outcome == .failed }

    /// Cancellation must not hide a pull, push, or locally saved commit that
    /// already completed before the task observed cancellation.
    var retainsCompletedWorkOnCancellation: Bool {
        switch pull {
        case .updated, .updatedWithAttention: return true
        default: break
        }
        switch push {
        case .pushed, .commitSavedNotPushed: return true
        default: return false
        }
    }

    static func pullOnly(_ pull: RepositoryPullResult) -> RepositoryReconciliationResult {
        let outcome: RepositoryReconciliationOutcome
        let message: String?
        switch pull {
        case .updated: outcome = .pulled; message = nil
        case .upToDate: outcome = .upToDate; message = nil
        case .authenticationOrTrustRequired(let value, _): outcome = .authenticationOrTrustRequired; message = value
        case .failed(let value): outcome = .failed; message = value
        case .updatedWithAttention(_, _, let attention): outcome = .blocked; message = attention.localizedDescription
        case .blockedByLocalChanges: outcome = .blocked; message = String(localized: "Local changes need attention.")
        case .diverged: outcome = .blocked; message = String(localized: "Local and remote history diverged.")
        case .remoteBranchMissing: outcome = .blocked; message = String(localized: "Remote branch is missing.")
        case .wrongBranch: outcome = .blocked; message = String(localized: "Selected branch is not currently checked out.")
        case .unavailable(let value): outcome = .blocked; message = value
        }
        return .init(outcome: outcome, pull: pull, push: nil, finalLocalCommitSHA: pull.newCommitSHA, message: message)
    }
}

/// Presentation-independent push policy. Every mutation runs inside one path
/// lease. A clean retry also checks for already-created commits ahead of origin,
/// closing the failed-push retry hole where HEAD advanced but the worktree is clean.
struct RepositoryPushRunner: Sendable {
    func run(
        serialized: SerializedGitRepository,
        repo: RepoConfig,
        credentials: String,
        message: String?
    ) async -> RepositoryPushResult {
        do {
            return try await serialized.withLease { repository in
                await self.runUnserialized(
                    repository: repository,
                    repo: repo,
                    credentials: credentials,
                    message: message,
                    validatePushPlan: nil
                )
            }
        } catch {
            return Self.map(error)
        }
    }

    /// Must be called while the caller owns the repository lease.
    func runUnserialized(
        repository: any GitRepositoryProtocol,
        repo: RepoConfig,
        credentials: String,
        message: String?,
        validatePushPlan: (@Sendable (PullPlan) async throws -> Void)?
    ) async -> RepositoryPushResult {
        guard repository.hasGitDirectory else {
            return .failed(message: LocalGitError.notCloned.localizedDescription)
        }
        let trimmed = (message ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let commitMessage = trimmed.isEmpty ? String(localized: "Update from GitSync.md") : trimmed
        let beforeSHA: String
        do { beforeSHA = try await repository.repoInfo().commitSHA }
        catch { return Self.map(error) }

        do {
            let conflict = try await repository.conflictSession()
            guard !conflict.isActive else {
                return .blocked(message: String(localized: "Resolve the active Git conflict before committing or pushing."))
            }
            do {
                try await stageAllLocalChanges(repository: repository)
            } catch LocalGitError.noChanges {
                return try await pushOutstandingCommitsIfNeeded(
                    repository: repository,
                    credentials: credentials,
                    configuredRemoteURL: repo.repoURL,
                    validatePushPlan: validatePushPlan
                )
            }
            try Task.checkCancellation()
            var expectedBranch: String?
            var safetyExpectation: PushSafetyExpectation?
            if let validatePushPlan {
                let plan = try await repository.pullPlan(pat: credentials)
                try await validatePushPlan(plan)
                guard !plan.remoteCommitSHA.isEmpty else {
                    throw LocalGitError.pullRemoteBranchMissing(plan.branch)
                }
                expectedBranch = plan.branch
                safetyExpectation = PushSafetyExpectation(
                    branch: plan.branch,
                    remoteCommitSHA: plan.remoteCommitSHA,
                    remoteIdentity: plan.remoteIdentity ?? GitRemoteIdentity(
                        fetchURL: repo.repoURL,
                        pushURL: repo.repoURL
                    )
                )
            }
            try Task.checkCancellation()

            // The remote fetch above is an editor-write window. Re-read status
            // after it and refuse to publish an older staged snapshot if any
            // newer worktree bytes appeared.
            let finalInfo = try await repository.repoInfo()
            let finalConflict = try await repository.conflictSession()
            guard finalInfo.statusEntries.contains(where: { $0.indexStatus != nil }),
                  !finalInfo.statusEntries.contains(where: { $0.workTreeStatus != nil }),
                  !finalInfo.statusEntries.contains(where: \.isConflicted),
                  !finalConflict.isActive else {
                throw LocalGitError.commitFailed(String(localized: "Could not stage all local file changes before push."))
            }
            try Task.checkCancellation()
            let result = try await repository.commitAndPush(
                message: commitMessage,
                authorName: repo.authorName,
                authorEmail: repo.authorEmail,
                pat: credentials,
                expectedBranch: expectedBranch,
                safetyExpectation: safetyExpectation
            )
            return .pushed(commitSHA: result.commitSHA)
        } catch {
            if let saved = error as? LocalCommitSavedNotPushedError {
                return .commitSavedNotPushed(
                    commitSHA: saved.commitSHA,
                    message: saved.localizedDescription,
                    trustError: saved.trustError
                )
            }
            let afterSHA = (try? await repository.repoInfo())?.commitSHA
            if let afterSHA, !afterSHA.isEmpty, afterSHA != beforeSHA {
                return .commitSavedNotPushed(
                    commitSHA: afterSHA,
                    message: error.localizedDescription,
                    trustError: Self.trustError(from: error)
                )
            }
            return Self.map(error)
        }
    }

    private func pushOutstandingCommitsIfNeeded(
        repository: any GitRepositoryProtocol,
        credentials: String,
        configuredRemoteURL: String,
        validatePushPlan: (@Sendable (PullPlan) async throws -> Void)?
    ) async throws -> RepositoryPushResult {
        try Task.checkCancellation()
        let plan = try await repository.pullPlan(pat: credentials)
        try await validatePushPlan?(plan)
        try Task.checkCancellation()
        if plan.aheadBy > 0 && plan.behindBy == 0 {
            let safetyExpectation: PushSafetyExpectation?
            if validatePushPlan != nil {
                guard !plan.remoteCommitSHA.isEmpty else {
                    throw LocalGitError.pullRemoteBranchMissing(plan.branch)
                }
                safetyExpectation = PushSafetyExpectation(
                    branch: plan.branch,
                    remoteCommitSHA: plan.remoteCommitSHA,
                    remoteIdentity: plan.remoteIdentity ?? GitRemoteIdentity(
                        fetchURL: configuredRemoteURL,
                        pushURL: configuredRemoteURL
                    )
                )
            } else {
                safetyExpectation = nil
            }
            try await repository.pushCurrentBranch(
                pat: credentials,
                expectedBranch: plan.branch,
                safetyExpectation: safetyExpectation
            )
            return .pushed(commitSHA: plan.localCommitSHA)
        }
        if plan.aheadBy > 0 && plan.behindBy > 0 {
            return .blocked(message: String(localized: "Local and remote history diverged."))
        }
        if plan.behindBy > 0 {
            return .blocked(message: String(localized: "Remote updates must be pulled before pushing."))
        }
        if plan.action == .remoteBranchMissing {
            return .blocked(message: String(localized: "Remote branch is missing."))
        }
        return .noChanges
    }

    private static func map(_ error: Error) -> RepositoryPushResult {
        if let local = error as? LocalGitError {
            switch local {
            case .authenticationFailed:
                return .authenticationOrTrustRequired(message: local.localizedDescription, trustError: nil)
            case .sshHostKeyTrustRequired(let trust):
                return .authenticationOrTrustRequired(message: local.localizedDescription, trustError: trust)
            case .pullBlockedByLocalChanges:
                return .blocked(message: String(localized: "Remote updates arrived while local changes were being prepared."))
            case .pullDiverged:
                return .blocked(message: String(localized: "Local and remote history diverged."))
            case .pullRemoteBranchMissing:
                return .blocked(message: local.localizedDescription)
            case .wrongBranch:
                return .blocked(message: local.localizedDescription)
            default:
                return .failed(message: local.localizedDescription)
            }
        }
        if error is CancellationError { return .failed(message: String(localized: "Cancelled")) }
        return .failed(message: error.localizedDescription)
    }

    private static func trustError(from error: Error) -> GitLFSSSHHostKeyTrustError? {
        if case LocalGitError.sshHostKeyTrustRequired(let trust) = error { return trust }
        return nil
    }

    /// Stages all local changes with a short settle window for delayed external
    /// editor rename/delete events. Cancellation is checked between every pass.
    private func stageAllLocalChanges(repository: any GitRepositoryProtocol) async throws {
        var sawAnyChanges = false
        for pass in 0..<8 {
            try Task.checkCancellation()
            let before = try await repository.repoInfo()
            if !before.statusEntries.isEmpty { sawAnyChanges = true }
            try await ensureNoActiveConflict(repository: repository)
            try await repository.stageAll()
            if pass < 7 { try await Task.sleep(for: .milliseconds(250)) }
        }

        var finalInfo = try await repository.repoInfo()
        let conflictAfterSettle = try await repository.conflictSession()
        if finalInfo.statusEntries.contains(where: { $0.isConflicted }) || conflictAfterSettle.isActive {
            throw LocalGitError.commitFailed(String(localized: "Resolve the active Git conflict before committing or pushing."))
        }

        if finalInfo.statusEntries.contains(where: { $0.workTreeStatus != nil }) {
            var seen = Set<String>()
            for entry in finalInfo.statusEntries where entry.workTreeStatus != nil {
                try Task.checkCancellation()
                guard entry.path != "<unknown>" else { continue }
                let key = "\(entry.path)\u{0}\(entry.oldPath ?? "")"
                guard seen.insert(key).inserted else { continue }
                try await ensureNoActiveConflict(repository: repository)
                try await repository.stage(path: entry.path, oldPath: entry.oldPath)
            }
            finalInfo = try await repository.repoInfo()
        }

        let hasStagedChanges = finalInfo.statusEntries.contains(where: { $0.indexStatus != nil })
        let hasRemainingWorktreeChanges = finalInfo.statusEntries.contains(where: { $0.workTreeStatus != nil })
        let finalConflictSession = try await repository.conflictSession()
        let hasConflict = finalInfo.statusEntries.contains(where: { $0.isConflicted }) || finalConflictSession.isActive
        if hasStagedChanges && !hasRemainingWorktreeChanges && !hasConflict { return }
        if sawAnyChanges || !finalInfo.statusEntries.isEmpty {
            throw LocalGitError.commitFailed(String(localized: "Could not stage all local file changes before push."))
        }
        throw LocalGitError.noChanges
    }

    private func ensureNoActiveConflict(repository: any GitRepositoryProtocol) async throws {
        guard !(try await repository.conflictSession()).isActive else {
            throw LocalGitError.commitFailed(
                String(localized: "Resolve the active Git conflict before committing or pushing.")
            )
        }
    }
}

/// Fail-closed automatic reconciliation. Pull and push are independently
/// consented, while the complete selected workflow owns one repository lease.
/// Push-only mode still fetches and validates remote state but never checks out
/// remote content. No automatic merge, rebase, branch switch, conflict
/// resolution, branch creation, or force push is reachable here.
struct RepositoryReconciliationRunner: Sendable {
    func run(
        serialized: SerializedGitRepository,
        repo: RepoConfig,
        credentials: String,
        expectedBranch: String?,
        allowsPull: Bool,
        allowsPush: Bool,
        message: String? = nil
    ) async -> RepositoryReconciliationResult {
        do {
            return try await serialized.withLease { repository in
            let pull: RepositoryPullResult?
            if allowsPull {
                let result = await RepositoryPullRunner().run(
                    repository: repository,
                    credentials: credentials,
                    expectedBranch: expectedBranch
                )
                pull = result
                guard allowsPush else { return .pullOnly(result) }
                guard result.completedWithoutAttention else { return .pullOnly(result) }
            } else {
                pull = nil
                guard allowsPush else {
                    return .init(
                        outcome: .upToDate,
                        pull: nil,
                        push: nil,
                        finalLocalCommitSHA: nil,
                        message: nil
                    )
                }
            }

            let push = await RepositoryPushRunner().runUnserialized(
                repository: repository,
                repo: repo,
                credentials: credentials,
                message: message
            ) { plan in
                if let expectedBranch, plan.branch != expectedBranch {
                    throw LocalGitError.wrongBranch(expected: expectedBranch, actual: plan.branch)
                }
                if plan.aheadBy > 0 && plan.behindBy > 0 { throw LocalGitError.pullDiverged }
                if plan.behindBy > 0 { throw LocalGitError.pullBlockedByLocalChanges }
                if plan.action == .remoteBranchMissing { throw LocalGitError.pullRemoteBranchMissing(plan.branch) }
            }

            let pulled: Bool
            if case .updated? = pull { pulled = true } else { pulled = false }
            let finalSHA = push.finalLocalCommitSHA ?? pull?.newCommitSHA
            switch push {
            case .pushed:
                return .init(
                    outcome: pulled ? .pulledAndPushed : .pushed,
                    pull: pull, push: push, finalLocalCommitSHA: finalSHA, message: nil
                )
            case .noChanges:
                return .init(
                    outcome: pulled ? .pulled : .upToDate,
                    pull: pull, push: push, finalLocalCommitSHA: finalSHA, message: nil
                )
            case .blocked(let message):
                return .init(outcome: .blocked, pull: pull, push: push, finalLocalCommitSHA: finalSHA, message: message)
            case .commitSavedNotPushed(_, let message, _):
                return .init(outcome: .blocked, pull: pull, push: push, finalLocalCommitSHA: finalSHA, message: message)
            case .authenticationOrTrustRequired(let message, _):
                return .init(outcome: .authenticationOrTrustRequired, pull: pull, push: push, finalLocalCommitSHA: finalSHA, message: message)
            case .failed(let message):
                return .init(outcome: .failed, pull: pull, push: push, finalLocalCommitSHA: finalSHA, message: message)
            }
            }
        } catch {
            return .init(
                outcome: .failed,
                pull: nil,
                push: .failed(message: error.localizedDescription),
                finalLocalCommitSHA: nil,
                message: error.localizedDescription
            )
        }
    }
}

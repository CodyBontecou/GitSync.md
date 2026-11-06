import Foundation

/// "Auto-commit when I pause": the watching panel (Split View beside a notes
/// editor) commits and pushes every change once edits stop for a while.
enum VaultWatchSettings {
    /// Quiet time before an automatic commit.
    static let pauseInterval: TimeInterval = 120

    private static func autoCommitKey(_ repoID: UUID) -> String {
        "vaultWatch.autoCommit.\(repoID.uuidString)"
    }

    static func autoCommitEnabled(repoID: UUID, defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: autoCommitKey(repoID))
    }

    static func setAutoCommitEnabled(_ enabled: Bool, repoID: UUID, defaults: UserDefaults = .standard) {
        defaults.set(enabled, forKey: autoCommitKey(repoID))
    }

    /// True once the vault has changes and nothing moved for `pauseInterval`.
    static func shouldAutoCommit(
        changeCount: Int,
        lastActivity: Date?,
        lastAutoCommittedActivity: Date?,
        now: Date = Date()
    ) -> Bool {
        guard changeCount > 0, let lastActivity else { return false }
        if let lastAutoCommittedActivity, lastAutoCommittedActivity >= lastActivity { return false }
        return now.timeIntervalSince(lastActivity) >= pauseInterval
    }
}

enum VaultAutoCommitResult: Equatable {
    case committed
    case skipped(String)
    case failed
}

extension AppState {
    /// Commits every non-conflicted change with a message suggested from the
    /// diff, then pushes through the normal push path (which commits locally
    /// and queues the push while offline).
    func autoCommitOnPause(repoID: UUID) async -> VaultAutoCommitResult {
        guard let repo = repo(id: repoID), repo.isCloned else {
            return .skipped(String(localized: "Vault isn't cloned"))
        }
        guard !isSyncing else { return .skipped(String(localized: "Waiting for the current sync")) }
        if conflictSessionByRepo[repoID]?.isActive == true {
            return .skipped(String(localized: "Finish the merge first"))
        }
        let entries = statusEntriesByRepo[repoID] ?? []
        guard !entries.isEmpty else { return .skipped(String(localized: "Nothing to commit")) }
        guard !entries.contains(where: \.isConflicted) else {
            return .skipped(String(localized: "Resolve conflicts first"))
        }

        let deleted: (GitStatusEntry) -> Bool = {
            $0.workTreeStatus == .deleted || ($0.workTreeStatus == nil && $0.indexStatus == .deleted)
        }
        let unstaged = entries.filter { $0.workTreeStatus != nil && !deleted($0) }.map(\.path)
        if !(await lfsCandidates(repoID: repoID, paths: unstaged)).isEmpty {
            // Moving files into LFS is a decision; leave it to the commit sheet.
            return .skipped(String(localized: "Large files need Git LFS — commit by hand"))
        }

        let stats = await lineStats(repoID: repoID)
        let files = entries.map { entry -> CommitMessageSuggester.File in
            let stat = stats[entry.path.precomposedStringWithCanonicalMapping] ?? .zero
            let change: CommitMessageSuggester.Change
            switch entry.workTreeStatus ?? entry.indexStatus {
            case .added, .untracked: change = .added
            case .deleted: change = .deleted
            case .renamed: change = .renamed
            default: change = .modified
            }
            return CommitMessageSuggester.File(
                path: entry.path, change: change,
                added: stat.added, removed: stat.removed, oldPath: entry.oldPath
            )
        }
        let message = CommitMessageSuggester.suggest(for: files).primary
        DebugLogger.shared.info("watch", "Auto-committing \(entries.count) change(s) in \(repo.displayName)")
        let ok = await commitSelected(
            repoID: repoID,
            included: Set(entries.map(\.path)),
            message: message,
            push: true
        )
        return ok ? .committed : .failed
    }
}

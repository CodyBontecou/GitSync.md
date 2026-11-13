import Foundation

/// Stop tracking helpers used by `RemoveGitTrackingView`.
extension AppState {
    /// Where `<name>-history.zip` backups are written: a folder in the app's
    /// Documents, visible under On My iPhone › GitSync.md in Files.
    nonisolated static var gitHistoryArchiveDirectory: URL {
        appDocumentsDirectory.appendingPathComponent("Git Backups", isDirectory: true)
    }

    /// Zips the reviewed `.git` directory before tracking is removed.
    func archiveGitHistory(for plan: GitTrackingRemovalPlan) async throws -> GitHistoryArchive {
        guard !isDemoMode else {
            throw GitTrackingRemovalError.operation(String(localized: "Leave demo mode before changing a local folder."))
        }
        guard !isSyncing, !isRemovingGitTracking else {
            throw GitTrackingRemovalError.operation(String(localized: "Wait for the current Git operation to finish before backing up."))
        }
        var directory = Self.gitHistoryArchiveDirectory
        // A vault that *is* the Documents folder cannot hold its own backup.
        let root = Self.canonicalFilePath(for: plan.rootURL)
        let target = Self.canonicalFilePath(for: directory)
        if target == root || target.hasPrefix(root + "/") {
            directory = FileManager.default.temporaryDirectory
                .appendingPathComponent("Git Backups", isDirectory: true)
        }
        return try await GitTrackingRemovalService().archiveHistory(plan, into: directory)
    }

    /// The registered repository whose working folder is `root`, if any.
    func repoID(forTrackingRoot root: URL) -> UUID? {
        let key = Self.canonicalFilePath(for: root)
        return repos.first {
            guard let url = vaultURL(for: $0.id) else { return false }
            return Self.canonicalFilePath(for: url) == key
        }?.id
    }
}

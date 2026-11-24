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

// MARK: - Stop tracking one file or folder

extension AppState {
    /// Tracked paths that `target` would stop tracking (empty on errors).
    func trackedPaths(repoID: UUID, matching target: GitUntrackTarget) async -> [String] {
        guard let repo = repo(id: repoID), repo.isCloned, !isDemoMode,
              let repository = try? serializedRepository(repoID: repoID) else { return [] }
        return (try? await repository.withLease { git in
            guard git.hasGitDirectory else { return [String]() }
            return try await git.trackedPaths(matching: target)
        }) ?? []
    }

    /// Removes `target` from Git, ignores it in `.gitignore` and commits both.
    /// The commit waits on this device until the next push.
    @discardableResult
    func stopTracking(repoID: UUID, target: GitUntrackTarget) async -> GitUntrackResult? {
        guard let repo = repo(id: repoID), repo.isCloned else {
            showError(message: String(localized: "Repository not found"))
            return nil
        }
        if isDemoMode { return nil }
        guard !isSyncing else {
            showError(message: String(localized: "Wait for the current Git operation to finish."), category: "untrack")
            return nil
        }

        isSyncing = true
        syncingRepoID = repoID
        syncProgress = String(localized: "Stopping tracking...")
        defer { isSyncing = false; syncingRepoID = nil }

        do {
            let repository = try serializedRepository(repoID: repoID)
            let result = try await withCheckoutMutation(repoID: repoID) {
                try await repository.withLease { git in
                    guard git.hasGitDirectory else { throw LocalGitError.notCloned }
                    return try await git.stopTracking(
                        target,
                        authorName: repo.authorName,
                        authorEmail: repo.authorEmail
                    )
                }
            }
            if let sha = result.commitSHA {
                if let index = repoIndex(id: repoID) {
                    repos[index].gitState.commitSHA = sha
                    saveRepos()
                }
                clearCommitHistoryCache(for: repoID)
                syncStateByRepo[repoID] = .ahead
            }
            detectChanges(repoID: repoID)
            syncProgress = String(localized: "Stopped tracking")
            DebugLogger.shared.info(
                "untrack",
                "Stopped tracking \(target.displayName)",
                detail: "Removed \(result.removedPaths.count) path(s); ignore entry: \(result.addedIgnoreEntry ?? "already ignored"); commit: \(result.commitSHA ?? "none")"
            )
            return result
        } catch {
            showError(message: error.localizedDescription, category: "untrack")
            detectChanges(repoID: repoID)
            return nil
        }
    }
}

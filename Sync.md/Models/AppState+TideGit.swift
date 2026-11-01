import Foundation

/// Per-file +/− line counts for a working-copy change.
struct GitLineStats: Equatable, Sendable {
    var added: Int
    var removed: Int

    static let zero = GitLineStats(added: 0, removed: 0)

    /// Counts `+` / `-` body lines in one file's unified patch.
    init(patch: String) {
        var added = 0, removed = 0
        for line in patch.split(separator: "\n", omittingEmptySubsequences: false) {
            if line.hasPrefix("+++") || line.hasPrefix("---") { continue }
            if line.hasPrefix("+") { added += 1 } else if line.hasPrefix("-") { removed += 1 }
        }
        self.added = added
        self.removed = removed
    }

    init(added: Int, removed: Int) {
        self.added = added
        self.removed = removed
    }

    init(added: Int, deleted: Int) {
        self.init(added: added, removed: deleted)
    }

    var deleted: Int { removed }

    /// VoiceOver phrasing with correct singular/plural: "1 line added, 3 lines removed".
    static func spokenSummary(added: Int, removed: Int) -> String {
        let a = added == 1 ? String(localized: "1 line added") : String(localized: "\(added) lines added")
        let r = removed == 1 ? String(localized: "1 line removed") : String(localized: "\(removed) lines removed")
        return a + ", " + r
    }

    /// Lines that differ, pairing removals with additions inside each hunk
    /// (an edited line counts once, not twice).
    static func changedLineCount(patch: String) -> Int {
        var total = 0, added = 0, removed = 0
        func flush() { total += max(added, removed); added = 0; removed = 0 }
        for line in patch.split(separator: "\n", omittingEmptySubsequences: false) {
            if line.hasPrefix("+++") || line.hasPrefix("---") { continue }
            if line.hasPrefix("@@") { flush(); continue }
            if line.hasPrefix("+") { added += 1 } else if line.hasPrefix("-") { removed += 1 } else { flush() }
        }
        flush()
        return total
    }
}

/// Git work behind the Tide commit sheet, discard sheet, file actions and
/// conflict meta. Kept out of AppState.swift so the main file stays stable.
extension AppState {

    // MARK: - Working diff

    /// HEAD → working tree diff for the whole vault, without touching the
    /// shared `diffByRepo` cache the diff screen reads.
    func workingDiff(repoID: UUID) async -> UnifiedDiffResult {
        guard let repo = repo(id: repoID), repo.isCloned, !isDemoMode,
              let repository = try? serializedRepository(repoID: repoID),
              repository.hasGitDirectory else { return .empty }
        return (try? await repository.unifiedDiff(path: nil)) ?? .empty
    }

    /// +/− line counts per changed path (new path and old path both keyed).
    func lineStats(repoID: UUID) async -> [String: GitLineStats] {
        let diff = await workingDiff(repoID: repoID)
        var stats: [String: GitLineStats] = [:]
        for file in diff.files {
            let value = GitLineStats(patch: file.patch)
            stats[file.path.precomposedStringWithCanonicalMapping] = value
            if let old = file.oldPath, stats[old.precomposedStringWithCanonicalMapping] == nil {
                stats[old.precomposedStringWithCanonicalMapping] = value
            }
        }
        return stats
    }

    /// How `path` differs from HEAD: lines changed (edited lines count once)
    /// and the 1-based working-copy line numbers that were added or edited.
    func changeSummary(repoID: UUID, path: String) async -> (count: Int, lines: Set<Int>) {
        guard !isDemoMode,
              let repository = try? serializedRepository(repoID: repoID), repository.hasGitDirectory,
              let diff = try? await repository.unifiedDiff(path: path) else { return (0, []) }
        var lines = Set<Int>()
        var count = 0
        for file in diff.files {
            count += GitLineStats.changedLineCount(patch: file.patch)
            var newLine = 0
            for raw in file.patch.split(separator: "\n", omittingEmptySubsequences: false) {
                if raw.hasPrefix("@@") {
                    // "@@ -a,b +c,d @@"
                    if let plus = raw.split(separator: " ").first(where: { $0.hasPrefix("+") }) {
                        newLine = Int(plus.dropFirst().split(separator: ",").first ?? "") ?? 0
                    }
                } else if raw.hasPrefix("+++") || raw.hasPrefix("---") {
                    continue
                } else if raw.hasPrefix("+") {
                    lines.insert(newLine)
                    newLine += 1
                } else if raw.hasPrefix("-") || raw.hasPrefix("\\") {
                    continue
                } else if newLine > 0 {
                    newLine += 1
                }
            }
        }
        return (count, lines)
    }

    // MARK: - Commit sheet

    /// Paths among `paths` that should go through Git LFS before staging.
    func lfsCandidates(repoID: UUID, paths: [String]) async -> [GitLFSAutoTrackingCandidate] {
        guard !isDemoMode, !paths.isEmpty,
              let repository = try? serializedRepository(repoID: repoID) else { return [] }
        return (try? await repository.lfsAutoTrackingCandidates(paths: paths)) ?? []
    }

    /// Makes the index hold exactly the `included` changes, then commits —
    /// and pushes when `push` is true. Excluded files stay as local edits.
    @discardableResult
    func commitSelected(
        repoID: UUID,
        included: Set<String>,
        message: String,
        push: Bool,
        lfsAutoTrack: Bool = false
    ) async -> Bool {
        guard let repo = repo(id: repoID), repo.isCloned else { return false }

        // Merge / rebase completion keeps its existing path.
        if conflictSessionByRepo[repoID]?.isActive == true {
            return await self.push(repoID: repoID, message: message)
        }

        if isDemoMode {
            return push ? await self.push(repoID: repoID, message: message) : true
        }

        do {
            let repository = try serializedRepository(repoID: repoID)
            guard repository.hasGitDirectory else { throw LocalGitError.notCloned }
            let entries = statusEntriesByRepo[repoID] ?? []
            markRepositoryMutated(repoID: repoID)
            for entry in entries {
                let wanted = included.contains(entry.path)
                if wanted, entry.workTreeStatus != nil {
                    try await repository.stage(path: entry.path, oldPath: entry.oldPath, lfsAutoTrack: lfsAutoTrack)
                } else if !wanted, entry.indexStatus != nil {
                    try await repository.unstage(path: entry.path, oldPath: entry.oldPath)
                }
            }
            markRepositoryMutated(repoID: repoID)
        } catch {
            showError(message: error.localizedDescription, category: "commit")
            detectChanges(repoID: repoID)
            return false
        }

        if push {
            if isOffline {
                return await commitOfflineAndQueue(repoID: repoID, message: message, stageEverything: false)
            }
            return await self.push(repoID: repoID, message: message)
        }
        return await commitOnly(repoID: repoID, message: message)
    }

    /// Commits what is staged without pushing. The commit waits on this
    /// iPhone until the next push.
    @discardableResult
    func commitOnly(repoID: UUID, message: String) async -> Bool {
        guard let repo = repo(id: repoID), repo.isCloned else {
            showError(message: String(localized: "Repository not found"))
            return false
        }
        if isDemoMode { return true }

        let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
        let commitMessage = trimmed.isEmpty ? String(localized: "Update from GitSync.md") : trimmed

        isSyncing = true
        syncingRepoID = repoID
        syncProgress = String(localized: "Committing...")
        defer { isSyncing = false; syncingRepoID = nil }

        do {
            let repository = try serializedRepository(repoID: repoID)
            markRepositoryMutated(repoID: repoID)
            let sha = try await repository.withLease { git in
                guard git.hasGitDirectory else { throw LocalGitError.notCloned }
                return try await git.commitLocal(
                    message: commitMessage,
                    authorName: repo.authorName,
                    authorEmail: repo.authorEmail
                )
            }
            markRepositoryMutated(repoID: repoID)
            if let index = repoIndex(id: repoID) {
                repos[index].gitState.commitSHA = sha
                saveRepos()
            }
            clearCommitHistoryCache(for: repoID)
            syncStateByRepo[repoID] = .ahead
            detectChanges(repoID: repoID)
            syncProgress = String(localized: "Committed")
            DebugLogger.shared.info("commit", "Committed without pushing", detail: "SHA: \(sha)")
            return true
        } catch {
            showError(message: error.localizedDescription, category: "commit")
            detectChanges(repoID: repoID)
            return false
        }
    }

    // MARK: - Discard

    /// Discards `paths` (or every change when nil). With `stashFirst`, the
    /// current edits are saved as a stash before anything is thrown away.
    func discardChanges(repoID: UUID, paths: [String]?, stashFirst: Bool) async {
        guard let repo = repo(id: repoID), repo.isCloned, !isDemoMode else { return }

        if stashFirst {
            do {
                let repository = try serializedRepository(repoID: repoID)
                let label = String(localized: "Before discard · \(Date().formatted(date: .abbreviated, time: .shortened))")
                markRepositoryMutated(repoID: repoID)
                try await repository.withLease { git in
                    _ = try await git.saveStash(
                        message: label,
                        authorName: repo.authorName,
                        authorEmail: repo.authorEmail,
                        includeUntracked: true
                    )
                    // Saving a stash resets the working copy. When only some
                    // files are discarded, put everything back first so the
                    // stash is a copy, not a move.
                    if paths != nil {
                        let result = try await git.applyStash(index: 0, reinstateIndex: true)
                        if result.kind == .conflicts {
                            throw LocalGitError.commitFailed(
                                String(localized: "Could not restore your edits after stashing. They are saved in the stash list.")
                            )
                        }
                    }
                }
                markRepositoryMutated(repoID: repoID)
                await loadStashes(repoID: repoID)
            } catch {
                showError(message: error.localizedDescription, category: "revert")
                detectChanges(repoID: repoID)
                return
            }
            if paths == nil {
                // The stash already reset every change.
                detectChanges(repoID: repoID)
                return
            }
        }

        if let paths {
            for path in paths { await discardFileChanges(repoID: repoID, path: path) }
        } else {
            await discardAllFileChanges(repoID: repoID)
        }
    }

    // MARK: - File history & commit lookup

    func fileHistory(repoID: UUID, path: String, limit: Int = 50) async -> [GitCommitSummary] {
        guard let repo = repo(id: repoID), repo.isCloned, !isDemoMode else { return [] }
        let service = LocalGitService(localURL: vaultURL(for: repoID))
        guard service.hasGitDirectory else { return [] }
        do {
            return try await service.fileHistory(path: path, limit: limit)
        } catch {
            showError(message: error.localizedDescription, category: "history")
            return []
        }
    }

    /// The commit that brought in the remote side of an in-progress merge or
    /// rebase (falls back to the upstream tip).
    func remoteSideCommit(repoID: UUID) async -> GitCommitSummary? {
        guard let repo = repo(id: repoID), repo.isCloned, !isDemoMode else { return nil }
        let service = LocalGitService(localURL: vaultURL(for: repoID))
        guard service.hasGitDirectory else { return nil }
        for revision in ["MERGE_HEAD", "REBASE_HEAD", "@{upstream}", "origin/\(repo.gitState.branch)"] {
            if let summary = try? await service.commitSummary(revision: revision) { return summary }
        }
        return nil
    }

    /// Short name for the remote host ("GitHub", "GitLab", or the host).
    func remoteHostLabel(repoID: UUID) -> String {
        guard let repo = repo(id: repoID), let remote = GitRemoteURL.parse(repo.repoURL) else {
            return String(localized: "Remote")
        }
        if remote.isGitHub { return "GitHub" }
        guard let host = remote.host?.lowercased(), !host.isEmpty else { return String(localized: "Remote") }
        if host.contains("gitlab") { return "GitLab" }
        if host.contains("bitbucket") { return "Bitbucket" }
        if host.contains("codeberg") { return "Codeberg" }
        return host
    }

    // MARK: - Git LFS

    /// Downloads the LFS object behind one pointer file and checks it out.
    @discardableResult
    func fetchLFSFile(repoID: UUID, path: String) async -> Bool {
        guard let repo = repo(id: repoID), repo.isCloned, !isDemoMode else { return false }
        let localURL = vaultURL(for: repoID)
        let credentials = remoteCredentials(for: repo)
        do {
            let repository = try serializedRepository(repoID: repoID)
            let result = try await repository.withLease { _ in
                try await GitLFSService(localURL: localURL, credentials: credentials)
                    .hydrateWorktree(candidatePaths: [path])
            }
            detectChanges(repoID: repoID)
            return result.checkedOutCount > 0
        } catch {
            showError(message: error.localizedDescription, category: "lfs")
            return false
        }
    }
}

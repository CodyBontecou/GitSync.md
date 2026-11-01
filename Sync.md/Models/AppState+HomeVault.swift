import Foundation
import UIKit

// MARK: - Home & Vault insights
//
// Read-only facts (fetch time, LFS size, numstat, ref labels) and the Home
// "Sync all" run. Git reads go through `VaultInsightsService` off the main
// actor; results land in the stored dictionaries declared in AppState.swift.

extension AppState {

    // MARK: Pinning & naming

    func setPinned(_ pinned: Bool, repoID: UUID) {
        updateRepo(id: repoID) { $0.isPinned = pinned }
    }

    func setCustomDisplayName(_ name: String, repoID: UUID) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        updateRepo(id: repoID) { repo in
            repo.customDisplayName = (trimmed.isEmpty || trimmed == repo.repoName) ? nil : trimmed
        }
    }

    // MARK: Meta (fetch time, LFS size, ahead/behind)

    /// Refreshes `vaultMetaByRepo` for one vault. The LFS object store is only
    /// re-measured every few minutes unless `forceLFS` is set.
    func refreshVaultMeta(repoID: UUID, forceLFS: Bool = false) async {
        guard let repo = repo(id: repoID), repo.isCloned, !isDemoMode,
              hasLocalRepositoryAccess(repoID: repoID) else { return }
        let url = vaultURL(for: repoID)
        let measuredAt = vaultLFSMeasuredAt[repoID]
        let includeLFS = forceLFS || measuredAt == nil || Date().timeIntervalSince(measuredAt!) > 300
        let previousLFS = vaultMetaByRepo[repoID]?.lfsBytes ?? 0

        var meta = await Task.detached(priority: .utility) {
            VaultInsightsService.meta(repositoryURL: url, includeLFS: includeLFS)
        }.value
        guard !Task.isCancelled else { return }
        if includeLFS {
            vaultLFSMeasuredAt[repoID] = Date()
        } else {
            meta.lfsBytes = previousLFS
        }
        if vaultMetaByRepo[repoID] != meta {
            vaultMetaByRepo[repoID] = meta
        }
    }

    func refreshAllVaultMeta() async {
        for repo in visibleRepos where repo.isCloned {
            guard !Task.isCancelled else { return }
            await refreshVaultMeta(repoID: repo.id)
        }
    }

    /// Latest fetch across all vaults, or change detection if that is newer.
    var lastCheckedDate: Date? {
        let fetches = visibleRepos.compactMap { vaultMetaByRepo[$0.id]?.lastFetchDate }
        return ([lastStatusCheckDate].compactMap { $0 } + fetches).max()
    }

    // MARK: Changes numstat

    func loadWorkingLineStats(repoID: UUID) async {
        guard let repo = repo(id: repoID), repo.isCloned, !isDemoMode,
              hasLocalRepositoryAccess(repoID: repoID) else { return }
        let url = vaultURL(for: repoID)
        let stats = (try? await Task.detached(priority: .utility) {
            try VaultInsightsService.workingLineStats(repositoryURL: url)
        }.value) ?? [:]
        guard !Task.isCancelled else { return }
        if workingLineStatsByRepo[repoID] != stats {
            workingLineStatsByRepo[repoID] = stats
        }
    }

    func lineStats(repoID: UUID, path: String) -> GitLineStats? {
        workingLineStatsByRepo[repoID]?[path.precomposedStringWithCanonicalMapping]
    }

    // MARK: History decorations

    /// Ref labels plus parent/file counts for the loaded history page.
    func loadHistoryDecorations(repoID: UUID) async {
        guard let repo = repo(id: repoID), repo.isCloned, !isDemoMode,
              hasLocalRepositoryAccess(repoID: repoID) else { return }
        let url = vaultURL(for: repoID)
        let known = commitExtrasByRepo[repoID] ?? [:]
        let missing = (commitHistoryByRepo[repoID] ?? []).map(\.oid).filter { known[$0] == nil }

        let (labels, extras) = await Task.detached(priority: .utility) {
            let labels = (try? VaultInsightsService.refLabels(repositoryURL: url)) ?? [:]
            let extras = (try? VaultInsightsService.commitExtras(repositoryURL: url, oids: missing)) ?? [:]
            return (labels, extras)
        }.value
        guard !Task.isCancelled else { return }
        if commitRefLabelsByRepo[repoID] != labels {
            commitRefLabelsByRepo[repoID] = labels
        }
        if !extras.isEmpty {
            commitExtrasByRepo[repoID] = known.merging(extras) { _, new in new }
        }
    }

    /// Where a commit most likely came from, from message/author heuristics.
    /// `isLocalOnly` marks commits not yet on the upstream (made on this device).
    func commitSource(_ commit: GitCommitSummary, repoID: UUID, isLocalOnly: Bool) -> CommitSource {
        let message = commit.message.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = message.lowercased()
        if let repo = repo(id: repoID), let backgroundSHA = repo.assist.health.commitSHA,
           !backgroundSHA.isEmpty, backgroundSHA == commit.oid {
            return .backgroundSync
        }
        if lower.hasPrefix("vault backup:") || lower.hasPrefix("auto backup:") {
            return .obsidianGit
        }
        if isLocalOnly || CommitSource.appCommitMessages.contains(message) {
            return .thisDevice
        }
        return .author(commit.authorName)
    }

    // MARK: Sync all

    /// Vaults the Home "Sync all" button walks through.
    var syncAllTargets: [RepoConfig] {
        visibleRepos.filter {
            $0.isCloned
                && !$0.repoURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && hasLocalRepositoryAccess(repoID: $0.id)
        }
    }

    /// Pulls, commits local edits, and pushes every vault one at a time through
    /// the same serialized `syncRepository` path the Shortcuts use. Problems are
    /// collected and shown once at the end.
    func syncAllVaults() async {
        guard !isSyncing, !isSyncingAllVaults else { return }
        let targets = syncAllTargets
        guard !targets.isEmpty else { return }
        isSyncingAllVaults = true
        defer {
            isSyncingAllVaults = false
            syncAllProgressText = nil
        }

        var problems: [String] = []
        for (index, repo) in targets.enumerated() {
            guard !Task.isCancelled else { break }
            syncAllProgressText = String(localized: "\(index + 1) of \(targets.count) · \(repo.displayName)")
            let result = await syncRepository(repoID: repo.id)
            switch result.outcome {
            case .synced, .pushSkipped:
                break
            case .blocked:
                problems.append("\(repo.displayName): \(result.message)")
            case .authenticationOrTrustRequired(let message, _), .failed(let message):
                problems.append("\(repo.displayName): \(message)")
                RepoErrorJournal.record(repoID: repo.id)
            }
            await refreshVaultMeta(repoID: repo.id)
        }

        if !problems.isEmpty {
            DebugLogger.shared.error("sync", "Sync all finished with problems", detail: problems.joined(separator: "\n"))
            lastError = problems.joined(separator: "\n")
            showError = true
        }
    }

    // MARK: Folder

    /// "Obsidian › VoxVault" — the containing folder (by its Files-app name)
    /// and the vault folder. App-managed vaults read "GitSync.md › name".
    func vaultFolderBreadcrumb(repoID: UUID) -> [String] {
        guard let repo = repo(id: repoID) else { return [] }
        guard isUsingCustomLocation(for: repoID) else {
            return ["GitSync.md"] + (repo.customVaultRelativePath ?? repo.vaultFolderName)
                .split(separator: "/").map(String.init)
        }
        let url = vaultURL(for: repoID)
        let parent = url.deletingLastPathComponent()
        let parentName = (try? parent.resourceValues(forKeys: [.localizedNameKey]))?.localizedName
            ?? parent.lastPathComponent
        let friendlyParent: String
        switch parentName {
        case "File Provider Storage", "Documents", "": friendlyParent = String(localized: "On My iPhone")
        default: friendlyParent = parentName
        }
        return [friendlyParent, url.lastPathComponent]
    }

    // MARK: LFS locks

    func lfsService(repoID: UUID) -> GitLFSService? {
        guard let repo = repo(id: repoID) else { return nil }
        return GitLFSService(localURL: vaultURL(for: repoID), credentials: remoteCredentials(for: repo))
    }
}

// MARK: - Commit source

enum CommitSource: Equatable {
    case thisDevice
    case obsidianGit
    case backgroundSync
    case author(String)

    /// Default messages GitSync.md writes when the user leaves it empty.
    static let appCommitMessages: Set<String> = [
        "Update from GitSync.md",
        "Local changes from GitSync.md",
        String(localized: "Update from GitSync.md"),
        String(localized: "Local changes from GitSync.md"),
    ]

    /// "iPhone", "Obsidian Git", "Background Sync", or the author name.
    var label: String {
        switch self {
        case .thisDevice: return UIDevice.current.model
        case .obsidianGit: return String(localized: "Obsidian Git")
        case .backgroundSync: return String(localized: "Background Sync")
        case .author(let name): return name
        }
    }

    /// Vault "Last pushed" phrasing: "from Obsidian", "from iPhone", …
    var fromLabel: String {
        switch self {
        case .thisDevice: return String(localized: "from \(UIDevice.current.model)")
        case .obsidianGit: return String(localized: "from Obsidian")
        case .backgroundSync: return String(localized: "Background Sync")
        case .author(let name): return name
        }
    }
}

// MARK: - Error journal

/// Per-vault error timestamps (last 7 days) for the Vault settings health card.
/// DebugLogger entries aren't attributed to a vault, so failures are recorded
/// here when the vault they belong to is known.
enum RepoErrorJournal {
    private static let key = "repo_error_journal_v1"
    private static let window: TimeInterval = 7 * 24 * 60 * 60

    static func record(repoID: UUID, at date: Date = Date()) {
        var journal = load()
        var dates = journal[repoID.uuidString] ?? []
        dates.append(date.timeIntervalSince1970)
        journal[repoID.uuidString] = dates
        save(journal)
    }

    static func count(repoID: UUID, now: Date = Date()) -> Int {
        let cutoff = now.addingTimeInterval(-window).timeIntervalSince1970
        return (load()[repoID.uuidString] ?? []).filter { $0 >= cutoff }.count
    }

    private static func load() -> [String: [TimeInterval]] {
        UserDefaults.standard.dictionary(forKey: key) as? [String: [TimeInterval]] ?? [:]
    }

    private static func save(_ journal: [String: [TimeInterval]]) {
        let cutoff = Date().addingTimeInterval(-window).timeIntervalSince1970
        let pruned = journal
            .mapValues { $0.filter { $0 >= cutoff }.suffix(200).map { $0 } }
            .filter { !$0.value.isEmpty }
        UserDefaults.standard.set(pruned, forKey: key)
    }
}

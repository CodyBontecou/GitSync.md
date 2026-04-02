import Foundation
import SwiftUI

// MARK: - App State

@Observable
final class AppState {

    // MARK: - Repositories

    var repos: [RepoConfig] = []
    var changeCounts: [UUID: Int] = [:]
    var statusEntriesByRepo: [UUID: [GitStatusEntry]] = [:]
    var syncStateByRepo: [UUID: RepoSyncState] = [:]
    var pullOutcomeByRepo: [UUID: PullOutcomeState] = [:]
    var diffByRepo: [UUID: UnifiedDiffResult] = [:]
    var branchesByRepo: [UUID: BranchInventory] = [:]
    var conflictSessionByRepo: [UUID: ConflictSession] = [:]
    var commitHistoryByRepo: [UUID: [GitCommitSummary]] = [:]
    var commitHistoryHasMoreByRepo: [UUID: Bool] = [:]
    var commitDetailByRepo: [UUID: [String: GitCommitDetail]] = [:]
    var stashesByRepo: [UUID: [GitStashEntry]] = [:]
    var tagsByRepo: [UUID: [GitTag]] = [:]

    // MARK: - Sync State

    var isSyncing: Bool = false
    var syncingRepoID: UUID? = nil
    var syncProgress: String = ""

    // MARK: - OAuth / Auth

    var isSignedIn: Bool = false
    var gitHubUsername: String = ""
    var gitHubDisplayName: String = ""
    var gitHubAvatarURL: String = ""
    var defaultAuthorName: String = ""
    var defaultAuthorEmail: String = ""
    var gitHubRepos: [GitHubRepo] = []
    var isLoadingRepos: Bool = false

    // MARK: - Callback State (x-callback-url from Obsidian plugin)

    /// When set, the UI programmatically navigates to this repo's VaultView.
    var callbackNavigateToRepoID: UUID? = nil

    /// Result from a completed callback operation — shown briefly before redirecting.
    var callbackResult: CallbackResultState? = nil

    // MARK: - Default Save Location

    var defaultSaveLocationBookmarkData: Data? = nil
    var resolvedDefaultSaveURL: URL? = nil
    private var defaultSaveAccessingScope: Bool = false

    /// Whether onboarding (including the save-location step) has been completed.
    var hasCompletedOnboarding: Bool = false

    // MARK: - Demo Mode

    var isDemoMode: Bool = false

    // MARK: - Review Prompt

    /// Set to `true` after the user's first successful clone, so the UI can trigger a review request.
    var shouldRequestReview: Bool = false

    // MARK: - Errors

    var lastError: String? = nil
    var showError: Bool = false

    // MARK: - Security-Scoped URLs (runtime only)

    private var resolvedCustomURLs: [UUID: URL] = [:]
    private var accessingSecurityScope: Set<UUID> = []

    // MARK: - PAT

    var pat: String {
        get { KeychainService.load(key: "github_pat") ?? "" }
        set { KeychainService.save(key: "github_pat", value: newValue) }
    }

    // MARK: - Dependencies

    private let gitRepositoryFactory: (URL) -> any GitRepositoryProtocol

    // MARK: - Init

    init(
        gitRepositoryFactory: @escaping (URL) -> any GitRepositoryProtocol = { LocalGitService(localURL: $0) },
        loadPersistedState: Bool = true
    ) {
        self.gitRepositoryFactory = gitRepositoryFactory
        if loadPersistedState {
            loadState()
        }
    }

    // MARK: - Persistence

    private static var reposFileURL: URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let dir = support.appendingPathComponent("SyncMD", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("repos.json")
    }

    private func loadState() {
        let defaults = UserDefaults.standard
        gitHubUsername = defaults.string(forKey: "gitHubUsername") ?? ""
        gitHubDisplayName = defaults.string(forKey: "gitHubDisplayName") ?? ""
        gitHubAvatarURL = defaults.string(forKey: "gitHubAvatarURL") ?? ""
        defaultAuthorName = defaults.string(forKey: "authorName") ?? ""
        defaultAuthorEmail = defaults.string(forKey: "authorEmail") ?? ""
        hasCompletedOnboarding = defaults.bool(forKey: "hasCompletedOnboarding")
        isSignedIn = !pat.isEmpty

        // Load default save location bookmark
        defaultSaveLocationBookmarkData = defaults.data(forKey: "defaultSaveLocationBookmark")
        resolveDefaultSaveBookmark()

        // Try to load multi-repo state
        if let data = try? Data(contentsOf: Self.reposFileURL) {
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            if let decoded = try? decoder.decode([RepoConfig].self, from: data) {
                repos = decoded
            }
        } else {
            // Migration from single-repo state
            migrateFromLegacy()
        }

        // Resolve custom vault bookmarks
        for repo in repos {
            resolveVaultBookmark(for: repo.id)
        }

        // Validate that cloned repos still exist on disk
        validateClonedRepos()

        // Detect changes for all cloned repos
        for repo in repos where repo.isCloned {
            detectChanges(repoID: repo.id)
        }
    }

    private func migrateFromLegacy() {
        let defaults = UserDefaults.standard
        let legacyRepoURL = defaults.string(forKey: "repoURL") ?? ""
        let legacyIsSetUp = defaults.bool(forKey: "isSetUp")

        guard legacyIsSetUp, !legacyRepoURL.isEmpty else { return }

        let legacyGitState = GitState.loadLegacy() ?? .empty

        let config = RepoConfig(
            repoURL: legacyRepoURL,
            branch: defaults.string(forKey: "branch") ?? "main",
            authorName: defaults.string(forKey: "authorName") ?? "",
            authorEmail: defaults.string(forKey: "authorEmail") ?? "",
            vaultFolderName: defaults.string(forKey: "vaultFolderName") ?? "vault",
            customVaultBookmarkData: defaults.data(forKey: "vaultBookmark"),
            gitState: legacyGitState
        )

        repos = [config]
        saveRepos()

        // Clean up legacy keys
        GitState.deleteLegacy()
        defaults.removeObject(forKey: "isSetUp")
        defaults.removeObject(forKey: "repoURL")
        defaults.removeObject(forKey: "branch")
        defaults.removeObject(forKey: "vaultFolderName")
        defaults.removeObject(forKey: "vaultBookmark")
    }

    func saveRepos() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = .prettyPrinted
        if let data = try? encoder.encode(repos) {
            try? data.write(to: Self.reposFileURL, options: .atomic)
        }
    }

    func saveGlobalSettings() {
        let defaults = UserDefaults.standard
        defaults.set(gitHubUsername, forKey: "gitHubUsername")
        defaults.set(gitHubDisplayName, forKey: "gitHubDisplayName")
        defaults.set(gitHubAvatarURL, forKey: "gitHubAvatarURL")
        defaults.set(defaultAuthorName, forKey: "authorName")
        defaults.set(defaultAuthorEmail, forKey: "authorEmail")
        defaults.set(hasCompletedOnboarding, forKey: "hasCompletedOnboarding")

        if let bookmarkData = defaultSaveLocationBookmarkData {
            defaults.set(bookmarkData, forKey: "defaultSaveLocationBookmark")
        } else {
            defaults.removeObject(forKey: "defaultSaveLocationBookmark")
        }
    }

    // MARK: - Default Save Location

    func setDefaultSaveLocation(_ url: URL) {
        clearDefaultSaveLocation()

        guard url.startAccessingSecurityScopedResource() else { return }

        guard let bookmark = try? url.bookmarkData(
            options: [],
            includingResourceValuesForKeys: nil,
            relativeTo: nil
        ) else {
            url.stopAccessingSecurityScopedResource()
            return
        }

        defaultSaveLocationBookmarkData = bookmark
        resolvedDefaultSaveURL = url
        defaultSaveAccessingScope = true
        saveGlobalSettings()
    }

    func clearDefaultSaveLocation() {
        if defaultSaveAccessingScope, let url = resolvedDefaultSaveURL {
            url.stopAccessingSecurityScopedResource()
            defaultSaveAccessingScope = false
        }
        resolvedDefaultSaveURL = nil
        defaultSaveLocationBookmarkData = nil
        saveGlobalSettings()
    }

    var defaultSaveDisplayPath: String {
        resolvedDefaultSaveURL?.path ?? ""
    }

    var hasDefaultSaveLocation: Bool {
        resolvedDefaultSaveURL != nil
    }

    private func resolveDefaultSaveBookmark() {
        guard let bookmarkData = defaultSaveLocationBookmarkData else { return }

        var isStale = false
        guard let url = try? URL(
            resolvingBookmarkData: bookmarkData,
            options: [],
            relativeTo: nil,
            bookmarkDataIsStale: &isStale
        ) else { return }

        if url.startAccessingSecurityScopedResource() {
            defaultSaveAccessingScope = true
        }
        resolvedDefaultSaveURL = url

        if isStale {
            if let newBookmark = try? url.bookmarkData(
                options: [],
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            ) {
                defaultSaveLocationBookmarkData = newBookmark
                saveGlobalSettings()
            }
        }
    }

    // MARK: - Repo Access

    func repo(id: UUID) -> RepoConfig? {
        repos.first { $0.id == id }
    }

    func repoIndex(id: UUID) -> Int? {
        repos.firstIndex { $0.id == id }
    }

    func vaultURL(for repoID: UUID) -> URL {
        if let customURL = resolvedCustomURLs[repoID] {
            // When the bookmark points to a parent directory (clone to custom
            // location), append the repo folder name — just like `git clone`.
            if let repo = repo(id: repoID), repo.customLocationIsParent {
                return customURL.appendingPathComponent(repo.vaultFolderName, isDirectory: true)
            }
            return customURL
        }
        guard let repo = repo(id: repoID) else {
            return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        }
        return repo.defaultVaultURL
    }

    func vaultDisplayPath(for repoID: UUID) -> String {
        if let customURL = resolvedCustomURLs[repoID] {
            if let repo = repo(id: repoID), repo.customLocationIsParent {
                return customURL.appendingPathComponent(repo.vaultFolderName).path
            }
            return customURL.path
        }
        guard let repo = repo(id: repoID) else { return "" }
        return String(localized: "On My iPhone › Sync.md › \(repo.vaultFolderName)")
    }

    func isUsingCustomLocation(for repoID: UUID) -> Bool {
        resolvedCustomURLs[repoID] != nil
    }

    // MARK: - Vault Location

    func setCustomVaultLocation(_ url: URL, for repoID: UUID) {
        // Stop any previous security-scoped access for this repo
        clearCustomLocation(for: repoID)

        guard url.startAccessingSecurityScopedResource() else { return }

        guard let bookmark = try? url.bookmarkData(
            options: [],
            includingResourceValuesForKeys: nil,
            relativeTo: nil
        ) else {
            url.stopAccessingSecurityScopedResource()
            return
        }

        if let idx = repoIndex(id: repoID) {
            repos[idx].customVaultBookmarkData = bookmark
            saveRepos()
        }

        resolvedCustomURLs[repoID] = url
        accessingSecurityScope.insert(repoID)
    }

    func clearCustomLocation(for repoID: UUID) {
        if accessingSecurityScope.contains(repoID), let url = resolvedCustomURLs[repoID] {
            url.stopAccessingSecurityScopedResource()
            accessingSecurityScope.remove(repoID)
        }
        resolvedCustomURLs.removeValue(forKey: repoID)
        if let idx = repoIndex(id: repoID) {
            repos[idx].customVaultBookmarkData = nil
            saveRepos()
        }
    }

    private func resolveVaultBookmark(for repoID: UUID) {
        guard let repo = repo(id: repoID),
              let bookmarkData = repo.customVaultBookmarkData else { return }

        var isStale = false
        guard let url = try? URL(
            resolvingBookmarkData: bookmarkData,
            options: [],
            relativeTo: nil,
            bookmarkDataIsStale: &isStale
        ) else { return }

        if url.startAccessingSecurityScopedResource() {
            accessingSecurityScope.insert(repoID)
        }
        resolvedCustomURLs[repoID] = url

        if isStale {
            if let newBookmark = try? url.bookmarkData(
                options: [],
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            ), let idx = repoIndex(id: repoID) {
                repos[idx].customVaultBookmarkData = newBookmark
                saveRepos()
            }
        }
    }

    // MARK: - Filesystem Validation

    /// Check all repos marked as cloned and reset any whose `.git` directory
    /// has been deleted from the filesystem (e.g. via Files app).
    func validateClonedRepos() {
        if isDemoMode { return }
        var didChange = false
        for (index, repo) in repos.enumerated() where repo.isCloned {
            let vaultDir = vaultURL(for: repo.id)
            let gitService = gitRepositoryFactory(vaultDir)

            if !gitService.hasGitDirectory {
                repos[index].gitState = .empty
                changeCounts[repo.id] = 0
                statusEntriesByRepo[repo.id] = []
                syncStateByRepo[repo.id] = .unknown
                diffByRepo[repo.id] = .empty
                branchesByRepo[repo.id] = .empty
                conflictSessionByRepo[repo.id] = .none
                commitHistoryByRepo[repo.id] = []
                commitHistoryHasMoreByRepo[repo.id] = false
                commitDetailByRepo[repo.id] = [:]
                stashesByRepo[repo.id] = []
                didChange = true
            }
        }
        if didChange {
            saveRepos()
        }
    }

    // MARK: - Change Detection

    func detectChanges(repoID: UUID) {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }
        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            // .git directory was removed — reset cloned state
            if let idx = repoIndex(id: repoID) {
                repos[idx].gitState = .empty
                saveRepos()
            }
            changeCounts[repoID] = 0
            statusEntriesByRepo[repoID] = []
            syncStateByRepo[repoID] = .unknown
            diffByRepo[repoID] = .empty
            branchesByRepo[repoID] = .empty
            conflictSessionByRepo[repoID] = .none
            commitHistoryByRepo[repoID] = []
            commitHistoryHasMoreByRepo[repoID] = false
            commitDetailByRepo[repoID] = [:]
            stashesByRepo[repoID] = []
            return
        }

        Task {
            do {
                let info = try await gitService.repoInfo()
                changeCounts[repoID] = info.changeCount
                statusEntriesByRepo[repoID] = info.statusEntries
                syncStateByRepo[repoID] = info.syncState
                diffByRepo[repoID] = .empty
            } catch {
                changeCounts[repoID] = 0
                statusEntriesByRepo[repoID] = []
                syncStateByRepo[repoID] = .unknown
                diffByRepo[repoID] = .empty
                branchesByRepo[repoID] = .empty
                conflictSessionByRepo[repoID] = .none
                commitHistoryByRepo[repoID] = []
                commitHistoryHasMoreByRepo[repoID] = false
                commitDetailByRepo[repoID] = [:]
                stashesByRepo[repoID] = []
            }
        }
    }

    func loadUnifiedDiff(repoID: UUID, path: String? = nil) async {
        guard let repo = repo(id: repoID), repo.isCloned else {
            diffByRepo[repoID] = .empty
            return
        }
        if isDemoMode {
            diffByRepo[repoID] = .empty
            return
        }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            diffByRepo[repoID] = .empty
            return
        }

        do {
            diffByRepo[repoID] = try await gitService.unifiedDiff(path: path)
        } catch {
            diffByRepo[repoID] = .empty
            showError(message: error.localizedDescription)
        }
    }

    func loadBranches(repoID: UUID) async {
        guard let repo = repo(id: repoID), repo.isCloned else {
            branchesByRepo[repoID] = .empty
            return
        }
        if isDemoMode {
            branchesByRepo[repoID] = .empty
            return
        }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            branchesByRepo[repoID] = .empty
            return
        }

        do {
            branchesByRepo[repoID] = try await gitService.listBranches()
        } catch {
            branchesByRepo[repoID] = .empty
            showError(message: error.localizedDescription)
        }
    }

    func loadConflictSession(repoID: UUID) async {
        guard let repo = repo(id: repoID), repo.isCloned else {
            conflictSessionByRepo[repoID] = .none
            return
        }
        if isDemoMode {
            conflictSessionByRepo[repoID] = .none
            return
        }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            conflictSessionByRepo[repoID] = .none
            return
        }

        do {
            conflictSessionByRepo[repoID] = try await gitService.conflictSession()
        } catch {
            conflictSessionByRepo[repoID] = .none
            showError(message: error.localizedDescription)
        }
    }

    func resolveConflictFile(repoID: UUID, path: String, strategy: ConflictResolutionStrategy) async {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        do {
            try await gitService.resolveConflict(path: path, strategy: strategy)
            detectChanges(repoID: repoID)
            await loadConflictSession(repoID: repoID)
        } catch {
            await loadConflictSession(repoID: repoID)
            showError(message: error.localizedDescription)
        }
    }

    func loadStashes(repoID: UUID) async {
        guard let repo = repo(id: repoID), repo.isCloned else {
            stashesByRepo[repoID] = []
            return
        }
        if isDemoMode {
            stashesByRepo[repoID] = []
            return
        }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            stashesByRepo[repoID] = []
            return
        }

        do {
            stashesByRepo[repoID] = try await gitService.listStashes()
        } catch {
            stashesByRepo[repoID] = []
            showError(message: error.localizedDescription)
        }
    }

    func saveStash(repoID: UUID, message: String = "", includeUntracked: Bool = true) async {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        do {
            _ = try await gitService.saveStash(
                message: message,
                authorName: repo.authorName,
                authorEmail: repo.authorEmail,
                includeUntracked: includeUntracked
            )
            detectChanges(repoID: repoID)
            await loadStashes(repoID: repoID)
        } catch {
            showError(message: error.localizedDescription)
        }
    }

    func applyStash(repoID: UUID, index: Int, reinstateIndex: Bool = false) async {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        do {
            _ = try await gitService.applyStash(index: index, reinstateIndex: reinstateIndex)
            detectChanges(repoID: repoID)
            await loadConflictSession(repoID: repoID)
            await loadStashes(repoID: repoID)
        } catch {
            await loadConflictSession(repoID: repoID)
            showError(message: error.localizedDescription)
        }
    }

    func popStash(repoID: UUID, index: Int, reinstateIndex: Bool = false) async {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        do {
            _ = try await gitService.popStash(index: index, reinstateIndex: reinstateIndex)
            detectChanges(repoID: repoID)
            await loadConflictSession(repoID: repoID)
            await loadStashes(repoID: repoID)
        } catch {
            await loadConflictSession(repoID: repoID)
            showError(message: error.localizedDescription)
        }
    }

    func dropStash(repoID: UUID, index: Int) async {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        do {
            try await gitService.dropStash(index: index)
            await loadStashes(repoID: repoID)
        } catch {
            showError(message: error.localizedDescription)
        }
    }

    func loadTags(repoID: UUID) async {
        guard let repo = repo(id: repoID), repo.isCloned else {
            tagsByRepo[repoID] = []
            return
        }
        if isDemoMode {
            tagsByRepo[repoID] = []
            return
        }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            tagsByRepo[repoID] = []
            return
        }

        do {
            tagsByRepo[repoID] = try await gitService.listTags()
        } catch {
            tagsByRepo[repoID] = []
            showError(message: error.localizedDescription)
        }
    }

    func createTag(repoID: UUID, name: String, targetOID: String? = nil, message: String? = nil) async {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        do {
            _ = try await gitService.createTag(
                name: name,
                targetOID: targetOID,
                message: message,
                authorName: repo.authorName,
                authorEmail: repo.authorEmail
            )
            await loadTags(repoID: repoID)
        } catch {
            showError(message: error.localizedDescription)
        }
    }

    func deleteTag(repoID: UUID, name: String) async {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        do {
            try await gitService.deleteTag(name: name)
            await loadTags(repoID: repoID)
        } catch {
            showError(message: error.localizedDescription)
        }
    }

    func pushTag(repoID: UUID, name: String) async {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        do {
            try await gitService.pushTag(name: name, pat: pat)
        } catch {
            showError(message: error.localizedDescription)
        }
    }

    func loadCommitHistory(repoID: UUID, pageSize: Int = 30, reset: Bool = false) async {
        guard let repo = repo(id: repoID), repo.isCloned else {
            commitHistoryByRepo[repoID] = []
            commitHistoryHasMoreByRepo[repoID] = false
            return
        }
        if isDemoMode {
            commitHistoryByRepo[repoID] = []
            commitHistoryHasMoreByRepo[repoID] = false
            return
        }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            commitHistoryByRepo[repoID] = []
            commitHistoryHasMoreByRepo[repoID] = false
            return
        }

        let existing = reset ? [] : (commitHistoryByRepo[repoID] ?? [])
        let skip = existing.count

        do {
            let page = try await gitService.commitHistory(limit: pageSize, skip: skip)
            let merged = reset ? page : (existing + page)
            commitHistoryByRepo[repoID] = merged
            commitHistoryHasMoreByRepo[repoID] = page.count == pageSize
            if reset {
                commitDetailByRepo[repoID] = [:]
            }
        } catch {
            if reset {
                commitHistoryByRepo[repoID] = []
                commitHistoryHasMoreByRepo[repoID] = false
                commitDetailByRepo[repoID] = [:]
            }
            showError(message: error.localizedDescription)
        }
    }

    func loadCommitDetail(repoID: UUID, oid: String) async {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }

        let trimmedOID = oid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedOID.isEmpty else { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else { return }

        do {
            let detail = try await gitService.commitDetail(oid: trimmedOID)
            var existing = commitDetailByRepo[repoID] ?? [:]
            existing[trimmedOID] = detail
            commitDetailByRepo[repoID] = existing
        } catch {
            showError(message: error.localizedDescription)
        }
    }

    func createBranch(repoID: UUID, name: String) async {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        do {
            try await gitService.createBranch(name: name)
            await loadBranches(repoID: repoID)
        } catch {
            showError(message: error.localizedDescription)
        }
    }

    func switchBranch(repoID: UUID, name: String) async {
        guard let idx = repoIndex(id: repoID), repos[idx].isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        isSyncing = true
        syncingRepoID = repoID
        syncProgress = String(localized: "Switching branch...")

        do {
            try await gitService.switchBranch(name: name)
            let info = try await gitService.repoInfo()

            repos[idx].gitState.branch = info.branch
            repos[idx].gitState.commitSHA = info.commitSHA
            saveRepos()
            clearCommitHistoryCache(for: repoID)

            detectChanges(repoID: repoID)
            await loadBranches(repoID: repoID)
        } catch {
            showError(message: error.localizedDescription)
        }

        isSyncing = false
        syncingRepoID = nil
    }

    func deleteBranch(repoID: UUID, name: String) async {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        do {
            try await gitService.deleteBranch(name: name)
            await loadBranches(repoID: repoID)
        } catch {
            showError(message: error.localizedDescription)
        }
    }

    func mergeBranch(repoID: UUID, from branchName: String) async {
        guard let idx = repoIndex(id: repoID), repos[idx].isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        isSyncing = true
        syncingRepoID = repoID
        syncProgress = String(localized: "Merging branch...")

        do {
            let result = try await gitService.mergeBranch(name: branchName)
            repos[idx].gitState.commitSHA = result.newCommitSHA
            repos[idx].gitState.lastSyncDate = Date()
            saveRepos()
            clearCommitHistoryCache(for: repoID)

            detectChanges(repoID: repoID)
            await loadBranches(repoID: repoID)
            await loadConflictSession(repoID: repoID)
        } catch {
            await loadConflictSession(repoID: repoID)
            showError(message: error.localizedDescription)
        }

        isSyncing = false
        syncingRepoID = nil
    }

    func revertCommit(repoID: UUID, oid: String, message: String = "") async {
        guard let idx = repoIndex(id: repoID), repos[idx].isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        isSyncing = true
        syncingRepoID = repoID
        syncProgress = String(localized: "Reverting commit...")

        do {
            let repo = repos[idx]
            let result = try await gitService.revertCommit(
                oid: oid,
                message: message,
                authorName: repo.authorName,
                authorEmail: repo.authorEmail
            )

            switch result.kind {
            case .reverted:
                if let newCommitSHA = result.newCommitSHA {
                    repos[idx].gitState.commitSHA = newCommitSHA
                    repos[idx].gitState.lastSyncDate = Date()
                    saveRepos()
                    clearCommitHistoryCache(for: repoID)
                }
                syncProgress = String(localized: "Revert complete")
            case .conflicts:
                syncProgress = String(localized: "Revert has conflicts")
            }

            detectChanges(repoID: repoID)
            await loadConflictSession(repoID: repoID)
        } catch {
            await loadConflictSession(repoID: repoID)
            showError(message: error.localizedDescription)
        }

        isSyncing = false
        syncingRepoID = nil
    }

    func completeMerge(repoID: UUID, message: String = "") async {
        guard let idx = repoIndex(id: repoID), repos[idx].isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        isSyncing = true
        syncingRepoID = repoID
        syncProgress = String(localized: "Finalizing merge...")

        do {
            let repo = repos[idx]
            let commitMessage = message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? String(localized: "Merge branch")
                : message

            let result = try await gitService.completeMerge(
                message: commitMessage,
                authorName: repo.authorName,
                authorEmail: repo.authorEmail
            )

            repos[idx].gitState.commitSHA = result.newCommitSHA
            repos[idx].gitState.lastSyncDate = Date()
            saveRepos()
            clearCommitHistoryCache(for: repoID)

            detectChanges(repoID: repoID)
            await loadConflictSession(repoID: repoID)
        } catch {
            await loadConflictSession(repoID: repoID)
            showError(message: error.localizedDescription)
        }

        isSyncing = false
        syncingRepoID = nil
    }

    func abortMerge(repoID: UUID) async {
        guard let _ = repoIndex(id: repoID), repo(id: repoID)?.isCloned == true else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        isSyncing = true
        syncingRepoID = repoID
        syncProgress = String(localized: "Aborting merge...")

        do {
            try await gitService.abortMerge()
            clearCommitHistoryCache(for: repoID)
            detectChanges(repoID: repoID)
            await loadConflictSession(repoID: repoID)
        } catch {
            await loadConflictSession(repoID: repoID)
            showError(message: error.localizedDescription)
        }

        isSyncing = false
        syncingRepoID = nil
    }

    func stageFile(repoID: UUID, path: String) async {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        do {
            try await gitService.stage(path: path)
            detectChanges(repoID: repoID)
        } catch {
            showError(message: error.localizedDescription)
        }
    }

    func unstageFile(repoID: UUID, path: String) async {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        do {
            try await gitService.unstage(path: path)
            detectChanges(repoID: repoID)
        } catch {
            showError(message: error.localizedDescription)
        }
    }

    func discardAllFileChanges(repoID: UUID) async {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        do {
            try await gitService.discardAllChanges()
            detectChanges(repoID: repoID)
        } catch {
            showError(message: error.localizedDescription)
        }
    }

    func discardFileChanges(repoID: UUID, path: String) async {
        guard let repo = repo(id: repoID), repo.isCloned else { return }
        if isDemoMode { return }

        let vaultDir = vaultURL(for: repoID)
        let gitService = gitRepositoryFactory(vaultDir)

        guard gitService.hasGitDirectory else {
            showError(message: LocalGitError.notCloned.localizedDescription)
            return
        }

        do {
            try await gitService.discardChanges(path: path)
            detectChanges(repoID: repoID)
        } catch {
            showError(message: error.localizedDescription)
        }
    }

    // MARK: - Git Operations (libgit2)

    func clone(repoID: UUID) async {
        guard let idx = repoIndex(id: repoID) else {
            showError(message: String(localized: "Repository not found"))
            return
        }

        isSyncing = true
        syncingRepoID = repoID
        syncProgress = String(localized: "Preparing to clone...")

        if isDemoMode {
            syncProgress = String(localized: "Cloning repository...")
            try? await Task.sleep(for: .seconds(1.5))
            syncProgress = String(localized: "Clone complete! (%lld files)", defaultValue: "Clone complete! (4 files)")
            try? await Task.sleep(for: .seconds(1))
            isSyncing = false
            syncingRepoID = nil
            return
        }

        do {
            var repo = repos[idx]
            let vaultDir = vaultURL(for: repoID)
            let fm = FileManager.default

            // Remove existing vault directory — git clone needs a clean target
            if fm.fileExists(atPath: vaultDir.path) {
                try fm.removeItem(at: vaultDir)
            }

            // Ensure parent directory exists (git clone creates the target dir itself)
            let parentDir = vaultDir.deletingLastPathComponent()
            try fm.createDirectory(at: parentDir, withIntermediateDirectories: true)

            // Build a clone-friendly URL (append .git if missing)
            var cloneURL = repo.repoURL
            if !cloneURL.hasSuffix(".git") {
                cloneURL += ".git"
            }

            let gitService = gitRepositoryFactory(vaultDir)

            syncProgress = String(localized: "Cloning repository...")
            let result = try await gitService.clone(remoteURL: cloneURL, pat: pat)

            // Update branch from what was actually checked out
            if repo.branch.isEmpty {
                repo.branch = result.branch
            }

            repo.gitState = GitState(
                commitSHA: result.commitSHA,
                treeSHA: "",
                branch: result.branch,
                blobSHAs: [:],
                lastSyncDate: Date()
            )

            repos[idx] = repo
            saveRepos()
            clearCommitHistoryCache(for: repoID)
            detectChanges(repoID: repoID)
            syncProgress = String(localized: "Clone complete! (\(result.fileCount) files)")

            // Request an App Store review after the first successful clone
            let reviewKey = "hasRequestedReview"
            if !UserDefaults.standard.bool(forKey: reviewKey) {
                UserDefaults.standard.set(true, forKey: reviewKey)
                shouldRequestReview = true
            }

        } catch {
            showError(message: error.localizedDescription)
        }

        try? await Task.sleep(for: .seconds(1))
        isSyncing = false
        syncingRepoID = nil
    }

    func pull(repoID: UUID) async {
        guard let idx = repoIndex(id: repoID) else {
            showError(message: String(localized: "Repository not found"))
            return
        }

        isSyncing = true
        syncingRepoID = repoID
        syncProgress = String(localized: "Checking for updates...")

        if isDemoMode {
            try? await Task.sleep(for: .seconds(1))
            syncProgress = String(localized: "Already up to date!")
            repos[idx].gitState.lastSyncDate = Date()
            saveRepos()
            try? await Task.sleep(for: .seconds(1))
            isSyncing = false
            syncingRepoID = nil
            return
        }

        pullOutcomeByRepo.removeValue(forKey: repoID)

        do {
            var repo = repos[idx]
            let vaultDir = vaultURL(for: repoID)
            let gitService = gitRepositoryFactory(vaultDir)

            guard gitService.hasGitDirectory else {
                throw LocalGitError.notCloned
            }

            let plan = try await gitService.pullPlan(pat: pat)

            switch plan.action {
            case .upToDate:
                syncProgress = String(localized: "Already up to date!")
                setPullOutcome(
                    repoID: repoID,
                    kind: .upToDate,
                    message: String(localized: "Already up to date")
                )

            case .blockedByLocalChanges:
                syncProgress = String(localized: "Pull blocked by local changes")
                setPullOutcome(
                    repoID: repoID,
                    kind: .blockedByLocalChanges,
                    message: String(localized: "Local edits detected. Commit, stash, or discard changes before pulling.")
                )

            case .diverged:
                syncProgress = String(localized: "Pull requires merge")
                setPullOutcome(
                    repoID: repoID,
                    kind: .diverged,
                    message: String(localized: "Local and remote have diverged. Merge support is required to continue.")
                )

            case .remoteBranchMissing:
                syncProgress = String(localized: "Remote branch missing")
                setPullOutcome(
                    repoID: repoID,
                    kind: .remoteBranchMissing,
                    message: String(localized: "Remote branch '\(plan.branch)' was not found.")
                )

            case .fastForward:
                syncProgress = String(localized: "Applying remote updates...")
                let result = try await gitService.pull(pat: pat)

                if !result.updated {
                    syncProgress = String(localized: "Already up to date!")
                    setPullOutcome(
                        repoID: repoID,
                        kind: .upToDate,
                        message: String(localized: "Already up to date")
                    )
                } else {
                    repo.gitState.commitSHA = result.newCommitSHA
                    repo.gitState.lastSyncDate = Date()

                    repos[idx] = repo
                    saveRepos()
                    clearCommitHistoryCache(for: repoID)
                    detectChanges(repoID: repoID)
                    syncProgress = String(localized: "Pull complete!")
                    setPullOutcome(
                        repoID: repoID,
                        kind: .fastForwarded,
                        message: String(localized: "Pulled latest changes (fast-forward)")
                    )
                }
            }

        } catch let error as LocalGitError {
            switch error {
            case .pullBlockedByLocalChanges:
                syncProgress = String(localized: "Pull blocked by local changes")
                setPullOutcome(
                    repoID: repoID,
                    kind: .blockedByLocalChanges,
                    message: String(localized: "Local edits detected. Commit, stash, or discard changes before pulling.")
                )
            case .pullDiverged:
                syncProgress = String(localized: "Pull requires merge")
                setPullOutcome(
                    repoID: repoID,
                    kind: .diverged,
                    message: String(localized: "Local and remote have diverged. Merge support is required to continue.")
                )
            case .pullRemoteBranchMissing(let branch):
                syncProgress = String(localized: "Remote branch missing")
                setPullOutcome(
                    repoID: repoID,
                    kind: .remoteBranchMissing,
                    message: String(localized: "Remote branch '\(branch)' was not found.")
                )
            default:
                setPullOutcome(repoID: repoID, kind: .failed, message: error.localizedDescription)
                showError(message: error.localizedDescription)
            }
        } catch {
            setPullOutcome(repoID: repoID, kind: .failed, message: error.localizedDescription)
            showError(message: error.localizedDescription)
        }

        try? await Task.sleep(for: .seconds(1))
        isSyncing = false
        syncingRepoID = nil
    }

    func push(repoID: UUID, message: String) async {
        guard let idx = repoIndex(id: repoID) else {
            showError(message: String(localized: "Repository not found"))
            return
        }

        isSyncing = true
        syncingRepoID = repoID
        syncProgress = String(localized: "Preparing changes...")

        if isDemoMode {
            syncProgress = String(localized: "Committing and pushing...")
            try? await Task.sleep(for: .seconds(1.5))
            repos[idx].gitState.commitSHA = UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(40).lowercased()
            repos[idx].gitState.lastSyncDate = Date()
            saveRepos()
            changeCounts[repoID] = 0
            syncProgress = String(localized: "Push complete!")
            try? await Task.sleep(for: .seconds(1))
            isSyncing = false
            syncingRepoID = nil
            return
        }

        do {
            var repo = repos[idx]
            let vaultDir = vaultURL(for: repoID)
            let gitService = gitRepositoryFactory(vaultDir)

            guard gitService.hasGitDirectory else {
                throw LocalGitError.notCloned
            }

            let commitMsg = message.isEmpty ? String(localized: "Update from Sync.md") : message

            syncProgress = String(localized: "Committing and pushing...")
            let result = try await gitService.commitAndPush(
                message: commitMsg,
                authorName: repo.authorName,
                authorEmail: repo.authorEmail,
                pat: pat
            )

            repo.gitState.commitSHA = result.commitSHA
            repo.gitState.lastSyncDate = Date()

            repos[idx] = repo
            saveRepos()
            clearCommitHistoryCache(for: repoID)
            detectChanges(repoID: repoID)
            syncProgress = String(localized: "Push complete!")

        } catch {
            showError(message: error.localizedDescription)
        }

        try? await Task.sleep(for: .seconds(1))
        isSyncing = false
        syncingRepoID = nil
    }

    // MARK: - Repo Management

    func addRepo(_ config: RepoConfig) {
        repos.append(config)
        saveRepos()
        resolveVaultBookmark(for: config.id)
    }

    /// Add a repository that already exists on the local filesystem.
    /// Reads git metadata from the `.git` directory and creates a RepoConfig
    /// that's immediately in "cloned" state — no network clone needed.
    func addLocalRepo(
        url: URL,
        bookmarkData: Data,
        authorName: String,
        authorEmail: String
    ) async {
        // Resolve the bookmark and start security-scoped access
        var isStale = false
        guard let resolvedURL = try? URL(
            resolvingBookmarkData: bookmarkData,
            options: [],
            relativeTo: nil,
            bookmarkDataIsStale: &isStale
        ) else {
            showError(message: String(localized: "Could not resolve folder bookmark."))
            return
        }

        guard resolvedURL.startAccessingSecurityScopedResource() else {
            showError(message: String(localized: "Could not access the selected folder."))
            return
        }

        let gitService = gitRepositoryFactory(resolvedURL)

        guard gitService.hasGitDirectory else {
            resolvedURL.stopAccessingSecurityScopedResource()
            showError(message: String(localized: "No .git directory found. Please select a folder that contains a git repository."))
            return
        }

        do {
            let info = try await gitService.repoInfo()

            // Try to read the remote URL from the git config
            let remoteURL = Self.readGitRemoteURL(at: resolvedURL) ?? ""

            let config = RepoConfig(
                repoURL: remoteURL,
                branch: info.branch,
                authorName: authorName,
                authorEmail: authorEmail,
                vaultFolderName: resolvedURL.lastPathComponent,
                customVaultBookmarkData: bookmarkData,
                gitState: GitState(
                    commitSHA: info.commitSHA,
                    treeSHA: "",
                    branch: info.branch,
                    blobSHAs: [:],
                    lastSyncDate: Date()
                )
            )

            // Track resolved URL and security scope
            resolvedCustomURLs[config.id] = resolvedURL
            accessingSecurityScope.insert(config.id)

            repos.append(config)
            saveRepos()
            detectChanges(repoID: config.id)
        } catch {
            resolvedURL.stopAccessingSecurityScopedResource()
            showError(message: String(localized: "Failed to read repository info: \(error.localizedDescription)"))
        }
    }

    /// Read the `origin` remote URL from a git repository's config.
    private static func readGitRemoteURL(at repoURL: URL) -> String? {
        let configURL = repoURL.appendingPathComponent(".git/config")
        guard let contents = try? String(contentsOf: configURL, encoding: .utf8) else { return nil }

        // Simple parser: find [remote "origin"] section, then the url = ... line
        let lines = contents.components(separatedBy: .newlines)
        var inOriginSection = false
        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("[remote \"origin\"]") {
                inOriginSection = true
                continue
            }
            if trimmed.hasPrefix("[") {
                inOriginSection = false
                continue
            }
            if inOriginSection && trimmed.hasPrefix("url") {
                let parts = trimmed.split(separator: "=", maxSplits: 1)
                if parts.count == 2 {
                    return parts[1].trimmingCharacters(in: .whitespaces)
                }
            }
        }
        return nil
    }

    func removeRepo(id: UUID) {
        let vaultDir = vaultURL(for: id)
        try? FileManager.default.removeItem(at: vaultDir)
        clearCustomLocation(for: id)
        changeCounts.removeValue(forKey: id)
        statusEntriesByRepo.removeValue(forKey: id)
        syncStateByRepo.removeValue(forKey: id)
        pullOutcomeByRepo.removeValue(forKey: id)
        diffByRepo.removeValue(forKey: id)
        branchesByRepo.removeValue(forKey: id)
        conflictSessionByRepo.removeValue(forKey: id)
        commitHistoryByRepo.removeValue(forKey: id)
        commitHistoryHasMoreByRepo.removeValue(forKey: id)
        commitDetailByRepo.removeValue(forKey: id)
        stashesByRepo.removeValue(forKey: id)
        tagsByRepo.removeValue(forKey: id)
        repos.removeAll { $0.id == id }
        saveRepos()
    }

    func updateRepo(id: UUID, mutate: (inout RepoConfig) -> Void) {
        guard let idx = repoIndex(id: id) else { return }
        mutate(&repos[idx])
        saveRepos()
    }

    // MARK: - OAuth

    func signInWithGitHub() async {
        do {
            let token = try await OAuthService.shared.signIn()
            pat = token
            isSignedIn = true

            syncProgress = String(localized: "Fetching profile...")
            let user = try await GitHubService.fetchUser(token: token)
            gitHubUsername = user.login
            gitHubDisplayName = user.name ?? user.login
            gitHubAvatarURL = user.avatar_url ?? ""
            defaultAuthorName = user.name ?? user.login

            if let email = user.email, !email.isEmpty {
                defaultAuthorEmail = email
            } else {
                defaultAuthorEmail = try await GitHubService.fetchPrimaryEmail(token: token) ?? ""
            }

            isLoadingRepos = true
            gitHubRepos = try await GitHubService.fetchRepos(token: token)
            isLoadingRepos = false

            saveGlobalSettings()
        } catch let oauthError as OAuthError where oauthError.isCancelled {
            // User cancelled — do nothing
        } catch {
            showError(message: error.localizedDescription)
        }
    }

    func signInWithPAT(token: String) async {
        do {
            let user = try await GitHubService.fetchUser(token: token)
            pat = token
            isSignedIn = true
            gitHubUsername = user.login
            gitHubDisplayName = user.name ?? user.login
            gitHubAvatarURL = user.avatar_url ?? ""
            defaultAuthorName = user.name ?? user.login

            if let email = user.email, !email.isEmpty {
                defaultAuthorEmail = email
            } else {
                defaultAuthorEmail = try await GitHubService.fetchPrimaryEmail(token: token) ?? ""
            }

            isLoadingRepos = true
            gitHubRepos = try await GitHubService.fetchRepos(token: token)
            isLoadingRepos = false

            saveGlobalSettings()
        } catch {
            showError(message: String(localized: "Invalid token: \(error.localizedDescription)"))
        }
    }

    func refreshRepos() async {
        let token = pat
        guard !token.isEmpty else { return }
        isLoadingRepos = true
        do {
            gitHubRepos = try await GitHubService.fetchRepos(token: token)
        } catch {
            showError(message: error.localizedDescription)
        }
        isLoadingRepos = false
    }

    func hydrateGitHubProfileIfNeeded() async {
        let token = pat
        guard !token.isEmpty else { return }

        let needsProfile = gitHubUsername.isEmpty
            || gitHubDisplayName.isEmpty
            || defaultAuthorName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || defaultAuthorEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty

        guard needsProfile else { return }

        do {
            let user = try await GitHubService.fetchUser(token: token)

            if gitHubUsername.isEmpty {
                gitHubUsername = user.login
            }
            if gitHubDisplayName.isEmpty {
                gitHubDisplayName = user.name ?? user.login
            }
            if gitHubAvatarURL.isEmpty {
                gitHubAvatarURL = user.avatar_url ?? ""
            }
            if defaultAuthorName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                defaultAuthorName = user.name ?? user.login
            }
            if defaultAuthorEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                if let email = user.email, !email.isEmpty {
                    defaultAuthorEmail = email
                } else if let email = try await GitHubService.fetchPrimaryEmail(token: token), !email.isEmpty {
                    defaultAuthorEmail = email
                }
            }

            saveGlobalSettings()
        } catch {
            // Best-effort hydration for older sessions; keep existing values if unavailable.
        }
    }

    func signOut() {
        if isDemoMode {
            deactivateDemoMode()
            return
        }
        pat = ""
        isSignedIn = false
        gitHubUsername = ""
        gitHubDisplayName = ""
        gitHubAvatarURL = ""
        defaultAuthorName = ""
        defaultAuthorEmail = ""
        gitHubRepos = []
        hasCompletedOnboarding = false
        clearDefaultSaveLocation()
        saveGlobalSettings()
    }

    // MARK: - Pull Outcome State

    private func setPullOutcome(repoID: UUID, kind: PullOutcomeKind, message: String) {
        pullOutcomeByRepo[repoID] = PullOutcomeState(kind: kind, message: message, date: Date())
    }

    private func clearCommitHistoryCache(for repoID: UUID) {
        commitHistoryByRepo.removeValue(forKey: repoID)
        commitHistoryHasMoreByRepo.removeValue(forKey: repoID)
        commitDetailByRepo.removeValue(forKey: repoID)
    }

    // MARK: - Error Handling

    private func showError(message: String) {
        lastError = message
        showError = true
    }

    // MARK: - Demo Mode

    func activateDemoMode() {
        isDemoMode = true
        isSignedIn = true
        gitHubUsername = "demo-user"
        gitHubDisplayName = "Demo User"
        gitHubAvatarURL = ""
        defaultAuthorName = "Demo User"
        defaultAuthorEmail = "demo@example.com"

        // Create a demo repo that appears already cloned with sample content
        let demoRepo = RepoConfig(
            repoURL: "https://github.com/demo-user/my-project.git",
            branch: "main",
            authorName: "Demo User",
            authorEmail: "demo@example.com",
            vaultFolderName: "my-project",
            gitState: GitState(
                commitSHA: "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
                treeSHA: "f6e5d4c3b2a1f6e5d4c3b2a1f6e5d4c3b2a1f6e5",
                branch: "main",
                blobSHAs: [:],
                lastSyncDate: Date()
            )
        )

        repos = [demoRepo]
        saveRepos()
        saveGlobalSettings()

        // Write sample markdown files to the vault directory
        createDemoFiles(for: demoRepo)

        // Set a fake change count so the reviewer can see the push UI
        changeCounts[demoRepo.id] = 2
    }

    func deactivateDemoMode() {
        isDemoMode = false
        signOut()

        // Remove demo repo files
        for repo in repos {
            let vaultDir = vaultURL(for: repo.id)
            try? FileManager.default.removeItem(at: vaultDir)
        }
        repos = []
        changeCounts = [:]
        statusEntriesByRepo = [:]
        syncStateByRepo = [:]
        pullOutcomeByRepo = [:]
        diffByRepo = [:]
        branchesByRepo = [:]
        conflictSessionByRepo = [:]
        commitHistoryByRepo = [:]
        commitHistoryHasMoreByRepo = [:]
        commitDetailByRepo = [:]
        stashesByRepo = [:]
        tagsByRepo = [:]
        saveRepos()
    }

    private func createDemoFiles(for repo: RepoConfig) {
        let vaultDir = repo.defaultVaultURL
        let fm = FileManager.default

        // Create vault directory
        try? fm.createDirectory(at: vaultDir, withIntermediateDirectories: true)

        // Create a fake .git directory so the app considers it cloned
        let gitDir = vaultDir.appendingPathComponent(".git", isDirectory: true)
        try? fm.createDirectory(at: gitDir, withIntermediateDirectories: true)
        // Write a minimal HEAD file
        let headFile = gitDir.appendingPathComponent("HEAD")
        try? "ref: refs/heads/main\n".write(to: headFile, atomically: true, encoding: .utf8)

        let sampleFiles: [(String, String)] = [
            ("README.md", """
            # Welcome to Sync.md 👋

            This is a **demo repository** showing how Sync.md works.

            ## Features
            - 📥 **Pull** — fetch the latest changes from GitHub
            - 📤 **Push** — commit and push your local edits
            - 🔄 **Sync** — keep any repo in sync between your iPhone and GitHub

            ## How It Works
            1. Sign in with GitHub
            2. Pick a repository (or enter any URL)
            3. Clone it to your iPhone
            4. Edit files in the **Files** app or any app that reads from it
            5. Push your changes back to GitHub

            > Files live in the **Files** app under `On My iPhone › Sync.md`
            """),
            ("notes/meeting-2026-02-10.md", """
            # Team Standup — Feb 10, 2026

            - Shipped v1.0 to App Store 🚀
            - Next sprint: collaboration features
            - @alice to investigate conflict resolution

            ## Product Review (Feb 7)
            - Approved new sync indicator design
            - Decided on 3-way merge strategy
            - Launch marketing site by end of month

            ### Action Items
            - [ ] Update onboarding flow
            - [ ] Add pull-to-refresh animation
            - [x] Fix branch detection on clone
            """),
            ("notes/ideas.md", """
            # Ideas & Backlog

            ## 🟢 In Progress
            - iPad split-view support
            - Conflict resolution UI

            ## 🔵 Planned
            - Branch switching
            - Multiple repository support
            - Shared team repositories

            ## 💡 Someday
            - End-to-end encryption option
            - Widget for sync status
            - Shortcuts integration
            """),
            ("config/settings.json", """
            {
              "project": "my-project",
              "version": "1.0.0",
              "sync": {
                "branch": "main",
                "autoCommit": false
              }
            }
            """),
        ]

        for (path, content) in sampleFiles {
            let fileURL = vaultDir.appendingPathComponent(path)
            let dir = fileURL.deletingLastPathComponent()
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
            try? content.write(to: fileURL, atomically: true, encoding: .utf8)
        }
    }
}

// MARK: - Callback Result State

/// Displayed briefly in the UI after a callback operation completes,
/// before redirecting back to the calling app.
struct CallbackResultState: Equatable {
    let repoID: UUID
    let action: String
    let isSuccess: Bool
    let message: String
}

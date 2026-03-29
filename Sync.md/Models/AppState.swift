import Foundation
import SwiftUI

// MARK: - App State

@Observable
final class AppState {

    // MARK: - Repositories

    var repos: [RepoConfig] = []
    var changeCounts: [UUID: Int] = [:]

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

    // MARK: - Init

    init() {
        loadState()
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
            let gitService = LocalGitService(localURL: vaultDir)

            if !gitService.hasGitDirectory {
                repos[index].gitState = .empty
                changeCounts[repo.id] = 0
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
        let gitService = LocalGitService(localURL: vaultDir)

        guard gitService.hasGitDirectory else {
            // .git directory was removed — reset cloned state
            if let idx = repoIndex(id: repoID) {
                repos[idx].gitState = .empty
                saveRepos()
            }
            changeCounts[repoID] = 0
            return
        }

        Task {
            do {
                let info = try await gitService.repoInfo()
                changeCounts[repoID] = info.changeCount
            } catch {
                changeCounts[repoID] = 0
            }
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

            let gitService = LocalGitService(localURL: vaultDir)

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
            detectChanges(repoID: repoID)
            syncProgress = String(localized: "Clone complete! (\(result.fileCount) files)")

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

        do {
            var repo = repos[idx]
            let vaultDir = vaultURL(for: repoID)
            let gitService = LocalGitService(localURL: vaultDir)

            guard gitService.hasGitDirectory else {
                throw LocalGitError.notCloned
            }

            let result = try await gitService.pull(pat: pat)

            if !result.updated {
                syncProgress = String(localized: "Already up to date!")
            } else {
                repo.gitState.commitSHA = result.newCommitSHA
                repo.gitState.lastSyncDate = Date()

                repos[idx] = repo
                saveRepos()
                detectChanges(repoID: repoID)
                syncProgress = String(localized: "Pull complete!")
            }

        } catch {
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
            let gitService = LocalGitService(localURL: vaultDir)

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

        let gitService = LocalGitService(localURL: resolvedURL)

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
            repoURL: "https://github.com/demo-user/my-notes.git",
            branch: "main",
            authorName: "Demo User",
            authorEmail: "demo@example.com",
            vaultFolderName: "my-notes",
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
            ("Welcome.md", """
            # Welcome to Sync.md 👋

            This is a **demo vault** showing how Sync.md works.

            ## Features
            - 📥 **Pull** — fetch changes from your GitHub repo
            - 📤 **Push** — commit and push local edits
            - 🔄 **Sync** — keep markdown notes in sync across devices

            ## How It Works
            1. Sign in with GitHub
            2. Pick a repository (or create one)
            3. Clone it to your device
            4. Edit files with any markdown editor (e.g. Obsidian)
            5. Push your changes back to GitHub

            > Your notes live in the **Files** app under `Sync.md/`
            """),
            ("Meeting Notes.md", """
            # Meeting Notes — Feb 2026

            ## Team Standup (Feb 10)
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
            ("Ideas.md", """
            # Ideas & Backlog

            ## 🟢 In Progress
            - Obsidian plugin for one-tap sync
            - iPad split-view support

            ## 🔵 Planned
            - Conflict resolution UI
            - Branch switching
            - Multiple vault support
            - Shared team repositories

            ## 💡 Someday
            - End-to-end encryption option
            - Markdown preview built-in
            - Widget for sync status
            """),
            ("Journal/2026-02-11.md", """
            # February 11, 2026

            Today I'm trying out **Sync.md** to keep my notes backed up on GitHub.

            The setup was simple:
            1. Signed in with GitHub
            2. Selected my `my-notes` repo
            3. Cloned — all my files appeared instantly

            Now I can edit in Obsidian and push changes whenever I'm ready. 🎉
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

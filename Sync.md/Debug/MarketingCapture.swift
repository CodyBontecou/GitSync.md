#if DEBUG
import SwiftUI
import UIKit
import Clibgit2
import libgit2

// MARK: - Core Utilities

enum MarketingCapture {

    // MARK: Launch argument

    static var isActive: Bool {
        ProcessInfo.processInfo.arguments.contains("-MarketingCapture") &&
        value(for: "-MarketingCapture") == "1"
    }

    static var isFileBrowserUITestActive: Bool {
        ProcessInfo.processInfo.arguments.contains("-FileBrowserUITest")
    }

    /// Issue #19 clone-flow slice: seeds a credential-free bare git remote under
    /// the shared simulator temp area (`/tmp/syncmd-uitest-fixtures`) so the
    /// AddRepoView → manual URL → clone flow can run against a real local
    /// remote with `none` credentials — no network, no GitHub.
    static var isUITestCloneFixtureActive: Bool {
        ProcessInfo.processInfo.arguments.contains("-UITestCloneFixture")
    }

    /// Issue #19 conflict-resolver slice: seeds a real working copy whose local
    /// commit has diverged from its local bare remote, so an in-app pull
    /// reports divergence and an in-app merge produces a genuine conflict
    /// session for the ConflictEditorView.
    static var isUITestConflictFixtureActive: Bool {
        ProcessInfo.processInfo.arguments.contains("-UITestConflictFixture")
    }

    static var usesSeededData: Bool {
        isActive || isFileBrowserUITestActive
            || isUITestCloneFixtureActive || isUITestConflictFixtureActive
    }

    private static func value(for key: String) -> String? {
        let args = ProcessInfo.processInfo.arguments
        guard let idx = args.firstIndex(of: key), idx + 1 < args.count else { return nil }
        return args[idx + 1]
    }

    // MARK: Locale

    static var localeFolder: String {
        if let explicit = value(for: "-MarketingLocale") {
            return explicit
        }
        return Locale.current.language.languageCode?.identifier
            ?? Locale.current.identifier
    }

    static var formFactor: String {
        value(for: "-MarketingFormFactor") ?? "iphone"
    }

    static var captureLimit: Int {
        formFactor == "ipad" ? 4 : 10
    }

    // MARK: Notifications

    static let dismissSheetNotification = Notification.Name("MarketingCapture.dismissSheet")
    static let showGitSheetNotification = Notification.Name("MarketingCapture.showGitSheet")
    static let showSettingsNotification = Notification.Name("MarketingCapture.showSettings")

    // MARK: Output

    static var outputRoot: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let root = docs
            .appendingPathComponent("marketing", isDirectory: true)
            .appendingPathComponent(formFactor, isDirectory: true)
            .appendingPathComponent(localeFolder, isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }

    static func writePNG(_ image: UIImage, name: String) {
        let url = outputRoot.appendingPathComponent("\(name).png")
        guard let data = image.pngData() else {
            print("[MarketingCapture] failed to encode \(name)")
            return
        }
        do {
            try data.write(to: url, options: .atomic)
            print("[MarketingCapture] wrote \(url.path)")
        } catch {
            print("[MarketingCapture] write failed: \(error)")
        }
    }

    static func writeSentinel() {
        let url = outputRoot.appendingPathComponent("_done")
        try? Data().write(to: url)
    }

    // MARK: Window snapshot

    @MainActor
    static func snapshotKeyWindow() -> UIImage? {
        guard let window = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .flatMap({ $0.windows })
            .first(where: { $0.isKeyWindow })
        else { return nil }

        let renderer = UIGraphicsImageRenderer(bounds: window.bounds)
        return renderer.image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
    }
}

// MARK: - Capture Step

struct CaptureStep {
    let name: String
    let navigate: @MainActor () -> Void
    let settle: Duration
    let cleanup: (@MainActor () -> Void)?

    init(
        name: String,
        settle: Duration = .milliseconds(1800),
        navigate: @escaping @MainActor () -> Void,
        cleanup: (@MainActor () -> Void)? = nil
    ) {
        self.name = name
        self.navigate = navigate
        self.settle = settle
        self.cleanup = cleanup
    }
}

// MARK: - Coordinator

@MainActor
final class MarketingCaptureCoordinator {
    static let shared = MarketingCaptureCoordinator()
    private init() {}

    var hasStarted = false

    func run(steps: [CaptureStep]) async {
        print("[MarketingCapture] run for locale=\(MarketingCapture.localeFolder)")

        for step in steps {
            step.navigate()
            try? await Task.sleep(for: step.settle)

            guard let image = MarketingCapture.snapshotKeyWindow() else {
                print("[MarketingCapture] snapshot failed: \(step.name)")
                continue
            }
            MarketingCapture.writePNG(image, name: step.name)

            if let cleanup = step.cleanup {
                cleanup()
                try? await Task.sleep(for: .milliseconds(900))
            }
        }

        MarketingCapture.writeSentinel()
        print("[MarketingCapture] done for locale=\(MarketingCapture.localeFolder)")
    }
}

// MARK: - Demo Data Seeder

enum MarketingDemoSeeder {

    static func seed(into state: AppState) {
        // UI-test local-git fixture modes (Issue #19 clone-flow and
        // conflict-resolver slices). These build REAL on-disk git fixtures and
        // replace the marketing demo state entirely.
        if MarketingCapture.isUITestCloneFixtureActive {
            seedUITestCloneFixture(into: state)
            return
        }
        if MarketingCapture.isUITestConflictFixtureActive {
            seedUITestConflictFixture(into: state)
            return
        }

        // Auth state
        state.isSignedIn = true
        state.hasCompletedOnboarding = true
        state.hasSeenOnboarding = true
        state.gitHubUsername = "sample-developer"
        state.gitHubDisplayName = "Sample Developer"
        state.gitHubAvatarURL = ""
        state.defaultAuthorName = "Sample Developer"
        state.defaultAuthorEmail = "developer@example.com"
        state.isDemoMode = false

        // --- Repos ---

        let primaryVaultFolderName = MarketingCapture.isFileBrowserUITestActive
            ? "file-browser-ui-test"
            : "second-brain"
        let repo1 = RepoConfig(
            repoURL: "https://github.com/example/second-brain.git",
            branch: "main",
            authorName: "Sample Developer",
            authorEmail: "developer@example.com",
            vaultFolderName: primaryVaultFolderName,
            gitState: GitState(
                commitSHA: "a3f8c1d4e7b2a5f8c1d4e7b2a5f8c1d4e7b2a5f8",
                treeSHA: "b2a5f8c1d4e7b2a5f8c1d4e7b2a5f8c1d4e7b2a5",
                branch: "main",
                blobSHAs: [
                    "README.md": "abc123",
                    "inbox/new-idea.md": "def456",
                    "projects/app-launch.md": "ghi789",
                    "notes/meeting-notes.md": "jkl012",
                    "archive/old-draft.md": "mno345",
                    "templates/daily.md": "pqr678",
                    "references/bookmarks.md": "stu901",
                ],
                lastSyncDate: Date().addingTimeInterval(-180)
            )
        )

        let repo2 = RepoConfig(
            repoURL: "https://github.com/example/engineering-docs.git",
            branch: "main",
            authorName: "Sample Developer",
            authorEmail: "developer@example.com",
            vaultFolderName: "engineering-docs",
            gitState: GitState(
                commitSHA: "c7d9e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6",
                treeSHA: "d2e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4c6d8e0",
                branch: "main",
                blobSHAs: [
                    "README.md": "xyz123",
                    "api/endpoints.md": "xyz456",
                ],
                lastSyncDate: Date().addingTimeInterval(-3600)
            )
        )

        let repo3 = RepoConfig(
            repoURL: "https://github.com/example-team/team-wiki.git",
            branch: "main",
            authorName: "Sample Developer",
            authorEmail: "developer@example.com",
            vaultFolderName: "team-wiki",
            gitState: GitState(
                commitSHA: "e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4c6d8e0f2",
                treeSHA: "f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4c6d8e0f2a4",
                branch: "main",
                blobSHAs: [
                    "README.md": "aaa111",
                    "onboarding/guide.md": "bbb222",
                ],
                lastSyncDate: Date().addingTimeInterval(-300)
            )
        )

        state.repos = [repo1, repo2, repo3]

        // --- Per-repo runtime state ---

        let r1 = repo1.id
        let r2 = repo2.id
        let r3 = repo3.id

        state.changeCounts = [r1: 4, r2: 1, r3: 0]

        state.syncStateByRepo = [
            r1: .ahead,
            r2: .behind,
            r3: .upToDate,
        ]

        // Status entries
        state.statusEntriesByRepo[r1] = [
            GitStatusEntry(path: "inbox/new-idea.md", indexStatus: nil, workTreeStatus: .untracked),
            GitStatusEntry(path: "projects/app-launch.md", indexStatus: .modified, workTreeStatus: nil),
            GitStatusEntry(path: "notes/meeting-notes.md", indexStatus: nil, workTreeStatus: .modified),
            GitStatusEntry(path: "archive/old-draft.md", indexStatus: .deleted, workTreeStatus: nil),
        ]

        state.statusEntriesByRepo[r2] = [
            GitStatusEntry(path: "api/endpoints.md", indexStatus: nil, workTreeStatus: .modified),
        ]

        // Branches for repo1
        state.branchesByRepo[r1] = BranchInventory(
            local: [
                GitBranchInfo(
                    name: "refs/heads/main", shortName: "main",
                    scope: .local, isCurrent: true,
                    upstreamShortName: "origin/main",
                    aheadBy: 2, behindBy: 0
                ),
                GitBranchInfo(
                    name: "refs/heads/feature/templates", shortName: "feature/templates",
                    scope: .local, isCurrent: false,
                    upstreamShortName: nil,
                    aheadBy: nil, behindBy: nil
                ),
                GitBranchInfo(
                    name: "refs/heads/fix/sync-conflict", shortName: "fix/sync-conflict",
                    scope: .local, isCurrent: false,
                    upstreamShortName: "origin/fix/sync-conflict",
                    aheadBy: 1, behindBy: 0
                ),
            ],
            remote: [
                GitBranchInfo(
                    name: "refs/remotes/origin/main", shortName: "origin/main",
                    scope: .remote, isCurrent: false,
                    upstreamShortName: nil,
                    aheadBy: nil, behindBy: nil
                ),
            ],
            detachedHeadOID: nil
        )

        // Stashes for repo1
        state.stashesByRepo[r1] = [
            GitStashEntry(index: 0, oid: "abc123def456", message: "WIP: reorganize project templates"),
        ]

        // Tags for repo1
        state.tagsByRepo[r1] = [
            GitTag(
                name: "refs/tags/v1.0.0", oid: "aaa111bbb222",
                kind: .annotated, message: "Initial release",
                targetOID: "a3f8c1d4e7b2a5f8"
            ),
            GitTag(
                name: "refs/tags/v1.1.0", oid: "ccc333ddd444",
                kind: .lightweight, message: nil,
                targetOID: "c7d9e2f4a6b8c0d2"
            ),
        ]

        // Diff for repo1 — realistic markdown checklist update
        let patchText = [
            "diff --git a/projects/app-launch.md b/projects/app-launch.md",
            "index a1b2c3d..e5f6a7b 100644",
            "--- a/projects/app-launch.md",
            "+++ b/projects/app-launch.md",
            "@@ -1,12 +1,16 @@",
            " # App Launch Checklist",
            " ",
            "-## Status: Planning",
            "+## Status: In Progress",
            " ",
            " ### Pre-Launch",
            "-- [ ] Finalize landing page copy",
            "-- [ ] Set up analytics dashboard",
            "+- [x] Finalize landing page copy",
            "+- [x] Set up analytics dashboard",
            "+- [x] Configure CI/CD pipeline",
            " - [ ] Write press kit",
            "+- [ ] Submit to App Store",
            " ",
            " ### Post-Launch",
            " - [ ] Monitor crash reports",
            " - [ ] Gather user feedback",
            "+- [ ] Plan v1.1 features",
            "+- [ ] Write changelog",
        ].joined(separator: "\n")

        state.diffByRepo[r1] = UnifiedDiffResult(
            files: [
                GitFileDiff(
                    path: "projects/app-launch.md",
                    oldPath: nil,
                    newPath: nil,
                    changeType: .modified,
                    isBinary: false,
                    patch: patchText
                ),
            ],
            rawPatch: patchText
        )

        // Public, fictional files for deterministic file-browser/editor shots.
        let fileManager = FileManager.default
        let vaultDirectory = repo1.defaultVaultURL
        let gitDirectory = vaultDirectory.appendingPathComponent(".git", isDirectory: true)
        let projectDirectory = vaultDirectory.appendingPathComponent("projects", isDirectory: true)
        let notesDirectory = vaultDirectory.appendingPathComponent("notes", isDirectory: true)
        let deepDirectory = projectDirectory.appendingPathComponent("mobile/client/screens", isDirectory: true)

        do {
            if MarketingCapture.isFileBrowserUITestActive,
               fileManager.fileExists(atPath: vaultDirectory.path) {
                try fileManager.removeItem(at: vaultDirectory)
            }
            try fileManager.createDirectory(at: gitDirectory, withIntermediateDirectories: true)
            try fileManager.createDirectory(at: projectDirectory, withIntermediateDirectories: true)
            try fileManager.createDirectory(at: notesDirectory, withIntermediateDirectories: true)
            try fileManager.createDirectory(at: deepDirectory, withIntermediateDirectories: true)
            try "# App Launch\n\n## Status: In Progress\n\n- [x] Configure CI/CD\n- [ ] Submit to App Store\n"
                .write(
                    to: projectDirectory.appendingPathComponent("app-launch.md"),
                    atomically: true,
                    encoding: .utf8
                )
            try "# Deep Note\n\nNested file-browser regression fixture.\n"
                .write(
                    to: deepDirectory.appendingPathComponent("deep-note.md"),
                    atomically: true,
                    encoding: .utf8
                )
            try "# Meeting Notes\n\nSample notes for the GitSync.md screenshot demo.\n"
                .write(
                    to: notesDirectory.appendingPathComponent("meeting-notes.md"),
                    atomically: true,
                    encoding: .utf8
                )
        } catch {
            assertionFailure("Could not create seeded file-browser data: \(error)")
        }
    }

    // MARK: - UI-Test Local Git Fixtures (Issue #19)

    /// Signed-out, onboarded, empty repo list plus a real bare git remote on
    /// disk. The UI test walks AddRepoView → manual URL entry → clone against
    /// the local `file://` remote with `none` credentials. Author defaults are
    /// seeded so the Add form is submittable without typing identity fields.
    private static func seedUITestCloneFixture(into state: AppState) {
        do {
            try UITestGitFixtures.resetFixtureArea()
            try UITestGitFixtures.commitToBare(
                atPath: UITestGitFixtures.cloneBareRemotePath,
                files: [
                    "README.md": Data("# Bare Remote\n\nCredential-free local clone fixture for UI tests.\n".utf8),
                    "notes/hello.md": Data("# Hello\n\nSeeded inside the bare remote.\n".utf8),
                ],
                message: "Base commit for clone UI test"
            )
        } catch {
            assertionFailure("UITest clone fixture seeding failed: \(error)")
        }

        state.isSignedIn = false
        state.hasCompletedOnboarding = true
        state.hasSeenOnboarding = true
        state.gitHubUsername = ""
        state.gitHubDisplayName = ""
        state.gitHubAvatarURL = ""
        state.defaultAuthorName = "Sample Developer"
        state.defaultAuthorEmail = "developer@example.com"
        state.isDemoMode = false
        state.repos = []
    }

    /// One seeded repo whose vault is a real git working copy with a local
    /// commit ("ours") diverged from its local bare remote ("theirs"). A pull
    /// classifies as diverged; merging produces a genuine conflict session in
    /// `notes/shared.md` for the ConflictEditorView flow.
    private static func seedUITestConflictFixture(into state: AppState) {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let vaultPath = documents
            .appendingPathComponent(UITestGitFixtures.conflictVaultFolderName, isDirectory: true)
            .path

        var headSHA: String?
        do {
            try UITestGitFixtures.resetFixtureArea()

            // 1. Base state on the bare remote.
            try UITestGitFixtures.commitToBare(
                atPath: UITestGitFixtures.conflictBareRemotePath,
                files: UITestGitFixtures.conflictFiles(base: true),
                message: "Base state"
            )

            // 2. Clone the working copy (origin/main = base).
            try UITestGitFixtures.clone(
                fromBareAtPath: UITestGitFixtures.conflictBareRemotePath,
                toWorktreeAtPath: vaultPath
            )

            // 3. Advance the remote (theirs) — fixture construction commits
            //    directly into the local bare repo; nothing is pushed.
            try UITestGitFixtures.commitToBare(
                atPath: UITestGitFixtures.conflictBareRemotePath,
                files: UITestGitFixtures.conflictFiles(base: false, ours: false),
                message: "Remote edit to shared note"
            )

            // 4. Local commit (ours) in the working copy.
            try UITestGitFixtures.commitWorktreeFiles(
                atPath: vaultPath,
                files: UITestGitFixtures.conflictFiles(base: false, ours: true),
                message: "Local edit to shared note"
            )

            // 5. Fetch so origin/main reflects the remote side (local transport).
            try UITestGitFixtures.fetchOrigin(atPath: vaultPath)

            headSHA = try UITestGitFixtures.headHexSHA(atPath: vaultPath)
        } catch {
            assertionFailure("UITest conflict fixture seeding failed: \(error)")
        }

        state.isSignedIn = false
        state.hasCompletedOnboarding = true
        state.hasSeenOnboarding = true
        state.gitHubUsername = ""
        state.gitHubDisplayName = ""
        state.gitHubAvatarURL = ""
        state.defaultAuthorName = "Sample Developer"
        state.defaultAuthorEmail = "developer@example.com"
        state.isDemoMode = false

        guard let headSHA else { return }
        let repo = RepoConfig(
            repoURL: UITestGitFixtures.conflictRemoteFileURL,
            branch: "main",
            authorName: "Sample Developer",
            authorEmail: "developer@example.com",
            vaultFolderName: UITestGitFixtures.conflictVaultFolderName,
            authMethod: .none,
            gitState: GitState(
                commitSHA: headSHA,
                treeSHA: "",
                branch: "main",
                blobSHAs: [:],
                lastSyncDate: Date().addingTimeInterval(-90)
            )
        )
        state.repos = [repo]
        state.syncStateByRepo = [repo.id: .diverged]
    }
}

// MARK: - Local libgit2 Fixture Builder

/// Builds the real on-disk git fixtures for the Issue #19 clone-flow and
/// conflict-resolver UI tests. Everything lives in the simulator's shared
/// temp area (`/tmp/syncmd-uitest-fixtures`), is rebuilt on every launch, and
/// involves no network, no credentials, and no pushes — the "remote" commits
/// are constructed directly in the local bare repository.
enum UITestGitFixtures {

    /// Simulator processes (app and UI-test runner) share the host `/tmp`, so
    /// a fixed absolute path is addressable from both sides.
    static let rootPath = "/tmp/syncmd-uitest-fixtures"

    static var cloneBareRemotePath: String { rootPath + "/bare-remote.git" }
    static var conflictBareRemotePath: String { rootPath + "/conflict-fixture.git" }

    /// The `file://` URL the UI test types into AddRepoView's manual entry.
    static let cloneRemoteFileURL = "file:///tmp/syncmd-uitest-fixtures/bare-remote.git"
    static let conflictRemoteFileURL = "file:///tmp/syncmd-uitest-fixtures/conflict-fixture.git"

    /// Vault folder (inside the app's Documents) for the conflict working copy.
    static let conflictVaultFolderName = "conflict-fixture"

    static let conflictedPath = "notes/shared.md"

    static func conflictFiles(base: Bool, ours: Bool = false) -> [String: Data] {
        let shared: String
        if base {
            shared = "# Shared Notes\n\nBase version.\n"
        } else if ours {
            shared = "# Shared Notes\n\nOurs version — local commit.\n"
        } else {
            shared = "# Shared Notes\n\nTheirs version — remote commit.\n"
        }
        return [
            "README.md": Data("# Conflict Fixture\n\nDiverged local/remote fixture for the conflict resolver UI test.\n".utf8),
            conflictedPath: Data(shared.utf8),
        ]
    }

    // MARK: - Fixture Area

    static func resetFixtureArea() throws {
        let fm = FileManager.default
        if fm.fileExists(atPath: rootPath) {
            try fm.removeItem(atPath: rootPath)
        }
        try fm.createDirectory(atPath: rootPath, withIntermediateDirectories: true)
        git_libgit2_init()
    }

    // MARK: - Bare Remote Construction

    /// Creates (or advances) a bare repository at `path`, committing `files`
    /// as a full-tree snapshot onto `refs/heads/main` and pointing HEAD at it.
    @discardableResult
    static func commitToBare(
        atPath path: String,
        files: [String: Data],
        message: String
    ) throws -> String {
        let fm = FileManager.default
        if !fm.fileExists(atPath: path) {
            try fm.createDirectory(atPath: path, withIntermediateDirectories: true)
            var repo: OpaquePointer?
            defer { if let repo { git_repository_free(repo) } }
            try check(git_repository_init(&repo, path, 1), "init bare \(path)")
        }

        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        try check(git_repository_open(&repo, path), "open bare \(path)")

        let treeID = try buildTree(repo: repo, files: files)
        var tree: OpaquePointer?
        defer { if let tree { git_tree_free(tree) } }
        var treeIDCopy = treeID
        try check(git_tree_lookup(&tree, repo, &treeIDCopy), "lookup tree")

        var sig: UnsafeMutablePointer<git_signature>?
        defer { if let sig { git_signature_free(sig) } }
        try check(git_signature_now(&sig, "UITest Seeder", "uitest@syncmd.example"), "signature")

        // Parent: the ref's current tip, when it exists.
        var parentCommit: OpaquePointer?
        defer { if let parentCommit { git_commit_free(parentCommit) } }
        var ref: OpaquePointer?
        defer { if let ref { git_reference_free(ref) } }
        let lookup = git_reference_lookup(&ref, repo, "refs/heads/main")
        if lookup == 0, let target = git_reference_target(ref) {
            var targetCopy = target.pointee
            try check(git_commit_lookup(&parentCommit, repo, &targetCopy), "lookup parent")
        }

        var commitID = git_oid()
        if let parentCommit {
            var parents: [OpaquePointer?] = [parentCommit]
            try parents.withUnsafeMutableBufferPointer { buffer in
                try check(
                    git_commit_create(&commitID, repo, "refs/heads/main", sig, sig, nil, message, tree, 1, buffer.baseAddress),
                    "commit \(message)"
                )
            }
        } else {
            try check(
                git_commit_create(&commitID, repo, "refs/heads/main", sig, sig, nil, message, tree, 0, nil),
                "initial commit \(message)"
            )
        }

        // Point HEAD at main so clones default to the seeded branch.
        try check(git_repository_set_head(repo, "refs/heads/main"), "set HEAD")
        return hex(commitID)
    }

    // MARK: - Working Copy Construction

    /// Clones the bare repository into a fresh working copy at `path`.
    static func clone(fromBareAtPath barePath: String, toWorktreeAtPath path: String) throws {
        let fm = FileManager.default
        if fm.fileExists(atPath: path) {
            try fm.removeItem(atPath: path)
        }
        try fm.createDirectory(
            atPath: (path as NSString).deletingLastPathComponent,
            withIntermediateDirectories: true
        )

        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        var opts = git_clone_options()
        git_clone_options_init(&opts, UInt32(GIT_CLONE_OPTIONS_VERSION))
        try check(git_clone(&repo, barePath, path, &opts), "clone \(barePath)")
    }

    /// Writes `files` into the working copy, stages, and commits on HEAD.
    @discardableResult
    static func commitWorktreeFiles(atPath path: String, files: [String: Data], message: String) throws -> String {
        let fm = FileManager.default
        let root = URL(fileURLWithPath: path, isDirectory: true)
        for (relativePath, data) in files {
            let fileURL = root.appendingPathComponent(relativePath)
            try fm.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try data.write(to: fileURL)
        }

        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        try check(git_repository_open(&repo, path), "open worktree \(path)")

        var index: OpaquePointer?
        defer { if let index { git_index_free(index) } }
        try check(git_repository_index(&index, repo), "read index")
        for relativePath in files.keys {
            try check(git_index_add_bypath(index, relativePath), "stage \(relativePath)")
        }
        try check(git_index_write(index), "write index")

        var treeID = git_oid()
        try check(git_index_write_tree(&treeID, index), "write tree")
        var tree: OpaquePointer?
        defer { if let tree { git_tree_free(tree) } }
        try check(git_tree_lookup(&tree, repo, &treeID), "lookup tree")

        var headRef: OpaquePointer?
        defer { if let headRef { git_reference_free(headRef) } }
        try check(git_repository_head(&headRef, repo), "read HEAD")
        guard let headTarget = git_reference_target(headRef) else {
            throw NSError(
                domain: "UITestGitFixtures",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Working copy HEAD is unborn"]
            )
        }
        var parentCommit: OpaquePointer?
        defer { if let parentCommit { git_commit_free(parentCommit) } }
        var headCopy = headTarget.pointee
        try check(git_commit_lookup(&parentCommit, repo, &headCopy), "lookup HEAD commit")

        var sig: UnsafeMutablePointer<git_signature>?
        defer { if let sig { git_signature_free(sig) } }
        try check(git_signature_now(&sig, "UITest Seeder", "uitest@syncmd.example"), "signature")

        var commitID = git_oid()
        var parents: [OpaquePointer?] = [parentCommit]
        try parents.withUnsafeMutableBufferPointer { buffer in
            try check(
                git_commit_create(&commitID, repo, "HEAD", sig, sig, nil, message, tree, 1, buffer.baseAddress),
                "worktree commit \(message)"
            )
        }
        return hex(commitID)
    }

    /// Fetches `origin` (local file transport — no credentials involved) so
    /// `refs/remotes/origin/main` reflects the advanced bare remote.
    static func fetchOrigin(atPath path: String) throws {
        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        try check(git_repository_open(&repo, path), "open worktree \(path)")

        var remote: OpaquePointer?
        defer { if let remote { git_remote_free(remote) } }
        try check(git_remote_lookup(&remote, repo, "origin"), "lookup origin")

        var opts = git_fetch_options()
        git_fetch_options_init(&opts, UInt32(GIT_FETCH_OPTIONS_VERSION))
        try check(git_remote_fetch(remote, nil, &opts, nil), "fetch origin")
    }

    /// HEAD commit hex SHA of the repository at `path`.
    static func headHexSHA(atPath path: String) throws -> String {
        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        try check(git_repository_open(&repo, path), "open \(path)")

        var headRef: OpaquePointer?
        defer { if let headRef { git_reference_free(headRef) } }
        try check(git_repository_head(&headRef, repo), "read HEAD")
        guard let target = git_reference_target(headRef) else {
            throw NSError(
                domain: "UITestGitFixtures",
                code: 2,
                userInfo: [NSLocalizedDescriptionKey: "HEAD has no target"]
            )
        }
        return hex(target.pointee)
    }

    // MARK: - libgit2 Helpers

    private static func check(_ code: Int32, _ context: String) throws {
        guard code >= 0 else {
            throw NSError(
                domain: "UITestGitFixtures",
                code: Int(code),
                userInfo: [NSLocalizedDescriptionKey: "\(context) failed (libgit2 code \(code))"]
            )
        }
    }

    private static func hex(_ oid: git_oid) -> String {
        var copy = oid
        guard let cString = git_oid_tostr_s(&copy) else { return "" }
        return String(cString: cString)
    }

    /// Recursively builds a tree from `files` (full relative path → content).
    private static func buildTree(repo: OpaquePointer?, files: [String: Data]) throws -> git_oid {
        var builder: OpaquePointer?
        defer { if let builder { git_treebuilder_free(builder) } }
        try check(git_treebuilder_new(&builder, repo, nil), "treebuilder")

        var direct: [(name: String, data: Data)] = []
        var subdirectories: [String: [String: Data]] = [:]
        for (path, data) in files {
            let components = path.split(separator: "/").map(String.init)
            if components.count == 1 {
                direct.append((components[0], data))
            } else {
                let dir = components[0]
                let rest = components.dropFirst().joined(separator: "/")
                subdirectories[dir, default: [:]][rest] = data
            }
        }

        for (name, data) in direct {
            var blobID = git_oid()
            let written = data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Int32 in
                git_blob_create_from_buffer(&blobID, repo, raw.baseAddress, raw.count)
            }
            try check(written, "blob \(name)")
            var id = blobID
            try check(
                git_treebuilder_insert(nil, builder, name, &id, GIT_FILEMODE_BLOB),
                "insert blob \(name)"
            )
        }

        for (dir, contents) in subdirectories {
            let subID = try buildTree(repo: repo, files: contents)
            var id = subID
            try check(
                git_treebuilder_insert(nil, builder, dir, &id, GIT_FILEMODE_TREE),
                "insert tree \(dir)"
            )
        }

        var treeID = git_oid()
        try check(git_treebuilder_write(&treeID, builder), "write tree")
        return treeID
    }
}
#endif

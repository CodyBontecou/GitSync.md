#if DEBUG
import SwiftUI
import UIKit

// MARK: - Core Utilities

enum MarketingCapture {

    // MARK: Launch argument

    static var isActive: Bool {
        ProcessInfo.processInfo.arguments.contains("-MarketingCapture") &&
        value(for: "-MarketingCapture") == "1"
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

        let repo1 = RepoConfig(
            repoURL: "https://github.com/example/second-brain.git",
            branch: "main",
            authorName: "Sample Developer",
            authorEmail: "developer@example.com",
            vaultFolderName: "second-brain",
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
        let projectDirectory = repo1.defaultVaultURL.appendingPathComponent("projects", isDirectory: true)
        let notesDirectory = repo1.defaultVaultURL.appendingPathComponent("notes", isDirectory: true)
        try? FileManager.default.createDirectory(at: projectDirectory, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: notesDirectory, withIntermediateDirectories: true)
        try? "# App Launch\n\n## Status: In Progress\n\n- [x] Configure CI/CD\n- [ ] Submit to App Store\n"
            .write(
                to: projectDirectory.appendingPathComponent("app-launch.md"),
                atomically: true,
                encoding: .utf8
            )
        try? "# Meeting Notes\n\nSample notes for the GitSync.md screenshot demo.\n"
            .write(
                to: notesDirectory.appendingPathComponent("meeting-notes.md"),
                atomically: true,
                encoding: .utf8
            )
    }
}
#endif

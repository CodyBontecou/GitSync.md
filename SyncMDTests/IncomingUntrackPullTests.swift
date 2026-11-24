import Foundation
import XCTest
import Clibgit2
import libgit2
@testable import Sync_md

/// Issue #137: another device stopped tracking a file (deleted from Git and
/// added to .gitignore) while this device has a local edit to it, which is
/// what Obsidian does to `.obsidian/workspace-mobile.json` all the time. The
/// pull must fast-forward and keep the local copy as an ignored file, not
/// block or produce a modify/delete conflict. Real libgit2 repositories with
/// a local bare remote.
final class IncomingUntrackPullTests: XCTestCase {
    private var root: URL!
    private var bare: String { root.appendingPathComponent("remote.git").path }
    private var vault: URL { root.appendingPathComponent("vault", isDirectory: true) }
    private let workspace = ".obsidian/workspace-mobile.json"

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("incoming-untrack-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        _ = try UITestGitFixtures.commitToBare(atPath: bare, files: [
            "a.md": Data("note\n".utf8),
            ".obsidian/app.json": Data("{}\n".utf8),
            workspace: Data("{\"active\":\"a.md\"}\n".utf8),
        ], message: "Base")
        try UITestGitFixtures.clone(fromBareAtPath: bare, toWorktreeAtPath: vault.path)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    /// The other device's push: workspace file gone from the tree, ignored.
    @discardableResult
    private func remoteStopsTracking(ignore: String? = "/.obsidian/workspace*.json\n") throws -> String {
        var files: [String: Data] = [
            "a.md": Data("note\n".utf8),
            ".obsidian/app.json": Data("{}\n".utf8),
        ]
        if let ignore { files[".gitignore"] = Data(ignore.utf8) }
        return try UITestGitFixtures.commitToBare(atPath: bare, files: files, message: "Stop tracking .obsidian/workspace*.json")
    }

    private func write(_ text: String, _ path: String) throws {
        try text.write(to: vault.appendingPathComponent(path), atomically: true, encoding: .utf8)
    }

    private func read(_ path: String) throws -> String {
        try String(contentsOf: vault.appendingPathComponent(path), encoding: .utf8)
    }

    private func isIgnored(_ path: String) throws -> Bool {
        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_open(&repo, vault.path), 0)
        defer { if let repo { git_repository_free(repo) } }
        var ignored: Int32 = 0
        XCTAssertEqual(git_ignore_path_is_ignored(&ignored, repo, path), 0)
        return ignored == 1
    }

    func testLocalEditToFileTheRemoteStoppedTrackingDoesNotBlockAndIsKept() async throws {
        let remoteSHA = try remoteStopsTracking()
        try write("{\"active\":\"this device's layout\"}\n", workspace)
        let git = LocalGitService(localURL: vault)

        let plan = try await git.pullPlan(pat: "")
        XCTAssertEqual(plan.action, .fastForward, "Only the stopped-tracking file changed, so nothing blocks")
        XCTAssertFalse(plan.hasLocalChanges)

        let result = try await git.pull(pat: "")

        XCTAssertTrue(result.updated)
        XCTAssertEqual(result.newCommitSHA, remoteSHA)
        XCTAssertEqual(try read(workspace), "{\"active\":\"this device's layout\"}\n", "This device keeps its own layout")
        XCTAssertTrue(try isIgnored(workspace))
        let status = try await git.repoInfo().statusEntries
        XCTAssertTrue(status.isEmpty, "No conflict, no re-tracking, nothing to commit: \(status)")
    }

    func testPullOnlyPathCarriesTheEditToo() async throws {
        try remoteStopsTracking()
        try write("{\"active\":\"edited\"}\n", workspace)
        let git = LocalGitService(localURL: vault)

        let execution = try await git.executePullOnly(pat: "", expectedBranch: "main")

        XCTAssertEqual(execution.plan.action, .fastForward)
        XCTAssertEqual(execution.pullResult?.updated, true)
        XCTAssertEqual(try read(workspace), "{\"active\":\"edited\"}\n")
        let status = try await git.repoInfo().statusEntries
        XCTAssertTrue(status.isEmpty, "\(status)")
    }

    func testLocalDeletionOfTheFileIsFine() async throws {
        try remoteStopsTracking()
        try FileManager.default.removeItem(at: vault.appendingPathComponent(workspace))
        let git = LocalGitService(localURL: vault)

        let result = try await git.pull(pat: "")

        XCTAssertTrue(result.updated)
        XCTAssertFalse(FileManager.default.fileExists(atPath: vault.appendingPathComponent(workspace).path))
        let status = try await git.repoInfo().statusEntries
        XCTAssertTrue(status.isEmpty, "\(status)")
    }

    func testOtherLocalChangesStillBlockAndNothingIsTouched() async throws {
        let baseSHA = try await LocalGitService(localURL: vault).repoInfo().commitSHA
        try remoteStopsTracking()
        try write("{\"active\":\"edited\"}\n", workspace)
        try write("my unsaved note\n", "a.md")
        let git = LocalGitService(localURL: vault)

        let plan = try await git.pullPlan(pat: "")
        XCTAssertEqual(plan.action, .blockedByLocalChanges)
        do {
            _ = try await git.pull(pat: "")
            XCTFail("Unrelated local edits must still block")
        } catch LocalGitError.pullBlockedByLocalChanges {}

        XCTAssertEqual(try read(workspace), "{\"active\":\"edited\"}\n")
        XCTAssertEqual(try read("a.md"), "my unsaved note\n")
        let head = try await git.repoInfo().commitSHA
        XCTAssertEqual(head, baseSHA)
    }

    func testRemoteDeletionWithoutIgnoreRuleStillBlocks() async throws {
        try remoteStopsTracking(ignore: nil)
        try write("{\"active\":\"edited\"}\n", workspace)
        let git = LocalGitService(localURL: vault)

        let plan = try await git.pullPlan(pat: "")

        XCTAssertEqual(plan.action, .blockedByLocalChanges,
                       "A plain deletion of an edited file is a real conflict; keep asking the user")
        XCTAssertEqual(try read(workspace), "{\"active\":\"edited\"}\n")
    }

    func testEditToAFileThatIsIgnoredButStillTrackedRemotelyStillBlocks() async throws {
        // The remote adds the ignore rule but keeps tracking the file.
        _ = try UITestGitFixtures.commitToBare(atPath: bare, files: [
            "a.md": Data("note\n".utf8),
            ".obsidian/app.json": Data("{}\n".utf8),
            workspace: Data("{\"active\":\"a.md\"}\n".utf8),
            ".gitignore": Data("/.obsidian/workspace*.json\n".utf8),
        ], message: "Ignore without untracking")
        try write("{\"active\":\"edited\"}\n", workspace)

        let plan = try await LocalGitService(localURL: vault).pullPlan(pat: "")

        XCTAssertEqual(plan.action, .blockedByLocalChanges)
    }
}

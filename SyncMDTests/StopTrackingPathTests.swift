import Foundation
import XCTest
import Clibgit2
import libgit2
@testable import Sync_md

/// Issue #132: stop tracking one file, folder or glob. `git rm --cached`, a
/// root `.gitignore` entry and one commit holding only those two changes.
/// Uses real libgit2 repositories in a temporary folder.
final class StopTrackingPathTests: XCTestCase {
    private var cleanup: [URL] = []

    override func tearDown() {
        for url in cleanup { try? FileManager.default.removeItem(at: url) }
        cleanup = []
        super.tearDown()
    }

    // MARK: Fixtures

    /// A repository with one commit holding `files` (path → contents).
    private func makeRepo(_ files: [String: String]) async throws -> (url: URL, git: LocalGitService, base: String) {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("untrack-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        cleanup.append(url)
        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, url.path, 0), 0)
        if let repo { git_repository_free(repo) }
        let git = LocalGitService(localURL: url)
        for (path, contents) in files.sorted(by: { $0.key < $1.key }) {
            try write(contents, to: path, in: url)
            try await git.stage(path: path)
        }
        let base = try await git.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        return (url, git, base)
    }

    private func write(_ contents: String, to path: String, in root: URL) throws {
        let file = root.appendingPathComponent(path)
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try contents.write(to: file, atomically: true, encoding: .utf8)
    }

    private func read(_ path: String, in root: URL) throws -> String {
        try String(contentsOf: root.appendingPathComponent(path), encoding: .utf8)
    }

    private func stopTracking(_ target: GitUntrackTarget, in git: LocalGitService) async throws -> GitUntrackResult {
        try await git.stopTracking(target, authorName: "Tests", authorEmail: "tests@example.com")
    }

    private func withRepo<T>(_ url: URL, _ body: (OpaquePointer?) throws -> T) throws -> T {
        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_open(&repo, url.path), 0)
        defer { if let repo { git_repository_free(repo) } }
        return try body(repo)
    }

    /// Blob text at `revision:path`, or nil when the path isn't in that tree.
    private func blob(_ revision: String, _ path: String, in url: URL) throws -> String? {
        try withRepo(url) { repo in
            var object: OpaquePointer?
            defer { if let object { git_object_free(object) } }
            guard git_revparse_single(&object, repo, "\(revision):\(path)") == 0,
                  let raw = git_blob_rawcontent(object) else { return nil }
            return String(decoding: Data(bytes: raw, count: Int(git_blob_rawsize(object))), as: UTF8.self)
        }
    }

    private func indexHas(_ path: String, in url: URL) throws -> Bool {
        try withRepo(url) { repo in
            var index: OpaquePointer?
            defer { if let index { git_index_free(index) } }
            XCTAssertEqual(git_repository_index(&index, repo), 0)
            return git_index_get_bypath(index, path, 0) != nil
        }
    }

    private func isIgnored(_ path: String, in url: URL) throws -> Bool {
        try withRepo(url) { repo in
            var ignored: Int32 = 0
            XCTAssertEqual(git_ignore_path_is_ignored(&ignored, repo, path), 0)
            return ignored == 1
        }
    }

    private func headParent(of url: URL) throws -> String? {
        try withRepo(url) { repo in
            var object: OpaquePointer?
            defer { if let object { git_object_free(object) } }
            guard git_revparse_single(&object, repo, "HEAD~1") == 0, let oid = git_object_id(object) else { return nil }
            var buffer = [CChar](repeating: 0, count: 41)
            git_oid_tostr(&buffer, 41, oid)
            return String(cString: buffer)
        }
    }

    // MARK: Entries and matching

    func testIgnoreEntriesAreRootAnchoredAndEscaped() {
        XCTAssertEqual(GitUntrackTarget.file(".obsidian/workspace-mobile.json").ignoreEntry, "/.obsidian/workspace-mobile.json")
        XCTAssertEqual(GitUntrackTarget.file("Daily Notes/My note.md").ignoreEntry, "/Daily Notes/My note.md")
        XCTAssertEqual(GitUntrackTarget.file("a/[draft] *what?.md").ignoreEntry, "/a/\\[draft] \\*what\\?.md")
        XCTAssertEqual(GitUntrackTarget.file("#notes/!x.md").ignoreEntry, "/#notes/!x.md")
        XCTAssertEqual(GitUntrackTarget.file("trailing  ").ignoreEntry, "/trailing\\ \\ ")
        XCTAssertEqual(GitUntrackTarget.folder("/.obsidian/plugins/sync/").ignoreEntry, "/.obsidian/plugins/sync/")
        XCTAssertEqual(GitUntrackTarget.obsidianWorkspace.ignoreEntry, "/.obsidian/workspace*.json")
        XCTAssertEqual(GitUntrackTarget.folder("a/b").displayName, "a/b/")
        XCTAssertEqual(GitUntrackTarget.file("a.md").commitMessage.components(separatedBy: "\n").first, "Stop tracking a.md")
    }

    func testMatching() {
        XCTAssertTrue(GitUntrackTarget.isObsidianWorkspaceFile(".obsidian/workspace.json"))
        XCTAssertTrue(GitUntrackTarget.isObsidianWorkspaceFile(".obsidian/workspace-mobile.json"))
        XCTAssertFalse(GitUntrackTarget.isObsidianWorkspaceFile(".obsidian/workspaces.json.bak"))
        XCTAssertFalse(GitUntrackTarget.isObsidianWorkspaceFile(".obsidian/sub/workspace.json"))
        XCTAssertFalse(GitUntrackTarget.isObsidianWorkspaceFile("vault/.obsidian/workspace.json"))
        XCTAssertTrue(GitUntrackTarget.folder("a b").matches("a b/c.md"))
        XCTAssertFalse(GitUntrackTarget.folder("a").matches("ab/c.md"))
        XCTAssertFalse(GitUntrackTarget.folder("a").matches("a"))
        XCTAssertTrue(GitUntrackTarget.file("a/b.md").matches("a/b.md"))
        XCTAssertFalse(GitUntrackTarget.file("a/b.md").matches("a/b.md.orig"))
    }

    func testGitIgnoreContainsAndAppend() {
        let content = "# comment\n.DS_Store\n.obsidian/workspace.json  \r\n/a/\n"
        XCTAssertTrue(GitIgnoreFile.contains("/.obsidian/workspace.json", in: content), "a/b and /a/b are the same rule")
        XCTAssertTrue(GitIgnoreFile.contains("/a/", in: content))
        XCTAssertFalse(GitIgnoreFile.contains("/.DS_Store", in: content), "/x is anchored, x is not")
        XCTAssertFalse(GitIgnoreFile.contains("/trailing\\ ", in: "/trailing\n"))
        XCTAssertEqual(GitIgnoreFile.appending("/x", to: ""), "/x\n")
        XCTAssertEqual(GitIgnoreFile.appending("/x", to: "a"), "a\n/x\n")
        XCTAssertEqual(GitIgnoreFile.appending("/x", to: "a\n"), "a\n/x\n")
        XCTAssertEqual(GitIgnoreFile.appending("/x", to: "a\r\n"), "a\r\n/x\r\n")
    }

    func testRejectsPathsOutsideTheVaultOrInsideGit() {
        for target: GitUntrackTarget in [.file(""), .file("../x"), .file("a/../b"), .file(".git/config"),
                                         .folder(".git"), .file(".gitignore"), .file("a//b"), .file("a\nb")] {
            XCTAssertThrowsError(try target.validate(), "\(target)")
        }
        XCTAssertNoThrow(try GitUntrackTarget.file(".github/x.yml").validate())
        XCTAssertNoThrow(try GitUntrackTarget.file(".gitattributes").validate())
    }

    // MARK: Index, .gitignore and commit

    func testStopTrackingFileKeepsItOnDiskIgnoresAndCommits() async throws {
        let (url, git, base) = try await makeRepo([
            ".obsidian/workspace-mobile.json": "{\"layout\":1}",
            "Note.md": "note\n",
        ])
        try write("{\"layout\":2}", to: ".obsidian/workspace-mobile.json", in: url)

        let result = try await stopTracking(.file(".obsidian/workspace-mobile.json"), in: git)

        XCTAssertEqual(result.removedPaths, [".obsidian/workspace-mobile.json"])
        XCTAssertEqual(result.addedIgnoreEntry, "/.obsidian/workspace-mobile.json")
        XCTAssertEqual(try read(".obsidian/workspace-mobile.json", in: url), "{\"layout\":2}", "The file stays on disk, edits included")
        XCTAssertEqual(try read(".gitignore", in: url), "/.obsidian/workspace-mobile.json\n", ".gitignore is created")
        XCTAssertFalse(try indexHas(".obsidian/workspace-mobile.json", in: url))
        let info = try await git.repoInfo()
        XCTAssertEqual(info.commitSHA, result.commitSHA)
        XCTAssertEqual(try headParent(of: url), base)
        XCTAssertNil(try blob("HEAD", ".obsidian/workspace-mobile.json", in: url))
        XCTAssertEqual(try blob("HEAD", ".gitignore", in: url), "/.obsidian/workspace-mobile.json\n")
        XCTAssertEqual(try blob("HEAD", "Note.md", in: url), "note\n")
        let summary = try await git.commitSummary(revision: "HEAD")
        XCTAssertEqual(summary?.message, "Stop tracking .obsidian/workspace-mobile.json")
        XCTAssertTrue(info.statusEntries.isEmpty, "Nothing left to commit: \(info.statusEntries)")
    }

    func testAppendsToExistingGitIgnoreWithoutFinalNewline() async throws {
        let (url, git, _) = try await makeRepo([".gitignore": ".DS_Store", "a.md": "a"])
        let result = try await stopTracking(.file("a.md"), in: git)
        XCTAssertEqual(result.addedIgnoreEntry, "/a.md")
        XCTAssertEqual(try read(".gitignore", in: url), ".DS_Store\n/a.md\n")
        XCTAssertEqual(try blob("HEAD", ".gitignore", in: url), ".DS_Store\n/a.md\n")
    }

    /// The common desktop half-fix: the rule is in .gitignore but the file was
    /// never removed from the index. Only the index changes.
    func testAlreadyIgnoredFileIsUntrackedWithoutDuplicatingTheRule() async throws {
        let ignore = ".obsidian/workspace*.json\n"
        let (url, git, _) = try await makeRepo([".gitignore": ignore, ".obsidian/workspace.json": "{}", "n.md": "n"])
        XCTAssertTrue(try indexHas(".obsidian/workspace.json", in: url), "Fixture tracks an ignored file")

        let result = try await stopTracking(.file(".obsidian/workspace.json"), in: git)

        XCTAssertNil(result.addedIgnoreEntry)
        XCTAssertNotNil(result.commitSHA)
        XCTAssertEqual(try read(".gitignore", in: url), ignore, ".gitignore is untouched")
        XCTAssertNil(try blob("HEAD", ".obsidian/workspace.json", in: url))
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.appendingPathComponent(".obsidian/workspace.json").path))
    }

    func testExactRuleIsNotDuplicatedAndRepeatIsRejected() async throws {
        let (url, git, _) = try await makeRepo(["a.md": "a"])
        _ = try await stopTracking(.file("a.md"), in: git)
        do {
            _ = try await stopTracking(.file("a.md"), in: git)
            XCTFail("Untracking twice must fail")
        } catch {
            XCTAssertEqual(error as? GitUntrackError, .notTracked("a.md"))
        }
        XCTAssertEqual(try read(".gitignore", in: url), "/a.md\n")
    }

    func testNestedPathWithSpacesAndGlobCharacters() async throws {
        let path = "Projects/Q1 plan/notes [draft].md"
        let (url, git, _) = try await makeRepo([path: "draft", "Projects/Q1 plan/notes d.md": "keep"])

        let result = try await stopTracking(.file(path), in: git)

        XCTAssertEqual(result.addedIgnoreEntry, "/Projects/Q1 plan/notes \\[draft].md")
        XCTAssertTrue(try isIgnored(path, in: url))
        XCTAssertFalse(try isIgnored("Projects/Q1 plan/notes d.md", in: url), "The escaped rule must not act as a glob")
        XCTAssertNil(try blob("HEAD", path, in: url))
        XCTAssertEqual(try blob("HEAD", "Projects/Q1 plan/notes d.md", in: url), "keep")
        let status = try await git.repoInfo().statusEntries
        XCTAssertTrue(status.isEmpty, "\(status)")
    }

    func testFolderRemovesEveryFileBelowIt() async throws {
        let (url, git, _) = try await makeRepo([
            ".obsidian/plugins/my plugin/data.json": "{}",
            ".obsidian/plugins/my plugin/main.js": "x",
            ".obsidian/plugins/other/data.json": "{}",
        ])
        let result = try await stopTracking(.folder(".obsidian/plugins/my plugin"), in: git)
        XCTAssertEqual(result.removedPaths, [".obsidian/plugins/my plugin/data.json", ".obsidian/plugins/my plugin/main.js"])
        XCTAssertEqual(result.addedIgnoreEntry, "/.obsidian/plugins/my plugin/")
        XCTAssertNotNil(try blob("HEAD", ".obsidian/plugins/other/data.json", in: url))
        XCTAssertNil(try blob("HEAD", ".obsidian/plugins/my plugin/main.js", in: url))
        let status = try await git.repoInfo().statusEntries
        XCTAssertTrue(status.isEmpty, "\(status)")
    }

    func testWorkspacePatternRemovesAllWorkspaceFiles() async throws {
        let (url, git, _) = try await makeRepo([
            ".obsidian/workspace.json": "{}",
            ".obsidian/workspace-mobile.json": "{}",
            ".obsidian/app.json": "{}",
        ])
        let tracked = try await git.trackedPaths(matching: .obsidianWorkspace)
        XCTAssertEqual(tracked, [".obsidian/workspace-mobile.json", ".obsidian/workspace.json"])

        let result = try await stopTracking(.obsidianWorkspace, in: git)

        XCTAssertEqual(result.removedPaths, tracked)
        XCTAssertEqual(try read(".gitignore", in: url), "/.obsidian/workspace*.json\n")
        XCTAssertNotNil(try blob("HEAD", ".obsidian/app.json", in: url))
        let remaining = try await git.trackedPaths(matching: .obsidianWorkspace)
        XCTAssertEqual(remaining, [])
    }

    func testOtherStagedChangesStayStagedAndOutOfTheCommit() async throws {
        let (url, git, _) = try await makeRepo(["Note.md": "v1\n", "w.json": "{}"])
        try write("v2\n", to: "Note.md", in: url)
        try await git.stage(path: "Note.md")

        _ = try await stopTracking(.file("w.json"), in: git)

        XCTAssertEqual(try blob("HEAD", "Note.md", in: url), "v1\n", "The staged edit is not committed")
        let entry = try await git.repoInfo().statusEntries.first { $0.path == "Note.md" }
        XCTAssertEqual(entry?.indexStatus, .modified, "The edit stays staged")
    }

    func testStagedOnlyFileIsUnstagedAndIgnored() async throws {
        let (url, git, base) = try await makeRepo(["a.md": "a"])
        try write("new", to: "new.md", in: url)
        try await git.stage(path: "new.md")

        let result = try await stopTracking(.file("new.md"), in: git)

        XCTAssertEqual(result.removedPaths, ["new.md"])
        XCTAssertFalse(try indexHas("new.md", in: url))
        XCTAssertEqual(try headParent(of: url), base, "The commit adds the .gitignore rule")
        XCTAssertEqual(try read("new.md", in: url), "new")
        let status = try await git.repoInfo().statusEntries
        XCTAssertTrue(status.isEmpty, "\(status)")
    }

    func testUntrackedIgnoredPathIsRejected() async throws {
        let (_, git, _) = try await makeRepo([".gitignore": "*.tmp\n", "a.md": "a"])
        do {
            _ = try await stopTracking(.file("x.tmp"), in: git)
            XCTFail("Nothing to do must be an error")
        } catch {
            XCTAssertEqual(error as? GitUntrackError, .notTracked("x.tmp"))
        }
    }
}

import Foundation
import XCTest
import Clibgit2
import libgit2
@testable import Sync_md

/// Issue #50: a vault must never be downgraded to "Not cloned" just because
/// its folder moved, and "Clone Repository" must never delete an intact
/// working copy. These tests use real libgit2 repositories and real files;
/// only the OS bookmark boundary is injected where a grant is involved.
@MainActor
final class Issue50WorkingCopyRecoveryTests: XCTestCase {
    private let remote = "https://github.com/owner/issue50-notes.git"
    private var cleanup: [URL] = []

    override func tearDown() {
        for url in cleanup { try? FileManager.default.removeItem(at: url) }
        cleanup = []
        super.tearDown()
    }

    // MARK: Fixtures

    private func scratch(_ name: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("issue50-\(name)-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        cleanup.append(url)
        return url
    }

    /// A real working copy with one commit, an `origin` remote and an
    /// unpublished edit. Returns the committed SHA and the dirty note bytes.
    private func makeWorkingCopy(at url: URL, remote: String?, committed: String = "committed\n") async throws -> (sha: String, branch: String, unpublished: Data) {
        let fm = FileManager.default
        try fm.createDirectory(at: url, withIntermediateDirectories: true)
        var repo: OpaquePointer?
        let code = git_repository_init(&repo, url.path, 0)
        if let repo { git_repository_free(repo) }
        XCTAssertEqual(code, 0)
        if let remote {
            let config = url.appendingPathComponent(".git/config")
            let existing = (try? String(contentsOf: config, encoding: .utf8)) ?? ""
            try (existing + "[remote \"origin\"]\n\turl = \(remote)\n\tfetch = +refs/heads/*:refs/remotes/origin/*\n")
                .write(to: config, atomically: true, encoding: .utf8)
        }
        let note = url.appendingPathComponent("Note.md")
        try committed.write(to: note, atomically: true, encoding: .utf8)
        let git = LocalGitService(localURL: url)
        try await git.stage(path: "Note.md")
        let sha = try await git.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        let branch = try await git.repoInfo().branch
        let unpublished = Data("unpublished local edit — must survive\n".utf8)
        try unpublished.write(to: note)
        return (sha, branch, unpublished)
    }

    private func config(folder: String, sha: String, branch: String, bookmark: Data? = nil,
                        parent: Bool = false, repoURL: String? = nil) -> RepoConfig {
        RepoConfig(repoURL: repoURL ?? remote, branch: branch, authorName: "Tests", authorEmail: "tests@example.com",
                   vaultFolderName: folder, customVaultBookmarkData: bookmark, customLocationIsParent: parent,
                   authMethod: GitAuthMethod.none,
                   gitState: sha.isEmpty ? .empty : GitState(commitSHA: sha, treeSHA: "", branch: branch,
                                                            blobSHAs: [:], lastSyncDate: .distantPast))
    }

    private func inventory(_ repos: [RepoConfig], in dir: URL) throws -> URL {
        let file = dir.appendingPathComponent("repos.json")
        try RepoPersistenceStore().replaceAll(repos, at: file)
        return file
    }

    // MARK: Priority 1 — never delete an intact working copy

    /// The audit probe that deleted unpublished files on main (PR #69 evidence).
    func testCloneReconnectsIntactSameRemoteWorkingCopyInsteadOfDeletingIt() async throws {
        let settings = try scratch("clone-settings")
        let folder = "issue50-clone-\(UUID().uuidString.prefix(8))"
        let target = AppState.appDocumentsDirectory.appendingPathComponent(folder, isDirectory: true)
        cleanup.append(target)
        let copy = try await makeWorkingCopy(at: target, remote: "git@github.com:Owner/issue50-notes.git")
        // The saved record already reads "Not cloned", exactly as in the report.
        let vault = config(folder: folder, sha: "", branch: "")
        let state = AppState(reposFileURL: try inventory([vault], in: settings), loadPersistedState: false)
        state.repos = [vault]
        _ = state.saveRepos(replaceAll: true)
        XCTAssertEqual(state.cloneTargetAssessment(target, repoID: vault.id), .existingWorkingCopy)
        XCTAssertNotNil(state.cloneTargetConflict(target, repoID: vault.id),
                        "Nothing may treat an intact working copy as a replaceable clone target")

        await state.clone(repoID: vault.id)

        XCTAssertFalse(state.showError, state.lastError ?? "")
        XCTAssertEqual(try Data(contentsOf: target.appendingPathComponent("Note.md")), copy.unpublished,
                       "Clone must not delete or overwrite unpublished edits")
        let head = try await LocalGitService(localURL: target).repoInfo()
        XCTAssertEqual(head.commitSHA, copy.sha, "Local commits must be untouched")
        XCTAssertEqual(state.repo(id: vault.id)?.gitState.commitSHA, copy.sha)
        XCTAssertEqual(state.repo(id: vault.id)?.gitState.branch, copy.branch)
        XCTAssertEqual(state.repo(id: vault.id)?.branch, copy.branch)
        XCTAssertEqual(try RepoPersistenceStore().loadStrict(from: settings.appendingPathComponent("repos.json")).first?.gitState.commitSHA,
                       copy.sha)
    }

    func testCloneTargetStillRefusesForeignRepositoryAndAllowsOnlyEmptyPartialClone() async throws {
        let state = AppState(loadPersistedState: false)
        let folder = "issue50-target-\(UUID().uuidString.prefix(8))"
        let target = AppState.appDocumentsDirectory.appendingPathComponent(folder, isDirectory: true)
        cleanup.append(target)
        let vault = config(folder: folder, sha: "", branch: "")
        state.repos = [vault]

        // Interrupted clone: only `.git`, remote written, no commits yet.
        let gitDir = target.appendingPathComponent(".git", isDirectory: true)
        try FileManager.default.createDirectory(at: gitDir.appendingPathComponent("refs/heads"), withIntermediateDirectories: true)
        try "[remote \"origin\"]\n\turl = \(remote)\n".write(to: gitDir.appendingPathComponent("config"), atomically: true, encoding: .utf8)
        XCTAssertEqual(state.cloneTargetAssessment(target, repoID: vault.id), .replaceable)

        // A file beside a commit-less `.git` was put there by someone: keep it.
        try "keep".write(to: target.appendingPathComponent("loose.md"), atomically: true, encoding: .utf8)
        guard case .conflict = state.cloneTargetAssessment(target, repoID: vault.id) else {
            return XCTFail("Files beside a partial clone must be preserved")
        }

        // A different repository with commits is a conflict, not a reconnect.
        try FileManager.default.removeItem(at: target)
        _ = try await makeWorkingCopy(at: target, remote: "https://github.com/owner/other.git")
        guard case .conflict = state.cloneTargetAssessment(target, repoID: vault.id) else {
            return XCTFail("Another repository must never be adopted or deleted")
        }
        await state.clone(repoID: vault.id)
        XCTAssertTrue(state.showError)
        XCTAssertFalse(state.repo(id: vault.id)?.isCloned ?? true)
        XCTAssertTrue(FileManager.default.fileExists(atPath: target.appendingPathComponent("Note.md").path))
    }

    // MARK: Priority 2 — follow a moved or renamed folder

    func testAppManagedVaultFollowsRenamedWorkingCopyInsteadOfBecomingNotCloned() async throws {
        let settings = try scratch("documents-settings")
        let fm = FileManager.default
        let folder = "issue50-docs-\(UUID().uuidString.prefix(8))"
        let original = AppState.appDocumentsDirectory.appendingPathComponent(folder, isDirectory: true)
        let renamed = AppState.appDocumentsDirectory.appendingPathComponent(folder + "-renamed", isDirectory: true)
        cleanup += [original, renamed]
        let copy = try await makeWorkingCopy(at: original, remote: remote)
        let vault = config(folder: folder, sha: copy.sha, branch: copy.branch)
        let file = try inventory([vault], in: settings)
        try fm.moveItem(at: original, to: renamed)

        let state = AppState(reposFileURL: file)
        XCTAssertTrue(state.repo(id: vault.id)?.isCloned == true, "Launch validation must not reset a renamed vault")
        XCTAssertEqual(state.vaultURL(for: vault.id).map(AppState.canonicalFilePath(for:)),
                       AppState.canonicalFilePath(for: renamed))
        XCTAssertEqual(try RepoPersistenceStore().loadStrict(from: file).first?.vaultFolderName, folder + "-renamed")
        XCTAssertEqual(try RepoPersistenceStore().loadStrict(from: file).first?.gitState, vault.gitState)

        // Renaming it back is followed too, on the next foreground validation.
        try fm.moveItem(at: renamed, to: original)
        state.refreshVaultBookmarks()
        state.validateClonedRepos()
        state.detectChanges(repoID: vault.id)
        XCTAssertTrue(state.repo(id: vault.id)?.isCloned == true)
        XCTAssertEqual(state.repo(id: vault.id)?.vaultFolderName, folder)
        XCTAssertEqual(try Data(contentsOf: original.appendingPathComponent("Note.md")), copy.unpublished)
    }

    /// The physical-iPhone reproduction: a Files-picked parent grant whose
    /// cloned child folder is renamed, then renamed back and relaunched.
    func testParentGrantFollowsRenamedChildAndRestoredName() async throws {
        let fm = FileManager.default
        let grant = try scratch("parent-grant")
        let original = grant.appendingPathComponent("gitsync-tide-qa", isDirectory: true)
        let renamed = grant.appendingPathComponent("gitsync-tide-qa-renamed", isDirectory: true)
        let copy = try await makeWorkingCopy(at: original, remote: remote)
        let vault = config(folder: "gitsync-tide-qa", sha: copy.sha, branch: copy.branch,
                           bookmark: Data("granted parent".utf8), parent: true)
        let settings = try scratch("parent-settings")
        let file = try inventory([vault], in: settings)
        let access = AppState.VaultBookmarkAccess(resolve: { _ in (grant, false) }, start: { _ in true }, stop: { _ in })

        try fm.moveItem(at: original, to: renamed)
        let state = AppState(reposFileURL: file, vaultBookmarkAccess: access)
        XCTAssertNil(state.vaultAccessErrors[vault.id])
        XCTAssertEqual(state.vaultURL(for: vault.id), renamed)
        state.detectChanges(repoID: vault.id)
        XCTAssertTrue(state.repo(id: vault.id)?.isCloned == true)
        XCTAssertEqual(try RepoPersistenceStore().loadStrict(from: file).first?.gitState, vault.gitState)
        XCTAssertEqual(try RepoPersistenceStore().loadStrict(from: file).first?.customLocationIsParent, true,
                       "Following the rename keeps the grant's parent semantics")

        // Restore the original name and relaunch: the record repairs itself.
        try fm.moveItem(at: renamed, to: original)
        let relaunched = AppState(reposFileURL: file, vaultBookmarkAccess: access)
        XCTAssertNil(relaunched.vaultAccessErrors[vault.id])
        XCTAssertEqual(relaunched.vaultURL(for: vault.id), original)
        XCTAssertEqual(relaunched.repo(id: vault.id)?.gitState, vault.gitState)
        XCTAssertEqual(try Data(contentsOf: original.appendingPathComponent("Note.md")), copy.unpublished)
    }

    func testAmbiguousOrDifferentCommitCandidatesAreNeverAdopted() async throws {
        let fm = FileManager.default
        let grant = try scratch("ambiguous-grant")
        let original = grant.appendingPathComponent("notes", isDirectory: true)
        let copy = try await makeWorkingCopy(at: original, remote: remote)
        let vault = config(folder: "notes", sha: copy.sha, branch: copy.branch,
                           bookmark: Data("granted parent".utf8), parent: true)
        let file = try inventory([vault], in: try scratch("ambiguous-settings"))
        let access = AppState.VaultBookmarkAccess(resolve: { _ in (grant, false) }, start: { _ in true }, stop: { _ in })

        // Same remote but a different HEAD: not provably this vault's copy.
        try fm.moveItem(at: original, to: grant.appendingPathComponent("notes-moved", isDirectory: true))
        let other = grant.appendingPathComponent("notes-other", isDirectory: true)
        let otherCopy = try await makeWorkingCopy(at: other, remote: remote, committed: "a different history\n")
        XCTAssertNotEqual(otherCopy.sha, copy.sha)
        var state = AppState(reposFileURL: file, vaultBookmarkAccess: access)
        XCTAssertEqual(state.vaultURL(for: vault.id), grant.appendingPathComponent("notes-moved", isDirectory: true),
                       "The matching-HEAD sibling is the only candidate")

        // Two matching copies: refuse to guess, keep metadata, require Reconnect.
        try fm.moveItem(at: grant.appendingPathComponent("notes-moved"), to: grant.appendingPathComponent("a"))
        try fm.copyItem(at: grant.appendingPathComponent("a"), to: grant.appendingPathComponent("b"))
        var stale = vault
        stale.vaultFolderName = "notes"
        try RepoPersistenceStore().replaceAll([stale], at: file)
        state = AppState(reposFileURL: file, vaultBookmarkAccess: access)
        XCTAssertEqual(state.vaultAccessErrors[vault.id], .unavailableFolder)
        XCTAssertNil(state.vaultURL(for: vault.id))
        state.detectChanges(repoID: vault.id)
        XCTAssertEqual(try RepoPersistenceStore().loadStrict(from: file).first, stale,
                       "Ambiguity must preserve the saved clone, never reset it")
    }

    func testForegroundRefreshFollowsMovedBookmarkAndKeepsWorkingGrantOnTransientFailure() async throws {
        let fm = FileManager.default
        let root = try scratch("moved-bookmark")
        let before = root.appendingPathComponent("Obsidian-old/vault", isDirectory: true)
        let after = root.appendingPathComponent("Obsidian-new/vault", isDirectory: true)
        let copy = try await makeWorkingCopy(at: before, remote: remote)
        let vault = config(folder: "vault", sha: copy.sha, branch: copy.branch, bookmark: Data("working copy".utf8))
        let file = try inventory([vault], in: try scratch("moved-settings"))
        var resolvedURL = before
        var resolveFails = false
        var starts = 0, stops = 0
        let access = AppState.VaultBookmarkAccess(resolve: { _ in
            if resolveFails { throw AppState.VaultAccessError.unresolvedBookmark }
            return (resolvedURL, false)
        }, start: { _ in starts += 1; return true }, stop: { _ in stops += 1 }, create: { _ in Data("renewed".utf8) })
        let state = AppState(reposFileURL: file, vaultBookmarkAccess: access)
        XCTAssertEqual(state.vaultURL(for: vault.id), before)

        // Unchanged path: no scope churn.
        state.refreshVaultBookmarks()
        XCTAssertEqual(starts, 1)
        XCTAssertEqual(stops, 0)

        // A transient resolution failure never revokes a working grant.
        resolveFails = true
        state.refreshVaultBookmarks()
        XCTAssertEqual(state.vaultURL(for: vault.id), before)
        XCTAssertNil(state.vaultAccessErrors[vault.id])

        // The container moved while suspended; the bookmark follows it.
        resolveFails = false
        try fm.createDirectory(at: after.deletingLastPathComponent(), withIntermediateDirectories: true)
        try fm.moveItem(at: before, to: after)
        resolvedURL = after
        state.refreshVaultBookmarks()
        state.validateClonedRepos()
        state.detectChanges(repoID: vault.id)
        XCTAssertEqual(state.vaultURL(for: vault.id), after)
        XCTAssertNil(state.vaultAccessErrors[vault.id])
        XCTAssertEqual(state.repo(id: vault.id)?.gitState, vault.gitState)
        XCTAssertEqual(stops, 1, "Only the old scope is released")
        XCTAssertEqual(try Data(contentsOf: after.appendingPathComponent("Note.md")), copy.unpublished)
    }

    // MARK: Recovery for app-cloned vaults

    func testReconnectFolderRepairsNotClonedAppVaultWithoutTouchingFiles() async throws {
        let settings = try scratch("reconnect-settings")
        let picked = try scratch("reconnect-picked").appendingPathComponent("vault", isDirectory: true)
        let copy = try await makeWorkingCopy(at: picked, remote: "https://github.com/OWNER/issue50-notes")
        let vault = config(folder: "issue50-reconnect-\(UUID().uuidString.prefix(8))", sha: "", branch: "")
        XCTAssertFalse(vault.isExternalLocalRepository, "App-cloned vaults were excluded from Reconnect before #50")
        let state = AppState(reposFileURL: try inventory([vault], in: settings))

        try await state.reconnectRepositoryFolder(repoID: vault.id, url: picked)

        XCTAssertEqual(state.vaultURL(for: vault.id), picked)
        XCTAssertEqual(state.repo(id: vault.id)?.gitState.commitSHA, copy.sha)
        XCTAssertEqual(try Data(contentsOf: picked.appendingPathComponent("Note.md")), copy.unpublished)
        XCTAssertFalse(state.repo(id: vault.id)?.isGitSyncManagedStorage ?? true,
                       "A picked folder is user-owned: removing the vault must not delete it")
        let relaunched = AppState(reposFileURL: settings.appendingPathComponent("repos.json"))
        XCTAssertEqual(relaunched.vaultURL(for: vault.id).map(AppState.canonicalFilePath(for:)),
                       AppState.canonicalFilePath(for: picked), "The reconnected folder's bookmark must persist")
        XCTAssertEqual(relaunched.repo(id: vault.id)?.gitState.commitSHA, copy.sha)

        // A folder holding a different repository is refused unchanged.
        let foreign = try scratch("reconnect-foreign").appendingPathComponent("vault", isDirectory: true)
        _ = try await makeWorkingCopy(at: foreign, remote: "https://github.com/owner/other.git")
        let before = state.repo(id: vault.id)
        do {
            try await state.reconnectRepositoryFolder(repoID: vault.id, url: foreign)
            XCTFail("A different repository must not be reconnected")
        } catch {}
        XCTAssertEqual(state.repo(id: vault.id), before)
    }

    // MARK: Report 2 — leftover HEAD/ref locks

    func testLockStatusReportsLeftoverHEADAndBranchRefLocks() async throws {
        let fm = FileManager.default
        let url = try scratch("ref-locks").appendingPathComponent("repo", isDirectory: true)
        let copy = try await makeWorkingCopy(at: url, remote: remote)
        let git = LocalGitService(localURL: url)
        let noLock = await git.indexLockStatus()
        XCTAssertNil(noLock)

        let old = Date().addingTimeInterval(-600)
        for relative in ["HEAD.lock", "refs/heads/\(copy.branch).lock"] {
            let lock = url.appendingPathComponent(".git/\(relative)")
            try Data().write(to: lock)
            try fm.setAttributes([.modificationDate: old], ofItemAtPath: lock.path)
            let found = await git.indexLockStatus()
            let status = try XCTUnwrap(found, relative)
            XCTAssertEqual(status.fileName, (relative as NSString).lastPathComponent)
            XCTAssertFalse(status.isIndexLock)
            try await git.removeStaleIndexLock(expected: status)
            XCTAssertFalse(fm.fileExists(atPath: lock.path))
        }
        let cleared = await git.indexLockStatus()
        XCTAssertNil(cleared)
        let head = try await git.repoInfo()
        XCTAssertEqual(head.commitSHA, copy.sha, "Removing locks never changes refs")
    }
}

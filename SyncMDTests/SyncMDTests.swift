import Foundation
import XCTest
import Clibgit2
import libgit2
@testable import Sync_md

final class SyncMDTests: XCTestCase {
    override func setUp() {
        super.setUp()
        _ = git_libgit2_init()
    }

    func testSmoke() {
        XCTAssertTrue(true)
    }

    @discardableResult
    private func commitLocalFixtureChanges(
        using service: LocalGitService,
        message: String,
        authorName: String = "SyncMD Tests",
        authorEmail: String = "tests@example.com"
    ) async throws -> String {
        // Fixture setup should never depend on an expected push failure from a
        // repository without an origin remote. Keep local-git tests deterministic
        // by committing only the staged index when the test is not exercising push.
        try await service.commitLocal(
            message: message,
            authorName: authorName,
            authorEmail: authorEmail
        )
    }

    func testFixtureFactoryBuildsDeterministicCleanDirtyDivergedAndConflictedStates() throws {
        for state in GitFixtureState.allCases {
            let fixtureA = try GitFixtureFactory.make(state: state)
            defer { fixtureA.cleanup() }

            let fixtureB = try GitFixtureFactory.make(state: state)
            defer { fixtureB.cleanup() }

            XCTAssertEqual(fixtureA.snapshot(), fixtureB.snapshot(), "Fixture state \(state.rawValue) should be deterministic")
            XCTAssertEqual(fixtureA.repoInfo.changeCount, state.expectedChangeCount)
            XCTAssertEqual(fixtureB.repoInfo.changeCount, state.expectedChangeCount)
        }
    }

    @MainActor
    func testAppStateDetectChangesUsesInjectedGitRepositoryFactory() async throws {
        let fixture = try GitFixtureFactory.make(state: .dirty)
        defer { fixture.cleanup() }

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        appState.detectChanges(repoID: fixture.repoConfig.id)

        for _ in 0..<20 {
            if appState.changeCounts[fixture.repoConfig.id] == fixture.repoInfo.changeCount {
                break
            }
            try await Task.sleep(for: .milliseconds(25))
        }

        XCTAssertEqual(appState.changeCounts[fixture.repoConfig.id], fixture.repoInfo.changeCount)
    }

    func testPullPlanClassifierDistinguishesFastForwardBlockedAndDiverged() {
        XCTAssertEqual(
            LocalGitService.classifyPullAction(ahead: 0, behind: 3, hasLocalChanges: false),
            .fastForward
        )
        XCTAssertEqual(
            LocalGitService.classifyPullAction(ahead: 0, behind: 1, hasLocalChanges: true),
            .blockedByLocalChanges
        )
        XCTAssertEqual(
            LocalGitService.classifyPullAction(ahead: 2, behind: 2, hasLocalChanges: false),
            .diverged
        )
        XCTAssertEqual(
            LocalGitService.classifyPullAction(ahead: 4, behind: 0, hasLocalChanges: false),
            .upToDate
        )
    }

    func testGitRemoteURLParsesGitHubSelfHostedAndSSHRemotes() {
        let gitHubShortcut = GitRemoteURL.parse("owner/repo")
        XCTAssertEqual(gitHubShortcut?.cloneURLString, "https://github.com/owner/repo.git")
        XCTAssertEqual(gitHubShortcut?.repoName, "repo")
        XCTAssertEqual(gitHubShortcut?.ownerName, "owner")
        XCTAssertEqual(gitHubShortcut?.isGitHub, true)

        let selfHosted = GitRemoteURL.parse("https://git.example.com/team/notes.git")
        XCTAssertEqual(selfHosted?.repoName, "notes")
        XCTAssertEqual(selfHosted?.ownerName, "team")
        XCTAssertEqual(selfHosted?.isGitHub, false)
        XCTAssertEqual(selfHosted?.cloneURLString, "https://git.example.com/team/notes.git")

        let ssh = GitRemoteURL.parse("git@git.example.com:team/notes.git")
        XCTAssertEqual(ssh?.repoName, "notes")
        XCTAssertEqual(ssh?.ownerName, "team")
        XCTAssertEqual(ssh?.username, "git")
        XCTAssertEqual(ssh?.isSSH, true)
    }

    func testGitRemoteCredentialsTransportPayloadRoundTripsAndSupportsLegacyPAT() {
        let credentials = GitRemoteCredentials.sshKey(
            username: "git",
            privateKey: "-----BEGIN OPENSSH PRIVATE KEY-----\nkey\n-----END OPENSSH PRIVATE KEY-----",
            publicKey: "ssh-ed25519 AAAA test",
            passphrase: "secret"
        )

        let decoded = GitRemoteCredentials.fromTransportPayload(credentials.transportPayload)
        XCTAssertEqual(decoded, credentials)

        let legacy = GitRemoteCredentials.fromTransportPayload("ghp_legacy")
        XCTAssertEqual(legacy.method, .gitHubPAT)
        XCTAssertEqual(legacy.username, "x-access-token")
        XCTAssertEqual(legacy.password, "ghp_legacy")
    }

    @MainActor
    func testAppStatePullBlockedByLocalChangesDoesNotMutateRepoState() async throws {
        let fixture = try GitFixtureFactory.make(state: .dirty)
        defer { fixture.cleanup() }

        fixture.repository.pullPlanResult = PullPlan(
            action: .blockedByLocalChanges,
            branch: "main",
            localCommitSHA: fixture.repoConfig.gitState.commitSHA,
            remoteCommitSHA: "9999999999999999999999999999999999999999",
            hasLocalChanges: true,
            aheadBy: 0,
            behindBy: 1
        )

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.pull(repoID: fixture.repoConfig.id)

        XCTAssertEqual(appState.repos.first?.gitState.commitSHA, fixture.repoConfig.gitState.commitSHA)
        XCTAssertEqual(appState.pullOutcomeByRepo[fixture.repoConfig.id]?.kind, .blockedByLocalChanges)
    }

    @MainActor
    func testAppStatePullFastForwardUpdatesCommitAndOutcome() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        let newCommit = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        fixture.repository.pullPlanResult = PullPlan(
            action: .fastForward,
            branch: "main",
            localCommitSHA: fixture.repoConfig.gitState.commitSHA,
            remoteCommitSHA: newCommit,
            hasLocalChanges: false,
            aheadBy: 0,
            behindBy: 1
        )
        fixture.repository.pullResult = .success(LocalPullResult(updated: true, newCommitSHA: newCommit))

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.pull(repoID: fixture.repoConfig.id)

        XCTAssertEqual(appState.repos.first?.gitState.commitSHA, newCommit)
        XCTAssertEqual(appState.pullOutcomeByRepo[fixture.repoConfig.id]?.kind, .fastForwarded)
    }

    @MainActor
    func testAppStateLoadUnifiedDiffStoresDiffByRepo() async throws {
        let fixture = try GitFixtureFactory.make(state: .dirty)
        defer { fixture.cleanup() }

        let expectedDiff = UnifiedDiffResult(
            files: [
                GitFileDiff(
                    path: "Inbox.md",
                    oldPath: "Inbox.md",
                    newPath: "Inbox.md",
                    changeType: .modified,
                    isBinary: false,
                    patch: "diff --git a/Inbox.md b/Inbox.md\n"
                )
            ],
            rawPatch: "diff --git a/Inbox.md b/Inbox.md\n"
        )
        fixture.repository.diffResult = expectedDiff

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.loadUnifiedDiff(repoID: fixture.repoConfig.id)

        XCTAssertEqual(appState.diffByRepo[fixture.repoConfig.id], expectedDiff)
    }

    @MainActor
    func testAppStateLoadCommitHistoryStoresAndPaginatesByRepo() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        let pageData = [
            GitCommitSummary(
                oid: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                shortOID: "aaaaaaa",
                message: "Third",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                authoredDate: Date(timeIntervalSince1970: 300)
            ),
            GitCommitSummary(
                oid: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                shortOID: "bbbbbbb",
                message: "Second",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                authoredDate: Date(timeIntervalSince1970: 200)
            ),
            GitCommitSummary(
                oid: "cccccccccccccccccccccccccccccccccccccccc",
                shortOID: "ccccccc",
                message: "First",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                authoredDate: Date(timeIntervalSince1970: 100)
            )
        ]

        fixture.repository.commitHistoryResult = pageData

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.loadCommitHistory(repoID: fixture.repoConfig.id, pageSize: 2, reset: true)

        XCTAssertEqual(appState.commitHistoryByRepo[fixture.repoConfig.id]?.map(\.oid), [
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        ])
        XCTAssertEqual(appState.commitHistoryHasMoreByRepo[fixture.repoConfig.id], true)

        await appState.loadCommitHistory(repoID: fixture.repoConfig.id, pageSize: 2, reset: false)

        XCTAssertEqual(appState.commitHistoryByRepo[fixture.repoConfig.id]?.count, 3)
        XCTAssertEqual(appState.commitHistoryHasMoreByRepo[fixture.repoConfig.id], false)
    }

    @MainActor
    func testAppStateSaveApplyPopStashDelegatesToGitRepository() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.saveStash(repoID: fixture.repoConfig.id, message: "WIP", includeUntracked: true)
        await appState.applyStash(repoID: fixture.repoConfig.id, index: 0, reinstateIndex: false)
        await appState.popStash(repoID: fixture.repoConfig.id, index: 0, reinstateIndex: false)

        XCTAssertEqual(fixture.repository.savedStashes.count, 1)
        XCTAssertEqual(fixture.repository.savedStashes.first?.message, "WIP")
        XCTAssertEqual(fixture.repository.appliedStashIndices, [0])
        XCTAssertEqual(fixture.repository.poppedStashIndices, [0])
    }

    @MainActor
    func testAppStateTagLifecycleDelegatesToGitRepository() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        // Create lightweight
        await appState.createTag(repoID: fixture.repoConfig.id, name: "v1.0")
        XCTAssertEqual(fixture.repository.createdTags.count, 1)
        XCTAssertEqual(fixture.repository.createdTags.first?.name, "v1.0")
        XCTAssertNil(fixture.repository.createdTags.first?.message)

        // Create annotated
        await appState.createTag(repoID: fixture.repoConfig.id, name: "v2.0", message: "Release 2")
        XCTAssertEqual(fixture.repository.createdTags.count, 2)
        XCTAssertEqual(fixture.repository.createdTags[1].message, "Release 2")

        // Push
        await appState.pushTag(repoID: fixture.repoConfig.id, name: "v1.0")
        XCTAssertEqual(fixture.repository.pushedTagNames, ["v1.0"])

        // Delete
        await appState.deleteTag(repoID: fixture.repoConfig.id, name: "v1.0")
        XCTAssertEqual(fixture.repository.deletedTagNames, ["v1.0"])
    }

    @MainActor
    func testAppStateLoadTagsStoresTagsByRepo() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        fixture.repository.tagsResult = [
            GitTag(name: "refs/tags/v1.0", oid: "aabb", kind: .lightweight, message: nil, targetOID: "ccdd"),
            GitTag(name: "refs/tags/v2.0", oid: "eeff", kind: .annotated, message: "Release 2", targetOID: "1122")
        ]

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.loadTags(repoID: fixture.repoConfig.id)

        XCTAssertEqual(appState.tagsByRepo[fixture.repoConfig.id]?.count, 2)
        XCTAssertEqual(appState.tagsByRepo[fixture.repoConfig.id]?.first?.shortName, "v1.0")
    }

    @MainActor
    func testAppStateDropStashDelegatesToGitRepository() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        fixture.repository.stashEntriesResult = [
            GitStashEntry(index: 0, oid: "aabbcc", message: "WIP: feature")
        ]

        await appState.dropStash(repoID: fixture.repoConfig.id, index: 0)

        XCTAssertEqual(fixture.repository.droppedStashIndices, [0])
        XCTAssertTrue(fixture.repository.stashEntriesResult.isEmpty)
    }

    @MainActor
    func testAppStateLoadCommitDetailStoresByRepoAndOID() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        let oid = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        fixture.repository.commitDetailResultByOID[oid] = GitCommitDetail(
            oid: oid,
            message: "Add README",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com",
            authoredDate: Date(timeIntervalSince1970: 100),
            committerName: "SyncMD Tests",
            committerEmail: "tests@example.com",
            committedDate: Date(timeIntervalSince1970: 100),
            parentOIDs: [],
            changedFiles: [
                GitCommitFileChange(path: "README.md", oldPath: nil, newPath: "README.md", changeType: .added)
            ]
        )

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.loadCommitDetail(repoID: fixture.repoConfig.id, oid: oid)

        XCTAssertEqual(appState.commitDetailByRepo[fixture.repoConfig.id]?[oid]?.message, "Add README")
    }

    @MainActor
    func testAppStateStageAndUnstageDelegateToGitRepository() async throws {
        let fixture = try GitFixtureFactory.make(state: .dirty)
        defer { fixture.cleanup() }

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.stageFile(repoID: fixture.repoConfig.id, path: "Inbox.md")
        await appState.unstageFile(repoID: fixture.repoConfig.id, path: "Inbox.md")

        XCTAssertEqual(fixture.repository.stagedPaths, ["Inbox.md"])
        XCTAssertEqual(fixture.repository.unstagedPaths, ["Inbox.md"])
    }

    @MainActor
    func testAppStateLoadBranchesStoresInventoryByRepo() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        let expected = BranchInventory(
            local: [
                GitBranchInfo(
                    name: "refs/heads/main",
                    shortName: "main",
                    scope: .local,
                    isCurrent: true,
                    upstreamShortName: "origin/main",
                    aheadBy: 0,
                    behindBy: 0
                )
            ],
            remote: [
                GitBranchInfo(
                    name: "refs/remotes/origin/main",
                    shortName: "origin/main",
                    scope: .remote,
                    isCurrent: false,
                    upstreamShortName: nil,
                    aheadBy: nil,
                    behindBy: nil
                )
            ],
            detachedHeadOID: nil
        )

        fixture.repository.branchInventoryResult = expected

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.loadBranches(repoID: fixture.repoConfig.id)

        XCTAssertEqual(appState.branchesByRepo[fixture.repoConfig.id], expected)
    }

    @MainActor
    func testAppStateCreateSwitchDeleteBranchDelegatesToGitRepository() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.createBranch(repoID: fixture.repoConfig.id, name: "feature")
        await appState.switchBranch(repoID: fixture.repoConfig.id, name: "feature")
        await appState.deleteBranch(repoID: fixture.repoConfig.id, name: "feature")

        XCTAssertEqual(fixture.repository.createdBranches, ["feature"])
        XCTAssertEqual(fixture.repository.switchedBranches, ["feature"])
        XCTAssertEqual(fixture.repository.deletedBranches, ["feature"])
    }

    @MainActor
    func testAppStateMergeBranchUpdatesCommitFromResult() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        let mergedSHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        fixture.repository.mergeResult = MergeResult(kind: .fastForwarded, sourceBranch: "feature", newCommitSHA: mergedSHA)

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.mergeBranch(repoID: fixture.repoConfig.id, from: "feature")

        XCTAssertEqual(appState.repos.first?.gitState.commitSHA, mergedSHA)
    }

    @MainActor
    func testAppStateRevertCommitUpdatesCommitOnSuccessfulRevert() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        let revertedSHA = "dddddddddddddddddddddddddddddddddddddddd"
        fixture.repository.revertResult = RevertResult(
            kind: .reverted,
            targetOID: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            newCommitSHA: revertedSHA
        )

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.revertCommit(repoID: fixture.repoConfig.id, oid: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", message: "Revert")

        XCTAssertEqual(appState.repos.first?.gitState.commitSHA, revertedSHA)
    }

    @MainActor
    func testAppStateCompleteMergeUpdatesCommitFromResult() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        let finalizedSHA = "cccccccccccccccccccccccccccccccccccccccc"
        fixture.repository.mergeFinalizeResult = MergeFinalizeResult(newCommitSHA: finalizedSHA)

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.completeMerge(repoID: fixture.repoConfig.id, message: "Resolve merge")

        XCTAssertEqual(appState.repos.first?.gitState.commitSHA, finalizedSHA)
    }

    @MainActor
    func testAppStateAbortMergeDelegatesToGitRepository() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.abortMerge(repoID: fixture.repoConfig.id)

        XCTAssertTrue(fixture.repository.didAbortMerge)
    }

    @MainActor
    func testAppStateLoadConflictSessionStoresByRepo() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        fixture.repository.conflictSessionResult = ConflictSession(
            kind: .merge,
            unmergedPaths: ["README.md", "notes/today.md"]
        )

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.loadConflictSession(repoID: fixture.repoConfig.id)

        XCTAssertEqual(appState.conflictSessionByRepo[fixture.repoConfig.id]?.kind, .merge)
        XCTAssertEqual(appState.conflictSessionByRepo[fixture.repoConfig.id]?.unmergedPaths, ["README.md", "notes/today.md"])
    }

    @MainActor
    func testAppStateResolveConflictFileDelegatesToGitRepository() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        fixture.repository.conflictSessionResult = ConflictSession(kind: .merge, unmergedPaths: ["README.md"])

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.resolveConflictFile(repoID: fixture.repoConfig.id, path: "README.md", strategy: .ours)

        XCTAssertEqual(fixture.repository.resolvedConflicts.count, 1)
        XCTAssertEqual(fixture.repository.resolvedConflicts.first?.path, "README.md")
        XCTAssertEqual(fixture.repository.resolvedConflicts.first?.strategy, .ours)
    }

    func testLocalGitServiceListBranchesReportsCurrentLocalBranch() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-BranchInventory-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)

        let readme = repoURL.appendingPathComponent("README.md")
        try "# Branch Test\n".write(to: readme, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")

        do {
            _ = try await service.commitAndPush(
                message: "Initial commit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
            XCTFail("Expected push to fail without origin remote")
        } catch {
            // Expected.
        }

        let repoInfo = try await service.repoInfo()
        let inventory = try await service.listBranches()

        XCTAssertTrue(inventory.remote.isEmpty)
        XCTAssertTrue(inventory.local.contains(where: { $0.shortName == repoInfo.branch && $0.isCurrent }))
    }

    func testLocalGitServiceCommitHistoryReturnsDeterministicPages() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-HistoryPages-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)
        let file = repoURL.appendingPathComponent("README.md")

        for idx in 1...3 {
            try "commit \(idx)\n".write(to: file, atomically: true, encoding: .utf8)
            try await service.stage(path: "README.md")
            do {
                _ = try await service.commitAndPush(
                    message: "Commit \(idx)",
                    authorName: "SyncMD Tests",
                    authorEmail: "tests@example.com",
                    pat: ""
                )
            } catch {
                // Expected push failure; commit still created.
            }
        }

        let firstPage = try await service.commitHistory(limit: 2, skip: 0)
        let secondPage = try await service.commitHistory(limit: 2, skip: 2)

        XCTAssertEqual(firstPage.count, 2)
        XCTAssertEqual(secondPage.count, 1)
        XCTAssertEqual(firstPage.first?.message, "Commit 3")
        XCTAssertEqual(firstPage.last?.message, "Commit 2")
        XCTAssertEqual(secondPage.first?.message, "Commit 1")
    }

    func testLocalGitServiceCommitDetailIncludesParentAndChangedFiles() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-CommitDetail-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)
        let file = repoURL.appendingPathComponent("README.md")

        try "initial\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Initial",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch {
            // Expected push failure.
        }

        try "updated\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Update README",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch {
            // Expected push failure.
        }

        let latest = try await service.commitHistory(limit: 1, skip: 0)
        let oid = try XCTUnwrap(latest.first?.oid)

        let detail = try await service.commitDetail(oid: oid)
        XCTAssertEqual(detail.oid, oid)
        XCTAssertEqual(detail.message, "Update README")
        XCTAssertEqual(detail.parentOIDs.count, 1)
        XCTAssertTrue(detail.changedFiles.contains(where: { $0.path == "README.md" && $0.changeType == .modified }))
    }

    func testLocalGitServiceStashSaveAndApplyRoundtrip() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-StashRoundtrip-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)
        let file = repoURL.appendingPathComponent("README.md")

        try "base\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Initial",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        try "work in progress\n".write(to: file, atomically: true, encoding: .utf8)

        _ = try await service.saveStash(
            message: "WIP",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com",
            includeUntracked: true
        )

        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), "base\n")

        let stashes = try await service.listStashes()
        XCTAssertEqual(stashes.count, 1)
        XCTAssertTrue(stashes[0].message.contains("WIP"))

        let applyResult = try await service.applyStash(index: 0, reinstateIndex: false)
        XCTAssertEqual(applyResult.kind, .applied)
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), "work in progress\n")
    }

    func testLocalGitServiceStashPopRemovesEntry() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-StashPop-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)
        let file = repoURL.appendingPathComponent("README.md")

        try "base\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Initial",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        try "stash me\n".write(to: file, atomically: true, encoding: .utf8)
        _ = try await service.saveStash(
            message: "stash",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com",
            includeUntracked: true
        )

        let beforePop = try await service.listStashes()
        XCTAssertEqual(beforePop.count, 1)

        let popResult = try await service.popStash(index: 0, reinstateIndex: false)
        XCTAssertEqual(popResult.kind, .applied)
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), "stash me\n")

        let afterPop = try await service.listStashes()
        XCTAssertTrue(afterPop.isEmpty)
    }

    func testLocalGitServiceStashDropRemovesWithoutApplying() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-StashDrop-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)
        let file = repoURL.appendingPathComponent("README.md")

        try "base\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Initial",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        try "drop me\n".write(to: file, atomically: true, encoding: .utf8)
        _ = try await service.saveStash(
            message: "to be dropped",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com",
            includeUntracked: true
        )

        let beforeDrop = try await service.listStashes()
        XCTAssertEqual(beforeDrop.count, 1)
        // File should be back to base after stashing
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), "base\n")

        try await service.dropStash(index: 0)

        let afterDrop = try await service.listStashes()
        XCTAssertTrue(afterDrop.isEmpty)
        // File stays at base — stash was discarded, not applied
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), "base\n")
    }

    func testLocalGitServiceTagLightweightCreateListDelete() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-TagLW-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)
        let file = repoURL.appendingPathComponent("README.md")
        try "base\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do { _ = try await service.commitAndPush(message: "Initial", authorName: "T", authorEmail: "t@t.com", pat: "") } catch {}

        // Create lightweight
        let tag = try await service.createTag(name: "v1.0", targetOID: nil, message: nil, authorName: "T", authorEmail: "t@t.com")
        XCTAssertEqual(tag.shortName, "v1.0")
        XCTAssertEqual(tag.kind, .lightweight)

        // List
        let tags = try await service.listTags()
        XCTAssertEqual(tags.count, 1)
        XCTAssertEqual(tags.first?.shortName, "v1.0")

        // Delete
        try await service.deleteTag(name: "v1.0")
        let afterDelete = try await service.listTags()
        XCTAssertTrue(afterDelete.isEmpty)
    }

    func testLocalGitServiceTagAnnotatedCreateListDelete() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-TagAnnotated-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)
        let file = repoURL.appendingPathComponent("README.md")
        try "base\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do { _ = try await service.commitAndPush(message: "Initial", authorName: "T", authorEmail: "t@t.com", pat: "") } catch {}

        // Create annotated
        let tag = try await service.createTag(name: "v2.0-annotated", targetOID: nil, message: "Release 2.0", authorName: "T", authorEmail: "t@t.com")
        XCTAssertEqual(tag.shortName, "v2.0-annotated")
        XCTAssertEqual(tag.kind, .annotated)
        XCTAssertEqual(tag.message, "Release 2.0")

        // List
        let tags = try await service.listTags()
        XCTAssertEqual(tags.count, 1)
        XCTAssertEqual(tags.first?.kind, .annotated)
        XCTAssertEqual(tags.first?.message, "Release 2.0")

        // Delete
        try await service.deleteTag(name: "v2.0-annotated")
        let afterDelete = try await service.listTags()
        XCTAssertTrue(afterDelete.isEmpty)
    }

    func testLocalGitServiceTagDuplicateThrows() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-TagDup-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)
        let file = repoURL.appendingPathComponent("README.md")
        try "base\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do { _ = try await service.commitAndPush(message: "Initial", authorName: "T", authorEmail: "t@t.com", pat: "") } catch {}

        _ = try await service.createTag(name: "v1.0", targetOID: nil, message: nil, authorName: "T", authorEmail: "t@t.com")

        do {
            _ = try await service.createTag(name: "v1.0", targetOID: nil, message: nil, authorName: "T", authorEmail: "t@t.com")
            XCTFail("Expected tagAlreadyExists error")
        } catch LocalGitError.tagAlreadyExists(let name) {
            XCTAssertEqual(name, "v1.0")
        }
    }

    func testLocalGitServiceDeleteNonexistentTagThrows() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-TagMissing-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)
        let file = repoURL.appendingPathComponent("README.md")
        try "base\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do { _ = try await service.commitAndPush(message: "Initial", authorName: "T", authorEmail: "t@t.com", pat: "") } catch {}

        do {
            try await service.deleteTag(name: "nonexistent")
            XCTFail("Expected tagNotFound error")
        } catch LocalGitError.tagNotFound(let name) {
            XCTAssertEqual(name, "nonexistent")
        }
    }

    func testLocalGitServiceRevertCommitCleanPathCreatesRevertCommit() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-RevertClean-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)
        let file = repoURL.appendingPathComponent("README.md")

        try "base\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Initial",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        try "change\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Change README",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        let targetOID = try await service.repoInfo().commitSHA

        let revert = try await service.revertCommit(
            oid: targetOID,
            message: "Revert README change",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com"
        )

        XCTAssertEqual(revert.kind, .reverted)
        XCTAssertEqual(revert.targetOID, targetOID)
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), "base\n")

        let session = try await service.conflictSession()
        XCTAssertEqual(session, .none)
    }

    func testLocalGitServiceRevertCommitConflictPathReturnsConflictResult() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-RevertConflict-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)
        let file = repoURL.appendingPathComponent("README.md")

        try "base\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Initial",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        try "one\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Commit A",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }
        let commitAOID = try await service.repoInfo().commitSHA

        try "two\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Commit B",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        let revert = try await service.revertCommit(
            oid: commitAOID,
            message: "",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com"
        )

        XCTAssertEqual(revert.kind, .conflicts)
        XCTAssertEqual(revert.targetOID, commitAOID)
        XCTAssertNil(revert.newCommitSHA)

        let session = try await service.conflictSession()
        XCTAssertEqual(session.kind, .revert)
        XCTAssertTrue(session.unmergedPaths.contains("README.md"))
    }

    func testLocalGitServiceSwitchBranchBlockedWhenDirtyWorkingTree() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-BranchSwitchDirty-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)

        let readme = repoURL.appendingPathComponent("README.md")
        try "initial\n".write(to: readme, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")

        do {
            _ = try await service.commitAndPush(
                message: "Initial commit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch {
            // Expected push failure; commit still created.
        }

        try await service.createBranch(name: "feature")

        try "dirty worktree\n".write(to: readme, atomically: true, encoding: .utf8)

        do {
            try await service.switchBranch(name: "feature")
            XCTFail("Expected switch to be blocked when working tree is dirty")
        } catch let error as LocalGitError {
            guard case .checkoutBlockedByLocalChanges = error else {
                XCTFail("Unexpected error: \(error.localizedDescription)")
                return
            }
        }
    }

    func testLocalGitServiceCreateSwitchDeleteBranchLifecycle() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-BranchLifecycle-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)

        let readme = repoURL.appendingPathComponent("README.md")
        try "initial\n".write(to: readme, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")

        do {
            _ = try await service.commitAndPush(
                message: "Initial commit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch {
            // Expected push failure; commit still created.
        }

        let current = try await service.repoInfo().branch

        try await service.createBranch(name: "feature")
        var inventory = try await service.listBranches()
        XCTAssertTrue(inventory.local.contains(where: { $0.shortName == "feature" }))

        try await service.switchBranch(name: "feature")
        let switchedInfo = try await service.repoInfo()
        XCTAssertEqual(switchedInfo.branch, "feature")

        try await service.switchBranch(name: current)
        try await service.deleteBranch(name: "feature")

        inventory = try await service.listBranches()
        XCTAssertFalse(inventory.local.contains(where: { $0.shortName == "feature" }))
    }

    func testLocalGitServiceMergeBranchFastForward() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-MergeFF-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)

        let file = repoURL.appendingPathComponent("README.md")
        try "initial\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Initial",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch {
            // Expected push failure; commit still created.
        }

        let mainBranch = try await service.repoInfo().branch

        try await service.createBranch(name: "feature")
        try await service.switchBranch(name: "feature")

        try "feature change\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Feature commit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch {
            // Expected push failure; commit still created.
        }

        let featureSHA = try await service.repoInfo().commitSHA

        try await service.switchBranch(name: mainBranch)
        let mergeResult = try await service.mergeBranch(name: "feature", authorName: "Tester", authorEmail: "tests@example.com")

        XCTAssertEqual(mergeResult.kind, .fastForwarded)
        XCTAssertEqual(mergeResult.newCommitSHA, featureSHA)
        let postMergeInfo = try await service.repoInfo()
        XCTAssertEqual(postMergeInfo.commitSHA, featureSHA)
    }

    func testLocalGitServiceMergeBranchCreatesMergeCommitWhenDiverged() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-MergeCommit-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)

        let mainFile = repoURL.appendingPathComponent("Main.md")
        let featureFile = repoURL.appendingPathComponent("Feature.md")

        try "base\n".write(to: mainFile, atomically: true, encoding: .utf8)
        try await service.stage(path: "Main.md")
        do {
            _ = try await service.commitAndPush(
                message: "Initial",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch {
            // Expected push failure; commit still created.
        }

        let mainBranch = try await service.repoInfo().branch

        try await service.createBranch(name: "feature")
        try await service.switchBranch(name: "feature")

        try "feature side\n".write(to: featureFile, atomically: true, encoding: .utf8)
        try await service.stage(path: "Feature.md")
        do {
            _ = try await service.commitAndPush(
                message: "Feature commit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch {
            // Expected push failure; commit still created.
        }

        try await service.switchBranch(name: mainBranch)

        try "main side\n".write(to: mainFile, atomically: true, encoding: .utf8)
        try await service.stage(path: "Main.md")
        do {
            _ = try await service.commitAndPush(
                message: "Main commit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch {
            // Expected push failure; commit still created.
        }

        let mergeResult = try await service.mergeBranch(name: "feature", authorName: "Tester", authorEmail: "tests@example.com")

        XCTAssertEqual(mergeResult.kind, .mergeCommitted)
        let mergedInfo = try await service.repoInfo()
        XCTAssertEqual(mergedInfo.branch, mainBranch)
    }

    func testLocalGitServiceConflictSessionReportsMergeConflicts() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-MergeConflictSession-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)

        let file = repoURL.appendingPathComponent("README.md")
        try "line\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Initial",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch {
            // Expected push failure; commit still created.
        }

        let mainBranch = try await service.repoInfo().branch

        try await service.createBranch(name: "feature")
        try await service.switchBranch(name: "feature")
        try "feature\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Feature edit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch {
            // Expected push failure; commit still created.
        }

        try await service.switchBranch(name: mainBranch)
        try "main\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Main edit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch {
            // Expected push failure; commit still created.
        }

        do {
            _ = try await service.mergeBranch(name: "feature", authorName: "Tester", authorEmail: "tests@example.com")
            XCTFail("Expected conflict during merge")
        } catch {
            guard let gitError = error as? LocalGitError else {
                XCTFail("Expected LocalGitError")
                return
            }
            if case .mergeConflictsDetected = gitError {
                // expected
            } else {
                XCTFail("Expected mergeConflictsDetected, got \(gitError)")
            }
        }

        let conflictSession = try await service.conflictSession()
        XCTAssertEqual(conflictSession.kind, .merge)
        XCTAssertTrue(conflictSession.unmergedPaths.contains("README.md"))
    }

    func testLocalGitServiceResolveConflictWithTheirsClearsConflictState() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-ResolveConflictTheirs-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)

        let file = repoURL.appendingPathComponent("README.md")
        try "line\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Initial",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        let mainBranch = try await service.repoInfo().branch

        try await service.createBranch(name: "feature")
        try await service.switchBranch(name: "feature")
        try "feature\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Feature edit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        try await service.switchBranch(name: mainBranch)
        try "main\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Main edit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        do {
            _ = try await service.mergeBranch(name: "feature", authorName: "Tester", authorEmail: "tests@example.com")
            XCTFail("Expected merge conflict")
        } catch { }

        try await service.resolveConflict(path: "README.md", strategy: .theirs)

        let session = try await service.conflictSession()
        XCTAssertFalse(session.unmergedPaths.contains("README.md"))
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), "feature\n")

        let repoInfo = try await service.repoInfo()
        XCTAssertFalse(repoInfo.statusEntries.contains(where: { $0.path == "README.md" && $0.isConflicted }))
    }

    func testLocalGitServiceResolveConflictManualClearsConflictState() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-ResolveConflictManual-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)

        let file = repoURL.appendingPathComponent("README.md")
        try "line\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Initial",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        let mainBranch = try await service.repoInfo().branch

        try await service.createBranch(name: "feature")
        try await service.switchBranch(name: "feature")
        try "feature\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Feature edit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        try await service.switchBranch(name: mainBranch)
        try "main\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Main edit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        do {
            _ = try await service.mergeBranch(name: "feature", authorName: "Tester", authorEmail: "tests@example.com")
            XCTFail("Expected merge conflict")
        } catch { }

        try "manual resolution\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.resolveConflict(path: "README.md", strategy: .manual)

        let session = try await service.conflictSession()
        XCTAssertFalse(session.unmergedPaths.contains("README.md"))
        XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), "manual resolution\n")

        let repoInfo = try await service.repoInfo()
        XCTAssertFalse(repoInfo.statusEntries.contains(where: { $0.path == "README.md" && $0.isConflicted }))
    }

    func testLocalGitServiceCompleteMergeCreatesCommitAndCleansState() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-CompleteMerge-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)

        let file = repoURL.appendingPathComponent("README.md")
        try "line\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Initial",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        let mainBranch = try await service.repoInfo().branch

        try await service.createBranch(name: "feature")
        try await service.switchBranch(name: "feature")
        try "feature\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Feature edit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        try await service.switchBranch(name: mainBranch)
        try "main\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Main edit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
        } catch { }

        do {
            _ = try await service.mergeBranch(name: "feature", authorName: "Tester", authorEmail: "tests@example.com")
            XCTFail("Expected merge conflict")
        } catch { }

        try "resolved content\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.resolveConflict(path: "README.md", strategy: .manual)

        let result = try await service.completeMerge(
            message: "Resolve conflict",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com"
        )

        XCTAssertEqual(result.newCommitSHA.count, 40)

        let session = try await service.conflictSession()
        XCTAssertEqual(session, .none)

        let info = try await service.repoInfo()
        XCTAssertEqual(info.changeCount, 0)
        XCTAssertEqual(info.commitSHA, result.newCommitSHA)
    }

    func testLocalGitServiceAbortMergeRestoresHeadAndClearsState() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-AbortMerge-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)

        let file = repoURL.appendingPathComponent("README.md")
        try "line\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        try await commitLocalFixtureChanges(using: service, message: "Initial")

        let mainBranch = try await service.repoInfo().branch

        try await service.createBranch(name: "feature")
        try await service.switchBranch(name: "feature")
        try "feature\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        try await commitLocalFixtureChanges(using: service, message: "Feature edit")

        try await service.switchBranch(name: mainBranch)
        try "main\n".write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        try await commitLocalFixtureChanges(using: service, message: "Main edit")

        do {
            _ = try await service.mergeBranch(name: "feature", authorName: "Tester", authorEmail: "tests@example.com")
            XCTFail("Expected merge conflict")
        } catch LocalGitError.mergeConflictsDetected {
            let conflictSession = try await service.conflictSession()
            XCTAssertEqual(conflictSession.kind, .merge)
            XCTAssertTrue(conflictSession.unmergedPaths.contains("README.md"))
        } catch {
            XCTFail("Expected mergeConflictsDetected, got: \(error)")
            throw error
        }

        try await service.abortMerge()

        let session = try await service.conflictSession()
        XCTAssertEqual(session, .none)

        let fileContents = try String(contentsOf: file, encoding: .utf8)
        XCTAssertEqual(fileContents, "main\n")

        let info = try await service.repoInfo()
        XCTAssertEqual(info.changeCount, 0)
    }

    func testLocalGitServiceCommitAndPushUsesStagedIndexOnly() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-StagedOnly-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)

        let fileA = repoURL.appendingPathComponent("A.md")
        let fileB = repoURL.appendingPathComponent("B.md")

        try "alpha\n".write(to: fileA, atomically: true, encoding: .utf8)
        try "bravo\n".write(to: fileB, atomically: true, encoding: .utf8)

        try await service.stage(path: "A.md")
        try await service.stage(path: "B.md")

        do {
            _ = try await service.commitAndPush(
                message: "Initial commit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
            XCTFail("Expected push to fail without origin remote")
        } catch {
            // Expected: commit succeeds, push fails due missing origin.
        }

        let cleanInfo = try await service.repoInfo()
        XCTAssertEqual(cleanInfo.changeCount, 0)

        try "alpha changed\n".write(to: fileA, atomically: true, encoding: .utf8)
        try "bravo changed\n".write(to: fileB, atomically: true, encoding: .utf8)

        try await service.stage(path: "A.md")

        do {
            _ = try await service.commitAndPush(
                message: "Commit staged only",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
            XCTFail("Expected push to fail without origin remote")
        } catch {
            // Expected push failure.
        }

        let info = try await service.repoInfo()
        XCTAssertEqual(info.changeCount, 1, "Unstaged file should remain modified after commit")

        let remaining = info.statusEntries.first { $0.path == "B.md" }
        XCTAssertEqual(remaining?.workTreeStatus, .modified)
        XCTAssertNil(remaining?.indexStatus)
        XCTAssertFalse(info.statusEntries.contains { $0.path == "A.md" }, "Staged file should be committed and clean")
    }

    func testLocalGitServiceStagesDeletionsRenamesAndMoves() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-StageDeletions-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)

        let keep = repoURL.appendingPathComponent("keep.md")
        let doomed = repoURL.appendingPathComponent("doomed.md")
        let renameOld = repoURL.appendingPathComponent("old-name.md")
        let subdir = repoURL.appendingPathComponent("subdir", isDirectory: true)
        try fm.createDirectory(at: subdir, withIntermediateDirectories: true)
        let moveOld = subdir.appendingPathComponent("mover.md")

        try "keep\n".write(to: keep, atomically: true, encoding: .utf8)
        try "doomed\n".write(to: doomed, atomically: true, encoding: .utf8)
        try "rename me\n".write(to: renameOld, atomically: true, encoding: .utf8)
        try "move me\n".write(to: moveOld, atomically: true, encoding: .utf8)

        try await service.stage(path: "keep.md")
        try await service.stage(path: "doomed.md")
        try await service.stage(path: "old-name.md")
        try await service.stage(path: "subdir/mover.md")

        try await commitLocalFixtureChanges(using: service, message: "Initial")

        let cleanInfo = try await service.repoInfo()
        XCTAssertEqual(cleanInfo.changeCount, 0)

        // Deletion: remove a tracked file from disk.
        try fm.removeItem(at: doomed)

        // Rename: remove the old file and create a new file with a new name.
        try fm.removeItem(at: renameOld)
        let renameNew = repoURL.appendingPathComponent("new-name.md")
        try "rename me\n".write(to: renameNew, atomically: true, encoding: .utf8)

        // Move to a different folder: same as rename across directories.
        try fm.removeItem(at: moveOld)
        let moveNewDir = repoURL.appendingPathComponent("other", isDirectory: true)
        try fm.createDirectory(at: moveNewDir, withIntermediateDirectories: true)
        let moveNew = moveNewDir.appendingPathComponent("mover.md")
        try "move me\n".write(to: moveNew, atomically: true, encoding: .utf8)

        // Staging the old halves (files no longer on disk) must succeed and
        // record the removal in the index. Before the fix, stage() called
        // git_index_add_bypath which requires the file to exist, so these
        // calls silently failed and the commit kept the old paths.
        try await service.stage(path: "doomed.md")
        try await service.stage(path: "old-name.md")
        try await service.stage(path: "subdir/mover.md")

        // Staging the new halves adds the new paths to the index.
        try await service.stage(path: "new-name.md")
        try await service.stage(path: "other/mover.md")

        try await commitLocalFixtureChanges(using: service, message: "Delete, rename, move")

        let afterInfo = try await service.repoInfo()
        XCTAssertEqual(afterInfo.changeCount, 0, "All deletions/renames/moves should be committed and the working tree clean")
        XCTAssertFalse(afterInfo.statusEntries.contains { $0.path == "doomed.md" })
        XCTAssertFalse(afterInfo.statusEntries.contains { $0.path == "old-name.md" })
        XCTAssertFalse(afterInfo.statusEntries.contains { $0.path == "new-name.md" })
        XCTAssertFalse(afterInfo.statusEntries.contains { $0.path == "subdir/mover.md" })
        XCTAssertFalse(afterInfo.statusEntries.contains { $0.path == "other/mover.md" })

        // Verify the HEAD tree actually reflects the deletions/renames/moves
        // by inspecting the latest commit's changed files.
        let latest = try await service.commitHistory(limit: 1, skip: 0)
        let oid = try XCTUnwrap(latest.first?.oid)
        let detail = try await service.commitDetail(oid: oid)

        let deletedPaths = detail.changedFiles
            .filter { $0.changeType == .deleted }
            .map(\.path)
            .sorted()
        XCTAssertTrue(deletedPaths.contains("doomed.md"), "Deleted file must appear as deleted in commit detail")

        // Rename/move may appear either as add+delete or as a rename delta
        // depending on libgit2's similarity detection. In both cases the new
        // path must be present in the commit's change set and the old path must
        // not still be tracked in HEAD.
        let changedPaths = Set(detail.changedFiles.map(\.path))
        XCTAssertTrue(changedPaths.contains("new-name.md"), "Renamed file's new path must appear in commit")
        XCTAssertFalse(changedPaths.contains("old-name.md") && !deletedPaths.contains("old-name.md"),
                       "old-name.md may only appear in commit as a deletion, not still tracked")
        XCTAssertTrue(changedPaths.contains("other/mover.md"), "Moved file's new path must appear in commit")
        XCTAssertFalse(changedPaths.contains("subdir/mover.md") && !deletedPaths.contains("subdir/mover.md"),
                       "subdir/mover.md may only appear in commit as a deletion, not still tracked")
    }

    func testLocalGitServiceUnifiedDiffShowsStagedOnlyJSONChanges() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-JSONDiff-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)
        let configDir = repoURL.appendingPathComponent("config", isDirectory: true)
        try fm.createDirectory(at: configDir, withIntermediateDirectories: true)
        let file = configDir.appendingPathComponent("settings.json")

        try """
        {
          "theme": "light"
        }
        """.write(to: file, atomically: true, encoding: .utf8)

        try await service.stage(path: "config/settings.json")
        do {
            _ = try await service.commitAndPush(
                message: "Initial config",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
            XCTFail("Expected push to fail without origin remote")
        } catch {
            // Expected: commit succeeds, push fails due missing origin.
        }

        try """
        {
          "theme": "dark"
        }
        """.write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "config/settings.json")

        let diff = try await service.unifiedDiff(path: "config/settings.json")

        XCTAssertEqual(diff.files.count, 1)
        XCTAssertEqual(diff.files.first?.path, "config/settings.json")
        XCTAssertEqual(diff.files.first?.changeType, .modified)
        XCTAssertFalse(diff.rawPatch.isEmpty)
        XCTAssertTrue(diff.rawPatch.contains("diff --git a/config/settings.json b/config/settings.json"))
        XCTAssertTrue(diff.rawPatch.contains("-  \"theme\": \"light\""))
        XCTAssertTrue(diff.rawPatch.contains("+  \"theme\": \"dark\""))
    }

    func testLocalGitServiceUnifiedDiffShowsUntrackedJSONChanges() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-UntrackedJSONDiff-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)
        let readme = repoURL.appendingPathComponent("README.md")
        try "# SyncMD\n".write(to: readme, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")
        do {
            _ = try await service.commitAndPush(
                message: "Initial commit",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
            XCTFail("Expected push to fail without origin remote")
        } catch {
            // Expected: commit succeeds, push fails due missing origin.
        }

        let configDir = repoURL.appendingPathComponent("config", isDirectory: true)
        try fm.createDirectory(at: configDir, withIntermediateDirectories: true)
        let file = configDir.appendingPathComponent("settings.json")
        try """
        {
          "theme": "dark"
        }
        """.write(to: file, atomically: true, encoding: .utf8)

        let diff = try await service.unifiedDiff(path: "config/settings.json")

        XCTAssertEqual(diff.files.count, 1)
        XCTAssertEqual(diff.files.first?.path, "config/settings.json")
        XCTAssertEqual(diff.files.first?.changeType, .added)
        XCTAssertFalse(diff.rawPatch.isEmpty)
        XCTAssertTrue(diff.rawPatch.contains("diff --git a/config/settings.json b/config/settings.json"))
        XCTAssertTrue(diff.rawPatch.contains("--- /dev/null"))
        XCTAssertTrue(diff.rawPatch.contains("+++ b/config/settings.json"))
        XCTAssertTrue(diff.rawPatch.contains("+  \"theme\": \"dark\""))
    }

    func testLocalGitServiceUnifiedDiffUsesHeadAsBaseForStagedAndUnstagedChanges() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-JSONMixedDiff-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        let service = LocalGitService(localURL: repoURL)
        let file = repoURL.appendingPathComponent("settings.json")

        try """
        {
          "theme": "light"
        }
        """.write(to: file, atomically: true, encoding: .utf8)

        try await service.stage(path: "settings.json")
        do {
            _ = try await service.commitAndPush(
                message: "Initial settings",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
            XCTFail("Expected push to fail without origin remote")
        } catch {
            // Expected: commit succeeds, push fails due missing origin.
        }

        try """
        {
          "theme": "dark"
        }
        """.write(to: file, atomically: true, encoding: .utf8)
        try await service.stage(path: "settings.json")

        try """
        {
          "theme": "solarized"
        }
        """.write(to: file, atomically: true, encoding: .utf8)

        let diff = try await service.unifiedDiff(path: "settings.json")

        XCTAssertEqual(diff.files.count, 1)
        XCTAssertFalse(diff.rawPatch.isEmpty)
        XCTAssertTrue(diff.rawPatch.contains("-  \"theme\": \"light\""))
        XCTAssertTrue(diff.rawPatch.contains("+  \"theme\": \"solarized\""))
        XCTAssertFalse(diff.rawPatch.contains("\"dark\""))
    }


}

private enum GitFixtureState: String, CaseIterable {
    case clean
    case dirty
    case diverged
    case conflicted

    var commitSHA: String {
        switch self {
        case .clean: "1111111111111111111111111111111111111111"
        case .dirty: "2222222222222222222222222222222222222222"
        case .diverged: "3333333333333333333333333333333333333333"
        case .conflicted: "4444444444444444444444444444444444444444"
        }
    }

    var expectedChangeCount: Int {
        switch self {
        case .clean: 0
        case .dirty: 2
        case .diverged: 3
        case .conflicted: 4
        }
    }
}

private struct GitFixtureFactory {
    static func make(state: GitFixtureState) throws -> GitFixture {
        let fm = FileManager.default
        let rootURL = fm.temporaryDirectory.appendingPathComponent("SyncMDTests-\(state.rawValue)-\(UUID().uuidString)", isDirectory: true)
        let gitURL = rootURL.appendingPathComponent(".git", isDirectory: true)
        try fm.createDirectory(at: gitURL, withIntermediateDirectories: true)

        try "ref: refs/heads/main\n".write(
            to: gitURL.appendingPathComponent("HEAD"),
            atomically: true,
            encoding: .utf8
        )
        try "state=\(state.rawValue)\n".write(
            to: gitURL.appendingPathComponent("FIXTURE_STATE"),
            atomically: true,
            encoding: .utf8
        )
        try "# Inbox\n- sync notes\n".write(
            to: rootURL.appendingPathComponent("Inbox.md"),
            atomically: true,
            encoding: .utf8
        )

        switch state {
        case .clean:
            break
        case .dirty:
            try "# Local edits\n- changed\n".write(
                to: rootURL.appendingPathComponent("LocalEdits.md"),
                atomically: true,
                encoding: .utf8
            )
            try "staged=1\nuntracked=1\n".write(
                to: gitURL.appendingPathComponent("STATUS"),
                atomically: true,
                encoding: .utf8
            )
        case .diverged:
            try "local=ahead\nremote=ahead\n".write(
                to: gitURL.appendingPathComponent("DIVERGED"),
                atomically: true,
                encoding: .utf8
            )
            try "# Diverged\nlocal branch differs\n".write(
                to: rootURL.appendingPathComponent("Diverged.md"),
                atomically: true,
                encoding: .utf8
            )
        case .conflicted:
            try "<<<<<<< ours\nlocal\n=======\nremote\n>>>>>>> theirs\n".write(
                to: rootURL.appendingPathComponent("Conflict.md"),
                atomically: true,
                encoding: .utf8
            )
            try "conflicts=1\n".write(
                to: gitURL.appendingPathComponent("MERGE_STATE"),
                atomically: true,
                encoding: .utf8
            )
        }

        let repoID = UUID()
        let repoConfig = RepoConfig(
            id: repoID,
            repoURL: "https://example.com/syncmd-fixture.git",
            branch: "main",
            authorName: "Fixture",
            authorEmail: "fixture@example.com",
            vaultFolderName: rootURL.lastPathComponent,
            customVaultBookmarkData: nil,
            customLocationIsParent: false,
            gitState: GitState(
                commitSHA: state.commitSHA,
                treeSHA: "",
                branch: "main",
                blobSHAs: [:],
                lastSyncDate: Date(timeIntervalSince1970: 0)
            )
        )

        let repoInfo = LocalRepoInfo(
            branch: "main",
            commitSHA: state.commitSHA,
            changeCount: state.expectedChangeCount
        )

        return GitFixture(
            rootURL: rootURL,
            repoConfig: repoConfig,
            repoInfo: repoInfo,
            repository: FakeGitRepository(repoInfoResult: repoInfo)
        )
    }
}

private struct GitFixture {
    let rootURL: URL
    let repoConfig: RepoConfig
    let repoInfo: LocalRepoInfo
    let repository: FakeGitRepository

    func cleanup() {
        try? FileManager.default.removeItem(at: rootURL)
    }

    func snapshot() -> [String: String] {
        let fm = FileManager.default
        guard let enumerator = fm.enumerator(at: rootURL, includingPropertiesForKeys: [.isDirectoryKey]) else {
            return [:]
        }

        var result: [String: String] = [:]
        for case let fileURL as URL in enumerator {
            let isDirectory = (try? fileURL.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
            guard !isDirectory else { continue }

            let relativePath = fileURL.path.replacingOccurrences(of: rootURL.path + "/", with: "")
            let content = (try? String(contentsOf: fileURL, encoding: .utf8)) ?? "<binary>"
            result[relativePath] = content
        }
        return result
    }
}

private final class FakeGitRepository: GitRepositoryProtocol, @unchecked Sendable {
    var hasGitDirectoryValue: Bool = true
    var repoInfoResult: LocalRepoInfo
    var pullPlanResult: PullPlan
    var pullResult: Result<LocalPullResult, Error>
    var diffResult: UnifiedDiffResult = .empty
    var commitHistoryResult: [GitCommitSummary] = []
    var commitDetailResultByOID: [String: GitCommitDetail] = [:]
    var stashEntriesResult: [GitStashEntry] = []
    var savedStashes: [(message: String, includeUntracked: Bool)] = []
    var appliedStashIndices: [Int] = []
    var poppedStashIndices: [Int] = []
    var droppedStashIndices: [Int] = []
    var discardedPaths: [String] = []
    var didDiscardAllChanges = false
    var tagsResult: [GitTag] = []
    var createdTags: [(name: String, message: String?)] = []
    var deletedTagNames: [String] = []
    var pushedTagNames: [String] = []
    var branchInventoryResult: BranchInventory = .empty
    var createdBranches: [String] = []
    var switchedBranches: [String] = []
    var deletedBranches: [String] = []
    var mergeResult: MergeResult = MergeResult(kind: .upToDate, sourceBranch: "main", newCommitSHA: "")
    var revertResult: RevertResult = RevertResult(kind: .reverted, targetOID: "", newCommitSHA: nil)
    var mergeFinalizeResult: MergeFinalizeResult = MergeFinalizeResult(newCommitSHA: "")
    var didAbortMerge = false
    var conflictSessionResult: ConflictSession = .none
    var resolvedConflicts: [(path: String, strategy: ConflictResolutionStrategy)] = []
    var stagedPaths: [String] = []
    var unstagedPaths: [String] = []

    init(repoInfoResult: LocalRepoInfo) {
        self.repoInfoResult = repoInfoResult
        self.pullPlanResult = PullPlan(
            action: .upToDate,
            branch: repoInfoResult.branch,
            localCommitSHA: repoInfoResult.commitSHA,
            remoteCommitSHA: repoInfoResult.commitSHA,
            hasLocalChanges: repoInfoResult.changeCount > 0,
            aheadBy: 0,
            behindBy: 0
        )
        self.pullResult = .success(LocalPullResult(updated: false, newCommitSHA: repoInfoResult.commitSHA))
    }

    var hasGitDirectory: Bool {
        hasGitDirectoryValue
    }

    func clone(remoteURL: String, pat: String) async throws -> LocalCloneResult {
        LocalCloneResult(commitSHA: repoInfoResult.commitSHA, branch: repoInfoResult.branch, fileCount: 1)
    }

    func pullPlan(pat: String) async throws -> PullPlan {
        pullPlanResult
    }

    func pull(pat: String) async throws -> LocalPullResult {
        switch pullResult {
        case .success(let result):
            return result
        case .failure(let error):
            throw error
        }
    }

    func unifiedDiff(path: String?) async throws -> UnifiedDiffResult {
        diffResult
    }

    func listBranches() async throws -> BranchInventory {
        branchInventoryResult
    }

    func createBranch(name: String) async throws {
        createdBranches.append(name)
    }

    func switchBranch(name: String) async throws {
        switchedBranches.append(name)
    }

    func deleteBranch(name: String) async throws {
        deletedBranches.append(name)
    }

    func mergeBranch(name: String, authorName: String, authorEmail: String) async throws -> MergeResult {
        mergeResult
    }

    func pushCurrentBranch(pat: String) async throws {}

    func fetchRemote(pat: String) async throws {}

    func revertCommit(oid: String, message: String, authorName: String, authorEmail: String) async throws -> RevertResult {
        revertResult
    }

    func completeMerge(message: String, authorName: String, authorEmail: String) async throws -> MergeFinalizeResult {
        mergeFinalizeResult
    }

    func abortMerge() async throws {
        didAbortMerge = true
    }

    func conflictSession() async throws -> ConflictSession {
        conflictSessionResult
    }

    func resolveConflict(path: String, strategy: ConflictResolutionStrategy) async throws {
        resolvedConflicts.append((path: path, strategy: strategy))
    }

    func conflictDetail(path: String) async throws -> ConflictFileDetail {
        ConflictFileDetail(lookupPath: path, ancestor: nil, ours: nil, theirs: nil)
    }

    func resolveConflictWithContent(
        path: String,
        content: Data,
        additionalPathsToRemove: [String]
    ) async throws {
        resolvedConflicts.append((path: path, strategy: .manual))
    }

    func commitLocal(message: String, authorName: String, authorEmail: String) async throws -> String {
        repoInfoResult.commitSHA
    }

    func stageAll() async throws {
        stagedPaths.append("*")
    }

    func stage(path: String, oldPath: String?) async throws {
        stagedPaths.append(path)
        if let oldPath { stagedPaths.append(oldPath) }
    }

    func unstage(path: String, oldPath: String?) async throws {
        unstagedPaths.append(path)
        if let oldPath { unstagedPaths.append(oldPath) }
    }

    func discardChanges(path: String) async throws {
        discardedPaths.append(path)
    }

    func discardAllChanges() async throws {
        didDiscardAllChanges = true
    }

    func commitAndPush(
        message: String,
        authorName: String,
        authorEmail: String,
        pat: String
    ) async throws -> LocalPushResult {
        LocalPushResult(commitSHA: repoInfoResult.commitSHA)
    }

    func listStashes() async throws -> [GitStashEntry] {
        stashEntriesResult
    }

    func saveStash(message: String, authorName: String, authorEmail: String, includeUntracked: Bool) async throws -> GitStashEntry {
        savedStashes.append((message: message, includeUntracked: includeUntracked))
        let entry = GitStashEntry(index: stashEntriesResult.count, oid: UUID().uuidString.replacingOccurrences(of: "-", with: ""), message: message)
        stashEntriesResult.insert(entry, at: 0)
        return entry
    }

    func applyStash(index: Int, reinstateIndex: Bool) async throws -> StashApplyResult {
        appliedStashIndices.append(index)
        return StashApplyResult(kind: .applied, index: index)
    }

    func popStash(index: Int, reinstateIndex: Bool) async throws -> StashApplyResult {
        poppedStashIndices.append(index)
        if index < stashEntriesResult.count {
            stashEntriesResult.remove(at: index)
        }
        return StashApplyResult(kind: .applied, index: index)
    }

    func dropStash(index: Int) async throws {
        droppedStashIndices.append(index)
        if index < stashEntriesResult.count {
            stashEntriesResult.remove(at: index)
        }
    }

    func listTags() async throws -> [GitTag] { tagsResult }

    func createTag(name: String, targetOID: String?, message: String?, authorName: String, authorEmail: String) async throws -> GitTag {
        createdTags.append((name: name, message: message))
        let tag = GitTag(name: "refs/tags/\(name)", oid: UUID().uuidString.replacingOccurrences(of: "-", with: ""), kind: message == nil ? .lightweight : .annotated, message: message, targetOID: "deadbeef")
        tagsResult.append(tag)
        return tag
    }

    func deleteTag(name: String) async throws {
        deletedTagNames.append(name)
        tagsResult.removeAll { $0.shortName == name }
    }

    func pushTag(name: String, pat: String) async throws {
        pushedTagNames.append(name)
    }

    func commitHistory(limit: Int, skip: Int) async throws -> [GitCommitSummary] {
        guard limit > 0 else { return [] }
        guard skip < commitHistoryResult.count else { return [] }
        let upperBound = min(commitHistoryResult.count, skip + limit)
        return Array(commitHistoryResult[skip..<upperBound])
    }

    func commitDetail(oid: String) async throws -> GitCommitDetail {
        if let detail = commitDetailResultByOID[oid] {
            return detail
        }
        throw LocalGitError.libgit2("Commit not found: \(oid)")
    }

    func repoInfo() async throws -> LocalRepoInfo {
        repoInfoResult
    }
}

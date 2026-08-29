import Foundation
import XCTest
import CryptoKit
import NIOSSH
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

    func testAssistLinkCompletionURLMatchesOnlyExactSafeHandoff() throws {
        for value in ["syncmd://assist-linked", "syncmd://assist-linked/"] {
            XCTAssertTrue(AssistLinkCompletionURL.matches(try XCTUnwrap(URL(string: value))), value)
        }
        for value in [
            "https://assist-linked",
            "syncmd://x-callback-url/assist-linked",
            "syncmd://user@assist-linked",
            "syncmd://assist-linked:443",
            "syncmd://assist-linked/path",
            "syncmd://assist-linked?state=secret",
            "syncmd://assist-linked#done"
        ] {
            XCTAssertFalse(AssistLinkCompletionURL.matches(try XCTUnwrap(URL(string: value))), value)
        }
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

    @MainActor
    func testAppStatePromptsBeforeStagingAutoLFSCandidate() async throws {
        let fixture = try GitFixtureFactory.make(state: .dirty)
        defer { fixture.cleanup() }
        fixture.repository.lfsAutoTrackingCandidatesResult = [
            GitLFSAutoTrackingCandidate(
                path: "Video.mov",
                sizeBytes: 12_000_000,
                patterns: ["*.mov", "*.MOV"],
                reason: .knownBinaryExtension("mov")
            )
        ]

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.stageFile(repoID: fixture.repoConfig.id, path: "Video.mov")

        XCTAssertNotNil(appState.pendingLFSAutoTrackingConfirmation)
        XCTAssertTrue(fixture.repository.stagedPaths.isEmpty)
        XCTAssertEqual(fixture.repository.lfsAutoTrackingCandidatePathRequests, [["Video.mov"]])

        await appState.confirmPendingLFSAutoTracking(useLFS: true)

        XCTAssertNil(appState.pendingLFSAutoTrackingConfirmation)
        XCTAssertEqual(fixture.repository.stagedPaths, ["Video.mov"])
        XCTAssertEqual(fixture.repository.lfsAutoTrackStageFlags, [true])
    }

    func testOAuthCallbackParserValidatesURLStateBeforeToken() throws {
        XCTAssertEqual(
            try OAuthService.parseCallbackURL(URL(string: "syncmd://auth?state=expected&token=secret"), expectedState: "expected"),
            "secret"
        )

        for url in [
            "syncmd://auth?token=secret",
            "syncmd://auth?state=&token=secret",
            "syncmd://auth?state=wrong&token=secret"
        ] {
            XCTAssertThrowsError(try OAuthService.parseCallbackURL(URL(string: url), expectedState: "expected")) { error in
                guard case OAuthError.stateMismatch = error else { return XCTFail("Expected stateMismatch, got \(error)") }
            }
        }
        XCTAssertThrowsError(try OAuthService.parseCallbackURL(URL(string: "syncmd://auth?state=expected"), expectedState: "expected")) { error in
            guard case OAuthError.noToken = error else { return XCTFail("Expected noToken, got \(error)") }
        }
        XCTAssertThrowsError(try OAuthService.parseCallbackURL(URL(string: "syncmd://auth?state=expected&token="), expectedState: "expected")) { error in
            guard case OAuthError.noToken = error else { return XCTFail("Expected noToken, got \(error)") }
        }
        for url in [
            "syncmd://other?state=expected&token=secret",
            "https://auth?state=expected&token=secret",
            "syncmd://auth/path?state=expected&token=secret",
            "syncmd://auth?state=expected&state=expected&token=secret",
            "syncmd://auth?state=expected&token=secret&token=other"
        ] {
            XCTAssertThrowsError(try OAuthService.parseCallbackURL(URL(string: url), expectedState: "expected"))
        }
    }

    func testRepositoryPullRunnerReturnsTypedOutcomesWithoutMutatingBlockedRepo() async throws {
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

        let result = await RepositoryPullRunner().run(repository: fixture.repository, credentials: "")
        XCTAssertEqual(result, .blockedByLocalChanges(branch: "main"))
        XCTAssertEqual(fixture.repository.pullPlanCallCount, 1)
        XCTAssertEqual(fixture.repository.pullFastForwardCallCount, 0)
    }

    func testRepositoryPullRunnerReturnsUpdatedAndUpToDate() async throws {
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

        let updated = await RepositoryPullRunner().run(repository: fixture.repository, credentials: "")
        XCTAssertEqual(updated, .updated(branch: "main", commitSHA: newCommit))

        fixture.repository.pullPlanResult = PullPlan(
            action: .upToDate,
            branch: "main",
            localCommitSHA: newCommit,
            remoteCommitSHA: newCommit,
            hasLocalChanges: false,
            aheadBy: 0,
            behindBy: 0
        )
        fixture.repository.pullResult = .success(LocalPullResult(updated: false, newCommitSHA: newCommit))
        let current = await RepositoryPullRunner().run(repository: fixture.repository, credentials: "")
        XCTAssertEqual(current, .upToDate(branch: "main", commitSHA: newCommit))
    }

    func testRepositoryPullRunnerReturnsNewSHAWithPostUpdateAttention() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        let newCommit = "abababababababababababababababababababab"
        let attention = PullPostUpdateAttention.lfsHydrationBlockedByLocalChanges(path: "Manual.pdf")
        fixture.repository.pullPlanResult = PullPlan(
            action: .fastForward,
            branch: "main",
            localCommitSHA: fixture.repoConfig.gitState.commitSHA,
            remoteCommitSHA: newCommit,
            hasLocalChanges: false,
            aheadBy: 0,
            behindBy: 1
        )
        fixture.repository.pullResult = .success(LocalPullResult(
            updated: true,
            newCommitSHA: newCommit,
            attention: attention
        ))

        let result = await RepositoryPullRunner().run(repository: fixture.repository, credentials: "")

        XCTAssertEqual(
            result,
            .updatedWithAttention(branch: "main", commitSHA: newCommit, attention: attention)
        )
        XCTAssertFalse(result.completedWithoutAttention)

        for additionalAttention in [
            PullPostUpdateAttention.lfsHydrationFailed(message: "quota"),
            .lfsAuthenticationOrTrustRequired(message: "trust required"),
            .cancelledAfterUpdate,
        ] {
            fixture.repository.pullResult = .success(LocalPullResult(
                updated: true,
                newCommitSHA: newCommit,
                attention: additionalAttention
            ))
            let additional = await RepositoryPullRunner().run(repository: fixture.repository, credentials: "")
            XCTAssertEqual(
                additional,
                .updatedWithAttention(branch: "main", commitSHA: newCommit, attention: additionalAttention)
            )
            XCTAssertFalse(additional.completedWithoutAttention)
        }
    }

    @MainActor
    func testAppStatePersistsNewSHAWhileSurfacingPostUpdateAttention() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        let newCommit = "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"
        fixture.repository.pullPlanResult = PullPlan(
            action: .fastForward,
            branch: "main",
            localCommitSHA: fixture.repoConfig.gitState.commitSHA,
            remoteCommitSHA: newCommit,
            hasLocalChanges: false,
            aheadBy: 0,
            behindBy: 1
        )
        fixture.repository.pullResult = .success(LocalPullResult(
            updated: true,
            newCommitSHA: newCommit,
            attention: .lfsHydrationBlockedByLocalChanges(path: "Manual.pdf")
        ))
        let state = AppState(gitRepositoryFactory: { _ in fixture.repository }, loadPersistedState: false)
        state.repos = [fixture.repoConfig]

        let result = await state.pullOnly(repoID: fixture.repoConfig.id, showsProgressDelay: false)

        guard case .updatedWithAttention(_, let persistedSHA, _) = result else {
            return XCTFail("Expected updated-with-attention, got \(result)")
        }
        XCTAssertEqual(persistedSHA, newCommit)
        XCTAssertEqual(state.repo(id: fixture.repoConfig.id)?.gitState.commitSHA, newCommit)
        XCTAssertEqual(state.pullOutcomeByRepo[fixture.repoConfig.id]?.kind, .lfsHydrationBlocked)
        XCTAssertFalse(result.completedWithoutAttention)
    }

    @MainActor
    func testAppStateSkipsPullStateWriteWhenRepositoryRemovedDuringAwait() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        let gate = AsyncGate()
        let newCommit = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        fixture.repository.pullPlanResult = PullPlan(action: .fastForward, branch: "main", localCommitSHA: fixture.repoConfig.gitState.commitSHA, remoteCommitSHA: newCommit, hasLocalChanges: false, aheadBy: 0, behindBy: 1)
        fixture.repository.pullResult = .success(LocalPullResult(updated: true, newCommitSHA: newCommit))
        fixture.repository.executePullOnlyGate = gate
        let other = RepoConfig(repoURL: "other/repo", branch: "main", authorName: "Other", authorEmail: "other@example.com", vaultFolderName: "other")
        let state = AppState(gitRepositoryFactory: { _ in fixture.repository }, loadPersistedState: false)
        state.repos = [fixture.repoConfig, other]

        let pull = Task { await state.pullOnly(repoID: fixture.repoConfig.id, showsProgressDelay: false) }
        try await Task.sleep(for: .milliseconds(20))
        state.repos.removeAll { $0.id == fixture.repoConfig.id }
        await gate.open()
        _ = await pull.value

        XCTAssertEqual(state.repos.map(\.id), [other.id])
        XCTAssertEqual(state.repos.first?.gitState.commitSHA, other.gitState.commitSHA)
    }

    @MainActor
    func testAppStateSkipsConfigurationWriteWhenRepositoryRemovedDuringRemoteUpdate() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        let gate = AsyncGate()
        fixture.repository.setRemoteURLGate = gate
        let other = RepoConfig(
            repoURL: "other/repo",
            branch: "main",
            authorName: "Other",
            authorEmail: "other@example.com",
            vaultFolderName: "other"
        )
        let state = AppState(gitRepositoryFactory: { _ in fixture.repository }, loadPersistedState: false)
        state.repos = [fixture.repoConfig, other]

        let save = Task {
            await state.saveRepoConfiguration(
                id: fixture.repoConfig.id,
                repoURL: "changed/repo",
                branch: "notes",
                authorName: "Changed",
                authorEmail: "changed@example.com",
                authMethod: .none,
                credentials: .none
            )
        }
        try await Task.sleep(for: .milliseconds(20))
        state.repos.removeAll { $0.id == fixture.repoConfig.id }
        await gate.open()

        let saved = await save.value
        XCTAssertFalse(saved)
        XCTAssertEqual(state.repos, [other])
    }

    func testSerializedGitRepositorySerializesIndependentWrappersForSamePath() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("serialization-\(UUID())")
        let probe = SerializationProbeRepository()
        let first = SerializedGitRepository(base: probe, localURL: root)
        let second = SerializedGitRepository(base: probe, localURL: root)

        async let a: Void = first.stageAll()
        async let b: Void = second.stageAll()
        _ = try await (a, b)

        XCTAssertEqual(probe.maximumConcurrentOperations, 1)
        XCTAssertEqual(probe.completedOperations, 2)
    }

    func testRepositoryCoordinatorEscapingChildCannotInheritReleasedLease() async throws {
        let coordinator = RepositoryOperationCoordinator()
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("lease-\(UUID())")
        let childGate = AsyncGate()
        let secondGate = AsyncGate()
        let events = EventRecorder()
        var child: Task<Void, Error>?

        try await coordinator.withRepository(at: root) {
            child = Task {
                await childGate.wait()
                try await coordinator.withRepository(at: root) {
                    await events.append("child")
                }
            }
        }
        let second = Task {
            try await coordinator.withRepository(at: root) {
                await events.append("second-start")
                await secondGate.wait()
                await events.append("second-end")
            }
        }
        try await Task.sleep(for: .milliseconds(20))
        await childGate.open()
        try await Task.sleep(for: .milliseconds(20))
        let whileSecondHeld = await events.values()
        XCTAssertEqual(whileSecondHeld, ["second-start"])
        await secondGate.open()
        try await second.value
        try await child?.value
        let completed = await events.values()
        XCTAssertEqual(completed, ["second-start", "second-end", "child"])
    }

    func testCoordinatedFileMutationRejectsStaleSnapshotAndCleansTemporaryFile() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("SyncMD-CoordinatedSave-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let fileURL = directory.appendingPathComponent("note.md")
        let loaded = Data("loaded\n".utf8)
        let external = Data("external\n".utf8)
        try external.write(to: fileURL)

        XCTAssertThrowsError(
            try CoordinatedFileMutation.replace(
                itemAt: fileURL,
                expected: .bytes(loaded),
                with: Data("editor\n".utf8),
                temporaryPrefix: ".syncmd-editor-"
            )
        ) { error in
            XCTAssertEqual(error as? CoordinatedFileMutationError, .destinationChanged(fileURL.path))
        }
        XCTAssertEqual(try Data(contentsOf: fileURL), external)
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: directory.path).contains { $0.hasPrefix(".syncmd-editor-") })
    }

    func testCoordinatedFileMutationSuccessfulSaveReplacesBytesAndCleansTemporaryFile() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("SyncMD-CoordinatedSuccess-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let fileURL = directory.appendingPathComponent("note.md")
        let loaded = Data("loaded\n".utf8)
        let saved = Data("saved\n".utf8)
        try loaded.write(to: fileURL)

        try CoordinatedFileMutation.replace(
            itemAt: fileURL,
            expected: .bytes(loaded),
            with: saved,
            temporaryPrefix: ".syncmd-editor-"
        )

        XCTAssertEqual(try Data(contentsOf: fileURL), saved)
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: directory.path).contains { $0.hasPrefix(".syncmd-editor-") })
    }

    func testCoordinatedFileMutationWaitsForRepositoryLeaseThenRechecksSnapshot() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("SyncMD-CoordinatedLease-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let fileURL = directory.appendingPathComponent("note.md")
        let loaded = Data("loaded\n".utf8)
        let external = Data("external\n".utf8)
        try loaded.write(to: fileURL)
        let coordinator = RepositoryOperationCoordinator()
        let gate = AsyncGate()
        let holder = Task {
            try await coordinator.withRepository(at: directory) { await gate.wait() }
        }
        try await Task.sleep(for: .milliseconds(20))
        let save = Task {
            try await coordinator.withRepository(at: directory) {
                try CoordinatedFileMutation.replace(
                    itemAt: fileURL,
                    expected: .bytes(loaded),
                    with: Data("editor\n".utf8),
                    temporaryPrefix: ".syncmd-editor-"
                )
            }
        }
        try await Task.sleep(for: .milliseconds(20))
        try external.write(to: fileURL)
        await gate.open()
        try await holder.value

        do {
            try await save.value
            XCTFail("Expected queued save to reject the stale snapshot")
        } catch let error as CoordinatedFileMutationError {
            XCTAssertEqual(error, .destinationChanged(fileURL.path))
        }
        XCTAssertEqual(try Data(contentsOf: fileURL), external)
        XCTAssertFalse(try FileManager.default.contentsOfDirectory(atPath: directory.path).contains { $0.hasPrefix(".syncmd-editor-") })
    }

    func testRepositoryCoordinatorCanceledQueuedWaiterNeverExecutes() async throws {
        let coordinator = RepositoryOperationCoordinator()
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("cancel-lease-\(UUID())")
        let gate = AsyncGate()
        let events = EventRecorder()
        let holder = Task {
            try await coordinator.withRepository(at: root) {
                await events.append("holder")
                await gate.wait()
            }
        }
        try await Task.sleep(for: .milliseconds(20))
        let canceled = Task {
            try await coordinator.withRepository(at: root) {
                await events.append("canceled-ran")
            }
        }
        try await Task.sleep(for: .milliseconds(20))
        canceled.cancel()
        do {
            try await canceled.value
            XCTFail("Expected cancellation")
        } catch is CancellationError {}
        await gate.open()
        try await holder.value
        let recordedEvents = await events.values()
        XCTAssertFalse(recordedEvents.contains("canceled-ran"))
    }

    func testRepoPersistenceStoreMergesIndependentRepositoryChanges() throws {
        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("repos-\(UUID()).json")
        defer { try? FileManager.default.removeItem(at: fileURL) }
        let store = RepoPersistenceStore()
        let first = RepoConfig(repoURL: "one/repo", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one")
        let second = RepoConfig(repoURL: "two/repo", branch: "main", authorName: "Two", authorEmail: "two@example.com", vaultFolderName: "two")
        try store.replaceAll([first, second], at: fileURL)

        var updatedFirst = first
        updatedFirst.authorName = "Updated One"
        var updatedSecond = second
        updatedSecond.branch = "notes"
        _ = try store.apply([.update(original: first, modified: updatedFirst)], to: fileURL)
        _ = try store.apply([.update(original: second, modified: updatedSecond)], to: fileURL)

        let persisted = store.load(from: fileURL)
        XCTAssertEqual(persisted.first(where: { $0.id == first.id })?.authorName, "Updated One")
        XCTAssertEqual(persisted.first(where: { $0.id == second.id })?.branch, "notes")
    }

    func testRepoPersistenceStoreMergesSeparateFieldsOnSameRepository() throws {
        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("same-repo-\(UUID()).json")
        defer { try? FileManager.default.removeItem(at: fileURL) }
        let store = RepoPersistenceStore()
        let original = RepoConfig(repoURL: "one/repo", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one")
        try store.replaceAll([original], at: fileURL)

        var authorChange = original
        authorChange.authorName = "Updated"
        var branchChange = original
        branchChange.branch = "notes"
        _ = try store.apply([.update(original: original, modified: authorChange)], to: fileURL)
        _ = try store.apply([.update(original: original, modified: branchChange)], to: fileURL)

        let persisted = try store.loadStrict(from: fileURL)
        XCTAssertEqual(persisted.first?.authorName, "Updated")
        XCTAssertEqual(persisted.first?.branch, "notes")
    }

    func testRepoPersistenceStoreMergesSeparateGitStateFields() throws {
        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("git-state-\(UUID()).json")
        defer { try? FileManager.default.removeItem(at: fileURL) }
        let store = RepoPersistenceStore()
        let original = RepoConfig(repoURL: "one/repo", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one")
        try store.replaceAll([original], at: fileURL)
        var commitChange = original
        commitChange.gitState.commitSHA = "abc"
        var branchChange = original
        branchChange.gitState.branch = "notes"
        _ = try store.apply([.update(original: original, modified: commitChange)], to: fileURL)
        _ = try store.apply([.update(original: original, modified: branchChange)], to: fileURL)
        let persisted = try XCTUnwrap(store.loadStrict(from: fileURL).first)
        XCTAssertEqual(persisted.gitState.commitSHA, "abc")
        XCTAssertEqual(persisted.gitState.branch, "notes")
    }

    func testRepoPersistenceStoreRejectsStaleUpdateAfterDelete() throws {
        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("deleted-repo-\(UUID()).json")
        defer { try? FileManager.default.removeItem(at: fileURL) }
        let store = RepoPersistenceStore()
        let original = RepoConfig(repoURL: "one/repo", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one")
        try store.replaceAll([original], at: fileURL)
        _ = try store.apply([.remove(original: original)], to: fileURL)
        var stale = original
        stale.authorName = "Restored"
        XCTAssertThrowsError(try store.apply([.update(original: original, modified: stale)], to: fileURL)) { error in
            guard case RepoPersistenceStore.StoreError.staleUpdateAfterDeletion(original.id) = error else {
                return XCTFail("Expected stale update error, got \(error)")
            }
        }
    }

    func testRepoPersistenceStorePreservesMalformedExistingFile() throws {
        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("malformed-repos-\(UUID()).json")
        defer { try? FileManager.default.removeItem(at: fileURL) }
        let malformed = Data("{not-json".utf8)
        try malformed.write(to: fileURL)
        let store = RepoPersistenceStore()
        let repo = RepoConfig(repoURL: "one/repo", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one")

        XCTAssertThrowsError(try store.apply([.add(repo)], to: fileURL))
        XCTAssertEqual(try Data(contentsOf: fileURL), malformed)
    }

    @MainActor
    func testIndependentAppStatesMergeRepositoryChangesWithoutDeletingConcurrentRecords() throws {
        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("app-state-repos-\(UUID()).json")
        defer { try? FileManager.default.removeItem(at: fileURL) }
        let firstRepo = RepoConfig(repoURL: "one/repo", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one")
        let secondRepo = RepoConfig(repoURL: "two/repo", branch: "main", authorName: "Two", authorEmail: "two@example.com", vaultFolderName: "two")
        let store = RepoPersistenceStore()
        try store.replaceAll([firstRepo], at: fileURL)

        let firstState = AppState(repoPersistenceStore: store, reposFileURL: fileURL, loadPersistedState: true)
        let secondState = AppState(repoPersistenceStore: store, reposFileURL: fileURL, loadPersistedState: true)
        firstState.addRepo(secondRepo)
        secondState.updateRepo(id: firstRepo.id) { $0.authorName = "Updated One" }

        let persisted = store.load(from: fileURL)
        XCTAssertEqual(persisted.count, 2)
        XCTAssertEqual(persisted.first(where: { $0.id == firstRepo.id })?.authorName, "Updated One")
        XCTAssertEqual(persisted.first(where: { $0.id == secondRepo.id })?.vaultFolderName, "two")
    }

    func testKeychainCredentialsUseAfterFirstUnlockDeviceOnlyAccessibility() throws {
        XCTAssertEqual(KeychainService.credentialAccessibility as String, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
        let first = "test-keychain-\(UUID().uuidString)"
        let second = "test-keychain-\(UUID().uuidString)"
        defer { KeychainService.delete(key: first); KeychainService.delete(key: second) }

        func addLockedKey(_ key: String) {
            let query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: KeychainService.service,
                kSecAttrAccount as String: key,
                kSecValueData as String: Data("secret".utf8),
                kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            ]
            XCTAssertEqual(SecItemAdd(query as CFDictionary, nil), errSecSuccess)
        }
        addLockedKey(first)
        let defaults = UserDefaults(suiteName: "keychain-test-\(UUID().uuidString)")!
        defaults.set(true, forKey: KeychainService.accessibilityMigrationDefaultsKey)
        KeychainService.migrateKnownGitCredentialsIfNeeded(keys: [first], defaults: defaults)
        KeychainService.migrateKnownGitCredentialsIfNeeded(keys: [first], defaults: defaults)
        XCTAssertEqual(KeychainService.attributes(key: first)?[kSecAttrAccessible as String] as? String,
                       kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)

        addLockedKey(second)
        KeychainService.migrateKnownGitCredentialsIfNeeded(keys: [second], defaults: defaults)
        XCTAssertEqual(KeychainService.attributes(key: second)?[kSecAttrAccessible as String] as? String,
                       kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
    }

    func testPremiumStoreKitConfigurationMatchesRuntimeProducts() throws {
        let sourceURL = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("Sync.md/GitSyncAssist.storekit")
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: sourceURL)) as? [String: Any])
        let groups = try XCTUnwrap(root["subscriptionGroups"] as? [[String: Any]])
        XCTAssertEqual(groups.count, 1)
        let group = try XCTUnwrap(groups.first)
        XCTAssertEqual(group["id"] as? String, PremiumProductIdentifiers.default.subscriptionGroup)
        let subscriptions = try XCTUnwrap(group["subscriptions"] as? [[String: Any]])
        let products = Dictionary(uniqueKeysWithValues: try subscriptions.map { subscription -> (String, String) in
            let id = try XCTUnwrap(subscription["productID"] as? String)
            let period = try XCTUnwrap(subscription["recurringSubscriptionPeriod"] as? String)
            XCTAssertEqual(subscription["subscriptionGroupID"] as? String, PremiumProductIdentifiers.default.subscriptionGroup)
            XCTAssertEqual(subscription["type"] as? String, "RecurringSubscription")
            return (id, period)
        })
        XCTAssertEqual(Set(products.keys), Set(PremiumProductIdentifiers.default.all))
        XCTAssertEqual(products[PremiumProductIdentifiers.default.monthly], "P1M")
        XCTAssertEqual(products[PremiumProductIdentifiers.default.annual], "P1Y")
    }

    func testPrivacyManifestCoversAppAnalyticsAndAssistWithoutTracking() throws {
        let manifestURL = try XCTUnwrap(Bundle.main.url(forResource: "PrivacyInfo", withExtension: "xcprivacy"))
        let manifest = try XCTUnwrap(PropertyListSerialization.propertyList(
            from: Data(contentsOf: manifestURL),
            options: [],
            format: nil
        ) as? [String: Any])
        XCTAssertEqual(manifest["NSPrivacyTracking"] as? Bool, false)
        XCTAssertEqual(manifest["NSPrivacyTrackingDomains"] as? [String], [])

        let collected = try XCTUnwrap(manifest["NSPrivacyCollectedDataTypes"] as? [[String: Any]])
        let purposes = Dictionary(uniqueKeysWithValues: try collected.map { entry -> (String, Set<String>) in
            let type = try XCTUnwrap(entry["NSPrivacyCollectedDataType"] as? String)
            XCTAssertEqual(entry["NSPrivacyCollectedDataTypeLinked"] as? Bool, true)
            XCTAssertEqual(entry["NSPrivacyCollectedDataTypeTracking"] as? Bool, false)
            return (type, Set(try XCTUnwrap(entry["NSPrivacyCollectedDataTypePurposes"] as? [String])))
        })
        XCTAssertEqual(Set(purposes.keys), [
            "NSPrivacyCollectedDataTypeDeviceID",
            "NSPrivacyCollectedDataTypeProductInteraction",
            "NSPrivacyCollectedDataTypePurchaseHistory",
            "NSPrivacyCollectedDataTypeUserID",
            "NSPrivacyCollectedDataTypeOtherUserContent",
            "NSPrivacyCollectedDataTypeOtherDiagnosticData"
        ])
        XCTAssertEqual(purposes["NSPrivacyCollectedDataTypeDeviceID"], [
            "NSPrivacyCollectedDataTypePurposeAnalytics",
            "NSPrivacyCollectedDataTypePurposeAppFunctionality"
        ])
        XCTAssertEqual(purposes["NSPrivacyCollectedDataTypeProductInteraction"], [
            "NSPrivacyCollectedDataTypePurposeAnalytics"
        ])
        XCTAssertEqual(purposes["NSPrivacyCollectedDataTypePurchaseHistory"], [
            "NSPrivacyCollectedDataTypePurposeAppFunctionality"
        ])
        XCTAssertEqual(purposes["NSPrivacyCollectedDataTypeUserID"], [
            "NSPrivacyCollectedDataTypePurposeAppFunctionality"
        ])
        XCTAssertEqual(purposes["NSPrivacyCollectedDataTypeOtherUserContent"], [
            "NSPrivacyCollectedDataTypePurposeAppFunctionality"
        ])
        XCTAssertEqual(purposes["NSPrivacyCollectedDataTypeOtherDiagnosticData"], [
            "NSPrivacyCollectedDataTypePurposeAnalytics",
            "NSPrivacyCollectedDataTypePurposeAppFunctionality"
        ])

        let accessed = try XCTUnwrap(manifest["NSPrivacyAccessedAPITypes"] as? [[String: Any]])
        let reasons = Dictionary(uniqueKeysWithValues: try accessed.map { entry -> (String, Set<String>) in
            let type = try XCTUnwrap(entry["NSPrivacyAccessedAPIType"] as? String)
            return (type, Set(try XCTUnwrap(entry["NSPrivacyAccessedAPITypeReasons"] as? [String])))
        })
        XCTAssertEqual(reasons, [
            "NSPrivacyAccessedAPICategoryUserDefaults": ["CA92.1"],
            "NSPrivacyAccessedAPICategoryFileTimestamp": ["C617.1", "3B52.1"]
        ])
    }

    func testPrivacyRequestDraftUsesPrivateAddressAndOpaqueInstallationIDs() throws {
        let suiteName = "privacy-request-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let onboardingID = "11111111-1111-4111-8111-111111111111"
        let assistID = "22222222-2222-4222-8222-222222222222"
        defaults.set(onboardingID, forKey: "onboarding.analytics.install_id.v1")
        defaults.set(assistID, forKey: PremiumInstallationIdentity.defaultsKey)

        var identityKeychain: [String: String] = [:]
        let url = try XCTUnwrap(FeedbackHelper.privacyRequestMailtoURL(
            defaults: defaults,
            bundle: .main,
            identityKeychainLoad: { identityKeychain[$0] },
            identityKeychainSave: { identityKeychain[$0] = $1 }
        ))
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.scheme, "mailto")
        XCTAssertEqual(components.path, FeedbackHelper.supportEmail)
        XCTAssertEqual(components.queryItems?.first(where: { $0.name == "subject" })?.value,
                       "GitSync.md Privacy & Data Request")
        let body = try XCTUnwrap(components.queryItems?.first(where: { $0.name == "body" })?.value)
        XCTAssertTrue(body.contains(onboardingID))
        XCTAssertTrue(body.contains(assistID))
        XCTAssertTrue(body.localizedCaseInsensitiveContains("keep these opaque installation identifiers private"))
        XCTAssertFalse(body.contains("test-bearer"))
        XCTAssertFalse(body.contains("test-delete"))
    }

    func testPremiumInstallationIdentitySurvivesUserDefaultsLossAndUsesKeychainAuthority() throws {
        let firstSuite = "premium-identity-first-\(UUID().uuidString)"
        let secondSuite = "premium-identity-second-\(UUID().uuidString)"
        let firstDefaults = try XCTUnwrap(UserDefaults(suiteName: firstSuite))
        let secondDefaults = try XCTUnwrap(UserDefaults(suiteName: secondSuite))
        defer {
            firstDefaults.removePersistentDomain(forName: firstSuite)
            secondDefaults.removePersistentDomain(forName: secondSuite)
        }
        var keychain: [String: String] = [:]
        let load: (String) -> String? = { keychain[$0] }
        let save: (String, String) -> Void = { keychain[$0] = $1 }
        let expected = UUID()
        firstDefaults.set(expected.uuidString, forKey: PremiumInstallationIdentity.defaultsKey)

        let first = PremiumInstallationIdentity.current(
            defaults: firstDefaults, bundle: .main, keychainLoad: load, keychainSave: save
        )
        XCTAssertEqual(first.installationID, expected)
        XCTAssertEqual(keychain[PremiumInstallationIdentity.keychainKey], expected.uuidString)

        // Simulate reinstall: UserDefaults is absent while the Keychain item
        // remains. The same installation ID must be restored and re-persisted.
        let reinstalled = PremiumInstallationIdentity.current(
            defaults: secondDefaults, bundle: .main, keychainLoad: load, keychainSave: save
        )
        XCTAssertEqual(reinstalled.installationID, expected)
        XCTAssertEqual(secondDefaults.string(forKey: PremiumInstallationIdentity.defaultsKey), expected.uuidString)

        // A stale/defaults-only UUID cannot strand credentials namespaced by
        // the reinstall-durable Keychain identity.
        secondDefaults.set(UUID().uuidString, forKey: PremiumInstallationIdentity.defaultsKey)
        XCTAssertEqual(PremiumInstallationIdentity.current(
            defaults: secondDefaults, bundle: .main, keychainLoad: load, keychainSave: save
        ).installationID, expected)
    }

    func testPremiumReleaseConfigurationAndBackgroundCapabilities() throws {
        let info = Bundle.main.infoDictionary ?? [:]
        XCTAssertEqual(info["UIBackgroundModes"] as? [String], ["remote-notification"])

        let unresolvedRelay = try XCTUnwrap(info["PREMIUM_RELAY_BASE_URL"] as? String)
        XCTAssertNil(PremiumAPIConfiguration(bundle: .main).baseURL)
        XCTAssertTrue(unresolvedRelay.isEmpty || unresolvedRelay.contains("$("),
                      "Local/CI builds must fail closed until an authorized HTTPS relay URL is configured")

        let projectURL = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("Sync.md.xcodeproj/project.pbxproj")
        let project = try String(contentsOf: projectURL, encoding: .utf8)
        XCTAssertTrue(project.contains("APS_ENVIRONMENT = development;"))
        XCTAssertTrue(project.contains("APS_ENVIRONMENT = production;"))
        XCTAssertEqual(project.components(separatedBy: "CODE_SIGN_ENTITLEMENTS = Sync.md/Sync_md.entitlements;").count - 1, 2)

        let entitlementsURL = projectURL
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("Sync.md/Sync_md.entitlements")
        let entitlements = try XCTUnwrap(PropertyListSerialization.propertyList(
            from: Data(contentsOf: entitlementsURL),
            options: [],
            format: nil
        ) as? [String: String])
        XCTAssertEqual(entitlements, ["aps-environment": "$(APS_ENVIRONMENT)"])
    }

    func testPremiumSilentPushParserAcceptsOnlyOpaqueBackgroundPayload() throws {
        let payload: [AnyHashable: Any] = [
            "aps": ["content-available": 1],
            "channel": "channel_12345678",
            "hint": "event-12345678"
        ]
        let parsed = try PremiumSilentPush.parse(payload)
        XCTAssertEqual(parsed.channel, "channel_12345678")
        XCTAssertEqual(parsed.hintID, "event-12345678")
        for invalid: [AnyHashable: Any] in [
            ["aps": ["content-available": 1, "alert": "secret"], "channel": "channel_12345678", "hint": "event-12345678"],
            ["aps": ["content-available": 1], "channel": "../private/path", "hint": "event-12345678"],
            ["aps": ["content-available": 1], "channel": "channel_12345678", "hint": String(repeating: "a", count: 129)],
            ["aps": ["content-available": 0], "channel": "channel_12345678", "hint": "event-12345678"],
            ["aps": ["content-available": 1], "channel": "channel_12345678", "hint": "event-12345678", "repo": "private"]
        ] {
            XCTAssertThrowsError(try PremiumSilentPush.parse(invalid))
        }
    }

    func testPremiumAPIRequestContainsOnlyAllowedMetadataAndFailsClosed() async throws {
        let transport = RecordingPremiumTransport()
        let installation = PremiumInstallation(installationID: UUID(), bundleID: "bontecou.Sync-md", appVersion: "3.0")
        let request = PremiumDeviceRegistrationRequest(
            installation: installation,
            token: "0011aaff",
            environment: .sandbox,
            registrationGeneration: 7
        )
        let credential = PremiumInstallationCredential(
            installationID: installation.installationID,
            token: "installation-bearer",
            deletionToken: "installation-delete",
            expiresAt: .distantFuture
        )
        let disabled = PremiumAPIClient(configuration: PremiumAPIConfiguration(baseURL: nil), transport: transport)
        await XCTAssertThrowsErrorAsync(try await disabled.registerDevice(request, credential: credential))
        let emptyRequests = await transport.requests()
        XCTAssertEqual(emptyRequests.count, 0)

        let enabled = PremiumAPIClient(configuration: PremiumAPIConfiguration(baseURL: URL(string: "https://relay.example")!), transport: transport)
        try await enabled.registerDevice(request, credential: credential)
        let requests = await transport.requests()
        let recorded = try XCTUnwrap(requests.first)
        XCTAssertEqual(recorded.value(forHTTPHeaderField: "Authorization"), "Bearer installation-bearer")
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(recorded.httpBody)) as? [String: Any])
        XCTAssertEqual(Set(json.keys), ["installation", "token", "environment", "registrationGeneration"])
        XCTAssertNil(json["channels"])
        XCTAssertEqual(json["registrationGeneration"] as? Int, 7)
        let string = String(data: recorded.httpBody!, encoding: .utf8)!
        XCTAssertFalse(string.contains("repoURL"))
        XCTAssertFalse(string.contains("credentials"))
        XCTAssertFalse(string.contains("path"))

        try await enabled.deleteDevice(.init(
            installationID: installation.installationID,
            token: nil,
            environment: .sandbox,
            maximumRegistrationGeneration: 7
        ), credential: credential)
        let deviceDeletionRequests = await transport.requests()
        let deviceDeletionRequest = try XCTUnwrap(deviceDeletionRequests.last)
        XCTAssertEqual(deviceDeletionRequest.httpMethod, "DELETE")
        let deletionJSON = try XCTUnwrap(JSONSerialization.jsonObject(
            with: try XCTUnwrap(deviceDeletionRequest.httpBody)
        ) as? [String: Any])
        XCTAssertEqual(Set(deletionJSON.keys), ["installationID", "environment", "maximumRegistrationGeneration"])
        XCTAssertEqual(deletionJSON["maximumRegistrationGeneration"] as? Int, 7)

        try await enabled.deleteInstallation(credential: credential)
        let deletionRequests = await transport.requests()
        let deletionRequest = try XCTUnwrap(deletionRequests.last)
        XCTAssertNil(deletionRequest.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(deletionRequest.value(forHTTPHeaderField: "X-Installation-Deletion-Token"), "installation-delete")
    }

    func testAPNsTokenHexAndEnvironment() {
        XCTAssertEqual(APNsDeviceToken.hex(Data([0, 1, 15, 16, 255])), "00010f10ff")
        #if DEBUG
        XCTAssertEqual(APNsDeviceToken.buildEnvironment, .sandbox)
        #else
        XCTAssertEqual(APNsDeviceToken.buildEnvironment, .production)
        #endif
    }

    func testRepoConfigLegacyDecodeDefaultsAssistDisabled() throws {
        let repo = RepoConfig(repoURL: "one/repo", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one")
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(repo)) as? [String: Any])
        json.removeValue(forKey: "assist")
        let decoded = try JSONDecoder().decode(RepoConfig.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertEqual(decoded.assist, .disabled)
        XCTAssertFalse(decoded.assist.enabled)
    }

    func testRepoPersistenceStoreMergesAssistPolicyAndHealthFields() throws {
        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("assist-merge-\(UUID()).json")
        defer { try? FileManager.default.removeItem(at: fileURL) }
        let store = RepoPersistenceStore()
        let original = RepoConfig(repoURL: "one/repo", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one")
        try store.replaceAll([original], at: fileURL)
        var policy = original
        policy.assist.enabled = true
        policy.assist.networkPolicy = .wifiOnly
        policy.assist.excludedFromAutomaticSync = true
        policy.assist.githubRepositoryID = 42
        policy.assist.githubRepositoryFullName = "owner/repo"
        policy.assist.linkedGitHubInstallationID = 101
        policy.assist.enrolledBranch = "main"
        policy.assist.enrollmentStatus = .enrolled
        policy.assist.enrollmentMessage = "ready"
        policy.assist.enrollmentLastAttemptDate = Date(timeIntervalSince1970: 123)
        var health = original
        health.assist.health = RepoAssistHealth(kind: .attention, attention: .localChanges, message: "dirty")
        _ = try store.apply([.update(original: original, modified: policy)], to: fileURL)
        _ = try store.apply([.update(original: original, modified: health)], to: fileURL)
        let result = try XCTUnwrap(store.loadStrict(from: fileURL).first)
        XCTAssertFalse(result.assist.enabled)
        XCTAssertEqual(result.assist.networkPolicy, .wifiOnly)
        XCTAssertTrue(result.assist.excludedFromAutomaticSync)
        XCTAssertNil(result.assist.channel)
        XCTAssertNil(result.assist.githubRepositoryID)
        XCTAssertNil(result.assist.githubRepositoryFullName)
        XCTAssertNil(result.assist.linkedGitHubInstallationID)
        XCTAssertNil(result.assist.enrolledBranch)
        XCTAssertEqual(result.assist.enrollmentStatus, .excluded)
        XCTAssertEqual(result.assist.enrollmentMessage, "ready")
        XCTAssertEqual(result.assist.enrollmentLastAttemptDate, Date(timeIntervalSince1970: 123))
        XCTAssertEqual(result.assist.health.attention, .localChanges)
    }

    func testRepoPersistenceExclusionDominatesStaleEnrollmentWhenExclusionWritesLast() throws {
        try assertAssistExclusionDominates(enrollmentWritesLast: false)
    }

    func testRepoPersistenceExclusionDominatesStaleEnrollmentWhenEnrollmentWritesLast() throws {
        try assertAssistExclusionDominates(enrollmentWritesLast: true)
    }

    private func assertAssistExclusionDominates(enrollmentWritesLast: Bool) throws {
        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("assist-exclusion-\(UUID()).json")
        defer { try? FileManager.default.removeItem(at: fileURL) }
        let store = RepoPersistenceStore()
        var original = RepoConfig(repoURL: "one/repo", branch: "main", authorName: "One",
                                  authorEmail: "one@example.com", vaultFolderName: "one")
        original.assist = RepoAssistSettings(
            enabled: true, channel: "original_channel_123", githubRepositoryID: 1,
            githubRepositoryFullName: "one/repo", linkedGitHubInstallationID: 10,
            enrolledBranch: "main", enrollmentStatus: .enrolled
        )
        try store.replaceAll([original], at: fileURL)
        var exclusion = original
        exclusion.assist.excludedFromAutomaticSync = true
        exclusion.assist.enabled = false
        exclusion.assist.channel = nil
        exclusion.assist.githubRepositoryID = nil
        exclusion.assist.githubRepositoryFullName = nil
        exclusion.assist.linkedGitHubInstallationID = nil
        exclusion.assist.enrolledBranch = nil
        exclusion.assist.enrollmentStatus = .excluded
        var staleEnrollment = original
        staleEnrollment.assist.channel = "stale_channel_456"
        staleEnrollment.assist.githubRepositoryID = 2
        staleEnrollment.assist.linkedGitHubInstallationID = 20

        let first = enrollmentWritesLast ? exclusion : staleEnrollment
        let second = enrollmentWritesLast ? staleEnrollment : exclusion
        _ = try store.apply([.update(original: original, modified: first)], to: fileURL)
        _ = try store.apply([.update(original: original, modified: second)], to: fileURL)

        let assist = try XCTUnwrap(store.loadStrict(from: fileURL).first).assist
        XCTAssertTrue(assist.excludedFromAutomaticSync)
        XCTAssertFalse(assist.enabled)
        XCTAssertNil(assist.channel)
        XCTAssertNil(assist.githubRepositoryID)
        XCTAssertNil(assist.githubRepositoryFullName)
        XCTAssertNil(assist.linkedGitHubInstallationID)
        XCTAssertNil(assist.enrolledBranch)
        XCTAssertEqual(assist.enrollmentStatus, .excluded)
    }

    func testHistoricalAssistObjectDecodeUsesSafeDefaults() throws {
        let data = Data("""
        {
          "enabled": true,
          "channel": "legacy_channel_123",
          "selectedBranch": "main",
          "networkPolicy": "any",
          "powerPolicy": "any",
          "health": { "kind": "never" }
        }
        """.utf8)
        let assist = try JSONDecoder().decode(RepoAssistSettings.self, from: data)
        XCTAssertTrue(assist.enabled)
        XCTAssertEqual(assist.channel, "legacy_channel_123")
        XCTAssertEqual(assist.enrollmentStatus, .enrolled)
        XCTAssertFalse(assist.excludedFromAutomaticSync)
        XCTAssertNil(assist.githubRepositoryID)
        XCTAssertNil(assist.linkedGitHubInstallationID)
    }

    @MainActor
    func testPremiumEntitlementStorePurchaseRestoreAndVerifiedUpdate() async throws {
        let storefront = FakePremiumStorefront()
        let defaults = UserDefaults(suiteName: "premium-store-\(UUID())")!
        let store = PremiumEntitlementStore(storefront: storefront, identifiers: .default, defaults: defaults)
        await store.start()
        XCTAssertEqual(store.state, .inactive)
        let transaction = premiumTransaction(id: 12)
        let purchase = await storefront.finishable(transaction)
        await storefront.setEntitlements([transaction])
        await storefront.setPurchase(.verified(purchase))
        await store.purchase(productID: PremiumProductIdentifiers.default.monthly)
        XCTAssertEqual(store.state, .active(PremiumEntitlementProof(transaction: transaction)))
        let finished = await storefront.finished()
        XCTAssertEqual(finished, [12])
        await store.restore()
        let syncCount = await storefront.syncCount()
        XCTAssertEqual(syncCount, 1)
        let annual = premiumTransaction(id: 13, productID: PremiumProductIdentifiers.default.annual)
        await storefront.setEntitlements([annual])
        await storefront.emit(annual)
        for _ in 0..<20 where store.state != .active(PremiumEntitlementProof(transaction: annual)) {
            await Task.yield()
        }
        XCTAssertEqual(store.state, .active(PremiumEntitlementProof(transaction: annual)))
        let finishedUpdates = await storefront.finished()
        XCTAssertEqual(finishedUpdates, [12, 13])
    }

    @MainActor
    func testPremiumTransactionEventCannotGrantAccessWithoutCurrentEntitlement() async {
        let storefront = FakePremiumStorefront()
        let defaults = UserDefaults(suiteName: "premium-event-authority-\(UUID())")!
        let store = PremiumEntitlementStore(storefront: storefront, identifiers: .default, defaults: defaults)
        await store.start()
        let transaction = premiumTransaction(id: 99)
        await storefront.emit(transaction)
        for _ in 0..<20 {
            if await storefront.finished().contains(99), store.state == .inactive { break }
            await Task.yield()
        }
        XCTAssertEqual(store.state, .inactive)
        let finished = await storefront.finished()
        XCTAssertEqual(finished, [99])
        XCTAssertNil(store.cachedVerifiedProof())
    }

    func testPremiumPushCompletionGateIsExactlyOnceUnderConcurrentClaims() async {
        let gate = PremiumPushCompletionGate()
        let claims = await withTaskGroup(of: Bool.self, returning: [Bool].self) { group in
            for _ in 0..<100 { group.addTask { await gate.claim() } }
            var values: [Bool] = []
            for await value in group { values.append(value) }
            return values
        }
        XCTAssertEqual(claims.filter { $0 }.count, 1)
        XCTAssertEqual(claims.filter { !$0 }.count, 99)
    }

    func testApplicationDelegateAssistCallbackGateUsesReleaseFlagBoundary() {
        XCTAssertFalse(SyncMDApplicationDelegate.shouldForwardAssistCallbacks(
            assistFeatureIsEnabled: { false }
        ))
        XCTAssertTrue(SyncMDApplicationDelegate.shouldForwardAssistCallbacks(
            assistFeatureIsEnabled: { true }
        ))
        XCTAssertFalse(
            SyncMDApplicationDelegate.shouldForwardAssistCallbacks(),
            "The production release flag must continue to block delegate forwarding"
        )
    }

    @MainActor
    func testPremiumNotificationBridgeTimesOutCancelsAndCompletesExactlyOnce() async throws {
        let bridge = PremiumNotificationBridge()
        let operationGate = AsyncGate()
        let cancelled = expectation(description: "push cancellation requested")
        let completed = expectation(description: "completion called once")
        completed.expectedFulfillmentCount = 1
        completed.assertForOverFulfill = true
        var results: [UIBackgroundFetchResult] = []
        bridge.connectForTesting(
            timeoutNanoseconds: 1_000_000,
            processPush: { _ in
                await operationGate.wait()
                return .completed(.updated(branch: "main", commitSHA: String(repeating: "a", count: 40)))
            },
            cancelPush: { _ in cancelled.fulfill() }
        )

        bridge.didReceive(userInfo: [:]) { result in
            results.append(result)
            completed.fulfill()
        }
        await fulfillment(of: [cancelled, completed], timeout: 2)
        await operationGate.open()
        for _ in 0..<50 { await Task.yield() }

        XCTAssertEqual(results, [.failed])
    }

    @MainActor
    func testPremiumNotificationBridgeReturnsSuccessfulResultBeforeTimeoutOnce() async throws {
        let bridge = PremiumNotificationBridge()
        let completed = expectation(description: "completion called")
        completed.assertForOverFulfill = true
        var cancellations = 0
        var results: [UIBackgroundFetchResult] = []
        bridge.connectForTesting(
            timeoutNanoseconds: 50_000_000,
            processPush: { _ in .completed(.updated(branch: "main", commitSHA: String(repeating: "a", count: 40))) },
            cancelPush: { _ in cancellations += 1 }
        )

        bridge.didReceive(userInfo: [:]) { result in
            results.append(result)
            completed.fulfill()
        }
        await fulfillment(of: [completed], timeout: 2)
        try await Task.sleep(for: .milliseconds(100))

        XCTAssertEqual(results, [.newData])
        XCTAssertEqual(cancellations, 0)
    }

    func testGitHubCanonicalIdentityRequiresExactOwnerAndRepository() {
        XCTAssertEqual(GitRemoteURL.parse("https://github.com/Owner/Repo.git")?.canonicalGitHubFullName, "Owner/Repo")
        XCTAssertEqual(GitRemoteURL.parse("Owner/Repo")?.canonicalGitHubFullName, "Owner/Repo")
        XCTAssertEqual(GitRemoteURL.parse("git@github.com:Owner/Repo.git")?.canonicalGitHubFullName, "Owner/Repo")
        XCTAssertNil(GitRemoteURL.parse("https://github.example/Owner/Repo")?.canonicalGitHubFullName)
        XCTAssertNil(GitRemoteURL.parse("https://github.com/Owner/Repo/issues")?.canonicalGitHubFullName)
    }

    func testGitHubExactRepositoryLookupClassifiesRateLimited403SeparatelyFromPermission403() {
        let exhausted = GitHubService.repositoryLookupError(
            statusCode: 403,
            headers: ["X-RateLimit-Remaining": "0"],
            body: Data("{\"message\":\"Forbidden\"}".utf8)
        )
        let retryAfter = GitHubService.repositoryLookupError(
            statusCode: 403,
            headers: ["Retry-After": "60"],
            body: Data()
        )
        let secondary = GitHubService.repositoryLookupError(
            statusCode: 403,
            headers: [:],
            body: Data("{\"message\":\"You have exceeded a secondary rate limit\"}".utf8)
        )
        let permission = GitHubService.repositoryLookupError(
            statusCode: 403,
            headers: ["X-RateLimit-Remaining": "4999"],
            body: Data("{\"message\":\"Resource not accessible by personal access token\"}".utf8)
        )

        if case .rateLimited = exhausted {} else { XCTFail("Exhausted primary limit must be transient") }
        if case .rateLimited = retryAfter {} else { XCTFail("Retry-After must be transient") }
        if case .rateLimited = secondary {} else { XCTFail("Secondary rate limit must be transient") }
        if case .forbidden = permission {} else { XCTFail("A genuine permission 403 must remain definitive") }
    }

    @MainActor
    func testPremiumRuntimeFeatureDisabledPushIgnoresPersistedConsentWithoutAnyAutomaticWork() async {
        let installationID = UUID()
        let automaticKey = "premium.automatic-sync.v1.\(installationID.uuidString)"
        let consentKey = "premium.relay-consent.\(installationID.uuidString)"
        UserDefaults.standard.set(true, forKey: automaticKey)
        UserDefaults.standard.set(true, forKey: consentKey)
        var repo = RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        repo.assist = RepoAssistSettings(enabled: true, channel: "channel_12345678", selectedBranch: "main")
        let api = ControllablePremiumRelayAPI()
        let harness = await PremiumRuntimeTestHarness.make(
            api: api,
            installationID: installationID,
            repo: repo,
            assistFeatureIsEnabled: false
        )
        defer { harness.cleanup() }

        let result = await harness.runtime.processPush([
            "aps": ["content-available": 1],
            "channel": "channel_12345678",
            "hint": "event-12345678"
        ])
        harness.runtime.didRegister(token: Data(repeating: 0x42, count: 32))
        harness.runtime.didFailToRegister(error: PremiumAPIError.rejected(500))

        XCTAssertEqual(result, .ignored)
        XCTAssertTrue(harness.runtime.automaticallySyncAllRepositories)
        XCTAssertTrue(harness.runtime.hasRelayConsent)
        XCTAssertTrue(UserDefaults.standard.bool(forKey: automaticKey), "The release gate must not clear the user's preference")
        let entitlementRequests = await harness.storefront.currentEntitlementRequestCount()
        let appAccountTokenSets = await harness.storefront.appAccountTokenSetCount()
        XCTAssertEqual(entitlementRequests, 0)
        XCTAssertEqual(appAccountTokenSets, 0)
        XCTAssertEqual(api.authorizationCount, 0)
        XCTAssertTrue(api.registrationRequests.isEmpty)
        XCTAssertTrue(api.deviceDeletionRequests.isEmpty)
        XCTAssertEqual(api.githubLinkAttempts, 0)
        XCTAssertTrue(api.enrollmentRequests.isEmpty)
        XCTAssertTrue(api.deletedEnrollmentChannels.isEmpty)
        XCTAssertEqual(api.installationDeletionCount, 0)
        XCTAssertEqual(harness.registrar.registerCount, 0)
        XCTAssertEqual(harness.registrar.unregisterCount, 0)
        XCTAssertEqual(harness.repository.executePullOnlyCallCount, 0)
        XCTAssertNil(harness.runtime.deviceRegistrationError)
    }

    @MainActor
    func testPremiumRuntimeGlobalPreferenceDefaultsOffAndDoesNotInferFromChannels() async {
        let api = ControllablePremiumRelayAPI()
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }

        XCTAssertFalse(harness.runtime.automaticallySyncAllRepositories)
        XCTAssertFalse(harness.runtime.hasRelayConsent)
        XCTAssertEqual(harness.provider.repo.assist.channel, "channel_12345678")
    }

    @MainActor
    func testUnconfiguredConcretePremiumClientCannotPersistGlobalModeOrRelayConsent() async {
        let installationID = UUID()
        let suite = "premium-unconfigured-\(installationID.uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let storefront = FakePremiumStorefront()
        await storefront.setEntitlements([premiumTransaction(id: 502)])
        let entitlement = PremiumEntitlementStore(storefront: storefront, identifiers: .default, defaults: defaults)
        let provider = FakeAssistRepositoryProvider(
            repo: RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one"),
            repository: FakeGitRepository(repoInfoResult: LocalRepoInfo(branch: "main", commitSHA: String(repeating: "1", count: 40), changeCount: 0))
        )
        let coordinator = BackgroundSyncCoordinator(
            entitlementIsActive: { entitlement.state.isActive },
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        let registrar = RecordingRemoteNotificationRegistrar()
        let transport = RecordingPremiumTransport()
        let runtime = PremiumRuntime(
            entitlementStore: entitlement,
            coordinator: coordinator,
            repositoryProvider: provider,
            api: PremiumAPIClient(configuration: .init(baseURL: nil), transport: transport),
            registrar: registrar,
            installation: .init(installationID: installationID, bundleID: "test", appVersion: "test"),
            environment: .sandbox,
            bridge: PremiumNotificationBridge(),
            assistFeatureIsEnabled: { true },
            keychain: ControllablePremiumKeychain(),
            defaults: defaults
        )

        let linkURL = await runtime.setAutomaticallySyncAllRepositories(true)

        XCTAssertNil(linkURL)
        XCTAssertFalse(runtime.automaticallySyncAllRepositories)
        XCTAssertFalse(runtime.hasRelayConsent)
        XCTAssertFalse(defaults.bool(forKey: "premium.automatic-sync.v1.\(installationID.uuidString)"))
        XCTAssertFalse(defaults.bool(forKey: "premium.relay-consent.\(installationID.uuidString)"))
        XCTAssertEqual(registrar.registerCount, 0)
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 0)
    }

    @MainActor
    func testPremiumRuntimeEnablesGlobalModeAndAutomaticallyEnrollsExactTarget() async {
        let api = ControllablePremiumRelayAPI()
        api.githubInstallationSummaries = [.init(githubInstallationID: 101, linkedAt: Date())]
        var repo = RepoConfig(repoURL: "git@github.com:Owner/Repo.git", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        let harness = await PremiumRuntimeTestHarness.make(api: api, repo: repo)
        defer { harness.cleanup() }

        let linkURL = await harness.runtime.setAutomaticallySyncAllRepositories(true)

        XCTAssertNil(linkURL)
        XCTAssertTrue(harness.runtime.automaticallySyncAllRepositories)
        XCTAssertTrue(harness.runtime.hasRelayConsent)
        XCTAssertEqual(harness.registrar.registerCount, 1)
        XCTAssertEqual(harness.runtime.githubInstallations, api.githubInstallationSummaries)
        XCTAssertEqual(api.enrollmentRequests, [.init(githubInstallationID: 101, repositoryID: 42, branch: "main")])
        XCTAssertEqual(harness.provider.repo.assist.enrollmentStatus, .enrolled)
        XCTAssertEqual(harness.provider.repo.assist.githubRepositoryID, 42)
        XCTAssertEqual(harness.provider.repo.assist.githubRepositoryFullName, "Owner/Repo")
        XCTAssertEqual(harness.provider.repo.assist.linkedGitHubInstallationID, 101)
        XCTAssertEqual(harness.provider.repo.assist.channel, "channel_12345678")
    }

    @MainActor
    func testPremiumRuntimeRejectsEnrollmentResponseForDifferentExactTarget() async {
        let api = ControllablePremiumRelayAPI()
        api.enrollmentResponseOverride = .init(
            channel: "channel_12345678", githubInstallationID: 999, repositoryID: 42, branch: "main"
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }
        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)

        let enrolled = await harness.runtime.enroll(
            repoID: harness.provider.repo.id, githubInstallationID: 101, repositoryID: 42, branch: "main"
        )

        XCTAssertFalse(enrolled)
        XCTAssertNil(harness.provider.repo.assist.githubRepositoryID)
        XCTAssertNil(harness.provider.repo.assist.linkedGitHubInstallationID)
    }

    @MainActor
    func testPremiumRuntimeKeepsClonedNonGitHubRepositoryForegroundOnly() async {
        let api = ControllablePremiumRelayAPI()
        api.githubInstallationSummaries = [.init(githubInstallationID: 101, linkedAt: Date())]
        var repo = RepoConfig(repoURL: "https://git.example.com/team/repo.git", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        let harness = await PremiumRuntimeTestHarness.make(api: api, repo: repo)
        defer { harness.cleanup() }

        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)

        XCTAssertTrue(harness.provider.repo.assist.enabled)
        XCTAssertEqual(harness.provider.repo.assist.enrollmentStatus, .foregroundOnly)
        XCTAssertNil(harness.provider.repo.assist.channel)
        XCTAssertTrue(harness.provider.repo.assist.enrollmentMessage?.contains("not a GitHub") == true)
    }

    @MainActor
    func testPremiumRuntimeExclusionIsLocalFirstAndCleansStaleChannel() async {
        let api = ControllablePremiumRelayAPI()
        api.githubInstallationSummaries = [.init(githubInstallationID: 101, linkedAt: Date())]
        api.enrollmentDeletionFailuresRemaining = 1
        var repo = RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        let harness = await PremiumRuntimeTestHarness.make(api: api, repo: repo)
        defer { harness.cleanup() }
        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)

        await harness.runtime.setAutomaticSyncExcluded(repoID: repo.id, excluded: true)

        XCTAssertTrue(harness.provider.repo.assist.excludedFromAutomaticSync)
        XCTAssertFalse(harness.provider.repo.assist.enabled)
        XCTAssertNil(harness.provider.repo.assist.channel)
        XCTAssertEqual(harness.provider.repo.assist.enrollmentStatus, .excluded)
        XCTAssertTrue(api.deletedEnrollmentChannels.isEmpty)

        _ = await harness.runtime.setAutomaticallySyncAllRepositories(false)
        XCTAssertFalse(harness.runtime.hasRelayConsent)
        XCTAssertEqual(api.deletedEnrollmentChannels, ["channel_12345678"])
    }

    @MainActor
    func testPremiumRuntimeStaleCleanupPreservesChannelQueuedDuringDeletionSuspension() async throws {
        let firstDeletionStarted = expectation(description: "first stale deletion started")
        let firstDeletionGate = AsyncGate()
        let secondDeletionStarted = expectation(description: "second stale deletion started")
        let secondDeletionGate = AsyncGate()
        let api = ControllablePremiumRelayAPI(
            enrollmentDeletionStarted: firstDeletionStarted,
            enrollmentDeletionGate: firstDeletionGate,
            secondEnrollmentDeletionStarted: secondDeletionStarted,
            secondEnrollmentDeletionGate: secondDeletionGate
        )
        api.githubInstallationSummaries = [.init(githubInstallationID: 101, linkedAt: Date())]
        api.enrollmentChannelsByInstallation[101] = "channel_B_67890"
        var repo = RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        repo.assist = RepoAssistSettings(
            enabled: true, channel: "channel_A_12345", selectedBranch: "main",
            githubRepositoryID: 42, githubRepositoryFullName: "owner/repo",
            linkedGitHubInstallationID: 101, enrolledBranch: "main", enrollmentStatus: .enrolled
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api, repo: repo)
        defer { harness.cleanup() }
        let enabling = Task { @MainActor in
            await harness.runtime.setAutomaticallySyncAllRepositories(true)
        }
        await fulfillment(of: [firstDeletionStarted], timeout: 2)

        await harness.runtime.setAutomaticSyncExcluded(repoID: repo.id, excluded: true)
        let staleKey = "premium.stale-channels.v1.\(harness.installationID.uuidString)"
        let queuedDuringSuspension = try XCTUnwrap(UserDefaults.standard.data(forKey: staleKey))
        XCTAssertEqual(
            Set(try JSONDecoder().decode([String].self, from: queuedDuringSuspension)),
            ["channel_A_12345", "channel_B_67890"]
        )

        await firstDeletionGate.open()
        await fulfillment(of: [secondDeletionStarted], timeout: 2)
        let afterFirstCompletion = try XCTUnwrap(UserDefaults.standard.data(forKey: staleKey))
        XCTAssertEqual(try JSONDecoder().decode([String].self, from: afterFirstCompletion), ["channel_B_67890"])

        await secondDeletionGate.open()
        _ = await enabling.value
        XCTAssertEqual(api.deletedEnrollmentChannels, ["channel_A_12345", "channel_B_67890"])
        XCTAssertNil(UserDefaults.standard.data(forKey: staleKey))
    }

    @MainActor
    func testPremiumRuntimeEnrollsRepositoryAddedAfterGlobalOptIn() async {
        let api = ControllablePremiumRelayAPI()
        api.githubInstallationSummaries = [.init(githubInstallationID: 101, linkedAt: Date())]
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }
        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        api.resetEnrollmentRequests()

        var added = RepoConfig(repoURL: "owner/added", branch: "main", authorName: "One",
                               authorEmail: "one@example.com", vaultFolderName: "added")
        added.gitState.commitSHA = String(repeating: "2", count: 40)
        harness.provider.addRepository(added)
        await waitUntil { api.enrollmentRequests.count == 1 }

        XCTAssertEqual(api.enrollmentRequests.first,
                       .init(githubInstallationID: 101, repositoryID: 42, branch: "main"))
        XCTAssertEqual(harness.provider.assistRepository(id: added.id)?.assist.enrollmentStatus, .enrolled)
    }

    @MainActor
    func testPremiumRuntimeRepairsPersistedEnrollmentByRepostingExactTuple() async {
        let api = ControllablePremiumRelayAPI()
        api.githubInstallationSummaries = [.init(githubInstallationID: 101, linkedAt: Date())]
        var repo = RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        repo.assist = RepoAssistSettings(
            enabled: true, channel: "healthy_channel_123", selectedBranch: "main",
            githubRepositoryID: 42, githubRepositoryFullName: "owner/repo",
            linkedGitHubInstallationID: 101, enrolledBranch: "main", enrollmentStatus: .enrolled
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api, repo: repo)
        defer { harness.cleanup() }

        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)

        XCTAssertEqual(api.enrollmentRequests,
                       [.init(githubInstallationID: 101, repositoryID: 42, branch: "main")])
        XCTAssertEqual(harness.provider.repo.assist.channel, "channel_12345678")
        XCTAssertEqual(api.deletedEnrollmentChannels, ["healthy_channel_123"])
    }

    @MainActor
    func testPremiumRuntimeRepairsUsingCurrentInstallationAfterStoredInstallationLosesAccess() async {
        let api = ControllablePremiumRelayAPI()
        api.githubInstallationSummaries = [.init(githubInstallationID: 202, linkedAt: Date())]
        api.enrollmentRejectionsByInstallation[101] = 404
        api.enrollmentChannelsByInstallation[202] = "replacement_channel_202"
        var repo = RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        repo.assist = RepoAssistSettings(
            enabled: true, channel: "healthy_channel_101", selectedBranch: "main",
            githubRepositoryID: 42, githubRepositoryFullName: "owner/repo",
            linkedGitHubInstallationID: 101, enrolledBranch: "main", enrollmentStatus: .enrolled
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api, repo: repo)
        defer { harness.cleanup() }

        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)

        XCTAssertEqual(api.enrollmentRequests.map(\.githubInstallationID), [101, 202])
        XCTAssertEqual(harness.provider.repo.assist.linkedGitHubInstallationID, 202)
        XCTAssertEqual(harness.provider.repo.assist.channel, "replacement_channel_202")
        XCTAssertEqual(api.deletedEnrollmentChannels, ["healthy_channel_101"])
    }

    @MainActor
    func testPremiumRuntimeFailedInstallationRefreshPreservesEnrollmentWhenStoredInstallationRejects() async {
        let api = ControllablePremiumRelayAPI()
        api.githubInstallationsError = PremiumAPIError.rejected(503)
        api.enrollmentRejectionsByInstallation[101] = 404
        var repo = RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        repo.assist = RepoAssistSettings(
            enabled: true, channel: "healthy_channel_101", selectedBranch: "main",
            githubRepositoryID: 42, githubRepositoryFullName: "owner/repo",
            linkedGitHubInstallationID: 101, enrolledBranch: "main", enrollmentStatus: .enrolled
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api, repo: repo)
        defer { harness.cleanup() }

        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)

        XCTAssertEqual(api.enrollmentRequests.map(\.githubInstallationID), [101])
        XCTAssertEqual(harness.provider.repo.assist.channel, "healthy_channel_101")
        XCTAssertEqual(harness.provider.repo.assist.linkedGitHubInstallationID, 101)
        XCTAssertEqual(harness.provider.repo.assist.enrollmentStatus, .failed)
        XCTAssertTrue(harness.provider.repo.assist.enrollmentMessage?.contains("will retry") == true)
        XCTAssertTrue(api.deletedEnrollmentChannels.isEmpty)
    }

    @MainActor
    func testPremiumRuntimeTransientIdentityFailurePreservesHealthyEnrollment() async {
        let api = ControllablePremiumRelayAPI()
        var repo = RepoConfig(repoURL: "owner/repo", branch: "notes", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        repo.assist = RepoAssistSettings(
            enabled: true, channel: "healthy_channel_123", selectedBranch: "main",
            githubRepositoryID: 42, githubRepositoryFullName: "owner/repo",
            linkedGitHubInstallationID: 101, enrolledBranch: "main", enrollmentStatus: .enrolled
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api, repo: repo)
        defer { harness.cleanup() }
        harness.provider.identityResolution = .transientFailure("rate limited")

        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)

        XCTAssertEqual(harness.provider.repo.assist.channel, "healthy_channel_123")
        XCTAssertEqual(harness.provider.repo.assist.githubRepositoryID, 42)
        XCTAssertEqual(harness.provider.repo.assist.enrolledBranch, "main")
        XCTAssertEqual(harness.provider.repo.assist.enrollmentStatus, .failed)
        XCTAssertTrue(api.deletedEnrollmentChannels.isEmpty)
    }

    @MainActor
    func testPremiumRuntimeTransientRelayFailurePreservesHealthyEnrollment() async {
        let api = ControllablePremiumRelayAPI()
        api.enrollmentTransientError = PremiumAPIError.rejected(500)
        var repo = RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        repo.assist = RepoAssistSettings(
            enabled: true, channel: "healthy_channel_123", selectedBranch: "main",
            githubRepositoryID: 42, githubRepositoryFullName: "owner/repo",
            linkedGitHubInstallationID: 101, enrolledBranch: "main", enrollmentStatus: .enrolled
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api, repo: repo)
        defer { harness.cleanup() }

        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)

        XCTAssertEqual(harness.provider.repo.assist.channel, "healthy_channel_123")
        XCTAssertEqual(harness.provider.repo.assist.githubRepositoryID, 42)
        XCTAssertEqual(harness.provider.repo.assist.enrollmentStatus, .failed)
        XCTAssertTrue(api.deletedEnrollmentChannels.isEmpty)
    }

    @MainActor
    func testPremiumRuntimeRemoteDriftReplacesEnrollmentAndCleansOldChannel() async {
        let api = ControllablePremiumRelayAPI()
        api.githubInstallationSummaries = [.init(githubInstallationID: 101, linkedAt: Date())]
        api.enrollmentChannelsByInstallation[101] = "replacement_channel_456"
        var repo = RepoConfig(repoURL: "owner/new-repo", branch: "main", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        repo.assist = RepoAssistSettings(
            enabled: true, channel: "stale_channel_123", selectedBranch: "main",
            githubRepositoryID: 7, githubRepositoryFullName: "owner/old-repo",
            linkedGitHubInstallationID: 101, enrolledBranch: "main", enrollmentStatus: .enrolled
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api, repo: repo)
        defer { harness.cleanup() }

        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)

        XCTAssertEqual(harness.provider.repo.assist.channel, "replacement_channel_456")
        XCTAssertEqual(harness.provider.repo.assist.githubRepositoryFullName, "owner/new-repo")
        XCTAssertEqual(api.deletedEnrollmentChannels, ["stale_channel_123"])
    }

    @MainActor
    func testPremiumRuntimeUnenrollDoesNotClearConcurrentReplacement() async {
        let deletionStarted = expectation(description: "enrollment delete started")
        let deletionGate = AsyncGate()
        let api = ControllablePremiumRelayAPI(
            enrollmentDeletionStarted: deletionStarted,
            enrollmentDeletionGate: deletionGate
        )
        api.enrollmentResponseOverride = .init(
            channel: "channel_A_12345", githubInstallationID: 101, repositoryID: 42, branch: "main"
        )
        var repo = RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        repo.assist = RepoAssistSettings(
            enabled: true, channel: "channel_A_12345", selectedBranch: "main",
            githubRepositoryID: 42, githubRepositoryFullName: "owner/repo",
            linkedGitHubInstallationID: 101, enrolledBranch: "main", enrollmentStatus: .enrolled
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api, repo: repo)
        defer { harness.cleanup() }
        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        api.resetDeletedEnrollmentChannels()

        let unenroll = Task { @MainActor in await harness.runtime.unenroll(repoID: repo.id) }
        await fulfillment(of: [deletionStarted], timeout: 2)
        var replacement = harness.provider.repo.assist
        replacement.channel = "channel_B_67890"
        replacement.enrollmentStatus = .enrolled
        harness.provider.updateAssistSettings(repoID: repo.id, replacement)
        await deletionGate.open()

        let unenrolled = await unenroll.value
        XCTAssertTrue(unenrolled)
        XCTAssertEqual(harness.provider.repo.assist.channel, "channel_B_67890")
    }

    @MainActor
    func testPremiumRuntimeUnenrollDoesNotClearSameChannelABATargetRepair() async {
        let deletionStarted = expectation(description: "enrollment delete started")
        let deletionGate = AsyncGate()
        let api = ControllablePremiumRelayAPI(
            enrollmentDeletionStarted: deletionStarted,
            enrollmentDeletionGate: deletionGate
        )
        api.enrollmentResponseOverride = .init(
            channel: "channel_A_12345", githubInstallationID: 101, repositoryID: 42, branch: "main"
        )
        var repo = RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        repo.assist = RepoAssistSettings(
            enabled: true, channel: "channel_A_12345", selectedBranch: "main",
            githubRepositoryID: 42, githubRepositoryFullName: "owner/repo",
            linkedGitHubInstallationID: 101, enrolledBranch: "main", enrollmentStatus: .enrolled
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api, repo: repo)
        defer { harness.cleanup() }
        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        api.resetDeletedEnrollmentChannels()

        let unenroll = Task { @MainActor in await harness.runtime.unenroll(repoID: repo.id) }
        await fulfillment(of: [deletionStarted], timeout: 2)
        var repaired = harness.provider.repo.assist
        repaired.githubRepositoryID = 84
        repaired.enrollmentLastAttemptDate = Date(timeIntervalSince1970: 1_800_000_000)
        repaired.enrollmentStatus = .enrolled
        harness.provider.updateAssistSettings(repoID: repo.id, repaired)
        await deletionGate.open()

        let unenrolled = await unenroll.value
        XCTAssertTrue(unenrolled)
        XCTAssertEqual(harness.provider.repo.assist.channel, "channel_A_12345")
        XCTAssertEqual(harness.provider.repo.assist.githubRepositoryID, 84)
        XCTAssertEqual(harness.provider.repo.assist.enrollmentLastAttemptDate, repaired.enrollmentLastAttemptDate)
    }

    @MainActor
    func testPremiumDeviceRegistrationRemainsConstantSizeWithMoreThanOneHundredRepos() async throws {
        let api = ControllablePremiumRelayAPI()
        api.githubInstallationSummaries = [.init(githubInstallationID: 101, linkedAt: Date())]
        let repos = (0..<101).map { index -> RepoConfig in
            var repo = RepoConfig(repoURL: "owner/repo\(index)", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "repo\(index)")
            repo.gitState.commitSHA = String(repeating: "1", count: 40)
            return repo
        }
        let repository = FakeGitRepository(repoInfoResult: LocalRepoInfo(branch: "main", commitSHA: String(repeating: "1", count: 40), changeCount: 0))
        let provider = FakeAssistRepositoryProvider(repos: repos, repository: repository)
        let installationID = UUID()
        let storefront = FakePremiumStorefront()
        await storefront.setEntitlements([premiumTransaction(id: 777)])
        let entitlement = PremiumEntitlementStore(storefront: storefront, identifiers: .default, defaults: UserDefaults(suiteName: "constant-registration-\(installationID)")!)
        let coordinator = BackgroundSyncCoordinator(entitlementIsActive: { entitlement.state.isActive }, repositoryProvider: provider, conditionsProvider: PermissiveBackgroundSyncConditions())
        let runtime = PremiumRuntime(entitlementStore: entitlement, coordinator: coordinator, repositoryProvider: provider, api: api,
                                     registrar: RecordingRemoteNotificationRegistrar(), installation: .init(installationID: installationID, bundleID: "test", appVersion: "test"),
                                     environment: .sandbox, bridge: PremiumNotificationBridge(),
                                     assistFeatureIsEnabled: { true })
        defer {
            UserDefaults.standard.removeObject(forKey: "premium.relay-consent.\(installationID.uuidString)")
            UserDefaults.standard.removeObject(forKey: "premium.automatic-sync.v1.\(installationID.uuidString)")
            UserDefaults.standard.removeObject(forKey: "premium.apns-token-generation.sandbox.\(installationID.uuidString)")
            KeychainService.delete(key: "premium.apns-token-generation.keychain.sandbox.\(installationID.uuidString)")
            KeychainService.delete(key: "premium.relay-deletion-credential.\(installationID.uuidString)")
            KeychainService.delete(key: "premium.apns-token.sandbox.\(installationID.uuidString)")
        }
        _ = await runtime.setAutomaticallySyncAllRepositories(true)
        runtime.didRegister(token: Data(repeating: 0x55, count: 32))
        await waitUntil { api.registrationRequests.count == 1 }

        let encoded = try JSONSerialization.jsonObject(with: JSONEncoder().encode(try XCTUnwrap(api.registrationRequests.first))) as! [String: Any]
        XCTAssertNil(encoded["channels"])
        XCTAssertEqual(api.registrationRequests.count, 1)
    }

    @MainActor
    func testPremiumRuntimeReauthorizesRevokedBearerForGitHubLinkWithoutAPNsToken() async throws {
        let api = ControllablePremiumRelayAPI(rejectFirstGitHubLinkAsUnauthorized: true)
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }

        let linkURL = await harness.runtime.setAutomaticallySyncAllRepositories(true)

        XCTAssertNotNil(linkURL)
        XCTAssertEqual(api.githubLinkAttempts, 2)
        XCTAssertEqual(api.authorizationCount, 2)
        XCTAssertNil(harness.runtime.latestToken)
        XCTAssertNil(harness.runtime.registrationError)
    }

    @MainActor
    func testPremiumRuntimeGitHubLinkRetryCannotBypassConcurrentDeletionBarrier() async throws {
        let linkStarted = expectation(description: "first GitHub link started")
        let linkGate = AsyncGate()
        let deletionStarted = expectation(description: "deletion started")
        let deletionGate = AsyncGate()
        let api = ControllablePremiumRelayAPI(
            deletionStarted: deletionStarted,
            deletionGate: deletionGate,
            rejectFirstGitHubLinkAsUnauthorized: true,
            firstGitHubLinkStarted: linkStarted,
            firstGitHubLinkGate: linkGate
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }
        let firstLink = Task { @MainActor in
            await harness.runtime.setAutomaticallySyncAllRepositories(true)
        }
        await fulfillment(of: [linkStarted], timeout: 2)
        let deletion = Task { @MainActor in await harness.runtime.deleteRelayData() }
        await fulfillment(of: [deletionStarted], timeout: 2)

        await linkGate.open()
        let firstLinkURL = await firstLink.value
        XCTAssertNil(firstLinkURL)
        XCTAssertEqual(api.githubLinkAttempts, 1)
        XCTAssertEqual(api.authorizationCount, 1)
        await deletionGate.open()
        await deletion.value
    }

    @MainActor
    func testPremiumRuntimeDelayedEnrollmentCannotRestoreConsentAfterDeletion() async throws {
        let enrollmentStarted = expectation(description: "enrollment started")
        let enrollmentGate = AsyncGate()
        let deletionStarted = expectation(description: "deletion started")
        let deletionGate = AsyncGate()
        let api = ControllablePremiumRelayAPI(
            deletionStarted: deletionStarted,
            deletionGate: deletionGate,
            enrollmentStarted: enrollmentStarted,
            enrollmentGate: enrollmentGate
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }
        let linkURL = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        XCTAssertNotNil(linkURL)

        let enrollment = Task { @MainActor in
            await harness.runtime.enroll(
                repoID: harness.provider.repo.id,
                githubInstallationID: 101,
                repositoryID: 42,
                branch: "main"
            )
        }
        await fulfillment(of: [enrollmentStarted], timeout: 2)
        let deletion = Task { @MainActor in await harness.runtime.deleteRelayData() }
        await fulfillment(of: [deletionStarted], timeout: 2)
        await deletionGate.open()
        await deletion.value
        await enrollmentGate.open()

        let enrollmentResult = await enrollment.value
        XCTAssertFalse(enrollmentResult)
        XCTAssertFalse(harness.runtime.hasRelayConsent)
        XCTAssertEqual(harness.provider.repo.assist, .disabled)
        XCTAssertFalse(UserDefaults.standard.bool(forKey: "premium.relay-consent.\(harness.installationID.uuidString)"))
        let staleData = try XCTUnwrap(UserDefaults.standard.data(
            forKey: "premium.stale-channels.v1.\(harness.installationID.uuidString)"
        ))
        XCTAssertEqual(try JSONDecoder().decode([String].self, from: staleData), ["channel_12345678"])

        let restarted = await PremiumRuntimeTestHarness.make(
            api: api,
            installationID: harness.installationID,
            repo: harness.provider.repo
        )
        defer { restarted.cleanup() }
        XCTAssertFalse(restarted.runtime.hasRelayConsent)
    }

    @MainActor
    func testPremiumRuntimeDelayedEnrollmentAfterExclusionIsQueuedForCleanup() async throws {
        let enrollmentStarted = expectation(description: "enrollment started")
        let enrollmentGate = AsyncGate()
        let api = ControllablePremiumRelayAPI(
            enrollmentStarted: enrollmentStarted,
            enrollmentGate: enrollmentGate
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }
        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)

        let enrollment = Task { @MainActor in
            await harness.runtime.enroll(
                repoID: harness.provider.repo.id,
                githubInstallationID: 101,
                repositoryID: 42,
                branch: "main"
            )
        }
        await fulfillment(of: [enrollmentStarted], timeout: 2)
        await harness.runtime.setAutomaticSyncExcluded(repoID: harness.provider.repo.id, excluded: true)
        await enrollmentGate.open()

        let enrolled = await enrollment.value
        XCTAssertFalse(enrolled)
        await waitUntil { api.deletedEnrollmentChannels == ["channel_12345678"] }
        XCTAssertEqual(harness.provider.repo.assist.enrollmentStatus, .excluded)
        XCTAssertNil(harness.provider.repo.assist.channel)
    }

    @MainActor
    func testPremiumRuntimeTokenReplacementCannotRestoreStaleRegistration() async throws {
        let firstRegistrationStarted = expectation(description: "first registration started")
        let firstRegistrationGate = AsyncGate()
        let api = ControllablePremiumRelayAPI(
            firstRegistrationStarted: firstRegistrationStarted,
            firstRegistrationGate: firstRegistrationGate
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }

        let linkURL = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        XCTAssertNotNil(linkURL)
        let consentKey = "premium.relay-consent.\(harness.installationID.uuidString)"
        XCTAssertTrue(UserDefaults.standard.bool(forKey: consentKey))
        XCTAssertTrue(harness.runtime.entitlementStore.state.isActive)
        XCTAssertTrue(harness.runtime.hasRelayConsent)
        harness.runtime.didRegister(token: Data(repeating: 0x11, count: 32))
        XCTAssertEqual(harness.runtime.latestToken, String(repeating: "11", count: 32))
        await fulfillment(of: [firstRegistrationStarted], timeout: 2)
        harness.runtime.didRegister(token: Data(repeating: 0x22, count: 32))
        await waitUntil { api.registrationRequests.count == 2 && api.deviceDeletionRequests.count == 1 }
        XCTAssertEqual(api.relayToken, String(repeating: "22", count: 32))
        await firstRegistrationGate.open()
        await waitUntil { harness.runtime.isRegistered }
        let refreshedLink = await harness.runtime.startGitHubLink()
        XCTAssertNotNil(refreshedLink)
        await waitUntil { api.registrationRequests.count == 3 }

        XCTAssertEqual(api.registrationRequests.map(\.token), [
            String(repeating: "11", count: 32),
            String(repeating: "22", count: 32),
            String(repeating: "22", count: 32)
        ])
        XCTAssertEqual(api.registrationRequests.map(\.registrationGeneration), [1, 2, 3])
        XCTAssertEqual(api.deviceDeletionRequests.map(\.token), [String(repeating: "11", count: 32)])
        XCTAssertEqual(api.relayToken, String(repeating: "22", count: 32))
        XCTAssertEqual(api.relayRegistrationGeneration, 3)
        XCTAssertEqual(harness.runtime.latestToken, String(repeating: "22", count: 32))
        XCTAssertNil(harness.runtime.registrationError)
    }

    @MainActor
    func testPremiumRuntimeOlderGenerationlessRegistrationFailureCannotOverwriteNewerSuccess() async throws {
        let secondRegistrationStarted = expectation(description: "generation N registration started")
        let secondRegistrationGate = AsyncGate()
        let api = ControllablePremiumRelayAPI(
            secondRegistrationStarted: secondRegistrationStarted,
            secondRegistrationGate: secondRegistrationGate
        )
        api.registrationFailureCalls = [2]
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }

        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        let token = Data(repeating: 0x23, count: 32)
        harness.runtime.didRegister(token: token)
        await waitUntil { harness.runtime.isRegistered }

        let older = Task { @MainActor in await harness.runtime.startGitHubLink() }
        await fulfillment(of: [secondRegistrationStarted], timeout: 2)
        let newer = Task { @MainActor in await harness.runtime.startGitHubLink() }
        let newerLink = await newer.value
        XCTAssertNotNil(newerLink)
        XCTAssertTrue(harness.runtime.isRegistered)
        XCTAssertNil(harness.runtime.deviceRegistrationError)

        await secondRegistrationGate.open()
        let olderLink = await older.value
        XCTAssertNotNil(olderLink)

        let expectedToken = String(repeating: "23", count: 32)
        XCTAssertEqual(api.registrationRequests.map(\.registrationGeneration), [1, 2, 3])
        XCTAssertEqual(api.relayRegistrationGeneration, 3)
        XCTAssertEqual(api.relayToken, expectedToken)
        XCTAssertEqual(harness.runtime.latestToken, expectedToken)
        XCTAssertTrue(harness.runtime.isRegistered)
        XCTAssertNil(harness.runtime.deviceRegistrationError)
    }

    @MainActor
    func testPremiumRuntimeInactiveCleanupCannotDeleteReactivatedSameTokenGeneration() async throws {
        let deletionStarted = expectation(description: "inactive device deletion started")
        let deletionGate = AsyncGate()
        let api = ControllablePremiumRelayAPI(
            firstDeviceDeletionStarted: deletionStarted,
            firstDeviceDeletionGate: deletionGate
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }
        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        let token = Data(repeating: 0x44, count: 32)
        harness.runtime.didRegister(token: token)
        await waitUntil { harness.runtime.isRegistered }

        await harness.storefront.setEntitlements([])
        await harness.runtime.entitlementStore.refresh()
        await fulfillment(of: [deletionStarted], timeout: 2)
        await harness.storefront.setEntitlements([premiumTransaction(id: 502)])
        await harness.runtime.entitlementStore.refresh()
        harness.runtime.didRegister(token: token)
        await waitUntil { api.relayRegistrationGeneration == 2 }
        await deletionGate.open()
        await waitUntil { api.deviceDeletionRequests.count == 1 }

        XCTAssertEqual(api.deviceDeletionRequests.first?.maximumRegistrationGeneration, 1)
        XCTAssertEqual(api.deviceDeletionRequests.first?.token, String(repeating: "44", count: 32))
        XCTAssertEqual(api.relayToken, String(repeating: "44", count: 32))
        XCTAssertEqual(api.relayRegistrationGeneration, 2)
    }

    @MainActor
    func testPremiumRuntimeNilTokenDisableSnapshotCannotDeleteNewRegistration() async throws {
        let deletionStarted = expectation(description: "nil-token device deletion started")
        let deletionGate = AsyncGate()
        let api = ControllablePremiumRelayAPI(
            firstDeviceDeletionStarted: deletionStarted,
            firstDeviceDeletionGate: deletionGate
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }
        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)

        let disable = Task { @MainActor in
            await harness.runtime.setAutomaticallySyncAllRepositories(false)
        }
        await fulfillment(of: [deletionStarted], timeout: 2)
        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        harness.runtime.didRegister(token: Data(repeating: 0x45, count: 32))
        await waitUntil { api.relayRegistrationGeneration == 1 }
        await deletionGate.open()
        _ = await disable.value

        XCTAssertNil(api.deviceDeletionRequests.first?.token)
        XCTAssertEqual(api.deviceDeletionRequests.first?.maximumRegistrationGeneration, 0)
        XCTAssertEqual(api.relayToken, String(repeating: "45", count: 32))
    }

    @MainActor
    func testPremiumRuntimeOutOfOrderGitHubRefreshUsesNewestInventoryForEveryCaller() async throws {
        let firstStarted = expectation(description: "older GitHub inventory request started")
        let firstGate = AsyncGate()
        let api = ControllablePremiumRelayAPI(
            firstGitHubInstallationsStarted: firstStarted,
            firstGitHubInstallationsGate: firstGate
        )
        let old = PremiumGitHubInstallationSummary(githubInstallationID: 101, linkedAt: Date(timeIntervalSince1970: 1))
        let newest = PremiumGitHubInstallationSummary(githubInstallationID: 202, linkedAt: Date(timeIntervalSince1970: 2))
        api.githubInstallationResponseSequence = [[old], [newest]]
        api.enrollmentChannelsByInstallation[202] = "newest_channel_202"
        var repo = RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        let harness = await PremiumRuntimeTestHarness.make(api: api, repo: repo)
        defer { harness.cleanup() }

        let enabling = Task { @MainActor in
            await harness.runtime.setAutomaticallySyncAllRepositories(true)
        }
        await fulfillment(of: [firstStarted], timeout: 2)
        let newerRefresh = Task { @MainActor in await harness.runtime.refreshGitHubInstallations() }
        let newerWasAuthoritative = await newerRefresh.value
        XCTAssertTrue(newerWasAuthoritative)
        await firstGate.open()
        _ = await enabling.value

        XCTAssertEqual(harness.runtime.githubInstallations, [newest])
        XCTAssertEqual(api.enrollmentRequests.map(\.githubInstallationID), [202])
        XCTAssertEqual(harness.provider.repo.assist.linkedGitHubInstallationID, 202)
    }

    @MainActor
    func testPremiumRuntimeThreeGitHubRefreshCallersConvergeWithoutHungIntermediate() async throws {
        let firstStarted = expectation(description: "request one started")
        let firstGate = AsyncGate()
        let secondStarted = expectation(description: "request two started")
        let secondGate = AsyncGate()
        let api = ControllablePremiumRelayAPI(
            firstGitHubInstallationsStarted: firstStarted,
            firstGitHubInstallationsGate: firstGate,
            secondGitHubInstallationsStarted: secondStarted,
            secondGitHubInstallationsGate: secondGate
        )
        let first = PremiumGitHubInstallationSummary(githubInstallationID: 101, linkedAt: Date(timeIntervalSince1970: 1))
        let intermediate = PremiumGitHubInstallationSummary(githubInstallationID: 202, linkedAt: Date(timeIntervalSince1970: 2))
        let newest = PremiumGitHubInstallationSummary(githubInstallationID: 303, linkedAt: Date(timeIntervalSince1970: 3))
        api.githubInstallationResponseSequence = [[first], [intermediate], [newest]]
        api.enrollmentChannelsByInstallation[303] = "newest_channel_303"
        var repo = RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        let harness = await PremiumRuntimeTestHarness.make(api: api, repo: repo)
        defer {
            Task { await firstGate.open(); await secondGate.open() }
            harness.cleanup()
        }
        let firstFinished = expectation(description: "request one caller finished")
        let secondFinished = expectation(description: "request two caller finished")

        let enabling = Task { @MainActor in
            let result = await harness.runtime.setAutomaticallySyncAllRepositories(true)
            firstFinished.fulfill()
            return result
        }
        await fulfillment(of: [firstStarted], timeout: 2)
        let intermediateRefresh = Task { @MainActor in
            let result = await harness.runtime.refreshGitHubInstallations()
            secondFinished.fulfill()
            return result
        }
        await fulfillment(of: [secondStarted], timeout: 2)

        await firstGate.open()
        await Task.yield()
        let newestWasAuthoritative = await harness.runtime.refreshGitHubInstallations()
        XCTAssertTrue(newestWasAuthoritative)
        await fulfillment(of: [firstFinished, secondFinished], timeout: 2)

        let enablingLink = await enabling.value
        let intermediateWasAuthoritative = await intermediateRefresh.value
        XCTAssertNil(enablingLink)
        XCTAssertTrue(intermediateWasAuthoritative)
        XCTAssertEqual(harness.runtime.githubInstallations, [newest])
        XCTAssertEqual(api.enrollmentRequests.map(\.githubInstallationID), [303])
        XCTAssertEqual(harness.provider.repo.assist.linkedGitHubInstallationID, 303)
        await secondGate.open()
    }

    @MainActor
    func testPremiumRegistrationErrorSurvivesSuccessfulGitHubStatusRefresh() async throws {
        let api = ControllablePremiumRelayAPI()
        api.githubInstallationSummaries = [.init(githubInstallationID: 101, linkedAt: Date())]
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }
        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        api.registrationFailuresRemaining = 1
        harness.runtime.didRegister(token: Data(repeating: 0x46, count: 32))
        await waitUntil { harness.runtime.deviceRegistrationError != nil }
        let reason = try XCTUnwrap(harness.runtime.deviceRegistrationError)

        let refreshWasAuthoritative = await harness.runtime.refreshGitHubInstallations()
        XCTAssertTrue(refreshWasAuthoritative)
        XCTAssertEqual(harness.runtime.deviceRegistrationError, reason)
        XCTAssertEqual(harness.runtime.registrationError, reason)
        XCTAssertNil(harness.runtime.githubError)
    }

    func testAutomaticSyncTogglePrerequisitesApplyOnlyWhenEnabling() {
        XCTAssertFalse(PremiumAutomaticSyncToggleAvailability.isDisabled(
            currentlyEnabled: true, isWorking: false, deletionPending: false,
            relayDataWasDeleted: false, entitlementIsActive: false, relayIsConfigured: false
        ))
        XCTAssertTrue(PremiumAutomaticSyncToggleAvailability.isDisabled(
            currentlyEnabled: false, isWorking: false, deletionPending: false,
            relayDataWasDeleted: false, entitlementIsActive: false, relayIsConfigured: false
        ))
        XCTAssertTrue(PremiumAutomaticSyncToggleAvailability.isDisabled(
            currentlyEnabled: true, isWorking: false, deletionPending: true,
            relayDataWasDeleted: false, entitlementIsActive: true, relayIsConfigured: true
        ))
    }

    func testAssistUpsellBannerEligibilityGates() {
        let base: [Bool] = [true, true, false, false, false] // feature, repos, sub, assist, dismissed
        func check(_ args: [Bool]) -> Bool {
            AssistUpsellEligibility.shouldShowBanner(
                featureEnabled: args[0], hasRepositories: args[1],
                subscriptionActive: args[2], assistEnabled: args[3], bannerDismissed: args[4]
            )
        }
        XCTAssertTrue(check(base))
        // Feature flag, empty repo list, active subscription, enabled Assist,
        // and prior dismissal each suppress the banner.
        for i in 0..<5 {
            var args = base
            args[i].toggle()
            XCTAssertFalse(check(args), "banner should hide when flag #\(i) flips")
        }
    }

    func testAssistUpsellMilestoneEligibilityGates() {
        func check(feature: Bool = true, sub: Bool = false, assist: Bool = false,
                   shown: Bool = false, pulls: Int = AssistUpsellEligibility.milestonePullThreshold) -> Bool {
            AssistUpsellEligibility.shouldShowMilestone(
                featureEnabled: feature, subscriptionActive: sub, assistEnabled: assist,
                milestoneShown: shown, successfulPullCount: pulls
            )
        }
        XCTAssertTrue(check())
        XCTAssertFalse(check(feature: false))
        XCTAssertFalse(check(sub: true))
        XCTAssertFalse(check(assist: true))
        XCTAssertFalse(check(shown: true))
        XCTAssertFalse(check(pulls: AssistUpsellEligibility.milestonePullThreshold - 1))
        // Power users who already exceeded the threshold remain eligible.
        XCTAssertTrue(check(pulls: 50))
    }

    @MainActor
    func testSuccessfulManualPullsAdvanceAssistUpsellMilestoneCounter() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.pullPlanResult = PullPlan(
            action: .upToDate,
            branch: "main",
            localCommitSHA: fixture.repoConfig.gitState.commitSHA,
            remoteCommitSHA: fixture.repoConfig.gitState.commitSHA,
            hasLocalChanges: false,
            aheadBy: 0,
            behindBy: 0
        )
        fixture.repository.pullResult = .success(
            LocalPullResult(updated: false, newCommitSHA: fixture.repoConfig.gitState.commitSHA)
        )
        let state = AppState(gitRepositoryFactory: { _ in fixture.repository }, loadPersistedState: false)
        state.repos = [fixture.repoConfig]
        XCTAssertEqual(state.assistManualPullSuccessCount, 0)

        _ = await state.pullOnly(repoID: fixture.repoConfig.id, showsProgressDelay: false)
        XCTAssertEqual(state.assistManualPullSuccessCount, 1)
        XCTAssertEqual(state.pullOutcomeByRepo[fixture.repoConfig.id]?.kind, .upToDate)

        state.markAssistUpsellMilestoneShown()
        XCTAssertTrue(state.assistUpsellMilestoneShown)
    }

    @MainActor
    func testPremiumRuntimeRestoresAPNsGenerationFromKeychainAfterUserDefaultsLoss() async throws {
        let api = ControllablePremiumRelayAPI()
        let installationID = UUID()
        let generationDefaultsKey = "premium.apns-token-generation.sandbox.\(installationID.uuidString)"
        let generationKeychainKey = "premium.apns-token-generation.keychain.sandbox.\(installationID.uuidString)"
        KeychainService.save(key: generationKeychainKey, value: "10")
        UserDefaults.standard.removeObject(forKey: generationDefaultsKey)

        let harness = await PremiumRuntimeTestHarness.make(api: api, installationID: installationID)
        defer { harness.cleanup() }
        let linkURL = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        XCTAssertNotNil(linkURL)
        harness.runtime.didRegister(token: Data(repeating: 0x33, count: 32))
        await waitUntil { api.registrationRequests.count == 1 }

        XCTAssertEqual(api.registrationRequests.first?.registrationGeneration, 11)
        XCTAssertEqual(api.relayRegistrationGeneration, 11)
        XCTAssertEqual(UserDefaults.standard.object(forKey: generationDefaultsKey) as? NSNumber, 11)
        XCTAssertEqual(KeychainService.load(key: generationKeychainKey), "11")
    }

    @MainActor
    func testFeatureDisabledLaunchRecoveryCompletesPersistedDeletionWithoutStartingAssist() async throws {
        let api = ControllablePremiumRelayAPI()
        let keychain = ControllablePremiumKeychain()
        let installationID = UUID()
        try seedPendingRelayDeletion(installationID: installationID, keychain: keychain)
        let harness = await PremiumRuntimeTestHarness.make(
            api: api,
            installationID: installationID,
            assistFeatureIsEnabled: false,
            keychain: keychain
        )
        defer { harness.cleanup() }

        await harness.runtime.recoverPendingDeletion()

        XCTAssertEqual(api.installationDeletionCount, 1)
        XCTAssertEqual(keychain.value(for: "premium.relay-deletion-state.\(installationID.uuidString)"), "completed")
        XCTAssertFalse(harness.runtime.deletionInProgress)
        XCTAssertTrue(harness.runtime.relayDataWasDeleted)
        XCTAssertFalse(harness.runtime.hasRelayConsent)
        await assertDeletionRecoveryDidNotStartAssist(harness: harness, api: api)
    }

    @MainActor
    func testFeatureDisabledLaunchRecoveryFailureRetainsPendingBarrierWithoutStartingAssist() async throws {
        let api = ControllablePremiumRelayAPI()
        api.installationDeletionFailuresRemaining = 1
        let keychain = ControllablePremiumKeychain()
        let installationID = UUID()
        try seedPendingRelayDeletion(installationID: installationID, keychain: keychain)
        let barrierKey = "premium.relay-deletion-barrier.\(installationID.uuidString)"
        let harness = await PremiumRuntimeTestHarness.make(
            api: api,
            installationID: installationID,
            assistFeatureIsEnabled: false,
            keychain: keychain
        )
        defer { harness.cleanup() }

        await harness.runtime.recoverPendingDeletion()

        XCTAssertEqual(api.installationDeletionCount, 1)
        XCTAssertEqual(keychain.value(for: "premium.relay-deletion-state.\(installationID.uuidString)"), "pending")
        XCTAssertTrue(UserDefaults.standard.bool(forKey: barrierKey))
        XCTAssertTrue(harness.runtime.deletionInProgress)
        XCTAssertFalse(harness.runtime.relayDataWasDeleted)
        XCTAssertFalse(harness.runtime.hasRelayConsent)
        XCTAssertNotNil(harness.runtime.deletionError)
        await assertDeletionRecoveryDidNotStartAssist(harness: harness, api: api)
    }

    @MainActor
    private func seedPendingRelayDeletion(
        installationID: UUID,
        keychain: ControllablePremiumKeychain
    ) throws {
        let credential = PremiumInstallationCredential(
            installationID: installationID,
            token: "expired-runtime-token",
            deletionToken: "durable-deletion-token",
            expiresAt: .distantFuture
        )
        let encoded = try JSONEncoder().encode(credential).base64EncodedString()
        XCTAssertEqual(
            keychain.save(key: "premium.relay-deletion-credential.\(installationID.uuidString)", value: encoded),
            errSecSuccess
        )
        XCTAssertEqual(
            keychain.save(key: "premium.relay-deletion-state.\(installationID.uuidString)", value: "pending"),
            errSecSuccess
        )
        UserDefaults.standard.set(true, forKey: "premium.relay-deletion-barrier.\(installationID.uuidString)")
        UserDefaults.standard.set(true, forKey: "premium.relay-consent.\(installationID.uuidString)")
        UserDefaults.standard.set(true, forKey: "premium.automatic-sync.v1.\(installationID.uuidString)")
    }

    @MainActor
    private func assertDeletionRecoveryDidNotStartAssist(
        harness: PremiumRuntimeTestHarness,
        api: ControllablePremiumRelayAPI,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        XCTAssertEqual(api.authorizationCount, 0, file: file, line: line)
        XCTAssertTrue(api.registrationRequests.isEmpty, file: file, line: line)
        XCTAssertTrue(api.deviceDeletionRequests.isEmpty, file: file, line: line)
        XCTAssertEqual(api.githubLinkAttempts, 0, file: file, line: line)
        XCTAssertTrue(api.enrollmentRequests.isEmpty, file: file, line: line)
        XCTAssertTrue(api.deletedEnrollmentChannels.isEmpty, file: file, line: line)
        XCTAssertEqual(harness.registrar.registerCount, 0, file: file, line: line)
        XCTAssertEqual(
            harness.registrar.unregisterCount, 1,
            "Pending deletion recovery must reassert local APNs cleanup without registering",
            file: file, line: line
        )
        XCTAssertFalse(harness.runtime.isRegistered, file: file, line: line)
        XCTAssertEqual(harness.repository.executePullOnlyCallCount, 0, file: file, line: line)
        let entitlementRequests = await harness.storefront.currentEntitlementRequestCount()
        let appAccountTokenSets = await harness.storefront.appAccountTokenSetCount()
        XCTAssertEqual(entitlementRequests, 0, file: file, line: line)
        XCTAssertEqual(appAccountTokenSets, 0, file: file, line: line)
    }

    @MainActor
    func testPremiumRuntimeFailedTerminalDeletionExposesRetryOnlyOutsideRequestFlight() async throws {
        let deletionStarted = expectation(description: "terminal deletion request started")
        let deletionGate = AsyncGate()
        let api = ControllablePremiumRelayAPI(deletionStarted: deletionStarted, deletionGate: deletionGate)
        api.installationDeletionFailuresRemaining = 1
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }
        _ = await harness.runtime.setAutomaticallySyncAllRepositories(true)

        let deletion = Task { @MainActor in await harness.runtime.deleteRelayData() }
        await fulfillment(of: [deletionStarted], timeout: 2)
        XCTAssertTrue(harness.runtime.deletionInProgress)
        XCTAssertTrue(harness.runtime.deletionRequestInFlight)
        XCTAssertFalse(harness.runtime.canRetryRelayDeletion)
        await deletionGate.open()
        await deletion.value

        XCTAssertTrue(harness.runtime.deletionInProgress)
        XCTAssertFalse(harness.runtime.deletionRequestInFlight)
        XCTAssertTrue(harness.runtime.canRetryRelayDeletion)
        XCTAssertNotNil(harness.runtime.deletionError)

        await harness.runtime.deleteRelayData()
        XCTAssertFalse(harness.runtime.deletionInProgress)
        XCTAssertFalse(harness.runtime.deletionRequestInFlight)
        XCTAssertFalse(harness.runtime.canRetryRelayDeletion)
        XCTAssertTrue(harness.runtime.relayDataWasDeleted)
        XCTAssertNil(harness.runtime.deletionError)
        XCTAssertEqual(api.installationDeletionCount, 2)
    }

    @MainActor
    func testPremiumRuntimePersistsDeletionBarrierAcrossRestart() async throws {
        let deletionStarted = expectation(description: "installation deletion started")
        let deletionGate = AsyncGate()
        let api = ControllablePremiumRelayAPI(
            deletionStarted: deletionStarted,
            deletionGate: deletionGate,
            blockOnlyFirstInstallationDeletion: true
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }
        let linkURL = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        XCTAssertNotNil(linkURL)
        let deletion = Task { @MainActor in await harness.runtime.deleteRelayData() }
        await fulfillment(of: [deletionStarted], timeout: 2)

        let restarted = await PremiumRuntimeTestHarness.make(
            api: api,
            installationID: harness.installationID,
            repo: harness.provider.repo
        )
        XCTAssertFalse(restarted.runtime.hasRelayConsent)
        XCTAssertTrue(UserDefaults.standard.bool(forKey: "premium.relay-deletion-barrier.\(harness.installationID.uuidString)"))
        await restarted.runtime.start()
        XCTAssertFalse(restarted.runtime.hasRelayConsent)
        XCTAssertFalse(UserDefaults.standard.bool(forKey: "premium.relay-deletion-barrier.\(harness.installationID.uuidString)"))
        XCTAssertEqual(api.installationDeletionCount, 2)
        XCTAssertTrue(restarted.runtime.relayDataWasDeleted)
        let restartedLinkURL = await restarted.runtime.startGitHubLink()
        XCTAssertNil(restartedLinkURL)
        XCTAssertTrue(restarted.runtime.registrationError?.localizedCaseInsensitiveContains("permanently deleted") == true)

        let reinstalled = await PremiumRuntimeTestHarness.make(
            api: api,
            installationID: harness.installationID,
            repo: restarted.provider.repo
        )
        defer { reinstalled.cleanup() }
        XCTAssertTrue(reinstalled.runtime.relayDataWasDeleted)
        XCTAssertFalse(reinstalled.runtime.hasRelayConsent)
        let reinstalledLinkURL = await reinstalled.runtime.startGitHubLink()
        XCTAssertNil(reinstalledLinkURL)
        XCTAssertEqual(api.authorizationCount, 1, "A completed deletion must fail closed locally before relay reauthorization")

        await deletionGate.open()
        await deletion.value
    }

    @MainActor
    func testPremiumRuntimePendingStateWriteFailureBlocksRelayDeletionUntilDurable() async throws {
        let api = ControllablePremiumRelayAPI()
        let keychain = ControllablePremiumKeychain()
        let installationID = UUID()
        let stateKey = "premium.relay-deletion-state.\(installationID.uuidString)"
        let barrierKey = "premium.relay-deletion-barrier.\(installationID.uuidString)"
        let harness = await PremiumRuntimeTestHarness.make(
            api: api,
            installationID: installationID,
            keychain: keychain
        )
        defer { harness.cleanup() }
        let initialLink = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        XCTAssertNotNil(initialLink)

        keychain.failSave(for: stateKey)
        await harness.runtime.deleteRelayData()
        await harness.runtime.deleteRelayData()
        XCTAssertNil(keychain.value(for: stateKey))
        XCTAssertTrue(UserDefaults.standard.bool(forKey: barrierKey))
        XCTAssertFalse(harness.runtime.hasRelayConsent)
        XCTAssertEqual(api.installationDeletionCount, 0)

        let restarted = await PremiumRuntimeTestHarness.make(
            api: api,
            installationID: installationID,
            repo: harness.provider.repo,
            keychain: keychain
        )
        await restarted.runtime.start()
        let blockedLink = await restarted.runtime.startGitHubLink()
        XCTAssertNil(blockedLink)
        XCTAssertEqual(api.installationDeletionCount, 0, "No relay deletion may run without a read-verified pending marker")
        XCTAssertEqual(api.authorizationCount, 1, "A failed pending-state write must not reopen relay authorization")
        XCTAssertTrue(UserDefaults.standard.bool(forKey: barrierKey))

        keychain.allowSave(for: stateKey)
        await restarted.runtime.start()
        XCTAssertEqual(api.installationDeletionCount, 1)
        XCTAssertEqual(keychain.value(for: stateKey), "completed")
        XCTAssertTrue(restarted.runtime.relayDataWasDeleted)
        XCTAssertFalse(restarted.runtime.hasRelayConsent)
    }

    @MainActor
    func testPremiumRuntimeCompletedStateWriteFailurePreservesPendingAcrossReinstall() async throws {
        let api = ControllablePremiumRelayAPI()
        let keychain = ControllablePremiumKeychain()
        let installationID = UUID()
        let stateKey = "premium.relay-deletion-state.\(installationID.uuidString)"
        let barrierKey = "premium.relay-deletion-barrier.\(installationID.uuidString)"
        let harness = await PremiumRuntimeTestHarness.make(
            api: api,
            installationID: installationID,
            keychain: keychain
        )
        defer { harness.cleanup() }
        let initialLink = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        XCTAssertNotNil(initialLink)

        keychain.failSave(for: stateKey, value: "completed")
        await harness.runtime.deleteRelayData()
        XCTAssertEqual(api.installationDeletionCount, 1)
        XCTAssertEqual(keychain.value(for: stateKey), "pending", "A failed completion update must retain the pending marker")
        XCTAssertTrue(UserDefaults.standard.bool(forKey: barrierKey))
        XCTAssertFalse(harness.runtime.relayDataWasDeleted)
        XCTAssertFalse(harness.runtime.hasRelayConsent)

        UserDefaults.standard.removeObject(forKey: barrierKey)
        let reinstalled = await PremiumRuntimeTestHarness.make(
            api: api,
            installationID: installationID,
            repo: harness.provider.repo,
            keychain: keychain
        )
        XCTAssertFalse(reinstalled.runtime.hasRelayConsent)
        let blockedLink = await reinstalled.runtime.startGitHubLink()
        XCTAssertNil(blockedLink)
        XCTAssertEqual(api.authorizationCount, 1, "A retained pending marker must block reauthorization after defaults loss")
        XCTAssertEqual(api.installationDeletionCount, 2)
        XCTAssertEqual(keychain.value(for: stateKey), "pending")

        keychain.allowSave(for: stateKey, value: "completed")
        await reinstalled.runtime.start()
        XCTAssertEqual(api.installationDeletionCount, 3)
        XCTAssertEqual(keychain.value(for: stateKey), "completed")
        XCTAssertTrue(reinstalled.runtime.relayDataWasDeleted)
        XCTAssertFalse(reinstalled.runtime.hasRelayConsent)
    }

    @MainActor
    func testPremiumRuntimeDeletionPreflightFailurePreservesPendingBarrierAcrossRestart() async throws {
        let api = ControllablePremiumRelayAPI()
        let keychain = ControllablePremiumKeychain()
        let installationID = UUID()
        let stateKey = "premium.relay-deletion-state.\(installationID.uuidString)"
        let credentialKey = "premium.relay-deletion-credential.\(installationID.uuidString)"
        let barrierKey = "premium.relay-deletion-barrier.\(installationID.uuidString)"
        let harness = await PremiumRuntimeTestHarness.make(
            api: api,
            installationID: installationID,
            keychain: keychain
        )
        defer { harness.cleanup() }
        let initialLink = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        XCTAssertNotNil(initialLink)
        let originalCredential = try XCTUnwrap(keychain.value(for: credentialKey))

        keychain.failSave(for: credentialKey)
        keychain.failLoad(for: credentialKey)
        await harness.runtime.deleteRelayData()

        XCTAssertEqual(keychain.value(for: stateKey), "pending")
        XCTAssertEqual(keychain.value(for: credentialKey), originalCredential, "A failed replacement must not erase the existing credential")
        XCTAssertTrue(UserDefaults.standard.bool(forKey: barrierKey))
        XCTAssertFalse(harness.runtime.hasRelayConsent)
        XCTAssertEqual(api.installationDeletionCount, 0)

        let restarted = await PremiumRuntimeTestHarness.make(
            api: api,
            installationID: installationID,
            repo: harness.provider.repo,
            keychain: keychain
        )
        XCTAssertFalse(restarted.runtime.hasRelayConsent)
        XCTAssertFalse(restarted.runtime.relayDataWasDeleted)
        let blockedLink = await restarted.runtime.startGitHubLink()
        XCTAssertNil(blockedLink)
        XCTAssertEqual(api.authorizationCount, 1, "A pending deletion must not reopen relay authorization")
        XCTAssertTrue(UserDefaults.standard.bool(forKey: barrierKey))
        XCTAssertEqual(keychain.value(for: stateKey), "pending")

        keychain.allowSave(for: credentialKey)
        keychain.allowLoad(for: credentialKey)
        await restarted.runtime.start()
        XCTAssertEqual(api.installationDeletionCount, 1)
        XCTAssertEqual(keychain.value(for: stateKey), "completed")
        XCTAssertTrue(restarted.runtime.relayDataWasDeleted)
        XCTAssertFalse(restarted.runtime.hasRelayConsent)
    }

    @MainActor
    func testPremiumRuntimeRestoresDeletionCredentialAfterInactiveRestart() async throws {
        let api = ControllablePremiumRelayAPI()
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }
        let linkURL = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        XCTAssertNotNil(linkURL)

        let restarted = await PremiumRuntimeTestHarness.make(
            api: api,
            installationID: harness.installationID,
            repo: harness.provider.repo,
            activeEntitlement: false
        )
        XCTAssertTrue(restarted.runtime.canDeleteRelayData)
        await restarted.runtime.deleteRelayData()
        XCTAssertEqual(api.installationDeletionCount, 1)
        XCTAssertFalse(restarted.runtime.canDeleteRelayData)
    }

    @MainActor
    func testPremiumRuntimeDeletionBarrierRejectsInFlightRegistrationAndNewToken() async throws {
        let firstRegistrationStarted = expectation(description: "registration started")
        let firstRegistrationGate = AsyncGate()
        let deletionStarted = expectation(description: "installation deletion started")
        let deletionGate = AsyncGate()
        let api = ControllablePremiumRelayAPI(
            firstRegistrationStarted: firstRegistrationStarted,
            firstRegistrationGate: firstRegistrationGate,
            deletionStarted: deletionStarted,
            deletionGate: deletionGate
        )
        let harness = await PremiumRuntimeTestHarness.make(api: api)
        defer { harness.cleanup() }

        let linkURL = await harness.runtime.setAutomaticallySyncAllRepositories(true)
        XCTAssertNotNil(linkURL)
        let consentKey = "premium.relay-consent.\(harness.installationID.uuidString)"
        XCTAssertTrue(UserDefaults.standard.bool(forKey: consentKey))
        XCTAssertTrue(harness.runtime.entitlementStore.state.isActive)
        harness.runtime.didRegister(token: Data(repeating: 0x33, count: 32))
        await fulfillment(of: [firstRegistrationStarted], timeout: 2)
        let deletion = Task { @MainActor in await harness.runtime.deleteRelayData() }
        await fulfillment(of: [deletionStarted], timeout: 2)
        harness.runtime.didRegister(token: Data(repeating: 0x44, count: 32))
        await firstRegistrationGate.open()
        await deletionGate.open()
        await deletion.value
        for _ in 0..<20 { await Task.yield() }

        XCTAssertFalse(harness.runtime.hasRelayConsent)
        XCTAssertFalse(harness.runtime.isRegistered)
        XCTAssertEqual(api.registrationRequests.map(\.token), [String(repeating: "33", count: 32)])
        XCTAssertTrue(api.deviceDeletionRequests.isEmpty)
        XCTAssertEqual(api.installationDeletionCount, 1)
        XCTAssertEqual(harness.registrar.unregisterCount, 1)
    }

    @MainActor
    private func waitUntil(
        timeout: Duration = .seconds(2),
        condition: @escaping @MainActor () -> Bool
    ) async {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: timeout)
        while !condition() && clock.now < deadline {
            await Task.yield()
        }
        XCTAssertTrue(condition(), "Timed out waiting for asynchronous condition")
    }

    @MainActor
    func testPremiumRuntimeRepositoryRemovalCancelsProviderFlightWithoutChannel() async throws {
        let gate = AsyncGate()
        let commit = String(repeating: "1", count: 40)
        let repository = FakeGitRepository(
            repoInfoResult: LocalRepoInfo(branch: "main", commitSHA: commit, changeCount: 0)
        )
        repository.executePullOnlyGate = gate
        var repo = RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = commit
        repo.assist = RepoAssistSettings(enabled: true, channel: nil, selectedBranch: "main")
        let persistenceURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("provider-removal-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: persistenceURL) }
        let provider = AppState(
            gitRepositoryFactory: { _ in repository },
            reposFileURL: persistenceURL,
            loadPersistedState: false
        )
        provider.repos = [repo]
        let coordinator = BackgroundSyncCoordinator(
            entitlementIsActive: { true }, repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        let storefront = FakePremiumStorefront()
        let defaultsSuite = "provider-removal-runtime-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: defaultsSuite)!
        defer { defaults.removePersistentDomain(forName: defaultsSuite) }
        let entitlement = PremiumEntitlementStore(storefront: storefront, identifiers: .default, defaults: defaults)
        let runtime = PremiumRuntime(
            entitlementStore: entitlement,
            coordinator: coordinator,
            repositoryProvider: provider,
            api: ControllablePremiumRelayAPI(),
            registrar: RecordingRemoteNotificationRegistrar(),
            installation: .init(installationID: UUID(), bundleID: "test", appVersion: "test"),
            environment: .sandbox,
            bridge: PremiumNotificationBridge(),
            assistFeatureIsEnabled: { true },
            keychain: ControllablePremiumKeychain(),
            defaults: defaults
        )
        _ = runtime

        let flight = Task { @MainActor in await coordinator.reconcile(repoID: repo.id) }
        await waitUntil { repository.executePullOnlyCallCount == 1 }
        provider.removeRepo(id: repo.id)
        await gate.open()

        let result = await flight.value
        XCTAssertEqual(result, .deferred("Cancelled"))
        XCTAssertNil(provider.repo(id: repo.id))
    }

    @MainActor
    func testPremiumRuntimeForegroundCancellationPropagatesToCoordinatorFlight() async {
        let gate = AsyncGate()
        let commit = String(repeating: "1", count: 40)
        let repository = FakeGitRepository(
            repoInfoResult: LocalRepoInfo(branch: "main", commitSHA: commit, changeCount: 0)
        )
        repository.executePullOnlyGate = gate
        var repo = RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = commit
        repo.assist = RepoAssistSettings(enabled: true, channel: nil, selectedBranch: "main")
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: repository)
        let coordinator = BackgroundSyncCoordinator(
            entitlementIsActive: { true }, repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        let storefront = FakePremiumStorefront()
        let defaultsSuite = "foreground-cancel-runtime-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: defaultsSuite)!
        defer { defaults.removePersistentDomain(forName: defaultsSuite) }
        let entitlement = PremiumEntitlementStore(storefront: storefront, identifiers: .default, defaults: defaults)
        let runtime = PremiumRuntime(
            entitlementStore: entitlement,
            coordinator: coordinator,
            repositoryProvider: provider,
            api: ControllablePremiumRelayAPI(),
            registrar: RecordingRemoteNotificationRegistrar(),
            installation: .init(installationID: UUID(), bundleID: "test", appVersion: "test"),
            environment: .sandbox,
            bridge: PremiumNotificationBridge(),
            assistFeatureIsEnabled: { true },
            keychain: ControllablePremiumKeychain(),
            defaults: defaults
        )

        let foreground = Task { @MainActor in await coordinator.reconcileForeground() }
        await waitUntil { repository.executePullOnlyCallCount == 1 }
        runtime.cancelForegroundReconciliation()
        await gate.open()

        let results = await foreground.value
        XCTAssertEqual(results[repo.id], .deferred("Cancelled"))
        XCTAssertEqual(provider.repo.assist.health, .never)
    }

    @MainActor
    func testBackgroundCoordinatorGatesAndRecordsTypedResults() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        repo.assist = RepoAssistSettings(enabled: true, channel: "channel_12345678", selectedBranch: "main")
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let conditions = FakeAssistConditions(BackgroundSyncConditions(isWiFi: true, isExternalPower: true))
        let coordinator = BackgroundSyncCoordinator(entitlementIsActive: { true }, repositoryProvider: provider, conditionsProvider: conditions)

        let result = await coordinator.handlePush([
            "aps": ["content-available": 1], "channel": "channel_12345678", "hint": "event-12345678"
        ])
        XCTAssertEqual(result, .completed(.upToDate(branch: "main", commitSHA: repo.gitState.commitSHA)))
        XCTAssertEqual(provider.repo.assist.health.kind, .upToDate)
        XCTAssertEqual(fixture.repository.executePullOnlyCallCount, 1)
        XCTAssertTrue(fixture.repository.stagedPaths.isEmpty)
        XCTAssertEqual(fixture.repository.commitLocalCallCount, 0)
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)
        XCTAssertEqual(fixture.repository.pullRebaseCallCount, 0)
        XCTAssertEqual(fixture.repository.continueRebaseCallCount, 0)
        XCTAssertEqual(fixture.repository.mergeBranchCallCount, 0)
        XCTAssertEqual(fixture.repository.completeMergeCallCount, 0)
        XCTAssertEqual(fixture.repository.pushCurrentBranchCallCount, 0)
        XCTAssertTrue(fixture.repository.pushedTagNames.isEmpty)
        XCTAssertTrue(fixture.repository.resolvedConflicts.isEmpty)
        let duplicate = await coordinator.handlePush([
            "aps": ["content-available": 1], "channel": "channel_12345678", "hint": "event-12345678"
        ])
        XCTAssertEqual(duplicate, .ignored)

        provider.repo.assist.networkPolicy = .wifiOnly
        await conditions.set(BackgroundSyncConditions(isWiFi: false, isExternalPower: true))
        let wifiDeferred = await coordinator.reconcile(repoID: repo.id)
        XCTAssertEqual(wifiDeferred, .deferred("Waiting for Wi-Fi."))
        provider.repo.assist.networkPolicy = .any
        provider.repo.assist.selectedBranch = "notes"
        let branchResult = await coordinator.reconcile(repoID: repo.id)
        XCTAssertEqual(branchResult, .completed(.wrongBranch(expected: "notes", actual: "main")))
        XCTAssertEqual(provider.repo.assist.health.attention, .wrongBranch)

        let inactive = BackgroundSyncCoordinator(entitlementIsActive: { false }, repositoryProvider: provider, conditionsProvider: conditions)
        let inactiveResult = await inactive.reconcile(repoID: repo.id)
        XCTAssertEqual(inactiveResult, .ignored)
    }

    @MainActor
    func testBackgroundCoordinatorDoesNotDedupeHintUntilAnEnabledRepositoryOwnsChannel() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        let provider = FakeAssistRepositoryProvider(repo: fixture.repoConfig, repository: fixture.repository)
        provider.repos = []
        let coordinator = BackgroundSyncCoordinator(
            entitlementIsActive: { true }, repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        let payload: [AnyHashable: Any] = [
            "aps": ["content-available": 1],
            "channel": "channel_12345678",
            "hint": "late-owner-12345"
        ]

        let unknownChannel = await coordinator.handlePush(payload)
        XCTAssertEqual(unknownChannel, .ignored)
        var added = fixture.repoConfig
        added.assist = RepoAssistSettings(enabled: true, channel: "channel_12345678", selectedBranch: "main")
        provider.addRepository(added)
        let ownedChannel = await coordinator.handlePush(payload)
        XCTAssertEqual(
            ownedChannel,
            .completed(.upToDate(branch: "main", commitSHA: added.gitState.commitSHA))
        )
    }

    @MainActor
    func testBackgroundCoordinatorReconcilesEveryLocalCloneSharingPushChannel() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var first = fixture.repoConfig
        first.assist = RepoAssistSettings(enabled: true, channel: "channel_12345678", selectedBranch: "main")
        var second = RepoConfig(repoURL: first.repoURL, branch: first.branch, authorName: first.authorName,
                                authorEmail: first.authorEmail, vaultFolderName: "second", gitState: first.gitState)
        second.assist = first.assist
        let provider = FakeAssistRepositoryProvider(repos: [first, second], repository: fixture.repository)
        let coordinator = BackgroundSyncCoordinator(entitlementIsActive: { true }, repositoryProvider: provider,
                                                    conditionsProvider: PermissiveBackgroundSyncConditions())

        let result = await coordinator.handlePush([
            "aps": ["content-available": 1], "channel": "channel_12345678", "hint": "shared-12345678"
        ])

        XCTAssertEqual(result, .completed(.upToDate(branch: "main", commitSHA: first.gitState.commitSHA)))
        XCTAssertEqual(fixture.repository.executePullOnlyCallCount, 2)
        XCTAssertEqual(provider.repos.map(\.assist.health.kind), [.upToDate, .upToDate])
    }

    @MainActor
    func testBackgroundCoordinatorMapsAllAttentionOutcomesAndPreservesLastSuccess() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        let successDate = Date(timeIntervalSince1970: 1_700_000_000)
        repo.assist = RepoAssistSettings(
            enabled: true,
            channel: "channel_12345678",
            selectedBranch: "main",
            health: RepoAssistHealth(kind: .upToDate, lastAttemptDate: successDate, lastSuccessDate: successDate, commitSHA: repo.gitState.commitSHA)
        )
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let conditions = FakeAssistConditions(.init(isWiFi: true, isExternalPower: true))
        let coordinator = BackgroundSyncCoordinator(entitlementIsActive: { true }, repositoryProvider: provider, conditionsProvider: conditions)

        let cases: [(Result<PullExecutionResult, Error>, RepoAssistAttention, RepoAssistHealthKind)] = [
            (.success(.init(plan: .init(action: .blockedByLocalChanges, branch: "main", localCommitSHA: "a", remoteCommitSHA: "b", hasLocalChanges: true, aheadBy: 0, behindBy: 1), pullResult: nil)), .localChanges, .attention),
            (.success(.init(plan: .init(action: .diverged, branch: "main", localCommitSHA: "a", remoteCommitSHA: "b", hasLocalChanges: false, aheadBy: 1, behindBy: 1), pullResult: nil)), .diverged, .attention),
            (.success(.init(plan: .init(action: .remoteBranchMissing, branch: "main", localCommitSHA: "a", remoteCommitSHA: "", hasLocalChanges: false, aheadBy: 0, behindBy: 0), pullResult: nil)), .remoteBranchMissing, .attention),
            (.failure(LocalGitError.authenticationFailed("Authentication required.")), .authenticationOrTrust, .attention),
            (.failure(LocalGitError.wrongBranch(expected: "main", actual: "notes")), .wrongBranch, .attention),
            (.failure(LocalGitError.notCloned), .unavailable, .attention),
            (.failure(LocalGitError.fetchFailed("network down")), .failed, .failed),
        ]

        for (result, attention, kind) in cases {
            fixture.repository.executePullOnlyResult = result
            _ = await coordinator.reconcile(repoID: repo.id)
            XCTAssertEqual(provider.repo.assist.health.kind, kind)
            XCTAssertEqual(provider.repo.assist.health.attention, attention)
            XCTAssertEqual(provider.repo.assist.health.lastSuccessDate, successDate)
            XCTAssertEqual(provider.repo.assist.health.commitSHA, repo.gitState.commitSHA)
        }
    }

    @MainActor
    func testBackgroundCoordinatorRecordsPostUpdateHydrationAttentionWithNewSHAAndPriorSuccessDate() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        let successDate = Date(timeIntervalSince1970: 1_700_000_000)
        let newCommit = "efefefefefefefefefefefefefefefefefefefef"
        repo.assist = RepoAssistSettings(
            enabled: true,
            channel: "channel_12345678",
            selectedBranch: "main",
            health: RepoAssistHealth(
                kind: .upToDate,
                lastAttemptDate: successDate,
                lastSuccessDate: successDate,
                commitSHA: repo.gitState.commitSHA
            )
        )
        fixture.repository.pullPlanResult = PullPlan(
            action: .fastForward,
            branch: "main",
            localCommitSHA: repo.gitState.commitSHA,
            remoteCommitSHA: newCommit,
            hasLocalChanges: false,
            aheadBy: 0,
            behindBy: 1
        )
        fixture.repository.pullResult = .success(LocalPullResult(
            updated: true,
            newCommitSHA: newCommit,
            attention: .lfsHydrationBlockedByLocalChanges(path: "Manual.pdf")
        ))
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let coordinator = BackgroundSyncCoordinator(
            entitlementIsActive: { true },
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )

        let disposition = await coordinator.reconcile(repoID: repo.id)

        guard case .completed(.updatedWithAttention(_, let resultSHA, _)) = disposition else {
            return XCTFail("Expected completed updated-with-attention, got \(disposition)")
        }
        XCTAssertEqual(resultSHA, newCommit)
        XCTAssertEqual(provider.repo.gitState.commitSHA, newCommit)
        XCTAssertEqual(provider.repo.assist.health.kind, .attention)
        XCTAssertEqual(provider.repo.assist.health.attention, .lfsHydration)
        XCTAssertEqual(provider.repo.assist.health.commitSHA, newCommit)
        XCTAssertEqual(provider.repo.assist.health.lastSuccessDate, successDate)
    }

    func testLocalGitPullOnlySafeCheckoutPreservesWriteArrivingAfterFinalStatusRead() async throws {
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-PullOnlyCheckoutRace")
        defer { try? FileManager.default.removeItem(at: repoURL) }
        let originURL = FileManager.default.temporaryDirectory.appendingPathComponent("SyncMD-PullOnlyCheckoutRace-Origin-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("README.md")
        let setup = LocalGitService(localURL: repoURL)
        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "README.md")
        _ = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        let baseSHA = try await setup.repoInfo().commitSHA
        try "remote\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "README.md")
        let remoteSHA = try await setup.commitLocal(message: "Remote", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: remoteSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: remoteSHA)
        try checkoutHeadTree(repoURL: repoURL)
        try await setup.setRemoteURL(name: "origin", url: "file://localhost\(originURL.path)")
        let service = LocalGitService(localURL: repoURL, pullOnlyBeforeCheckout: {
            try? "external write\n".write(to: fileURL, atomically: true, encoding: .utf8)
        })

        let execution = try await service.executePullOnly(pat: "", expectedBranch: "main")

        XCTAssertEqual(execution.plan.action, .blockedByLocalChanges)
        XCTAssertNil(execution.pullResult)
        XCTAssertEqual(try String(contentsOf: fileURL, encoding: .utf8), "external write\n")
        let finalInfo = try await service.repoInfo()
        XCTAssertEqual(finalInfo.commitSHA, baseSHA)
    }

    func testLocalGitPullOnlyNormalizesCleanHydratedLFSForSafeUpdateAndDeletion() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-LFSSafeCheckout")
        defer { try? fm.removeItem(at: repoURL) }
        let originURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSSafeCheckout-Origin-\(UUID().uuidString)", isDirectory: true)
        defer { try? fm.removeItem(at: originURL) }
        let changedURL = repoURL.appendingPathComponent("Changed.pdf")
        let deletedURL = repoURL.appendingPathComponent("Deleted.pdf")
        let oldChanged = Data("old hydrated changed bytes\n".utf8)
        let oldDeleted = Data("old hydrated deleted bytes\n".utf8)
        let newChanged = Data("new hydrated changed bytes\n".utf8)
        try "*.pdf filter=lfs diff=lfs merge=lfs -text\n".write(
            to: repoURL.appendingPathComponent(".gitattributes"), atomically: true, encoding: .utf8
        )
        try oldChanged.write(to: changedURL)
        try oldDeleted.write(to: deletedURL)
        let setup = LocalGitService(localURL: repoURL)
        try await setup.stageAll()
        let baseSHA = try await setup.commitLocal(message: "Base LFS", authorName: "Tests", authorEmail: "tests@example.com")

        try newChanged.write(to: changedURL)
        try fm.removeItem(at: deletedURL)
        try await setup.stageAll()
        let remoteSHA = try await setup.commitLocal(message: "Remote LFS update", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: remoteSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: remoteSHA)
        try checkoutHeadTree(repoURL: repoURL)
        try oldChanged.write(to: changedURL)
        try oldDeleted.write(to: deletedURL)
        let cleanBeforePull = try await setup.repoInfo()
        XCTAssertEqual(cleanBeforePull.changeCount, 0)
        try await setup.setRemoteURL(name: "origin", url: "file://localhost\(originURL.path)")

        let execution = try await setup.executePullOnly(pat: "", expectedBranch: "main")

        XCTAssertEqual(execution.plan.action, .fastForward)
        XCTAssertEqual(execution.pullResult?.newCommitSHA, remoteSHA)
        XCTAssertNil(execution.pullResult?.attention)
        XCTAssertEqual(try Data(contentsOf: changedURL), newChanged)
        XCTAssertFalse(fm.fileExists(atPath: deletedURL.path))
        let finalInfo = try await setup.repoInfo()
        XCTAssertEqual(finalInfo.commitSHA, remoteSHA)
        XCTAssertEqual(finalInfo.changeCount, 0)
    }

    func testLocalGitPullOnlyRollsBackNormalizedHydrationWhenAnotherPathConflicts() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-LFSNormalizationRollback")
        defer { try? fm.removeItem(at: repoURL) }
        let originURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSNormalizationRollback-Origin-\(UUID().uuidString)", isDirectory: true)
        defer { try? fm.removeItem(at: originURL) }
        let lfsURL = repoURL.appendingPathComponent("A.pdf")
        let conflictURL = repoURL.appendingPathComponent("Z.md")
        let oldData = Data("old hydrated data\n".utf8)
        let newData = Data("new hydrated data\n".utf8)
        try "*.pdf filter=lfs diff=lfs merge=lfs -text\n".write(
            to: repoURL.appendingPathComponent(".gitattributes"), atomically: true, encoding: .utf8
        )
        try oldData.write(to: lfsURL)
        try "base\n".write(to: conflictURL, atomically: true, encoding: .utf8)
        let setup = LocalGitService(localURL: repoURL)
        try await setup.stageAll()
        let baseSHA = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        try newData.write(to: lfsURL)
        try "remote\n".write(to: conflictURL, atomically: true, encoding: .utf8)
        try await setup.stageAll()
        let remoteSHA = try await setup.commitLocal(message: "Remote", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: remoteSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: remoteSHA)
        try checkoutHeadTree(repoURL: repoURL)
        try oldData.write(to: lfsURL)
        try await setup.setRemoteURL(name: "origin", url: "file://localhost\(originURL.path)")
        let service = LocalGitService(localURL: repoURL, pullOnlyBeforeCheckout: {
            try? "external\n".write(to: conflictURL, atomically: true, encoding: .utf8)
        })

        let execution = try await service.executePullOnly(pat: "", expectedBranch: "main")

        XCTAssertEqual(execution.plan.action, .blockedByLocalChanges)
        XCTAssertNil(execution.pullResult)
        XCTAssertEqual(try Data(contentsOf: lfsURL), oldData)
        XCTAssertEqual(try String(contentsOf: conflictURL, encoding: .utf8), "external\n")
        let finalInfo = try await setup.repoInfo()
        XCTAssertEqual(finalInfo.commitSHA, baseSHA)
    }

    func testLocalGitPullOnlyAdvancesHEADAndReturnsAttentionWhenPostCommitHydrationConflicts() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-LFSPostCommitAttention")
        defer { try? fm.removeItem(at: repoURL) }
        let originURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSPostCommitAttention-Origin-\(UUID().uuidString)", isDirectory: true)
        defer { try? fm.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("Manual.pdf")
        let oldData = Data("old hydrated data\n".utf8)
        let newData = Data("new hydrated data\n".utf8)
        let externalData = Data("edit during hydration\n".utf8)
        try "*.pdf filter=lfs diff=lfs merge=lfs -text\n".write(
            to: repoURL.appendingPathComponent(".gitattributes"), atomically: true, encoding: .utf8
        )
        try oldData.write(to: fileURL)
        let setup = LocalGitService(localURL: repoURL)
        try await setup.stageAll()
        let baseSHA = try await setup.commitLocal(message: "Base LFS", authorName: "Tests", authorEmail: "tests@example.com")
        try newData.write(to: fileURL)
        try await setup.stageAll()
        let remoteSHA = try await setup.commitLocal(message: "Remote LFS", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: remoteSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: remoteSHA)
        try checkoutHeadTree(repoURL: repoURL)
        try oldData.write(to: fileURL)
        try await setup.setRemoteURL(name: "origin", url: "file://localhost\(originURL.path)")
        let service = LocalGitService(localURL: repoURL, pullOnlyBeforeLFSReplacement: { path in
            if path == "Manual.pdf" { try? externalData.write(to: fileURL, options: .atomic) }
        })

        let execution = try await service.executePullOnly(pat: "", expectedBranch: "main")

        XCTAssertEqual(execution.plan.action, .fastForward)
        XCTAssertEqual(execution.pullResult?.updated, true)
        XCTAssertEqual(execution.pullResult?.newCommitSHA, remoteSHA)
        XCTAssertEqual(
            execution.pullResult?.attention,
            .lfsHydrationBlockedByLocalChanges(path: "Manual.pdf")
        )
        let finalInfo = try await setup.repoInfo()
        XCTAssertEqual(finalInfo.commitSHA, remoteSHA)
        XCTAssertEqual(try Data(contentsOf: fileURL), externalData)
    }

    func testLocalGitPullOnlyPreservesEditArrivingBeforeLFSNormalization() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-LFSNormalizationRace")
        defer { try? fm.removeItem(at: repoURL) }
        let originURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSNormalizationRace-Origin-\(UUID().uuidString)", isDirectory: true)
        defer { try? fm.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("Manual.pdf")
        let oldData = Data("old hydrated data\n".utf8)
        let newData = Data("new hydrated data\n".utf8)
        let externalData = Data("external edit data\n".utf8)
        try "*.pdf filter=lfs diff=lfs merge=lfs -text\n".write(
            to: repoURL.appendingPathComponent(".gitattributes"), atomically: true, encoding: .utf8
        )
        try oldData.write(to: fileURL)
        let setup = LocalGitService(localURL: repoURL)
        try await setup.stageAll()
        let baseSHA = try await setup.commitLocal(message: "Base LFS", authorName: "Tests", authorEmail: "tests@example.com")
        try newData.write(to: fileURL)
        try await setup.stageAll()
        let remoteSHA = try await setup.commitLocal(message: "Remote LFS", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: remoteSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: remoteSHA)
        try checkoutHeadTree(repoURL: repoURL)
        try oldData.write(to: fileURL)
        try await setup.setRemoteURL(name: "origin", url: "file://localhost\(originURL.path)")
        let service = LocalGitService(localURL: repoURL, pullOnlyBeforeCheckout: {
            try? externalData.write(to: fileURL, options: .atomic)
        })

        let execution = try await service.executePullOnly(pat: "", expectedBranch: "main")

        XCTAssertEqual(execution.plan.action, .blockedByLocalChanges)
        XCTAssertNil(execution.pullResult)
        XCTAssertEqual(try Data(contentsOf: fileURL), externalData)
        let finalInfo = try await setup.repoInfo()
        XCTAssertEqual(finalInfo.commitSHA, baseSHA)
    }

    func testLocalGitPullOnlyDoesNotOverwriteBranchAdvancedAfterAncestryValidation() async throws {
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-PullOnlyRefRace")
        defer { try? FileManager.default.removeItem(at: repoURL) }
        let originURL = FileManager.default.temporaryDirectory.appendingPathComponent("SyncMD-PullOnlyRefRace-Origin-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("README.md")
        let setup = LocalGitService(localURL: repoURL)

        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "README.md")
        _ = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        let baseSHA = try await setup.repoInfo().commitSHA

        try "remote\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "README.md")
        let remoteSHA = try await setup.commitLocal(message: "Remote", authorName: "Tests", authorEmail: "tests@example.com")

        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: remoteSHA)
        try checkoutHeadTree(repoURL: repoURL)
        try "concurrent\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "README.md")
        let concurrentSHA = try await setup.commitLocal(message: "Concurrent", authorName: "Tests", authorEmail: "tests@example.com")

        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: remoteSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: remoteSHA)
        try checkoutHeadTree(repoURL: repoURL)
        try await setup.setRemoteURL(name: "origin", url: "file://localhost\(originURL.path)")
        let service = LocalGitService(localURL: repoURL, pullOnlyBeforeCheckout: {
            try? setLocalBranchRef(repoURL: repoURL, branch: "main", sha: concurrentSHA)
        })

        do {
            _ = try await service.executePullOnly(pat: "", expectedBranch: "main")
            XCTFail("Expected a concurrent branch update to abort the fast-forward")
        } catch LocalGitError.pullDiverged {
            // The external commit remains authoritative; automation fails closed.
        }

        let finalInfo = try await service.repoInfo()
        XCTAssertEqual(finalInfo.commitSHA, concurrentSHA)
        XCTAssertEqual(try String(contentsOf: fileURL, encoding: .utf8), "base\n")
        XCTAssertNotEqual(finalInfo.commitSHA, remoteSHA)

        // The same transaction path still performs an ordinary clean
        // fast-forward once the fixture is restored to the expected base.
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: remoteSHA)
        try checkoutHeadTree(repoURL: repoURL)
        let successful = try await LocalGitService(localURL: repoURL).executePullOnly(pat: "", expectedBranch: "main")
        XCTAssertEqual(successful.plan.action, .fastForward)
        XCTAssertEqual(successful.pullResult?.updated, true)
        XCTAssertEqual(successful.pullResult?.newCommitSHA, remoteSHA)
        let successfulInfo = try await setup.repoInfo()
        XCTAssertEqual(successfulInfo.commitSHA, remoteSHA)
        XCTAssertEqual(try String(contentsOf: fileURL, encoding: .utf8), "remote\n")
    }

    func testLocalGitPullOnlyCancellationBeforeMutationPreventsCheckoutAndReleasesLease() async throws {
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-PullOnlyCancellation")
        defer { try? FileManager.default.removeItem(at: repoURL) }
        let originURL = FileManager.default.temporaryDirectory.appendingPathComponent("SyncMD-PullOnlyCancellation-Origin-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("README.md")
        let setup = LocalGitService(localURL: repoURL)

        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "README.md")
        _ = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        let baseSHA = try await setup.repoInfo().commitSHA
        try "remote\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "README.md")
        let remoteSHA = try await setup.commitLocal(message: "Remote", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: remoteSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: remoteSHA)
        try checkoutHeadTree(repoURL: repoURL)
        try await setup.setRemoteURL(name: "origin", url: "file://localhost\(originURL.path)")

        let beforeMutation = expectation(description: "real LocalGitService reached final pre-mutation hook")
        let releaseHook = DispatchSemaphore(value: 0)
        let service = LocalGitService(localURL: repoURL, pullOnlyBeforeCheckout: {
            beforeMutation.fulfill()
            releaseHook.wait()
        })
        let coordinator = RepositoryOperationCoordinator()
        let serialized = SerializedGitRepository(base: service, localURL: repoURL, coordinator: coordinator)
        let pull = Task {
            try await serialized.executePullOnly(pat: "", expectedBranch: "main")
        }

        await fulfillment(of: [beforeMutation], timeout: 2)
        pull.cancel()
        releaseHook.signal()
        do {
            _ = try await pull.value
            XCTFail("Expected cancellation from detached libgit2 bridge")
        } catch is CancellationError {
            // The cancellation signal is checked before transaction/checkout.
        }

        let infoAfterCancellation = try await setup.repoInfo()
        XCTAssertEqual(infoAfterCancellation.commitSHA, baseSHA)
        XCTAssertEqual(try String(contentsOf: fileURL, encoding: .utf8), "base\n")

        // Acquiring through the same coordinator proves the canceled operation
        // returned from detached work and released its repository lease.
        let postCancellationInfo = try await serialized.repoInfo()
        XCTAssertEqual(postCancellationInfo.commitSHA, baseSHA)
        XCTAssertEqual(postCancellationInfo.changeCount, 0)
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

        let completed = await appState.pull(repoID: fixture.repoConfig.id)

        XCTAssertTrue(completed, "Foreground pull preserves the legacy completed-classification contract")
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
    func testAppStatePushCurrentBranchPushesAheadCommitWithoutNewChanges() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main",
            commitSHA: fixture.repoConfig.gitState.commitSHA,
            changeCount: 0,
            syncState: .upToDate,
            statusEntries: []
        )

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]
        appState.syncStateByRepo[fixture.repoConfig.id] = .ahead

        let succeeded = await appState.pushCurrentBranch(repoID: fixture.repoConfig.id)

        XCTAssertTrue(succeeded)
        XCTAssertTrue(fixture.repository.didPushCurrentBranch)
        XCTAssertEqual(appState.syncStateByRepo[fixture.repoConfig.id], .upToDate)
    }

    func testSSHHostKeyTrustRequestFormatsUnknownAndChangedHostMessages() {
        let unknown = SSHHostKeyTrustRequest(
            repoID: UUID(),
            operation: .clone,
            trustError: .unknownHostKey(host: "forgejo.lan", port: 2222, algorithm: "ssh-ed25519", fingerprint: "SHA256:unknown", sawOtherKeyTypes: false)
        )
        XCTAssertEqual(unknown.title, "Trust SSH Host?")
        XCTAssertEqual(unknown.confirmButtonTitle, "Trust Host")
        XCTAssertEqual(unknown.host, "forgejo.lan")
        XCTAssertEqual(unknown.port, 2222)
        XCTAssertEqual(unknown.fingerprintToTrust, "SHA256:unknown")
        XCTAssertTrue(unknown.message.contains("forgejo.lan:2222"))
        XCTAssertTrue(unknown.message.contains("SHA256:unknown"))
        XCTAssertTrue(unknown.message.contains("Only trust it"))

        let changed = SSHHostKeyTrustRequest(
            repoID: UUID(),
            operation: .pull,
            trustError: .changedHostKey(
                host: "forgejo.lan",
                port: 22,
                algorithm: "ssh-rsa",
                expectedFingerprint: "SHA256:old",
                actualFingerprint: "SHA256:new"
            )
        )
        XCTAssertEqual(changed.title, "SSH Host Key Changed")
        XCTAssertEqual(changed.confirmButtonTitle, "Trust New Key")
        XCTAssertEqual(changed.host, "forgejo.lan")
        XCTAssertEqual(changed.port, 22)
        XCTAssertEqual(changed.fingerprintToTrust, "SHA256:new")
        XCTAssertTrue(changed.message.contains("Previously trusted"))
        XCTAssertTrue(changed.message.contains("SHA256:old"))
        XCTAssertTrue(changed.message.contains("SHA256:new"))
        XCTAssertTrue(changed.message.contains("Do not trust"))
    }

    @MainActor
    func testAppStateCloneUnknownSSHHostKeyPromptsInsteadOfShowingGenericError() async throws {
        let repoInfo = LocalRepoInfo(branch: "main", commitSHA: "1111111111111111111111111111111111111111", changeCount: 0)
        let repository = FakeGitRepository(repoInfoResult: repoInfo)
        let trustError = GitLFSSSHHostKeyTrustError.unknownHostKey(
            host: "forgejo.example.com",
            port: 22,
            algorithm: "ssh-ed25519",
            fingerprint: "SHA256:clone-unknown",
            sawOtherKeyTypes: false
        )
        repository.cloneResults = [.failure(LocalGitError.sshHostKeyTrustRequired(trustError))]
        let repo = RepoConfig(
            repoURL: "git@forgejo.example.com:team/notes.git",
            branch: "main",
            authorName: "Test User",
            authorEmail: "test@example.com",
            vaultFolderName: "SyncMD-SSHHostKey-\(UUID().uuidString)",
            authMethod: .sshKey,
            authUsername: "git"
        )
        let trustURL = FileManager.default.temporaryDirectory.appendingPathComponent("SyncMD-Trust-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }
        let appState = AppState(
            gitRepositoryFactory: { _ in repository },
            sshHostKeyTrustStore: GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL),
            loadPersistedState: false
        )
        appState.repos = [repo]

        await appState.clone(repoID: repo.id)

        XCTAssertEqual(repository.cloneRemoteURLs, ["git@forgejo.example.com:team/notes.git"])
        XCTAssertEqual(appState.pendingSSHHostKeyTrustRequest?.repoID, repo.id)
        XCTAssertEqual(appState.pendingSSHHostKeyTrustRequest?.operation, .clone)
        XCTAssertEqual(appState.pendingSSHHostKeyTrustRequest?.trustError, trustError)
        XCTAssertFalse(appState.showError)
        XCTAssertNil(appState.lastError)
    }

    @MainActor
    func testTrustingPendingSSHHostKeyPersistsFingerprintAndRetriesClone() async throws {
        let repoInfo = LocalRepoInfo(branch: "main", commitSHA: "2222222222222222222222222222222222222222", changeCount: 0)
        let repository = FakeGitRepository(repoInfoResult: repoInfo)
        let trustedFingerprint = "SHA256:clone-trusted-\(UUID().uuidString)"
        let trustError = GitLFSSSHHostKeyTrustError.unknownHostKey(
            host: "forgejo-retry.example.com",
            port: 2222,
            algorithm: "ssh-ed25519",
            fingerprint: trustedFingerprint,
            sawOtherKeyTypes: false
        )
        let clonedCommit = "3333333333333333333333333333333333333333"
        repository.cloneResults = [
            .failure(LocalGitError.sshHostKeyTrustRequired(trustError)),
            .success(LocalCloneResult(commitSHA: clonedCommit, branch: "main", fileCount: 7))
        ]
        let repo = RepoConfig(
            repoURL: "ssh://git@forgejo-retry.example.com:2222/team/notes.git",
            branch: "main",
            authorName: "Test User",
            authorEmail: "test@example.com",
            vaultFolderName: "SyncMD-SSHHostKey-Retry-\(UUID().uuidString)",
            authMethod: .sshKey,
            authUsername: "git"
        )
        let trustURL = FileManager.default.temporaryDirectory.appendingPathComponent("SyncMD-Trust-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }
        let trustStore = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        let appState = AppState(
            gitRepositoryFactory: { _ in repository },
            sshHostKeyTrustStore: trustStore,
            loadPersistedState: false
        )
        appState.repos = [repo]

        await appState.clone(repoID: repo.id)
        XCTAssertNotNil(appState.pendingSSHHostKeyTrustRequest)

        await appState.trustPendingSSHHostKeyAndRetry()

        XCTAssertNil(appState.pendingSSHHostKeyTrustRequest)
        XCTAssertEqual(repository.cloneRemoteURLs.count, 2)
        XCTAssertEqual(trustStore.trustedFingerprints(forHost: "forgejo-retry.example.com", port: 2222).algorithms["ssh-ed25519"], trustedFingerprint)
        XCTAssertEqual(appState.repos.first?.gitState.commitSHA, clonedCommit)
        XCTAssertFalse(appState.showError)
    }

    @MainActor
    func testCancelPendingSSHHostKeyTrustDoesNotPersistOrRetry() async throws {
        let repoInfo = LocalRepoInfo(branch: "main", commitSHA: "4444444444444444444444444444444444444444", changeCount: 0)
        let repository = FakeGitRepository(repoInfoResult: repoInfo)
        let trustError = GitLFSSSHHostKeyTrustError.unknownHostKey(
            host: "forgejo-cancel.example.com",
            port: 22,
            algorithm: "ssh-ed25519",
            fingerprint: "SHA256:cancelled",
            sawOtherKeyTypes: false
        )
        repository.cloneResults = [.failure(LocalGitError.sshHostKeyTrustRequired(trustError))]
        let repo = RepoConfig(
            repoURL: "git@forgejo-cancel.example.com:team/notes.git",
            branch: "main",
            authorName: "Test User",
            authorEmail: "test@example.com",
            vaultFolderName: "SyncMD-SSHHostKey-Cancel-\(UUID().uuidString)",
            authMethod: .sshKey,
            authUsername: "git"
        )
        let trustURL = FileManager.default.temporaryDirectory.appendingPathComponent("SyncMD-Trust-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }
        let trustStore = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        let appState = AppState(
            gitRepositoryFactory: { _ in repository },
            sshHostKeyTrustStore: trustStore,
            loadPersistedState: false
        )
        appState.repos = [repo]

        await appState.clone(repoID: repo.id)
        appState.cancelPendingSSHHostKeyTrust()

        XCTAssertNil(appState.pendingSSHHostKeyTrustRequest)
        XCTAssertEqual(repository.cloneRemoteURLs.count, 1)
        XCTAssertNil(trustStore.trustedFingerprints(forHost: "forgejo-cancel.example.com", port: 22).algorithms["ssh-ed25519"])
    }

    @MainActor
    func testAppStateCloneLFSHostKeyTrustPromptsHydrationOnlyRetry() async throws {
        // Clone succeeds, but Git LFS hydration (second SSH stack) presents an
        // unseen key type. The trust prompt must retry hydration alone — not
        // re-clone — and the repo must stay cloned.
        let repoInfo = LocalRepoInfo(branch: "main", commitSHA: "6666666666666666666666666666666666666666", changeCount: 0)
        let repository = FakeGitRepository(repoInfoResult: repoInfo)
        let trustError = GitLFSSSHHostKeyTrustError.unknownHostKey(
            host: "forgejo.example.com",
            port: 22,
            algorithm: "ssh-ed25519",
            fingerprint: "SHA256:lfs-second-stack",
            sawOtherKeyTypes: true
        )
        repository.cloneResults = [
            .success(LocalCloneResult(
                commitSHA: repoInfo.commitSHA,
                branch: "main",
                fileCount: 9,
                lfsTrustError: trustError
            ))
        ]
        let repo = RepoConfig(
            repoURL: "git@forgejo.example.com:team/notes.git",
            branch: "main",
            authorName: "Test User",
            authorEmail: "test@example.com",
            vaultFolderName: "SyncMD-SSHHostKey-LFS-\(UUID().uuidString)",
            authMethod: .sshKey,
            authUsername: "git"
        )
        let trustURL = FileManager.default.temporaryDirectory.appendingPathComponent("SyncMD-Trust-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }
        let trustStore = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        let appState = AppState(
            gitRepositoryFactory: { _ in repository },
            sshHostKeyTrustStore: trustStore,
            loadPersistedState: false
        )
        appState.repos = [repo]

        await appState.clone(repoID: repo.id)

        XCTAssertEqual(repository.cloneRemoteURLs.count, 1)
        XCTAssertEqual(appState.pendingSSHHostKeyTrustRequest?.operation, .hydrateLFS)
        XCTAssertEqual(appState.pendingSSHHostKeyTrustRequest?.trustError, trustError)
        XCTAssertTrue(appState.repos.first?.isCloned ?? false)
        XCTAssertNil(appState.lastError)

        await appState.trustPendingSSHHostKeyAndRetry()

        XCTAssertNil(appState.pendingSSHHostKeyTrustRequest)
        XCTAssertEqual(repository.cloneRemoteURLs.count, 1, "hydrate retry must not re-clone")
        XCTAssertEqual(repository.hydrateLFSCallCount, 1)
        XCTAssertEqual(trustStore.trustedFingerprints(forHost: "forgejo.example.com", port: 22).algorithms["ssh-ed25519"], "SHA256:lfs-second-stack")
    }

    @MainActor
    func testAppStatePullSSHHostKeyFailurePromptsAndRetryUsesTrustedFingerprint() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        let trustError = GitLFSSSHHostKeyTrustError.unknownHostKey(
            host: "forgejo-pull.example.com",
            port: 22,
            algorithm: "ssh-rsa",
            fingerprint: "SHA256:pull",
            sawOtherKeyTypes: false
        )
        fixture.repository.pullPlanError = LocalGitError.sshHostKeyTrustRequired(trustError)
        let trustURL = FileManager.default.temporaryDirectory.appendingPathComponent("SyncMD-Trust-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }
        let trustStore = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            sshHostKeyTrustStore: trustStore,
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        let firstResult = await appState.pull(repoID: fixture.repoConfig.id)
        XCTAssertFalse(firstResult)
        XCTAssertEqual(appState.pendingSSHHostKeyTrustRequest?.operation, .pull)
        XCTAssertEqual(appState.pendingSSHHostKeyTrustRequest?.trustError, trustError)
        XCTAssertEqual(appState.pullOutcomeByRepo[fixture.repoConfig.id]?.kind, .failed)

        fixture.repository.pullPlanError = nil
        fixture.repository.pullPlanResult = PullPlan(
            action: .upToDate,
            branch: "main",
            localCommitSHA: fixture.repoConfig.gitState.commitSHA,
            remoteCommitSHA: fixture.repoConfig.gitState.commitSHA,
            hasLocalChanges: false,
            aheadBy: 0,
            behindBy: 0
        )
        await appState.trustPendingSSHHostKeyAndRetry()

        XCTAssertNil(appState.pendingSSHHostKeyTrustRequest)
        XCTAssertEqual(fixture.repository.pullPlanCallCount, 2)
        XCTAssertEqual(trustStore.trustedFingerprints(forHost: "forgejo-pull.example.com", port: 22).algorithms["ssh-rsa"], "SHA256:pull")
        XCTAssertEqual(appState.pullOutcomeByRepo[fixture.repoConfig.id]?.kind, .upToDate)
        // The pull trust retry also runs a hydration pass so LFS pointer
        // stubs left behind by a hydration trust failure are recovered even
        // when the retried pull reports up-to-date.
        XCTAssertEqual(fixture.repository.hydrateLFSCallCount, 1)
    }

    @MainActor
    func testAppStatePushCurrentBranchSSHHostKeyFailurePromptsAndRetry() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        let trustError = GitLFSSSHHostKeyTrustError.changedHostKey(
            host: "forgejo-push.example.com",
            port: 2200,
            algorithm: "ssh-ed25519",
            expectedFingerprint: "SHA256:old-push",
            actualFingerprint: "SHA256:new-push"
        )
        fixture.repository.pushCurrentBranchResult = .failure(LocalGitError.sshHostKeyTrustRequired(trustError))
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main",
            commitSHA: fixture.repoConfig.gitState.commitSHA,
            changeCount: 0,
            syncState: .upToDate,
            statusEntries: []
        )
        let trustURL = FileManager.default.temporaryDirectory.appendingPathComponent("SyncMD-Trust-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }
        let trustStore = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            sshHostKeyTrustStore: trustStore,
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]
        appState.syncStateByRepo[fixture.repoConfig.id] = .ahead

        let firstResult = await appState.pushCurrentBranch(repoID: fixture.repoConfig.id)
        XCTAssertFalse(firstResult)
        XCTAssertEqual(appState.pendingSSHHostKeyTrustRequest?.operation, .pushCurrentBranch)
        XCTAssertEqual(appState.pendingSSHHostKeyTrustRequest?.trustError, trustError)

        fixture.repository.pushCurrentBranchResult = .success(())
        await appState.trustPendingSSHHostKeyAndRetry()

        XCTAssertNil(appState.pendingSSHHostKeyTrustRequest)
        XCTAssertEqual(fixture.repository.pushCurrentBranchCallCount, 2)
        XCTAssertEqual(trustStore.trustedFingerprints(forHost: "forgejo-push.example.com", port: 2200).algorithms["ssh-ed25519"], "SHA256:new-push")
        XCTAssertEqual(appState.syncStateByRepo[fixture.repoConfig.id], .upToDate)
    }

    @MainActor
    func testAppStateCommitAndPushSSHHostKeyFailurePreservesCommitMessageOnRetry() async throws {
        let fixture = try GitFixtureFactory.make(state: .dirty)
        defer { fixture.cleanup() }
        let trustError = GitLFSSSHHostKeyTrustError.unknownHostKey(
            host: "forgejo-commit-push.example.com",
            port: 22,
            algorithm: "ssh-ed25519",
            fingerprint: "SHA256:commit-push",
            sawOtherKeyTypes: false
        )
        fixture.repository.commitAndPushResult = .failure(LocalGitError.sshHostKeyTrustRequired(trustError))
        let trustURL = FileManager.default.temporaryDirectory.appendingPathComponent("SyncMD-Trust-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }
        let trustStore = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            sshHostKeyTrustStore: trustStore,
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        let firstResult = await appState.push(repoID: fixture.repoConfig.id, message: "sync notes")
        XCTAssertFalse(firstResult)
        XCTAssertEqual(appState.pendingSSHHostKeyTrustRequest?.operation, .pushCommit(message: "sync notes"))
        XCTAssertEqual(appState.pendingSSHHostKeyTrustRequest?.trustError, trustError)
        XCTAssertEqual(fixture.repository.commitAndPushMessages, ["sync notes"])

        fixture.repository.commitAndPushResult = .success(LocalPushResult(commitSHA: "5555555555555555555555555555555555555555"))
        await appState.trustPendingSSHHostKeyAndRetry()

        XCTAssertNil(appState.pendingSSHHostKeyTrustRequest)
        XCTAssertEqual(fixture.repository.commitAndPushMessages, ["sync notes", "sync notes"])
        XCTAssertEqual(trustStore.trustedFingerprints(forHost: "forgejo-commit-push.example.com", port: 22).algorithms["ssh-ed25519"], "SHA256:commit-push")
        XCTAssertEqual(appState.repos.first?.gitState.commitSHA, "5555555555555555555555555555555555555555")
    }

    @MainActor
    func testAppStateNonSSHHostKeyErrorsStillShowRegularErrorAlert() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.pullPlanError = LocalGitError.fetchFailed("network down")
        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        let result = await appState.pull(repoID: fixture.repoConfig.id)

        XCTAssertFalse(result)
        XCTAssertNil(appState.pendingSSHHostKeyTrustRequest)
        XCTAssertTrue(appState.showError)
        XCTAssertEqual(appState.lastError, LocalGitError.fetchFailed("network down").localizedDescription)
    }

    @MainActor
    func testAppStatePullWithRebaseUpdatesCommitAndOutcome() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        let rebasedCommit = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        fixture.repository.pullPlanResult = PullPlan(
            action: .diverged,
            branch: "main",
            localCommitSHA: fixture.repoConfig.gitState.commitSHA,
            remoteCommitSHA: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            hasLocalChanges: false,
            aheadBy: 1,
            behindBy: 1
        )
        fixture.repository.rebaseResult = .success(LocalPullResult(updated: true, newCommitSHA: rebasedCommit))

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.pullWithRebase(repoID: fixture.repoConfig.id)

        XCTAssertEqual(appState.repos.first?.gitState.commitSHA, rebasedCommit)
        XCTAssertEqual(appState.pullOutcomeByRepo[fixture.repoConfig.id]?.kind, .rebased)
    }

    @MainActor
    func testAppStatePullWithRebaseConflictStoresOutcome() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        fixture.repository.pullPlanResult = PullPlan(
            action: .diverged,
            branch: "main",
            localCommitSHA: fixture.repoConfig.gitState.commitSHA,
            remoteCommitSHA: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            hasLocalChanges: false,
            aheadBy: 1,
            behindBy: 1
        )
        fixture.repository.rebaseResult = .failure(LocalGitError.rebaseConflictsDetected)
        fixture.repository.conflictSessionResult = ConflictSession(kind: .rebase, unmergedPaths: ["README.md"])

        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            loadPersistedState: false
        )
        appState.repos = [fixture.repoConfig]

        await appState.pullWithRebase(repoID: fixture.repoConfig.id)

        XCTAssertEqual(appState.pullOutcomeByRepo[fixture.repoConfig.id]?.kind, .rebaseConflicts)
        XCTAssertEqual(appState.conflictSessionByRepo[fixture.repoConfig.id]?.kind, .rebase)
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

    func testLocalGitServiceCommitAndPushReportsMissingAuthorEmail() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-MissingAuthorEmail")
        defer { try? fm.removeItem(at: repoURL) }

        let service = LocalGitService(localURL: repoURL)
        let readme = repoURL.appendingPathComponent("README.md")
        try "# Identity Test\n".write(to: readme, atomically: true, encoding: .utf8)
        try await service.stage(path: "README.md")

        do {
            _ = try await service.commitAndPush(
                message: "Initial commit",
                authorName: "SyncMD Tests",
                authorEmail: " ",
                pat: ""
            )
            XCTFail("Expected missing author email to be reported before libgit2 signature creation")
        } catch LocalGitError.invalidAuthorIdentity(let message) {
            XCTAssertTrue(message.contains("Author Email"), message)
        } catch {
            XCTFail("Expected invalidAuthorIdentity, got \(error)")
        }
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

    func testGitLFSPointerParsesAndSerializesCanonicalPointers() throws {
        let oid = String(repeating: "a", count: 64)
        let text = """
        version https://git-lfs.github.com/spec/v1
        oid sha256:\(oid)
        size 12345

        """

        let pointer = try XCTUnwrap(GitLFSPointer(data: Data(text.utf8)))

        XCTAssertEqual(pointer.oid, oid)
        XCTAssertEqual(pointer.size, 12_345)
        XCTAssertEqual(pointer.serializedString, text)
    }

    func testGitLFSAttributesMatchCommonGitattributesPatterns() {
        let attributes = GitLFSAttributes(text: """
        *.pdf filter=lfs diff=lfs merge=lfs -text lockable
        Attachments/** filter=lfs diff=lfs merge=lfs -text
        notes/*.png filter=lfs
        Secrets/** lockable
        Legacy/** -lockable
        *.md text
        """)

        XCTAssertTrue(attributes.isLFSTracked(path: "Manual.pdf"))
        XCTAssertTrue(attributes.isLFSTracked(path: "Attachments/2026/report.pdf"))
        XCTAssertTrue(attributes.isLFSTracked(path: "notes/diagram.png"))
        XCTAssertFalse(attributes.isLFSTracked(path: "notes/screens/deep.png"))
        XCTAssertFalse(attributes.isLFSTracked(path: "README.md"))
        XCTAssertTrue(attributes.isLockable(path: "Manual.pdf"))
        XCTAssertTrue(attributes.isLockable(path: "Secrets/plan.md"))
        XCTAssertFalse(attributes.isLockable(path: "Legacy/archive.pdf"))
        XCTAssertFalse(attributes.isLockable(path: "README.md"))
    }

    func testGitLFSHydrateDownloadsPointerFilesThroughBatchAPI() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSHydrate-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL.appendingPathComponent(".git", isDirectory: true), withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        try """
        [remote "origin"]
            url = https://github.com/example/vault.git
        """.write(to: repoURL.appendingPathComponent(".git/config"), atomically: true, encoding: .utf8)

        let realData = Data("actual pdf bytes\n".utf8)
        let oid = GitLFSPointer.sha256Hex(for: realData)
        let pointer = GitLFSPointer(oid: oid, size: Int64(realData.count))
        let docsURL = repoURL.appendingPathComponent("Docs", isDirectory: true)
        try fm.createDirectory(at: docsURL, withIntermediateDirectories: true)
        let lfsFileURL = docsURL.appendingPathComponent("Manual.pdf")
        try Data(pointer.serializedString.utf8).write(to: lfsFileURL)

        let transport = MockGitLFSTransport { request, body in
            if request.url?.absoluteString == "https://github.com/example/vault.git/info/lfs/objects/batch" {
                let bodyString = String(data: try XCTUnwrap(body), encoding: .utf8) ?? ""
                XCTAssertEqual(request.httpMethod, "POST")
                XCTAssertTrue(bodyString.contains("\"operation\":\"download\""))
                XCTAssertTrue(bodyString.contains(oid))
                XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Basic eC1hY2Nlc3MtdG9rZW46Z2hwX3Rlc3Q=")
                let response = """
                {"transfer":"basic","objects":[{"oid":"\(oid)","size":\(realData.count),"actions":{"download":{"href":"https://lfs.example.test/objects/\(oid)","header":{"X-LFS-Test":"download"}}}}]}
                """
                return (Data(response.utf8), 200)
            }

            if request.url?.absoluteString == "https://lfs.example.test/objects/\(oid)" {
                XCTAssertEqual(request.httpMethod, "GET")
                XCTAssertEqual(request.value(forHTTPHeaderField: "X-LFS-Test"), "download")
                return (realData, 200)
            }

            XCTFail("Unexpected LFS request: \(request.url?.absoluteString ?? "<nil>")")
            return (Data(), 404)
        }

        let service = GitLFSService(
            localURL: repoURL,
            credentials: .gitHubPAT("ghp_test"),
            transport: transport
        )

        let result = try await service.hydrateWorktree()

        XCTAssertEqual(result.downloadedCount, 1)
        XCTAssertEqual(try Data(contentsOf: lfsFileURL), realData)
    }

    func testGitLFSHydratePreservesPointerEditedDuringSuspendedDownloadAndReportsAttention() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSPointerRace-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL.appendingPathComponent(".git", isDirectory: true), withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        try """
        [remote "origin"]
            url = https://github.com/example/vault.git
        """.write(to: repoURL.appendingPathComponent(".git/config"), atomically: true, encoding: .utf8)

        let objectData = Data("downloaded object bytes\n".utf8)
        let pointer = GitLFSPointer(oid: GitLFSPointer.sha256Hex(for: objectData), size: Int64(objectData.count))
        let fileURL = repoURL.appendingPathComponent("Manual.pdf")
        let discoveredBytes = Data(pointer.serializedString.utf8)
        try discoveredBytes.write(to: fileURL)

        let downloadStarted = AsyncGate()
        let allowDownload = AsyncGate()
        let transport = MockGitLFSTransport { request, _ in
            if request.url?.absoluteString.hasSuffix("/objects/batch") == true {
                return (Data("""
                {"objects":[{"oid":"\(pointer.oid)","size":\(pointer.size),"actions":{"download":{"href":"https://lfs.example.test/objects/\(pointer.oid)"}}}]}
                """.utf8), 200)
            }
            if request.url?.absoluteString == "https://lfs.example.test/objects/\(pointer.oid)" {
                await downloadStarted.open()
                await allowDownload.wait()
                return (objectData, 200)
            }
            return (Data(), 404)
        }
        let service = GitLFSService(localURL: repoURL, credentials: .none, transport: transport)

        let hydration = Task { try await service.hydrateWorktree() }
        await downloadStarted.wait()
        let editedBytes = Data("user edit while LFS download is suspended\n".utf8)
        try editedBytes.write(to: fileURL, options: .atomic)
        await allowDownload.open()

        do {
            _ = try await hydration.value
            XCTFail("Expected concurrent local edit to block hydration")
        } catch LocalGitError.lfsHydrationBlockedByLocalChanges(let path) {
            XCTAssertEqual(path, "Manual.pdf")
        }

        XCTAssertEqual(try Data(contentsOf: fileURL), editedBytes)
        XCTAssertFalse(fm.fileExists(atPath: repoURL.appendingPathComponent(".git/syncmd/lfs-clean-cache.json").path))
        XCTAssertTrue((try fm.contentsOfDirectory(atPath: repoURL.path)).allSatisfy { !$0.hasPrefix(".syncmd-lfs-") })

        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.executePullOnlyResult = .failure(LocalGitError.lfsHydrationBlockedByLocalChanges("Manual.pdf"))
        let automationResult = await RepositoryPullRunner().run(
            repository: fixture.repository,
            credentials: "",
            expectedBranch: "main"
        )
        XCTAssertEqual(automationResult, .blockedByLocalChanges(branch: "main"))
        XCTAssertFalse(automationResult.completedWithoutAttention)
    }

    func testGitLFSHydrateSharedOIDDownloadsOnceAndHydratesEveryPointer() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSSharedOID-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL.appendingPathComponent(".git", isDirectory: true), withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }
        try """
        [remote "origin"]
            url = https://github.com/example/vault.git
        """.write(to: repoURL.appendingPathComponent(".git/config"), atomically: true, encoding: .utf8)

        let objectData = Data("one object shared by two pointers\n".utf8)
        let pointer = GitLFSPointer(oid: GitLFSPointer.sha256Hex(for: objectData), size: Int64(objectData.count))
        let firstURL = repoURL.appendingPathComponent("First.pdf")
        let secondURL = repoURL.appendingPathComponent("Second.pdf")
        try Data(pointer.serializedString.utf8).write(to: firstURL)
        try Data(pointer.serializedString.utf8).write(to: secondURL)
        var downloadRequests = 0
        let transport = MockGitLFSTransport { request, _ in
            if request.url?.absoluteString.hasSuffix("/objects/batch") == true {
                return (Data("""
                {"objects":[{"oid":"\(pointer.oid)","size":\(pointer.size),"actions":{"download":{"href":"https://lfs.example.test/objects/\(pointer.oid)"}}}]}
                """.utf8), 200)
            }
            if request.url?.absoluteString == "https://lfs.example.test/objects/\(pointer.oid)" {
                downloadRequests += 1
                return (objectData, 200)
            }
            return (Data(), 404)
        }

        let result = try await GitLFSService(localURL: repoURL, credentials: .none, transport: transport).hydrateWorktree()

        XCTAssertEqual(result.pointerCount, 2)
        XCTAssertEqual(result.downloadedCount, 1)
        XCTAssertEqual(result.checkedOutCount, 2)
        XCTAssertEqual(downloadRequests, 1)
        XCTAssertEqual(try Data(contentsOf: firstURL), objectData)
        XCTAssertEqual(try Data(contentsOf: secondURL), objectData)
    }

    func testGitLFSHydrateCanBeLimitedToChangedPaths() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSHydrateScoped-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL.appendingPathComponent(".git", isDirectory: true), withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        try """
        [remote "origin"]
            url = https://github.com/example/vault.git
        """.write(to: repoURL.appendingPathComponent(".git/config"), atomically: true, encoding: .utf8)

        let changedData = Data("changed lfs data\n".utf8)
        let unchangedData = Data("unchanged lfs data\n".utf8)
        let changedPointer = GitLFSPointer(oid: GitLFSPointer.sha256Hex(for: changedData), size: Int64(changedData.count))
        let unchangedPointer = GitLFSPointer(oid: GitLFSPointer.sha256Hex(for: unchangedData), size: Int64(unchangedData.count))
        try changedPointer.serializedString.write(to: repoURL.appendingPathComponent("Changed.pdf"), atomically: true, encoding: .utf8)
        try unchangedPointer.serializedString.write(to: repoURL.appendingPathComponent("Unchanged.pdf"), atomically: true, encoding: .utf8)

        let transport = MockGitLFSTransport { request, body in
            if request.url?.absoluteString == "https://github.com/example/vault.git/info/lfs/objects/batch" {
                let bodyString = String(data: try XCTUnwrap(body), encoding: .utf8) ?? ""
                XCTAssertTrue(bodyString.contains(changedPointer.oid))
                XCTAssertFalse(bodyString.contains(unchangedPointer.oid))
                return (Data("""
                {"transfer":"basic","objects":[{"oid":"\(changedPointer.oid)","size":\(changedData.count),"actions":{"download":{"href":"https://lfs.example.test/objects/\(changedPointer.oid)"}}}]}
                """.utf8), 200)
            }

            if request.url?.absoluteString == "https://lfs.example.test/objects/\(changedPointer.oid)" {
                return (changedData, 200)
            }

            XCTFail("Unexpected LFS request: \(request.url?.absoluteString ?? "<nil>")")
            return (Data(), 404)
        }

        let result = try await GitLFSService(
            localURL: repoURL,
            credentials: .gitHubPAT("ghp_test"),
            transport: transport
        ).hydrateWorktree(candidatePaths: ["Changed.pdf"])

        XCTAssertEqual(result.checkedOutCount, 1)
        XCTAssertEqual(try Data(contentsOf: repoURL.appendingPathComponent("Changed.pdf")), changedData)
        XCTAssertNotNil(GitLFSPointer(data: try Data(contentsOf: repoURL.appendingPathComponent("Unchanged.pdf"))))
    }

    func testGitLFSBatchErrorsIncludeServerMessage() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSBatchError-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL.appendingPathComponent(".git", isDirectory: true), withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        try "ref: refs/heads/main\n".write(to: repoURL.appendingPathComponent(".git/HEAD"), atomically: true, encoding: .utf8)
        try """
        [remote "origin"]
            url = https://github.com/example/vault.git
        """.write(to: repoURL.appendingPathComponent(".git/config"), atomically: true, encoding: .utf8)

        let pointer = GitLFSPointer(oid: String(repeating: "a", count: 64), size: 12_706_707)
        try pointer.serializedString.write(to: repoURL.appendingPathComponent("video.mp4"), atomically: true, encoding: .utf8)

        let transport = MockGitLFSTransport { request, body in
            XCTAssertEqual(request.url?.absoluteString, "https://github.com/example/vault.git/info/lfs/objects/batch")
            let bodyString = String(data: try XCTUnwrap(body), encoding: .utf8) ?? ""
            XCTAssertTrue(bodyString.contains("refs"))
            XCTAssertTrue(bodyString.contains("heads"))
            XCTAssertTrue(bodyString.contains("main"))
            return (Data("""
            {"message":"Repository is over its Git LFS data quota.","request_id":"abc123"}
            """.utf8), 422)
        }

        do {
            _ = try await GitLFSService(
                localURL: repoURL,
                credentials: .gitHubPAT("ghp_test"),
                transport: transport
            ).hydrateWorktree()
            XCTFail("Expected Git LFS batch error")
        } catch LocalGitError.lfsFailed(let message) {
            XCTAssertTrue(message.contains("HTTP 422"))
            XCTAssertTrue(message.contains("data quota"))
            XCTAssertTrue(message.contains("abc123"))
        }
    }

    func testGitLFSBatchUsesGitSuffixForGitHubHTTPSRemoteWithoutGitSuffix() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSGitHubSuffix-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL.appendingPathComponent(".git", isDirectory: true), withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        try """
        [remote "origin"]
            url = https://github.com/example/vault
        """.write(to: repoURL.appendingPathComponent(".git/config"), atomically: true, encoding: .utf8)

        let realData = Data("large binary fixture\n".utf8)
        let oid = GitLFSPointer.sha256Hex(for: realData)
        let pointer = GitLFSPointer(oid: oid, size: Int64(realData.count))
        try pointer.serializedString.write(to: repoURL.appendingPathComponent("video.mp4"), atomically: true, encoding: .utf8)

        let transport = MockGitLFSTransport { request, _ in
            if request.url?.absoluteString == "https://github.com/example/vault.git/info/lfs/objects/batch" {
                let response = """
                {"transfer":"basic","objects":[{"oid":"\(oid)","size":\(realData.count),"actions":{"download":{"href":"https://lfs.example.test/objects/\(oid)"}}}]}
                """
                return (Data(response.utf8), 200)
            }

            if request.url?.absoluteString == "https://lfs.example.test/objects/\(oid)" {
                return (realData, 200)
            }

            XCTFail("Unexpected LFS request: \(request.url?.absoluteString ?? "<nil>")")
            return (Data(), 404)
        }

        let result = try await GitLFSService(
            localURL: repoURL,
            credentials: .gitHubPAT("ghp_test"),
            transport: transport
        ).hydrateWorktree()

        XCTAssertEqual(result.checkedOutCount, 1)
        XCTAssertEqual(try Data(contentsOf: repoURL.appendingPathComponent("video.mp4")), realData)
    }

    func testGitLFSBatchHTMLErrorsAreSummarized() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSHTMLBatchError-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL.appendingPathComponent(".git", isDirectory: true), withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        try """
        [remote "origin"]
            url = https://github.com/example/vault.git
        """.write(to: repoURL.appendingPathComponent(".git/config"), atomically: true, encoding: .utf8)

        let pointer = GitLFSPointer(oid: String(repeating: "c", count: 64), size: 42)
        try pointer.serializedString.write(to: repoURL.appendingPathComponent("asset.bin"), atomically: true, encoding: .utf8)

        let transport = MockGitLFSTransport { _, _ in
            let html = """
            <!DOCTYPE html>
            <html>
              <head><title>Oh no &middot; GitHub</title></head>
              <body>large diagnostic page that should not be shown verbatim</body>
            </html>
            """
            return (Data(html.utf8), 422)
        }

        do {
            _ = try await GitLFSService(
                localURL: repoURL,
                credentials: .gitHubPAT("ghp_test"),
                transport: transport
            ).hydrateWorktree()
            XCTFail("Expected Git LFS batch error")
        } catch LocalGitError.lfsFailed(let message) {
            XCTAssertTrue(message.contains("HTTP 422"))
            XCTAssertTrue(message.contains("Server returned an HTML error page"))
            XCTAssertTrue(message.contains("Oh no · GitHub"))
            XCTAssertFalse(message.contains("<!DOCTYPE html>"))
            XCTAssertFalse(message.contains("<html>"))
            XCTAssertFalse(message.contains("large diagnostic page"))
        }
    }

    func testLocalGitServiceCloneSucceedsWithWarningWhenLFSHydrationFails() async throws {
        let fm = FileManager.default
        let sourceURL = try makeTemporaryGitRepository(prefix: "SyncMD-LFSCloneSource")
        let cloneParentURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSCloneParent-\(UUID().uuidString)", isDirectory: true)
        let cloneURL = cloneParentURL.appendingPathComponent("clone", isDirectory: true)
        defer {
            try? fm.removeItem(at: sourceURL)
            try? fm.removeItem(at: cloneParentURL)
        }

        let pointer = GitLFSPointer(oid: String(repeating: "b", count: 64), size: 42)
        try pointer.serializedString.write(to: sourceURL.appendingPathComponent("asset.bin"), atomically: true, encoding: .utf8)
        let sourceService = LocalGitService(localURL: sourceURL)
        try await sourceService.stage(path: "asset.bin")
        _ = try await sourceService.commitLocal(
            message: "Add pointer fixture",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com"
        )

        try fm.createDirectory(at: cloneParentURL, withIntermediateDirectories: true)
        let cloneService = LocalGitService(localURL: cloneURL)
        let result = try await cloneService.clone(remoteURL: sourceURL.path, pat: "")

        XCTAssertEqual(result.fileCount, 1)
        XCTAssertTrue(result.lfsWarning?.contains("Git LFS") == true)
        XCTAssertTrue(cloneService.hasGitDirectory)
        XCTAssertNotNil(GitLFSPointer(data: try Data(contentsOf: cloneURL.appendingPathComponent("asset.bin"))))
    }

    func testGitLFSSSHAuthRequestBuildsAuthenticateCommandsForPrivateRemotes() throws {
        let sshURL = try XCTUnwrap(GitRemoteURL.parse("ssh://git@example.com:2222/owner/vault.git"))
        let download = try GitLFSSSHAuthRequest(
            remote: sshURL,
            credentials: .sshKey(username: "", privateKey: "test-key"),
            operation: .download
        )

        XCTAssertEqual(download.username, "git")
        XCTAssertEqual(download.host, "example.com")
        XCTAssertEqual(download.port, 2222)
        XCTAssertEqual(download.repositoryPath, "owner/vault.git")
        XCTAssertEqual(download.command, "git-lfs-authenticate 'owner/vault.git' download")

        let scpURL = try XCTUnwrap(GitRemoteURL.parse("git@github.com:owner/repo.git"))
        let upload = try GitLFSSSHAuthRequest(
            remote: scpURL,
            credentials: .sshKey(username: "deploy", privateKey: "test-key"),
            operation: .upload
        )

        XCTAssertEqual(upload.username, "deploy")
        XCTAssertEqual(upload.host, "github.com")
        XCTAssertEqual(upload.port, 22)
        XCTAssertEqual(upload.repositoryPath, "owner/repo.git")
        XCTAssertEqual(upload.command, "git-lfs-authenticate 'owner/repo.git' upload")
    }

    func testGitLFSHydrateUsesSSHLFSAuthenticateForPrivateSSHRemote() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSSSHHydrate-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL.appendingPathComponent(".git", isDirectory: true), withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        try """
        [remote "origin"]
            url = git@github.com:example/vault.git
        """.write(to: repoURL.appendingPathComponent(".git/config"), atomically: true, encoding: .utf8)

        let realData = Data("private ssh lfs bytes\n".utf8)
        let oid = GitLFSPointer.sha256Hex(for: realData)
        let pointer = GitLFSPointer(oid: oid, size: Int64(realData.count))
        let fileURL = repoURL.appendingPathComponent("Manual.pdf")
        try Data(pointer.serializedString.utf8).write(to: fileURL)

        let ssh = MockGitLFSSSHAuthenticator { request, credentials in
            XCTAssertEqual(request.host, "github.com")
            XCTAssertEqual(request.username, "git")
            XCTAssertEqual(request.command, "git-lfs-authenticate 'example/vault.git' download")
            XCTAssertEqual(credentials.method, .sshKey)
            return GitLFSAccess(
                href: URL(string: "https://lfs.github.test/example/vault.git/info/lfs")!,
                headers: ["Authorization": "RemoteAuth download"],
                expiresAt: Date(timeIntervalSince1970: 4_000)
            )
        }

        let transport = MockGitLFSTransport { request, body in
            if request.url?.absoluteString == "https://lfs.github.test/example/vault.git/info/lfs/objects/batch" {
                let bodyString = String(data: try XCTUnwrap(body), encoding: .utf8) ?? ""
                XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "RemoteAuth download")
                XCTAssertTrue(bodyString.contains("\"operation\":\"download\""))
                return (Data("""
                {"objects":[{"oid":"\(oid)","size":\(realData.count),"actions":{"download":{"href":"https://objects.example.test/\(oid)"}}}]}
                """.utf8), 200)
            }

            if request.url?.absoluteString == "https://objects.example.test/\(oid)" {
                return (realData, 200)
            }

            XCTFail("Unexpected LFS request: \(request.url?.absoluteString ?? "<nil>")")
            return (Data(), 404)
        }

        let service = GitLFSService(
            localURL: repoURL,
            credentials: .sshKey(username: "git", privateKey: "test-private-key"),
            transport: transport,
            sshAuthenticator: ssh,
            now: { Date(timeIntervalSince1970: 1_000) }
        )

        let result = try await service.hydrateWorktree()

        XCTAssertEqual(result.downloadedCount, 1)
        XCTAssertEqual(ssh.requests.map(\.operation), [.download])
        XCTAssertEqual(try Data(contentsOf: fileURL), realData)
    }

    func testGitLFSUploadUsesSeparateSSHLFSAuthenticateOperationAndHeaders() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSSSHUpload-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL.appendingPathComponent(".git", isDirectory: true), withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        try """
        [remote "origin"]
            url = ssh://git@example.com:2222/owner/vault.git
        """.write(to: repoURL.appendingPathComponent(".git/config"), atomically: true, encoding: .utf8)

        let data = Data("upload me\n".utf8)
        let pointer = GitLFSPointer(oid: GitLFSPointer.sha256Hex(for: data), size: Int64(data.count))
        let objectURL = repoURL
            .appendingPathComponent(".git/lfs/objects", isDirectory: true)
            .appendingPathComponent(String(pointer.oid.prefix(2)), isDirectory: true)
            .appendingPathComponent(String(pointer.oid.dropFirst(2).prefix(2)), isDirectory: true)
            .appendingPathComponent(pointer.oid)
        try fm.createDirectory(at: objectURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: objectURL)

        let ssh = MockGitLFSSSHAuthenticator { request, _ in
            XCTAssertEqual(request.host, "example.com")
            XCTAssertEqual(request.port, 2222)
            XCTAssertEqual(request.command, "git-lfs-authenticate 'owner/vault.git' upload")
            return GitLFSAccess(
                href: URL(string: "https://lfs.example.test/owner/vault.git/info/lfs")!,
                headers: ["Authorization": "RemoteAuth upload"]
            )
        }

        let transport = MockGitLFSTransport { request, body in
            XCTAssertEqual(request.url?.absoluteString, "https://lfs.example.test/owner/vault.git/info/lfs/objects/batch")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "RemoteAuth upload")
            let bodyString = String(data: try XCTUnwrap(body), encoding: .utf8) ?? ""
            XCTAssertTrue(bodyString.contains("\"operation\":\"upload\""))
            XCTAssertTrue(bodyString.contains(pointer.oid))
            return (Data("""
            {"objects":[{"oid":"\(pointer.oid)","size":\(pointer.size)}]}
            """.utf8), 200)
        }

        let uploaded = try await GitLFSService(
            localURL: repoURL,
            credentials: .sshKey(username: "git", privateKey: "test-private-key"),
            transport: transport,
            sshAuthenticator: ssh
        ).uploadObjects([pointer])

        XCTAssertEqual(uploaded, 0)
        XCTAssertEqual(ssh.requests.map(\.operation), [.upload])
    }

    func testGitLFSBatchRefreshesSSHAccessAfterAuthFailure() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSSSHRefresh-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL.appendingPathComponent(".git", isDirectory: true), withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        try """
        [remote "origin"]
            url = git@example.com:owner/vault.git
        """.write(to: repoURL.appendingPathComponent(".git/config"), atomically: true, encoding: .utf8)

        let realData = Data("refresh token bytes\n".utf8)
        let oid = GitLFSPointer.sha256Hex(for: realData)
        let pointer = GitLFSPointer(oid: oid, size: Int64(realData.count))
        let fileURL = repoURL.appendingPathComponent("asset.bin")
        try Data(pointer.serializedString.utf8).write(to: fileURL)

        var batchAttempts = 0
        var authHeaders: [String] = []
        let ssh = MockGitLFSSSHAuthenticator { _, _ in
            let token = "Bearer token-\(authHeaders.count + 1)"
            return GitLFSAccess(
                href: URL(string: "https://lfs.example.test/owner/vault.git/info/lfs")!,
                headers: ["Authorization": token],
                expiresAt: Date(timeIntervalSince1970: 4_000)
            )
        }

        let transport = MockGitLFSTransport { request, _ in
            if request.url?.absoluteString == "https://lfs.example.test/owner/vault.git/info/lfs/objects/batch" {
                batchAttempts += 1
                authHeaders.append(request.value(forHTTPHeaderField: "Authorization") ?? "")
                if batchAttempts == 1 {
                    return (Data(), 401)
                }
                return (Data("""
                {"objects":[{"oid":"\(oid)","size":\(realData.count),"actions":{"download":{"href":"https://objects.example.test/\(oid)"}}}]}
                """.utf8), 200)
            }

            if request.url?.absoluteString == "https://objects.example.test/\(oid)" {
                return (realData, 200)
            }

            XCTFail("Unexpected LFS request: \(request.url?.absoluteString ?? "<nil>")")
            return (Data(), 404)
        }

        let result = try await GitLFSService(
            localURL: repoURL,
            credentials: .sshKey(username: "git", privateKey: "test-private-key"),
            transport: transport,
            sshAuthenticator: ssh,
            now: { Date(timeIntervalSince1970: 1_000) }
        ).hydrateWorktree()

        XCTAssertEqual(result.downloadedCount, 1)
        XCTAssertEqual(ssh.requests.count, 2)
        XCTAssertEqual(authHeaders, ["Bearer token-1", "Bearer token-2"])
    }

    func testLocalGitServiceStagesLFSTrackedFilesAsPointersAndKeepsHydratedWorktreeClean() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSStage-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        try "*.pdf filter=lfs diff=lfs merge=lfs -text\n".write(
            to: repoURL.appendingPathComponent(".gitattributes"),
            atomically: true,
            encoding: .utf8
        )
        let docsURL = repoURL.appendingPathComponent("Docs", isDirectory: true)
        try fm.createDirectory(at: docsURL, withIntermediateDirectories: true)
        let pdfURL = docsURL.appendingPathComponent("Manual.pdf")
        let pdfData = Data("%PDF-1.7\nactual binary-ish content\n".utf8)
        try pdfData.write(to: pdfURL)

        let service = LocalGitService(localURL: repoURL)
        try await service.stageAll()
        _ = try await service.commitLocal(
            message: "Add LFS PDF",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com"
        )

        let committedBlob = try headBlobString(repoURL: repoURL, path: "Docs/Manual.pdf")
        let committedPointer = try XCTUnwrap(GitLFSPointer(data: Data(committedBlob.utf8)))

        XCTAssertEqual(committedPointer.oid, GitLFSPointer.sha256Hex(for: pdfData))
        XCTAssertEqual(committedPointer.size, Int64(pdfData.count))
        XCTAssertEqual(try Data(contentsOf: pdfURL), pdfData)

        let repoInfo = try await service.repoInfo()
        XCTAssertEqual(repoInfo.changeCount, 0)
    }

    func testLocalGitServiceHashesHydratedLFSBytesEvenWhenSizeAndMtimeMatchCache() async throws {
        let fm = FileManager.default
        let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSMirrorClean-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, repoURL.path, 0), 0)
        if let repo { git_repository_free(repo) }

        try "*.pdf filter=lfs diff=lfs merge=lfs -text\n".write(
            to: repoURL.appendingPathComponent(".gitattributes"),
            atomically: true,
            encoding: .utf8
        )

        let data = Data("previously hydrated pdf bytes\n".utf8)
        let pointer = GitLFSPointer(oid: GitLFSPointer.sha256Hex(for: data), size: Int64(data.count))
        try pointer.serializedString.write(to: repoURL.appendingPathComponent("Manual.pdf"), atomically: true, encoding: .utf8)

        let service = LocalGitService(localURL: repoURL)
        try await service.stageAll()
        _ = try await service.commitLocal(
            message: "Add LFS pointer",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com"
        )

        let objectURL = lfsObjectURL(repoURL: repoURL, pointer: pointer)
        try fm.createDirectory(at: objectURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: objectURL)
        try data.write(to: repoURL.appendingPathComponent("Manual.pdf"))

        let mirrorDate = Date(timeIntervalSince1970: 1_700_000_000)
        try fm.setAttributes([.modificationDate: mirrorDate], ofItemAtPath: objectURL.path)
        try fm.setAttributes([.modificationDate: mirrorDate], ofItemAtPath: repoURL.appendingPathComponent("Manual.pdf").path)
        let initiallyClean = try await service.repoInfo()
        XCTAssertEqual(initiallyClean.changeCount, 0)

        // Preserve both size and the previously-clean mtime while changing the
        // bytes. Neither the persistent metadata cache nor cached-object mirror
        // may independently prove clean.
        let wrongData = Data(repeating: 0x58, count: data.count)
        try wrongData.write(to: repoURL.appendingPathComponent("Manual.pdf"))
        try fm.setAttributes([.modificationDate: mirrorDate], ofItemAtPath: repoURL.appendingPathComponent("Manual.pdf").path)
        let dirty = try await service.repoInfo()
        XCTAssertEqual(dirty.changeCount, 1)

        try data.write(to: repoURL.appendingPathComponent("Manual.pdf"))
        try fm.setAttributes([.modificationDate: mirrorDate], ofItemAtPath: repoURL.appendingPathComponent("Manual.pdf").path)
        let cleanAgain = try await service.repoInfo()
        XCTAssertEqual(cleanAgain.changeCount, 0)
    }

    func testLocalGitServiceReportsAutoLFSCandidatesWithoutModifyingGitattributes() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-LFSCandidate")
        defer { try? fm.removeItem(at: repoURL) }

        let mediaURL = repoURL.appendingPathComponent("Media", isDirectory: true)
        try fm.createDirectory(at: mediaURL, withIntermediateDirectories: true)
        let movieURL = mediaURL.appendingPathComponent("clip.mov")
        try Data(repeating: 0xAA, count: 4096).write(to: movieURL)

        let service = LocalGitService(localURL: repoURL)
        let candidates = try await service.lfsAutoTrackingCandidates(paths: ["Media/clip.mov"])

        XCTAssertEqual(candidates.map(\.path), ["Media/clip.mov"])
        XCTAssertEqual(candidates.first?.patterns, ["*.mov", "*.MOV"])
        XCTAssertFalse(fm.fileExists(atPath: repoURL.appendingPathComponent(".gitattributes").path))

        try await service.stage(path: "Media/clip.mov")
        _ = try await service.commitLocal(
            message: "Stage without LFS confirmation",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com"
        )

        XCTAssertEqual(try Data(contentsOf: movieURL), Data(repeating: 0xAA, count: 4096))
        XCTAssertNil(GitLFSPointer(data: Data(try headBlobString(repoURL: repoURL, path: "Media/clip.mov").utf8)))
        XCTAssertFalse(fm.fileExists(atPath: repoURL.appendingPathComponent(".gitattributes").path))
    }

    func testLocalGitServiceAutoTracksPDFAsLFSWithoutExistingGitattributes() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-AutoLFSPDF")
        defer { try? fm.removeItem(at: repoURL) }

        let docsURL = repoURL.appendingPathComponent("Docs", isDirectory: true)
        try fm.createDirectory(at: docsURL, withIntermediateDirectories: true)
        let pdfURL = docsURL.appendingPathComponent("Manual.pdf")
        var pdfData = Data("%PDF-1.7\n".utf8)
        pdfData.append(Data(repeating: 0xA5, count: 1024))
        try pdfData.write(to: pdfURL)

        let service = LocalGitService(localURL: repoURL)
        try await service.stageAll(lfsAutoTrack: true)
        _ = try await service.commitLocal(
            message: "Auto-track PDF",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com"
        )

        let committedBlob = try headBlobString(repoURL: repoURL, path: "Docs/Manual.pdf")
        let pointer = try XCTUnwrap(GitLFSPointer(data: Data(committedBlob.utf8)))
        XCTAssertEqual(pointer.oid, GitLFSPointer.sha256Hex(for: pdfData))
        XCTAssertEqual(pointer.size, Int64(pdfData.count))
        XCTAssertEqual(try Data(contentsOf: pdfURL), pdfData)
        XCTAssertTrue(fm.fileExists(atPath: lfsObjectURL(repoURL: repoURL, pointer: pointer).path))

        let attributes = try headBlobString(repoURL: repoURL, path: ".gitattributes")
        XCTAssertTrue(attributes.contains("*.pdf filter=lfs diff=lfs merge=lfs -text"))
    }

    func testLocalGitServiceAutoTracksUppercaseMOVAndAppendsGitattributesRule() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-AutoLFSMOV")
        defer { try? fm.removeItem(at: repoURL) }

        try "*.mp4 filter=lfs diff=lfs merge=lfs -text\n".write(
            to: repoURL.appendingPathComponent(".gitattributes"),
            atomically: true,
            encoding: .utf8
        )

        let videosURL = repoURL.appendingPathComponent("raw/assets/videos", isDirectory: true)
        try fm.createDirectory(at: videosURL, withIntermediateDirectories: true)
        let movURL = videosURL.appendingPathComponent("IMG_3617.MOV")
        var movData = Data("ftypqt  ".utf8)
        movData.append(Data(repeating: 0xCC, count: 4096))
        try movData.write(to: movURL)

        let service = LocalGitService(localURL: repoURL)
        try await service.stageAll(lfsAutoTrack: true)
        _ = try await service.commitLocal(
            message: "Auto-track MOV",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com"
        )

        let committedBlob = try headBlobString(repoURL: repoURL, path: "raw/assets/videos/IMG_3617.MOV")
        let pointer = try XCTUnwrap(GitLFSPointer(data: Data(committedBlob.utf8)))
        XCTAssertEqual(pointer.oid, GitLFSPointer.sha256Hex(for: movData))
        XCTAssertEqual(pointer.size, Int64(movData.count))
        XCTAssertEqual(try Data(contentsOf: movURL), movData)

        let attributes = try headBlobString(repoURL: repoURL, path: ".gitattributes")
        XCTAssertTrue(attributes.contains("*.mp4 filter=lfs diff=lfs merge=lfs -text"))
        XCTAssertTrue(attributes.contains("*.MOV filter=lfs diff=lfs merge=lfs -text"))
    }

    func testLocalGitServiceAutoTracksUnknownLargeBinaryWithExactPathRule() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-AutoLFSUnknownLarge")
        defer { try? fm.removeItem(at: repoURL) }

        let blobsURL = repoURL.appendingPathComponent("raw/assets/blobs", isDirectory: true)
        try fm.createDirectory(at: blobsURL, withIntermediateDirectories: true)
        let blobURL = blobsURL.appendingPathComponent("session.capture")
        let largeData = Data(repeating: 0, count: Int(GitLFSAutoTrackingPolicy.default.largeFileThresholdBytes) + 1)
        try largeData.write(to: blobURL)

        let service = LocalGitService(localURL: repoURL)
        try await service.stageAll(lfsAutoTrack: true)
        _ = try await service.commitLocal(
            message: "Auto-track large binary",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com"
        )

        let committedBlob = try headBlobString(repoURL: repoURL, path: "raw/assets/blobs/session.capture")
        let pointer = try XCTUnwrap(GitLFSPointer(data: Data(committedBlob.utf8)))
        XCTAssertEqual(pointer.oid, GitLFSPointer.sha256Hex(for: largeData))
        XCTAssertEqual(pointer.size, Int64(largeData.count))
        XCTAssertEqual(try Data(contentsOf: blobURL), largeData)
        XCTAssertTrue(fm.fileExists(atPath: lfsObjectURL(repoURL: repoURL, pointer: pointer).path))

        let attributes = try headBlobString(repoURL: repoURL, path: ".gitattributes")
        XCTAssertTrue(attributes.contains("/raw/assets/blobs/session.capture filter=lfs diff=lfs merge=lfs -text"))
    }

    func testLocalGitServiceDoesNotAutoTrackSmallMarkdownFile() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-NoAutoLFSText")
        defer { try? fm.removeItem(at: repoURL) }

        let note = "# Notes\nThis should stay as normal Git text.\n"
        try note.write(to: repoURL.appendingPathComponent("README.md"), atomically: true, encoding: .utf8)

        let service = LocalGitService(localURL: repoURL)
        try await service.stageAll()
        _ = try await service.commitLocal(
            message: "Add markdown",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com"
        )

        let committedBlob = try headBlobString(repoURL: repoURL, path: "README.md")
        XCTAssertEqual(committedBlob, note)
        XCTAssertNil(GitLFSPointer(data: Data(committedBlob.utf8)))
        XCTAssertFalse(fm.fileExists(atPath: repoURL.appendingPathComponent(".gitattributes").path))
    }

    func testLocalGitServiceStagesAndCommitsGitattributesForAutoLFSRule() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-AutoLFSAttributes")
        defer { try? fm.removeItem(at: repoURL) }

        let designURL = repoURL.appendingPathComponent("Design", isDirectory: true)
        try fm.createDirectory(at: designURL, withIntermediateDirectories: true)
        let figURL = designURL.appendingPathComponent("mockup.fig")
        try Data(repeating: 0xFA, count: 256).write(to: figURL)

        let service = LocalGitService(localURL: repoURL)
        try await service.stage(path: "Design/mockup.fig", oldPath: nil, lfsAutoTrack: true)
        _ = try await service.commitLocal(
            message: "Add design asset",
            authorName: "SyncMD Tests",
            authorEmail: "tests@example.com"
        )

        let attributes = try headBlobString(repoURL: repoURL, path: ".gitattributes")
        XCTAssertTrue(attributes.contains("*.fig filter=lfs diff=lfs merge=lfs -text"))
        XCTAssertNotNil(GitLFSPointer(data: Data(try headBlobString(repoURL: repoURL, path: "Design/mockup.fig").utf8)))
    }

    func testLocalGitServicePrePushValidationBlocksLargeStagedNonLFSBlob() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-LFSPrePushBlock")
        defer { try? fm.removeItem(at: repoURL) }

        let bypassURL = repoURL.appendingPathComponent("Bypass", isDirectory: true)
        try fm.createDirectory(at: bypassURL, withIntermediateDirectories: true)
        let largePath = "Bypass/large.customblob"
        let largeURL = repoURL.appendingPathComponent(largePath)
        let largeData = Data(repeating: 0, count: Int(GitLFSAutoTrackingPolicy.default.largeFileThresholdBytes) + 1)
        try largeData.write(to: largeURL)
        try stagePathBypassingLocalGitService(repoURL: repoURL, path: largePath)

        let service = LocalGitService(localURL: repoURL)
        do {
            _ = try await service.commitAndPush(
                message: "Bypass LFS",
                authorName: "SyncMD Tests",
                authorEmail: "tests@example.com",
                pat: ""
            )
            XCTFail("Expected pre-push validation to block the large non-LFS blob")
        } catch LocalGitError.lfsFailed(let message) {
            XCTAssertTrue(message.contains(largePath))
            XCTAssertTrue(message.contains("Git LFS"))
        }
    }

    func testGitLFSSSHHostKeyTrustStoreAcceptsPersistedTrustedHostKey() throws {
        let trustURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("SyncMD-HostKeys-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }

        let store = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        try store.trust(algorithm: "ssh-ed25519", fingerprint: "SHA256:trusted", host: "git.example.com", port: 22)

        let reloaded = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        XCTAssertNoThrow(try reloaded.validate(
            algorithm: "ssh-ed25519",
            fingerprint: "SHA256:trusted",
            host: "git.example.com",
            port: 22
        ))
    }

    func testGitLFSSSHHostKeyTrustStoreRejectsUnknownHostKeyWithFingerprintDetails() throws {
        let trustURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("SyncMD-HostKeys-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }

        let store = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)

        XCTAssertThrowsError(try store.validate(
            algorithm: "ssh-ed25519",
            fingerprint: "SHA256:new-key",
            host: "git.example.com",
            port: 2222
        )) { error in
            guard let trustError = error as? GitLFSSSHHostKeyTrustError,
                  case let .unknownHostKey(host, port, algorithm, fingerprint, sawOtherKeyTypes) = trustError else {
                return XCTFail("Expected unknown host-key trust error, got \(error)")
            }
            XCTAssertEqual(host, "git.example.com")
            XCTAssertEqual(port, 2222)
            XCTAssertEqual(algorithm, "ssh-ed25519")
            XCTAssertEqual(fingerprint, "SHA256:new-key")
            XCTAssertFalse(sawOtherKeyTypes)
            XCTAssertTrue(error.localizedDescription.contains("SHA256:new-key"))
            XCTAssertTrue(error.localizedDescription.contains("git.example.com:2222"))
        }
    }

    func testGitLFSSSHHostKeyTrustStoreRejectsChangedHostKey() throws {
        let trustURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("SyncMD-HostKeys-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }

        let store = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        try store.trust(algorithm: "ssh-rsa", fingerprint: "SHA256:old-key", host: "git.example.com", port: 22)

        XCTAssertThrowsError(try store.validate(
            algorithm: "ssh-rsa",
            fingerprint: "SHA256:new-key",
            host: "git.example.com",
            port: 22
        )) { error in
            guard let trustError = error as? GitLFSSSHHostKeyTrustError,
                  case let .changedHostKey(host, port, algorithm, expected, actual) = trustError else {
                return XCTFail("Expected changed host-key trust error, got \(error)")
            }
            XCTAssertEqual(host, "git.example.com")
            XCTAssertEqual(port, 22)
            XCTAssertEqual(algorithm, "ssh-rsa")
            XCTAssertEqual(expected, "SHA256:old-key")
            XCTAssertEqual(actual, "SHA256:new-key")
            XCTAssertTrue(error.localizedDescription.contains("changed"))
            XCTAssertTrue(error.localizedDescription.contains("SHA256:old-key"))
            XCTAssertTrue(error.localizedDescription.contains("SHA256:new-key"))
        }
    }

    func testGitLFSSSHHostKeyTrustStoreTreatsUnseenAlgorithmAsUnknownNotChanged() throws {
        // Regression: the git transport (libssh2) and the Git LFS transport
        // (NIOSSH) negotiate different host-key algorithms against the same
        // server. A second key type must prompt for trust, not claim a
        // man-in-the-middle attack.
        let trustURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("SyncMD-HostKeys-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }

        let store = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        try store.trust(algorithm: "ssh-rsa", fingerprint: "SHA256:rsa-key", host: "git.example.com", port: 22)

        XCTAssertThrowsError(try store.validate(
            algorithm: "ssh-ed25519",
            fingerprint: "SHA256:ed25519-key",
            host: "git.example.com",
            port: 22
        )) { error in
            guard let trustError = error as? GitLFSSSHHostKeyTrustError,
                  case let .unknownHostKey(_, _, algorithm, fingerprint, sawOtherKeyTypes) = trustError else {
                return XCTFail("Expected unknown host-key trust error for unseen algorithm, got \(error)")
            }
            XCTAssertEqual(algorithm, "ssh-ed25519")
            XCTAssertEqual(fingerprint, "SHA256:ed25519-key")
            XCTAssertTrue(sawOtherKeyTypes)
        }

        // Once trusted, both key types validate side by side.
        try store.trust(algorithm: "ssh-ed25519", fingerprint: "SHA256:ed25519-key", host: "git.example.com", port: 22)
        XCTAssertNoThrow(try store.validate(
            algorithm: "ssh-rsa",
            fingerprint: "SHA256:rsa-key",
            host: "git.example.com",
            port: 22
        ))
        XCTAssertNoThrow(try store.validate(
            algorithm: "ssh-ed25519",
            fingerprint: "SHA256:ed25519-key",
            host: "git.example.com",
            port: 22
        ))
    }

    func testGitLFSSSHHostKeyTrustStoreAcceptsSameKeyPresentedUnderDifferentAlgorithmLabel() throws {
        // The same key material presented by the other SSH stack (e.g. an
        // algorithm the parser could not resolve) matches the existing pin.
        let trustURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("SyncMD-HostKeys-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }

        let store = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        try store.trust(algorithm: "ssh-ed25519", fingerprint: "SHA256:same-key", host: "git.example.com", port: 22)

        XCTAssertNoThrow(try store.validate(
            algorithm: "ssh-unknown",
            fingerprint: "SHA256:same-key",
            host: "git.example.com",
            port: 22
        ))
    }

    func testGitLFSSSHHostKeyTrustStoreLegacyPinWithoutAlgorithmStillValidatesAndPromptsForNewKeys() throws {
        // Pins persisted by builds before per-algorithm pinning keep working:
        // the same fingerprint is accepted (regardless of which stack
        // presents it), and a different fingerprint prompts for trust
        // instead of accusing a man-in-the-middle.
        let trustURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("SyncMD-HostKeys-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }

        let legacyJSON = """
        [{"host":"git.example.com","port":22,"fingerprint":"SHA256:legacy-key"}]
        """
        try legacyJSON.write(to: trustURL, atomically: true, encoding: .utf8)

        let store = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        XCTAssertNoThrow(try store.validate(
            algorithm: "ecdsa-sha2-nistp256",
            fingerprint: "SHA256:legacy-key",
            host: "git.example.com",
            port: 22
        ))

        XCTAssertThrowsError(try store.validate(
            algorithm: "ssh-ed25519",
            fingerprint: "SHA256:different-key",
            host: "git.example.com",
            port: 22
        )) { error in
            guard let trustError = error as? GitLFSSSHHostKeyTrustError,
                  case .unknownHostKey = trustError else {
                return XCTFail("Expected unknown host-key prompt for unseen key after legacy pin, got \(error)")
            }
        }
    }

    func testGitLFSSSHHostKeyTrustStorePreservesLegacyPinsWhenTrustingNewKeys() throws {
        // A user upgrades, then trusts a second key type: the pre-upgrade pin
        // must stay on disk (other SSH stacks still present that key), and a
        // legacy fingerprint that later gets pinned under a concrete
        // algorithm is finally dropped from the legacy bucket.
        let trustURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("SyncMD-HostKeys-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }

        let legacyJSON = """
        [{"host":"git.example.com","port":22,"fingerprint":"SHA256:legacy-rsa"}]
        """
        try legacyJSON.write(to: trustURL, atomically: true, encoding: .utf8)

        let store = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        try store.trust(algorithm: "ssh-ed25519", fingerprint: "SHA256:ed25519-new", host: "git.example.com", port: 22)

        let reloaded = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        XCTAssertNoThrow(try reloaded.validate(
            algorithm: "ssh-rsa",
            fingerprint: "SHA256:legacy-rsa",
            host: "git.example.com",
            port: 22
        ))
        XCTAssertNoThrow(try reloaded.validate(
            algorithm: "ssh-ed25519",
            fingerprint: "SHA256:ed25519-new",
            host: "git.example.com",
            port: 22
        ))

        // Pinning the legacy fingerprint under a real algorithm retires the
        // legacy bucket entry.
        try reloaded.trust(algorithm: "ssh-rsa", fingerprint: "SHA256:legacy-rsa", host: "git.example.com", port: 22)
        let reloadedAgain = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        let pins = reloadedAgain.trustedFingerprints(forHost: "git.example.com", port: 22)
        XCTAssertEqual(pins.legacyFingerprints, [])
        XCTAssertEqual(pins.algorithms["ssh-rsa"], "SHA256:legacy-rsa")
        XCTAssertEqual(pins.algorithms["ssh-ed25519"], "SHA256:ed25519-new")
    }

    func testGitLFSSSHHostKeyTrustStorePreseedsGitHubPublishedFingerprints() throws {
        let trustURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("SyncMD-HostKeys-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }

        let store = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)

        // All three published key types validate with no prior user action,
        // mirroring the first-use experience for HTTPS with bundled roots.
        XCTAssertNoThrow(try store.validate(
            algorithm: "ssh-ed25519",
            fingerprint: "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU",
            host: "github.com",
            port: 22
        ))
        XCTAssertNoThrow(try store.validate(
            algorithm: "ecdsa-sha2-nistp256",
            fingerprint: "SHA256:p2QAMXNIC1TJYWeIOttrVc98/R1BUFWu3/LiyKgUfQM",
            host: "GitHub.com",
            port: 22
        ))
        XCTAssertNoThrow(try store.validate(
            algorithm: "ssh-rsa",
            fingerprint: "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s",
            host: "github.com",
            port: 22
        ))

        // A same-algorithm key mismatch is still a hard failure.
        XCTAssertThrowsError(try store.validate(
            algorithm: "ssh-ed25519",
            fingerprint: "SHA256:attacker-key",
            host: "github.com",
            port: 22
        )) { error in
            guard let trustError = error as? GitLFSSSHHostKeyTrustError,
                  case .changedHostKey = trustError else {
                return XCTFail("Expected changed host-key error for mismatched GitHub seed, got \(error)")
            }
        }

        // Seeds are not persisted: the file stays empty and other hosts
        // still go through trust-on-first-use.
        XCTAssertFalse(FileManager.default.fileExists(atPath: trustURL.path))
        XCTAssertThrowsError(try store.validate(
            algorithm: "ssh-ed25519",
            fingerprint: "SHA256:any",
            host: "ssh.github.com",
            port: 443
        )) { error in
            guard let trustError = error as? GitLFSSSHHostKeyTrustError,
                  case .unknownHostKey = trustError else {
                return XCTFail("Expected unknown host-key error for unseeded host, got \(error)")
            }
        }
    }

    func testGitLFSSSHHostKeyAlgorithmParsesNIOSSHPublicKeyBlob() throws {
        let openSSHPublicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFheFfz6xC6XuekPEypFPgzjaQ2eoSjs05mEt09RJwl7 test@fixture"
        let key = try NIOSSHPublicKey(openSSHPublicKey: openSSHPublicKey)
        XCTAssertEqual(GitLFSSSHHostKeyAlgorithm.name(forNIOSSHPublicKey: key), "ssh-ed25519")
    }

    func testGitLFSSSHHostKeyTrustStoreKeepsHostPortsDistinct() throws {
        let trustURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("SyncMD-HostKeys-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: trustURL) }

        let store = GitLFSSSHHostKeyFileTrustStore(fileURL: trustURL)
        try store.trust(algorithm: "ssh-rsa", fingerprint: "SHA256:port-22", host: "git.example.com", port: 22)

        XCTAssertNoThrow(try store.validate(
            algorithm: "ssh-rsa",
            fingerprint: "SHA256:port-22",
            host: "git.example.com",
            port: 22
        ))
        XCTAssertThrowsError(try store.validate(
            algorithm: "ssh-rsa",
            fingerprint: "SHA256:port-22",
            host: "git.example.com",
            port: 2222
        )) { error in
            guard let trustError = error as? GitLFSSSHHostKeyTrustError,
                  case .unknownHostKey = trustError else {
                return XCTFail("Expected unknown host-key trust error for distinct port, got \(error)")
            }
        }

        try store.trust(algorithm: "ssh-rsa", fingerprint: "SHA256:port-2222", host: "git.example.com", port: 2222)
        XCTAssertNoThrow(try store.validate(
            algorithm: "ssh-rsa",
            fingerprint: "SHA256:port-2222",
            host: "git.example.com",
            port: 2222
        ))
        XCTAssertThrowsError(try store.validate(
            algorithm: "ssh-rsa",
            fingerprint: "SHA256:port-2222",
            host: "git.example.com",
            port: 22
        )) { error in
            guard let trustError = error as? GitLFSSSHHostKeyTrustError,
                  case .changedHostKey = trustError else {
                return XCTFail("Expected changed host-key trust error for the separately-pinned port, got \(error)")
            }
        }
    }

    func testGitLFSCreateLockPostsLocksAPI() async throws {
        let repoURL = try makeLFSLockingRepo()
        defer { try? FileManager.default.removeItem(at: repoURL) }

        let transport = MockGitLFSTransport { request, body in
            XCTAssertEqual(request.url?.absoluteString, "https://git.example.com/team/vault.git/info/lfs/locks")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "application/vnd.git-lfs+json")
            let bodyData = try XCTUnwrap(body)
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: bodyData) as? [String: Any])
            XCTAssertEqual(json["path"] as? String, "Docs/Manual.pdf")
            let ref = try XCTUnwrap(json["ref"] as? [String: Any])
            XCTAssertEqual(ref["name"] as? String, "refs/heads/main")
            return (Data("""
            {"lock":{"id":"lock-1","path":"Docs/Manual.pdf","locked_at":"2026-05-14T12:00:00Z","owner":{"name":"Cody"}}}
            """.utf8), 200)
        }

        let lock = try await GitLFSService(
            localURL: repoURL,
            credentials: .httpsToken(username: "cody", password: "secret"),
            transport: transport
        ).createLock(path: "Docs/Manual.pdf", refName: "refs/heads/main")

        XCTAssertEqual(lock?.id, "lock-1")
        XCTAssertEqual(lock?.path, "Docs/Manual.pdf")
        XCTAssertEqual(lock?.owner?.name, "Cody")
        XCTAssertEqual(lock?.lockedAt, ISO8601DateFormatter().date(from: "2026-05-14T12:00:00Z"))
    }

    func testGitLFSListLocksUsesLocksAPI() async throws {
        let repoURL = try makeLFSLockingRepo()
        defer { try? FileManager.default.removeItem(at: repoURL) }

        let transport = MockGitLFSTransport { request, body in
            XCTAssertNil(body)
            XCTAssertEqual(request.httpMethod, "GET")
            let components = try XCTUnwrap(URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false))
            XCTAssertEqual(components.path, "/team/vault.git/info/lfs/locks")
            XCTAssertEqual(components.queryItems?.first(where: { $0.name == "path" })?.value, "Docs/Manual.pdf")
            return (Data("""
            {"locks":[{"id":"lock-1","path":"Docs/Manual.pdf","locked_at":"2026-05-14T12:00:00Z","owner":{"name":"Cody"}}],"next_cursor":"next-page"}
            """.utf8), 200)
        }

        let result = try await GitLFSService(
            localURL: repoURL,
            credentials: .none,
            transport: transport
        ).listLocks(path: "Docs/Manual.pdf")

        XCTAssertEqual(result.locks.map(\.id), ["lock-1"])
        XCTAssertEqual(result.nextCursor, "next-page")
    }

    func testGitLFSUnlockLockPostsUnlockAPI() async throws {
        let repoURL = try makeLFSLockingRepo()
        defer { try? FileManager.default.removeItem(at: repoURL) }

        let transport = MockGitLFSTransport { request, body in
            XCTAssertEqual(request.url?.absoluteString, "https://git.example.com/team/vault.git/info/lfs/locks/lock-1/unlock")
            XCTAssertEqual(request.httpMethod, "POST")
            let bodyString = String(data: try XCTUnwrap(body), encoding: .utf8) ?? ""
            XCTAssertTrue(bodyString.contains("\"force\":true"))
            return (Data("""
            {"lock":{"id":"lock-1","path":"Docs/Manual.pdf","locked_at":"2026-05-14T12:00:00Z","owner":{"name":"Cody"}}}
            """.utf8), 200)
        }

        let lock = try await GitLFSService(
            localURL: repoURL,
            credentials: .none,
            transport: transport
        ).unlockLock(id: "lock-1", force: true)

        XCTAssertEqual(lock?.id, "lock-1")
        XCTAssertEqual(lock?.path, "Docs/Manual.pdf")
    }

    func testGitLFSVerifyLocksReturnsOursAndTheirs() async throws {
        let repoURL = try makeLFSLockingRepo()
        defer { try? FileManager.default.removeItem(at: repoURL) }

        let transport = MockGitLFSTransport { request, body in
            XCTAssertEqual(request.url?.absoluteString, "https://git.example.com/team/vault.git/info/lfs/locks/verify")
            XCTAssertEqual(request.httpMethod, "POST")
            let bodyData = try XCTUnwrap(body)
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: bodyData) as? [String: Any])
            let ref = try XCTUnwrap(json["ref"] as? [String: Any])
            XCTAssertEqual(ref["name"] as? String, "refs/heads/main")
            return (Data("""
            {"ours":[{"id":"ours-1","path":"Mine.pdf","locked_at":"2026-05-14T12:00:00Z","owner":{"name":"Cody"}}],"theirs":[{"id":"theirs-1","path":"Theirs.pdf","locked_at":"2026-05-14T12:01:00Z","owner":{"name":"Alex"}}],"next_cursor":"cursor-2"}
            """.utf8), 200)
        }

        let result = try await GitLFSService(
            localURL: repoURL,
            credentials: .none,
            transport: transport
        ).verifyLocks(refName: "refs/heads/main")

        XCTAssertTrue(result.lockingSupported)
        XCTAssertEqual(result.ours.map(\.path), ["Mine.pdf"])
        XCTAssertEqual(result.theirs.map(\.owner?.name), ["Alex"])
        XCTAssertEqual(result.nextCursor, "cursor-2")
    }

    func testGitLFSPushVerificationBlocksChangedFileLockedBySomeoneElse() async throws {
        let repoURL = try makeLFSLockingRepo(attributes: "*.pdf filter=lfs diff=lfs merge=lfs -text lockable\n")
        defer { try? FileManager.default.removeItem(at: repoURL) }

        let transport = MockGitLFSTransport { request, _ in
            XCTAssertEqual(request.url?.absoluteString, "https://git.example.com/team/vault.git/info/lfs/locks/verify")
            return (Data("""
            {"ours":[],"theirs":[{"id":"theirs-1","path":"Docs/Manual.pdf","locked_at":"2026-05-14T12:01:00Z","owner":{"name":"Alex"}}]}
            """.utf8), 200)
        }

        let service = GitLFSService(localURL: repoURL, credentials: .none, transport: transport)

        do {
            try await service.verifyPushAllowed(
                changedPaths: ["Docs/Manual.pdf", "README.md"],
                refName: "refs/heads/main"
            )
            XCTFail("Expected push verification to reject another user's lock")
        } catch LocalGitError.lfsFailed(let message) {
            XCTAssertTrue(message.contains("Docs/Manual.pdf"))
            XCTAssertTrue(message.contains("Alex"))
        }
    }

    func testGitLFSUnsupportedLockingDegradesCleanly() async throws {
        let repoURL = try makeLFSLockingRepo(attributes: "*.pdf filter=lfs diff=lfs merge=lfs -text lockable\n")
        defer { try? FileManager.default.removeItem(at: repoURL) }

        var requestCount = 0
        let transport = MockGitLFSTransport { _, _ in
            requestCount += 1
            return (Data(), 501)
        }
        let service = GitLFSService(localURL: repoURL, credentials: .none, transport: transport)

        let result = try await service.verifyLocks(refName: "refs/heads/main")
        XCTAssertFalse(result.lockingSupported)
        XCTAssertTrue(GitLFSAttributes.load(from: repoURL).isLockable(path: "Docs/Manual.pdf"))
        try await service.verifyPushAllowed(changedPaths: ["Docs/Manual.pdf"], refName: "refs/heads/main")
        XCTAssertEqual(requestCount, 2)
    }

    // MARK: - File browser deep navigation (regression: /private/var symlink)

    /// Reproduces the platform mechanism behind the "subfolders two levels
    /// deep always show as Empty Directory" bug (user report, 2.5.2/2.5.3).
    ///
    /// On iOS the app's Documents URL arrives through the `/var ->
    /// /private/var` symlink, while `contentsOfDirectory` returns child URLs
    /// with symlinks RESOLVED. The old `FileBrowserView` derived each row's
    /// relative path by prefix-stripping the vault path off `url.path`:
    /// the prefix check failed, the code silently fell back to the bare entry
    /// name, and navigation to `<vault>/lib` (a path that does not exist)
    /// threw — which `try?` converted into "Empty Directory". Depth 1 kept
    /// working only because the fallback name coincides with the correct
    /// relative path. The simulator never caught it because its container
    /// paths contain no symlinked ancestors.
    func testFileBrowserListingSurvivesSymlinkedVaultAncestor() throws {
        let fm = FileManager.default
        let root = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("filebrowser-symlink-\(UUID().uuidString)", isDirectory: true)
        defer { try? fm.removeItem(at: root) }

        // <root>/real/vault mirrors /private/var/mobile/.../Documents/<vault>
        // and <root>/varlike mirrors the unresolved /var/mobile/... form: the
        // symlink is an ANCESTOR of the enumerated directory, exactly like
        // /var -> /private/var on device. (Note: making the symlink the final
        // component instead makes the URL-based contentsOfDirectory fail
        // outright, which is a different FileManager quirk.)
        let vault = root.appendingPathComponent("real/vault", isDirectory: true)
        try fm.createDirectory(
            at: vault.appendingPathComponent("src/lib", isDirectory: true),
            withIntermediateDirectories: true
        )
        fm.createFile(atPath: vault.appendingPathComponent("src/lib/store.js").path, contents: nil)
        fm.createFile(atPath: vault.appendingPathComponent("svelte.config.js").path, contents: nil)

        let unresolvedVar = root.appendingPathComponent("varlike", isDirectory: true)
        try fm.createSymbolicLink(at: unresolvedVar, withDestinationURL: root.appendingPathComponent("real", isDirectory: true))
        let unresolvedVault = unresolvedVar.appendingPathComponent("vault", isDirectory: true)

        // Precondition: child URLs come back with symlinks resolved, so their
        // paths do NOT share the unresolved vault prefix. This is the platform
        // behaviour that broke prefix-based relative-path math on device.
        let rawChildren = try fm.contentsOfDirectory(
            at: unresolvedVault,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: .skipsHiddenFiles
        )
        XCTAssertTrue(
            rawChildren.contains { !$0.path.hasPrefix(unresolvedVault.path) },
            "Test premise: enumeration through a symlinked ancestor resolves child URLs"
        )

        // Level 0: rows are navigable by name.
        let level0 = try FileBrowserView.listContents(of: unresolvedVault, parentRelativePath: "")
        XCTAssertEqual(
            Set(level0.map(\.relativePath)),
            ["src", "svelte.config.js"]
        )

        // Level 1: relative path must be "src" + name, not just the name.
        let level1 = try FileBrowserView.listContents(
            of: unresolvedVault.appendingPathComponent("src"),
            parentRelativePath: "src"
        )
        XCTAssertEqual(level1.map(\.relativePath), ["src/lib"])
        XCTAssertTrue(level1[0].isDirectory)

        // Level 2: resolves to src/lib and actually lists its files. Under the
        // old bug this enumerated <vault>/lib (nonexistent) and threw.
        let level2 = try FileBrowserView.listContents(
            of: unresolvedVault.appendingPathComponent(level1[0].relativePath),
            parentRelativePath: level1[0].relativePath
        )
        XCTAssertEqual(level2.map(\.relativePath), ["src/lib/store.js"])

        // And the old fallback path really is invalid: <vault>/lib does not exist.
        XCTAssertFalse(
            fm.fileExists(atPath: unresolvedVault.appendingPathComponent("lib").path),
            "The depth-1 fallback name must not resolve at depth 2"
        )
    }

}

private func XCTAssertThrowsErrorAsync<T>(
    _ expression: @autoclosure () async throws -> T,
    file: StaticString = #filePath,
    line: UInt = #line
) async {
    do {
        _ = try await expression()
        XCTFail("Expected error", file: file, line: line)
    } catch {}
}

private actor RecordingPremiumTransport: PremiumHTTPTransport {
    private var values: [URLRequest] = []
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        values.append(request)
        return (Data(), HTTPURLResponse(url: request.url!, statusCode: 204, httpVersion: nil, headerFields: nil)!)
    }
    func requests() -> [URLRequest] { values }
}

private func premiumTransaction(
    id: UInt64,
    productID: String = PremiumProductIdentifiers.default.monthly
) -> PremiumVerifiedTransaction {
    PremiumVerifiedTransaction(
        productID: productID,
        transactionID: id,
        originalTransactionID: 1,
        purchaseDate: Date(timeIntervalSince1970: 100),
        expirationDate: Date.distantFuture,
        revocationDate: nil,
        appAccountToken: nil,
        environment: .sandbox,
        signedTransaction: "signed-jws"
    )
}

private actor FakePremiumStorefront: PremiumStorefront {
    private var entitlements: [PremiumVerifiedTransaction] = []
    private var purchaseOutcome: PremiumPurchaseOutcome = .pending
    private var finishedIDs: [UInt64] = []
    private var syncs = 0
    private var appAccountTokenSets = 0
    private var currentEntitlementRequests = 0
    private var continuation: AsyncStream<PremiumFinishableTransaction>.Continuation?

    func setAppAccountToken(_ token: UUID) { appAccountTokenSets += 1 }
    func products(identifiers: [String]) async throws -> [PremiumProduct] {
        identifiers.map { PremiumProduct(id: $0, displayName: $0, displayPrice: "$1.99", period: .month) }
    }
    func currentEntitlements() async -> [PremiumVerifiedTransaction] {
        currentEntitlementRequests += 1
        return entitlements
    }
    func purchase(productID: String) async throws -> PremiumPurchaseOutcome { purchaseOutcome }
    func sync() async throws { syncs += 1 }
    nonisolated func transactionUpdates() -> AsyncStream<PremiumFinishableTransaction> {
        AsyncStream { continuation in Task { await self.setContinuation(continuation) } }
    }
    func finish(_ id: UInt64) { finishedIDs.append(id) }
    func setPurchase(_ value: PremiumPurchaseOutcome) { purchaseOutcome = value }
    func setEntitlements(_ values: [PremiumVerifiedTransaction]) { entitlements = values }
    func setContinuation(_ value: AsyncStream<PremiumFinishableTransaction>.Continuation) { continuation = value }
    func finishable(_ transaction: PremiumVerifiedTransaction) -> PremiumFinishableTransaction {
        PremiumFinishableTransaction(value: transaction) { await self.finish(transaction.transactionID) }
    }
    func emit(_ transaction: PremiumVerifiedTransaction) { continuation?.yield(finishable(transaction)) }
    func finished() -> [UInt64] { finishedIDs }
    func syncCount() -> Int { syncs }
    func appAccountTokenSetCount() -> Int { appAccountTokenSets }
    func currentEntitlementRequestCount() -> Int { currentEntitlementRequests }
}

@MainActor
private struct PremiumRuntimeTestHarness {
    let runtime: PremiumRuntime
    let provider: FakeAssistRepositoryProvider
    let repository: FakeGitRepository
    let registrar: RecordingRemoteNotificationRegistrar
    let storefront: FakePremiumStorefront
    let defaultsSuite: String
    let installationID: UUID
    let keychain: any PremiumKeychainStoring

    static func make(
        api: ControllablePremiumRelayAPI,
        installationID: UUID = UUID(),
        repo existingRepo: RepoConfig? = nil,
        activeEntitlement: Bool = true,
        assistFeatureIsEnabled: Bool = true,
        keychain: any PremiumKeychainStoring = SystemPremiumKeychainStore()
    ) async -> PremiumRuntimeTestHarness {
        let defaultsSuite = "premium-runtime-\(installationID.uuidString)"
        let defaults = UserDefaults(suiteName: defaultsSuite)!
        let storefront = FakePremiumStorefront()
        await storefront.setEntitlements(activeEntitlement ? [premiumTransaction(id: 501)] : [])
        let entitlement = PremiumEntitlementStore(storefront: storefront, identifiers: .default, defaults: defaults)
        var repo = existingRepo ?? RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one")
        if existingRepo == nil {
            repo.assist = RepoAssistSettings(enabled: true, channel: "channel_12345678", selectedBranch: "main")
        }
        let repository = FakeGitRepository(
            repoInfoResult: LocalRepoInfo(
                branch: "main", commitSHA: String(repeating: "1", count: 40), changeCount: 0
            )
        )
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: repository)
        let coordinator = BackgroundSyncCoordinator(
            entitlementIsActive: { entitlement.state.isActive },
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        let registrar = RecordingRemoteNotificationRegistrar()
        let runtime = PremiumRuntime(
            entitlementStore: entitlement,
            coordinator: coordinator,
            repositoryProvider: provider,
            api: api,
            registrar: registrar,
            installation: PremiumInstallation(installationID: installationID, bundleID: "bontecou.Sync-md", appVersion: "test"),
            environment: .sandbox,
            bridge: PremiumNotificationBridge(),
            assistFeatureIsEnabled: { assistFeatureIsEnabled },
            keychain: keychain
        )
        return PremiumRuntimeTestHarness(
            runtime: runtime,
            provider: provider,
            repository: repository,
            registrar: registrar,
            storefront: storefront,
            defaultsSuite: defaultsSuite,
            installationID: installationID,
            keychain: keychain
        )
    }

    func cleanup() {
        UserDefaults.standard.removeObject(forKey: "premium.relay-consent.\(installationID.uuidString)")
        UserDefaults.standard.removeObject(forKey: "premium.automatic-sync.v1.\(installationID.uuidString)")
        UserDefaults.standard.removeObject(forKey: "premium.stale-channels.v1.\(installationID.uuidString)")
        UserDefaults.standard.removeObject(forKey: "premium.apns-token-generation.sandbox.\(installationID.uuidString)")
        keychain.delete(key: "premium.apns-token-generation.keychain.sandbox.\(installationID.uuidString)")
        UserDefaults.standard.removeObject(forKey: "premium.relay-deletion-barrier.\(installationID.uuidString)")
        keychain.delete(key: "premium.relay-deletion-credential.\(installationID.uuidString)")
        keychain.delete(key: "premium.relay-deletion-state.\(installationID.uuidString)")
        UserDefaults.standard.removePersistentDomain(forName: defaultsSuite)
        keychain.delete(key: "premium.apns-token.sandbox.\(installationID.uuidString)")
    }
}

private final class ControllablePremiumKeychain: PremiumKeychainStoring {
    private var values: [String: String] = [:]
    private var failingSaveKeys: Set<String> = []
    private var failingSaveValues: [String: Set<String>] = [:]
    private var failingLoadKeys: Set<String> = []

    func load(key: String) -> String? {
        guard !failingLoadKeys.contains(key) else { return nil }
        return values[key]
    }

    func save(key: String, value: String) -> OSStatus {
        guard !failingSaveKeys.contains(key), failingSaveValues[key]?.contains(value) != true else { return errSecNotAvailable }
        values[key] = value
        return errSecSuccess
    }

    func delete(key: String) { values.removeValue(forKey: key) }
    func value(for key: String) -> String? { values[key] }
    func failSave(for key: String) { failingSaveKeys.insert(key) }
    func allowSave(for key: String) { failingSaveKeys.remove(key) }
    func failSave(for key: String, value: String) { failingSaveValues[key, default: []].insert(value) }
    func allowSave(for key: String, value: String) { failingSaveValues[key]?.remove(value) }
    func failLoad(for key: String) { failingLoadKeys.insert(key) }
    func allowLoad(for key: String) { failingLoadKeys.remove(key) }
}

@MainActor
private final class RecordingRemoteNotificationRegistrar: RemoteNotificationRegistering, @unchecked Sendable {
    private(set) var registerCount = 0
    private(set) var unregisterCount = 0
    func register() { registerCount += 1 }
    func unregister() { unregisterCount += 1 }
}

@MainActor
private final class ControllablePremiumRelayAPI: PremiumRelayManaging, @unchecked Sendable {
    private(set) var registrationRequests: [PremiumDeviceRegistrationRequest] = []
    private(set) var deviceDeletionRequests: [PremiumDeviceDeletionRequest] = []
    private(set) var installationDeletionCount = 0
    private(set) var authorizationCount = 0
    private(set) var githubLinkAttempts = 0
    private(set) var enrollmentRequests: [PremiumRepositoryEnrollmentRequest] = []
    private(set) var deletedEnrollmentChannels: [String] = []
    var githubInstallationSummaries: [PremiumGitHubInstallationSummary] = []
    var githubInstallationResponseSequence: [[PremiumGitHubInstallationSummary]] = []
    var githubInstallationsError: (any Error)?
    var registrationFailuresRemaining = 0
    var registrationFailureCalls: Set<Int> = []
    var installationDeletionFailuresRemaining = 0
    var enrollmentResponseOverride: PremiumRepositoryEnrollment?
    var enrollmentChannelsByInstallation: [Int64: String] = [:]
    var enrollmentRejectionsByInstallation: [Int64: Int] = [:]
    var enrollmentTransientError: (any Error)?
    var enrollmentDeletionFailuresRemaining = 0
    var rejectFirstGitHubLinkAsUnauthorized = false
    private let firstGitHubLinkStarted: XCTestExpectation?
    private let firstGitHubLinkGate: AsyncGate?
    private(set) var blockOnlyFirstInstallationDeletion = false
    private(set) var relayToken: String?
    private(set) var relayRegistrationGeneration: UInt64 = 0
    private let firstRegistrationStarted: XCTestExpectation?
    private let firstRegistrationGate: AsyncGate?
    private let secondRegistrationStarted: XCTestExpectation?
    private let secondRegistrationGate: AsyncGate?
    private let firstDeviceDeletionStarted: XCTestExpectation?
    private let firstDeviceDeletionGate: AsyncGate?
    private let firstGitHubInstallationsStarted: XCTestExpectation?
    private let firstGitHubInstallationsGate: AsyncGate?
    private let secondGitHubInstallationsStarted: XCTestExpectation?
    private let secondGitHubInstallationsGate: AsyncGate?
    private var githubInstallationsCallCount = 0
    private var deletionStarted: XCTestExpectation?
    private let deletionGate: AsyncGate?
    private let enrollmentStarted: XCTestExpectation?
    private let enrollmentGate: AsyncGate?
    private let enrollmentDeletionStarted: XCTestExpectation?
    private let enrollmentDeletionGate: AsyncGate?
    private let secondEnrollmentDeletionStarted: XCTestExpectation?
    private let secondEnrollmentDeletionGate: AsyncGate?
    private var enrollmentDeletionCount = 0

    init(
        firstRegistrationStarted: XCTestExpectation? = nil,
        firstRegistrationGate: AsyncGate? = nil,
        secondRegistrationStarted: XCTestExpectation? = nil,
        secondRegistrationGate: AsyncGate? = nil,
        firstDeviceDeletionStarted: XCTestExpectation? = nil,
        firstDeviceDeletionGate: AsyncGate? = nil,
        firstGitHubInstallationsStarted: XCTestExpectation? = nil,
        firstGitHubInstallationsGate: AsyncGate? = nil,
        secondGitHubInstallationsStarted: XCTestExpectation? = nil,
        secondGitHubInstallationsGate: AsyncGate? = nil,
        deletionStarted: XCTestExpectation? = nil,
        deletionGate: AsyncGate? = nil,
        blockOnlyFirstInstallationDeletion: Bool = false,
        rejectFirstGitHubLinkAsUnauthorized: Bool = false,
        firstGitHubLinkStarted: XCTestExpectation? = nil,
        firstGitHubLinkGate: AsyncGate? = nil,
        enrollmentStarted: XCTestExpectation? = nil,
        enrollmentGate: AsyncGate? = nil,
        enrollmentDeletionStarted: XCTestExpectation? = nil,
        enrollmentDeletionGate: AsyncGate? = nil,
        secondEnrollmentDeletionStarted: XCTestExpectation? = nil,
        secondEnrollmentDeletionGate: AsyncGate? = nil
    ) {
        self.firstRegistrationStarted = firstRegistrationStarted
        self.firstRegistrationGate = firstRegistrationGate
        self.secondRegistrationStarted = secondRegistrationStarted
        self.secondRegistrationGate = secondRegistrationGate
        self.firstDeviceDeletionStarted = firstDeviceDeletionStarted
        self.firstDeviceDeletionGate = firstDeviceDeletionGate
        self.firstGitHubInstallationsStarted = firstGitHubInstallationsStarted
        self.firstGitHubInstallationsGate = firstGitHubInstallationsGate
        self.secondGitHubInstallationsStarted = secondGitHubInstallationsStarted
        self.secondGitHubInstallationsGate = secondGitHubInstallationsGate
        self.deletionStarted = deletionStarted
        self.deletionGate = deletionGate
        self.blockOnlyFirstInstallationDeletion = blockOnlyFirstInstallationDeletion
        self.rejectFirstGitHubLinkAsUnauthorized = rejectFirstGitHubLinkAsUnauthorized
        self.firstGitHubLinkStarted = firstGitHubLinkStarted
        self.firstGitHubLinkGate = firstGitHubLinkGate
        self.enrollmentStarted = enrollmentStarted
        self.enrollmentGate = enrollmentGate
        self.enrollmentDeletionStarted = enrollmentDeletionStarted
        self.enrollmentDeletionGate = enrollmentDeletionGate
        self.secondEnrollmentDeletionStarted = secondEnrollmentDeletionStarted
        self.secondEnrollmentDeletionGate = secondEnrollmentDeletionGate
    }

    func authorizeEntitlement(_ request: PremiumEntitlementUploadRequest) async throws -> PremiumInstallationCredential {
        authorizationCount += 1
        return PremiumInstallationCredential(installationID: request.installation.installationID, token: "test-bearer-\(authorizationCount)", deletionToken: "test-delete-\(authorizationCount)", expiresAt: .distantFuture)
    }
    func registerDevice(_ request: PremiumDeviceRegistrationRequest, credential: PremiumInstallationCredential) async throws {
        registrationRequests.append(request)
        let call = registrationRequests.count
        if call == 1, let firstRegistrationGate {
            firstRegistrationStarted?.fulfill()
            await firstRegistrationGate.wait()
        } else if call == 2, let secondRegistrationGate {
            secondRegistrationStarted?.fulfill()
            await secondRegistrationGate.wait()
        }
        if registrationFailureCalls.contains(call) {
            throw PremiumAPIError.rejected(500)
        }
        if registrationFailuresRemaining > 0 {
            registrationFailuresRemaining -= 1
            throw PremiumAPIError.rejected(500)
        }
        if request.registrationGeneration >= relayRegistrationGeneration {
            relayToken = request.token
            relayRegistrationGeneration = request.registrationGeneration
        }
    }
    func deleteDevice(_ request: PremiumDeviceDeletionRequest, credential: PremiumInstallationCredential) async throws {
        deviceDeletionRequests.append(request)
        if deviceDeletionRequests.count == 1 {
            firstDeviceDeletionStarted?.fulfill()
            if let firstDeviceDeletionGate { await firstDeviceDeletionGate.wait() }
        }
        let generationMatches = request.maximumRegistrationGeneration.map { relayRegistrationGeneration <= $0 } ?? true
        if generationMatches, request.token == nil || request.token == relayToken { relayToken = nil }
    }
    func startGitHubLink(credential: PremiumInstallationCredential) async throws -> PremiumGitHubLink {
        githubLinkAttempts += 1
        if githubLinkAttempts == 1, let firstGitHubLinkGate {
            firstGitHubLinkStarted?.fulfill()
            await firstGitHubLinkGate.wait()
        }
        if rejectFirstGitHubLinkAsUnauthorized && githubLinkAttempts == 1 { throw PremiumAPIError.rejected(401) }
        return PremiumGitHubLink(url: URL(string: "https://github.example/link")!, expiresAt: .distantFuture)
    }
    func githubInstallations(credential: PremiumInstallationCredential) async throws -> [PremiumGitHubInstallationSummary] {
        githubInstallationsCallCount += 1
        let call = githubInstallationsCallCount
        let response = githubInstallationResponseSequence.indices.contains(call - 1)
            ? githubInstallationResponseSequence[call - 1]
            : githubInstallationSummaries
        if call == 1 {
            firstGitHubInstallationsStarted?.fulfill()
            if let firstGitHubInstallationsGate { await firstGitHubInstallationsGate.wait() }
        } else if call == 2 {
            secondGitHubInstallationsStarted?.fulfill()
            if let secondGitHubInstallationsGate { await secondGitHubInstallationsGate.wait() }
        }
        if let githubInstallationsError { throw githubInstallationsError }
        return response
    }
    func createEnrollment(_ request: PremiumRepositoryEnrollmentRequest, credential: PremiumInstallationCredential) async throws -> PremiumRepositoryEnrollment {
        enrollmentRequests.append(request)
        enrollmentStarted?.fulfill()
        if let enrollmentGate { await enrollmentGate.wait() }
        if let status = enrollmentRejectionsByInstallation[request.githubInstallationID] {
            throw PremiumAPIError.rejected(status)
        }
        if let enrollmentTransientError { throw enrollmentTransientError }
        return enrollmentResponseOverride ?? PremiumRepositoryEnrollment(
            channel: enrollmentChannelsByInstallation[request.githubInstallationID] ?? "channel_12345678",
            githubInstallationID: request.githubInstallationID,
            repositoryID: request.repositoryID,
            branch: request.branch
        )
    }
    func deleteEnrollment(channel: String, credential: PremiumInstallationCredential) async throws {
        enrollmentDeletionCount += 1
        if enrollmentDeletionCount == 1 {
            enrollmentDeletionStarted?.fulfill()
            if let enrollmentDeletionGate { await enrollmentDeletionGate.wait() }
        } else if enrollmentDeletionCount == 2 {
            secondEnrollmentDeletionStarted?.fulfill()
            if let secondEnrollmentDeletionGate { await secondEnrollmentDeletionGate.wait() }
        }
        if enrollmentDeletionFailuresRemaining > 0 {
            enrollmentDeletionFailuresRemaining -= 1
            throw PremiumAPIError.rejected(500)
        }
        deletedEnrollmentChannels.append(channel)
    }
    func resetEnrollmentRequests() { enrollmentRequests.removeAll() }
    func resetDeletedEnrollmentChannels() { deletedEnrollmentChannels.removeAll() }

    func deleteInstallation(credential: PremiumInstallationCredential) async throws {
        installationDeletionCount += 1
        if let deletionStarted {
            self.deletionStarted = nil
            deletionStarted.fulfill()
        }
        if let deletionGate, !blockOnlyFirstInstallationDeletion || installationDeletionCount == 1 {
            await deletionGate.wait()
        }
        if installationDeletionFailuresRemaining > 0 {
            installationDeletionFailuresRemaining -= 1
            throw PremiumAPIError.rejected(500)
        }
    }
}

private actor FakeAssistConditions: BackgroundSyncConditionsProviding {
    private var value: BackgroundSyncConditions
    init(_ value: BackgroundSyncConditions) { self.value = value }
    func current() async -> BackgroundSyncConditions { value }
    func set(_ value: BackgroundSyncConditions) { self.value = value }
}

@MainActor
private final class FakeAssistRepositoryProvider: AssistRepositoryProviding {
    var repos: [RepoConfig]
    var identityResolution: AssistGitHubIdentityResolution?
    private var configurationChangeHandler: (@MainActor @Sendable () -> Void)?
    private var inventoryChangeHandler: (@MainActor @Sendable () -> Void)?
    var repo: RepoConfig {
        get { repos[0] }
        set { repos[0] = newValue }
    }
    let repository: any GitRepositoryProtocol
    init(repo: RepoConfig, repository: any GitRepositoryProtocol) {
        self.repos = [repo]
        self.repository = repository
    }
    init(repos: [RepoConfig], repository: any GitRepositoryProtocol) {
        self.repos = repos
        self.repository = repository
    }
    func assistRepositories() -> [RepoConfig] { repos }
    func assistRepository(id: UUID) -> RepoConfig? { repos.first { $0.id == id } }
    func assistRepositoryInstance(id: UUID) throws -> any GitRepositoryProtocol { repository }
    func assistCredentials(for repo: RepoConfig) -> String { "" }
    func recordAssist(result: RepositoryPullResult?, health: RepoAssistHealth, repoID: UUID) {
        guard let index = repos.firstIndex(where: { $0.id == repoID }) else { return }
        switch result {
        case .updated(_, let sha), .updatedWithAttention(_, let sha, _):
            repos[index].gitState.commitSHA = sha
        default: break
        }
        repos[index].assist.health = health
    }
    func updateAssistSettings(repoID: UUID, _ settings: RepoAssistSettings) {
        guard let index = repos.firstIndex(where: { $0.id == repoID }) else { return }
        repos[index].assist = settings
    }
    func assistCanonicalGitHubFullName(repoID: UUID) -> String? {
        guard let repo = assistRepository(id: repoID) else { return nil }
        return GitRemoteURL.parse(repo.repoURL)?.canonicalGitHubFullName
    }
    func resolveAssistGitHubIdentity(repoID: UUID) async -> AssistGitHubIdentityResolution {
        if let identityResolution { return identityResolution }
        guard let fullName = assistCanonicalGitHubFullName(repoID: repoID) else { return .definitiveNoAccess }
        return .resolved(GitHubRepositoryIdentity(repositoryID: 42, fullName: fullName))
    }
    func setAssistConfigurationChangeHandler(_ handler: (@MainActor @Sendable () -> Void)?) {
        configurationChangeHandler = handler
    }
    func setAssistInventoryChangeHandler(_ handler: (@MainActor @Sendable () -> Void)?) {
        inventoryChangeHandler = handler
    }
    func triggerConfigurationChange() { configurationChangeHandler?() }
    func triggerInventoryChange() { inventoryChangeHandler?() }
    func addRepository(_ repo: RepoConfig) {
        repos.append(repo)
        inventoryChangeHandler?()
    }
    func removeRepository(id: UUID) {
        repos.removeAll { $0.id == id }
        inventoryChangeHandler?()
    }
}

private func makeLFSLockingRepo(attributes: String = "") throws -> URL {
    let fm = FileManager.default
    let repoURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-LFSLocking-\(UUID().uuidString)", isDirectory: true)
    try fm.createDirectory(at: repoURL.appendingPathComponent(".git", isDirectory: true), withIntermediateDirectories: true)
    try """
    [remote "origin"]
        url = https://git.example.com/team/vault.git
    """.write(to: repoURL.appendingPathComponent(".git/config"), atomically: true, encoding: .utf8)
    if !attributes.isEmpty {
        try attributes.write(to: repoURL.appendingPathComponent(".gitattributes"), atomically: true, encoding: .utf8)
    }
    return repoURL
}

private final class MockGitLFSTransport: GitLFSHTTPTransport, @unchecked Sendable {
    typealias Handler = (URLRequest, Data?) async throws -> (Data, Int)

    private let handler: Handler

    init(handler: @escaping Handler) {
        self.handler = handler
    }

    func response(for request: URLRequest, body: Data?) async throws -> (Data, HTTPURLResponse) {
        let (data, statusCode) = try await handler(request, body)
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: statusCode,
            httpVersion: nil,
            headerFields: nil
        )!
        return (data, response)
    }
}

private final class MockGitLFSSSHAuthenticator: GitLFSSSHAuthenticator, @unchecked Sendable {
    typealias Handler = (GitLFSSSHAuthRequest, GitRemoteCredentials) async throws -> GitLFSAccess

    private let handler: Handler
    private(set) var requests: [GitLFSSSHAuthRequest] = []

    init(handler: @escaping Handler) {
        self.handler = handler
    }

    func authenticate(request: GitLFSSSHAuthRequest, credentials: GitRemoteCredentials) async throws -> GitLFSAccess {
        requests.append(request)
        return try await handler(request, credentials)
    }
}

private func makeTemporaryGitRepository(prefix: String) throws -> URL {
    let fm = FileManager.default
    let repoURL = fm.temporaryDirectory.appendingPathComponent("\(prefix)-\(UUID().uuidString)", isDirectory: true)
    try fm.createDirectory(at: repoURL, withIntermediateDirectories: true)

    var repo: OpaquePointer?
    let code = git_repository_init(&repo, repoURL.path, 0)
    if let repo { git_repository_free(repo) }
    guard code == 0 else {
        throw NSError(domain: "SyncMDTests.GitRepositoryInit", code: Int(code))
    }
    return repoURL
}

private func makeBareOrigin(at originURL: URL, copyingObjectsFrom sourceURL: URL, headSHA: String) throws {
    var origin: OpaquePointer?
    XCTAssertEqual(git_repository_init(&origin, originURL.path, 1), 0)
    if let origin { git_repository_free(origin) }
    let sourceObjects = sourceURL.appendingPathComponent(".git/objects", isDirectory: true)
    let targetObjects = originURL.appendingPathComponent("objects", isDirectory: true)
    try FileManager.default.removeItem(at: targetObjects)
    try FileManager.default.copyItem(at: sourceObjects, to: targetObjects)
    var repo: OpaquePointer?
    defer { if let repo { git_repository_free(repo) } }
    XCTAssertEqual(git_repository_open(&repo, originURL.path), 0)
    var oid = git_oid()
    XCTAssertEqual(headSHA.withCString { git_oid_fromstr(&oid, $0) }, 0)
    var ref: OpaquePointer?
    defer { if let ref { git_reference_free(ref) } }
    XCTAssertEqual(git_reference_create(&ref, repo, "refs/heads/main", &oid, 1, "test origin"), 0)
    XCTAssertEqual(git_repository_set_head(repo, "refs/heads/main"), 0)
}

private func setLocalAndRemoteTrackingRefs(repoURL: URL, localSHA: String, remoteSHA: String) throws {
    var repo: OpaquePointer?
    defer { if let repo { git_repository_free(repo) } }
    XCTAssertEqual(git_repository_open(&repo, repoURL.path), 0)
    var localOID = git_oid(), remoteOID = git_oid()
    XCTAssertEqual(localSHA.withCString { git_oid_fromstr(&localOID, $0) }, 0)
    XCTAssertEqual(remoteSHA.withCString { git_oid_fromstr(&remoteOID, $0) }, 0)
    var localRef: OpaquePointer?, remoteRef: OpaquePointer?
    defer {
        if let localRef { git_reference_free(localRef) }
        if let remoteRef { git_reference_free(remoteRef) }
    }
    XCTAssertEqual(git_reference_create(&localRef, repo, "refs/heads/main", &localOID, 1, "test reset"), 0)
    XCTAssertEqual(git_reference_create(&remoteRef, repo, "refs/remotes/origin/main", &remoteOID, 1, "test remote"), 0)
    XCTAssertEqual(git_repository_set_head(repo, "refs/heads/main"), 0)
}

private func setLocalBranchRef(repoURL: URL, branch: String, sha: String) throws {
    var repo: OpaquePointer?
    defer { if let repo { git_repository_free(repo) } }
    guard git_repository_open(&repo, repoURL.path) == 0 else {
        throw LocalGitError.repositoryCorrupted("Test could not open repository")
    }
    var oid = git_oid()
    guard sha.withCString({ git_oid_fromstr(&oid, $0) }) == 0 else {
        throw LocalGitError.repositoryCorrupted("Test could not parse branch OID")
    }
    var ref: OpaquePointer?
    defer { if let ref { git_reference_free(ref) } }
    guard git_reference_create(&ref, repo, "refs/heads/\(branch)", &oid, 1, "test concurrent ref update") == 0 else {
        throw LocalGitError.repositoryCorrupted("Test could not advance branch")
    }
}

private func checkoutHeadTree(repoURL: URL) throws {
    var repo: OpaquePointer?
    defer { if let repo { git_repository_free(repo) } }
    XCTAssertEqual(git_repository_open(&repo, repoURL.path), 0)
    var head: OpaquePointer?, commit: OpaquePointer?, tree: OpaquePointer?
    defer {
        if let head { git_reference_free(head) }
        if let commit { git_commit_free(commit) }
        if let tree { git_tree_free(tree) }
    }
    XCTAssertEqual(git_repository_head(&head, repo), 0)
    guard let oid = git_reference_target(head) else { throw LocalGitError.repositoryCorrupted("HEAD missing") }
    var copy = oid.pointee
    XCTAssertEqual(git_commit_lookup(&commit, repo, &copy), 0)
    XCTAssertEqual(git_commit_tree(&tree, commit), 0)
    var options = git_checkout_options()
    XCTAssertEqual(git_checkout_options_init(&options, UInt32(GIT_CHECKOUT_OPTIONS_VERSION)), 0)
    options.checkout_strategy = GIT_CHECKOUT_FORCE.rawValue
    XCTAssertEqual(git_checkout_tree(repo, tree, &options), 0)
    var index: OpaquePointer?
    defer { if let index { git_index_free(index) } }
    XCTAssertEqual(git_repository_index(&index, repo), 0)
    XCTAssertEqual(git_index_read_tree(index, tree), 0)
    XCTAssertEqual(git_index_write(index), 0)
}

private func stagePathBypassingLocalGitService(repoURL: URL, path: String) throws {
    var repo: OpaquePointer?
    defer { if let repo { git_repository_free(repo) } }
    XCTAssertEqual(git_repository_open(&repo, repoURL.path), 0)

    var index: OpaquePointer?
    defer { if let index { git_index_free(index) } }
    XCTAssertEqual(git_repository_index(&index, repo), 0)
    XCTAssertEqual(path.withCString { git_index_add_bypath(index, $0) }, 0)
    XCTAssertEqual(git_index_write(index), 0)
}

private func lfsObjectURL(repoURL: URL, pointer: GitLFSPointer) -> URL {
    repoURL
        .appendingPathComponent(".git/lfs/objects", isDirectory: true)
        .appendingPathComponent(String(pointer.oid.prefix(2)), isDirectory: true)
        .appendingPathComponent(String(pointer.oid.dropFirst(2).prefix(2)), isDirectory: true)
        .appendingPathComponent(pointer.oid)
}

private func headBlobString(repoURL: URL, path: String) throws -> String {
    var repo: OpaquePointer?
    defer { if let repo { git_repository_free(repo) } }
    XCTAssertEqual(git_repository_open(&repo, repoURL.path), 0)

    var head: OpaquePointer?
    defer { if let head { git_reference_free(head) } }
    XCTAssertEqual(git_repository_head(&head, repo), 0)

    guard let headOID = git_reference_target(head) else {
        throw LocalGitError.repositoryCorrupted("HEAD missing")
    }

    var oid = headOID.pointee
    var commit: OpaquePointer?
    defer { if let commit { git_commit_free(commit) } }
    XCTAssertEqual(git_commit_lookup(&commit, repo, &oid), 0)

    var tree: OpaquePointer?
    defer { if let tree { git_tree_free(tree) } }
    XCTAssertEqual(git_commit_tree(&tree, commit), 0)

    var entry: OpaquePointer?
    defer { if let entry { git_tree_entry_free(entry) } }
    XCTAssertEqual(path.withCString { git_tree_entry_bypath(&entry, tree, $0) }, 0)

    guard let entryOID = git_tree_entry_id(entry) else {
        throw LocalGitError.repositoryCorrupted("Tree entry missing OID")
    }

    var blobOID = entryOID.pointee
    var blob: OpaquePointer?
    defer { if let blob { git_blob_free(blob) } }
    XCTAssertEqual(git_blob_lookup(&blob, repo, &blobOID), 0)

    let size = Int(git_blob_rawsize(blob))
    guard let raw = git_blob_rawcontent(blob) else { return "" }
    return String(decoding: Data(bytes: raw, count: size), as: UTF8.self)
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
    var rebaseResult: Result<LocalPullResult, Error>?
    var continueRebaseResult: Result<LocalPullResult, Error>?
    var didAbortRebase = false
    var hydrateLFSObjectsResults: [Result<GitLFSHydrateResult, Error>] = []
    var hydrateLFSCallCount = 0
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
    var didPushCurrentBranch = false
    var didAbortMerge = false
    var conflictSessionResult: ConflictSession = .none
    var resolvedConflicts: [(path: String, strategy: ConflictResolutionStrategy)] = []
    var stagedPaths: [String] = []
    var lfsAutoTrackStageFlags: [Bool] = []
    var unstagedPaths: [String] = []
    var lfsAutoTrackingCandidatesResult: [GitLFSAutoTrackingCandidate] = []
    var lfsAutoTrackingCandidatePathRequests: [[String]?] = []
    var cloneResults: [Result<LocalCloneResult, Error>] = []
    var cloneRemoteURLs: [String] = []
    var setRemoteURLCalls: [(name: String, url: String)] = []
    var setRemoteURLGate: AsyncGate?
    var pullPlanError: Error?
    var pullPlanCallCount = 0
    var executePullOnlyCallCount = 0
    var executePullOnlyResult: Result<PullExecutionResult, Error>?
    var pullFastForwardCallCount = 0
    var pullRebaseCallCount = 0
    var continueRebaseCallCount = 0
    var mergeBranchCallCount = 0
    var completeMergeCallCount = 0
    var commitLocalCallCount = 0
    var pushCurrentBranchResult: Result<Void, Error>?
    var pushCurrentBranchCallCount = 0
    var commitAndPushResult: Result<LocalPushResult, Error>?
    var commitAndPushMessages: [String] = []
    var executePullOnlyGate: AsyncGate?

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
        cloneRemoteURLs.append(remoteURL)
        if !cloneResults.isEmpty {
            switch cloneResults.removeFirst() {
            case .success(let result):
                return result
            case .failure(let error):
                throw error
            }
        }
        return LocalCloneResult(commitSHA: repoInfoResult.commitSHA, branch: repoInfoResult.branch, fileCount: 1)
    }

    func hydrateLFSObjects(pat: String) async throws -> GitLFSHydrateResult {
        hydrateLFSCallCount += 1
        if !hydrateLFSObjectsResults.isEmpty {
            switch hydrateLFSObjectsResults.removeFirst() {
            case .success(let result):
                return result
            case .failure(let error):
                throw error
            }
        }
        return .empty
    }

    func setRemoteURL(name: String, url: String) async throws {
        if let setRemoteURLGate { await setRemoteURLGate.wait() }
        setRemoteURLCalls.append((name: name, url: url))
    }

    func pullPlan(pat: String) async throws -> PullPlan {
        pullPlanCallCount += 1
        if let pullPlanError { throw pullPlanError }
        return pullPlanResult
    }

    func pull(pat: String) async throws -> LocalPullResult {
        let plan = try await pullPlan(pat: pat)
        switch plan.action {
        case .upToDate:
            return LocalPullResult(updated: false, newCommitSHA: plan.localCommitSHA)
        case .blockedByLocalChanges:
            throw LocalGitError.pullBlockedByLocalChanges
        case .diverged:
            throw LocalGitError.pullDiverged
        case .remoteBranchMissing:
            throw LocalGitError.pullRemoteBranchMissing(plan.branch)
        case .fastForward:
            return try pullResult.get()
        }
    }

    func executePullOnly(pat: String, expectedBranch: String?) async throws -> PullExecutionResult {
        executePullOnlyCallCount += 1
        if let executePullOnlyGate { await executePullOnlyGate.wait() }
        if let executePullOnlyResult { return try executePullOnlyResult.get() }
        let plan = try await pullPlan(pat: pat)
        if let expectedBranch, expectedBranch != plan.branch {
            throw LocalGitError.wrongBranch(expected: expectedBranch, actual: plan.branch)
        }
        switch plan.action {
        case .fastForward:
            pullFastForwardCallCount += 1
            return PullExecutionResult(plan: plan, pullResult: try pullResult.get())
        case .upToDate, .blockedByLocalChanges, .diverged, .remoteBranchMissing:
            return PullExecutionResult(plan: plan, pullResult: nil)
        }
    }

    func pullFastForward(branch: String, pat: String) async throws -> LocalPullResult {
        pullFastForwardCallCount += 1
        return try pullResult.get()
    }

    func pullRebase(branch: String, pat: String, authorName: String, authorEmail: String) async throws -> LocalPullResult {
        pullRebaseCallCount += 1
        switch rebaseResult ?? pullResult {
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
        mergeBranchCallCount += 1
        return mergeResult
    }

    func pushCurrentBranch(pat: String) async throws {
        didPushCurrentBranch = true
        pushCurrentBranchCallCount += 1
        if let pushCurrentBranchResult {
            switch pushCurrentBranchResult {
            case .success:
                return
            case .failure(let error):
                throw error
            }
        }
    }

    func fetchRemote(pat: String) async throws {}

    func revertCommit(oid: String, message: String, authorName: String, authorEmail: String) async throws -> RevertResult {
        revertResult
    }

    func completeMerge(message: String, authorName: String, authorEmail: String) async throws -> MergeFinalizeResult {
        completeMergeCallCount += 1
        return mergeFinalizeResult
    }

    func abortMerge() async throws {
        didAbortMerge = true
    }

    func continueRebase(pat: String, authorName: String, authorEmail: String) async throws -> LocalPullResult {
        continueRebaseCallCount += 1
        switch continueRebaseResult ?? rebaseResult ?? pullResult {
        case .success(let result):
            return result
        case .failure(let error):
            throw error
        }
    }

    func abortRebase() async throws {
        didAbortRebase = true
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
        commitLocalCallCount += 1
        return repoInfoResult.commitSHA
    }

    func lfsAutoTrackingCandidates(paths: [String]?) async throws -> [GitLFSAutoTrackingCandidate] {
        lfsAutoTrackingCandidatePathRequests.append(paths)
        return lfsAutoTrackingCandidatesResult
    }

    func stageAll() async throws {
        try await stageAll(lfsAutoTrack: false)
    }

    func stageAll(lfsAutoTrack: Bool) async throws {
        stagedPaths.append("*")
        lfsAutoTrackStageFlags.append(lfsAutoTrack)
    }

    func stage(path: String, oldPath: String?) async throws {
        try await stage(path: path, oldPath: oldPath, lfsAutoTrack: false)
    }

    func stage(path: String, oldPath: String?, lfsAutoTrack: Bool) async throws {
        stagedPaths.append(path)
        if let oldPath { stagedPaths.append(oldPath) }
        lfsAutoTrackStageFlags.append(lfsAutoTrack)
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
        commitAndPushMessages.append(message)
        if let commitAndPushResult {
            switch commitAndPushResult {
            case .success(let result):
                return result
            case .failure(let error):
                throw error
            }
        }
        return LocalPushResult(commitSHA: repoInfoResult.commitSHA)
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

final class OnboardingAnalyticsEventModelTests: XCTestCase {
    func testPayloadEncodesOnlyCoarseOnboardingProperties() {
        let event = OnboardingAnalyticsEvent(
            name: .onboardingCompleted,
            properties: OnboardingAnalyticsProperties(
                appVersion: "1.7.0",
                buildNumber: "2026053001",
                platform: .iOS,
                onboardingStep: .ready,
                authMethod: .githubOAuth,
                saveLocationPreference: .customFolder
            )
        )

        let payload = event.encodedPayload()

        XCTAssertEqual(payload.eventName, "sync_onboarding_completed")
        XCTAssertEqual(payload.properties[.appVersion], .string("1.7.0"))
        XCTAssertEqual(payload.properties[.buildNumber], .string("2026053001"))
        XCTAssertEqual(payload.properties[.platform], .string("ios"))
        XCTAssertEqual(payload.properties[.onboardingStep], .string("ready"))
        XCTAssertEqual(payload.properties[.authMethod], .string("github_oauth"))
        XCTAssertEqual(payload.properties[.saveLocationPreference], .string("custom_folder"))
        XCTAssertNil(payload.properties[.errorCategory])
    }

    func testInvalidFreeFormMetadataIsDropped() {
        let properties = OnboardingAnalyticsProperties(
            appVersion: "1.7.0-beta",
            buildNumber: "build-123"
        )

        XCTAssertTrue(properties.encodedProperties().isEmpty)
    }
}

final class OnboardingAnalyticsClientTests: XCTestCase {
    func testOfflineTransportPersistsQueuedPayloadWithStableMetadata() async {
        let defaults = TestOnboardingAnalyticsDefaults()
        let queueKey = "onboarding.analytics.test.offline.\(UUID().uuidString)"
        let client = OnboardingAnalyticsClient(
            transport: OfflineOnboardingAnalyticsTransport(),
            defaults: defaults,
            queueKey: queueKey,
            isEnabled: true,
            retryDelayNanoseconds: 0,
            metadataProvider: {
                OnboardingAnalyticsAppMetadata(appVersion: "1.7.0", buildNumber: "123", platform: .iOS)
            }
        )

        client.trackOnboardingStepViewed(.saveLocation)
        await client.flushAndWait()

        let queued = await client.queuedPayloads()
        XCTAssertEqual(queued.count, 1)
        XCTAssertNotNil(queued.first?.eventId)
        XCTAssertEqual(queued.first?.eventName, "sync_onboarding_step_viewed")
        XCTAssertEqual(queued.first?.properties[.appVersion], .string("1.7.0"))
        XCTAssertEqual(queued.first?.properties[.buildNumber], .string("123"))
        XCTAssertEqual(queued.first?.properties[.platform], .string("ios"))
        XCTAssertEqual(queued.first?.properties[.onboardingStep], .string("save_location"))
        XCTAssertNotNil(defaults.data(forKey: queueKey))
    }

    func testSuccessfulTransportDrainsQueue() async {
        let defaults = TestOnboardingAnalyticsDefaults()
        let transport = RecordingOnboardingAnalyticsTransport()
        let client = OnboardingAnalyticsClient(
            transport: transport,
            defaults: defaults,
            queueKey: "onboarding.analytics.test.success.\(UUID().uuidString)",
            isEnabled: true,
            retryDelayNanoseconds: 0,
            metadataProvider: {
                OnboardingAnalyticsAppMetadata(appVersion: "1.7.0", buildNumber: "123", platform: .iOS)
            }
        )

        client.trackOnboardingCompleted(
            authMethod: .githubOAuth,
            saveLocationPreference: .customFolder
        )
        await client.flushAndWait()

        let queuedPayloads = await client.queuedPayloads()
        XCTAssertTrue(queuedPayloads.isEmpty)
        let payloads = await transport.payloadsValue()
        XCTAssertEqual(payloads.count, 1)
        XCTAssertEqual(payloads.first?.eventName, "sync_onboarding_completed")
        XCTAssertEqual(payloads.first?.properties[.authMethod], .string("github_oauth"))
        XCTAssertEqual(payloads.first?.properties[.saveLocationPreference], .string("custom_folder"))
    }
}

private final class TestOnboardingAnalyticsDefaults: OnboardingAnalyticsDefaultsStoring, @unchecked Sendable {
    private let queue = DispatchQueue(label: "test.onboarding.analytics.defaults")
    private var storage: [String: Any] = [:]

    func data(forKey defaultName: String) -> Data? {
        queue.sync { storage[defaultName] as? Data }
    }

    func string(forKey defaultName: String) -> String? {
        queue.sync { storage[defaultName] as? String }
    }

    func set(_ value: Any?, forKey defaultName: String) {
        queue.sync {
            if let value {
                storage[defaultName] = value
            } else {
                storage.removeValue(forKey: defaultName)
            }
        }
    }

    func removeObject(forKey defaultName: String) {
        queue.sync { _ = storage.removeValue(forKey: defaultName) }
    }
}

private actor RecordingOnboardingAnalyticsTransport: OnboardingAnalyticsTransport {
    private var payloads: [OnboardingAnalyticsPayload] = []

    func send(_ payload: OnboardingAnalyticsPayload) async throws {
        payloads.append(payload)
    }

    func payloadsValue() -> [OnboardingAnalyticsPayload] {
        payloads
    }
}

private actor EventRecorder {
    private var events: [String] = []
    func append(_ event: String) { events.append(event) }
    func values() -> [String] { events }
}

private actor AsyncGate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var isOpen = false
    func wait() async {
        if isOpen { return }
        await withCheckedContinuation { continuation = $0 }
    }
    func open() {
        isOpen = true
        continuation?.resume()
        continuation = nil
    }
}

private final class SerializationProbeRepository: GitRepositoryProtocol, @unchecked Sendable {
    private let lock = NSLock()
    private var concurrentOperations = 0
    private(set) var maximumConcurrentOperations = 0
    private(set) var completedOperations = 0

    var hasGitDirectory: Bool { true }

    func stageAll() async throws {
        lock.lock()
        concurrentOperations += 1
        maximumConcurrentOperations = max(maximumConcurrentOperations, concurrentOperations)
        lock.unlock()
        try await Task.sleep(for: .milliseconds(50))
        lock.lock()
        concurrentOperations -= 1
        completedOperations += 1
        lock.unlock()
    }

    func clone(remoteURL: String, pat: String) async throws -> LocalCloneResult { fatalError() }
    func hydrateLFSObjects(pat: String) async throws -> GitLFSHydrateResult { fatalError() }
    func setRemoteURL(name: String, url: String) async throws { fatalError() }
    func pullPlan(pat: String) async throws -> PullPlan { fatalError() }
    func pull(pat: String) async throws -> LocalPullResult { fatalError() }
    func executePullOnly(pat: String, expectedBranch: String?) async throws -> PullExecutionResult { fatalError() }
    func pullFastForward(branch: String, pat: String) async throws -> LocalPullResult { fatalError() }
    func pullRebase(branch: String, pat: String, authorName: String, authorEmail: String) async throws -> LocalPullResult { fatalError() }
    func unifiedDiff(path: String?) async throws -> UnifiedDiffResult { fatalError() }
    func listBranches() async throws -> BranchInventory { fatalError() }
    func createBranch(name: String) async throws { fatalError() }
    func switchBranch(name: String) async throws { fatalError() }
    func deleteBranch(name: String) async throws { fatalError() }
    func mergeBranch(name: String, authorName: String, authorEmail: String) async throws -> MergeResult { fatalError() }
    func pushCurrentBranch(pat: String) async throws { fatalError() }
    func revertCommit(oid: String, message: String, authorName: String, authorEmail: String) async throws -> RevertResult { fatalError() }
    func completeMerge(message: String, authorName: String, authorEmail: String) async throws -> MergeFinalizeResult { fatalError() }
    func abortMerge() async throws { fatalError() }
    func continueRebase(pat: String, authorName: String, authorEmail: String) async throws -> LocalPullResult { fatalError() }
    func abortRebase() async throws { fatalError() }
    func conflictSession() async throws -> ConflictSession { fatalError() }
    func conflictDetail(path: String) async throws -> ConflictFileDetail { fatalError() }
    func resolveConflict(path: String, strategy: ConflictResolutionStrategy) async throws { fatalError() }
    func resolveConflictWithContent(path: String, content: Data, additionalPathsToRemove: [String]) async throws { fatalError() }
    func commitLocal(message: String, authorName: String, authorEmail: String) async throws -> String { fatalError() }
    func lfsAutoTrackingCandidates(paths: [String]?) async throws -> [GitLFSAutoTrackingCandidate] { fatalError() }
    func stage(path: String, oldPath: String?) async throws { fatalError() }
    func stage(path: String, oldPath: String?, lfsAutoTrack: Bool) async throws { fatalError() }
    func stageAll(lfsAutoTrack: Bool) async throws { try await stageAll() }
    func unstage(path: String, oldPath: String?) async throws { fatalError() }
    func discardChanges(path: String) async throws { fatalError() }
    func discardAllChanges() async throws { fatalError() }
    func commitAndPush(message: String, authorName: String, authorEmail: String, pat: String) async throws -> LocalPushResult { fatalError() }
    func listStashes() async throws -> [GitStashEntry] { fatalError() }
    func saveStash(message: String, authorName: String, authorEmail: String, includeUntracked: Bool) async throws -> GitStashEntry { fatalError() }
    func applyStash(index: Int, reinstateIndex: Bool) async throws -> StashApplyResult { fatalError() }
    func popStash(index: Int, reinstateIndex: Bool) async throws -> StashApplyResult { fatalError() }
    func dropStash(index: Int) async throws { fatalError() }
    func listTags() async throws -> [GitTag] { fatalError() }
    func createTag(name: String, targetOID: String?, message: String?, authorName: String, authorEmail: String) async throws -> GitTag { fatalError() }
    func deleteTag(name: String) async throws { fatalError() }
    func pushTag(name: String, pat: String) async throws { fatalError() }
    func fetchRemote(pat: String) async throws { fatalError() }
    func commitHistory(limit: Int, skip: Int) async throws -> [GitCommitSummary] { fatalError() }
    func commitDetail(oid: String) async throws -> GitCommitDetail { fatalError() }
    func repoInfo() async throws -> LocalRepoInfo { fatalError() }
}

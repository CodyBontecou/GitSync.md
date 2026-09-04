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

    @MainActor
    func testCallbackPullMappingPreservesEveryTypedOutcome() {
        let updated = CallbackURLHandler.mapPullResult(.updated(branch: "main", commitSHA: "new"))
        XCTAssertEqual(updated.params, ["sha": "new", "updated": "true"])
        XCTAssertNil(updated.errorMessage)

        let current = CallbackURLHandler.mapPullResult(.upToDate(branch: "main", commitSHA: "same"))
        XCTAssertEqual(current.params, ["sha": "same", "updated": "false"])
        XCTAssertNil(current.errorMessage)

        let attention = CallbackURLHandler.mapPullResult(
            .updatedWithAttention(
                branch: "main",
                commitSHA: "saved",
                attention: .cancelledAfterUpdate
            )
        )
        XCTAssertEqual(attention.params, ["sha": "saved", "updated": "true"])
        XCTAssertNotNil(attention.errorMessage)

        let softStops: [RepositoryPullResult] = [
            .blockedByLocalChanges(branch: "main"),
            .diverged(branch: "main", aheadBy: 1, behindBy: 1),
            .remoteBranchMissing(branch: "main"),
            .wrongBranch(expected: "main", actual: "notes"),
            .authenticationOrTrustRequired(message: "authenticate", trustError: nil),
            .unavailable(message: "unavailable"),
            .failed(message: "failed")
        ]
        for outcome in softStops {
            let mapping = CallbackURLHandler.mapPullResult(outcome)
            XCTAssertEqual(mapping.params["updated"], "false", "\(outcome)")
            XCTAssertNotNil(mapping.errorMessage, "\(outcome)")
        }
    }

    @MainActor
    func testCallbackPushAndSyncMappingsPreserveStatusesAndCompletedWork() {
        let pushed = CallbackURLHandler.mapPushResult(.pushed(commitSHA: "published"))
        XCTAssertEqual(pushed.params, ["sha": "published"])
        XCTAssertNil(pushed.errorMessage)

        let noChanges = CallbackURLHandler.mapPushResult(.noChanges)
        XCTAssertTrue(noChanges.params.isEmpty)
        XCTAssertNotNil(noChanges.errorMessage)

        for push in [
            RepositoryPushResult.blocked(message: "blocked"),
            .authenticationOrTrustRequired(message: "authenticate", trustError: nil),
            .failed(message: "failed")
        ] {
            let mapping = CallbackURLHandler.mapPushResult(push)
            XCTAssertTrue(mapping.params.isEmpty)
            XCTAssertNotNil(mapping.errorMessage)
        }

        let saved = CallbackURLHandler.mapPushResult(
            .commitSavedNotPushed(commitSHA: "local", message: "not published", trustError: nil)
        )
        XCTAssertEqual(saved.params, ["sha": "local", "commit_saved": "true"])
        XCTAssertEqual(saved.errorMessage, "not published")

        let synced = CallbackURLHandler.mapSyncResult(.init(
            outcome: .synced,
            pull: .updated(branch: "main", commitSHA: "incoming"),
            push: .pushed(commitSHA: "outgoing"),
            message: "complete"
        ))
        XCTAssertEqual(synced.params, ["pull_updated": "true", "sha": "outgoing"])
        XCTAssertNil(synced.errorMessage)

        let skipped = CallbackURLHandler.mapSyncResult(.init(
            outcome: .pushSkipped,
            pull: .upToDate(branch: "main", commitSHA: "current"),
            push: .noChanges,
            message: "nothing to publish"
        ))
        XCTAssertEqual(
            skipped.params,
            ["pull_updated": "false", "sha": "current", "push_skipped": "true"]
        )
        XCTAssertNil(skipped.errorMessage)

        let blockedAfterCommit = CallbackURLHandler.mapSyncResult(.init(
            outcome: .blocked,
            pull: .upToDate(branch: "main", commitSHA: "before"),
            push: .commitSavedNotPushed(
                commitSHA: "saved",
                message: "publication stopped",
                trustError: nil
            ),
            message: "publication stopped"
        ))
        XCTAssertEqual(
            blockedAfterCommit.params,
            ["pull_updated": "false", "sha": "saved", "commit_saved": "true"]
        )
        XCTAssertEqual(blockedAfterCommit.errorMessage, "publication stopped")

        let authenticationAfterPull = CallbackURLHandler.mapSyncResult(.init(
            outcome: .authenticationOrTrustRequired(message: "authenticate", trustError: nil),
            pull: .updated(branch: "main", commitSHA: "pulled"),
            push: .authenticationOrTrustRequired(message: "authenticate", trustError: nil),
            message: "authenticate"
        ))
        XCTAssertEqual(authenticationAfterPull.params, ["pull_updated": "true", "sha": "pulled"])
        XCTAssertEqual(authenticationAfterPull.errorMessage, "authenticate")

        let failed = CallbackURLHandler.mapSyncResult(.init(
            outcome: .failed(message: "failed"),
            pull: nil,
            push: .failed(message: "failed"),
            message: "failed"
        ))
        XCTAssertEqual(failed.params, ["pull_updated": "false"])
        XCTAssertEqual(failed.errorMessage, "failed")
    }

    @MainActor
    func testShortcutPushAndSyncMappingsReturnBlockedEntitiesAndThrowHardFailures() throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        let repo = fixture.repoConfig

        let pushed = try GitShortcutRunner.pushOutcome(
            repo: repo,
            result: .pushed(commitSHA: "published")
        )
        XCTAssertEqual(pushed.entity.status, GitSyncResultEntity.statusPushed)
        XCTAssertEqual(pushed.entity.commitSHA, "published")

        let noChanges = try GitShortcutRunner.pushOutcome(repo: repo, result: .noChanges)
        XCTAssertEqual(noChanges.entity.status, GitSyncResultEntity.statusNoChanges)

        let blocked = try GitShortcutRunner.pushOutcome(
            repo: repo,
            result: .blocked(message: "needs attention")
        )
        XCTAssertEqual(blocked.entity.status, GitSyncResultEntity.statusBlocked)
        XCTAssertEqual(blocked.entity.message, "needs attention")

        XCTAssertThrowsError(
            try GitShortcutRunner.pushOutcome(
                repo: repo,
                result: .authenticationOrTrustRequired(message: "authenticate", trustError: nil)
            )
        ) { error in
            guard case GitShortcutError.authenticationRequired = error else {
                return XCTFail("Authentication must throw the customer-visible authentication error: \(error)")
            }
        }
        XCTAssertThrowsError(
            try GitShortcutRunner.pushOutcome(repo: repo, result: .failed(message: "hard failure"))
        ) { error in
            guard case GitShortcutError.operationFailed = error else {
                return XCTFail("Hard push failures must throw: \(error)")
            }
        }

        let synced = try GitShortcutRunner.syncOutcome(
            repo: repo,
            result: .init(
                outcome: .synced,
                pull: .updated(branch: "main", commitSHA: "incoming"),
                push: .pushed(commitSHA: "outgoing"),
                message: "complete"
            )
        )
        XCTAssertEqual(synced.entity.status, GitSyncResultEntity.statusPushed)
        XCTAssertEqual(synced.entity.commitSHA, "outgoing")

        let syncBlocked = try GitShortcutRunner.syncOutcome(
            repo: repo,
            result: .init(
                outcome: .blocked,
                pull: .upToDate(branch: "main", commitSHA: "before"),
                push: .commitSavedNotPushed(
                    commitSHA: "saved",
                    message: "publication stopped",
                    trustError: nil
                ),
                message: "publication stopped"
            )
        )
        XCTAssertEqual(syncBlocked.entity.status, GitSyncResultEntity.statusBlocked)
        XCTAssertEqual(syncBlocked.entity.commitSHA, "saved")

        XCTAssertThrowsError(
            try GitShortcutRunner.syncOutcome(
                repo: repo,
                result: .init(
                    outcome: .authenticationOrTrustRequired(message: "authenticate", trustError: nil),
                    pull: nil,
                    push: nil,
                    message: "authenticate"
                )
            )
        ) { error in
            guard case GitShortcutError.authenticationRequired = error else {
                return XCTFail("Sync authentication must throw: \(error)")
            }
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

    @MainActor
    func testManagedRepositoryRemovalWaitsForRepositoryLeaseBeforeDeletingFiles() async throws {
        let coordinator = RepositoryOperationCoordinator()
        let state = AppState(gitRepositoryFactory: { _ in
            FakeGitRepository(repoInfoResult: LocalRepoInfo(branch: "main", commitSHA: "a", changeCount: 0))
        }, loadPersistedState: false)
        let repo = RepoConfig(
            repoURL: "owner/removal-race", branch: "main", authorName: "Test",
            authorEmail: "test@example.com", vaultFolderName: "removal-\(UUID().uuidString)"
        )
        state.repos = [repo]
        var cancellationRequested = false
        state.assistRepositoryRemovalHandler = { _ in cancellationRequested = true }
        let vaultURL = state.vaultURL(for: repo.id)
        try FileManager.default.createDirectory(at: vaultURL, withIntermediateDirectories: true)
        try Data("keep until lease releases".utf8).write(to: vaultURL.appendingPathComponent("Note.md"))
        let gate = AsyncGate()
        let holderStarted = expectation(description: "lease holder started")
        let holder = Task {
            try await coordinator.withRepository(at: vaultURL) {
                holderStarted.fulfill()
                await gate.wait()
            }
        }
        await fulfillment(of: [holderStarted], timeout: 2)

        let removal = Task { @MainActor in
            await state.removeRepo(id: repo.id, deleteLocalFiles: true, operationCoordinator: coordinator)
        }
        while await coordinator.queuedOperationCount(at: vaultURL) == 0 { await Task.yield() }
        XCTAssertTrue(cancellationRequested, "Coordinator cancellation callback must run before waiting for deletion lease")
        XCTAssertTrue(FileManager.default.fileExists(atPath: vaultURL.path))
        XCTAssertNotNil(state.repo(id: repo.id))

        await gate.open()
        try await holder.value
        await removal.value
        XCTAssertFalse(FileManager.default.fileExists(atPath: vaultURL.path))
        XCTAssertNil(state.repo(id: repo.id))
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
        defaults.set(onboardingID, forKey: "onboarding.analytics.install_id.v1")

        let url = try XCTUnwrap(FeedbackHelper.privacyRequestMailtoURL(defaults: defaults))
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(components.scheme, "mailto")
        XCTAssertEqual(components.path, FeedbackHelper.supportEmail)
        XCTAssertEqual(components.queryItems?.first(where: { $0.name == "subject" })?.value,
                       "GitSync.md Privacy & Data Request")
        let body = try XCTUnwrap(components.queryItems?.first(where: { $0.name == "body" })?.value)
        XCTAssertTrue(body.contains(onboardingID))
        XCTAssertTrue(body.localizedCaseInsensitiveContains("keep this opaque installation identifier private"))
        XCTAssertFalse(body.localizedCaseInsensitiveContains("Background Sync installation"),
                       "Background Sync has no server records, so no installation identifier is collected for it")
    }

    func testPremiumReleaseConfigurationAndBackgroundCapabilities() throws {
        let info = Bundle.main.infoDictionary ?? [:]
        // "processing" covers on-device Background Sync; "fetch" was added
        // intentionally (b26eb3a) so BGAppRefreshTask app-refresh tasks can be
        // scheduled.
        XCTAssertEqual(info["UIBackgroundModes"] as? [String], ["fetch", "processing"])
        XCTAssertEqual(
            info["BGTaskSchedulerPermittedIdentifiers"] as? [String],
            SystemPremiumBackgroundProcessingScheduler.permittedIdentifiers
        )
        XCTAssertNil(info["PREMIUM_RELAY_BASE_URL"], "The relay configuration key is retired")

        let projectURL = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("Sync.md.xcodeproj/project.pbxproj")
        let project = try String(contentsOf: projectURL, encoding: .utf8)
        // Background Sync still runs entirely on-device; the push environment
        // is configured through Sync_md.entitlements (below), not the project
        // file, for push-initiated sync notifications.
        XCTAssertFalse(
            project.contains("APS_ENVIRONMENT"),
            "The APNs environment must be declared in the entitlements file, not the project file"
        )
        XCTAssertFalse(
            project.contains("PREMIUM_RELAY_BASE_URL"),
            "No relay endpoint is configured in any build"
        )
        XCTAssertEqual(
            project.components(separatedBy: "CODE_SIGN_ENTITLEMENTS = Sync.md/Sync_md.entitlements;").count - 1
                + project.components(separatedBy: "\"CODE_SIGN_ENTITLEMENTS\" = \"Sync.md/Sync_md.entitlements\";").count - 1,
            2
        )

        let entitlementsURL = projectURL
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("Sync.md/Sync_md.entitlements")
        let entitlements = try XCTUnwrap(PropertyListSerialization.propertyList(
            from: Data(contentsOf: entitlementsURL),
            options: [],
            format: nil
        ) as? [String: String])
        // Intentional since b911f13 (push-initiated sync): the aps-environment
        // entitlement lets push-worker APNs notifications surface "tap to sync"
        // prompts. Background Sync itself remains entirely on-device.
        XCTAssertEqual(
            entitlements,
            ["aps-environment": "development"],
            "Push entitlement is intentional for push-initiated sync; no other entitlements should accumulate"
        )
    }

    func testRepoConfigLegacyDecodeDefaultsAssistDisabled() throws {
        let repo = RepoConfig(repoURL: "one/repo", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one")
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(repo)) as? [String: Any])
        json.removeValue(forKey: "assist")
        let decoded = try JSONDecoder().decode(RepoConfig.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertEqual(decoded.assist, .disabled)
        XCTAssertFalse(decoded.assist.enabled)
    }

    // MARK: - Repository Discovery

    private func makeScanFixture() throws -> URL {
        let fm = FileManager.default
        let root = fm.temporaryDirectory
            .appendingPathComponent("SyncMD-Scan-\(UUID().uuidString)", isDirectory: true)

        func mkdir(_ relativePath: String) throws -> URL {
            let url = root.appendingPathComponent(relativePath, isDirectory: true)
            try fm.createDirectory(at: url, withIntermediateDirectories: true)
            return url
        }

        // Plain repository with a readable origin remote.
        let alpha = try mkdir("alpha/.git")
        let config = """
        [remote "origin"]
        \turl = https://github.com/owner/alpha.git
        \tfetch = +refs/heads/*:refs/remotes/origin/*

        [branch "main"]
        \tremote = origin
        """
        try config.write(to: alpha.appendingPathComponent("config"), atomically: true, encoding: .utf8)

        // Repository nested several levels below the root, without a remote.
        try mkdir("nested/deep/beta/.git")

        // Nested working copy inside an already-discovered repository.
        try mkdir("alpha/inner/.git")

        // Repository hidden inside a dependency tree.
        try mkdir("site/node_modules/pkg/.git")

        // Working copy below a dot directory.
        try mkdir(".secret/repo/.git")

        // Worktree-style repository whose `.git` is a file, not a directory.
        let fileGit = try mkdir("filegit")
        try "gitdir: ../elsewhere\n".write(
            to: fileGit.appendingPathComponent(".git"),
            atomically: true,
            encoding: .utf8
        )

        // Plain directory that is not a repository at all.
        try mkdir("not-a-repo")

        return root
    }

    func testGitRepoScannerFindsWorkingCopiesWithRelativePathsAndRemotes() throws {
        let root = try makeScanFixture()
        defer { try? FileManager.default.removeItem(at: root) }

        let results = GitRepoScanner.discoverRepositories(root: root)

        XCTAssertEqual(Set(results.map(\.relativePath)), ["alpha", "nested/deep/beta", "filegit"])

        let alpha = try XCTUnwrap(results.first { $0.relativePath == "alpha" })
        XCTAssertEqual(alpha.remoteURL, "https://github.com/owner/alpha.git")
        XCTAssertEqual(alpha.name, "alpha")
        XCTAssertFalse(alpha.isInsideAppContainer)
        XCTAssertEqual(alpha.url.lastPathComponent, "alpha")

        let beta = try XCTUnwrap(results.first { $0.relativePath == "nested/deep/beta" })
        XCTAssertNil(beta.remoteURL)

        let fileGit = try XCTUnwrap(results.first { $0.relativePath == "filegit" })
        XCTAssertEqual(fileGit.name, "filegit")
    }

    func testGitRepoScannerMarksContainerResultsAndRespectsDepthLimit() throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory
            .appendingPathComponent("SyncMD-ScanDepth-\(UUID().uuidString)", isDirectory: true)
        // D1/D2/D3/D4/D5/at-limit/.git sits exactly at depth 6.
        try fm.createDirectory(
            at: root.appendingPathComponent("D1/D2/D3/D4/D5/at-limit/.git"),
            withIntermediateDirectories: true
        )
        // D1/D2/D3/D4/D5/D6/too-deep/.git sits one level past the boundary.
        try fm.createDirectory(
            at: root.appendingPathComponent("D1/D2/D3/D4/D5/D6/too-deep/.git"),
            withIntermediateDirectories: true
        )
        defer { try? fm.removeItem(at: root) }

        let results = GitRepoScanner.discoverRepositories(
            root: root,
            maxDepth: GitRepoScanner.defaultMaxDepth,
            isInsideAppContainer: true
        )

        XCTAssertEqual(results.map(\.relativePath), ["D1/D2/D3/D4/D5/at-limit"])
        XCTAssertTrue(results.allSatisfy(\.isInsideAppContainer))
    }

    func testGitRepoScannerReturnsGrantRootWhenRootItselfIsARepository() throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory
            .appendingPathComponent("SyncMD-ScanRoot-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: root.appendingPathComponent(".git"), withIntermediateDirectories: true)
        try fm.createDirectory(at: root.appendingPathComponent("sub-repo/.git"), withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: root) }

        let results = GitRepoScanner.discoverRepositories(root: root)

        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results.first?.relativePath, "")
        XCTAssertEqual(results.first?.url.standardizedFileURL.path, root.standardizedFileURL.path)
        XCTAssertEqual(results.first?.name, root.lastPathComponent)
    }

    func testGitRepoScannerPreservesRelativePathThroughSymlinkedRootAncestor() throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory
            .appendingPathComponent("SyncMD-ScanSymlink-\(UUID().uuidString)", isDirectory: true)
        let realRoot = root.appendingPathComponent("real/grant", isDirectory: true)
        try fm.createDirectory(
            at: realRoot.appendingPathComponent("projects/notes/.git", isDirectory: true),
            withIntermediateDirectories: true
        )
        defer { try? fm.removeItem(at: root) }

        // Mirrors iOS's /var -> /private/var alias: the selected root retains
        // the symlink, while FileManager returns resolved descendant URLs.
        let alias = root.appendingPathComponent("varlike", isDirectory: true)
        try fm.createSymbolicLink(
            at: alias,
            withDestinationURL: root.appendingPathComponent("real", isDirectory: true)
        )
        let selectedRoot = alias.appendingPathComponent("grant", isDirectory: true)

        let results = GitRepoScanner.discoverRepositories(root: selectedRoot)

        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results.first?.relativePath, "projects/notes")
        XCTAssertEqual(results.first?.name, "notes")
    }

    func testDiscoveryDeduplicatesCanonicalPathsAcrossScanSources() throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory
            .appendingPathComponent("SyncMD-ScanDedup-\(UUID().uuidString)", isDirectory: true)
        let realRoot = root.appendingPathComponent("real", isDirectory: true)
        let realRepo = realRoot.appendingPathComponent("shared", isDirectory: true)
        let otherRepo = realRoot.appendingPathComponent("other", isDirectory: true)
        try fm.createDirectory(at: realRepo, withIntermediateDirectories: true)
        try fm.createDirectory(at: otherRepo, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: root) }

        let aliasRoot = root.appendingPathComponent("alias", isDirectory: true)
        try fm.createSymbolicLink(at: aliasRoot, withDestinationURL: realRoot)
        let aliasedRepo = aliasRoot.appendingPathComponent("shared", isDirectory: true)

        let containerResult = DiscoveredRepo(
            url: realRepo,
            relativePath: "shared",
            remoteURL: "https://github.com/owner/shared.git",
            isInsideAppContainer: true
        )
        let duplicateGrantResult = DiscoveredRepo(
            url: aliasedRepo,
            relativePath: "shared",
            remoteURL: "https://github.com/owner/shared.git",
            isInsideAppContainer: false
        )
        let uniqueGrantResult = DiscoveredRepo(
            url: otherRepo,
            relativePath: "other",
            remoteURL: nil,
            isInsideAppContainer: false
        )

        XCTAssertEqual(containerResult.id, duplicateGrantResult.id)
        let visible = GitRepoScanner.deduplicatedRepositories(
            [duplicateGrantResult, uniqueGrantResult, uniqueGrantResult],
            excluding: [containerResult]
        )
        XCTAssertEqual(visible.map(\.id), [uniqueGrantResult.id])
    }

    @MainActor
    func testRepoDiscoverySubtitleDoesNotExposeOptionalOwner() {
        let repo = DiscoveredRepo(
            url: URL(fileURLWithPath: "/tmp/notes"),
            relativePath: "notes",
            remoteURL: "https://github.com/owner/notes.git",
            isInsideAppContainer: false
        )

        let subtitle = RepoDiscoveryView.repositorySubtitle(for: repo)
        XCTAssertEqual(subtitle, "owner/notes")
        XCTAssertFalse(subtitle.contains("Optional"))
    }

    func testRepoConfigLegacyDecodeDefaultsRelativePathNil() throws {
        let repo = RepoConfig(repoURL: "one/repo", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one")
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(repo)) as? [String: Any])
        json.removeValue(forKey: "customVaultRelativePath")
        let decoded = try JSONDecoder().decode(RepoConfig.self, from: JSONSerialization.data(withJSONObject: json))
        XCTAssertNil(decoded.customVaultRelativePath)

        var anchored = repo
        anchored.customVaultRelativePath = "projects/notes"
        XCTAssertNotNil(anchored.customVaultRelativePath)
        let roundTripped = try JSONDecoder().decode(RepoConfig.self, from: JSONEncoder().encode(anchored))
        XCTAssertEqual(roundTripped.customVaultRelativePath, "projects/notes")
    }

    func testRepoPersistenceStoreMergesCustomVaultRelativePath() throws {
        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("relative-path-\(UUID()).json")
        defer { try? FileManager.default.removeItem(at: fileURL) }
        let store = RepoPersistenceStore()
        let original = RepoConfig(repoURL: "one/repo", branch: "main", authorName: "One", authorEmail: "one@example.com", vaultFolderName: "one")
        try store.replaceAll([original], at: fileURL)

        var anchored = original
        anchored.customVaultRelativePath = "repos/notes"
        anchored.customVaultBookmarkData = Data("bookmark".utf8)
        _ = try store.apply([.update(original: original, modified: anchored)], to: fileURL)

        var unrelated = anchored
        unrelated.branch = "notes"
        _ = try store.apply([.update(original: anchored, modified: unrelated)], to: fileURL)

        let persisted = try XCTUnwrap(store.loadStrict(from: fileURL).first)
        XCTAssertEqual(persisted.customVaultRelativePath, "repos/notes")
        XCTAssertNotNil(persisted.customVaultBookmarkData)
        XCTAssertEqual(persisted.branch, "notes")
        XCTAssertTrue(persisted.isExternalLocalRepository)
    }

    @MainActor
    func testAppStateAddLocalRepoAnchorsRelativePathToGrantRoot() async throws {
        let fm = FileManager.default
        let grantRoot = fm.temporaryDirectory
            .appendingPathComponent("SyncMD-Grant-\(UUID().uuidString)", isDirectory: true)
        let repoURL = grantRoot.appendingPathComponent("projects/notes", isDirectory: true)
        let gitDir = repoURL.appendingPathComponent(".git", isDirectory: true)
        try fm.createDirectory(at: gitDir, withIntermediateDirectories: true)
        try "\n[remote \"origin\"]\n\turl = https://github.com/owner/notes.git\n".write(
            to: gitDir.appendingPathComponent("config"), atomically: true, encoding: .utf8
        )
        defer { try? fm.removeItem(at: grantRoot) }

        let bookmark = try grantRoot.bookmarkData()
        let reposFile = fm.temporaryDirectory.appendingPathComponent("repos-discovery-\(UUID()).json")
        defer { try? fm.removeItem(at: reposFile) }

        let fixtureRepository = FakeGitRepository(
            repoInfoResult: LocalRepoInfo(branch: "main", commitSHA: "aaaa", changeCount: 0)
        )
        let appState = AppState(
            gitRepositoryFactory: { url in
                XCTAssertEqual(url.standardizedFileURL.path, repoURL.standardizedFileURL.path)
                return fixtureRepository
            },
            reposFileURL: reposFile,
            loadPersistedState: false
        )

        await appState.addLocalRepo(
            url: repoURL,
            bookmarkData: bookmark,
            authorName: "Discoverer",
            authorEmail: "discover@example.com",
            relativePath: "projects/notes"
        )
        await appState.addLocalRepo(
            url: repoURL,
            bookmarkData: bookmark,
            authorName: "Discoverer",
            authorEmail: "discover@example.com",
            relativePath: "projects/notes"
        )

        XCTAssertEqual(appState.repos.count, 1)
        let added = try XCTUnwrap(appState.repos.first)
        XCTAssertEqual(added.customVaultRelativePath, "projects/notes")
        XCTAssertEqual(added.repoURL, "https://github.com/owner/notes.git")
        XCTAssertEqual(added.vaultFolderName, "notes")
        XCTAssertTrue(added.isExternalLocalRepository)
        XCTAssertFalse(added.isGitSyncManagedStorage)
        XCTAssertEqual(
            appState.vaultURL(for: added.id).standardizedFileURL.path,
            repoURL.standardizedFileURL.path
        )
        XCTAssertTrue(appState.isRepoAlreadyTracked(atPath: repoURL.path))
        XCTAssertFalse(appState.isRepoAlreadyTracked(atPath: grantRoot.path))
    }

    @MainActor
    func testAppStateRelinkManagedRepoUsesDocumentsRelativePath() async throws {
        let fm = FileManager.default
        let documents = AppState.appDocumentsDirectory
        let repoName = "ScanRelink-\(UUID().uuidString.prefix(8))"
        let repoURL = documents.appendingPathComponent(repoName, isDirectory: true)
        let gitDir = repoURL.appendingPathComponent(".git", isDirectory: true)
        try fm.createDirectory(at: gitDir, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: repoURL) }

        let reposFile = fm.temporaryDirectory.appendingPathComponent("repos-relink-\(UUID()).json")
        defer { try? fm.removeItem(at: reposFile) }

        let fixtureRepository = FakeGitRepository(
            repoInfoResult: LocalRepoInfo(branch: "main", commitSHA: "bbbb", changeCount: 0)
        )
        let appState = AppState(
            gitRepositoryFactory: { _ in fixtureRepository },
            reposFileURL: reposFile,
            loadPersistedState: false
        )

        await appState.relinkManagedRepo(
            atURL: repoURL,
            authorName: "Relinker",
            authorEmail: "relink@example.com"
        )
        await appState.relinkManagedRepo(
            atURL: repoURL,
            authorName: "Relinker",
            authorEmail: "relink@example.com"
        )

        XCTAssertEqual(appState.repos.count, 1)
        let added = try XCTUnwrap(appState.repos.first)
        XCTAssertEqual(added.customVaultRelativePath, repoName)
        XCTAssertNil(added.customVaultBookmarkData)
        XCTAssertTrue(added.isGitSyncManagedStorage)
        XCTAssertFalse(added.isExternalLocalRepository)
        XCTAssertEqual(
            appState.vaultURL(for: added.id).standardizedFileURL.path,
            repoURL.standardizedFileURL.path
        )
    }

    @MainActor
    func testAppStateRelinkManagedRepoAcceptsSymlinkedDocumentsPath() async throws {
        let fm = FileManager.default
        let documents = AppState.appDocumentsDirectory
        let repoName = "ScanRelinkAlias-\(UUID().uuidString.prefix(8))"
        let repoURL = documents.appendingPathComponent(repoName, isDirectory: true)
        try fm.createDirectory(
            at: repoURL.appendingPathComponent(".git", isDirectory: true),
            withIntermediateDirectories: true
        )

        let aliasRoot = fm.temporaryDirectory
            .appendingPathComponent("SyncMD-DocumentsAlias-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: aliasRoot, withIntermediateDirectories: true)
        let documentsAlias = aliasRoot.appendingPathComponent("Documents", isDirectory: true)
        try fm.createSymbolicLink(at: documentsAlias, withDestinationURL: documents)
        let aliasedRepoURL = documentsAlias.appendingPathComponent(repoName, isDirectory: true)

        let reposFile = fm.temporaryDirectory.appendingPathComponent("repos-relink-alias-\(UUID()).json")
        defer {
            try? fm.removeItem(at: repoURL)
            try? fm.removeItem(at: aliasRoot)
            try? fm.removeItem(at: reposFile)
        }

        let fixtureRepository = FakeGitRepository(
            repoInfoResult: LocalRepoInfo(branch: "main", commitSHA: "cccc", changeCount: 0)
        )
        let appState = AppState(
            gitRepositoryFactory: { _ in fixtureRepository },
            reposFileURL: reposFile,
            loadPersistedState: false
        )

        await appState.relinkManagedRepo(
            atURL: aliasedRepoURL,
            authorName: "Relinker",
            authorEmail: "relink@example.com"
        )

        let added = try XCTUnwrap(appState.repos.first)
        XCTAssertEqual(added.customVaultRelativePath, repoName)
        XCTAssertEqual(
            AppState.canonicalFilePath(for: appState.vaultURL(for: added.id)),
            AppState.canonicalFilePath(for: repoURL)
        )
        XCTAssertTrue(appState.isRepoAlreadyTracked(atPath: aliasedRepoURL.path))
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
    func testPremiumRuntimeGlobalPreferenceDefaultsOffWithoutAutomaticWork() async {
        let harness = await PremiumRuntimeTestHarness.make()
        defer { harness.cleanup() }

        XCTAssertFalse(harness.runtime.automaticallySyncAllRepositories)
        XCTAssertFalse(harness.runtime.automaticallyPullRemoteChanges)
        XCTAssertFalse(harness.runtime.automaticallyPushLocalChanges, "Automatic publishing must default off")
        XCTAssertFalse(harness.provider.repo.assist.enabled == false && harness.provider.repo.assist.enrollmentStatus == .enrolled,
                       "No repository is included before the global preference is enabled")

        harness.runtime.setAutomaticallyPushLocalChanges(true)
        XCTAssertTrue(harness.runtime.automaticallyPushLocalChanges)
        let restored = await PremiumRuntimeTestHarness.make(defaultsSuite: harness.defaultsSuite)
        XCTAssertTrue(restored.runtime.automaticallyPushLocalChanges, "Publishing consent persists across relaunch")
        harness.runtime.setAutomaticallyPushLocalChanges(false)
        XCTAssertFalse(harness.runtime.automaticallyPushLocalChanges)
        restored.cleanup()
    }

    @MainActor
    func testPremiumRuntimeMigratesInstallationScopedPreferencesAndMaterializesPullOn() async {
        let defaultsSuite = "premium-runtime-migration-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: defaultsSuite)!
        let installationID = UUID()
        defaults.set(installationID.uuidString, forKey: "premium.installation-id.v1")
        defaults.set(true, forKey: "premium.automatic-sync.v1.\(installationID.uuidString)")
        // No installation-scoped pull key: an enabled legacy install must
        // materialize the historical pull-only default under the fixed key.

        let harness = await PremiumRuntimeTestHarness.make(defaultsSuite: defaultsSuite)
        defer { harness.cleanup() }

        XCTAssertTrue(harness.runtime.automaticallySyncAllRepositories)
        XCTAssertTrue(harness.runtime.automaticallyPullRemoteChanges)
        XCTAssertFalse(harness.runtime.automaticallyPushLocalChanges)
        XCTAssertTrue(defaults.bool(forKey: "premium.automatic-pull.v1"),
                      "The pull-only default is materialized under the fixed key")
        XCTAssertTrue(defaults.bool(forKey: "premium.automatic-sync.v1"),
                      "The legacy value is adopted under the fixed key")

        harness.runtime.setAutomaticallyPullRemoteChanges(false)
        let restored = await PremiumRuntimeTestHarness.make(defaultsSuite: defaultsSuite)
        XCTAssertFalse(restored.runtime.automaticallyPullRemoteChanges, "Explicit pull-off must survive relaunch")
        XCTAssertFalse(restored.runtime.automaticallyPushLocalChanges)
        restored.cleanup()
    }

    @MainActor
    func testPremiumRuntimeRelaunchPreservesEnabledNeitherMode() async {
        let harness = await PremiumRuntimeTestHarness.make()
        defer { harness.cleanup() }
        await harness.runtime.setAutomaticallySyncAllRepositories(true)
        harness.runtime.setAutomaticallyPullRemoteChanges(false)
        XCTAssertTrue(harness.runtime.automaticallySyncAllRepositories)
        XCTAssertFalse(harness.runtime.automaticallyPullRemoteChanges)
        XCTAssertFalse(harness.runtime.automaticallyPushLocalChanges)

        let restored = await PremiumRuntimeTestHarness.make(defaultsSuite: harness.defaultsSuite)
        XCTAssertTrue(restored.runtime.automaticallySyncAllRepositories)
        XCTAssertFalse(restored.runtime.automaticallyPullRemoteChanges)
        XCTAssertFalse(restored.runtime.automaticallyPushLocalChanges)
        restored.cleanup()
    }

    @MainActor
    func testPremiumRuntimeSchedulesProcessingWithEitherAutomaticActionAndReschedulesAfterInvocation() async {
        var repo = RepoConfig(
            repoURL: "owner/repo", branch: "main", authorName: "One",
            authorEmail: "one@example.com", vaultFolderName: "one"
        )
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        repo.assist = RepoAssistSettings(enabled: true, channel: "channel_12345678", selectedBranch: "main")
        let scheduler = RecordingBackgroundProcessingScheduler()
        let harness = await PremiumRuntimeTestHarness.make(
            repo: repo, backgroundScheduler: scheduler
        )
        defer { harness.cleanup() }

        XCTAssertEqual(scheduler.registerCount, 1)
        XCTAssertEqual(scheduler.scheduleCount, 0)

        await harness.runtime.setAutomaticallySyncAllRepositories(true)
        XCTAssertTrue(harness.runtime.automaticallyPullRemoteChanges)
        XCTAssertGreaterThanOrEqual(scheduler.scheduleCount, 1, "Automatic pull receives discretionary processing opportunities")

        let cancelsBeforePullOff = scheduler.cancelCount
        harness.runtime.setAutomaticallyPullRemoteChanges(false)
        XCTAssertGreaterThan(scheduler.cancelCount, cancelsBeforePullOff)

        let schedulesBeforePushOn = scheduler.scheduleCount
        harness.runtime.setAutomaticallyPushLocalChanges(true)
        XCTAssertFalse(harness.runtime.automaticallyPullRemoteChanges)
        XCTAssertGreaterThan(scheduler.scheduleCount, schedulesBeforePushOn, "Push-only mode receives discretionary processing opportunities")

        let scheduledBeforeInvocation = scheduler.scheduleCount
        let completed = expectation(description: "processing task completed")
        let completionRecorder = BackgroundTaskCompletionRecorder()
        completionRecorder.onComplete = { _ in completed.fulfill() }
        var task: RecordingBackgroundProcessingTask? = RecordingBackgroundProcessingTask(recorder: completionRecorder)
        weak var retainedTask = task
        scheduler.invoke(task!)
        task = nil
        XCTAssertNotNil(retainedTask, "Runtime must retain the wrapper while work is active")
        await fulfillment(of: [completed], timeout: 5)
        XCTAssertEqual(completionRecorder.values, [true])
        XCTAssertNil(retainedTask, "Completion must release the wrapper and its expiration closure")
        XCTAssertGreaterThan(scheduler.scheduleCount, scheduledBeforeInvocation, "Each invocation requests another best-effort opportunity")
        XCTAssertEqual(harness.repository.executePullOnlyCallCount, 0, "Push-only processing must never enter pull checkout")
        XCTAssertGreaterThan(harness.repository.pullPlanCallCount, 0, "Push-only processing still validates remote state")

        harness.runtime.setAutomaticallyPushLocalChanges(false)
        XCTAssertGreaterThanOrEqual(scheduler.cancelCount, 1)
    }

    @MainActor
    func testPremiumRuntimeStaleProcessingInvocationRunsNoGitWhenBothActionsAreOff() async {
        let scheduler = RecordingBackgroundProcessingScheduler()
        let harness = await PremiumRuntimeTestHarness.make(
            backgroundScheduler: scheduler
        )
        defer { harness.cleanup() }
        await harness.runtime.setAutomaticallySyncAllRepositories(true)
        harness.runtime.setAutomaticallyPullRemoteChanges(false)
        XCTAssertFalse(harness.runtime.automaticallyPushLocalChanges)

        let completed = expectation(description: "stale processing task completed")
        let recorder = BackgroundTaskCompletionRecorder()
        recorder.onComplete = { _ in completed.fulfill() }
        scheduler.invoke(RecordingBackgroundProcessingTask(recorder: recorder))
        await fulfillment(of: [completed], timeout: 2)

        XCTAssertEqual(recorder.values, [true])
        XCTAssertEqual(harness.repository.executePullOnlyCallCount, 0)
        XCTAssertEqual(harness.repository.pullPlanCallCount, 0)
        XCTAssertTrue(harness.repository.stagedPaths.isEmpty)
        XCTAssertTrue(harness.repository.commitAndPushMessages.isEmpty)
    }

    @MainActor
    func testPremiumBackgroundProcessingExecutionRetainsWrapperAndExpiresExactlyOnceWhileBlocked() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        let provider = FakeAssistRepositoryProvider(repo: fixture.repoConfig, repository: fixture.repository)
        let coordinator = BackgroundSyncCoordinator(
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        let started = expectation(description: "processing operation started")
        let bodyReturned = expectation(description: "processing operation returned after cancellation")
        let completed = expectation(description: "expiration completed task")
        let gate = AsyncGate()
        let recorder = BackgroundTaskCompletionRecorder()
        recorder.onComplete = { _ in completed.fulfill() }
        var backgroundTask: RecordingBackgroundProcessingTask? = RecordingBackgroundProcessingTask(recorder: recorder)
        weak var retainedBackgroundTask = backgroundTask
        var execution: PremiumBackgroundProcessingExecution? = PremiumBackgroundProcessingExecution(
            task: backgroundTask!, coordinator: coordinator
        )
        weak var retainedExecution = execution
        let expiration = backgroundTask?.expirationHandler
        execution?.start {
            started.fulfill()
            await gate.wait()
            bodyReturned.fulfill()
            return true
        }
        execution = nil
        backgroundTask = nil

        await fulfillment(of: [started], timeout: 2)
        XCTAssertNotNil(retainedExecution)
        XCTAssertNotNil(retainedBackgroundTask, "Execution must retain the production wrapper while work is blocked")
        expiration?()
        expiration?()
        await fulfillment(of: [completed], timeout: 1)
        XCTAssertEqual(recorder.values, [false], "Expiration completes false exactly once")
        XCTAssertNil(retainedBackgroundTask, "Expiration releases the wrapper without waiting for blocked work")

        await gate.open()
        await fulfillment(of: [bodyReturned], timeout: 2)
        await Task.yield()
        XCTAssertEqual(recorder.values, [false], "Late operation completion must not complete the BG task again")
        XCTAssertNil(retainedExecution, "Completion breaks the operation retention cycle")
    }

    @MainActor
    func testPremiumRuntimeGlobalDisableClearsPublishingConsentAcrossRelaunchAndReenable() async {
        let harness = await PremiumRuntimeTestHarness.make()
        defer { harness.cleanup() }
        await harness.runtime.setAutomaticallySyncAllRepositories(true)
        XCTAssertTrue(harness.runtime.automaticallyPullRemoteChanges)
        harness.runtime.setAutomaticallyPushLocalChanges(true)
        XCTAssertTrue(harness.runtime.automaticallyPushLocalChanges)

        await harness.runtime.setAutomaticallySyncAllRepositories(false)
        XCTAssertFalse(harness.runtime.automaticallyPullRemoteChanges)
        XCTAssertFalse(harness.runtime.automaticallyPushLocalChanges)

        let relaunched = await PremiumRuntimeTestHarness.make(defaultsSuite: harness.defaultsSuite)
        XCTAssertFalse(relaunched.runtime.automaticallyPullRemoteChanges)
        XCTAssertFalse(relaunched.runtime.automaticallyPushLocalChanges)
        await relaunched.runtime.setAutomaticallySyncAllRepositories(true)
        XCTAssertTrue(relaunched.runtime.automaticallyPullRemoteChanges, "A fresh activation restores the safe pull-only default")
        XCTAssertFalse(relaunched.runtime.automaticallyPushLocalChanges, "Re-enabling pull automation requires fresh publishing consent")
        relaunched.cleanup()
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
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        let defaultsSuite = "provider-removal-runtime-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: defaultsSuite)!
        defer { defaults.removePersistentDomain(forName: defaultsSuite) }
        let runtime = PremiumRuntime(
            coordinator: coordinator,
            repositoryProvider: provider,
            assistFeatureIsEnabled: { true },
            defaults: defaults
        )
        _ = runtime
        // This test drives the coordinator directly; select pull explicitly
        // because the production runtime correctly restores both actions off
        // while global Background Sync is disabled.
        coordinator.setAutomaticallyPullRemoteChanges(true)

        let flight = Task { @MainActor in await coordinator.reconcile(repoID: repo.id) }
        await waitUntil { repository.executePullOnlyCallCount == 1 }
        let removal = Task { @MainActor in await provider.removeRepo(id: repo.id) }
        await Task.yield()
        await gate.open()
        await removal.value

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
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        let defaultsSuite = "foreground-cancel-runtime-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: defaultsSuite)!
        defer { defaults.removePersistentDomain(forName: defaultsSuite) }
        let runtime = PremiumRuntime(
            coordinator: coordinator,
            repositoryProvider: provider,
            assistFeatureIsEnabled: { true },
            defaults: defaults
        )
        // This test drives the coordinator directly rather than enabling the
        // runtime's global mode, so opt into the pull action under test.
        coordinator.setAutomaticallyPullRemoteChanges(true)

        let foreground = Task { @MainActor in await coordinator.reconcileForeground() }
        await waitUntil { repository.executePullOnlyCallCount == 1 }
        runtime.cancelForegroundReconciliation()
        await gate.open()

        let results = await foreground.value
        XCTAssertEqual(results[repo.id], .deferred("Cancelled"))
        XCTAssertEqual(provider.repo.assist.health, .never)
    }

    @MainActor
    func testConcurrentForegroundReconciliationsCoalesceIntoOnePass() async {
        var repo = RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        let harness = await PremiumRuntimeTestHarness.make(repo: repo)
        defer { harness.cleanup() }
        await harness.runtime.setAutomaticallySyncAllRepositories(true)

        async let first = harness.runtime.reconcileForeground()
        async let second = harness.runtime.reconcileForeground()
        _ = await (first, second)
        XCTAssertEqual(harness.repository.pullPlanCallCount, 1,
                       "Overlapping activations coalesce into the running pass instead of cancelling and restarting")
    }

    @MainActor
    func testForegroundReconciliationCooldownSuppressesBouncesButExplicitSyncBypasses() async {
        var repo = RepoConfig(repoURL: "owner/repo", branch: "main", authorName: "One",
                              authorEmail: "one@example.com", vaultFolderName: "one")
        repo.gitState.commitSHA = String(repeating: "1", count: 40)
        let harness = await PremiumRuntimeTestHarness.make(repo: repo)
        defer { harness.cleanup() }
        await harness.runtime.setAutomaticallySyncAllRepositories(true)

        await harness.runtime.reconcileForeground()
        let afterFirstPass = harness.repository.pullPlanCallCount
        XCTAssertGreaterThanOrEqual(afterFirstPass, 1, "First activation runs a full pass")

        await harness.runtime.reconcileForeground()
        XCTAssertEqual(harness.repository.pullPlanCallCount, afterFirstPass,
                       "A scene bounce inside the cooldown must not refetch")

        await harness.runtime.reconcileNow()
        XCTAssertGreaterThan(harness.repository.pullPlanCallCount, afterFirstPass,
                             "Explicit sync bypasses the bounce cooldown")
    }

    @MainActor
    func testBackgroundCoordinatorGatesAndRecordsTypedResults() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        repo.assist = RepoAssistSettings(enabled: true, channel: "channel_12345678", selectedBranch: "main")
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let conditions = FakeAssistConditions(BackgroundSyncConditions(isWiFi: true, isExternalPower: true))
        let coordinator = BackgroundSyncCoordinator(repositoryProvider: provider, conditionsProvider: conditions)

        let result = await coordinator.reconcile(repoID: repo.id)
        XCTAssertEqual(result, .completed(.pullOnly(.upToDate(branch: "main", commitSHA: repo.gitState.commitSHA))))
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

        provider.repo.assist.networkPolicy = .wifiOnly
        await conditions.set(BackgroundSyncConditions(isWiFi: false, isExternalPower: true))
        let wifiDeferred = await coordinator.reconcile(repoID: repo.id)
        XCTAssertEqual(wifiDeferred, .deferred("Waiting for Wi-Fi."))
        provider.repo.assist.networkPolicy = .any
        provider.repo.assist.selectedBranch = "notes"
        let branchResult = await coordinator.reconcile(repoID: repo.id)
        XCTAssertEqual(branchResult, .completed(.pullOnly(.wrongBranch(expected: "notes", actual: "main"))))
        XCTAssertEqual(provider.repo.assist.health.attention, .wrongBranch)

        let disabledProvider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        disabledProvider.repo.assist.enabled = false
        let disabled = BackgroundSyncCoordinator(repositoryProvider: disabledProvider, conditionsProvider: conditions)
        let disabledResult = await disabled.reconcile(repoID: repo.id)
        XCTAssertEqual(disabledResult, .ignored, "Repos opted out of Background Sync are never reconciled")
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
        let coordinator = BackgroundSyncCoordinator(repositoryProvider: provider, conditionsProvider: conditions)

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
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )

        let disposition = await coordinator.reconcile(repoID: repo.id)

        guard case .completed(let reconciliation) = disposition,
              case .updatedWithAttention(_, let resultSHA, _) = reconciliation.pull else {
            return XCTFail("Expected completed updated-with-attention, got \(disposition)")
        }
        XCTAssertEqual(resultSHA, newCommit)
        XCTAssertEqual(provider.repo.gitState.commitSHA, newCommit)
        XCTAssertEqual(provider.repo.assist.health.kind, .attention)
        XCTAssertEqual(provider.repo.assist.health.attention, .lfsHydration)
        XCTAssertEqual(provider.repo.assist.health.commitSHA, newCommit)
        XCTAssertEqual(provider.repo.assist.health.lastSuccessDate, successDate)
    }

    @MainActor
    func testBackgroundCoordinatorPreservesSuccessfulPullTruthWhenFollowingPushFails() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        repo.assist = RepoAssistSettings(enabled: true, selectedBranch: "main")
        let newCommit = String(repeating: "d", count: 40)
        let completedAt = Date(timeIntervalSince1970: 1_800_000_000)
        let initialPlan = PullPlan(
            action: .fastForward, branch: "main", localCommitSHA: repo.gitState.commitSHA,
            remoteCommitSHA: newCommit, hasLocalChanges: false, aheadBy: 0, behindBy: 1
        )
        let revalidatedPlan = PullPlan(
            action: .upToDate, branch: "main", localCommitSHA: newCommit,
            remoteCommitSHA: newCommit, hasLocalChanges: true, aheadBy: 0, behindBy: 0
        )
        fixture.repository.pullPlanResults = [initialPlan, revalidatedPlan]
        fixture.repository.pullResult = .success(LocalPullResult(updated: true, newCommitSHA: newCommit))
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main", commitSHA: newCommit, changeCount: 1, statusEntries: [Self.stagedEntry()]
        )
        fixture.repository.commitAndPushResult = .failure(LocalGitError.pushFailed("offline"))
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let coordinator = BackgroundSyncCoordinator(
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions(), now: { completedAt }
        )
        coordinator.setAutomaticallyPushLocalChanges(true)

        let disposition = await coordinator.reconcile(repoID: repo.id)

        guard case .completed(let result) = disposition else { return XCTFail("Expected composite failure result") }
        XCTAssertEqual(result.outcome, .failed)
        XCTAssertTrue(result.didTransferData)
        XCTAssertEqual(result.finalLocalCommitSHA, newCommit)
        XCTAssertEqual(provider.repo.gitState.commitSHA, newCommit)
        XCTAssertEqual(provider.repo.assist.health.kind, .failed)
        XCTAssertEqual(provider.repo.assist.health.lastSuccessDate, completedAt)
    }

    @MainActor
    func testBackgroundCoordinatorReportsActivityLifecycleAroundReconciliation() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        repo.assist = RepoAssistSettings(enabled: true, selectedBranch: "main")
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let coordinator = BackgroundSyncCoordinator(
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )

        _ = await coordinator.reconcile(repoID: repo.id)

        XCTAssertEqual(provider.backgroundSyncActivityEvents.count, 2, "A completed flight must report exactly one begin/finish pair")
        XCTAssertEqual(provider.backgroundSyncActivityEvents.first?.repoID, repo.id)
        XCTAssertTrue(provider.backgroundSyncActivityEvents.first?.began == true)
        XCTAssertEqual(provider.backgroundSyncActivityEvents.last?.repoID, repo.id)
        XCTAssertTrue(provider.backgroundSyncActivityEvents.last?.began == false)
    }

    @MainActor
    func testBackgroundCoordinatorReportsActivityFinishEvenWhenFlightIsCancelledMidRun() async {
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
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        coordinator.setAutomaticallyPullRemoteChanges(true)

        let flight = Task { @MainActor in await coordinator.reconcile(repoID: repo.id) }
        await waitUntil { provider.backgroundSyncActivityEvents.count == 1 }
        XCTAssertEqual(provider.backgroundSyncActivityEvents.first?.repoID, repo.id)
        XCTAssertTrue(provider.backgroundSyncActivityEvents.first?.began == true)

        coordinator.cancel(repoID: repo.id)
        await gate.open()
        _ = await flight.value

        XCTAssertEqual(provider.backgroundSyncActivityEvents.count, 2, "Cancellation mid-run must still report the finish half of the pair")
        XCTAssertEqual(provider.backgroundSyncActivityEvents.last?.repoID, repo.id)
        XCTAssertTrue(provider.backgroundSyncActivityEvents.last?.began == false)
    }

    @MainActor
    func testAppStateRecordAssistSkipsStatusRescanWhenPassVerifiedUpToDate() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        let persistenceURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("record-assist-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: persistenceURL) }
        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            reposFileURL: persistenceURL,
            loadPersistedState: false
        )
        var repo = fixture.repoConfig
        repo.assist.enabled = true
        appState.repos = [repo]

        // An up-to-date pass verified freshness without touching the tree;
        // the cached status entries remain valid so no rescan may start.
        appState.recordAssist(
            result: .pullOnly(.upToDate(branch: repo.branch, commitSHA: repo.gitState.commitSHA)),
            health: RepoAssistHealth(
                kind: .upToDate,
                lastAttemptDate: Date(),
                lastSuccessDate: Date(),
                commitSHA: repo.gitState.commitSHA
            ),
            repoID: repo.id
        )
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(fixture.repository.repoInfoCallCount, 0, "An up-to-date verification must not trigger a redundant working-tree rescan")

        // A pass that pulled new commits changed the tree and must rescan.
        let newSHA = String(repeating: "a", count: 40)
        appState.recordAssist(
            result: .pullOnly(.updated(branch: repo.branch, commitSHA: newSHA)),
            health: RepoAssistHealth(
                kind: .updated,
                lastAttemptDate: Date(),
                lastSuccessDate: Date(),
                commitSHA: newSHA
            ),
            repoID: repo.id
        )
        await waitUntil { fixture.repository.repoInfoCallCount == 1 }
        XCTAssertEqual(fixture.repository.repoInfoCallCount, 1, "A pass that transferred data must rescan the working tree")
    }

    @MainActor
    func testAppStateRecordAssistOnlyAdvancesLastSyncDateWhenDataTransferred() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        let persistenceURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("record-assist-sync-date-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: persistenceURL) }
        let appState = AppState(
            gitRepositoryFactory: { _ in fixture.repository },
            reposFileURL: persistenceURL,
            loadPersistedState: false
        )
        var repo = fixture.repoConfig
        repo.assist.enabled = true
        let staleSyncDate = Date(timeIntervalSinceNow: -3600)
        repo.gitState.lastSyncDate = staleSyncDate
        appState.repos = [repo]

        // An up-to-date verification on app open must not reset the repo-card
        // "last sync" clock — otherwise the chip always reads "just now".
        appState.recordAssist(
            result: .pullOnly(.upToDate(branch: repo.branch, commitSHA: repo.gitState.commitSHA)),
            health: RepoAssistHealth(
                kind: .upToDate,
                lastAttemptDate: Date(),
                lastSuccessDate: Date(),
                commitSHA: repo.gitState.commitSHA
            ),
            repoID: repo.id
        )
        let unchanged = try XCTUnwrap(appState.repo(id: repo.id)?.gitState.lastSyncDate)
        XCTAssertEqual(
            unchanged.timeIntervalSince(staleSyncDate),
            0,
            accuracy: 0.001,
            "An up-to-date verification must not advance lastSyncDate"
        )

        // A pass that pulled commits moved data and must advance the clock.
        let newSHA = String(repeating: "b", count: 40)
        appState.recordAssist(
            result: .pullOnly(.updated(branch: repo.branch, commitSHA: newSHA)),
            health: RepoAssistHealth(
                kind: .updated,
                lastAttemptDate: Date(),
                lastSuccessDate: Date(),
                commitSHA: newSHA
            ),
            repoID: repo.id
        )
        let advanced = try XCTUnwrap(appState.repo(id: repo.id)?.gitState.lastSyncDate)
        XCTAssertGreaterThan(
            advanced.timeIntervalSince(staleSyncDate), 3500,
            "A pass that transferred data must advance lastSyncDate"
        )
    }

    @MainActor
    func testPushSyncRegistrationBodyOnlyIncludesClonedGitHubRepos() {
        var cloned = RepoConfig(repoURL: "https://github.com/CodyBontecou/Travel.git", branch: "main", authorName: "A", authorEmail: "a@b.c", vaultFolderName: "travel")
        cloned.gitState.commitSHA = "abc123"
        var notCloned = RepoConfig(repoURL: "https://github.com/CodyBontecou/Other.git", branch: "main", authorName: "A", authorEmail: "a@b.c", vaultFolderName: "other")
        notCloned.gitState.commitSHA = ""
        var nonGitHub = RepoConfig(repoURL: "https://gitlab.com/someone/repo.git", branch: "main", authorName: "A", authorEmail: "a@b.c", vaultFolderName: "repo")
        nonGitHub.gitState.commitSHA = "abc123"

        let body = PushSyncManager.makeRegistrationBody(
            tokenHex: String(repeating: "a", count: 64),
            repos: [cloned, notCloned, nonGitHub],
            deviceSecret: "test-secret-123"
        )
        let json = (try? JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertNotNil(json)
        XCTAssertEqual(json?["token"] as? String, String(repeating: "a", count: 64))
        XCTAssertEqual(json?["deviceSecret"] as? String, "test-secret-123")
        let names = ((json?["repos"] as? [[String: Any]]) ?? []).compactMap { $0["name"] as? String }
        XCTAssertEqual(names.count, 1)
        XCTAssertTrue(names.first?.lowercased().hasSuffix("/travel") == true, "expected owner/travel, got \(names)")
    }

    @MainActor
    func testPushSyncHexStringFormatting() {
        let data = Data([0x00, 0x0f, 0xff, 0xa5])
        XCTAssertEqual(PushSyncManager.hexString(from: data), "000fffa5")
        XCTAssertEqual(PushSyncManager.hexString(from: Data()).count, 0)
    }

    @MainActor
    func testBackgroundCoordinatorClassifiesPostUpdateLFSAuthenticationAsAuthenticationAttention() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        repo.assist = RepoAssistSettings(enabled: true, selectedBranch: "main")
        let newCommit = String(repeating: "e", count: 40)
        fixture.repository.pullPlanResult = PullPlan(
            action: .fastForward, branch: "main", localCommitSHA: repo.gitState.commitSHA,
            remoteCommitSHA: newCommit, hasLocalChanges: false, aheadBy: 0, behindBy: 1
        )
        fixture.repository.pullResult = .success(LocalPullResult(
            updated: true, newCommitSHA: newCommit,
            attention: .lfsAuthenticationOrTrustRequired(message: "Trust required")
        ))
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let coordinator = BackgroundSyncCoordinator(
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )

        let result = await coordinator.reconcile(repoID: repo.id)

        XCTAssertTrue(result.didTransferData)
        XCTAssertEqual(provider.repo.gitState.commitSHA, newCommit)
        XCTAssertEqual(provider.repo.assist.health.kind, .attention)
        XCTAssertEqual(provider.repo.assist.health.attention, .authenticationOrTrust)
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

    func testLocalGitPullOnlyHoldsIndexLockAcrossCheckoutAndRefCommit() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-PullOnlyIndexLock")
        defer { try? fm.removeItem(at: repoURL) }
        let originURL = fm.temporaryDirectory.appendingPathComponent(
            "SyncMD-PullOnlyIndexLock-Origin-\(UUID().uuidString)",
            isDirectory: true
        )
        defer { try? fm.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("README.md")
        let setup = LocalGitService(localURL: repoURL)

        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "README.md")
        _ = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        let baseBranch = try await setup.repoInfo().branch
        try await setup.createBranch(name: "feature")
        try await setup.switchBranch(name: "feature")
        try "feature\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "README.md")
        _ = try await setup.commitLocal(message: "Feature", authorName: "Tests", authorEmail: "tests@example.com")
        try await setup.switchBranch(name: baseBranch)
        try "local\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "README.md")
        let localSHA = try await setup.commitLocal(message: "Local", authorName: "Tests", authorEmail: "tests@example.com")
        try "remote\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "README.md")
        let remoteSHA = try await setup.commitLocal(message: "Remote", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: remoteSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: localSHA, remoteSHA: remoteSHA)
        try checkoutHeadTree(repoURL: repoURL)
        try await setup.setRemoteURL(name: "origin", url: "file://localhost\(originURL.path)")

        let lockHeld = expectation(description: "pull-only holds index lock")
        let releaseLock = DispatchSemaphore(value: 0)
        let service = LocalGitService(localURL: repoURL, pullOnlyAfterIndexLock: {
            lockHeld.fulfill()
            releaseLock.wait()
        })
        let pullTask = Task { try await service.executePullOnly(pat: "", expectedBranch: "main") }
        await fulfillment(of: [lockHeld], timeout: 5)
        let indexLockURL = repoURL.appendingPathComponent(".git/index.lock")
        XCTAssertTrue(fm.fileExists(atPath: indexLockURL.path))

        do {
            _ = try await setup.mergeBranch(
                name: "feature",
                authorName: "Tests",
                authorEmail: "tests@example.com"
            )
            XCTFail("A concurrent merge must not mutate the index while pull-only owns index.lock")
        } catch LocalGitError.mergeConflictsDetected {
            XCTFail("The concurrent conflict must be rejected before its index is persisted")
        } catch {}
        let conflictWhileLocked = try await setup.conflictSession()
        let infoWhileLocked = try await setup.repoInfo()
        XCTAssertFalse(conflictWhileLocked.isActive)
        XCTAssertEqual(infoWhileLocked.commitSHA, localSHA)
        XCTAssertTrue(fm.fileExists(atPath: indexLockURL.path))

        releaseLock.signal()
        let execution = try await pullTask.value
        XCTAssertEqual(execution.plan.action, .fastForward)
        XCTAssertEqual(execution.pullResult?.newCommitSHA, remoteSHA)
        let finalInfo = try await setup.repoInfo()
        XCTAssertEqual(finalInfo.commitSHA, remoteSHA)
        XCTAssertFalse(fm.fileExists(atPath: indexLockURL.path))
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

    // MARK: - RepositoryPushRunner / pushOnly / syncRepository (Issue #32)

    /// A status entry that is already staged — the fake never mutates its
    /// `repoInfoResult`, so an `indexStatus` entry makes the 8-pass staging
    /// loop succeed on the first pass.
    private static func stagedEntry(path: String = "Note.md") -> GitStatusEntry {
        GitStatusEntry(path: path, indexStatus: .modified, workTreeStatus: nil)
    }

    /// A work-tree-only entry the fake can never stage — exercises the
    /// "saw changes but couldn't stage them" failure path.
    private static func unstagedEntry(path: String = "Note.md") -> GitStatusEntry {
        GitStatusEntry(path: path, indexStatus: nil, workTreeStatus: .modified)
    }

    @MainActor
    func testRepositoryPushRunnerMapsSuccess() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        let pushedSHA = "abc123abc123abc123abc123abc123abc123abc1"
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main",
            commitSHA: fixture.repoInfo.commitSHA,
            changeCount: 1,
            statusEntries: [Self.stagedEntry()]
        )
        fixture.repository.commitAndPushResult = .success(LocalPushResult(commitSHA: pushedSHA))

        let serialized = SerializedGitRepository(base: fixture.repository, localURL: fixture.rootURL)
        let result = await RepositoryPushRunner().run(
            serialized: serialized,
            repo: fixture.repoConfig,
            credentials: "pat",
            message: nil
        )

        XCTAssertEqual(result, .pushed(commitSHA: pushedSHA))
        XCTAssertEqual(fixture.repository.commitAndPushMessages, ["Update from GitSync.md"])
    }

    @MainActor
    func testRepositoryPushRunnerDefaultsEmptyMessageToStandardCommit() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main",
            commitSHA: fixture.repoInfo.commitSHA,
            changeCount: 1,
            statusEntries: [Self.stagedEntry()]
        )
        fixture.repository.commitAndPushResult = .success(LocalPushResult(commitSHA: fixture.repoInfo.commitSHA))

        let serialized = SerializedGitRepository(base: fixture.repository, localURL: fixture.rootURL)
        _ = await RepositoryPushRunner().run(
            serialized: serialized,
            repo: fixture.repoConfig,
            credentials: "pat",
            message: "   "
        )

        XCTAssertEqual(
            fixture.repository.commitAndPushMessages,
            ["Update from GitSync.md"],
            "A blank message must fall back to the default commit message"
        )
    }

    @MainActor
    func testRepositoryPushRunnerMapsNoChangesWithoutCommitting() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }

        let serialized = SerializedGitRepository(base: fixture.repository, localURL: fixture.rootURL)
        let result = await RepositoryPushRunner().run(
            serialized: serialized,
            repo: fixture.repoConfig,
            credentials: "pat",
            message: nil
        )

        XCTAssertEqual(result, .noChanges)
        XCTAssertTrue(
            fixture.repository.commitAndPushMessages.isEmpty,
            "Nothing must be committed when there are no changes"
        )
    }

    @MainActor
    func testRepositoryPushRunnerMapsAuthenticationFailure() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main",
            commitSHA: fixture.repoInfo.commitSHA,
            changeCount: 1,
            statusEntries: [Self.stagedEntry()]
        )
        fixture.repository.commitAndPushResult = .failure(LocalGitError.authenticationFailed("bad PAT"))

        let serialized = SerializedGitRepository(base: fixture.repository, localURL: fixture.rootURL)
        let result = await RepositoryPushRunner().run(
            serialized: serialized,
            repo: fixture.repoConfig,
            credentials: "pat",
            message: nil
        )

        guard case .authenticationOrTrustRequired(let message, let trustError) = result else {
            return XCTFail("Expected authenticationOrTrustRequired, got \(result)")
        }
        XCTAssertTrue(message.contains("bad PAT"))
        XCTAssertNil(trustError)
    }

    @MainActor
    func testRepositoryPushRunnerFailsWhenChangesCannotBeStaged() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main",
            commitSHA: fixture.repoInfo.commitSHA,
            changeCount: 1,
            statusEntries: [Self.unstagedEntry()]
        )
        fixture.repository.commitAndPushResult = .success(LocalPushResult(commitSHA: fixture.repoInfo.commitSHA))

        let serialized = SerializedGitRepository(base: fixture.repository, localURL: fixture.rootURL)
        let result = await RepositoryPushRunner().run(
            serialized: serialized,
            repo: fixture.repoConfig,
            credentials: "pat",
            message: nil
        )

        guard case .failed(let message) = result else {
            return XCTFail("Expected failed, got \(result)")
        }
        XCTAssertTrue(message.contains("stage"), "Failure should explain staging, got: \(message)")
        XCTAssertTrue(
            fixture.repository.commitAndPushMessages.isEmpty,
            "A half-staged tree must never be committed or pushed"
        )
    }

    @MainActor
    func testRepositoryPushRunnerBlocksActiveConflictBeforeStageAll() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.conflictSessionResult = ConflictSession(kind: .cherryPick, unmergedPaths: ["Note.md"])
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main", commitSHA: fixture.repoInfo.commitSHA, changeCount: 1,
            statusEntries: [Self.unstagedEntry()]
        )

        let result = await RepositoryPushRunner().run(
            serialized: SerializedGitRepository(base: fixture.repository, localURL: fixture.rootURL),
            repo: fixture.repoConfig, credentials: "pat", message: nil
        )

        guard case .blocked(let message) = result else { return XCTFail("Expected conflict block, got \(result)") }
        XCTAssertTrue(message.localizedCaseInsensitiveContains("conflict"))
        XCTAssertGreaterThanOrEqual(fixture.repository.conflictSessionCallCount, 1)
        XCTAssertTrue(fixture.repository.stagedPaths.isEmpty)
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)
    }

    func testRepositoryPushRunnerPreservesRealLocalGitConflictSessionWithoutStaging() async throws {
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-ConflictPushGuard")
        defer { try? FileManager.default.removeItem(at: repoURL) }
        let fileURL = repoURL.appendingPathComponent("Note.md")
        let service = LocalGitService(localURL: repoURL)
        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await service.stage(path: "Note.md")
        _ = try await service.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        let baseBranch = try await service.repoInfo().branch
        try await service.createBranch(name: "feature")
        try await service.switchBranch(name: "feature")
        try "feature\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await service.stage(path: "Note.md")
        _ = try await service.commitLocal(message: "Feature", authorName: "Tests", authorEmail: "tests@example.com")
        try await service.switchBranch(name: baseBranch)
        try "main\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await service.stage(path: "Note.md")
        let mainSHA = try await service.commitLocal(message: "Main", authorName: "Tests", authorEmail: "tests@example.com")
        do {
            _ = try await service.mergeBranch(name: "feature", authorName: "Tests", authorEmail: "tests@example.com")
        } catch {
            // Expected: the service leaves a typed conflict session for manual resolution.
        }
        let conflictBeforePush = try await service.conflictSession()
        XCTAssertTrue(conflictBeforePush.isActive)

        let repo = RepoConfig(
            repoURL: "owner/conflicted", branch: baseBranch, authorName: "Tests",
            authorEmail: "tests@example.com", vaultFolderName: repoURL.lastPathComponent
        )
        let result = await RepositoryPushRunner().run(
            serialized: SerializedGitRepository(base: service, localURL: repoURL),
            repo: repo, credentials: "", message: nil
        )

        guard case .blocked = result else { return XCTFail("Expected active conflict to block push, got \(result)") }
        let finalInfo = try await service.repoInfo()
        let conflictAfterPush = try await service.conflictSession()
        XCTAssertEqual(finalInfo.commitSHA, mainSHA)
        XCTAssertTrue(conflictAfterPush.isActive)
    }

    func testLocalGitOrdinaryStageAndCommitPreserveActiveConflictIndex() async throws {
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-ConflictStageGuard")
        defer { try? FileManager.default.removeItem(at: repoURL) }
        let fileURL = repoURL.appendingPathComponent("Note.md")
        let service = LocalGitService(localURL: repoURL)
        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await service.stage(path: "Note.md")
        _ = try await service.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        let baseBranch = try await service.repoInfo().branch
        try await service.createBranch(name: "feature")
        try await service.switchBranch(name: "feature")
        try "feature\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await service.stage(path: "Note.md")
        _ = try await service.commitLocal(message: "Feature", authorName: "Tests", authorEmail: "tests@example.com")
        try await service.switchBranch(name: baseBranch)
        try "main\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await service.stage(path: "Note.md")
        let mainSHA = try await service.commitLocal(message: "Main", authorName: "Tests", authorEmail: "tests@example.com")
        _ = try? await service.mergeBranch(name: "feature", authorName: "Tests", authorEmail: "tests@example.com")
        let before = try await service.conflictSession()
        XCTAssertTrue(before.isActive)
        XCTAssertFalse(before.unmergedPaths.isEmpty)

        do {
            try await service.stageAll()
            XCTFail("Ordinary stage-all must reject an active conflict")
        } catch {}
        do {
            _ = try await service.commitLocal(message: "Must not commit", authorName: "Tests", authorEmail: "tests@example.com")
            XCTFail("Ordinary commit must reject an active conflict")
        } catch {}

        let after = try await service.conflictSession()
        XCTAssertEqual(after.kind, before.kind)
        XCTAssertEqual(after.unmergedPaths, before.unmergedPaths)
        let finalSHA = try await service.repoInfo().commitSHA
        XCTAssertEqual(finalSHA, mainSHA)
    }

    func testLocalGitStageAllPreservesConflictIntroducedAtMutationBoundary() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-ConflictStageRace")
        defer { try? fm.removeItem(at: repoURL) }
        let noteURL = repoURL.appendingPathComponent("Note.md")
        let setup = LocalGitService(localURL: repoURL)
        try "base\n".write(to: noteURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        _ = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        let baseBranch = try await setup.repoInfo().branch
        try await setup.createBranch(name: "feature")
        try await setup.switchBranch(name: "feature")
        try "feature\n".write(to: noteURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        _ = try await setup.commitLocal(message: "Feature", authorName: "Tests", authorEmail: "tests@example.com")
        try await setup.switchBranch(name: baseBranch)
        try "main\n".write(to: noteURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let mainSHA = try await setup.commitLocal(message: "Main", authorName: "Tests", authorEmail: "tests@example.com")

        let reachedWriteBoundary = expectation(description: "stage-all reached guarded index write")
        let releaseWriteBoundary = DispatchSemaphore(value: 0)
        let guarded = LocalGitService(localURL: repoURL, stageAllBeforeWrite: {
            reachedWriteBoundary.fulfill()
            releaseWriteBoundary.wait()
        })
        let stageTask = Task { try await guarded.stageAll() }
        await fulfillment(of: [reachedWriteBoundary], timeout: 2)

        // Simulate an external Git client producing an unmerged index after the
        // runner's preflight but before ordinary staging writes. Removing the
        // merge markers leaves repository state NONE while preserving stages
        // 1/2/3, the exact race that must not be collapsed by `git add -A`.
        _ = try? await setup.mergeBranch(
            name: "feature",
            authorName: "Tests",
            authorEmail: "tests@example.com"
        )
        let gitDirectory = repoURL.appendingPathComponent(".git", isDirectory: true)
        for marker in ["MERGE_HEAD", "MERGE_MSG", "MERGE_MODE", "AUTO_MERGE"] {
            try? fm.removeItem(at: gitDirectory.appendingPathComponent(marker))
        }
        let conflictBeforeRelease = try await setup.conflictSession()
        XCTAssertTrue(conflictBeforeRelease.isActive)
        XCTAssertEqual(conflictBeforeRelease.kind, .none)
        XCTAssertFalse(conflictBeforeRelease.unmergedPaths.isEmpty)

        releaseWriteBoundary.signal()
        do {
            try await stageTask.value
            XCTFail("Stage-all must reject an index conflict introduced at the write boundary")
        } catch {}

        let conflictAfter = try await setup.conflictSession()
        XCTAssertEqual(conflictAfter.kind, .none)
        XCTAssertEqual(conflictAfter.unmergedPaths, conflictBeforeRelease.unmergedPaths)
        let finalSHA = try await setup.repoInfo().commitSHA
        XCTAssertEqual(finalSHA, mainSHA)
    }

    func testLocalGitStageAllHoldsIndexLockThroughAtomicReplacement() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-AtomicIndexLock")
        defer { try? fm.removeItem(at: repoURL) }
        let noteURL = repoURL.appendingPathComponent("Note.md")
        let setup = LocalGitService(localURL: repoURL)
        try "base\n".write(to: noteURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        _ = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        let baseBranch = try await setup.repoInfo().branch
        try await setup.createBranch(name: "feature")
        try await setup.switchBranch(name: "feature")
        try "feature\n".write(to: noteURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        _ = try await setup.commitLocal(message: "Feature", authorName: "Tests", authorEmail: "tests@example.com")
        try await setup.switchBranch(name: baseBranch)
        try "main\n".write(to: noteURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let mainSHA = try await setup.commitLocal(message: "Main", authorName: "Tests", authorEmail: "tests@example.com")

        let lockHeld = expectation(description: "stage-all holds the cross-process index lock")
        let releaseLock = DispatchSemaphore(value: 0)
        let guarded = LocalGitService(localURL: repoURL, stageAllAfterIndexLock: {
            lockHeld.fulfill()
            releaseLock.wait()
        })
        let stageTask = Task { try await guarded.stageAll() }
        await fulfillment(of: [lockHeld], timeout: 5)
        let indexLockURL = repoURL.appendingPathComponent(".git/index.lock")
        XCTAssertTrue(fm.fileExists(atPath: indexLockURL.path))

        do {
            try await setup.stageAll()
            XCTFail("A second GitSync.md index writer must not enter the held lock")
        } catch {}
        XCTAssertTrue(
            fm.fileExists(atPath: indexLockURL.path),
            "A failed lock acquisition must never unlink the owning writer's index.lock"
        )

        do {
            _ = try await setup.mergeBranch(
                name: "feature",
                authorName: "Tests",
                authorEmail: "tests@example.com"
            )
            XCTFail("An external merge must not mutate the index while staging owns index.lock")
        } catch LocalGitError.mergeConflictsDetected {
            XCTFail("The external conflict must be rejected before its index is persisted")
        } catch {
            // Expected: libgit2 honors the existing index.lock and fails closed.
        }
        let conflictWhileLocked = try await setup.conflictSession()
        XCTAssertFalse(conflictWhileLocked.isActive)

        releaseLock.signal()
        try await stageTask.value
        let finalConflict = try await setup.conflictSession()
        let finalSHA = try await setup.repoInfo().commitSHA
        XCTAssertFalse(finalConflict.isActive)
        XCTAssertEqual(finalSHA, mainSHA)
        XCTAssertFalse(fm.fileExists(atPath: indexLockURL.path))
    }

    func testLocalGitPullOnlyRollsBackWhenFinalIndexPublicationIsInterrupted() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-PullIndexPublicationFailure")
        let originURL = fm.temporaryDirectory.appendingPathComponent(
            "SyncMD-PullIndexPublicationFailure-Origin-\(UUID().uuidString)",
            isDirectory: true
        )
        defer { try? fm.removeItem(at: repoURL); try? fm.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("Note.md")
        let setup = LocalGitService(localURL: repoURL)
        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let baseSHA = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        try "remote\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let remoteSHA = try await setup.commitLocal(message: "Remote", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: remoteSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: remoteSHA)
        try checkoutHeadTree(repoURL: repoURL)
        let remoteURL = "file://localhost\(originURL.path)"
        try await setup.setRemoteURL(name: "origin", url: remoteURL)
        let service = LocalGitService(localURL: repoURL, pullOnlyBeforeIndexPublication: {
            throw LocalGitError.commitFailed("injected final index publication failure")
        })

        await XCTAssertThrowsErrorAsync(
            try await service.executePullOnly(pat: "", expectedBranch: "main")
        )

        let finalInfo = try await setup.repoInfo()
        XCTAssertEqual(finalInfo.commitSHA, baseSHA)
        XCTAssertEqual(finalInfo.changeCount, 0)
        XCTAssertEqual(try String(contentsOf: fileURL, encoding: .utf8), "base\n")
        XCTAssertFalse(fm.fileExists(atPath: repoURL.appendingPathComponent(".git/index.lock").path))
        XCTAssertEqual(try referenceTargetSHA(repositoryURL: repoURL, name: "refs/heads/main"), baseSHA)
    }

    func testLocalGitPullOnlyPreservesWriteAfterRefCommitAsUpdatedAttention() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-PullFinalCheckoutWrite")
        let originURL = fm.temporaryDirectory.appendingPathComponent(
            "SyncMD-PullFinalCheckoutWrite-Origin-\(UUID().uuidString)", isDirectory: true
        )
        defer { try? fm.removeItem(at: repoURL); try? fm.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("Note.md")
        let setup = LocalGitService(localURL: repoURL)
        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let baseSHA = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        try "remote\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let remoteSHA = try await setup.commitLocal(message: "Remote", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: remoteSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: remoteSHA)
        try checkoutHeadTree(repoURL: repoURL)
        try await setup.setRemoteURL(name: "origin", url: "file://localhost\(originURL.path)")
        let service = LocalGitService(localURL: repoURL, pullOnlyBeforeFinalCheckout: {
            try? "external write\n".write(to: fileURL, atomically: true, encoding: .utf8)
        })

        let execution = try await service.executePullOnly(pat: "", expectedBranch: "main")

        XCTAssertEqual(execution.plan.action, .fastForward)
        XCTAssertEqual(execution.pullResult?.newCommitSHA, remoteSHA)
        guard case .checkoutIncomplete = execution.pullResult?.attention else {
            return XCTFail("Expected explicit post-update checkout attention")
        }
        XCTAssertEqual(try String(contentsOf: fileURL, encoding: .utf8), "external write\n")
        let finalInfo = try await setup.repoInfo()
        XCTAssertEqual(finalInfo.commitSHA, remoteSHA)
        XCTAssertGreaterThan(finalInfo.changeCount, 0)
        XCTAssertFalse(fm.fileExists(atPath: repoURL.appendingPathComponent(".git/index.lock").path))
    }

    func testLocalGitCommitAndPushDoesNotRecreateRemoteBranchDeletedAtTransportBoundary() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-DeletedRemoteBranch")
        let originURL = fm.temporaryDirectory.appendingPathComponent(
            "SyncMD-DeletedRemoteBranch-Origin-\(UUID().uuidString)", isDirectory: true
        )
        defer { try? fm.removeItem(at: repoURL); try? fm.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("Note.md")
        let setup = LocalGitService(localURL: repoURL)
        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let baseSHA = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: baseSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: baseSHA)
        let remoteURL = "file://localhost\(originURL.path)"
        try await setup.setRemoteURL(name: "origin", url: remoteURL)
        try "publish\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let service = LocalGitService(localURL: repoURL, pushBeforeTransport: {
            try? deleteReference(repositoryURL: originURL, name: "refs/heads/main")
        })

        do {
            _ = try await service.commitAndPush(
                message: "Publish",
                authorName: "Tests",
                authorEmail: "tests@example.com",
                pat: "",
                expectedBranch: "main",
                safetyExpectation: PushSafetyExpectation(
                    branch: "main", remoteCommitSHA: baseSHA, remoteURL: remoteURL
                )
            )
            XCTFail("A deleted destination branch must never be recreated automatically")
        } catch let saved as LocalCommitSavedNotPushedError {
            XCTAssertNotEqual(saved.commitSHA, baseSHA)
        }

        XCTAssertNil(try referenceTargetSHA(repositoryURL: originURL, name: "refs/heads/main"))
    }

    func testLocalGitPushCurrentBranchRejectsOriginChangeAtTransportBoundary() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-OriginRace")
        let originURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-OriginRace-A-\(UUID().uuidString)", isDirectory: true)
        let replacementURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-OriginRace-B-\(UUID().uuidString)", isDirectory: true)
        defer {
            try? fm.removeItem(at: repoURL)
            try? fm.removeItem(at: originURL)
            try? fm.removeItem(at: replacementURL)
        }
        let fileURL = repoURL.appendingPathComponent("Note.md")
        let setup = LocalGitService(localURL: repoURL)
        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let baseSHA = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        try "ahead\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let aheadSHA = try await setup.commitLocal(message: "Ahead", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: baseSHA)
        try makeBareOrigin(at: replacementURL, copyingObjectsFrom: repoURL, headSHA: baseSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: aheadSHA, remoteSHA: baseSHA)
        let remoteURL = "file://localhost\(originURL.path)"
        let replacementRemoteURL = "file://localhost\(replacementURL.path)"
        try await setup.setRemoteURL(name: "origin", url: remoteURL)
        let service = LocalGitService(localURL: repoURL, pushBeforeTransport: {
            try? setRemoteURLDirect(repositoryURL: repoURL, name: "origin", url: replacementRemoteURL)
        })

        await XCTAssertThrowsErrorAsync(
            try await service.pushCurrentBranch(
                pat: "",
                expectedBranch: "main",
                safetyExpectation: PushSafetyExpectation(
                    branch: "main", remoteCommitSHA: baseSHA, remoteURL: remoteURL
                )
            )
        )

        XCTAssertEqual(try referenceTargetSHA(repositoryURL: originURL, name: "refs/heads/main"), baseSHA)
        XCTAssertEqual(try referenceTargetSHA(repositoryURL: replacementURL, name: "refs/heads/main"), baseSHA)
    }

    func testLocalGitCommitAndPushDoesNotOverwriteSameBranchAdvanceAtCommitBoundary() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-CommitTransactionAdvance")
        let originURL = fm.temporaryDirectory.appendingPathComponent(
            "SyncMD-CommitTransactionAdvance-Origin-\(UUID().uuidString)", isDirectory: true
        )
        defer { try? fm.removeItem(at: repoURL); try? fm.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("Note.md")
        let setup = LocalGitService(localURL: repoURL)
        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let baseSHA = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        try "concurrent\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let concurrentSHA = try await setup.commitLocal(message: "Concurrent", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: baseSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: baseSHA)
        try checkoutHeadTree(repoURL: repoURL)
        try await setup.setRemoteURL(name: "origin", url: "file://localhost\(originURL.path)")
        try "automatic\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let service = LocalGitService(localURL: repoURL, commitBeforeRefTransaction: {
            try? setLocalBranchRef(repoURL: repoURL, branch: "main", sha: concurrentSHA)
        })

        await XCTAssertThrowsErrorAsync(
            try await service.commitAndPush(
                message: "Automatic",
                authorName: "Tests",
                authorEmail: "tests@example.com",
                pat: "",
                expectedBranch: "main"
            )
        )

        XCTAssertEqual(try referenceTargetSHA(repositoryURL: repoURL, name: "refs/heads/main"), concurrentSHA)
        XCTAssertEqual(try referenceTargetSHA(repositoryURL: originURL, name: "refs/heads/main"), baseSHA)
    }

    func testLocalGitCommitAndPushPreservesSameBranchAdvanceAtTransportBoundary() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-CommitBranchAdvance")
        let originURL = fm.temporaryDirectory.appendingPathComponent(
            "SyncMD-CommitBranchAdvance-Origin-\(UUID().uuidString)", isDirectory: true
        )
        defer { try? fm.removeItem(at: repoURL); try? fm.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("Note.md")
        let setup = LocalGitService(localURL: repoURL)
        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let baseSHA = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        try "concurrent\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let concurrentSHA = try await setup.commitLocal(message: "Concurrent", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: baseSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: baseSHA)
        try checkoutHeadTree(repoURL: repoURL)
        let remoteURL = "file://localhost\(originURL.path)"
        try await setup.setRemoteURL(name: "origin", url: remoteURL)
        try "automatic\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let service = LocalGitService(localURL: repoURL, pushBeforeTransport: {
            try? setLocalBranchRef(repoURL: repoURL, branch: "main", sha: concurrentSHA)
        })

        do {
            _ = try await service.commitAndPush(
                message: "Automatic",
                authorName: "Tests",
                authorEmail: "tests@example.com",
                pat: "",
                expectedBranch: "main",
                safetyExpectation: PushSafetyExpectation(
                    branch: "main", remoteCommitSHA: baseSHA, remoteURL: remoteURL
                )
            )
            XCTFail("A concurrent same-branch advance must stop publication")
        } catch let saved as LocalCommitSavedNotPushedError {
            XCTAssertNotEqual(saved.commitSHA, baseSHA)
            XCTAssertNotEqual(saved.commitSHA, concurrentSHA)
        }

        XCTAssertEqual(try referenceTargetSHA(repositoryURL: repoURL, name: "refs/heads/main"), concurrentSHA)
        XCTAssertEqual(try referenceTargetSHA(repositoryURL: originURL, name: "refs/heads/main"), baseSHA)
    }

    func testLocalGitPushCurrentBranchPreservesSameBranchAdvanceAtTransportBoundary() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-AheadBranchAdvance")
        let originURL = fm.temporaryDirectory.appendingPathComponent(
            "SyncMD-AheadBranchAdvance-Origin-\(UUID().uuidString)", isDirectory: true
        )
        defer { try? fm.removeItem(at: repoURL); try? fm.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("Note.md")
        let setup = LocalGitService(localURL: repoURL)
        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let baseSHA = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        try "ahead\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let aheadSHA = try await setup.commitLocal(message: "Ahead", authorName: "Tests", authorEmail: "tests@example.com")
        try "concurrent\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let concurrentSHA = try await setup.commitLocal(message: "Concurrent", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: baseSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: aheadSHA, remoteSHA: baseSHA)
        try checkoutHeadTree(repoURL: repoURL)
        let remoteURL = "file://localhost\(originURL.path)"
        try await setup.setRemoteURL(name: "origin", url: remoteURL)
        let service = LocalGitService(localURL: repoURL, pushBeforeTransport: {
            try? setLocalBranchRef(repoURL: repoURL, branch: "main", sha: concurrentSHA)
        })

        await XCTAssertThrowsErrorAsync(
            try await service.pushCurrentBranch(
                pat: "",
                expectedBranch: "main",
                safetyExpectation: PushSafetyExpectation(
                    branch: "main", remoteCommitSHA: baseSHA, remoteURL: remoteURL
                )
            )
        )

        XCTAssertEqual(try referenceTargetSHA(repositoryURL: repoURL, name: "refs/heads/main"), concurrentSHA)
        XCTAssertEqual(try referenceTargetSHA(repositoryURL: originURL, name: "refs/heads/main"), baseSHA)
    }

    func testLocalGitCommitAndPushCancellationBeforeTransportSavesCommitWithoutPublishing() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-CancelCommitPush")
        let originURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-CancelCommitPush-Origin-\(UUID().uuidString)", isDirectory: true)
        defer { try? fm.removeItem(at: repoURL); try? fm.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("Note.md")
        let setup = LocalGitService(localURL: repoURL)
        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let baseSHA = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: baseSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: baseSHA)
        try await setup.setRemoteURL(name: "origin", url: "file://localhost\(originURL.path)")
        try "publish\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")

        let beforeTransport = expectation(description: "commit push reached cancellable transport gate")
        let releaseTransport = DispatchSemaphore(value: 0)
        let service = LocalGitService(localURL: repoURL, pushBeforeTransport: {
            beforeTransport.fulfill()
            releaseTransport.wait()
        })
        let push = Task {
            try await service.commitAndPush(
                message: "Publish", authorName: "Tests", authorEmail: "tests@example.com", pat: "", expectedBranch: "main"
            )
        }

        await fulfillment(of: [beforeTransport], timeout: 2)
        push.cancel()
        releaseTransport.signal()
        do {
            _ = try await push.value
            XCTFail("Expected cancellation before remote publication")
        } catch is CancellationError {}

        let localSHA = try await setup.repoInfo().commitSHA
        XCTAssertNotEqual(localSHA, baseSHA, "The locally saved commit remains retryable")
        let plan = try await setup.pullPlan(pat: "")
        XCTAssertEqual(plan.remoteCommitSHA, baseSHA)
        XCTAssertGreaterThan(plan.aheadBy, 0)
    }

    func testLocalGitPushCurrentBranchCancellationBeforeTransportDoesNotPublish() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-CancelAheadPush")
        let originURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-CancelAheadPush-Origin-\(UUID().uuidString)", isDirectory: true)
        defer { try? fm.removeItem(at: repoURL); try? fm.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("Note.md")
        let setup = LocalGitService(localURL: repoURL)
        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let baseSHA = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        try "ahead\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let aheadSHA = try await setup.commitLocal(message: "Ahead", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: baseSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: aheadSHA, remoteSHA: baseSHA)
        try await setup.setRemoteURL(name: "origin", url: "file://localhost\(originURL.path)")

        let beforeTransport = expectation(description: "ahead push reached cancellable transport gate")
        let releaseTransport = DispatchSemaphore(value: 0)
        let service = LocalGitService(localURL: repoURL, pushBeforeTransport: {
            beforeTransport.fulfill()
            releaseTransport.wait()
        })
        let push = Task { try await service.pushCurrentBranch(pat: "", expectedBranch: "main") }

        await fulfillment(of: [beforeTransport], timeout: 2)
        push.cancel()
        releaseTransport.signal()
        do {
            try await push.value
            XCTFail("Expected cancellation before ahead-branch publication")
        } catch is CancellationError {}

        let plan = try await setup.pullPlan(pat: "")
        XCTAssertEqual(plan.localCommitSHA, aheadSHA)
        XCTAssertEqual(plan.remoteCommitSHA, baseSHA)
        XCTAssertGreaterThan(plan.aheadBy, 0)
    }

    func testLocalGitCommitAndPushRemainsSuccessfulWhenOriginBecomesUnavailableAfterAcceptance() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-PushAccepted")
        let originURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-PushAccepted-Origin-\(UUID().uuidString)", isDirectory: true)
        defer { try? fm.removeItem(at: repoURL); try? fm.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("Note.md")
        let setup = LocalGitService(localURL: repoURL)
        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let baseSHA = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: baseSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: baseSHA, remoteSHA: baseSHA)
        try await setup.setRemoteURL(name: "origin", url: "file://localhost\(originURL.path)")
        let service = LocalGitService(localURL: repoURL, pushAccepted: { try? FileManager.default.removeItem(at: originURL) })
        try "publish\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await service.stage(path: "Note.md")

        let result = try await service.commitAndPush(
            message: "Publish", authorName: "Tests", authorEmail: "tests@example.com", pat: ""
        )

        XCTAssertFalse(result.commitSHA.isEmpty)
        XCTAssertFalse(fm.fileExists(atPath: originURL.path), "Hook proves any post-push verification fetch would fail")
    }

    func testLocalGitPushCurrentBranchRemainsSuccessfulWhenOriginBecomesUnavailableAfterAcceptance() async throws {
        let fm = FileManager.default
        let repoURL = try makeTemporaryGitRepository(prefix: "SyncMD-AheadPushAccepted")
        let originURL = fm.temporaryDirectory.appendingPathComponent("SyncMD-AheadPushAccepted-Origin-\(UUID().uuidString)", isDirectory: true)
        defer { try? fm.removeItem(at: repoURL); try? fm.removeItem(at: originURL) }
        let fileURL = repoURL.appendingPathComponent("Note.md")
        let setup = LocalGitService(localURL: repoURL)
        try "base\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let baseSHA = try await setup.commitLocal(message: "Base", authorName: "Tests", authorEmail: "tests@example.com")
        try "ahead\n".write(to: fileURL, atomically: true, encoding: .utf8)
        try await setup.stage(path: "Note.md")
        let aheadSHA = try await setup.commitLocal(message: "Ahead", authorName: "Tests", authorEmail: "tests@example.com")
        try makeBareOrigin(at: originURL, copyingObjectsFrom: repoURL, headSHA: baseSHA)
        try setLocalAndRemoteTrackingRefs(repoURL: repoURL, localSHA: aheadSHA, remoteSHA: baseSHA)
        try await setup.setRemoteURL(name: "origin", url: "file://localhost\(originURL.path)")
        let service = LocalGitService(localURL: repoURL, pushAccepted: { try? FileManager.default.removeItem(at: originURL) })

        try await service.pushCurrentBranch(pat: "")

        XCTAssertFalse(fm.fileExists(atPath: originURL.path), "Hook proves accepted push does not depend on a later fetch")
    }

    @MainActor
    func testManualCommitAndMergePathBlocksConflictBeforeStageAll() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.conflictSessionResult = ConflictSession(kind: .revert, unmergedPaths: ["Note.md"])
        let state = AppState(gitRepositoryFactory: { _ in fixture.repository }, loadPersistedState: false)
        state.repos = [fixture.repoConfig]

        await state.commitLocalAndAttemptMerge(repoID: fixture.repoConfig.id, message: "Do not commit conflicts")

        XCTAssertTrue(fixture.repository.stagedPaths.isEmpty)
        XCTAssertEqual(fixture.repository.commitLocalCallCount, 0)
        XCTAssertEqual(fixture.repository.mergeBranchCallCount, 0)
        XCTAssertNotNil(state.lastError)
    }

    @MainActor
    func testRepositoryPushRunnerRejectsMixedIndexAndWorktreeStateThatCannotFullyRestage() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main", commitSHA: fixture.repoInfo.commitSHA, changeCount: 1,
            statusEntries: [GitStatusEntry(path: "Note.md", indexStatus: .modified, workTreeStatus: .modified)]
        )

        let result = await RepositoryPushRunner().run(
            serialized: SerializedGitRepository(base: fixture.repository, localURL: fixture.rootURL),
            repo: fixture.repoConfig, credentials: "pat", message: nil
        )

        guard case .failed(let message) = result else { return XCTFail("Expected fail-closed staging result, got \(result)") }
        XCTAssertTrue(message.localizedCaseInsensitiveContains("stage"))
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)
    }

    @MainActor
    func testRepositoryPushRunnerFailsClosedWhenBaselineSHAIsUnavailable() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.repoInfoResults = [.failure(LocalGitError.repositoryCorrupted("baseline unavailable"))]

        let result = await RepositoryPushRunner().run(
            serialized: SerializedGitRepository(base: fixture.repository, localURL: fixture.rootURL),
            repo: fixture.repoConfig, credentials: "pat", message: nil
        )

        guard case .failed = result else { return XCTFail("Expected baseline read failure, got \(result)") }
        XCTAssertTrue(fixture.repository.stagedPaths.isEmpty)
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)
    }

    @MainActor
    func testRepositoryPushRunnerRejectsWriteArrivingDuringRemoteRevalidation() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main", commitSHA: fixture.repoInfo.commitSHA, changeCount: 1,
            statusEntries: [Self.stagedEntry()]
        )

        let result = await RepositoryPushRunner().runUnserialized(
            repository: fixture.repository,
            repo: fixture.repoConfig,
            credentials: "pat",
            message: nil
        ) { _ in
            fixture.repository.repoInfoResult = LocalRepoInfo(
                branch: "main", commitSHA: fixture.repoInfo.commitSHA, changeCount: 1,
                statusEntries: [GitStatusEntry(path: "Note.md", indexStatus: .modified, workTreeStatus: .modified)]
            )
        }

        guard case .failed(let message) = result else { return XCTFail("Expected fail-closed late-write result, got \(result)") }
        XCTAssertTrue(message.localizedCaseInsensitiveContains("stage"))
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)
    }

    @MainActor
    func testReconciliationCleanAheadRetryRevalidatesConfiguredBranch() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.pullPlanResults = [
            PullPlan(
                action: .upToDate, branch: "main", localCommitSHA: fixture.repoInfo.commitSHA,
                remoteCommitSHA: fixture.repoInfo.commitSHA, hasLocalChanges: false, aheadBy: 0, behindBy: 0
            ),
            PullPlan(
                action: .upToDate, branch: "notes", localCommitSHA: String(repeating: "a", count: 40),
                remoteCommitSHA: String(repeating: "b", count: 40), hasLocalChanges: false, aheadBy: 1, behindBy: 0
            )
        ]

        let result = await RepositoryReconciliationRunner().run(
            serialized: SerializedGitRepository(base: fixture.repository, localURL: fixture.rootURL),
            repo: fixture.repoConfig,
            credentials: "pat",
            expectedBranch: "main",
            allowsPull: true,
            allowsPush: true
        )

        XCTAssertEqual(result.outcome, .blocked)
        XCTAssertEqual(fixture.repository.pushCurrentBranchCallCount, 0)
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)
    }

    @MainActor
    func testAppStatePushOnlyPersistsCommitAndUpdatesGitState() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        let pushedSHA = "fedcba9876543210fedcba9876543210fedcba98"
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main",
            commitSHA: fixture.repoInfo.commitSHA,
            changeCount: 1,
            statusEntries: [Self.stagedEntry()]
        )
        fixture.repository.commitAndPushResult = .success(LocalPushResult(commitSHA: pushedSHA))
        let state = AppState(gitRepositoryFactory: { _ in fixture.repository }, loadPersistedState: false)
        state.repos = [fixture.repoConfig]

        let result = await state.pushOnly(repoID: fixture.repoConfig.id, message: "Update from Obsidian")

        XCTAssertEqual(result, .pushed(commitSHA: pushedSHA))
        XCTAssertEqual(fixture.repository.commitAndPushMessages, ["Update from Obsidian"])
        XCTAssertEqual(state.repo(id: fixture.repoConfig.id)?.gitState.commitSHA, pushedSHA)
        let lastSync = try XCTUnwrap(state.repo(id: fixture.repoConfig.id)?.gitState.lastSyncDate)
        XCTAssertEqual(lastSync.timeIntervalSince1970, Date().timeIntervalSince1970, accuracy: 30)
    }

    @MainActor
    func testAppStateSyncRepositoryShortCircuitsWhenPullDiverged() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.pullPlanResult = PullPlan(
            action: .diverged,
            branch: "main",
            localCommitSHA: fixture.repoConfig.gitState.commitSHA,
            remoteCommitSHA: "1111111111111111111111111111111111111111",
            hasLocalChanges: false,
            aheadBy: 2,
            behindBy: 3
        )
        fixture.repository.commitAndPushResult = .success(LocalPushResult(commitSHA: "deadbeef"))
        let state = AppState(gitRepositoryFactory: { _ in fixture.repository }, loadPersistedState: false)
        state.repos = [fixture.repoConfig]

        let result = await state.syncRepository(repoID: fixture.repoConfig.id)

        XCTAssertEqual(result.outcome, .blocked)
        XCTAssertTrue(
            fixture.repository.commitAndPushMessages.isEmpty,
            "A blocked pull must never attempt a push"
        )
        XCTAssertNil(result.push)
    }

    @MainActor
    func testAppStateSyncRepositoryPushSkipsWhenNoLocalChanges() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        // Default pull plan is up-to-date; default repo info has no changes.
        let state = AppState(gitRepositoryFactory: { _ in fixture.repository }, loadPersistedState: false)
        state.repos = [fixture.repoConfig]

        let result = await state.syncRepository(repoID: fixture.repoConfig.id)

        XCTAssertEqual(result.outcome, .pushSkipped)
        guard case .upToDate = result.pull else {
            return XCTFail("Expected up-to-date pull, got \(String(describing: result.pull))")
        }
        XCTAssertEqual(result.push, .noChanges)
    }

    @MainActor
    func testRepositoryPushRunnerPushesAheadOnlyCleanBranchOnRetry() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.pullPlanResult = PullPlan(
            action: .upToDate, branch: "main",
            localCommitSHA: fixture.repoInfo.commitSHA,
            remoteCommitSHA: String(repeating: "0", count: 40),
            hasLocalChanges: false, aheadBy: 1, behindBy: 0
        )
        let result = await RepositoryPushRunner().run(
            serialized: SerializedGitRepository(base: fixture.repository, localURL: fixture.rootURL),
            repo: fixture.repoConfig, credentials: "pat", message: nil
        )
        XCTAssertEqual(result, .pushed(commitSHA: fixture.repoInfo.commitSHA))
        XCTAssertEqual(fixture.repository.pushCurrentBranchCallCount, 1)
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)
    }

    @MainActor
    func testRepositoryPushRunnerReportsCommitSavedWhenPushFails() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        let savedSHA = String(repeating: "9", count: 40)
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main", commitSHA: fixture.repoInfo.commitSHA, changeCount: 1,
            statusEntries: [Self.stagedEntry()]
        )
        fixture.repository.commitAndPushPostFailureSHA = savedSHA
        fixture.repository.commitAndPushResult = .failure(LocalGitError.authenticationFailed("offline"))
        let result = await RepositoryPushRunner().run(
            serialized: SerializedGitRepository(base: fixture.repository, localURL: fixture.rootURL),
            repo: fixture.repoConfig, credentials: "pat", message: nil
        )
        guard case .commitSavedNotPushed(let sha, let message, _) = result else {
            return XCTFail("Expected saved local commit, got \(result)")
        }
        XCTAssertEqual(sha, savedSHA)
        XCTAssertTrue(message.contains("offline"))
    }

    /// Issue #36 retry shape at the user-facing seam: a Shortcut, x-callback,
    /// or in-app push retry after a failed publication must not report
    /// "nothing to push" while HEAD sits ahead of origin with a clean tree.
    @MainActor
    func testAppStatePushOnlyRetriesStrandedCommitWhenTreeIsClean() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.pullPlanResult = PullPlan(
            action: .upToDate, branch: "main",
            localCommitSHA: fixture.repoInfo.commitSHA,
            remoteCommitSHA: String(repeating: "0", count: 40),
            hasLocalChanges: false, aheadBy: 1, behindBy: 0
        )
        let state = AppState(gitRepositoryFactory: { _ in fixture.repository }, loadPersistedState: false)
        state.repos = [fixture.repoConfig]

        let result = await state.pushOnly(repoID: fixture.repoConfig.id, message: "Retry from Shortcut")

        XCTAssertEqual(result, .pushed(commitSHA: fixture.repoInfo.commitSHA))
        XCTAssertEqual(fixture.repository.pushCurrentBranchCallCount, 1)
        XCTAssertTrue(
            fixture.repository.commitAndPushMessages.isEmpty,
            "A clean-tree retry must push the stranded commit, not create a second one"
        )
        XCTAssertEqual(state.repo(id: fixture.repoConfig.id)?.gitState.commitSHA, fixture.repoInfo.commitSHA)
    }

    /// The stranded-commit retry must also fail loudly: when the push of the
    /// already-committed work fails (auth, network), the typed failure has to
    /// reach callers instead of a soft `.noChanges` success misreport.
    @MainActor
    func testRepositoryPushRunnerRetrySurfacesPushFailureInsteadOfNoChanges() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        fixture.repository.pullPlanResult = PullPlan(
            action: .upToDate, branch: "main",
            localCommitSHA: fixture.repoInfo.commitSHA,
            remoteCommitSHA: String(repeating: "0", count: 40),
            hasLocalChanges: false, aheadBy: 1, behindBy: 0
        )
        fixture.repository.pushCurrentBranchResult = .failure(LocalGitError.authenticationFailed("token expired"))

        let result = await RepositoryPushRunner().run(
            serialized: SerializedGitRepository(base: fixture.repository, localURL: fixture.rootURL),
            repo: fixture.repoConfig, credentials: "pat", message: nil
        )

        XCTAssertEqual(fixture.repository.pushCurrentBranchCallCount, 1)
        guard case .authenticationOrTrustRequired(let message, let trustError) = result else {
            return XCTFail("Expected the retry push failure to surface, got \(result)")
        }
        XCTAssertTrue(message.contains("token expired"))
        XCTAssertNil(trustError)
        XCTAssertFalse(result.didPush)
    }

    @MainActor
    func testBackgroundCoordinatorAutoPushRequiresSeparateConsent() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        repo.assist = RepoAssistSettings(enabled: true, selectedBranch: "main")
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main", commitSHA: fixture.repoInfo.commitSHA, changeCount: 1,
            statusEntries: [Self.stagedEntry()]
        )
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let coordinator = BackgroundSyncCoordinator(
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )

        _ = await coordinator.reconcile(repoID: repo.id)
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)

        coordinator.setAutomaticallyPushLocalChanges(true)
        let result = await coordinator.reconcile(repoID: repo.id)
        XCTAssertTrue(result.didTransferData)
        XCTAssertEqual(fixture.repository.commitAndPushMessages, ["Update from GitSync.md"])
    }

    @MainActor
    func testBackgroundCoordinatorPushOnlyPublishesWithoutRunningPullCheckout() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        repo.assist = RepoAssistSettings(enabled: true, selectedBranch: "main")
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main", commitSHA: fixture.repoInfo.commitSHA, changeCount: 1,
            statusEntries: [Self.stagedEntry()]
        )
        fixture.repository.pullPlanResult = PullPlan(
            action: .upToDate,
            branch: "main",
            localCommitSHA: fixture.repoInfo.commitSHA,
            remoteCommitSHA: fixture.repoInfo.commitSHA,
            hasLocalChanges: true,
            aheadBy: 0,
            behindBy: 0
        )
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let coordinator = BackgroundSyncCoordinator(
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        coordinator.setAutomaticallyPullRemoteChanges(false)
        coordinator.setAutomaticallyPushLocalChanges(true)

        let disposition = await coordinator.reconcile(repoID: repo.id)

        guard case .completed(let result) = disposition else { return XCTFail("Expected push-only result") }
        XCTAssertEqual(result.outcome, .pushed)
        XCTAssertNil(result.pull)
        XCTAssertEqual(fixture.repository.executePullOnlyCallCount, 0)
        XCTAssertGreaterThanOrEqual(fixture.repository.pullPlanCallCount, 1, "Push-only must still fetch and validate remote state")
        XCTAssertEqual(fixture.repository.commitAndPushMessages, ["Update from GitSync.md"])
        let safety = try XCTUnwrap(fixture.repository.commitAndPushSafetyExpectations.last ?? nil)
        XCTAssertEqual(safety.branch, "main")
        XCTAssertEqual(safety.remoteCommitSHA, fixture.repoInfo.commitSHA)
    }

    @MainActor
    func testBackgroundCoordinatorPushOnlyBlocksRemoteAheadWithoutPulling() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        repo.assist = RepoAssistSettings(enabled: true, selectedBranch: "main")
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main", commitSHA: fixture.repoInfo.commitSHA, changeCount: 1,
            statusEntries: [Self.stagedEntry()]
        )
        fixture.repository.pullPlanResult = PullPlan(
            action: .blockedByLocalChanges,
            branch: "main",
            localCommitSHA: fixture.repoInfo.commitSHA,
            remoteCommitSHA: String(repeating: "8", count: 40),
            hasLocalChanges: true,
            aheadBy: 0,
            behindBy: 1
        )
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let coordinator = BackgroundSyncCoordinator(
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        coordinator.setAutomaticallyPullRemoteChanges(false)
        coordinator.setAutomaticallyPushLocalChanges(true)

        let disposition = await coordinator.reconcile(repoID: repo.id)

        guard case .completed(let result) = disposition else { return XCTFail("Expected blocked push-only result") }
        XCTAssertEqual(result.outcome, .blocked)
        XCTAssertNil(result.pull)
        XCTAssertEqual(fixture.repository.executePullOnlyCallCount, 0)
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)
        XCTAssertEqual(fixture.repository.pushCurrentBranchCallCount, 0)
    }

    @MainActor
    func testBackgroundCoordinatorRunsNoGitOperationWhenPullAndPushAreOff() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        repo.assist = RepoAssistSettings(enabled: true, selectedBranch: "main")
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let coordinator = BackgroundSyncCoordinator(
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        coordinator.setAutomaticallyPullRemoteChanges(false)
        coordinator.setAutomaticallyPushLocalChanges(false)

        let disposition = await coordinator.reconcile(repoID: repo.id)

        XCTAssertEqual(disposition, .ignored)
        XCTAssertEqual(fixture.repository.executePullOnlyCallCount, 0)
        XCTAssertEqual(fixture.repository.pullPlanCallCount, 0)
        XCTAssertTrue(fixture.repository.stagedPaths.isEmpty)
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)
    }

    @MainActor
    func testRevokingAutomaticPullConsentCancelsCapturedPullFlight() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        repo.assist = RepoAssistSettings(enabled: true, selectedBranch: "main")
        let gate = AsyncGate()
        fixture.repository.executePullOnlyGate = gate
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let coordinator = BackgroundSyncCoordinator(
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )

        let flight = Task { @MainActor in await coordinator.reconcile(repoID: repo.id) }
        await waitUntil { fixture.repository.executePullOnlyCallCount == 1 }
        coordinator.setAutomaticallyPullRemoteChanges(false)
        await gate.open()

        let disposition = await flight.value
        guard case .deferred(let message) = disposition else {
            return XCTFail("Revoked pull consent must defer the captured flight, got \(disposition)")
        }
        XCTAssertTrue(message.localizedCaseInsensitiveContains("cancel"))
        XCTAssertEqual(provider.repo.assist.health, .never)
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)
    }

    @MainActor
    func testRevokingAutomaticPushConsentCancelsCapturedPublishingFlight() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        repo.assist = RepoAssistSettings(enabled: true, selectedBranch: "main")
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main", commitSHA: fixture.repoInfo.commitSHA, changeCount: 1,
            statusEntries: [Self.stagedEntry()]
        )
        let commitGate = AsyncGate()
        let commitStarted = expectation(description: "automatic publishing reached commit boundary")
        fixture.repository.commitAndPushGate = commitGate
        fixture.repository.commitAndPushStarted = { commitStarted.fulfill() }
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let coordinator = BackgroundSyncCoordinator(
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        coordinator.setAutomaticallyPushLocalChanges(true)

        let flight = Task { await coordinator.reconcile(repoID: repo.id) }
        await fulfillment(of: [commitStarted], timeout: 2)
        coordinator.setAutomaticallyPushLocalChanges(false)
        await commitGate.open()
        let result = await flight.value

        guard case .deferred(let message) = result else {
            return XCTFail("Revoked consent must defer the cancelled flight, got \(result)")
        }
        XCTAssertTrue(message.localizedCaseInsensitiveContains("cancel"))
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)
    }

    @MainActor
    func testBackgroundCoordinatorBlocksRemoteAheadDirtyTreeWithoutPush() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        repo.assist = RepoAssistSettings(enabled: true, selectedBranch: "main")
        fixture.repository.pullPlanResult = PullPlan(
            action: .blockedByLocalChanges, branch: "main",
            localCommitSHA: fixture.repoInfo.commitSHA,
            remoteCommitSHA: String(repeating: "8", count: 40),
            hasLocalChanges: true, aheadBy: 0, behindBy: 1
        )
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let coordinator = BackgroundSyncCoordinator(
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        coordinator.setAutomaticallyPushLocalChanges(true)

        let result = await coordinator.reconcile(repoID: repo.id)
        guard case .completed(let reconciliation) = result else { return XCTFail("Expected result") }
        XCTAssertEqual(reconciliation.outcome, .blocked)
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)
        XCTAssertEqual(fixture.repository.pushCurrentBranchCallCount, 0)
    }

    @MainActor
    func testBackgroundCoordinatorDivergenceNeverMutatesRepository() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        repo.assist = RepoAssistSettings(enabled: true, selectedBranch: "main")
        fixture.repository.pullPlanResult = PullPlan(
            action: .diverged, branch: "main",
            localCommitSHA: fixture.repoInfo.commitSHA,
            remoteCommitSHA: String(repeating: "7", count: 40),
            hasLocalChanges: true, aheadBy: 1, behindBy: 1
        )
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let coordinator = BackgroundSyncCoordinator(
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        coordinator.setAutomaticallyPushLocalChanges(true)

        let result = await coordinator.reconcile(repoID: repo.id)
        guard case .completed(let reconciliation) = result else { return XCTFail("Expected result") }
        XCTAssertEqual(reconciliation.outcome, .blocked)
        XCTAssertTrue(fixture.repository.stagedPaths.isEmpty)
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)
        XCTAssertEqual(fixture.repository.pushCurrentBranchCallCount, 0)
    }

    @MainActor
    func testBackgroundCoordinatorRevalidatesRemoteAfterStagingBeforeCommit() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        repo.assist = RepoAssistSettings(enabled: true, selectedBranch: "main")
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main", commitSHA: fixture.repoInfo.commitSHA, changeCount: 1,
            statusEntries: [Self.stagedEntry()]
        )
        let equal = PullPlan(
            action: .upToDate, branch: "main",
            localCommitSHA: fixture.repoInfo.commitSHA, remoteCommitSHA: fixture.repoInfo.commitSHA,
            hasLocalChanges: true, aheadBy: 0, behindBy: 0
        )
        let advanced = PullPlan(
            action: .blockedByLocalChanges, branch: "main",
            localCommitSHA: fixture.repoInfo.commitSHA, remoteCommitSHA: String(repeating: "6", count: 40),
            hasLocalChanges: true, aheadBy: 0, behindBy: 1
        )
        fixture.repository.pullPlanResults = [equal, advanced]
        let provider = FakeAssistRepositoryProvider(repo: repo, repository: fixture.repository)
        let coordinator = BackgroundSyncCoordinator(
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        coordinator.setAutomaticallyPushLocalChanges(true)

        let result = await coordinator.reconcile(repoID: repo.id)
        guard case .completed(let reconciliation) = result else { return XCTFail("Expected result") }
        XCTAssertEqual(reconciliation.outcome, .blocked)
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)
        XCTAssertEqual(fixture.repository.pushCurrentBranchCallCount, 0)
        XCTAssertGreaterThanOrEqual(fixture.repository.pullPlanCallCount, 2)
    }

    @MainActor
    func testAppStateSyncRepositoryEnforcesConfiguredBranchBeforePush() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        var repo = fixture.repoConfig
        repo.branch = "notes"
        fixture.repository.pullPlanResult = PullPlan(
            action: .upToDate, branch: "main", localCommitSHA: fixture.repoInfo.commitSHA,
            remoteCommitSHA: fixture.repoInfo.commitSHA, hasLocalChanges: true, aheadBy: 0, behindBy: 0
        )
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main", commitSHA: fixture.repoInfo.commitSHA, changeCount: 1,
            statusEntries: [Self.stagedEntry()]
        )
        let state = AppState(gitRepositoryFactory: { _ in fixture.repository }, loadPersistedState: false)
        state.repos = [repo]

        let result = await state.syncRepository(repoID: repo.id)

        XCTAssertEqual(result.outcome, .blocked)
        guard case .wrongBranch(let expected, let actual) = result.pull else { return XCTFail("Expected wrong-branch pull result") }
        XCTAssertEqual(expected, "notes")
        XCTAssertEqual(actual, "main")
        XCTAssertTrue(fixture.repository.stagedPaths.isEmpty)
        XCTAssertTrue(fixture.repository.commitAndPushMessages.isEmpty)
    }

    @MainActor
    func testAppStateSyncRepositoryPushesAfterCleanPull() async throws {
        let fixture = try GitFixtureFactory.make(state: .clean)
        defer { fixture.cleanup() }
        let pushedSHA = "1234567890abcdef1234567890abcdef12345678"
        fixture.repository.repoInfoResult = LocalRepoInfo(
            branch: "main",
            commitSHA: fixture.repoInfo.commitSHA,
            changeCount: 1,
            statusEntries: [Self.stagedEntry()]
        )
        fixture.repository.commitAndPushResult = .success(LocalPushResult(commitSHA: pushedSHA))
        let plannedIdentity = GitRemoteIdentity(
            fetchURL: fixture.repoConfig.repoURL + ".git",
            pushURL: fixture.repoConfig.repoURL + ".git"
        )
        fixture.repository.pullPlanResult = PullPlan(
            action: .upToDate,
            branch: "main",
            localCommitSHA: fixture.repoInfo.commitSHA,
            remoteCommitSHA: fixture.repoInfo.commitSHA,
            hasLocalChanges: true,
            aheadBy: 0,
            behindBy: 0,
            remoteIdentity: plannedIdentity
        )
        let state = AppState(gitRepositoryFactory: { _ in fixture.repository }, loadPersistedState: false)
        state.repos = [fixture.repoConfig]

        let result = await state.syncRepository(repoID: fixture.repoConfig.id, message: "Update from Obsidian")

        XCTAssertEqual(result.outcome, .synced)
        XCTAssertEqual(result.push, .pushed(commitSHA: pushedSHA))
        XCTAssertEqual(fixture.repository.commitAndPushMessages, ["Update from Obsidian"])
        XCTAssertEqual(
            fixture.repository.commitAndPushSafetyExpectations,
            [PushSafetyExpectation(
                branch: "main",
                remoteCommitSHA: fixture.repoInfo.commitSHA,
                remoteIdentity: plannedIdentity
            )]
        )
        XCTAssertEqual(state.repo(id: fixture.repoConfig.id)?.gitState.commitSHA, pushedSHA)
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

@MainActor
private final class RecordingBackgroundProcessingScheduler: PremiumBackgroundProcessingScheduling {
    private(set) var registerCount = 0
    private(set) var scheduleCount = 0
    private(set) var cancelCount = 0
    private var handler: (@MainActor (any PremiumBackgroundProcessingTask) -> Void)?

    func register(handler: @escaping @MainActor (any PremiumBackgroundProcessingTask) -> Void) {
        registerCount += 1
        self.handler = handler
    }
    func schedule() { scheduleCount += 1 }
    func cancel() { cancelCount += 1 }
    func invoke(_ task: any PremiumBackgroundProcessingTask) { handler?(task) }
}

@MainActor
private final class BackgroundTaskCompletionRecorder {
    private(set) var values: [Bool] = []
    var onComplete: ((Bool) -> Void)?
    func record(_ value: Bool) {
        values.append(value)
        onComplete?(value)
    }
}

@MainActor
private final class RecordingBackgroundProcessingTask: PremiumBackgroundProcessingTask {
    var expirationHandler: (() -> Void)?
    let recorder: BackgroundTaskCompletionRecorder
    var completion: Bool? { recorder.values.last }
    init(recorder: BackgroundTaskCompletionRecorder) { self.recorder = recorder }
    convenience init() { self.init(recorder: BackgroundTaskCompletionRecorder()) }
    func complete(success: Bool) { recorder.record(success) }
}

@MainActor
private struct PremiumRuntimeTestHarness {
    let runtime: PremiumRuntime
    let provider: FakeAssistRepositoryProvider
    let repository: FakeGitRepository
    let defaultsSuite: String

    static func make(
        defaultsSuite: String = "premium-runtime-\(UUID().uuidString)",
        repo existingRepo: RepoConfig? = nil,
        assistFeatureIsEnabled: Bool = true,
        backgroundScheduler: (any PremiumBackgroundProcessingScheduling)? = nil
    ) async -> PremiumRuntimeTestHarness {
        let defaults = UserDefaults(suiteName: defaultsSuite)!
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
            repositoryProvider: provider,
            conditionsProvider: PermissiveBackgroundSyncConditions()
        )
        let runtime = PremiumRuntime(
            coordinator: coordinator,
            repositoryProvider: provider,
            assistFeatureIsEnabled: { assistFeatureIsEnabled },
            backgroundScheduler: backgroundScheduler,
            defaults: defaults
        )
        return PremiumRuntimeTestHarness(
            runtime: runtime,
            provider: provider,
            repository: repository,
            defaultsSuite: defaultsSuite
        )
    }

    func cleanup() {
        UserDefaults.standard.removePersistentDomain(forName: defaultsSuite)
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
    /// Ordered activity lifecycle notifications: `true` = didBegin, `false` = didFinish.
    private(set) var backgroundSyncActivityEvents: [(repoID: UUID, began: Bool)] = []
    private var configurationChangeHandler: (@MainActor @Sendable () -> Void)?
    private var inventoryChangeHandler: (@MainActor @Sendable () -> Void)?
    var repo: RepoConfig {
        get { repos[0] }
        set { repos[0] = newValue }
    }
    let repository: any GitRepositoryProtocol
    private let repositoryURL = FileManager.default.temporaryDirectory.appendingPathComponent("assist-provider-\(UUID().uuidString)")
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
    func assistRepositoryInstance(id: UUID) throws -> SerializedGitRepository {
        SerializedGitRepository(base: repository, localURL: repositoryURL)
    }
    func assistCredentials(for repo: RepoConfig) -> String { "" }
    func recordAssist(result: RepositoryReconciliationResult?, health: RepoAssistHealth, repoID: UUID) {
        guard let index = repos.firstIndex(where: { $0.id == repoID }) else { return }
        if let sha = result?.finalLocalCommitSHA { repos[index].gitState.commitSHA = sha }
        repos[index].assist.health = health
    }
    func updateAssistSettings(repoID: UUID, _ settings: RepoAssistSettings) {
        guard let index = repos.firstIndex(where: { $0.id == repoID }) else { return }
        repos[index].assist = settings
    }
    func setAssistConfigurationChangeHandler(_ handler: (@MainActor @Sendable () -> Void)?) {
        configurationChangeHandler = handler
    }
    func setAssistInventoryChangeHandler(_ handler: (@MainActor @Sendable () -> Void)?) {
        inventoryChangeHandler = handler
    }
    func backgroundSyncDidBegin(repoID: UUID) {
        backgroundSyncActivityEvents.append((repoID, true))
    }
    func backgroundSyncDidFinish(repoID: UUID) {
        backgroundSyncActivityEvents.append((repoID, false))
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

private func referenceTargetSHA(repositoryURL: URL, name: String) throws -> String? {
    var repo: OpaquePointer?
    defer { if let repo { git_repository_free(repo) } }
    guard git_repository_open(&repo, repositoryURL.path) == 0 else {
        throw LocalGitError.repositoryCorrupted("Test could not open repository")
    }
    var ref: OpaquePointer?
    defer { if let ref { git_reference_free(ref) } }
    let code = git_reference_lookup(&ref, repo, name)
    if code == GIT_ENOTFOUND.rawValue { return nil }
    guard code == 0, let oid = git_reference_target(ref), let raw = git_oid_tostr_s(oid) else {
        throw LocalGitError.repositoryCorrupted("Test could not read reference target")
    }
    return String(cString: raw)
}

private func deleteReference(repositoryURL: URL, name: String) throws {
    var repo: OpaquePointer?
    defer { if let repo { git_repository_free(repo) } }
    guard git_repository_open(&repo, repositoryURL.path) == 0 else {
        throw LocalGitError.repositoryCorrupted("Test could not open repository")
    }
    var ref: OpaquePointer?
    defer { if let ref { git_reference_free(ref) } }
    let lookup = git_reference_lookup(&ref, repo, name)
    if lookup == GIT_ENOTFOUND.rawValue { return }
    guard lookup == 0, git_reference_delete(ref) == 0 else {
        throw LocalGitError.repositoryCorrupted("Test could not delete reference")
    }
}

private func setRemoteURLDirect(repositoryURL: URL, name: String, url: String) throws {
    var repo: OpaquePointer?
    defer { if let repo { git_repository_free(repo) } }
    guard git_repository_open(&repo, repositoryURL.path) == 0,
          git_remote_set_url(repo, name, url) == 0 else {
        throw LocalGitError.repositoryCorrupted("Test could not change origin URL")
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
    var repoInfoResults: [Result<LocalRepoInfo, Error>] = []
    var repoInfoCallCount = 0
    var pullPlanResult: PullPlan
    var pullPlanResults: [PullPlan] = []
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
    var conflictSessionCallCount = 0
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
    var pushCurrentBranchSafetyExpectations: [PushSafetyExpectation?] = []
    var commitAndPushResult: Result<LocalPushResult, Error>?
    var commitAndPushPostFailureSHA: String?
    var commitAndPushMessages: [String] = []
    var commitAndPushSafetyExpectations: [PushSafetyExpectation?] = []
    var commitAndPushGate: AsyncGate?
    var commitAndPushStarted: (@Sendable () -> Void)?
    var executePullOnlyGate: AsyncGate?
    var executePullOnlyStarted: (@Sendable () -> Void)?

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
        if !pullPlanResults.isEmpty { return pullPlanResults.removeFirst() }
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
        executePullOnlyStarted?()
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

    func pushCurrentBranch(
        pat: String,
        expectedBranch: String?,
        safetyExpectation: PushSafetyExpectation?
    ) async throws {
        if let expectedBranch, repoInfoResult.branch != expectedBranch {
            throw LocalGitError.wrongBranch(expected: expectedBranch, actual: repoInfoResult.branch)
        }
        didPushCurrentBranch = true
        pushCurrentBranchCallCount += 1
        pushCurrentBranchSafetyExpectations.append(safetyExpectation)
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
        conflictSessionCallCount += 1
        return conflictSessionResult
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
        pat: String,
        expectedBranch: String?,
        safetyExpectation: PushSafetyExpectation?
    ) async throws -> LocalPushResult {
        if let expectedBranch, repoInfoResult.branch != expectedBranch {
            throw LocalGitError.wrongBranch(expected: expectedBranch, actual: repoInfoResult.branch)
        }
        commitAndPushSafetyExpectations.append(safetyExpectation)
        commitAndPushStarted?()
        if let commitAndPushGate { await commitAndPushGate.wait() }
        try Task.checkCancellation()
        commitAndPushMessages.append(message)
        if let commitAndPushResult {
            switch commitAndPushResult {
            case .success(let result):
                return result
            case .failure(let error):
                if let sha = commitAndPushPostFailureSHA {
                    repoInfoResult = LocalRepoInfo(
                        branch: repoInfoResult.branch,
                        commitSHA: sha,
                        changeCount: 0,
                        statusEntries: []
                    )
                }
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
        repoInfoCallCount += 1
        if !repoInfoResults.isEmpty { return try repoInfoResults.removeFirst().get() }
        return repoInfoResult
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
    func pushCurrentBranch(pat: String, expectedBranch: String?, safetyExpectation: PushSafetyExpectation?) async throws { fatalError() }
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
    func commitAndPush(message: String, authorName: String, authorEmail: String, pat: String, expectedBranch: String?, safetyExpectation: PushSafetyExpectation?) async throws -> LocalPushResult { fatalError() }
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

import Foundation
import XCTest
@testable import Sync_md

@MainActor
final class FolderPublicationCoordinatorTests: XCTestCase {
    func testSelectionDoesNotInitializeAndPreparationPersistsReviewedPaths() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        let observedCount1 = await fixture.git.preparationCount()
        XCTAssertEqual(observedCount1, 0)
        XCTAssertEqual(fixture.coordinator.record(id: id)?.selectedPaths, ["notes.md"])
        try await fixture.prepare(id)
        let saved = try XCTUnwrap(fixture.coordinator.record(id: id))
        XCTAssertEqual(saved.phase, .prepared)
        XCTAssertEqual(saved.commitOID, Fixture.commit)
        XCTAssertEqual(saved.selectedPaths, ["notes.md"])
        let reloaded = fixture.reload()
        XCTAssertEqual(reloaded.record(id: id), saved)
        let observedCount2 = await fixture.github.creationCount()
        XCTAssertEqual(observedCount2, 0)
    }

    func testLostCreationResponseSurvivesRestartAndRequiresExplicitAdoption() async throws {
        let fixture = try Fixture(loseCreationResponse: true)
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id)
        await assertFailure { try await fixture.coordinator.publish(id: id, token: "alice-token") }
        XCTAssertEqual(fixture.coordinator.record(id: id)?.phase, .creationUnknown)
        let resumed = fixture.reload()
        await assertFailure { try await resumed.publish(id: id, token: "alice-token") }
        let observedCount3 = await fixture.github.creationCount()
        XCTAssertEqual(observedCount3, 1)
        let remote = try await resumed.reconcileCreation(id: id, token: "alice-token")
        XCTAssertNil(resumed.record(id: id)?.remote)
        XCTAssertEqual(resumed.record(id: id)?.phase, .creationUnknown)
        try await resumed.adoptRecoveredRepository(id: id, remote: remote, token: "alice-token")
        try await resumed.publish(id: id, token: "alice-token")
        XCTAssertEqual(resumed.record(id: id)?.phase, .published)
        let observedCount4 = await fixture.github.creationCount()
        XCTAssertEqual(observedCount4, 1)
        let observedCount5 = await fixture.git.publicationCount()
        XCTAssertEqual(observedCount5, 1)
    }

    func testLostPushResponseReconcilesSavedOIDWithoutPushingAgain() async throws {
        let fixture = try Fixture(losePushResponse: true)
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id)
        await assertFailure { try await fixture.coordinator.publish(id: id, token: "alice-token") }
        XCTAssertEqual(fixture.coordinator.record(id: id)?.phase, .pushUnknown)
        let resumed = fixture.reload()
        try await resumed.publish(id: id, token: "alice-token")
        XCTAssertEqual(resumed.record(id: id)?.phase, .published)
        let observedCount6 = await fixture.github.creationCount()
        XCTAssertEqual(observedCount6, 1)
        let observedCount7 = await fixture.git.publicationCount()
        XCTAssertEqual(observedCount7, 1)
        let observedCount8 = await fixture.git.preparationCount()
        XCTAssertEqual(observedCount8, 1)
    }

    func testAccountSwitchCannotRetargetPreparedPublication() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id)
        await assertFailure { try await fixture.coordinator.publish(id: id, token: "bob-token") }
        XCTAssertEqual(fixture.coordinator.record(id: id)?.phase, .prepared)
        XCTAssertEqual(fixture.coordinator.record(id: id)?.accountLogin, "alice")
        let observedCount9 = await fixture.github.creationCount()
        XCTAssertEqual(observedCount9, 0)
    }

    func testNameCollisionAllowsCorrectionWithoutAnotherCommit() async throws {
        let fixture = try Fixture(rejectName: "notes")
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id)
        await assertFailure { try await fixture.coordinator.publish(id: id, token: "alice-token") }
        XCTAssertEqual(fixture.coordinator.record(id: id)?.phase, .prepared)
        try await fixture.coordinator.updateRepositoryName(id: id, name: "new-notes")
        try await fixture.coordinator.publish(id: id, token: "alice-token")
        XCTAssertEqual(fixture.coordinator.record(id: id)?.remote?.name, "new-notes")
        let observedCount10 = await fixture.git.preparationCount()
        XCTAssertEqual(observedCount10, 1)
    }

    func testUnexpectedRemoteHistoryBlocksFirstPush() async throws {
        let fixture = try Fixture(hasHistory: true)
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id)
        await assertFailure { try await fixture.coordinator.publish(id: id, token: "alice-token") }
        XCTAssertEqual(fixture.coordinator.record(id: id)?.phase, .remoteCreated)
        let observedCount11 = await fixture.git.publicationCount()
        XCTAssertEqual(observedCount11, 0)
    }

    func testReusedLoginWithDifferentUserIDCannotPublish() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id)
        await assertFailure { try await fixture.coordinator.publish(id: id, token: "reused-alice-token") }
        let count = await fixture.github.creationCount()
        XCTAssertEqual(count, 0)
        XCTAssertEqual(fixture.coordinator.record(id: id)?.accountUserID, 1)
    }

    func testOtherImportPathsCannotRegisterUnfinishedPublicationMetadata() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id)
        let separateCoordinator = fixture.reload()
        XCTAssertThrowsError(try separateCoordinator.validateImportAvailability(at: fixture.folder))
        try await fixture.coordinator.publish(id: id, token: "alice-token")
        XCTAssertThrowsError(try separateCoordinator.validateImportAvailability(at: fixture.folder))
        try await fixture.coordinator.completeRegistration(id: id) { _, _ in }
        XCTAssertNoThrow(try separateCoordinator.validateImportAvailability(at: fixture.folder))
    }

    func testStaleCoordinatorCannotRenameAConfirmedDestination() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id)
        let stale = fixture.reload()
        try await fixture.coordinator.publish(id: id, token: "alice-token")
        await assertFailure { try await stale.updateRepositoryName(id: id, name: "different-repo") }
        let stored = try XCTUnwrap(fixture.store.load().first { $0.id == id })
        XCTAssertEqual(stored.repositoryName, "notes")
        XCTAssertEqual(stored.phase, .published)
    }

    func testRegistrationHoldsSharedGuardAcrossAwaitAndBlocksReconnection() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id)
        try await fixture.coordinator.publish(id: id, token: "alice-token")
        let published = try XCTUnwrap(fixture.coordinator.record(id: id))
        let otherCoordinator = fixture.reload()
        let entered = expectation(description: "Registration callback entered")
        let gate = FolderRegistrationGate()

        let registration = Task {
            try await fixture.coordinator.completeRegistration(id: id) { record, url in
                XCTAssertEqual(record, published)
                XCTAssertEqual(url.standardizedFileURL.resolvingSymlinksInPath().path, published.folderPath)
                entered.fulfill()
                await gate.wait()
            }
        }
        defer {
            registration.cancel()
            Task { await gate.release() }
        }
        await fulfillment(of: [entered], timeout: 2)

        do {
            try await otherCoordinator.reconnect(id: id, url: fixture.folder)
            XCTFail("A second coordinator must not replace the registration bookmark while registration awaits")
        } catch FolderPublicationError.busy {
            // The shared journal guard protects both coordinator instances.
        } catch {
            XCTFail("Expected the shared operation guard, got \(error)")
        }
        XCTAssertEqual(try fixture.store.load().first { $0.id == id }, published)

        await gate.release()
        try await registration.value
        let completed = try XCTUnwrap(fixture.store.load().first { $0.id == id })
        XCTAssertEqual(completed.phase, .completed)
        XCTAssertEqual(completed.bookmarkData, published.bookmarkData)
        XCTAssertEqual(completed.folderPath, published.folderPath)
        XCTAssertEqual(completed.remote, published.remote)
    }

    func testJournalCompareAndSwapRejectsStaleMutation() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        let original = try XCTUnwrap(fixture.coordinator.record(id: id))
        var latest = original
        latest.phase = .creatingRemote
        try fixture.store.replace(latest, expected: original)
        var stale = original
        stale.repositoryName = "another-repo"
        XCTAssertThrowsError(try fixture.store.replace(stale, expected: original))
        XCTAssertEqual(try fixture.store.load().first, latest)
    }

    func testInvalidBookmarkNeverUsesRecordedPath() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id)
        var record = try XCTUnwrap(fixture.coordinator.record(id: id))
        record.bookmarkData = Data("broken-bookmark".utf8)
        try fixture.store.save(record)
        let resumed = fixture.reload()
        await assertFailure { try await resumed.publish(id: id, token: "alice-token") }
        let observedCount12 = await fixture.github.creationCount()
        XCTAssertEqual(observedCount12, 0)
        let observedCount13 = await fixture.git.publicationCount()
        XCTAssertEqual(observedCount13, 0)
    }

    func testLoadingInterruptedIntentsPreservesJournalUntilGuardedRecovery() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id)
        var record = try XCTUnwrap(fixture.coordinator.record(id: id))
        record.phase = .creatingRemote
        try fixture.store.save(record)
        XCTAssertEqual(fixture.reload().record(id: id)?.phase, .creatingRemote)
        record.phase = .pushing
        try fixture.store.save(record)
        XCTAssertEqual(fixture.reload().record(id: id)?.phase, .pushing)
    }

    func testMalformedJournalIsPreservedAndBlocksNewWork() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let invalid = Data("{unfinished".utf8)
        try invalid.write(to: fixture.store.url)
        let resumed = fixture.reload()
        XCTAssertNotNil(resumed.loadError)
        await assertFailure { _ = try await resumed.selectFolder(url: fixture.folder) }
        XCTAssertEqual(try Data(contentsOf: fixture.store.url), invalid)
    }

    func testUnavailableExternalBookmarkPreservesStateAndCannotCloneOverFallback() async throws {
        let name = "unavailable-\(UUID().uuidString)"
        let defaultFolder = AppState.appDocumentsDirectory.appendingPathComponent(name)
        try FileManager.default.createDirectory(at: defaultFolder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: defaultFolder) }
        let sentinel = defaultFolder.appendingPathComponent("keep.txt")
        try Data("unrelated managed data".utf8).write(to: sentinel)
        let state = AppState(loadPersistedState: false)
        let record = RepoConfig(repoURL: "https://github.com/alice/notes.git", branch: "main",
            authorName: "Alice", authorEmail: "alice@example.com", vaultFolderName: name,
            customVaultBookmarkData: Data("revoked".utf8),
            gitState: GitState(commitSHA: String(repeating: "a", count: 40), treeSHA: "", branch: "main", blobSHAs: [:], lastSyncDate: .distantPast))
        state.repos = [record]
        XCTAssertFalse(state.hasLocalRepositoryAccess(repoID: record.id))
        XCTAssertNotEqual(state.vaultURL(for: record.id), defaultFolder)
        state.validateClonedRepos()
        XCTAssertEqual(state.repo(id: record.id)?.gitState.commitSHA, record.gitState.commitSHA)
        await state.clone(repoID: record.id)
        XCTAssertEqual(try Data(contentsOf: sentinel), Data("unrelated managed data".utf8))
        XCTAssertEqual(state.repo(id: record.id)?.gitState.commitSHA, record.gitState.commitSHA)
    }

    func testPublishedFolderRegistrationIsIdempotentAndExcludedFromAutomation() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        try Data("my notes".utf8).write(to: fixture.folder.appendingPathComponent("notes.md"))
        let native = FolderGitService(enforceLocalStorage: false)
        let inspection = try await native.inspect(at: fixture.folder)
        var record = FolderPublicationRecord(bookmarkData: try fixture.folder.bookmarkData(), folderName: "notes",
            folderPath: fixture.folder.standardizedFileURL.resolvingSymlinksInPath().path, files: inspection.files)
        let commit = try await native.prepare(at: fixture.folder, workflowID: record.id, files: inspection.files,
            selectedPaths: ["notes.md"], authorName: "Alice", authorEmail: "alice@example.com", message: "Initial commit")
        let remote = PublishedGitHubRepository(id: 42, owner: "alice", name: "notes", htmlURL: "https://github.com/alice/notes",
            cloneURL: "https://github.com/alice/notes.git", isPrivate: true)
        try await LocalGitService(localURL: fixture.folder).setRemoteURL(name: "origin", url: remote.cloneURL)
        record.authorName = "Alice"
        record.authorEmail = "alice@example.com"
        record.accountLogin = "alice"
        record.accountUserID = 1
        record.repositoryName = "notes"
        record.remote = remote
        record.commitOID = commit.commitOID
        record.treeOID = commit.treeOID
        record.phase = .published
        try fixture.store.save(record)
        let coordinator = fixture.reload()
        let state = AppState(reposFileURL: fixture.root.appendingPathComponent("repos.json"), loadPersistedState: false,
            folderPublicationCoordinator: coordinator)
        try await state.registerFolderPublication(id: record.id)
        try await state.registerFolderPublication(id: record.id)
        XCTAssertEqual(state.repos.count, 1)
        XCTAssertTrue(try XCTUnwrap(state.repo(id: record.id)).isExternalLocalRepository)
        XCTAssertTrue(try XCTUnwrap(state.repo(id: record.id)).assist.excludedFromAutomaticSync)
        XCTAssertFalse(try XCTUnwrap(state.repo(id: record.id)).assist.enabled)
        XCTAssertEqual(coordinator.record(id: record.id)?.phase, .completed)
        await state.removeRepo(id: record.id, deleteLocalFiles: true)
        XCTAssertEqual(try Data(contentsOf: fixture.folder.appendingPathComponent("notes.md")), Data("my notes".utf8))
    }

    func testUnavailableNewCloneLocationDoesNotDeleteExistingManagedFiles() async throws {
        let name = "managed-\(UUID().uuidString)"
        let folder = AppState.appDocumentsDirectory.appendingPathComponent(name)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let sentinel = folder.appendingPathComponent("keep.txt")
        let bytes = Data("existing local data".utf8)
        try bytes.write(to: sentinel)
        let state = AppState(loadPersistedState: false)
        let record = RepoConfig(repoURL: "https://github.com/alice/notes.git", branch: "main",
            authorName: "Alice", authorEmail: "alice@example.com", vaultFolderName: name)
        state.repos = [record]
        state.defaultSaveLocationBookmarkData = Data("revoked-default".utf8)
        await state.clone(repoID: record.id)
        XCTAssertEqual(try Data(contentsOf: sentinel), bytes)
        XCTAssertNil(state.repo(id: record.id)?.customVaultBookmarkData)
        XCTAssertTrue(state.hasLocalRepositoryAccess(repoID: record.id))
    }

    func testCloneCannotReplaceFolderWithPendingPublication() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let name = "pending-\(UUID().uuidString)"
        let folder = AppState.appDocumentsDirectory.appendingPathComponent(name)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let sentinel = folder.appendingPathComponent("keep.txt")
        let bytes = Data("reviewed local data".utf8)
        try bytes.write(to: sentinel)
        let pending = FolderPublicationRecord(bookmarkData: try folder.bookmarkData(), folderName: name,
            folderPath: folder.standardizedFileURL.resolvingSymlinksInPath().path, files: [])
        try fixture.store.save(pending)
        let state = AppState(reposFileURL: fixture.root.appendingPathComponent("repos.json"), loadPersistedState: false,
            folderPublicationCoordinator: fixture.reload())
        state.repos = [RepoConfig(repoURL: "https://github.com/alice/notes.git", branch: "main",
            authorName: "Alice", authorEmail: "alice@example.com", vaultFolderName: name)]
        await state.clone(repoID: state.repos[0].id)
        XCTAssertEqual(try Data(contentsOf: sentinel), bytes)
        XCTAssertEqual(try fixture.store.load().first, pending)
    }

    private func assertFailure(_ operation: () async throws -> Void, file: StaticString = #filePath, line: UInt = #line) async {
        do { try await operation(); XCTFail("Expected a recoverable failure", file: file, line: line) }
        catch { }
    }

    @MainActor private struct Fixture {
        static let commit = String(repeating: "a", count: 40)
        let root: URL
        let folder: URL
        let store: FolderPublicationStore
        let git: FolderGitStub
        let github: FolderGitHubStub
        let coordinator: FolderPublicationCoordinator

        init(loseCreationResponse: Bool = false, losePushResponse: Bool = false, rejectName: String? = nil, hasHistory: Bool = false) throws {
            root = FileManager.default.temporaryDirectory.appendingPathComponent("publication-\(UUID().uuidString)")
            folder = root.appendingPathComponent("notes")
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            github = FolderGitHubStub(loseCreationResponse: loseCreationResponse, rejectName: rejectName, hasHistory: hasHistory)
            git = FolderGitStub(github: github, losePushResponse: losePushResponse)
            store = FolderPublicationStore(url: root.appendingPathComponent("progress.json"))
            coordinator = FolderPublicationCoordinator(store: store, git: git, github: github, allowUnscopedAccess: true)
        }

        func prepare(_ id: UUID) async throws {
            try await coordinator.prepare(id: id, selectedPaths: ["notes.md"], authorName: "Alice", authorEmail: "alice@users.noreply.github.com",
                message: "Initial notes", accountLogin: "alice", repositoryName: "notes", token: "alice-token")
        }
        func reload() -> FolderPublicationCoordinator {
            FolderPublicationCoordinator(store: store, git: git, github: github, allowUnscopedAccess: true)
        }
        func remove() { try? FileManager.default.removeItem(at: root) }
    }
}

private actor FolderRegistrationGate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var released = false

    func wait() async {
        if released { return }
        await withCheckedContinuation { continuation = $0 }
    }

    func release() {
        released = true
        continuation?.resume()
        continuation = nil
    }
}

private actor FolderGitStub: FolderGitHandling {
    let github: FolderGitHubStub
    let losePushResponse: Bool
    var preparations = 0
    var publications = 0
    init(github: FolderGitHubStub, losePushResponse: Bool) { self.github = github; self.losePushResponse = losePushResponse }
    func inspect(at url: URL) async throws -> FolderInspection {
        FolderInspection(files: [
            FolderPublicationFile(path: "notes.md", size: 5, digest: "notes", isIgnored: false),
            FolderPublicationFile(path: ".env", size: 6, digest: "secret", isIgnored: false),
            FolderPublicationFile(path: "ignored.txt", size: 1, digest: "ignored", isIgnored: true)
        ], warnings: [])
    }
    func prepare(at url: URL, workflowID: UUID, files: [FolderPublicationFile], selectedPaths: Set<String>,
                 authorName: String, authorEmail: String, message: String) async throws -> FolderPreparedCommit {
        preparations += 1
        return FolderPreparedCommit(commitOID: String(repeating: "a", count: 40), treeOID: "tree")
    }
    func validatePrepared(at url: URL, workflowID: UUID, commitOID: String) async throws { }
    func publish(at url: URL, workflowID: UUID, commitOID: String, remoteURL: String, token: String) async throws {
        publications += 1
        await github.markPublished(commitOID)
        if losePushResponse { throw URLError(.networkConnectionLost) }
    }
    func finish(at url: URL, workflowID: UUID, commitOID: String, remoteURL: String, token: String) async throws { }
    func preparationCount() -> Int { preparations }
    func publicationCount() -> Int { publications }
}

private actor FolderGitHubStub: GitHubRepositoryPublishing {
    let loseCreationResponse: Bool
    let rejectName: String?
    let hasHistory: Bool
    var creations = 0
    var remote: PublishedGitHubRepository?
    var oid: String?
    init(loseCreationResponse: Bool, rejectName: String?, hasHistory: Bool) {
        self.loseCreationResponse = loseCreationResponse; self.rejectName = rejectName; self.hasHistory = hasHistory
    }
    func authenticatedAccount(token: String) async throws -> GitHubPublicationAccount {
        GitHubPublicationAccount(id: token == "bob-token" ? 2 : (token == "reused-alice-token" ? 99 : 1), login: token == "bob-token" ? "bob" : "alice")
    }
    func authenticatedLogin(token: String) async throws -> String { token == "bob-token" ? "bob" : "alice" }
    func createPrivateRepository(name: String, token: String) async throws -> PublishedGitHubRepository {
        creations += 1
        if name == rejectName { throw GitHubRepositoryPublicationError.validationFailed("name already exists") }
        let created = PublishedGitHubRepository(id: 42, owner: "alice", name: name,
            htmlURL: "https://github.com/alice/\(name)", cloneURL: "https://github.com/alice/\(name).git", isPrivate: true)
        remote = created
        if loseCreationResponse { throw GitHubRepositoryPublicationError.creationOutcomeUnknown("Response lost") }
        return created
    }
    func repository(owner: String, name: String, token: String) async throws -> PublishedGitHubRepository? { remote }
    func branchOID(owner: String, name: String, branch: String, token: String) async throws -> String? { oid }
    func hasAnyReferences(owner: String, name: String, token: String) async throws -> Bool { hasHistory || oid != nil }
    func markPublished(_ oid: String) { self.oid = oid }
    func creationCount() -> Int { creations }
}

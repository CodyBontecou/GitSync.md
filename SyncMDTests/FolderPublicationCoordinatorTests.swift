import Foundation
import XCTest
@testable import Sync_md

@MainActor
final class FolderPublicationCoordinatorTests: XCTestCase {
    func testSelectionDoesNotInitializeAndPreparationPersistsReviewedPaths() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        XCTAssertTrue(try XCTUnwrap(fixture.coordinator.record(id: id)).repositoryIsPrivate)
        let observedCount1 = await fixture.git.preparationCount()
        XCTAssertEqual(observedCount1, 0)
        XCTAssertEqual(fixture.coordinator.record(id: id)?.selectedPaths, ["notes.md"])
        try await fixture.prepare(id)
        let saved = try XCTUnwrap(fixture.coordinator.record(id: id))
        XCTAssertEqual(saved.phase, .prepared)
        XCTAssertEqual(saved.commitOID, Fixture.commit)
        XCTAssertEqual(saved.selectedPaths, ["notes.md"])
        XCTAssertTrue(saved.repositoryIsPrivate)
        let reloaded = fixture.reload()
        XCTAssertEqual(reloaded.record(id: id), saved)
        let observedCount2 = await fixture.github.creationCount()
        XCTAssertEqual(observedCount2, 0)
    }

    func testLegacyJournalWithoutVisibilityResumesAsPrivate() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id)
        let original = try XCTUnwrap(fixture.coordinator.record(id: id))
        var journal = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(contentsOf: fixture.store.url)) as? [[String: Any]])
        journal[0].removeValue(forKey: "requestedIsPrivate")
        try JSONSerialization.data(withJSONObject: journal).write(to: fixture.store.url)

        let resumed = fixture.reload()
        XCTAssertNil(resumed.loadError)
        let recovered = try XCTUnwrap(resumed.record(id: id))
        XCTAssertEqual(recovered.id, original.id)
        XCTAssertEqual(recovered.phase, .prepared)
        XCTAssertEqual(recovered.selectedPaths, original.selectedPaths)
        XCTAssertEqual(recovered.commitOID, original.commitOID)
        XCTAssertEqual(recovered.accountUserID, original.accountUserID)
        XCTAssertTrue(recovered.repositoryIsPrivate)
        try await resumed.publish(id: id, token: "alice-token")
        let requestedVisibilities = await fixture.github.createdVisibilities()
        XCTAssertEqual(requestedVisibilities, [true])
    }

    func testPublicReviewChoiceSurvivesRefreshPreparationAndPublicationAfterRestart() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try fixture.coordinator.updateRepositoryVisibility(id: id, isPrivate: false)
        let resumedReview = fixture.reload()
        XCTAssertFalse(try XCTUnwrap(resumedReview.record(id: id)).repositoryIsPrivate)
        try await resumedReview.refreshReview(id: id)
        XCTAssertFalse(try XCTUnwrap(resumedReview.record(id: id)).repositoryIsPrivate)
        try await fixture.prepare(id, repositoryIsPrivate: false)

        let resumedPublication = fixture.reload()
        XCTAssertFalse(try XCTUnwrap(resumedPublication.record(id: id)).repositoryIsPrivate)
        try await resumedPublication.publish(id: id, token: "alice-token")
        let saved = try XCTUnwrap(fixture.store.load().first { $0.id == id })
        XCTAssertEqual(saved.phase, .published)
        XCTAssertFalse(saved.repositoryIsPrivate)
        XCTAssertFalse(try XCTUnwrap(saved.remote).isPrivate)
        let requestedVisibilities = await fixture.github.createdVisibilities()
        XCTAssertEqual(requestedVisibilities, [false])
        let pushCount = await fixture.git.publicationCount()
        XCTAssertEqual(pushCount, 1)
    }

    func testPreparationWithoutVisibilityArgumentPreservesSavedPublicChoice() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try fixture.coordinator.updateRepositoryVisibility(id: id, isPrivate: false)

        let resumed = fixture.reload()
        try await resumed.prepare(id: id, selectedPaths: ["notes.md"], authorName: "Alice", authorEmail: "alice@users.noreply.github.com",
            message: "Initial notes", accountLogin: "alice", repositoryName: "notes", token: "alice-token")
        XCTAssertFalse(try XCTUnwrap(resumed.record(id: id)).repositoryIsPrivate)
        try await resumed.publish(id: id, token: "alice-token")
        let requestedVisibilities = await fixture.github.createdVisibilities()
        XCTAssertEqual(requestedVisibilities, [false])
        XCTAssertEqual(resumed.record(id: id)?.phase, .published)
        XCTAssertFalse(try XCTUnwrap(resumed.record(id: id)?.remote).isPrivate)
    }

    func testResumingInterruptedPreparationKeepsSavedPublicVisibility() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id, repositoryIsPrivate: false)
        var interrupted = try XCTUnwrap(fixture.coordinator.record(id: id))
        interrupted.phase = .preparing
        interrupted.commitOID = nil
        interrupted.treeOID = nil
        try fixture.store.save(interrupted)

        let resumed = fixture.reload()
        try await resumed.prepare(id: id, selectedPaths: ["notes.md"], authorName: "Alice", authorEmail: "alice@users.noreply.github.com",
            message: "Initial notes", accountLogin: "alice", repositoryName: "notes", token: "alice-token")
        XCTAssertEqual(resumed.record(id: id)?.phase, .prepared)
        XCTAssertFalse(try XCTUnwrap(resumed.record(id: id)).repositoryIsPrivate)
        try await resumed.publish(id: id, token: "alice-token")
        let requestedVisibilities = await fixture.github.createdVisibilities()
        XCTAssertEqual(requestedVisibilities, [false])
    }

    func testPreparedVisibilityCanChangeBeforeRemoteCreation() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id)
        try fixture.coordinator.updateRepositoryVisibility(id: id, isPrivate: false)
        let changed = try XCTUnwrap(fixture.reload().record(id: id))
        XCTAssertEqual(changed.phase, .prepared)
        XCTAssertFalse(changed.repositoryIsPrivate)
        XCTAssertEqual(changed.commitOID, Fixture.commit)
        try await fixture.coordinator.publish(id: id, token: "alice-token")
        let requestedVisibilities = await fixture.github.createdVisibilities()
        XCTAssertEqual(requestedVisibilities, [false])
        let preparationCount = await fixture.git.preparationCount()
        XCTAssertEqual(preparationCount, 1)
    }

    func testRefreshAfterChangedFileAndRestartKeepsUserExclusions() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let notesFolder = fixture.folder.appendingPathComponent("notes")
        try FileManager.default.createDirectory(at: notesFolder, withIntermediateDirectories: true)
        let readme = fixture.folder.appendingPathComponent("README.md")
        try Data("Reviewed README".utf8).write(to: readme)
        try Data("Unselected note".utf8).write(to: notesFolder.appendingPathComponent("sample.md"))
        try Data("TOKEN=secret".utf8).write(to: fixture.folder.appendingPathComponent(".env"))
        let native = FolderGitService(enforceLocalStorage: false)
        let coordinator = FolderPublicationCoordinator(store: fixture.store, git: native,
            github: fixture.github, allowUnscopedAccess: true)
        let id = try await coordinator.selectFolder(url: fixture.folder)
        XCTAssertEqual(Set(try XCTUnwrap(coordinator.record(id: id)).selectedPaths), ["README.md", "notes/sample.md"])

        // A stale review asks the user to refresh, but that must not revoke an
        // explicit exclusion made before attempting the local commit.
        try Data("Changed README".utf8).write(to: readme)
        do {
            try await coordinator.prepare(id: id, selectedPaths: ["README.md"],
                authorName: "Alice", authorEmail: "alice@example.com", message: "Initial notes",
                accountLogin: "alice", repositoryName: "notes", token: "alice-token")
            XCTFail("Preparing changed contents should require a new review")
        } catch FolderGitServiceError.reviewChanged(let path) {
            XCTAssertEqual(path, "README.md")
        }
        let saved = try XCTUnwrap(coordinator.record(id: id))
        XCTAssertEqual(saved.phase, .review)
        XCTAssertEqual(saved.selectedPaths, ["README.md"])
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.folder.appendingPathComponent(".git").path))

        let resumed = FolderPublicationCoordinator(store: fixture.store, git: native,
            github: fixture.github, allowUnscopedAccess: true)
        XCTAssertEqual(resumed.record(id: id)?.selectedPaths, ["README.md"])
        try await resumed.refreshReview(id: id)
        let refreshed = try XCTUnwrap(resumed.record(id: id))
        XCTAssertEqual(refreshed.selectedPaths, ["README.md"], "Refreshing must keep the unselected note excluded")
        XCTAssertNotEqual(refreshed.files.first { $0.path == "README.md" }?.digest,
            saved.files.first { $0.path == "README.md" }?.digest)
        XCTAssertEqual(try fixture.store.load().first { $0.id == id }?.selectedPaths, ["README.md"])
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.folder.appendingPathComponent(".git").path))
        let remoteCreationCount = await fixture.github.creationCount()
        XCTAssertEqual(remoteCreationCount, 0)
    }

    func testReviewChoicesSurviveRestartAndRefreshOnlyDefaultsNewEligibleFiles() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        for (path, contents) in ["README.md": "Readme", "sample.md": "Note", "obsolete.md": "Old",
                                 ".env": "TOKEN=secret", ".gitignore": "ignored.txt\n", "ignored.txt": "Ignored"] {
            try Data(contents.utf8).write(to: fixture.folder.appendingPathComponent(path))
        }
        let native = FolderGitService(enforceLocalStorage: false)
        func reload() -> FolderPublicationCoordinator {
            FolderPublicationCoordinator(store: fixture.store, git: native,
                github: fixture.github, allowUnscopedAccess: true)
        }
        let coordinator = reload()
        let id = try await coordinator.selectFolder(url: fixture.folder)
        try coordinator.updateReviewSelection(id: id, selectedPaths: [])
        let resumed = reload()
        try await resumed.refreshReview(id: id)
        XCTAssertEqual(resumed.record(id: id)?.selectedPaths, [], "An intentionally empty review must remain empty")

        try resumed.updateReviewSelection(id: id, selectedPaths: ["README.md", ".env", "obsolete.md"])
        XCTAssertEqual(Set(try XCTUnwrap(reload().record(id: id)).selectedPaths), ["README.md", ".env", "obsolete.md"])
        try FileManager.default.removeItem(at: fixture.folder.appendingPathComponent("obsolete.md"))
        try Data("New note".utf8).write(to: fixture.folder.appendingPathComponent("new.md"))
        try Data("NEW_TOKEN=secret".utf8).write(to: fixture.folder.appendingPathComponent(".env.new"))
        let refreshed = reload()
        try await refreshed.refreshReview(id: id)
        let saved = try XCTUnwrap(refreshed.record(id: id))
        XCTAssertEqual(Set(saved.selectedPaths), ["README.md", ".env", "new.md"],
            "Keep explicit sensitive-file opt-in and ordinary-file exclusions; default only new eligible paths")
        XCTAssertFalse(saved.files.contains { $0.path == "obsolete.md" })
        XCTAssertTrue(saved.files.first { $0.path == "ignored.txt" }?.isIgnored == true)
        XCTAssertThrowsError(try refreshed.updateReviewSelection(id: id, selectedPaths: ["ignored.txt"]))
        XCTAssertEqual(try fixture.store.load().first { $0.id == id }, saved)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.folder.appendingPathComponent(".git").path))
        let remoteCreationCount = await fixture.github.creationCount()
        XCTAssertEqual(remoteCreationCount, 0)
    }

    func testReviewSelectionCannotChangePreparedSnapshot() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id)
        let prepared = try XCTUnwrap(fixture.coordinator.record(id: id))
        XCTAssertThrowsError(try fixture.coordinator.updateReviewSelection(id: id, selectedPaths: []))
        XCTAssertEqual(fixture.coordinator.record(id: id), prepared)
        XCTAssertEqual(try fixture.store.load().first { $0.id == id }, prepared)
    }

    func testLostCreationResponseSurvivesRestartAndRequiresExplicitAdoption() async throws {
        for isPrivate in [true, false] {
            let fixture = try Fixture(loseCreationResponse: true)
            defer { fixture.remove() }
            let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
            try await fixture.prepare(id, repositoryIsPrivate: isPrivate)
            await assertFailure { try await fixture.coordinator.publish(id: id, token: "alice-token") }
            XCTAssertEqual(fixture.coordinator.record(id: id)?.phase, .creationUnknown)
            let resumed = fixture.reload()
            await assertFailure { try await resumed.publish(id: id, token: "alice-token") }
            let observedCount3 = await fixture.github.creationCount()
            XCTAssertEqual(observedCount3, 1)
            let remote = try await resumed.reconcileCreation(id: id, token: "alice-token")
            XCTAssertEqual(remote.isPrivate, isPrivate)
            XCTAssertNil(resumed.record(id: id)?.remote)
            XCTAssertEqual(resumed.record(id: id)?.phase, .creationUnknown)
            try await resumed.adoptRecoveredRepository(id: id, remote: remote, token: "alice-token")
            try await resumed.publish(id: id, token: "alice-token")
            XCTAssertEqual(resumed.record(id: id)?.phase, .published)
            XCTAssertEqual(resumed.record(id: id)?.repositoryIsPrivate, isPrivate)
            let observedCount4 = await fixture.github.creationCount()
            XCTAssertEqual(observedCount4, 1)
            let observedCount5 = await fixture.git.publicationCount()
            XCTAssertEqual(observedCount5, 1)
        }
    }

    func testReconciliationAndAdoptionRejectChangedVisibilityInEitherDirection() async throws {
        for isPrivate in [true, false] {
            let fixture = try Fixture(loseCreationResponse: true)
            defer { fixture.remove() }
            let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
            try await fixture.prepare(id, repositoryIsPrivate: isPrivate)
            await assertFailure { try await fixture.coordinator.publish(id: id, token: "alice-token") }
            let saved = try XCTUnwrap(fixture.coordinator.record(id: id))
            XCTAssertThrowsError(try fixture.coordinator.updateRepositoryVisibility(id: id, isPrivate: !isPrivate))
            let updatedRemote = await fixture.github.changeRemoteVisibility(isPrivate: !isPrivate)
            let changedRemote = try XCTUnwrap(updatedRemote)
            let resumed = fixture.reload()

            await assertFailure { _ = try await resumed.reconcileCreation(id: id, token: "alice-token") }
            await assertFailure { try await resumed.adoptRecoveredRepository(id: id, remote: changedRemote, token: "alice-token") }
            XCTAssertEqual(resumed.record(id: id), saved)
            XCTAssertEqual(try fixture.store.load().first { $0.id == id }, saved)
            let pushCount = await fixture.git.publicationCount()
            XCTAssertEqual(pushCount, 0)
            let creationCount = await fixture.github.creationCount()
            XCTAssertEqual(creationCount, 1)
        }
    }

    func testRetryRejectsRemoteVisibilityChangeEvenWhenPublishedCommitMatches() async throws {
        for isPrivate in [true, false] {
            let fixture = try Fixture(losePushResponse: true)
            defer { fixture.remove() }
            let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
            try await fixture.prepare(id, repositoryIsPrivate: isPrivate)
            await assertFailure { try await fixture.coordinator.publish(id: id, token: "alice-token") }
            XCTAssertEqual(fixture.coordinator.record(id: id)?.phase, .pushUnknown)
            _ = await fixture.github.changeRemoteVisibility(isPrivate: !isPrivate)
            let resumed = fixture.reload()
            await assertFailure { try await resumed.publish(id: id, token: "alice-token") }

            XCTAssertEqual(resumed.record(id: id)?.phase, .pushUnknown)
            XCTAssertEqual(resumed.record(id: id)?.repositoryIsPrivate, isPrivate)
            XCTAssertEqual(resumed.record(id: id)?.remote?.isPrivate, isPrivate)
            let pushCount = await fixture.git.publicationCount()
            XCTAssertEqual(pushCount, 1)
            let creationCount = await fixture.github.creationCount()
            XCTAssertEqual(creationCount, 1)
        }
    }

    func testVisibilityChangesAreLockedAfterCreationStarts() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
        try await fixture.prepare(id)
        var record = try XCTUnwrap(fixture.coordinator.record(id: id))
        for phase: FolderPublicationPhase in [.preparing, .creatingRemote, .creationUnknown, .remoteCreated,
                                              .pushing, .pushUnknown, .published, .completed] {
            record.phase = phase
            try fixture.store.save(record)
            let resumed = fixture.reload()
            XCTAssertThrowsError(try resumed.updateRepositoryVisibility(id: id, isPrivate: false), "Visibility must be locked in \(phase)")
            XCTAssertEqual(try fixture.store.load().first { $0.id == id }, record)
        }
    }

    func testSavedRemoteCannotContradictRequestedVisibilityDuringRecovery() async throws {
        for isPrivate in [true, false] {
            let fixture = try Fixture(losePushResponse: true)
            defer { fixture.remove() }
            let id = try await fixture.coordinator.selectFolder(url: fixture.folder)
            try await fixture.prepare(id, repositoryIsPrivate: isPrivate)
            await assertFailure { try await fixture.coordinator.publish(id: id, token: "alice-token") }
            var contradictory = try XCTUnwrap(fixture.coordinator.record(id: id))
            contradictory.repositoryIsPrivate = !isPrivate
            try fixture.store.save(contradictory)

            let resumed = fixture.reload()
            await assertFailure { try await resumed.publish(id: id, token: "alice-token") }
            XCTAssertEqual(resumed.record(id: id)?.phase, .pushUnknown)
            XCTAssertEqual(resumed.record(id: id)?.remote, contradictory.remote)
            let pushCount = await fixture.git.publicationCount()
            XCTAssertEqual(pushCount, 1)
            let creationCount = await fixture.github.creationCount()
            XCTAssertEqual(creationCount, 1)
        }
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

    func testGitTrackingRemovalRejectsEveryUnfinishedOverlappingPublication() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let path = fixture.folder.standardizedFileURL.resolvingSymlinksInPath().path
        var record = FolderPublicationRecord(bookmarkData: Data(), folderName: "notes", folderPath: path, files: [])
        let unfinished: [FolderPublicationPhase] = [.review, .preparing, .prepared, .creatingRemote,
            .creationUnknown, .remoteCreated, .pushing, .pushUnknown, .published]
        for phase in unfinished {
            record.phase = phase
            try fixture.store.save(record)
            for selected in [fixture.folder, fixture.folder.appendingPathComponent("ElCamino"), fixture.root] {
                var ran = false
                await assertFailure {
                    try await fixture.coordinator.withGitTrackingRemovalAvailability(at: selected) { ran = true }
                }
                XCTAssertFalse(ran, "\(phase) must block removal of an overlapping repository")
                XCTAssertEqual(try fixture.store.load().first, record, "Checking removal must not normalize publication intent")
            }
        }
        // Prefixes are compared at path-component boundaries.
        let sibling = URL(fileURLWithPath: path + "-other")
        let value = try await fixture.coordinator.withGitTrackingRemovalAvailability(at: sibling) { 17 }
        XCTAssertEqual(value, 17)
        record.phase = .completed
        try fixture.store.save(record)
        let completedValue = try await fixture.coordinator.withGitTrackingRemovalAvailability(at: fixture.folder) { 23 }
        XCTAssertEqual(completedValue, 23)
        XCTAssertEqual(try fixture.store.load().first, record)
    }

    func testGitTrackingRemovalChecksFreshJournalAndResolvedBookmark() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        XCTAssertTrue(fixture.coordinator.records.isEmpty)
        let record = FolderPublicationRecord(bookmarkData: try fixture.folder.bookmarkData(), folderName: "notes",
            folderPath: fixture.root.appendingPathComponent("earlier-location").path, files: [])
        try fixture.store.save(record)
        XCTAssertThrowsError(try fixture.coordinator.validateGitTrackingRemovalAvailability(at: fixture.folder))
        var ran = false
        await assertFailure {
            try await fixture.coordinator.withGitTrackingRemovalAvailability(at: fixture.folder) { ran = true }
        }
        XCTAssertFalse(ran, "A stale cached journal and an old recorded path cannot bypass the current bookmark")
        XCTAssertEqual(try fixture.store.load().first, record)
    }

    func testGitTrackingRemovalPreservesMalformedJournalAndDoesNotRunMutation() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        _ = try fixture.store.load() // Establish its parent directory.
        let invalid = Data("{unfinished".utf8)
        try invalid.write(to: fixture.store.url)
        XCTAssertThrowsError(try fixture.coordinator.validateGitTrackingRemovalAvailability(at: fixture.folder))
        var ran = false
        await assertFailure {
            try await fixture.coordinator.withGitTrackingRemovalAvailability(at: fixture.folder) { ran = true }
        }
        XCTAssertFalse(ran)
        XCTAssertFalse(fixture.coordinator.isBusy)
        XCTAssertEqual(try Data(contentsOf: fixture.store.url), invalid)
        // A failed validation must release the guard rather than strand it.
        try Data("[]".utf8).write(to: fixture.store.url)
        try await fixture.coordinator.withGitTrackingRemovalAvailability(at: fixture.folder) { ran = true }
        XCTAssertTrue(ran)
    }

    func testGitTrackingRemovalHoldsSharedPublicationGuardAcrossAwait() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        let other = fixture.reload()
        let entered = expectation(description: "Git tracking removal entered")
        let gate = FolderRegistrationGate()
        let removal = Task {
            try await fixture.coordinator.withGitTrackingRemovalAvailability(at: fixture.folder) {
                entered.fulfill()
                await gate.wait()
            }
        }
        defer {
            removal.cancel()
            Task { await gate.release() }
        }
        await fulfillment(of: [entered], timeout: 2)
        XCTAssertTrue(fixture.coordinator.isBusy)
        do {
            _ = try await other.selectFolder(url: fixture.folder)
            XCTFail("A second coordinator cannot start a publication during Git tracking removal")
        } catch FolderPublicationError.busy {} catch { XCTFail("Expected busy, got \(error)") }
        do {
            try await other.withGitTrackingRemovalAvailability(at: fixture.folder) {}
            XCTFail("A second removal must share the publication guard")
        } catch FolderPublicationError.busy {} catch { XCTFail("Expected busy, got \(error)") }
        XCTAssertTrue(try fixture.store.load().isEmpty)
        await gate.release()
        try await removal.value
        XCTAssertFalse(fixture.coordinator.isBusy)
        _ = try await other.selectFolder(url: fixture.folder)
    }

    func testGitTrackingRemovalReleasesGuardWhenMutationFailsOrIsCancelled() async throws {
        let fixture = try Fixture()
        defer { fixture.remove() }
        await assertFailure {
            try await fixture.coordinator.withGitTrackingRemovalAvailability(at: fixture.folder) {
                throw FolderPublicationError.changedHistory
            }
        }
        let other = fixture.reload()
        let entered = expectation(description: "Cancelled removal entered")
        let gate = FolderRegistrationGate()
        let removal = Task {
            try await fixture.coordinator.withGitTrackingRemovalAvailability(at: fixture.folder) {
                entered.fulfill()
                await gate.wait()
                try Task.checkCancellation()
            }
        }
        defer { Task { await gate.release() } }
        await fulfillment(of: [entered], timeout: 2)
        removal.cancel()
        await gate.release()
        do {
            try await removal.value
            XCTFail("Expected cancellation")
        } catch is CancellationError {} catch { XCTFail("Expected cancellation, got \(error)") }
        XCTAssertFalse(fixture.coordinator.isBusy)
        let value = try await other.withGitTrackingRemovalAvailability(at: fixture.folder) { "released" }
        XCTAssertEqual(value, "released")
        XCTAssertTrue(try fixture.store.load().isEmpty)
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

        func prepare(_ id: UUID, repositoryIsPrivate: Bool = true) async throws {
            try await coordinator.prepare(id: id, selectedPaths: ["notes.md"], authorName: "Alice", authorEmail: "alice@users.noreply.github.com",
                message: "Initial notes", accountLogin: "alice", repositoryName: "notes", repositoryIsPrivate: repositoryIsPrivate, token: "alice-token")
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
    var creationVisibilities: [Bool] = []
    var remote: PublishedGitHubRepository?
    var oid: String?
    init(loseCreationResponse: Bool, rejectName: String?, hasHistory: Bool) {
        self.loseCreationResponse = loseCreationResponse; self.rejectName = rejectName; self.hasHistory = hasHistory
    }
    func authenticatedAccount(token: String) async throws -> GitHubPublicationAccount {
        GitHubPublicationAccount(id: token == "bob-token" ? 2 : (token == "reused-alice-token" ? 99 : 1), login: token == "bob-token" ? "bob" : "alice")
    }
    func authenticatedLogin(token: String) async throws -> String { token == "bob-token" ? "bob" : "alice" }
    func createRepository(name: String, isPrivate: Bool, token: String) async throws -> PublishedGitHubRepository {
        creations += 1
        creationVisibilities.append(isPrivate)
        if name == rejectName { throw GitHubRepositoryPublicationError.validationFailed("name already exists") }
        let created = PublishedGitHubRepository(id: 42, owner: "alice", name: name,
            htmlURL: "https://github.com/alice/\(name)", cloneURL: "https://github.com/alice/\(name).git", isPrivate: isPrivate)
        remote = created
        if loseCreationResponse { throw GitHubRepositoryPublicationError.creationOutcomeUnknown("Response lost") }
        return created
    }
    func repository(owner: String, name: String, token: String) async throws -> PublishedGitHubRepository? { remote }
    func branchOID(owner: String, name: String, branch: String, token: String) async throws -> String? { oid }
    func hasAnyReferences(owner: String, name: String, token: String) async throws -> Bool { hasHistory || oid != nil }
    func markPublished(_ oid: String) { self.oid = oid }
    func creationCount() -> Int { creations }
    func createdVisibilities() -> [Bool] { creationVisibilities }
    func changeRemoteVisibility(isPrivate: Bool) -> PublishedGitHubRepository? {
        guard let current = remote else { return nil }
        let updated = PublishedGitHubRepository(id: current.id, owner: current.owner, name: current.name,
            htmlURL: current.htmlURL, cloneURL: current.cloneURL, isPrivate: isPrivate)
        remote = updated
        return updated
    }
}

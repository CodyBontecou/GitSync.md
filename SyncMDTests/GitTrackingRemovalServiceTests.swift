import Foundation
import Darwin
import XCTest
import Clibgit2
import libgit2
@testable import Sync_md

final class GitTrackingRemovalServiceTests: XCTestCase {
    private let service = GitTrackingRemovalService(enforceLocalStorage: false)

    override func setUp() {
        super.setUp()
        _ = git_libgit2_init()
    }

    func testRemovalPreservesNotesNestedRepositoryAndCompleteRestorableHistory() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
        let backups = fixture.appendingPathComponent("Backups", isDirectory: true)
        let original = try await prepareRepository(root)
        let nested = root.appendingPathComponent("ElCamino", isDirectory: true)
        let independent = fixture.appendingPathComponent("IndependentVault", isDirectory: true)
        _ = try await prepareRepository(independent)
        try FileManager.default.moveItem(at: independent, to: nested)
        let nestedMetadata = try regularFiles(in: nested.appendingPathComponent(".git"))
        let originalMetadata = try regularFiles(in: root.appendingPathComponent(".git"))
        let plan = try await service.inspect(at: root)
        XCTAssertEqual(plan.remoteURL, "https://github.com/example/obsidian.git")
        XCTAssertEqual(plan.branch, "main")
        XCTAssertGreaterThan(plan.metadataByteCount, 0)
        // Working-file edits after review are independent of Git metadata and
        // must remain intact without forcing their inclusion in any commit.
        try write("local edits\n", root: root, path: "Note.md")
        try write("private untracked note\n", root: root, path: "Untracked.md")

        let result = try await service.removeTracking(plan, backupDirectory: backups)
        let backupURL = try XCTUnwrap(result.backupURL)

        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "local edits\n")
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Untracked.md"), encoding: .utf8), "private untracked note\n")
        XCTAssertEqual(try regularFiles(in: nested.appendingPathComponent(".git")), nestedMetadata)
        XCTAssertEqual(try regularFiles(in: backupURL.appendingPathComponent("git-metadata")), originalMetadata)
        XCTAssertEqual(try regularFiles(in: backupURL.appendingPathComponent("original-git-metadata")), originalMetadata)
        let readme = try String(contentsOf: backupURL.appendingPathComponent("README.txt"), encoding: .utf8)
        XCTAssertTrue(readme.contains("rename it\nto .git"))
        XCTAssertTrue(readme.contains("GitHub repository was not changed"))

        // Restoring the original metadata also restores the exact commit and
        // remote configuration, while preserving edits made before detaching.
        try FileManager.default.moveItem(at: backupURL.appendingPathComponent("original-git-metadata"), to: root.appendingPathComponent(".git"))
        try await FolderGitService(enforceLocalStorage: false).validatePrepared(at: root, workflowID: original.id, commitOID: original.commit)
        let restored = try await service.inspect(at: root)
        XCTAssertEqual(restored.remoteURL, plan.remoteURL)
        XCTAssertEqual(restored.branch, plan.branch)
    }

    func testChildFolderNeverAdoptsOrMovesItsAncestorsGitDirectory() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian")
        _ = try await prepareRepository(root)
        let child = root.appendingPathComponent("ElCamino")
        try FileManager.default.createDirectory(at: child, withIntermediateDirectories: true)
        let before = try regularFiles(in: root.appendingPathComponent(".git"))

        do {
            _ = try await service.inspect(at: child)
            XCTFail("Selecting a child cannot silently detach its parent")
        } catch GitTrackingRemovalError.noGitDirectory {}

        XCTAssertEqual(try regularFiles(in: root.appendingPathComponent(".git")), before)
    }

    func testSourceChangesAfterReviewRequireAnotherReview() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian")
        _ = try await prepareRepository(root)
        let plan = try await service.inspect(at: root)
        try write("changed metadata\n", root: root, path: ".git/description")

        do {
            _ = try await service.removeTracking(plan, backupDirectory: fixture.appendingPathComponent("Backups"))
            XCTFail("Reviewed metadata must be pinned")
        } catch GitTrackingRemovalError.sourceChanged {}

        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent(".git/description"), encoding: .utf8), "changed metadata\n")
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: fixture.appendingPathComponent("Backups").path), [])
    }

    func testReplacingGitDirectoryWithIdenticalBytesIsRejected() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian")
        _ = try await prepareRepository(root)
        let plan = try await service.inspect(at: root)
        let replacement = fixture.appendingPathComponent("replacement")
        try FileManager.default.copyItem(at: root.appendingPathComponent(".git"), to: replacement)
        try FileManager.default.removeItem(at: root.appendingPathComponent(".git"))
        try FileManager.default.moveItem(at: replacement, to: root.appendingPathComponent(".git"))

        do {
            _ = try await service.removeTracking(plan, backupDirectory: fixture.appendingPathComponent("Backups"))
            XCTFail("An identical replacement is not the reviewed directory")
        } catch GitTrackingRemovalError.sourceChanged {}

        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git/HEAD").path))
    }

    func testBackupCannotBeSelectedRootOrOneOfItsDescendants() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian")
        _ = try await prepareRepository(root)
        let plan = try await service.inspect(at: root)
        let child = root.appendingPathComponent("backup")
        try FileManager.default.createDirectory(at: child, withIntermediateDirectories: true)
        for destination in [root, child] {
            do {
                _ = try await service.removeTracking(plan, backupDirectory: destination)
                XCTFail("A backup must be outside the detached repository")
            } catch GitTrackingRemovalError.backupInsideRepository {}
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git/HEAD").path))
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: child.path).isEmpty)
    }

    func testBackupCannotBeInsideAnotherGitRepository() async throws {
        for useRepositoryRoot in [true, false] {
            let fixture = try makeFixture()
            defer { try? FileManager.default.removeItem(at: fixture) }
            let root = fixture.appendingPathComponent("Obsidian")
            _ = try await prepareRepository(root)
            let other = fixture.appendingPathComponent("OtherRepository")
            _ = try await prepareRepository(other)
            let destination = useRepositoryRoot ? other : other.appendingPathComponent("Backups")
            try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
            let original = try regularFiles(in: root.appendingPathComponent(".git"))
            let otherMetadata = try regularFiles(in: other.appendingPathComponent(".git"))
            let existingItems = try FileManager.default.contentsOfDirectory(atPath: destination.path).sorted()
            let plan = try await service.inspect(at: root)

            do {
                _ = try await service.removeTracking(plan, backupDirectory: destination)
                XCTFail("Another repository can discard or upload an untracked backup")
            } catch GitTrackingRemovalError.backupInRepository {}

            XCTAssertEqual(try regularFiles(in: root.appendingPathComponent(".git")), original)
            XCTAssertEqual(try regularFiles(in: other.appendingPathComponent(".git")), otherMetadata)
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: destination.path).sorted(), existingItems)
        }
    }

    func testBackupAncestorGitPointersMalformedMetadataAndLinksFailClosed() async throws {
        for type in ["empty-directory", "malformed-file", "gitdir-pointer", "symbolic-link"] {
            for atSelectedFolder in [true, false] {
                let fixture = try makeFixture()
                defer { try? FileManager.default.removeItem(at: fixture) }
                let root = fixture.appendingPathComponent("Obsidian")
                _ = try await prepareRepository(root)
                let suspicious = fixture.appendingPathComponent("Suspicious")
                try FileManager.default.createDirectory(at: suspicious, withIntermediateDirectories: true)
                let metadata = suspicious.appendingPathComponent(".git")
                switch type {
                case "empty-directory":
                    try FileManager.default.createDirectory(at: metadata, withIntermediateDirectories: true)
                case "symbolic-link":
                    try FileManager.default.createSymbolicLink(at: metadata, withDestinationURL: root.appendingPathComponent(".git"))
                case "gitdir-pointer":
                    try "gitdir: \(root.appendingPathComponent(".git").path)\n".write(to: metadata, atomically: true, encoding: .utf8)
                default:
                    try "not valid Git metadata\n".write(to: metadata, atomically: true, encoding: .utf8)
                }
                let destination = atSelectedFolder ? suspicious : suspicious.appendingPathComponent("Backups")
                try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
                let before = try FileManager.default.contentsOfDirectory(atPath: destination.path).sorted()
                let original = try regularFiles(in: root.appendingPathComponent(".git"))
                let plan = try await service.inspect(at: root)

                do {
                    _ = try await service.removeTracking(plan, backupDirectory: destination)
                    XCTFail("A \(type) .git item cannot be treated as safe backup storage")
                } catch GitTrackingRemovalError.backupInRepository {}

                XCTAssertEqual(try regularFiles(in: root.appendingPathComponent(".git")), original)
                XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: destination.path).sorted(), before)
            }
        }
    }

    func testBackupFolderCanContainDescendantRepositoriesAndGitLookalikeFolders() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian")
        _ = try await prepareRepository(root)
        let destination = fixture.appendingPathComponent("Backups")
        let descendant = destination.appendingPathComponent("IndependentVault")
        _ = try await prepareRepository(descendant)
        let nestedMetadata = try regularFiles(in: descendant.appendingPathComponent(".git"))
        for name in ["HEAD", "objects", "refs", "config"] {
            try FileManager.default.createDirectory(at: destination.appendingPathComponent(name), withIntermediateDirectories: true)
        }
        let plan = try await service.inspect(at: root)

        let result = try await service.removeTracking(plan, backupDirectory: destination)
        let backupURL = try XCTUnwrap(result.backupURL)

        XCTAssertTrue(FileManager.default.fileExists(atPath: backupURL.appendingPathComponent("original-git-metadata/HEAD").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        XCTAssertEqual(try regularFiles(in: descendant.appendingPathComponent(".git")), nestedMetadata)
    }

    func testBackupRepositoryAppearingDuringCopyPreventsSourceMove() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian")
        _ = try await prepareRepository(root)
        let destination = fixture.appendingPathComponent("Backups")
        let original = try regularFiles(in: root.appendingPathComponent(".git"))
        let concurrent = GitTrackingRemovalService(enforceLocalStorage: false, copyMetadata: { source, copy in
            try FileManager.default.copyItem(at: source, to: copy)
            try FileManager.default.createDirectory(at: destination.appendingPathComponent(".git"), withIntermediateDirectories: true)
        })
        let plan = try await concurrent.inspect(at: root)

        do {
            _ = try await concurrent.removeTracking(plan, backupDirectory: destination)
            XCTFail("An ancestor repository introduced during copying cannot retain the original metadata")
        } catch GitTrackingRemovalError.backupInRepository {}

        XCTAssertEqual(try regularFiles(in: root.appendingPathComponent(".git")), original)
    }

    func testNestedSourceCannotDetachUntilParentRepositoryIsDetached() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let parent = fixture.appendingPathComponent("Obsidian")
        _ = try await prepareRepository(parent)
        let independent = fixture.appendingPathComponent("IndependentVault")
        _ = try await prepareRepository(independent)
        let child = parent.appendingPathComponent("ElCamino")
        try FileManager.default.moveItem(at: independent, to: child)
        let childMetadata = try regularFiles(in: child.appendingPathComponent(".git"))

        do {
            _ = try await service.inspect(at: child)
            XCTFail("Removing a nested repository's tracking exposes it to the parent's Git operations")
        } catch GitTrackingRemovalError.sourceInsideRepository {}

        let parentPlan = try await service.inspect(at: parent)
        _ = try await service.removeTracking(parentPlan, backupDirectory: fixture.appendingPathComponent("Backups"))
        XCTAssertEqual(try regularFiles(in: child.appendingPathComponent(".git")), childMetadata)
        let childPlan = try await service.inspect(at: child)
        XCTAssertEqual(childPlan.rootURL, child.standardizedFileURL)
    }

    func testNewParentGitMetadataAfterReviewPreventsSourceMove() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let parent = fixture.appendingPathComponent("Parent")
        let root = parent.appendingPathComponent("Obsidian")
        _ = try await prepareRepository(root)
        let original = try regularFiles(in: root.appendingPathComponent(".git"))
        let plan = try await service.inspect(at: root)
        try FileManager.default.createDirectory(at: parent.appendingPathComponent(".git"), withIntermediateDirectories: true)

        do {
            _ = try await service.removeTracking(plan, backupDirectory: fixture.appendingPathComponent("Backups"))
            XCTFail("A new parent repository invalidates the reviewed source scope")
        } catch GitTrackingRemovalError.sourceInsideRepository {}

        XCTAssertEqual(try regularFiles(in: root.appendingPathComponent(".git")), original)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: fixture.appendingPathComponent("Backups").path).isEmpty)
    }

    func testGitdirPointersAndSymbolicMetadataLinksAreRejected() async throws {
        for useSymlink in [false, true] {
            let fixture = try makeFixture()
            defer { try? FileManager.default.removeItem(at: fixture) }
            let original = fixture.appendingPathComponent("Original")
            _ = try await prepareRepository(original)
            let root = fixture.appendingPathComponent("Obsidian")
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let pointer = root.appendingPathComponent(".git")
            if useSymlink {
                try FileManager.default.createSymbolicLink(at: pointer, withDestinationURL: original.appendingPathComponent(".git"))
            } else {
                try "gitdir: \(original.appendingPathComponent(".git").path)\n".write(to: pointer, atomically: true, encoding: .utf8)
            }
            do {
                _ = try await service.inspect(at: root)
                XCTFail("External Git directories must not be moved")
            } catch GitTrackingRemovalError.unsupportedMetadata {}
            XCTAssertTrue(FileManager.default.fileExists(atPath: original.appendingPathComponent(".git/HEAD").path))
        }
    }

    func testLinksInsideMetadataAreRejectedWithoutFollowingTheirTargets() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian")
        _ = try await prepareRepository(root)
        let outside = fixture.appendingPathComponent("private-note.txt")
        try "unchanged\n".write(to: outside, atomically: true, encoding: .utf8)
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent(".git/outside"), withDestinationURL: outside)
        do {
            _ = try await service.inspect(at: root)
            XCTFail("A backup may not depend on linked files")
        } catch GitTrackingRemovalError.unsupportedMetadata {}
        XCTAssertEqual(try String(contentsOf: outside, encoding: .utf8), "unchanged\n")
    }

    func testWorktreesSubmodulesAndAlternateObjectStoresRequireManualHandling() async throws {
        for path in ["commondir", "gitdir", "worktrees/other/gitdir", "modules/vault/HEAD", "objects/info/alternates", "objects/info/http-alternates"] {
            let fixture = try makeFixture()
            defer { try? FileManager.default.removeItem(at: fixture) }
            let root = fixture.appendingPathComponent("Obsidian")
            _ = try await prepareRepository(root)
            try write("external\n", root: root, path: ".git/" + path)
            do {
                _ = try await service.inspect(at: root)
                XCTFail("Unsupported metadata at \(path) cannot be detached")
            } catch GitTrackingRemovalError.unsupportedMetadata {}
            XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git/" + path).path))
        }
    }

    func testRedirectedAndBareConfigurationsAreRejected() async throws {
        for addition in ["\n[core]\n\tworktree = ..\n", "\n[core]\n\tbare = true\n", "\n[include]\n\tpath = ../outside-config\n"] {
            let fixture = try makeFixture()
            defer { try? FileManager.default.removeItem(at: fixture) }
            let root = fixture.appendingPathComponent("Obsidian")
            _ = try await prepareRepository(root)
            let config = root.appendingPathComponent(".git/config")
            let text = try String(contentsOf: config, encoding: .utf8)
            try (text + addition).write(to: config, atomically: true, encoding: .utf8)
            do {
                _ = try await service.inspect(at: root)
                XCTFail("Unsafe configuration cannot be detached")
            } catch GitTrackingRemovalError.unsupportedMetadata {}
            XCTAssertEqual(try String(contentsOf: config, encoding: .utf8), text + addition)
        }
    }

    func testGitLocksBlockInspectionAndConfirmation() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian")
        _ = try await prepareRepository(root)
        let plan = try await service.inspect(at: root)
        try write("another writer\n", root: root, path: ".git/index.lock")
        do {
            _ = try await service.inspect(at: root)
            XCTFail("A locked repository cannot be reviewed")
        } catch GitTrackingRemovalError.activeGitOperation {}
        do {
            _ = try await service.removeTracking(plan, backupDirectory: fixture.appendingPathComponent("Backups"))
            XCTFail("A lock acquired after review still prevents removal")
        } catch GitTrackingRemovalError.activeGitOperation {}
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent(".git/index.lock"), encoding: .utf8), "another writer\n")
    }

    func testUnfinishedMergeOrRebaseMustFinishBeforeDetaching() async throws {
        for path in ["MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge/head-name", "rebase-apply/head-name", "sequencer/head"] {
            let fixture = try makeFixture()
            defer { try? FileManager.default.removeItem(at: fixture) }
            let root = fixture.appendingPathComponent("Obsidian")
            _ = try await prepareRepository(root)
            let plan = try await service.inspect(at: root)
            try write("pending operation\n", root: root, path: ".git/" + path)
            do {
                _ = try await service.inspect(at: root)
                XCTFail("An unfinished Git operation cannot be detached")
            } catch GitTrackingRemovalError.activeGitOperation {}
            do {
                _ = try await service.removeTracking(plan, backupDirectory: fixture.appendingPathComponent("Backups"))
                XCTFail("An operation started after review still prevents moving metadata")
            } catch GitTrackingRemovalError.sourceChanged {}
            XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git/" + path).path))
        }
    }

    func testCopyFailureOrCorruptCopyLeavesOriginalMetadataAndNotesInPlace() async throws {
        for failCopy in [true, false] {
            let fixture = try makeFixture()
            defer { try? FileManager.default.removeItem(at: fixture) }
            let root = fixture.appendingPathComponent("Obsidian")
            _ = try await prepareRepository(root)
            let original = try regularFiles(in: root.appendingPathComponent(".git"))
            let failing = GitTrackingRemovalService(enforceLocalStorage: false, copyMetadata: { source, destination in
                try FileManager.default.copyItem(at: source, to: destination)
                if failCopy { throw NSError(domain: "DeliberateCopyFailure", code: 1) }
                try Data("corruption\n".utf8).write(to: destination.appendingPathComponent("HEAD"))
            })
            let plan = try await failing.inspect(at: root)
            do {
                _ = try await failing.removeTracking(plan, backupDirectory: fixture.appendingPathComponent("Backups"))
                XCTFail("A complete and verified backup is required")
            } catch GitTrackingRemovalError.backupVerificationFailed {
                XCTAssertFalse(failCopy)
            } catch {
                XCTAssertTrue(failCopy)
            }
            XCTAssertEqual(try regularFiles(in: root.appendingPathComponent(".git")), original)
            XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
        }
    }

    func testMetadataChangeDuringCopyIsRecheckedBeforeMovingSource() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian")
        _ = try await prepareRepository(root)
        let concurrent = GitTrackingRemovalService(enforceLocalStorage: false, copyMetadata: { source, destination in
            try FileManager.default.copyItem(at: source, to: destination)
            try Data("new metadata\n".utf8).write(to: source.appendingPathComponent("description"))
        })
        let plan = try await concurrent.inspect(at: root)
        do {
            _ = try await concurrent.removeTracking(plan, backupDirectory: fixture.appendingPathComponent("Backups"))
            XCTFail("Changes made while copying must be retained at the source")
        } catch GitTrackingRemovalError.sourceChanged {}
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent(".git/description"), encoding: .utf8), "new metadata\n")
    }

    func testInsufficientStorageDoesNotCreateBackupOrMoveMetadata() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian")
        _ = try await prepareRepository(root)
        let constrained = GitTrackingRemovalService(enforceLocalStorage: false, availableCapacity: { _ in 0 })
        let plan = try await constrained.inspect(at: root)
        let backups = fixture.appendingPathComponent("Backups")
        do {
            _ = try await constrained.removeTracking(plan, backupDirectory: backups)
            XCTFail("Storage must be checked before copying")
        } catch GitTrackingRemovalError.insufficientStorage {}
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: backups.path).isEmpty)
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git/HEAD").path))
    }

    func testCancellationWhileCopyingLeavesOriginalMetadataInPlace() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian")
        _ = try await prepareRepository(root)
        let original = try regularFiles(in: root.appendingPathComponent(".git"))
        let plan = try await service.inspect(at: root)
        let copying = expectation(description: "Copy began")
        let release = DispatchSemaphore(value: 0)
        let cancellable = GitTrackingRemovalService(enforceLocalStorage: false, copyMetadata: { source, destination in
            try FileManager.default.copyItem(at: source, to: destination)
            copying.fulfill()
            release.wait()
        })
        let operation = Task { try await cancellable.removeTracking(plan, backupDirectory: fixture.appendingPathComponent("Backups")) }
        await fulfillment(of: [copying], timeout: 5)
        operation.cancel()
        release.signal()
        do {
            _ = try await operation.value
            XCTFail("Cancellation before the atomic move must retain the source")
        } catch is CancellationError {}
        XCTAssertEqual(try regularFiles(in: root.appendingPathComponent(".git")), original)
    }

    func testLocalStorageEnforcementRejectsArbitraryFixtureLocations() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian")
        _ = try await prepareRepository(root)
        do {
            _ = try await GitTrackingRemovalService().inspect(at: root)
            XCTFail("The local-storage policy cannot be disabled by folder naming")
        } catch GitTrackingRemovalError.unsupportedLocation {}
        let plan = try await service.inspect(at: root)
        do {
            _ = try await GitTrackingRemovalService().removeTracking(plan, backupDirectory: fixture.appendingPathComponent("Backups"))
            XCTFail("Confirmation must independently enforce the location policy")
        } catch GitTrackingRemovalError.unsupportedBackupLocation {}
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git/HEAD").path))
    }

    func testLocalDocumentsRepositoryAndBackupPassLocationEnforcement() async throws {
        let documents = try XCTUnwrap(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first)
        let fixture = documents.appendingPathComponent("git-tracking-removal-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: fixture.appendingPathComponent("Backups"), withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian")
        _ = try await prepareRepository(root)
        let local = GitTrackingRemovalService()
        let plan = try await local.inspect(at: root)

        let result = try await local.removeTracking(plan, backupDirectory: fixture.appendingPathComponent("Backups"))
        let backupURL = try XCTUnwrap(result.backupURL)

        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: backupURL.appendingPathComponent("git-metadata/HEAD").path))
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
    }

    func testDownloadsProviderBackupOffersActionableErrorWithoutMovingMetadata() async throws {
        let documents = try XCTUnwrap(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first)
        let root = documents.appendingPathComponent("git-tracking-removal-source-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        _ = try await prepareRepository(root)
        let originalMetadata = try regularFiles(in: root.appendingPathComponent(".git"))
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        // Files exposes Apple's local Downloads directory through an app-group
        // file-provider path even when it is selected under On My iPhone.
        let downloads = fixture.appendingPathComponent(
            "var/mobile/Containers/Shared/AppGroup/\(UUID().uuidString)/File Provider Storage/Downloads",
            isDirectory: true
        )
        try FileManager.default.createDirectory(at: downloads, withIntermediateDirectories: true)
        let local = GitTrackingRemovalService()
        let plan = try await local.inspect(at: root)

        for preflight in [true, false] {
            do {
                if preflight {
                    try await local.validateBackupDirectory(downloads, for: plan)
                } else {
                    _ = try await local.removeTracking(plan, backupDirectory: downloads)
                }
                XCTFail("An unverified provider backup must fail before metadata is moved")
            } catch GitTrackingRemovalError.unsupportedBackupLocation {
                let message = try XCTUnwrap(GitTrackingRemovalError.unsupportedBackupLocation.errorDescription)
                XCTAssertTrue(message.contains("GitSync.md"))
                XCTAssertFalse(message.contains("Choose a local On My iPhone folder"))
            }
            XCTAssertEqual(try regularFiles(in: root.appendingPathComponent(".git")), originalMetadata)
            XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: downloads.path).isEmpty)
            XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
        }
    }

    func testAppBackupFolderCanBeReusedAndCompletesVerifiedRemoval() async throws {
        let documents = try XCTUnwrap(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first)
        let fixture = documents.appendingPathComponent("git-tracking-removal-app-backup-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: fixture, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
        _ = try await prepareRepository(root)
        let originalMetadata = try regularFiles(in: root.appendingPathComponent(".git"))
        let local = GitTrackingRemovalService()
        let backups = try await local.prepareAppBackupDirectory(in: fixture)
        XCTAssertEqual(backups, fixture.appendingPathComponent("Git Backups", isDirectory: true))
        try write("previous backup contents\n", root: backups, path: "Existing Backup/README.txt")
        let reused = try await local.prepareAppBackupDirectory(in: fixture)
        XCTAssertEqual(reused, backups)
        XCTAssertEqual(try String(contentsOf: backups.appendingPathComponent("Existing Backup/README.txt"), encoding: .utf8), "previous backup contents\n")
        let plan = try await local.inspect(at: root)
        let before = try FileManager.default.contentsOfDirectory(atPath: backups.path).sorted()

        try await local.validateBackupDirectory(backups, for: plan)

        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: backups.path).sorted(), before)
        XCTAssertEqual(try regularFiles(in: root.appendingPathComponent(".git")), originalMetadata)
        let result = try await local.removeTracking(plan, backupDirectory: backups)
        let backupURL = try XCTUnwrap(result.backupURL)

        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        XCTAssertEqual(try regularFiles(in: backupURL.appendingPathComponent("git-metadata")), originalMetadata)
        XCTAssertEqual(try regularFiles(in: backupURL.appendingPathComponent("original-git-metadata")), originalMetadata)
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
        XCTAssertEqual(try String(contentsOf: backups.appendingPathComponent("Existing Backup/README.txt"), encoding: .utf8), "previous backup contents\n")
    }

    func testAppBackupFolderRejectsSymbolicLinkWithoutWritingTarget() async throws {
        let documents = try XCTUnwrap(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first)
        let fixture = documents.appendingPathComponent("git-tracking-removal-app-backup-link-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: fixture, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: fixture) }
        let target = try makeFixture()
        defer { try? FileManager.default.removeItem(at: target) }
        try write("preserved\n", root: target, path: "existing.txt")
        let contents = try regularFiles(in: target)
        let before = try FileManager.default.contentsOfDirectory(atPath: target.path).sorted()
        try FileManager.default.createSymbolicLink(at: fixture.appendingPathComponent("Git Backups"), withDestinationURL: target)

        do {
            _ = try await GitTrackingRemovalService().prepareAppBackupDirectory(in: fixture)
            XCTFail("The default backup folder cannot redirect through a link")
        } catch GitTrackingRemovalError.unsupportedBackupLocation {}

        XCTAssertEqual(try regularFiles(in: target), contents)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: target.path).sorted(), before)
        XCTAssertEqual(try FileManager.default.destinationOfSymbolicLink(atPath: fixture.appendingPathComponent("Git Backups").path), target.path)
    }

    func testUnchangedProtectedMetadataCompletesAppBackupWithoutFalseSourceChanged() async throws {
        let documents = try XCTUnwrap(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first)
        let fixture = documents.appendingPathComponent("git-tracking-removal-protected-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: fixture, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
        _ = try await prepareRepository(root)
        let metadata = root.appendingPathComponent(".git", isDirectory: true)
        let enumerator = try XCTUnwrap(FileManager.default.enumerator(at: metadata, includingPropertiesForKeys: [.isRegularFileKey]))
        for case let file as URL in enumerator {
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: file.path)
            if try file.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true {
                try FileManager.default.setAttributes([.posixPermissions: 0o444], ofItemAtPath: file.path)
            }
        }
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: metadata.path)
        let original = try regularFiles(in: metadata)
        let local = GitTrackingRemovalService()
        let backups = try await local.prepareAppBackupDirectory(in: fixture)
        let plan = try await local.inspect(at: root)
        try await local.validateBackupDirectory(backups, for: plan)

        let result = try await local.removeTracking(plan, backupDirectory: backups)
        let backupURL = try XCTUnwrap(result.backupURL)

        XCTAssertFalse(FileManager.default.fileExists(atPath: metadata.path))
        XCTAssertEqual(try regularFiles(in: backupURL.appendingPathComponent("git-metadata")), original)
        XCTAssertEqual(try regularFiles(in: backupURL.appendingPathComponent("original-git-metadata")), original)
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
    }

    func testRepositoryStatusRefreshKeepsReviewedMetadataValidForRemoval() async throws {
        let documents = try XCTUnwrap(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first)
        let fixture = documents.appendingPathComponent("git-tracking-removal-status-refresh-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: fixture, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
        _ = try await prepareRepository(root)
        let local = GitTrackingRemovalService()
        let backups = try await local.prepareAppBackupDirectory(in: fixture)
        let plan = try await local.inspect(at: root)
        let configuration = root.appendingPathComponent(".git/config")
        let configurationBytes = try Data(contentsOf: configuration)
        var before = stat()
        XCTAssertEqual(lstat(configuration.path, &before), 0)

        let information = try await LocalGitService(localURL: root).repoInfo()

        XCTAssertEqual(information.branch, "main")
        XCTAssertEqual(try Data(contentsOf: configuration), configurationBytes)
        var after = stat()
        XCTAssertEqual(lstat(configuration.path, &after), 0)
        XCTAssertEqual(after.st_ino, before.st_ino, "Refreshing status must not replace reviewed Git configuration")
        XCTAssertEqual(after.st_ctimespec.tv_sec, before.st_ctimespec.tv_sec, "Refreshing status must not modify reviewed Git configuration")
        XCTAssertEqual(after.st_ctimespec.tv_nsec, before.st_ctimespec.tv_nsec, "Refreshing status must not modify reviewed Git configuration")
        try await local.validateBackupDirectory(backups, for: plan)
        let result = try await local.removeTracking(plan, backupDirectory: backups)
        let backupURL = try XCTUnwrap(result.backupURL)

        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: backupURL.appendingPathComponent("original-git-metadata/HEAD").path))
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
    }

    func testLegacyRepositoryStatusRefreshKeepsReviewedMetadataValidForRemoval() async throws {
        let documents = try XCTUnwrap(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first)
        let fixture = documents.appendingPathComponent("git-tracking-removal-legacy-status-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: fixture, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
        _ = try await prepareRepository(root)
        let configuration = root.appendingPathComponent(".git/config")
        do {
            var config: OpaquePointer?
            defer { if let config { git_config_free(config) } }
            try check(git_config_open_ondisk(&config, configuration.path))
            try check(git_config_delete_entry(config, "core.precomposeunicode"))
        }
        let local = GitTrackingRemovalService()
        let backups = try await local.prepareAppBackupDirectory(in: fixture)
        let plan = try await local.inspect(at: root)
        let configurationBytes = try Data(contentsOf: configuration)

        let information = try await LocalGitService(localURL: root).repoInfo()

        XCTAssertEqual(information.branch, "main")
        XCTAssertEqual(try Data(contentsOf: configuration), configurationBytes, "Reading legacy repository status must not rewrite reviewed Git configuration")
        try await local.validateBackupDirectory(backups, for: plan)
        let result = try await local.removeTracking(plan, backupDirectory: backups)
        let backupURL = try XCTUnwrap(result.backupURL)

        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: backupURL.appendingPathComponent("original-git-metadata/HEAD").path))
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
    }

    func testUnicodeStatusReadsStayCleanAndPreserveNoncanonicalLegacyConfiguration() async throws {
        let variants: [String?] = ["yes", "false", nil]
        for setting in variants {
            let documents = try XCTUnwrap(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first)
            let fixture = documents.appendingPathComponent("git-tracking-removal-unicode-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: fixture, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: fixture) }
            let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let physicalPath = "Cafe\u{301}.md"
            let treePath = "Caf\u{e9}.md"
            let originalBytes = Data("# Caf\u{e9}\n".utf8)
            let note = root.appendingPathComponent(physicalPath)
            try originalBytes.write(to: note)
            let physicalName = try XCTUnwrap(FileManager.default.contentsOfDirectory(atPath: root.path).first)
            XCTAssertEqual(Array(physicalName.utf8), Array(physicalPath.utf8), "The fixture must use a decomposed physical filename")
            let git = FolderGitService(enforceLocalStorage: false)
            let inspection = try await git.inspect(at: root)
            XCTAssertEqual(Array(try XCTUnwrap(inspection.files.first).path.utf8), Array(treePath.utf8))
            _ = try await git.prepare(at: root, workflowID: UUID(), files: inspection.files, selectedPaths: [treePath],
                                      authorName: "Tests", authorEmail: "tests@example.com", message: "Unicode commit")
            let configuration = root.appendingPathComponent(".git/config")
            do {
                var config: OpaquePointer?
                defer { if let config { git_config_free(config) } }
                try check(git_config_open_ondisk(&config, configuration.path))
                if let setting { try check(git_config_set_string(config, "core.precomposeunicode", setting)) }
                else { try check(git_config_delete_entry(config, "core.precomposeunicode")) }
            }
            let local = GitTrackingRemovalService()
            let backups = try await local.prepareAppBackupDirectory(in: fixture)
            let plan = try await local.inspect(at: root)
            let originalMetadata = try regularFiles(in: root.appendingPathComponent(".git"))
            let reader = LocalGitService(localURL: root)

            let clean = try await reader.repoInfo()

            XCTAssertEqual(clean.changeCount, 0, "Unchanged NFC/NFD note must stay clean for \(setting ?? "absent") configuration")
            XCTAssertTrue(clean.statusEntries.isEmpty)
            // A dirty control proves status enumeration is functioning rather
            // than returning an empty result after an internal error.
            try Data("# Changed Caf\u{e9}\n".utf8).write(to: note)
            let dirty = try await reader.repoInfo()
            XCTAssertEqual(dirty.changeCount, 1)
            XCTAssertEqual(dirty.statusEntries.map(\.path), [treePath])
            XCTAssertEqual(Array(try XCTUnwrap(dirty.statusEntries.first).path.utf8), Array(treePath.utf8))
            try originalBytes.write(to: note)
            let cleanAgain = try await reader.repoInfo()
            XCTAssertEqual(cleanAgain.changeCount, 0)
            XCTAssertEqual(try regularFiles(in: root.appendingPathComponent(".git")), originalMetadata, "Status reads must preserve every metadata file for \(setting ?? "absent") configuration")
            try await local.validateBackupDirectory(backups, for: plan)
            let result = try await local.removeTracking(plan, backupDirectory: backups)
            let backupURL = try XCTUnwrap(result.backupURL)

            XCTAssertEqual(try Data(contentsOf: note), originalBytes)
            XCTAssertEqual(try regularFiles(in: backupURL.appendingPathComponent("original-git-metadata")), originalMetadata)
            XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        }
    }

    func testMovedMetadataAllowsOnlyDescendantStatusChangeTimeToChange() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
        _ = try await prepareRepository(root)
        let metadata = root.appendingPathComponent(".git", isDirectory: true)
        try FileManager.default.setAttributes([.posixPermissions: 0o444], ofItemAtPath: metadata.appendingPathComponent("refs/heads/main").path)
        let watched = try ["refs/heads/main", "objects"].map { ($0, try fileStatus(metadata.appendingPathComponent($0))) }
        let originalFiles = try regularFiles(in: metadata)
        let plan = try await service.inspect(at: root)
        let moved = fixture.appendingPathComponent("Backups/original-git-metadata", isDirectory: true)
        try FileManager.default.moveItem(at: metadata, to: moved)

        for (path, before) in watched {
            let target = moved.appendingPathComponent(path)
            try changeOnlyStatusChangeTime(target)
            let after = try fileStatus(target)
            XCTAssertEqual(after.st_dev, before.st_dev)
            XCTAssertEqual(after.st_ino, before.st_ino)
            XCTAssertEqual(after.st_mode, before.st_mode)
            XCTAssertEqual(after.st_size, before.st_size)
            XCTAssertEqual(after.st_mtimespec.tv_sec, before.st_mtimespec.tv_sec)
            XCTAssertEqual(after.st_mtimespec.tv_nsec, before.st_mtimespec.tv_nsec)
            XCTAssertTrue(after.st_ctimespec.tv_sec != before.st_ctimespec.tv_sec || after.st_ctimespec.tv_nsec != before.st_ctimespec.tv_nsec,
                          "The fixture must reproduce descendant ctime changes after relocation")
        }

        XCTAssertNoThrow(try GitTrackingRemovalService.validateMovedMetadata(at: moved, against: plan))
        XCTAssertEqual(try regularFiles(in: moved), originalFiles)
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
    }

    func testDescendantStatusChangeTimeBeforeMoveStillRequiresReview() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
        _ = try await prepareRepository(root)
        let metadata = root.appendingPathComponent(".git", isDirectory: true)
        let originalFiles = try regularFiles(in: metadata)
        let target = metadata.appendingPathComponent("refs/heads/main")
        let plan = try await service.inspect(at: root)
        let before = try fileStatus(target)
        try changeOnlyStatusChangeTime(target)
        let after = try fileStatus(target)
        XCTAssertTrue(after.st_ctimespec.tv_sec != before.st_ctimespec.tv_sec || after.st_ctimespec.tv_nsec != before.st_ctimespec.tv_nsec)

        do {
            _ = try await service.removeTracking(plan, backupDirectory: fixture.appendingPathComponent("Backups"))
            XCTFail("A pre-move metadata timestamp change must still invalidate review")
        } catch GitTrackingRemovalError.sourceChanged {}

        XCTAssertEqual(try regularFiles(in: metadata), originalFiles)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: fixture.appendingPathComponent("Backups").path).isEmpty)
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
    }

    func testMovedMetadataRejectsChangedContentsIdentityPermissionsAndModificationTime() async throws {
        for mutation in ["contents", "identity", "permissions", "modification-time"] {
            let fixture = try makeFixture()
            defer { try? FileManager.default.removeItem(at: fixture) }
            let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
            _ = try await prepareRepository(root)
            let metadata = root.appendingPathComponent(".git", isDirectory: true)
            let targetBeforeMove = metadata.appendingPathComponent("description")
            let before = try fileStatus(targetBeforeMove)
            let originalBytes = try Data(contentsOf: targetBeforeMove)
            let parentBefore = try fileStatus(metadata)
            let plan = try await service.inspect(at: root)
            let moved = fixture.appendingPathComponent("Backups/original-git-metadata", isDirectory: true)
            try FileManager.default.moveItem(at: metadata, to: moved)
            let target = moved.appendingPathComponent("description")
            switch mutation {
            case "contents":
                var changed = originalBytes
                XCTAssertFalse(changed.isEmpty)
                changed[0] ^= 1
                try changed.write(to: target)
                try setFileTimes(target, to: before)
            case "identity":
                let replacement = fixture.appendingPathComponent("replacement-description")
                try FileManager.default.copyItem(at: target, to: replacement)
                try FileManager.default.removeItem(at: target)
                try FileManager.default.moveItem(at: replacement, to: target)
                try setFileTimes(target, to: before)
                try setFileTimes(moved, to: parentBefore)
            case "permissions":
                try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: target.path)
            default:
                var modified = before
                modified.st_mtimespec.tv_sec += 1
                try setFileTimes(target, to: modified)
            }
            let after = try fileStatus(target)
            XCTAssertEqual(after.st_size, before.st_size)
            if mutation == "identity" { XCTAssertNotEqual(after.st_ino, before.st_ino) }
            else { XCTAssertEqual(after.st_ino, before.st_ino) }
            if mutation == "permissions" { XCTAssertNotEqual(after.st_mode, before.st_mode) }
            else { XCTAssertEqual(after.st_mode, before.st_mode) }
            if mutation == "modification-time" { XCTAssertNotEqual(after.st_mtimespec.tv_sec, before.st_mtimespec.tv_sec) }
            else {
                XCTAssertEqual(after.st_mtimespec.tv_sec, before.st_mtimespec.tv_sec)
                XCTAssertEqual(after.st_mtimespec.tv_nsec, before.st_mtimespec.tv_nsec)
            }
            if mutation == "contents" { XCTAssertNotEqual(try Data(contentsOf: target), originalBytes) }
            else { XCTAssertEqual(try Data(contentsOf: target), originalBytes) }

            XCTAssertThrowsError(try GitTrackingRemovalService.validateMovedMetadata(at: moved, against: plan), mutation) { error in
                XCTAssertEqual(error as? GitTrackingRemovalError, .sourceChanged)
            }
            XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
        }
    }

    func testBackupPreflightRejectsRepositoryOverlapWithoutWriting() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
        _ = try await prepareRepository(root)
        let child = root.appendingPathComponent("Backups", isDirectory: true)
        try FileManager.default.createDirectory(at: child, withIntermediateDirectories: true)
        let originalMetadata = try regularFiles(in: root.appendingPathComponent(".git"))
        let other = fixture.appendingPathComponent("AnotherRepository", isDirectory: true)
        _ = try await prepareRepository(other)
        let otherBackups = other.appendingPathComponent("Backups", isDirectory: true)
        try FileManager.default.createDirectory(at: otherBackups, withIntermediateDirectories: true)
        let otherMetadata = try regularFiles(in: other.appendingPathComponent(".git"))
        let plan = try await service.inspect(at: root)

        for destination in [root, child] {
            do {
                try await service.validateBackupDirectory(destination, for: plan)
                XCTFail("Preflight must reject backups inside the repository")
            } catch GitTrackingRemovalError.backupInsideRepository {}
        }
        do {
            try await service.validateBackupDirectory(otherBackups, for: plan)
            XCTFail("Preflight must reject backups covered by another repository")
        } catch GitTrackingRemovalError.backupInRepository {}

        XCTAssertEqual(try regularFiles(in: root.appendingPathComponent(".git")), originalMetadata)
        XCTAssertEqual(try regularFiles(in: other.appendingPathComponent(".git")), otherMetadata)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: child.path).isEmpty)
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: otherBackups.path).isEmpty)
    }

    func testRemovalWithoutBackupPreservesWorkingFilesNestedRepositoryAndSiblings() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
        let original = try await prepareRepository(root)
        let independent = fixture.appendingPathComponent("IndependentVault", isDirectory: true)
        _ = try await prepareRepository(independent)
        let nested = root.appendingPathComponent("ElCamino", isDirectory: true)
        try FileManager.default.moveItem(at: independent, to: nested)
        let sibling = fixture.appendingPathComponent("SiblingVault", isDirectory: true)
        _ = try await prepareRepository(sibling)
        try write("local edits\n", root: root, path: "Note.md")
        try write("private untracked note\n", root: root, path: "Untracked.md")
        for name in ["HEAD", "objects", "refs", "config", ".git-quarantine-user-owned"] {
            try write("keep this unrelated folder\n", root: root, path: name + "/keep.md")
        }
        // The default descriptor-based deleter must handle immutable regular
        // objects and nested reference directories without touching lookalikes.
        for path in ["objects/pack/qa-readonly.pack", "objects/pack/qa-readonly.idx", "refs/heads/archive/keep"] {
            try write(original.commit + "\n", root: root, path: ".git/" + path)
            try FileManager.default.setAttributes([.posixPermissions: 0o444], ofItemAtPath: root.appendingPathComponent(".git/" + path).path)
        }
        let workingFiles = try regularFiles(in: root).filter { !$0.key.hasPrefix(".git/") }
        let nestedMetadata = try regularFiles(in: nested.appendingPathComponent(".git"))
        let siblingFiles = try regularFiles(in: sibling)
        let expectedTopLevel = try FileManager.default.contentsOfDirectory(atPath: root.path)
            .filter { $0 != ".git" }.sorted()
        let plan = try await service.inspect(at: root)

        let result = try await service.removeTrackingWithoutBackup(plan)

        XCTAssertNil(result.backupURL)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        XCTAssertEqual(try regularFiles(in: root), workingFiles)
        XCTAssertEqual(try regularFiles(in: nested.appendingPathComponent(".git")), nestedMetadata)
        XCTAssertEqual(try regularFiles(in: sibling), siblingFiles)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path).sorted(), expectedTopLevel,
                       "Successful deletion must leave no temporary quarantine and preserve unrelated hidden folders")
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: fixture.appendingPathComponent("Backups").path).isEmpty)
    }

    func testRemovalWithoutBackupDoesNotCopyMetadataOrRequireBackupCapacity() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
        _ = try await prepareRepository(root)
        let noBackup = GitTrackingRemovalService(
            enforceLocalStorage: false,
            copyMetadata: { _, _ in throw NSError(domain: "UnexpectedBackupCopy", code: 1) },
            availableCapacity: { _ in 0 }
        )
        let plan = try await noBackup.inspect(at: root)

        let result = try await noBackup.removeTrackingWithoutBackup(plan)

        XCTAssertNil(result.backupURL)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: fixture.appendingPathComponent("Backups").path).isEmpty)
    }

    func testRemovalWithoutBackupRejectsChangedOrReplacedMetadataAfterReview() async throws {
        for replaceDirectory in [false, true] {
            let fixture = try makeFixture()
            defer { try? FileManager.default.removeItem(at: fixture) }
            let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
            _ = try await prepareRepository(root)
            let plan = try await service.inspect(at: root)
            if replaceDirectory {
                let replacement = fixture.appendingPathComponent("replacement", isDirectory: true)
                try FileManager.default.copyItem(at: root.appendingPathComponent(".git"), to: replacement)
                try FileManager.default.removeItem(at: root.appendingPathComponent(".git"))
                try FileManager.default.moveItem(at: replacement, to: root.appendingPathComponent(".git"))
            } else {
                try write("metadata changed after review\n", root: root, path: ".git/description")
            }
            let currentMetadata = try regularFiles(in: root.appendingPathComponent(".git"))
            let topLevel = try FileManager.default.contentsOfDirectory(atPath: root.path).sorted()

            do {
                _ = try await service.removeTrackingWithoutBackup(plan)
                XCTFail("Deletion must retain metadata that differs from the reviewed snapshot or identity")
            } catch GitTrackingRemovalError.sourceChanged {}

            XCTAssertEqual(try regularFiles(in: root.appendingPathComponent(".git")), currentMetadata)
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path).sorted(), topLevel)
            XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
        }
    }

    func testRemovalWithoutBackupRejectsLocksAndLinksIntroducedAfterReview() async throws {
        for useLink in [false, true] {
            let fixture = try makeFixture()
            defer { try? FileManager.default.removeItem(at: fixture) }
            let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
            _ = try await prepareRepository(root)
            let outside = fixture.appendingPathComponent("private-note.txt")
            try "unchanged private file\n".write(to: outside, atomically: true, encoding: .utf8)
            let plan = try await service.inspect(at: root)
            if useLink {
                try FileManager.default.createSymbolicLink(at: root.appendingPathComponent(".git/outside"), withDestinationURL: outside)
            } else {
                try write("active writer\n", root: root, path: ".git/index.lock")
            }
            let topLevel = try FileManager.default.contentsOfDirectory(atPath: root.path).sorted()

            do {
                _ = try await service.removeTrackingWithoutBackup(plan)
                XCTFail("Deletion must reject newly locked or linked metadata before moving it")
            } catch GitTrackingRemovalError.unsupportedMetadata {
                XCTAssertTrue(useLink)
            } catch GitTrackingRemovalError.activeGitOperation {
                XCTAssertFalse(useLink)
            }

            XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git/HEAD").path))
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path).sorted(), topLevel)
            XCTAssertEqual(try String(contentsOf: outside, encoding: .utf8), "unchanged private file\n")
            if useLink {
                XCTAssertEqual(try FileManager.default.destinationOfSymbolicLink(atPath: root.appendingPathComponent(".git/outside").path), outside.path)
            } else {
                XCTAssertEqual(try String(contentsOf: root.appendingPathComponent(".git/index.lock"), encoding: .utf8), "active writer\n")
            }
        }
    }

    func testRemovalWithoutBackupRejectsMetadataDirectoryReplacedBySymbolicLink() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
        _ = try await prepareRepository(root)
        let target = fixture.appendingPathComponent("OtherVault", isDirectory: true)
        _ = try await prepareRepository(target)
        let targetMetadata = try regularFiles(in: target.appendingPathComponent(".git"))
        let plan = try await service.inspect(at: root)
        let preserved = fixture.appendingPathComponent("preserved-original-git", isDirectory: true)
        try FileManager.default.moveItem(at: root.appendingPathComponent(".git"), to: preserved)
        let originalMetadata = try regularFiles(in: preserved)
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent(".git"), withDestinationURL: target.appendingPathComponent(".git"))

        do {
            _ = try await service.removeTrackingWithoutBackup(plan)
            XCTFail("A symlink substitution must not cause deletion of another vault's metadata")
        } catch GitTrackingRemovalError.unsupportedMetadata {}

        XCTAssertEqual(try regularFiles(in: target.appendingPathComponent(".git")), targetMetadata)
        XCTAssertEqual(try regularFiles(in: preserved), originalMetadata)
        XCTAssertEqual(try FileManager.default.destinationOfSymbolicLink(atPath: root.appendingPathComponent(".git").path), target.appendingPathComponent(".git").path)
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
    }

    func testPrecancelledRemovalWithoutBackupPreservesCompleteMetadata() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
        _ = try await prepareRepository(root)
        let original = try regularFiles(in: root.appendingPathComponent(".git"))
        let topLevel = try FileManager.default.contentsOfDirectory(atPath: root.path).sorted()
        let plan = try await service.inspect(at: root)
        let operation = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await service.removeTrackingWithoutBackup(plan)
        }

        do {
            _ = try await operation.value
            XCTFail("Cancellation before the deletion boundary must preserve intact Git tracking")
        } catch is CancellationError {}

        XCTAssertEqual(try regularFiles(in: root.appendingPathComponent(".git")), original)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path).sorted(), topLevel)
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
    }

    func testCancellationAfterDeletionBoundaryStillCompletesRemovalWithoutBackup() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
        _ = try await prepareRepository(root)
        let deleting = expectation(description: "Deletion boundary crossed")
        let release = DispatchSemaphore(value: 0)
        let cancellable = GitTrackingRemovalService(enforceLocalStorage: false, deleteMetadata: { metadata in
            deleting.fulfill()
            release.wait()
            try FileManager.default.removeItem(at: metadata)
        })
        let plan = try await cancellable.inspect(at: root)
        let expectedTopLevel = try FileManager.default.contentsOfDirectory(atPath: root.path)
            .filter { $0 != ".git" }.sorted()
        let operation = Task { try await cancellable.removeTrackingWithoutBackup(plan) }
        await fulfillment(of: [deleting], timeout: 5)
        operation.cancel()
        release.signal()

        let result = try await operation.value

        XCTAssertNil(result.backupURL)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path).sorted(), expectedTopLevel)
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
    }

    func testPartialDeletionFailureRetainsRemainingMetadataAndReportsItsLocation() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
        _ = try await prepareRepository(root)
        let original = try regularFiles(in: root.appendingPathComponent(".git"))
        let failing = GitTrackingRemovalService(enforceLocalStorage: false, deleteMetadata: { metadata in
            try FileManager.default.removeItem(at: metadata.appendingPathComponent("HEAD"))
            throw NSError(domain: "DeliberatePartialDeletionFailure", code: 1)
        })
        let plan = try await failing.inspect(at: root)

        do {
            _ = try await failing.removeTrackingWithoutBackup(plan)
            XCTFail("A partial deletion must report remaining metadata and keep the detached root disconnected")
        } catch GitTrackingRemovalError.metadataDeletionIncomplete(let metadataURL) {
            XCTAssertEqual(metadataURL.deletingLastPathComponent().standardizedFileURL, root.standardizedFileURL)
            XCTAssertTrue(metadataURL.lastPathComponent.hasPrefix("."), "Retained quarantine must be hidden")
            XCTAssertFalse(FileManager.default.fileExists(atPath: metadataURL.appendingPathComponent("HEAD").path))
            XCTAssertEqual(try regularFiles(in: metadataURL), original.filter { $0.key != "HEAD" })
            let message = try XCTUnwrap(GitTrackingRemovalError.metadataDeletionIncomplete(metadataURL: metadataURL).errorDescription)
            XCTAssertTrue(message.contains(metadataURL.path))
        }

        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
        XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: fixture.appendingPathComponent("Backups").path).isEmpty)
    }

    func testCancellationErrorFromDeletionIsReportedAsIncompleteMetadataDeletion() async throws {
        let fixture = try makeFixture()
        defer { try? FileManager.default.removeItem(at: fixture) }
        let root = fixture.appendingPathComponent("Obsidian", isDirectory: true)
        _ = try await prepareRepository(root)
        let original = try regularFiles(in: root.appendingPathComponent(".git"))
        let failing = GitTrackingRemovalService(enforceLocalStorage: false, deleteMetadata: { _ in
            throw CancellationError()
        })
        let plan = try await failing.inspect(at: root)

        do {
            _ = try await failing.removeTrackingWithoutBackup(plan)
            XCTFail("Cancellation thrown after irreversible deletion begins cannot claim that .git was restored")
        } catch GitTrackingRemovalError.metadataDeletionIncomplete(let metadataURL) {
            XCTAssertEqual(try regularFiles(in: metadataURL), original)
        }

        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "original note\n")
    }

    private func makeFixture() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("GitTrackingRemovalTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root.appendingPathComponent("Backups"), withIntermediateDirectories: true)
        return root
    }

    private func prepareRepository(_ root: URL) async throws -> (id: UUID, commit: String) {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try write("original note\n", root: root, path: "Note.md")
        let git = FolderGitService(enforceLocalStorage: false)
        let inspection = try await git.inspect(at: root)
        let id = UUID()
        let prepared = try await git.prepare(at: root, workflowID: id, files: inspection.files, selectedPaths: ["Note.md"],
                                             authorName: "Tests", authorEmail: "tests@example.com", message: "Original commit")
        var repository: OpaquePointer?
        defer { if let repository { git_repository_free(repository) } }
        try check(git_repository_open(&repository, root.path))
        var remote: OpaquePointer?
        defer { if let remote { git_remote_free(remote) } }
        try check(git_remote_create(&remote, repository, "origin", "https://github.com/example/obsidian.git"))
        return (id, prepared.commitOID)
    }

    private func write(_ content: String, root: URL, path: String) throws {
        let file = root.appendingPathComponent(path)
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try content.write(to: file, atomically: true, encoding: .utf8)
    }

    private func regularFiles(in root: URL) throws -> [String: Data] {
        var result: [String: Data] = [:]
        let enumerator = try XCTUnwrap(FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey]))
        for case let file as URL in enumerator {
            if try file.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true {
                let relative = String(file.path.dropFirst(root.path.count + 1))
                result[relative] = try Data(contentsOf: file)
            }
        }
        return result
    }

    private func check(_ code: Int32) throws {
        if code < 0 {
            throw NSError(domain: "GitTrackingRemovalTests", code: Int(code), userInfo: [NSLocalizedDescriptionKey: git_error_last()?.pointee.message.map { String(cString: $0) } ?? "Git operation failed"])
        }
    }

    private func fileStatus(_ url: URL) throws -> stat {
        var result = stat()
        guard lstat(url.path, &result) == 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
        return result
    }

    private func changeOnlyStatusChangeTime(_ url: URL) throws {
        let before = try fileStatus(url)
        let descriptor = open(url.path, O_RDONLY | O_NOFOLLOW)
        guard descriptor >= 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
        defer { close(descriptor) }
        guard fchmod(descriptor, before.st_mode & 0o7777) == 0 else {
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
        }
    }

    private func setFileTimes(_ url: URL, to status: stat) throws {
        let times = [status.st_atimespec, status.st_mtimespec]
        let result = url.path.withCString { path in
            times.withUnsafeBufferPointer { buffer in
                utimensat(AT_FDCWD, path, buffer.baseAddress, AT_SYMLINK_NOFOLLOW)
            }
        }
        guard result == 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
    }
}

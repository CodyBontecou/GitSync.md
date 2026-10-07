import Foundation
import Darwin
import XCTest
import Clibgit2
import libgit2
@testable import Sync_md

final class FolderGitServiceTests: XCTestCase {
    private let service = FolderGitService(enforceLocalStorage: false)

    override func setUp() {
        super.setUp()
        _ = git_libgit2_init()
    }

    func testInspectionLeavesFolderUntouchedAndUsesNestedIgnoreRules() async throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        try write("# Note\n", to: "Note.md", root: root)
        try write("hidden\n", to: ".hidden", root: root)
        try write("*.secret\n", to: "Notes/.gitignore", root: root)
        try write("private\n", to: "Notes/private.secret", root: root)

        let result = try await service.inspect(at: root)

        XCTAssertEqual(Set(result.files.map(\.path)), ["Note.md", ".hidden", "Notes/.gitignore", "Notes/private.secret"])
        XCTAssertTrue(try XCTUnwrap(result.files.first { $0.path == "Notes/private.secret" }).isIgnored)
        XCTAssertFalse(try XCTUnwrap(result.files.first { $0.path == "Note.md" }).isIgnored)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Notes/private.secret"), encoding: .utf8), "private\n")
    }

    func testLocationAllowlistDoesNotTrustContainerNamesInsideAnArbitraryFolder() async throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        let imitation = root.appendingPathComponent("Containers/Data/Application/\(UUID().uuidString)/Documents", isDirectory: true)
        try FileManager.default.createDirectory(at: imitation, withIntermediateDirectories: true)
        do {
            _ = try await FolderGitService().inspect(at: imitation)
            XCTFail("A provider or arbitrary location cannot qualify by naming subfolders like an app container")
        } catch FolderGitServiceError.unsupportedLocation {}
        XCTAssertFalse(FileManager.default.fileExists(atPath: imitation.appendingPathComponent(".git").path))
    }

    func testLocalDocumentsLocationAcceptsBothDeviceVarAliases() {
        let container = UUID().uuidString
        for prefix in ["/var/mobile", "/private/var/mobile"] {
            for suffix in ["Documents", "Documents/ElCamino"] {
                let folder = URL(fileURLWithPath: "\(prefix)/Containers/Data/Application/\(container)/\(suffix)", isDirectory: true)
                XCTAssertTrue(FolderGitService.isLocalDocumentsLocation(folder), "Local app Documents must accept \(prefix)")
            }
        }
    }

    func testLocalDocumentsLocationKeepsCloudAndProviderStorageExcluded() {
        let container = UUID().uuidString
        for path in [
            "/var/mobile/Library/Mobile Documents/iCloud~md~obsidian/Documents/ElCamino",
            "/var/mobile/Containers/Shared/AppGroup/\(container)/File Provider Storage/ElCamino",
            "/var/mobile/Containers/Data/Application/\(container)/Library/ElCamino",
            "/var/mobile/Containers/Data/Application/\(container)/DocumentsBackup/ElCamino",
            "/var/mobile/Containers/Data/Application/not-a-container/Documents/ElCamino",
            "/tmp/Containers/Data/Application/\(container)/Documents/ElCamino",
            "/tmp/CoreSimulator/Devices/\(container)/data/Containers/Data/Application/\(container)/Library/ElCamino"
        ] {
            XCTAssertFalse(FolderGitService.isLocalDocumentsLocation(URL(fileURLWithPath: path, isDirectory: true)), "Unsupported location: \(path)")
        }
    }

    func testLocalDocumentsFolderCanBeInspectedPreparedAndReopenedWithLocationEnforcement() async throws {
        let documents = try XCTUnwrap(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first)
        let root = documents.appendingPathComponent("folder-publication-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try write("# Local vault\n", to: "Note.md", root: root)
        let localService = FolderGitService()
        let inspection = try await localService.inspect(at: root)
        XCTAssertEqual(inspection.files.map(\.path), ["Note.md"])
        let workflowID = UUID()
        let prepared = try await localService.prepare(at: root, workflowID: workflowID, files: inspection.files,
            selectedPaths: ["Note.md"], authorName: "Tests", authorEmail: "tests@example.com", message: "Initial commit")
        try await localService.validatePrepared(at: root, workflowID: workflowID, commitOID: prepared.commitOID)
        XCTAssertEqual(try blob(root: root, oid: prepared.commitOID, path: "Note.md"), Data("# Local vault\n".utf8))
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "# Local vault\n")
    }

    func testPreparationPreservesBytesAndRetainsLiteralExclusions() async throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        let note = Data("# Note\r\nReviewed bytes\r\n".utf8)
        let secret = Data("local secret".utf8)
        try note.write(to: root.appendingPathComponent("Note.md"))
        try secret.write(to: root.appendingPathComponent("private[1]*.txt"))
        let inspection = try await service.inspect(at: root)
        let workflowID = UUID()

        let prepared = try await prepare(root, id: workflowID, inspection: inspection, paths: ["Note.md"])

        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("Note.md")), note)
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("private[1]*.txt")), secret)
        XCTAssertEqual(try blob(root: root, oid: prepared.commitOID, path: "Note.md"), note)
        XCTAssertNil(try blob(root: root, oid: prepared.commitOID, path: "private[1]*.txt"))
        XCTAssertTrue(try isIgnored(root: root, path: "private[1]*.txt"))
        XCTAssertFalse(try isIgnored(root: root, path: "private1other.txt"))
        let repeated = try await prepare(root, id: workflowID, inspection: inspection, paths: ["Note.md"])
        XCTAssertEqual(repeated.commitOID, prepared.commitOID)
        XCTAssertEqual(repeated.treeOID, prepared.treeOID)
    }

    func testMutationAfterReviewIsRejectedBeforeInitialization() async throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        try write("reviewed", to: "Note.md", root: root)
        let inspection = try await service.inspect(at: root)
        try write("new content", to: "Note.md", root: root)

        do {
            _ = try await prepare(root, id: UUID(), inspection: inspection, paths: ["Note.md"])
            XCTFail("Changed bytes must require another review")
        } catch FolderGitServiceError.reviewChanged {}

        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "new content")
    }

    func testUnreviewedNewFileIsRejectedBeforeInitialization() async throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        try write("reviewed", to: "Note.md", root: root)
        let inspection = try await service.inspect(at: root)
        try write("new secret", to: ".env", root: root)
        do {
            _ = try await prepare(root, id: UUID(), inspection: inspection, paths: ["Note.md"])
            XCTFail("The initial snapshot cannot silently acquire new files")
        } catch FolderGitServiceError.reviewChanged {}
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
    }

    func testExistingMetadataAndForeignWorkflowAreNeverAdopted() async throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        try write("reviewed", to: "Note.md", root: root)
        let inspection = try await service.inspect(at: root)
        let prepared = try await prepare(root, id: UUID(), inspection: inspection, paths: ["Note.md"])
        let marker = try Data(contentsOf: root.appendingPathComponent(".git/folder-publication.json"))

        do {
            _ = try await service.inspect(at: root)
            XCTFail("Inspection must not treat an existing repository as a plain folder")
        } catch FolderGitServiceError.existingMetadata {}
        do {
            _ = try await prepare(root, id: UUID(), inspection: inspection, paths: ["Note.md"])
            XCTFail("A different workflow cannot adopt metadata")
        } catch FolderGitServiceError.ownershipMismatch {}
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent(".git/folder-publication.json")), marker)
        XCTAssertEqual(try blob(root: root, oid: prepared.commitOID, path: "Note.md"), Data("reviewed".utf8))
    }

    func testRecoveryReusesCommitAfterMarkerResponseWasLost() async throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        try write("reviewed", to: "Note.md", root: root)
        let inspection = try await service.inspect(at: root)
        let id = UUID()
        let original = try await prepare(root, id: id, inspection: inspection, paths: ["Note.md"])
        let markerURL = root.appendingPathComponent(".git/folder-publication.json")
        var marker = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: markerURL)) as? [String: Any])
        marker.removeValue(forKey: "commitOID")
        marker.removeValue(forKey: "treeOID")
        try JSONSerialization.data(withJSONObject: marker).write(to: markerURL, options: .atomic)

        let recovered = try await prepare(root, id: id, inspection: inspection, paths: ["Note.md"])

        XCTAssertEqual(recovered.commitOID, original.commitOID)
        XCTAssertEqual(recovered.treeOID, original.treeOID)
    }

    func testLFSTrackedFileAndPointerAreRejectedBeforeInitialization() async throws {
        for includeAttributes in [true, false] {
            let root = try temporaryFolder()
            defer { try? FileManager.default.removeItem(at: root) }
            if includeAttributes {
                try write("*.txt filter=lfs diff=lfs merge=lfs -text\n", to: ".gitattributes", root: root)
                try write("ordinary bytes", to: "Note.txt", root: root)
            } else {
                let pointer = GitLFSPointer(oid: String(repeating: "a", count: 64), size: 50)
                try write(pointer.serializedString, to: "Note.txt", root: root)
            }
            let inspection = try await service.inspect(at: root)
            do {
                _ = try await prepare(root, id: UUID(), inspection: inspection, paths: ["Note.txt"])
                XCTFail("LFS requires a publication path outside this MVP")
            } catch FolderGitServiceError.unsupportedFile {}
            XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        }
    }

    func testOversizedTextIsRejectedBeforeInitialization() async throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        try Data(repeating: UInt8(ascii: "a"), count: Int(FolderGitService.maximumFileBytes) + 1).write(to: root.appendingPathComponent("Large.txt"))
        let inspection = try await service.inspect(at: root)
        do {
            _ = try await prepare(root, id: UUID(), inspection: inspection, paths: ["Large.txt"])
            XCTFail("Text files obey the same size policy as binary files")
        } catch FolderGitServiceError.unsupportedFile {}
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
    }

    func testContentConversionAttributesRequireExclusionBeforeInitialization() async throws {
        for attribute in ["text", "text=auto", "eol=lf", "ident", "working-tree-encoding=UTF-8"] {
            let root = try temporaryFolder()
            defer { try? FileManager.default.removeItem(at: root) }
            let bytes = Data("# Reviewed\r\n$Id$\r\n".utf8)
            try bytes.write(to: root.appendingPathComponent("Note.md"))
            try write("*.md \(attribute)\n", to: ".gitattributes", root: root)
            let inspection = try await service.inspect(at: root)
            XCTAssertTrue(inspection.warnings.contains { $0.contains("Note.md") })
            do {
                _ = try await prepare(root, id: UUID(), inspection: inspection, paths: ["Note.md", ".gitattributes"])
                XCTFail("Content conversion cannot silently change the reviewed Git bytes")
            } catch FolderGitServiceError.unsupportedFile {}
            XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
            XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("Note.md")), bytes)
        }
    }

    func testDecomposedUnicodeFilenamePreservesBytesAndUsesCanonicalTreePath() async throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        let physicalPath = "Cafe\u{301}.md"
        let treePath = "Caf\u{e9}.md"
        let bytes = Data("# Caf\u{e9}\n".utf8)
        try bytes.write(to: root.appendingPathComponent(physicalPath))
        let inspection = try await service.inspect(at: root)
        XCTAssertEqual(inspection.files.map(\.path), [treePath])
        let id = UUID()

        let prepared = try await prepare(root, id: id, inspection: inspection, paths: [treePath])
        try await service.validatePrepared(at: root, workflowID: id, commitOID: prepared.commitOID)

        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent(physicalPath)), bytes)
        XCTAssertEqual(try blob(root: root, oid: prepared.commitOID, path: treePath), bytes)
        let info = try await LocalGitService(localURL: root).repoInfo()
        XCTAssertEqual(info.changeCount, 0)
    }

    func testInsufficientStorageFailsBeforeMetadataReservation() async throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        try write("reviewed bytes", to: "Note.md", root: root)
        let inspection = try await service.inspect(at: root)
        let lowStorageService = FolderGitService(enforceLocalStorage: false, availableCapacityProvider: { _ in 0 })

        do {
            _ = try await lowStorageService.prepare(at: root, workflowID: UUID(), files: inspection.files,
                selectedPaths: ["Note.md"], authorName: "Tests", authorEmail: "tests@example.com", message: "Initial commit")
            XCTFail("Storage must be checked before reserving Git metadata")
        } catch FolderGitServiceError.insufficientStorage(let required, let available) {
            XCTAssertGreaterThan(required, 0)
            XCTAssertEqual(available, 0)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("Note.md"), encoding: .utf8), "reviewed bytes")
    }

    func testEmptyRegularFileCanBePublishedButEmptyFolderCannot() async throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        let emptyInspection = try await service.inspect(at: root)
        do {
            _ = try await prepare(root, id: UUID(), inspection: emptyInspection, paths: [])
            XCTFail("Git cannot represent an empty working directory without a file")
        } catch FolderGitServiceError.invalidSelection {}
        try Data().write(to: root.appendingPathComponent("Empty.txt"))
        let inspection = try await service.inspect(at: root)
        let prepared = try await prepare(root, id: UUID(), inspection: inspection, paths: ["Empty.txt"])
        XCTAssertEqual(try blob(root: root, oid: prepared.commitOID, path: "Empty.txt"), Data())
    }

    func testSymlinkNestedRepositoryAndAccessibleAncestorAreRejected() async throws {
        let symlinkRoot = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: symlinkRoot) }
        try FileManager.default.createSymbolicLink(at: symlinkRoot.appendingPathComponent("external"), withDestinationURL: URL(fileURLWithPath: "/etc/hosts"))
        do {
            _ = try await service.inspect(at: symlinkRoot)
            XCTFail("Symlinks must not be followed")
        } catch FolderGitServiceError.unsupportedFile {}

        let ancestor = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: ancestor) }
        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, ancestor.path, 0), 0)
        if let repo { git_repository_free(repo) }
        let child = ancestor.appendingPathComponent("Child", isDirectory: true)
        try FileManager.default.createDirectory(at: child, withIntermediateDirectories: false)
        do {
            _ = try await service.inspect(at: child)
            XCTFail("An enclosing repository must be preserved")
        } catch FolderGitServiceError.enclosingRepository(let path) {
            XCTAssertEqual(path, ancestor.standardizedFileURL.resolvingSymlinksInPath().path)
        }

        let nestedRoot = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: nestedRoot) }
        let nested = nestedRoot.appendingPathComponent("Nested", isDirectory: true)
        try FileManager.default.createDirectory(at: nested, withIntermediateDirectories: false)
        try write("gitdir: elsewhere\n", to: ".git", root: nested)
        do {
            _ = try await service.inspect(at: nestedRoot)
            XCTFail("A .git file also marks nested metadata")
        } catch FolderGitServiceError.existingMetadata {}
    }

    func testBareLookingAncestorIsNotAnEnclosingRepository() async throws {
        let ancestor = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: ancestor) }
        try write("ordinary heading", to: "HEAD", root: ancestor)
        try write("ordinary settings", to: "config", root: ancestor)
        for name in ["objects", "refs"] {
            try FileManager.default.createDirectory(at: ancestor.appendingPathComponent(name), withIntermediateDirectories: true)
        }
        let child = ancestor.appendingPathComponent("ElCamino", isDirectory: true)
        try FileManager.default.createDirectory(at: child, withIntermediateDirectories: true)
        try write("# Reviewed note\n", to: "Note.md", root: child)

        let inspection = try await service.inspect(at: child)
        XCTAssertEqual(inspection.files.map(\.path), ["Note.md"])
        let prepared = try await prepare(child, id: UUID(), inspection: inspection, paths: ["Note.md"])
        XCTAssertEqual(try blob(root: child, oid: prepared.commitOID, path: "Note.md"), Data("# Reviewed note\n".utf8))
        XCTAssertEqual(try String(contentsOf: ancestor.appendingPathComponent("HEAD"), encoding: .utf8), "ordinary heading")
        XCTAssertFalse(FileManager.default.fileExists(atPath: ancestor.appendingPathComponent(".git").path))
    }

    func testRealBareAncestorRemainsProtected() async throws {
        let ancestor = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: ancestor) }
        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, ancestor.path, 1), 0)
        if let repo { git_repository_free(repo) }
        let originalHead = try Data(contentsOf: ancestor.appendingPathComponent("HEAD"))
        let child = ancestor.appendingPathComponent("ElCamino", isDirectory: true)
        try FileManager.default.createDirectory(at: child, withIntermediateDirectories: true)
        try write("# Note", to: "Note.md", root: child)
        do {
            _ = try await service.inspect(at: child)
            XCTFail("A real bare repository must remain protected")
        } catch FolderGitServiceError.enclosingRepository(let path) {
            XCTAssertEqual(path, ancestor.standardizedFileURL.resolvingSymlinksInPath().path)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: child.appendingPathComponent(".git").path))
        XCTAssertEqual(try Data(contentsOf: ancestor.appendingPathComponent("HEAD")), originalHead)
    }

    func testOrdinaryBareLookingNamesInsideSelectionRemainPublishable() async throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        for prefix in ["", "Nested/"] {
            try write("ordinary heading", to: "\(prefix)HEAD", root: root)
            try write("ordinary settings", to: "\(prefix)config", root: root)
            for name in ["objects", "refs"] {
                try FileManager.default.createDirectory(at: root.appendingPathComponent("\(prefix)\(name)"), withIntermediateDirectories: true)
            }
        }
        let inspection = try await service.inspect(at: root)
        XCTAssertEqual(Set(inspection.files.map(\.path)), ["HEAD", "config", "Nested/HEAD", "Nested/config"])
    }

    func testCorruptBareMetadataInsideSelectionRemainsProtected() async throws {
        for nested in [false, true] {
            let root = try temporaryFolder()
            defer { try? FileManager.default.removeItem(at: root) }
            let metadata = nested ? root.appendingPathComponent("Nested", isDirectory: true) : root
            try FileManager.default.createDirectory(at: metadata, withIntermediateDirectories: true)
            var repo: OpaquePointer?
            XCTAssertEqual(git_repository_init(&repo, metadata.path, 1), 0)
            if let repo { git_repository_free(repo) }
            try write("invalid git config", to: "config", root: metadata)
            do {
                _ = try await service.inspect(at: root)
                XCTFail("Corrupt bare Git configuration must not be published as ordinary content")
            } catch FolderGitServiceError.metadataNeedsAttention(let path) {
                XCTAssertEqual(path, metadata.path)
            }
            XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
            XCTAssertEqual(try String(contentsOf: metadata.appendingPathComponent("config"), encoding: .utf8), "invalid git config")
        }
    }

    func testBareMetadataWithMalformedObjectStorageRemainsProtected() async throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        try write("ref: refs/heads/main\n", to: "HEAD", root: root)
        try write("[core]\n bare = true\n", to: "config", root: root)
        try write("broken object storage", to: "objects", root: root)
        try FileManager.default.createDirectory(at: root.appendingPathComponent("refs"), withIntermediateDirectories: true)
        do {
            _ = try await service.inspect(at: root)
            XCTFail("Malformed Git object storage cannot be treated as ordinary content")
        } catch FolderGitServiceError.metadataNeedsAttention(let path) {
            XCTAssertEqual(path, root.appendingPathComponent("objects").path)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("objects"), encoding: .utf8), "broken object storage")
    }

    func testIncompleteAncestorGitMetadataNeedsAttentionWithoutBeingReplaced() async throws {
        let ancestor = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: ancestor) }
        let gitdir = ancestor.appendingPathComponent(".git", isDirectory: true)
        try FileManager.default.createDirectory(at: gitdir, withIntermediateDirectories: true)
        try write("preserve this", to: ".git/partial", root: ancestor)
        let child = ancestor.appendingPathComponent("ElCamino", isDirectory: true)
        try FileManager.default.createDirectory(at: child, withIntermediateDirectories: true)
        try write("# Note", to: "Note.md", root: child)
        do {
            _ = try await service.inspect(at: child)
            XCTFail("Incomplete ancestor metadata cannot be silently bypassed")
        } catch FolderGitServiceError.metadataNeedsAttention(let path) {
            XCTAssertEqual(path, gitdir.standardizedFileURL.resolvingSymlinksInPath().path)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: child.appendingPathComponent(".git").path))
        XCTAssertEqual(try String(contentsOf: gitdir.appendingPathComponent("partial"), encoding: .utf8), "preserve this")
    }

    func testPreparedValidationReadsSavedTreeInsteadOfMutableIndex() async throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        try write("saved bytes", to: "Note.md", root: root)
        let inspection = try await service.inspect(at: root)
        let id = UUID()
        let prepared = try await prepare(root, id: id, inspection: inspection, paths: ["Note.md"])
        try write("new staged bytes", to: "Note.md", root: root)
        try await LocalGitService(localURL: root).stage(path: "Note.md")

        try await service.validatePrepared(at: root, workflowID: id, commitOID: prepared.commitOID)

        XCTAssertEqual(try blob(root: root, oid: prepared.commitOID, path: "Note.md"), Data("saved bytes".utf8))
    }

    func testStageAllNeverReaddsIgnoredLFSCandidates() async throws {
        for autoTrack in [false, true] {
            let root = try temporaryFolder()
            defer { try? FileManager.default.removeItem(at: root) }
            var repo: OpaquePointer?
            XCTAssertEqual(git_repository_init(&repo, root.path, 0), 0)
            if let repo { git_repository_free(repo) }
            try write("private.pdf\n", to: ".gitignore", root: root)
            if !autoTrack {
                try write("*.pdf filter=lfs diff=lfs merge=lfs -text\n", to: ".gitattributes", root: root)
            }
            try write("%PDF private bytes\n", to: "private.pdf", root: root)
            try write("%PDF public bytes\n", to: "public.pdf", root: root)
            let git = LocalGitService(localURL: root)
            try await git.stageAll(lfsAutoTrack: autoTrack)
            let oid = try await git.commitLocal(message: "Privacy regression", authorName: "Tests", authorEmail: "tests@example.com")

            XCTAssertNil(try blob(root: root, oid: oid, path: "private.pdf"))
            let publicBlob = try XCTUnwrap(blob(root: root, oid: oid, path: "public.pdf"))
            XCTAssertNotNil(GitLFSPointer(data: publicBlob))
            XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("private.pdf"), encoding: .utf8), "%PDF private bytes\n")
        }
    }

    func testFirstPublicationToLocalBareRemoteEstablishesTracking() async throws {
        let root = try temporaryFolder()
        let origin = try temporaryFolder()
        defer {
            try? FileManager.default.removeItem(at: root)
            try? FileManager.default.removeItem(at: origin)
        }
        var repo: OpaquePointer?
        XCTAssertEqual(git_repository_init(&repo, origin.path, 1), 0)
        if let repo { git_repository_free(repo) }
        try write("# Published\n", to: "Note.md", root: root)
        let inspection = try await service.inspect(at: root)
        let id = UUID()
        let prepared = try await prepare(root, id: id, inspection: inspection, paths: ["Note.md"])

        try await service.publish(at: root, workflowID: id, commitOID: prepared.commitOID, remoteURL: origin.absoluteString, token: "")
        try await service.finish(at: root, workflowID: id, commitOID: prepared.commitOID, remoteURL: origin.absoluteString, token: "")

        let inventory = try await LocalGitService(localURL: root).listBranches()
        XCTAssertEqual(inventory.local.first { $0.shortName == "main" }?.upstreamShortName, "origin/main")
        let info = try await LocalGitService(localURL: root).repoInfo()
        XCTAssertEqual(info.syncState, .upToDate)
        XCTAssertEqual(try blob(root: origin, oid: prepared.commitOID, path: "Note.md"), Data("# Published\n".utf8))
    }

    func testReviewedPublicationDoesNotUploadMutableIndexLFSPointers() async throws {
        let root = try temporaryFolder()
        let origin = try temporaryFolder()
        defer {
            try? FileManager.default.removeItem(at: root)
            try? FileManager.default.removeItem(at: origin)
        }
        var bare: OpaquePointer?
        try require(git_repository_init(&bare, origin.path, 1))
        if let bare { git_repository_free(bare) }
        let reviewed = Data("# Reviewed publication\n".utf8)
        try reviewed.write(to: root.appendingPathComponent("Note.md"))
        let inspection = try await service.inspect(at: root)
        let id = UUID()
        let prepared = try await prepare(root, id: id, inspection: inspection, paths: ["Note.md"])
        let remoteURL = origin.absoluteString
        try configureSyntheticEmptyUpstream(root: root, remoteURL: remoteURL)

        let unreviewed = Data("Private bytes staged by another client\n".utf8)
        try unreviewed.write(to: root.appendingPathComponent("Note.md"))
        try write("Note.md filter=lfs diff=lfs merge=lfs -text\n", to: ".gitattributes", root: root)
        let git = LocalGitService(localURL: root)
        try await git.stageAll()
        let pointer = GitLFSPointer(oid: GitLFSPointer.sha256Hex(for: unreviewed), size: Int64(unreviewed.count))
        // Leave a matching working pointer so unrelated unstaged-work guards
        // cannot mask whether the LFS upload path was selected.
        try Data(pointer.serializedString.utf8).write(to: root.appendingPathComponent("Note.md"))
        let cache = root.appendingPathComponent(".git/lfs/objects/\(pointer.oid.prefix(2))/\(pointer.oid.dropFirst(2).prefix(2))/\(pointer.oid)")
        XCTAssertEqual(try Data(contentsOf: cache), unreviewed)
        let expectation = PushSafetyExpectation(branch: "main", localCommitSHA: prepared.commitOID,
            remoteCommitSHA: String(repeating: "0", count: 40), remoteURL: remoteURL)

        // A file remote has no LFS API. The ordinary path must discover the
        // deliberately staged pointer and fail before Git transport begins.
        do {
            try await git.pushCurrentBranch(pat: "", expectedBranch: "main", safetyExpectation: expectation)
            XCTFail("The control must exercise mutable-index LFS upload selection")
        } catch LocalGitError.lfsFailed(let message) {
            XCTAssertTrue(message.contains("endpoint"))
        }
        XCTAssertNil(try referenceOID(root: origin, name: "refs/heads/main"))

        try await service.publish(at: root, workflowID: id, commitOID: prepared.commitOID, remoteURL: remoteURL, token: "")
        try await service.finish(at: root, workflowID: id, commitOID: prepared.commitOID, remoteURL: remoteURL, token: "")

        XCTAssertEqual(try referenceOID(root: origin, name: "refs/heads/main"), prepared.commitOID)
        XCTAssertEqual(try blob(root: origin, oid: prepared.commitOID, path: "Note.md"), reviewed)
        XCTAssertNil(try blob(root: origin, oid: prepared.commitOID, path: ".gitattributes"))
        XCTAssertEqual(try Data(contentsOf: cache), unreviewed)
        XCTAssertEqual(try Data(contentsOf: root.appendingPathComponent("Note.md")), Data(pointer.serializedString.utf8))
        XCTAssertFalse(FileManager.default.fileExists(atPath: origin.appendingPathComponent("lfs").path))
    }

    func testExecutableFilePublishesWithReviewedModeAndCleanStatus() async throws {
        let root = try temporaryFolder()
        let origin = try temporaryFolder()
        defer {
            try? FileManager.default.removeItem(at: root)
            try? FileManager.default.removeItem(at: origin)
        }
        var bare: OpaquePointer?
        try require(git_repository_init(&bare, origin.path, 1))
        if let bare { git_repository_free(bare) }
        let bytes = Data("#!/bin/sh\nprintf 'Reviewed script\\n'\n".utf8)
        let script = root.appendingPathComponent("publish.sh")
        try bytes.write(to: script)
        XCTAssertEqual(chmod(script.path, 0o755), 0)
        let inspection = try await service.inspect(at: root)
        XCTAssertEqual(inspection.files.first?.isExecutable, true)
        let id = UUID()
        let prepared = try await prepare(root, id: id, inspection: inspection, paths: ["publish.sh"])

        try await service.publish(at: root, workflowID: id, commitOID: prepared.commitOID, remoteURL: origin.absoluteString, token: "")
        try await service.finish(at: root, workflowID: id, commitOID: prepared.commitOID, remoteURL: origin.absoluteString, token: "")

        XCTAssertEqual(try blob(root: origin, oid: prepared.commitOID, path: "publish.sh"), bytes)
        XCTAssertEqual(try committedMode(root: origin, oid: prepared.commitOID, path: "publish.sh"), UInt32(GIT_FILEMODE_BLOB_EXECUTABLE.rawValue))
        XCTAssertEqual(try Data(contentsOf: script), bytes)
        var status = stat()
        XCTAssertEqual(lstat(script.path, &status), 0)
        XCTAssertNotEqual(status.st_mode & S_IXUSR, 0)
        let info = try await LocalGitService(localURL: root).repoInfo()
        XCTAssertEqual(info.changeCount, 0)
    }

    func testExecutableModeChangeAfterReviewRequiresAnotherReview() async throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        let script = root.appendingPathComponent("publish.sh")
        try write("#!/bin/sh\n", to: "publish.sh", root: root)
        XCTAssertEqual(chmod(script.path, 0o644), 0)
        let inspection = try await service.inspect(at: root)
        XCTAssertEqual(chmod(script.path, 0o755), 0)
        do {
            _ = try await prepare(root, id: UUID(), inspection: inspection, paths: ["publish.sh"])
            XCTFail("Executable metadata changes must be reviewed alongside contents")
        } catch FolderGitServiceError.reviewChanged {}
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent(".git").path))
    }

    private func prepare(_ root: URL, id: UUID, inspection: FolderInspection, paths: Set<String>) async throws -> FolderPreparedCommit {
        try await service.prepare(at: root, workflowID: id, files: inspection.files, selectedPaths: paths,
                                  authorName: "Tests", authorEmail: "tests@example.com", message: "Initial commit")
    }

    private func temporaryFolder() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("FolderGitTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        return root
    }

    private func write(_ text: String, to path: String, root: URL) throws {
        let target = root.appendingPathComponent(path)
        try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try text.write(to: target, atomically: true, encoding: .utf8)
    }

    private func isIgnored(root: URL, path: String) throws -> Bool {
        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        try require(git_repository_open(&repo, root.path))
        var ignored: Int32 = 0
        try require(git_ignore_path_is_ignored(&ignored, repo, path))
        return ignored != 0
    }

    private func configureSyntheticEmptyUpstream(root: URL, remoteURL: String) throws {
        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        try require(git_repository_open(&repo, root.path))
        var remote: OpaquePointer?
        defer { if let remote { git_remote_free(remote) } }
        try require(git_remote_create(&remote, repo, "origin", remoteURL))
        var builder: OpaquePointer?
        defer { if let builder { git_treebuilder_free(builder) } }
        try require(git_treebuilder_new(&builder, repo, nil))
        var treeOID = git_oid()
        try require(git_treebuilder_write(&treeOID, builder))
        var tree: OpaquePointer?
        defer { if let tree { git_tree_free(tree) } }
        try require(git_tree_lookup(&tree, repo, &treeOID))
        var signature: UnsafeMutablePointer<git_signature>?
        defer { if let signature { git_signature_free(signature) } }
        try require(git_signature_new(&signature, "Tests", "tests@example.com", 1, 0))
        var commitOID = git_oid()
        try require(git_commit_create(&commitOID, repo, nil, signature, signature, nil, "Synthetic earlier empty commit", tree, 0, nil))
        var tracking: OpaquePointer?
        defer { if let tracking { git_reference_free(tracking) } }
        try require(git_reference_create(&tracking, repo, "refs/remotes/origin/main", &commitOID, 0, "Fixture upstream"))
        var branch: OpaquePointer?
        defer { if let branch { git_reference_free(branch) } }
        try require(git_branch_lookup(&branch, repo, "main", GIT_BRANCH_LOCAL))
        try require(git_branch_set_upstream(branch, "origin/main"))
    }

    private func referenceOID(root: URL, name: String) throws -> String? {
        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        try require(git_repository_open(&repo, root.path))
        var oid = git_oid()
        let code = git_reference_name_to_id(&oid, repo, name)
        if code == GIT_ENOTFOUND.rawValue { return nil }
        try require(code)
        return String(cString: try XCTUnwrap(git_oid_tostr_s(&oid)))
    }

    private func committedMode(root: URL, oid: String, path: String) throws -> UInt32 {
        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        try require(git_repository_open(&repo, root.path))
        var parsed = git_oid()
        try require(git_oid_fromstr(&parsed, oid))
        var commit: OpaquePointer?
        defer { if let commit { git_commit_free(commit) } }
        try require(git_commit_lookup(&commit, repo, &parsed))
        var tree: OpaquePointer?
        defer { if let tree { git_tree_free(tree) } }
        try require(git_commit_tree(&tree, commit))
        var entry: OpaquePointer?
        defer { if let entry { git_tree_entry_free(entry) } }
        try require(git_tree_entry_bypath(&entry, tree, path))
        return UInt32(git_tree_entry_filemode(entry).rawValue)
    }

    private func blob(root: URL, oid: String, path: String) throws -> Data? {
        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        try require(git_repository_open(&repo, root.path))
        var parsed = git_oid()
        try require(git_oid_fromstr(&parsed, oid))
        var commit: OpaquePointer?
        defer { if let commit { git_commit_free(commit) } }
        try require(git_commit_lookup(&commit, repo, &parsed))
        var tree: OpaquePointer?
        defer { if let tree { git_tree_free(tree) } }
        try require(git_commit_tree(&tree, commit))
        var entry: OpaquePointer?
        defer { if let entry { git_tree_entry_free(entry) } }
        let lookup = git_tree_entry_bypath(&entry, tree, path)
        if lookup == GIT_ENOTFOUND.rawValue { return nil }
        try require(lookup)
        var blob: OpaquePointer?
        defer { if let blob { git_blob_free(blob) } }
        var target = try XCTUnwrap(git_tree_entry_id(entry)).pointee
        try require(git_blob_lookup(&blob, repo, &target))
        let size = Int(git_blob_rawsize(blob))
        return size == 0 ? Data() : Data(bytes: try XCTUnwrap(git_blob_rawcontent(blob)), count: size)
    }

    private func require(_ code: Int32) throws {
        if code < 0 {
            throw NSError(domain: "FolderGitTests", code: Int(code), userInfo: [NSLocalizedDescriptionKey: git_error_last()?.pointee.message.map { String(cString: $0) } ?? "Git error"])
        }
    }
}

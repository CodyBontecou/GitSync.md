import Foundation
import CryptoKit
import Darwin
import Clibgit2
import libgit2

enum GitTrackingRemovalError: LocalizedError, Sendable, Equatable {
    case unsupportedLocation
    case unsupportedBackupLocation
    case noGitDirectory
    case unsupportedMetadata(String)
    case activeGitOperation(String)
    case sourceChanged
    case sourceInsideRepository
    case backupInsideRepository
    case backupInRepository
    case differentVolume
    case insufficientStorage
    case backupVerificationFailed
    /// The original directory remains safely in this backup, but a different
    /// .git item appeared at the source and prevented restoring the original.
    /// Callers must keep the selected root unregistered from synchronization.
    case originalMetadataRelocated(backupURL: URL)
    /// Verification failed and the original could not be restored. No deletion
    /// has begun; the complete directory remains at this temporary location.
    case originalMetadataRelocatedWithoutBackup(metadataURL: URL)
    /// Tracking was detached, but deleting the quarantined metadata failed.
    /// History may be incomplete; callers must not reconnect this root.
    case metadataDeletionIncomplete(metadataURL: URL)
    case operation(String)

    var leavesRootDetached: Bool {
        switch self {
        case .originalMetadataRelocated, .originalMetadataRelocatedWithoutBackup, .metadataDeletionIncomplete:
            return true
        default:
            return false
        }
    }

    var errorDescription: String? {
        switch self {
        case .unsupportedLocation:
            return "Choose a local On My iPhone folder. Cloud and file-provider folders cannot be used for removing Git tracking."
        case .unsupportedBackupLocation:
            return "This backup location is not supported yet, including Downloads under On My iPhone. Select a local folder in GitSync.md or another app, outside any Git repository."
        case .noGitDirectory:
            return "The selected folder does not contain its own .git directory. Choose the repository's top-level folder."
        case .unsupportedMetadata(let detail):
            return "This repository cannot be detached safely: \(detail). No Git metadata was moved."
        case .activeGitOperation(let path):
            return "Git is busy or a lock file remains at \(path). Finish that Git operation before removing tracking."
        case .sourceChanged:
            return "The Git metadata changed after review. Review the folder again before removing tracking."
        case .sourceInsideRepository:
            return "The selected repository is inside another Git repository. Choose the parent repository first so removing its tracking cannot expose this folder to a parent Git operation."
        case .backupInsideRepository:
            return "Choose a backup folder outside the repository being detached."
        case .backupInRepository:
            return "Choose a backup folder outside other Git repositories so it cannot be synced or removed by Git operations."
        case .differentVolume:
            return "Choose a backup folder on the same local storage as this repository so its original Git metadata can be moved safely."
        case .insufficientStorage:
            return "There is not enough free storage to verify a complete backup. Free some storage and try again."
        case .backupVerificationFailed:
            return "The Git backup could not be verified. The original Git metadata remains in place."
        case .originalMetadataRelocated(let backupURL):
            return "The Git metadata changed while being moved and could not be restored to its original location. Its complete original directory is preserved in \(backupURL.path)/original-git-metadata. Keep this folder disconnected from synchronization. Restore the original metadata to .git before reconnecting it."
        case .originalMetadataRelocatedWithoutBackup(let metadataURL):
            return "Git tracking was detached, but the metadata changed and could not be restored. No history was deleted. The original metadata remains at \(metadataURL.path). Keep this folder disconnected from synchronization until you resolve it."
        case .metadataDeletionIncomplete(let metadataURL):
            return "Git tracking was removed, but some local Git history could not be deleted at \(metadataURL.path). Your notes and files remain in place. This temporary metadata is not a verified backup and may be incomplete. Keep it disconnected from synchronization."
        case .operation(let message):
            return message
        }
    }
}

struct GitTrackingRemovalPlan: Sendable, Equatable {
    let rootURL: URL
    let remoteURL: String?
    let branch: String?
    let metadataByteCount: Int64
    fileprivate let rootIdentity: GitTrackingRemovalService.Identity
    fileprivate let snapshot: GitTrackingRemovalService.Snapshot
}

struct GitTrackingRemovalResult: Sendable {
    /// Nil only after explicitly requested removal without preserving history.
    let backupURL: URL?
}

/// Detaches only the selected folder's own .git. Backup mode atomically retains
/// the original after verifying a separate copy. Explicit no-backup removal
/// verifies the relocated original, then deletes only reviewed metadata through
/// directory descriptors. Callers retain all selected folders' security scope.
final class GitTrackingRemovalService: @unchecked Sendable {
    private static let initializeLibrary: Void = { _ = git_libgit2_init() }()
    private let enforceLocalStorage: Bool
    private let copyMetadata: @Sendable (URL, URL) throws -> Void
    private let availableCapacity: @Sendable (URL) throws -> Int64
    private let deleteMetadata: (@Sendable (URL) throws -> Void)?

    init(
        enforceLocalStorage: Bool = true,
        copyMetadata: (@Sendable (URL, URL) throws -> Void)? = nil,
        availableCapacity: (@Sendable (URL) throws -> Int64)? = nil,
        deleteMetadata: (@Sendable (URL) throws -> Void)? = nil
    ) {
        _ = Self.initializeLibrary
        self.enforceLocalStorage = enforceLocalStorage
        self.copyMetadata = copyMetadata ?? { try FileManager.default.copyItem(at: $0, to: $1) }
        self.deleteMetadata = deleteMetadata
        self.availableCapacity = availableCapacity ?? { url in
            let values = try url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            if let capacity = values.volumeAvailableCapacityForImportantUsage { return capacity }
            let attributes = try FileManager.default.attributesOfFileSystem(forPath: url.path)
            return (attributes[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
        }
    }

    func inspect(at root: URL) async throws -> GitTrackingRemovalPlan {
        try await RepositoryOperationCoordinator.shared.withRepository(at: root) {
            try await self.inspectWithLease(at: root)
        }
    }

    /// For workflows that already hold the shared repository lease.
    func inspectWithLease(at root: URL) async throws -> GitTrackingRemovalPlan {
        try await Self.runLocal {
            try Self.coordinateReading(root) { coordinatedRoot in
                try self.validateLocation(coordinatedRoot)
                return try Self.makePlan(at: coordinatedRoot)
            }
        }
    }

    /// Checks a picker destination before presenting the removal confirmation.
    /// Removal repeats these checks under its own coordinated write operation.
    func validateBackupDirectory(_ directory: URL, for plan: GitTrackingRemovalPlan) async throws {
        guard !Self.isWithin(directory, root: plan.rootURL) else {
            throw GitTrackingRemovalError.backupInsideRepository
        }
        try await Self.runLocal {
            try Task.checkCancellation()
            try self.validateBackupLocation(directory)
            try Self.coordinateReading(directory) { destination in
                try self.validateBackupLocation(destination)
                guard !Self.isWithin(destination, root: plan.rootURL) else {
                    throw GitTrackingRemovalError.backupInsideRepository
                }
                try Self.assertGitFreeAncestors(of: destination, includeSelectedFolder: true, error: .backupInRepository)
                let destinationFD = try Self.openDirectory(destination)
                defer { close(destinationFD) }
                let destinationIdentity = try Self.identity(of: destinationFD)
                try Self.assertIdentity(destination, equals: destinationIdentity)
                try Self.assertMatches(plan, root: plan.rootURL)
                guard plan.rootIdentity.device == destinationIdentity.device else {
                    throw GitTrackingRemovalError.differentVolume
                }
            }
        }
    }

    /// Uses the app's Documents container, independently of the user's default
    /// repository save location. Existing backup contents are never replaced.
    func prepareAppBackupDirectory(in documents: URL) async throws -> URL {
        try await Self.runLocal {
            try Task.checkCancellation()
            try self.validateBackupLocation(documents)
            try Self.assertGitFreeAncestors(of: documents, includeSelectedFolder: true, error: .backupInRepository)
            let documentsFD = try Self.openDirectory(documents)
            defer { close(documentsFD) }
            let documentsIdentity = try Self.identity(of: documentsFD)
            try Self.assertIdentity(documents, equals: documentsIdentity)
            if mkdirat(documentsFD, "Git Backups", 0o700) != 0 && errno != EEXIST {
                throw Self.posixError("Create GitSync.md backup folder")
            }
            let destination = documents.appendingPathComponent("Git Backups", isDirectory: true)
            try Self.assertIdentity(documents, equals: documentsIdentity)
            try self.validateBackupLocation(destination)
            try Self.assertGitFreeAncestors(of: destination, includeSelectedFolder: true, error: .backupInRepository)
            return destination
        }
    }

    func removeTracking(_ plan: GitTrackingRemovalPlan, backupDirectory: URL) async throws -> GitTrackingRemovalResult {
        try await RepositoryOperationCoordinator.shared.withRepository(at: plan.rootURL) {
            try await self.removeTrackingWithLease(plan, backupDirectory: backupDirectory)
        }
    }

    /// The AppState caller holds this same lease until repository registrations
    /// and Background Sync enrollment have also been removed.
    func removeTrackingWithLease(_ plan: GitTrackingRemovalPlan, backupDirectory: URL) async throws -> GitTrackingRemovalResult {
        // Reject overlapping coordination requests before acquiring file
        // coordination; repeat this check using the accessor's actual URLs.
        guard !Self.isWithin(backupDirectory, root: plan.rootURL) else {
            throw GitTrackingRemovalError.backupInsideRepository
        }
        return try await Self.runLocal {
            try self.validateBackupLocation(backupDirectory)
            try Self.assertGitFreeAncestors(of: backupDirectory, includeSelectedFolder: true, error: .backupInRepository)
            return try Self.coordinateWriting(plan.rootURL, backupDirectory) { root, destination in
                try self.validateLocation(root)
                try self.validateBackupLocation(destination)
                guard Self.canonical(root) == Self.canonical(plan.rootURL) else {
                    throw GitTrackingRemovalError.sourceChanged
                }
                guard !Self.isWithin(destination, root: root) else {
                    throw GitTrackingRemovalError.backupInsideRepository
                }
                let rootFD = try Self.openDirectory(root)
                defer { close(rootFD) }
                let destinationFD = try Self.openDirectory(destination)
                defer { close(destinationFD) }
                let rootIdentity = try Self.identity(of: rootFD)
                let destinationIdentity = try Self.identity(of: destinationFD)
                guard rootIdentity == plan.rootIdentity else { throw GitTrackingRemovalError.sourceChanged }
                guard rootIdentity.device == destinationIdentity.device else { throw GitTrackingRemovalError.differentVolume }
                try Self.assertMatches(plan, root: root)
                try Task.checkCancellation()
                // One copied tree plus the original is retained in the backup.
                // The original's atomic move itself requires no second copy.
                let (required, overflow) = plan.metadataByteCount.addingReportingOverflow(1024 * 1024)
                guard !overflow, try self.availableCapacity(destination) >= required else {
                    throw GitTrackingRemovalError.insufficientStorage
                }
                try Self.assertGitFreeAncestors(of: destination, includeSelectedFolder: true, error: .backupInRepository)
                let name = "Git Backup \(root.lastPathComponent) \(UUID().uuidString)"
                guard mkdirat(destinationFD, name, 0o700) == 0 else { throw Self.posixError("Create Git backup") }
                let backup = destination.appendingPathComponent(name, isDirectory: true)
                let backupFD = openat(destinationFD, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW)
                guard backupFD >= 0 else { throw Self.posixError("Open Git backup") }
                defer { close(backupFD) }
                let backupIdentity = try Self.identity(of: backupFD)
                let copiedMetadata = backup.appendingPathComponent("git-metadata", isDirectory: true)
                let originalMetadata = backup.appendingPathComponent("original-git-metadata", isDirectory: true)
                try Self.restoreInstructions(for: plan).write(
                    to: backup.appendingPathComponent("README.txt"), atomically: false, encoding: .utf8
                )
                try self.copyMetadata(root.appendingPathComponent(".git", isDirectory: true), copiedMetadata)
                try Task.checkCancellation()
                let copiedSnapshot = try Self.snapshot(at: copiedMetadata)
                guard copiedSnapshot.sameContents(as: plan.snapshot) else {
                    throw GitTrackingRemovalError.backupVerificationFailed
                }
                // Recheck both contents and directory identities immediately
                // before moving. Descriptor-relative, exclusive rename prevents
                // a substituted path from broadening this operation's scope.
                try Self.assertMatches(plan, root: root)
                try Self.assertIdentity(root, equals: rootIdentity)
                try Self.assertIdentity(destination, equals: destinationIdentity)
                try Self.assertIdentity(backup, equals: backupIdentity)
                try Self.assertGitFreeAncestors(of: destination, includeSelectedFolder: true, error: .backupInRepository)
                guard try Self.snapshot(at: copiedMetadata).sameContents(as: plan.snapshot) else {
                    throw GitTrackingRemovalError.backupVerificationFailed
                }
                try Task.checkCancellation()
                guard renameatx_np(rootFD, ".git", backupFD, "original-git-metadata", UInt32(RENAME_EXCL)) == 0 else {
                    throw Self.posixError("Move the original Git metadata into its backup")
                }
                // A non-cooperating process could change .git during the final
                // checks. Retain its entire original directory, and restore it
                // when possible if it no longer matches the reviewed metadata.
                do {
                    try Self.validateMovedMetadata(at: originalMetadata, against: plan)
                    try Self.assertGitFreeAncestors(of: root, includeSelectedFolder: false, error: .sourceInsideRepository)
                    try Self.assertGitFreeAncestors(of: destination, includeSelectedFolder: true, error: .backupInRepository)
                    var unexpectedMetadata = stat()
                    if fstatat(rootFD, ".git", &unexpectedMetadata, AT_SYMLINK_NOFOLLOW) == 0 {
                        throw GitTrackingRemovalError.sourceChanged
                    }
                    guard errno == ENOENT else { throw Self.posixError("Confirm Git tracking was detached") }
                } catch {
                    if renameatx_np(backupFD, "original-git-metadata", rootFD, ".git", UInt32(RENAME_EXCL)) != 0 {
                        throw GitTrackingRemovalError.originalMetadataRelocated(backupURL: backup)
                    }
                    throw error
                }
                // Cancellation after the atomic move must report success so
                // AppState can finish removing stale repository registrations.
                return GitTrackingRemovalResult(backupURL: backup)
            }
        }
    }

    func removeTrackingWithoutBackup(_ plan: GitTrackingRemovalPlan) async throws -> GitTrackingRemovalResult {
        try await RepositoryOperationCoordinator.shared.withRepository(at: plan.rootURL) {
            try await self.removeTrackingWithoutBackupWithLease(plan)
        }
    }

    /// Holds the same removal boundary as backup mode, without requiring a
    /// destination or free space for a copy. The temporary directory is not a
    /// backup and is removed before success is reported.
    func removeTrackingWithoutBackupWithLease(_ plan: GitTrackingRemovalPlan) async throws -> GitTrackingRemovalResult {
        try await Self.runLocal {
            try Self.coordinateWriting(plan.rootURL) { root in
                try self.validateLocation(root)
                guard Self.canonical(root) == Self.canonical(plan.rootURL) else {
                    throw GitTrackingRemovalError.sourceChanged
                }
                let rootFD = try Self.openDirectory(root)
                defer { close(rootFD) }
                guard try Self.identity(of: rootFD) == plan.rootIdentity else {
                    throw GitTrackingRemovalError.sourceChanged
                }
                try Self.assertMatches(plan, root: root)
                try Self.assertIdentity(root, equals: plan.rootIdentity)
                try Task.checkCancellation()
                let name = ".gitsync-removing-git-\(UUID().uuidString)"
                let metadata = root.appendingPathComponent(name, isDirectory: true)
                guard renameatx_np(rootFD, ".git", rootFD, name, UInt32(RENAME_EXCL)) == 0 else {
                    throw Self.posixError("Detach Git tracking before deleting local history")
                }
                do {
                    try Self.validateMovedMetadata(at: metadata, against: plan)
                    try Self.assertIdentity(root, equals: plan.rootIdentity)
                    try Self.assertGitFreeAncestors(of: root, includeSelectedFolder: false, error: .sourceInsideRepository)
                    var unexpected = stat()
                    if fstatat(rootFD, ".git", &unexpected, AT_SYMLINK_NOFOLLOW) == 0 {
                        throw GitTrackingRemovalError.sourceChanged
                    }
                    guard errno == ENOENT else { throw Self.posixError("Confirm Git tracking was detached") }
                    try Task.checkCancellation()
                } catch {
                    // Restore only our original directory, never a substituted
                    // item and never overwrite a newly created .git.
                    var original = stat()
                    guard let reviewed = plan.snapshot.entries.first,
                          fstatat(rootFD, name, &original, AT_SYMLINK_NOFOLLOW) == 0,
                          Self.identity(original) == reviewed.identity,
                          original.st_mode & S_IFMT == S_IFDIR,
                          renameatx_np(rootFD, name, rootFD, ".git", UInt32(RENAME_EXCL)) == 0 else {
                        throw GitTrackingRemovalError.originalMetadataRelocatedWithoutBackup(metadataURL: metadata)
                    }
                    throw error
                }
                // Deletion is irreversible from this point. Ignore cancellation
                // during cleanup, and finish unregistering the detached root.
                // If cleanup fails, never restore a partially deleted .git.
                do {
                    if let deleteMetadata = self.deleteMetadata {
                        try deleteMetadata(metadata)
                    } else {
                        try Self.deleteReviewedMetadata(parentFD: rootFD, name: name, snapshot: plan.snapshot)
                    }
                    var remaining = stat()
                    if fstatat(rootFD, name, &remaining, AT_SYMLINK_NOFOLLOW) == 0 {
                        throw GitTrackingRemovalError.sourceChanged
                    }
                    guard errno == ENOENT else { throw Self.posixError("Confirm local Git history was deleted") }
                } catch {
                    throw GitTrackingRemovalError.metadataDeletionIncomplete(metadataURL: metadata)
                }
                return GitTrackingRemovalResult(backupURL: nil)
            }
        }
    }

    /// Deletes reviewed paths inside the isolated original metadata tree.
    /// Descriptor-relative lookups reject encountered replacements and never
    /// follow links into the working files. POSIX unlink has no atomic inode
    /// condition, so a non-cooperating writer can still race a final lookup;
    /// its effects remain confined to this metadata namespace. Unknown paths
    /// are never traversed and make the final rmdir fail closed.
    private static func deleteReviewedMetadata(parentFD: Int32, name: String, snapshot: Snapshot) throws {
        let children = Dictionary(grouping: snapshot.entries.filter { !$0.path.isEmpty }) {
            ($0.path as NSString).deletingLastPathComponent
        }
        guard let root = snapshot.entries.first, root.path.isEmpty else {
            throw GitTrackingRemovalError.sourceChanged
        }

        func matchesReviewed(_ status: stat, _ entry: Entry) -> Bool {
            identity(status) == entry.identity && UInt16(status.st_mode) == entry.mode
                && (status.st_mode & S_IFMT != S_IFREG || (status.st_nlink == 1 && Int64(status.st_size) == entry.size))
                && Int64(status.st_mtimespec.tv_sec) == entry.modifiedSeconds
                && Int64(status.st_mtimespec.tv_nsec) == entry.modifiedNanoseconds
        }

        func delete(_ entry: Entry, parent: Int32, itemName: String) throws {
            let isDirectory = entry.mode & UInt16(S_IFMT) == UInt16(S_IFDIR)
            let flags = O_RDONLY | O_NOFOLLOW | (isDirectory ? O_DIRECTORY : 0)
            let descriptor = openat(parent, itemName, flags)
            guard descriptor >= 0 else { throw posixError("Open reviewed Git metadata for deletion") }
            defer { close(descriptor) }
            let opened = try fileStatus(descriptor)
            guard matchesReviewed(opened, entry) else { throw GitTrackingRemovalError.sourceChanged }
            if isDirectory {
                for child in children[entry.path, default: []] {
                    try delete(child, parent: descriptor, itemName: (child.path as NSString).lastPathComponent)
                }
            } else {
                // Recheck bytes and read stability without honoring cancellation
                // once another entry may already have been deleted.
                let file = FileHandle(fileDescriptor: descriptor, closeOnDealloc: false)
                var hasher = SHA256()
                while let data = try file.read(upToCount: 1024 * 1024), !data.isEmpty { hasher.update(data: data) }
                let digest = hasher.finalize().map { String(format: "%02x", $0) }.joined()
                guard digest == entry.digest, sameStatus(opened, try fileStatus(descriptor)) else {
                    throw GitTrackingRemovalError.sourceChanged
                }
            }
            var current = stat()
            guard fstatat(parent, itemName, &current, AT_SYMLINK_NOFOLLOW) == 0,
                  identity(current) == entry.identity, UInt16(current.st_mode) == entry.mode else {
                throw GitTrackingRemovalError.sourceChanged
            }
            if !isDirectory && !sameStatus(opened, current) { throw GitTrackingRemovalError.sourceChanged }
            guard unlinkat(parent, itemName, isDirectory ? AT_REMOVEDIR : 0) == 0 else {
                throw posixError("Delete reviewed local Git history")
            }
        }
        try delete(root, parent: parentFD, itemName: name)
    }

    private func validateLocation(_ url: URL) throws {
        guard url.isFileURL else { throw GitTrackingRemovalError.unsupportedLocation }
        let attributes = try Self.fileStatus(url)
        let values = try url.resourceValues(forKeys: [.isUbiquitousItemKey, .isPackageKey])
        guard attributes.st_mode & S_IFMT == S_IFDIR, values.isUbiquitousItem != true,
              values.isPackage != true else { throw GitTrackingRemovalError.unsupportedLocation }
        guard !enforceLocalStorage || FolderGitService.isLocalDocumentsLocation(url) else {
            throw GitTrackingRemovalError.unsupportedLocation
        }
    }

    private func validateBackupLocation(_ url: URL) throws {
        do {
            try validateLocation(url)
        } catch GitTrackingRemovalError.unsupportedLocation {
            throw GitTrackingRemovalError.unsupportedBackupLocation
        }
    }

    private static func makePlan(at root: URL) throws -> GitTrackingRemovalPlan {
        try Task.checkCancellation()
        let rootIdentity = try identity(root)
        let metadata = root.appendingPathComponent(".git", isDirectory: true)
        var status = stat()
        guard lstat(metadata.path, &status) == 0 else {
            if errno == ENOENT { throw GitTrackingRemovalError.noGitDirectory }
            throw posixError("Read Git metadata")
        }
        guard status.st_mode & S_IFMT == S_IFDIR else {
            throw GitTrackingRemovalError.unsupportedMetadata(".git is a link or a gitdir pointer")
        }
        try assertGitFreeAncestors(of: root, includeSelectedFolder: false, error: .sourceInsideRepository)
        let initial = try snapshot(at: metadata)
        let details = try repositoryDetails(root, metadata: metadata)
        let afterOpen = try snapshot(at: metadata)
        guard initial == afterOpen else { throw GitTrackingRemovalError.sourceChanged }
        return GitTrackingRemovalPlan(rootURL: root.standardizedFileURL, remoteURL: details.remote,
                                      branch: details.branch, metadataByteCount: initial.bytes,
                                      rootIdentity: rootIdentity, snapshot: initial)
    }

    private static func repositoryDetails(_ root: URL, metadata: URL) throws -> (remote: String?, branch: String?) {
        for path in ["MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply", "sequencer"] {
            if FileManager.default.fileExists(atPath: metadata.appendingPathComponent(path).path) {
                throw GitTrackingRemovalError.activeGitOperation(path)
            }
        }
        for path in ["commondir", "gitdir", "worktrees", "modules", "objects/info/alternates", "objects/info/http-alternates"] {
            if FileManager.default.fileExists(atPath: metadata.appendingPathComponent(path).path) {
                throw GitTrackingRemovalError.unsupportedMetadata("linked worktrees, submodule metadata, or alternate object stores require a Git desktop client")
            }
        }
        var repository: OpaquePointer?
        defer { if let repository { git_repository_free(repository) } }
        try check(git_repository_open_ext(&repository, root.path, UInt32(GIT_REPOSITORY_OPEN_NO_SEARCH.rawValue), nil), "Open the selected repository")
        guard git_repository_is_bare(repository) == 0,
              let workingDirectory = git_repository_workdir(repository),
              canonical(URL(fileURLWithPath: String(cString: workingDirectory))) == canonical(root),
              let repositoryPath = git_repository_path(repository),
              canonical(URL(fileURLWithPath: String(cString: repositoryPath))) == canonical(metadata) else {
            throw GitTrackingRemovalError.unsupportedMetadata("the Git directory redirects to a different working folder or is bare")
        }
        var liveConfig: OpaquePointer?
        defer { if let liveConfig { git_config_free(liveConfig) } }
        try check(git_config_open_ondisk(&liveConfig, metadata.appendingPathComponent("config").path), "Read repository configuration")
        // libgit2's borrowed string API requires an immutable snapshot. Keep
        // this scoped to the selected .git/config instead of merging global
        // configuration into the repository-layout safety checks.
        var config: OpaquePointer?
        defer { if let config { git_config_free(config) } }
        try check(git_config_snapshot(&config, liveConfig), "Snapshot repository configuration")
        var worktree: UnsafePointer<CChar>?
        let worktreeCode = git_config_get_string(&worktree, config, "core.worktree")
        if worktreeCode != GIT_ENOTFOUND.rawValue {
            try check(worktreeCode, "Read repository working folder")
            throw GitTrackingRemovalError.unsupportedMetadata("core.worktree redirects the repository")
        }
        var bare: Int32 = 0
        let bareCode = git_config_get_bool(&bare, config, "core.bare")
        if bareCode != GIT_ENOTFOUND.rawValue { try check(bareCode, "Read repository type") }
        guard bare == 0 else { throw GitTrackingRemovalError.unsupportedMetadata("bare repositories have no working folder") }
        var iterator: OpaquePointer?
        defer { if let iterator { git_config_iterator_free(iterator) } }
        try check(git_config_iterator_new(&iterator, config), "Read repository configuration")
        var entry: UnsafeMutablePointer<git_config_entry>?
        while true {
            let code = git_config_next(&entry, iterator)
            if code == GIT_ITEROVER.rawValue { break }
            try check(code, "Read repository configuration")
            let name = entry?.pointee.name.map { String(cString: $0).lowercased() } ?? ""
            guard !name.hasPrefix("include."), !name.hasPrefix("includeif.") else {
                throw GitTrackingRemovalError.unsupportedMetadata("configuration includes files outside this Git directory")
            }
        }
        var remoteValue: UnsafePointer<CChar>?
        let remoteCode = git_config_get_string(&remoteValue, config, "remote.origin.url")
        let remote: String?
        if remoteCode == GIT_ENOTFOUND.rawValue { remote = nil }
        else { try check(remoteCode, "Read origin"); remote = remoteValue.map { String(cString: $0) } }
        var head: OpaquePointer?
        defer { if let head { git_reference_free(head) } }
        let headCode = git_repository_head(&head, repository)
        let branch: String?
        if headCode == GIT_EUNBORNBRANCH.rawValue || headCode == GIT_ENOTFOUND.rawValue {
            // Symbolic HEAD still names an unborn branch.
            var symbolic: OpaquePointer?
            defer { if let symbolic { git_reference_free(symbolic) } }
            if git_reference_lookup(&symbolic, repository, "HEAD") == 0,
               let target = git_reference_symbolic_target(symbolic) {
                branch = String(cString: target).replacingOccurrences(of: "refs/heads/", with: "")
            } else { branch = nil }
        } else {
            try check(headCode, "Read current branch")
            branch = git_reference_is_branch(head) != 0 ? git_reference_shorthand(head).map { String(cString: $0) } : nil
        }
        return (remote, branch)
    }

    fileprivate struct Identity: Sendable, Equatable {
        let device: Int32
        let inode: UInt64
    }

    fileprivate struct Entry: Sendable, Equatable {
        let path: String
        let identity: Identity
        let mode: UInt16
        let size: Int64
        let modifiedSeconds: Int64
        let modifiedNanoseconds: Int64
        let changedSeconds: Int64
        let changedNanoseconds: Int64
        let digest: String?

        func sameContents(as other: Entry) -> Bool {
            path == other.path && mode == other.mode && size == other.size && digest == other.digest
        }
    }

    fileprivate struct Snapshot: Sendable, Equatable {
        let entries: [Entry]
        let bytes: Int64

        func sameContents(as other: Snapshot) -> Bool {
            entries.count == other.entries.count && zip(entries, other.entries).allSatisfy { $0.sameContents(as: $1) }
        }

        func matchesMovedOriginal(_ other: Snapshot) -> Bool {
            guard entries.count == other.entries.count else { return false }
            return zip(entries, other.entries).allSatisfy { actual, expected in
                // iOS can update ctime on descendants as well as the root when
                // moving a directory. The pre-move comparison remains strict;
                // afterward require the same objects, permissions, modification
                // times and every file's bytes, allowing only ctime to differ.
                actual.sameContents(as: expected) && actual.identity == expected.identity
                    && actual.modifiedSeconds == expected.modifiedSeconds && actual.modifiedNanoseconds == expected.modifiedNanoseconds
            }
        }
    }

    private static func snapshot(at metadata: URL) throws -> Snapshot {
        var entries: [Entry] = []
        var bytes: Int64 = 0
        func visit(_ url: URL, relative: String) throws {
            try Task.checkCancellation()
            guard entries.count < 200_000 else { throw GitTrackingRemovalError.unsupportedMetadata("the Git directory is too large to verify on this device") }
            let before = try fileStatus(url)
            let kind = before.st_mode & S_IFMT
            guard kind == S_IFDIR || kind == S_IFREG else {
                throw GitTrackingRemovalError.unsupportedMetadata("Git metadata contains a link or unsupported file at \(relative)")
            }
            if kind == S_IFREG && before.st_nlink > 1 {
                throw GitTrackingRemovalError.unsupportedMetadata("Git metadata contains hard-linked files at \(relative)")
            }
            if url.lastPathComponent.hasSuffix(".lock") {
                throw GitTrackingRemovalError.activeGitOperation(relative)
            }
            let size = kind == S_IFREG ? Int64(before.st_size) : 0
            let digest = kind == S_IFREG ? try hash(url, expected: before) : nil
            let (nextBytes, overflow) = bytes.addingReportingOverflow(size)
            guard !overflow else { throw GitTrackingRemovalError.insufficientStorage }
            bytes = nextBytes
            entries.append(Entry(path: relative, identity: identity(before), mode: UInt16(before.st_mode), size: size,
                                 modifiedSeconds: Int64(before.st_mtimespec.tv_sec), modifiedNanoseconds: Int64(before.st_mtimespec.tv_nsec),
                                 changedSeconds: Int64(before.st_ctimespec.tv_sec), changedNanoseconds: Int64(before.st_ctimespec.tv_nsec), digest: digest))
            if kind == S_IFDIR {
                for child in try FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: nil).sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                    let path = relative.isEmpty ? child.lastPathComponent : relative + "/" + child.lastPathComponent
                    try visit(child, relative: path)
                }
            }
            let after = try fileStatus(url)
            guard sameStatus(before, after) else { throw GitTrackingRemovalError.sourceChanged }
        }
        try visit(metadata, relative: "")
        return Snapshot(entries: entries, bytes: bytes)
    }

    private static func hash(_ url: URL, expected: stat) throws -> String {
        let descriptor = open(url.path, O_RDONLY | O_NOFOLLOW)
        guard descriptor >= 0 else { throw posixError("Read Git metadata") }
        let file = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
        let opened = try fileStatus(descriptor)
        guard sameStatus(expected, opened) else { throw GitTrackingRemovalError.sourceChanged }
        var hasher = SHA256()
        while let data = try file.read(upToCount: 1024 * 1024), !data.isEmpty {
            try Task.checkCancellation()
            hasher.update(data: data)
        }
        let read = try fileStatus(descriptor)
        guard sameStatus(expected, read) else { throw GitTrackingRemovalError.sourceChanged }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func assertMatches(_ plan: GitTrackingRemovalPlan, root: URL) throws {
        try assertGitFreeAncestors(of: root, includeSelectedFolder: false, error: .sourceInsideRepository)
        guard try identity(root) == plan.rootIdentity else { throw GitTrackingRemovalError.sourceChanged }
        let actual = try snapshot(at: root.appendingPathComponent(".git", isDirectory: true))
        guard actual == plan.snapshot else {
            throw GitTrackingRemovalError.sourceChanged
        }
        _ = try repositoryDetails(root, metadata: root.appendingPathComponent(".git", isDirectory: true))
    }

    /// Used only after the original directory has been atomically moved. The
    /// pre-move review still requires an exact snapshot, including ctime.
    static func validateMovedMetadata(at metadata: URL, against plan: GitTrackingRemovalPlan) throws {
        var moved: Snapshot?
        // Filesystem bookkeeping may settle during the first read after rename.
        // Retry only an unstable read; every attempt retains strict read checks,
        // and the final result must still match all reviewed data and identities.
        for attempt in 0..<3 {
            do {
                moved = try snapshot(at: metadata)
                break
            } catch GitTrackingRemovalError.sourceChanged where attempt < 2 {
                usleep(20_000)
            }
        }
        guard let moved, moved.matchesMovedOriginal(plan.snapshot) else {
            throw GitTrackingRemovalError.sourceChanged
        }
    }

    private static func identity(_ status: stat) -> Identity { Identity(device: status.st_dev, inode: UInt64(status.st_ino)) }
    private static func identity(_ url: URL) throws -> Identity { identity(try fileStatus(url)) }
    private static func identity(of descriptor: Int32) throws -> Identity { identity(try fileStatus(descriptor)) }
    private static func assertIdentity(_ url: URL, equals expected: Identity) throws {
        guard try identity(url) == expected else { throw GitTrackingRemovalError.sourceChanged }
    }
    private static func sameStatus(_ first: stat, _ second: stat) -> Bool {
        identity(first) == identity(second) && first.st_mode == second.st_mode && first.st_size == second.st_size
            && first.st_mtimespec.tv_sec == second.st_mtimespec.tv_sec && first.st_mtimespec.tv_nsec == second.st_mtimespec.tv_nsec
            && first.st_ctimespec.tv_sec == second.st_ctimespec.tv_sec && first.st_ctimespec.tv_nsec == second.st_ctimespec.tv_nsec
    }
    private static func fileStatus(_ url: URL) throws -> stat {
        var value = stat()
        guard lstat(url.path, &value) == 0 else { throw posixError("Read Git metadata") }
        return value
    }
    private static func fileStatus(_ descriptor: Int32) throws -> stat {
        var value = stat()
        guard fstat(descriptor, &value) == 0 else { throw posixError("Read Git metadata") }
        return value
    }
    private static func openDirectory(_ url: URL) throws -> Int32 {
        let descriptor = open(url.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW)
        guard descriptor >= 0 else { throw posixError("Open selected folder") }
        return descriptor
    }
    private static func canonical(_ url: URL) -> String { RepositoryOperationCoordinator.canonicalKey(for: url) }
    /// Only an actual .git item establishes a repository boundary. Ordinary
    /// folders named HEAD, objects, refs, or config do not qualify. Unexpected
    /// metadata types and unreadable ancestor locations fail closed.
    private static func assertGitFreeAncestors(
        of url: URL, includeSelectedFolder: Bool, error: GitTrackingRemovalError
    ) throws {
        let selected = url.standardizedFileURL.resolvingSymlinksInPath()
        let boundedByDocuments = FolderGitService.isLocalDocumentsLocation(selected)
        // URL.deletingLastPathComponent can grow / into /.. for directory URLs.
        // Canonical path strings and a strictly shrinking bound terminate the
        // fixture traversal at the filesystem root as well as Documents.
        var cursorPath = includeSelectedFolder ? selected.path : (selected.path as NSString).deletingLastPathComponent
        var cursor = URL(fileURLWithPath: cursorPath, isDirectory: true)
        // The app container's Documents folder is itself a valid selected root;
        // its parent is outside the granted local Documents scope.
        if boundedByDocuments && !FolderGitService.isLocalDocumentsLocation(cursor) { return }
        while !cursorPath.isEmpty {
            try Task.checkCancellation()
            var status = stat()
            let metadata = cursor.appendingPathComponent(".git")
            if lstat(metadata.path, &status) == 0 { throw error }
            guard errno == ENOENT else { throw error }
            let parentPath = (cursorPath as NSString).deletingLastPathComponent
            if parentPath.count >= cursorPath.count || parentPath.isEmpty { break }
            let parent = URL(fileURLWithPath: parentPath, isDirectory: true)
            if boundedByDocuments && !FolderGitService.isLocalDocumentsLocation(parent) { break }
            cursor = parent
            cursorPath = parentPath
        }
    }
    private static func isWithin(_ destination: URL, root: URL) -> Bool {
        let rootPath = canonical(root)
        let destinationPath = canonical(destination)
        return destinationPath == rootPath || destinationPath.hasPrefix(rootPath + "/")
    }
    private static func posixError(_ context: String) -> GitTrackingRemovalError {
        .operation("\(context): \(String(cString: strerror(errno)))")
    }
    private static func check(_ code: Int32, _ context: String) throws {
        guard code >= 0 else {
            throw GitTrackingRemovalError.operation("\(context): \(git_error_last()?.pointee.message.map { String(cString: $0) } ?? "Git operation failed")")
        }
    }
    private static func restoreInstructions(for plan: GitTrackingRemovalPlan) -> String {
        """
        Git tracking backup

        Original folder: \(plan.rootURL.path)
        Origin: \(plan.remoteURL ?? "No origin remote")
        Branch: \(plan.branch ?? "Detached or unknown")

        Your working files were left in their original folder. The GitHub repository was not changed.

        When original-git-metadata is present, it contains the complete original .git directory,
        moved here atomically. git-metadata is a separately verified copy of the reviewed metadata.

        If original-git-metadata is absent, removal did not finish. git-metadata may be incomplete
        or older than the original folder's Git metadata. Keep using the original folder's .git;
        do not replace it with a copy from this incomplete backup.

        To restore tracking, first close GitSync.md and any other Git tools. Make sure the original
        folder has no .git item. Move original-git-metadata into the original folder and rename it
        to .git. If the original directory is unavailable, use git-metadata instead. Never merge
        this metadata into another .git directory. Then reopen the folder as an existing repository.

        Keep this backup private: Git metadata can contain remote URLs and historical file contents.
        """
    }
    private static func runLocal<T: Sendable>(_ operation: @escaping @Sendable () throws -> T) async throws -> T {
        let task = Task.detached(priority: .userInitiated) { try operation() }
        return try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
    }
    private static func coordinateReading<T>(_ root: URL, operation: @escaping (URL) throws -> T) throws -> T {
        var result: Result<T, Error>?
        var coordinationError: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(readingItemAt: root, options: .withoutChanges, error: &coordinationError) { coordinatedRoot in
            result = Result { try operation(coordinatedRoot) }
        }
        if let coordinationError { throw coordinationError }
        guard let result else { throw GitTrackingRemovalError.operation("The selected folder could not be coordinated.") }
        return try result.get()
    }
    private static func coordinateWriting<T>(_ root: URL, _ destination: URL, operation: @escaping (URL, URL) throws -> T) throws -> T {
        var result: Result<T, Error>?
        var coordinationError: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(writingItemAt: root, options: .forMerging,
            writingItemAt: destination, options: .forMerging, error: &coordinationError) { coordinatedRoot, coordinatedDestination in
            result = Result { try operation(coordinatedRoot, coordinatedDestination) }
        }
        if let coordinationError { throw coordinationError }
        guard let result else { throw GitTrackingRemovalError.operation("The repository and backup folders could not be coordinated.") }
        return try result.get()
    }
    private static func coordinateWriting<T>(_ root: URL, operation: @escaping (URL) throws -> T) throws -> T {
        var result: Result<T, Error>?
        var coordinationError: NSError?
        NSFileCoordinator(filePresenter: nil).coordinate(writingItemAt: root, options: .forMerging, error: &coordinationError) { coordinatedRoot in
            result = Result { try operation(coordinatedRoot) }
        }
        if let coordinationError { throw coordinationError }
        guard let result else { throw GitTrackingRemovalError.operation("The selected folder could not be coordinated.") }
        return try result.get()
    }
}

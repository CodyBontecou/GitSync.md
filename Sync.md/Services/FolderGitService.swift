import Foundation
import CryptoKit
import Darwin
import Clibgit2
import libgit2

enum FolderGitServiceError: LocalizedError, Sendable {
    case unsupportedLocation
    case existingMetadata(String)
    case unsupportedFile(String)
    case reviewChanged(String)
    case invalidSelection
    case ownershipMismatch
    case selectionTooLarge
    case folderTooManyFiles
    case insufficientStorage(required: Int64, available: Int64)
    case operation(String)

    var errorDescription: String? {
        switch self {
        case .unsupportedLocation:
            return "Choose a local On My iPhone folder. Cloud and file-provider folders are not supported for publication yet."
        case .existingMetadata(let path):
            return "This folder contains or belongs to an existing Git repository (\(path)). Use the existing repository instead."
        case .unsupportedFile(let path):
            return "\(path) is unsupported for folder publication. This version supports regular files up to 10 MiB without Git LFS, custom filters, or content-conversion attributes."
        case .reviewChanged(let path):
            return "\(path) changed after review. Review the folder again before preparing a commit."
        case .invalidSelection:
            return "Select at least one eligible, non-ignored file to publish."
        case .ownershipMismatch:
            return "The Git metadata does not belong to this publication attempt. No existing metadata was replaced."
        case .selectionTooLarge:
            return "Select at most 10,000 files totaling 256 MiB for one initial publication."
        case .folderTooManyFiles:
            return "This folder contains more than 50,000 files. Choose a smaller folder so its contents can be reviewed completely."
        case .insufficientStorage(let required, let available):
            let needed = ByteCountFormatter.string(fromByteCount: required, countStyle: .file)
            let free = ByteCountFormatter.string(fromByteCount: available, countStyle: .file)
            return "Preparing this publication needs approximately \(needed) of additional storage; \(free) is available. Free some storage and retry."
        case .operation(let message):
            return message
        }
    }
}

struct FolderPublicationResourceEstimate: Sendable, Equatable {
    let fileCount: Int
    let selectedBytes: Int64
    let estimatedAdditionalBytes: Int64
}

/// A deliberately narrow first-publication implementation. Network operations
/// never run inside a file-coordination accessor, and no working file is written.
struct FolderGitService: FolderGitHandling {
    static let maximumFileBytes: Int64 = 10 * 1024 * 1024
    static let maximumSelectedFiles = 10_000
    static let maximumSelectedBytes: Int64 = 256 * 1024 * 1024
    static let maximumInspectedFiles = 50_000
    private static let initializeLibrary: Void = { _ = git_libgit2_init() }()
    private let enforceLocalStorage: Bool
    private let availableCapacityProvider: @Sendable (URL) throws -> Int64

    /// Disabling the location allowlist is intended only for temporary fixtures.
    init(enforceLocalStorage: Bool = true, availableCapacityProvider: (@Sendable (URL) throws -> Int64)? = nil) {
        _ = Self.initializeLibrary
        self.enforceLocalStorage = enforceLocalStorage
        self.availableCapacityProvider = availableCapacityProvider ?? Self.availableStorage
    }

    private struct Marker: Codable, Equatable {
        let workflowID: UUID
        let files: [FolderPublicationFile]
        let selectedPaths: [String]
        let authorName: String
        let authorEmail: String
        let message: String
        let timestamp: Int64
        var commitOID: String?
        var treeOID: String?
        var remoteURL: String?
    }

    private struct TreeFile {
        let path: String
        let oid: git_oid
        let mode: UInt32
    }

    func inspect(at url: URL) async throws -> FolderInspection {
        try await RepositoryOperationCoordinator.shared.withRepository(at: url) {
            try await Self.runLocal {
                try Self.coordinate(at: url, writing: false) { root in
                    try self.validateLocation(root)
                    let files = try Self.inspectFiles(root, ownedWorkflow: nil)
                    var warnings = files.isEmpty ? ["Git does not preserve empty directories. Add a file before publishing."] : []
                    if files.contains(where: { !$0.isIgnored && $0.size > Self.maximumFileBytes }) {
                        warnings.append("Files larger than 10 MiB must be excluded from this first publication.")
                    }
                    try Self.withInspectionRepository(workdir: root) { repo in
                        for file in files where !file.isIgnored {
                            do { try Self.rejectUnsupportedAttributes(repo, path: file.path) }
                            catch FolderGitServiceError.unsupportedFile {
                                warnings.append("\(file.path) uses Git LFS, a custom filter, or content-conversion attributes and must be excluded.")
                            }
                        }
                    }
                    return FolderInspection(files: files, warnings: warnings)
                }
            }
        }
    }

    func prepare(
        at url: URL, workflowID: UUID, files: [FolderPublicationFile], selectedPaths: Set<String>,
        authorName: String, authorEmail: String, message: String
    ) async throws -> FolderPreparedCommit {
        try await RepositoryOperationCoordinator.shared.withRepository(at: url) {
            try await Self.runLocal {
                try Self.coordinate(at: url, writing: true) { root in
                    try self.validateLocation(root)
                    return try Self.prepareLocal(
                        root, workflowID: workflowID, files: files, selectedPaths: selectedPaths,
                        authorName: authorName.trimmingCharacters(in: .whitespacesAndNewlines),
                        authorEmail: authorEmail.trimmingCharacters(in: .whitespacesAndNewlines), message: message,
                        availableCapacityProvider: self.availableCapacityProvider
                    )
                }
            }
        }
    }

    func validatePrepared(at url: URL, workflowID: UUID, commitOID: String) async throws {
        try await RepositoryOperationCoordinator.shared.withRepository(at: url) {
            try await self.validateOwnedPrepared(at: url, workflowID: workflowID, commitOID: commitOID)
        }
    }

    func publish(at url: URL, workflowID: UUID, commitOID: String, remoteURL: String, token: String) async throws {
        try await RepositoryOperationCoordinator.shared.withRepository(at: url) {
            try await self.validateOwnedPrepared(at: url, workflowID: workflowID, commitOID: commitOID)
            try await self.configureOrigin(at: url, workflowID: workflowID, commitOID: commitOID, remoteURL: remoteURL)
            try Task.checkCancellation()
            // The server's live destination advertisement must still be absent.
            // A saved OID pins the reviewed commit even if another client moves HEAD.
            try await LocalGitService(localURL: url).pushReviewedNonLFSBranch(
                pat: token,
                safetyExpectation: PushSafetyExpectation(
                    branch: "main", localCommitSHA: commitOID,
                    remoteCommitSHA: String(repeating: "0", count: 40), remoteURL: remoteURL
                )
            )
        }
    }

    func finish(at url: URL, workflowID: UUID, commitOID: String, remoteURL: String, token: String) async throws {
        try await RepositoryOperationCoordinator.shared.withRepository(at: url) {
            try await self.validateOwnedPrepared(at: url, workflowID: workflowID, commitOID: commitOID)
            try await self.configureOrigin(at: url, workflowID: workflowID, commitOID: commitOID, remoteURL: remoteURL)
            try Task.checkCancellation()
            try await LocalGitService(localURL: url).fetchRemote(pat: token)
            try await Self.runLocal {
                try Self.coordinate(at: url, writing: true) { root in
                    try self.validateLocation(root)
                    try Self.withOwnedRepository(root, workflowID: workflowID) { repo, marker in
                        try Self.validateCommit(repo, marker: marker, commitOID: commitOID)
                        try Self.assertOrigin(repo, remoteURL: remoteURL)
                        var remoteRef: OpaquePointer?
                        defer { if let remoteRef { git_reference_free(remoteRef) } }
                        try Self.check(git_reference_lookup(&remoteRef, repo, "refs/remotes/origin/main"), "Read published branch")
                        guard let target = git_reference_target(remoteRef), Self.hex(target.pointee) == commitOID else {
                            throw FolderGitServiceError.operation("The remote main branch does not match the saved commit. Publication needs attention.")
                        }
                        var branch: OpaquePointer?
                        defer { if let branch { git_reference_free(branch) } }
                        try Self.check(git_branch_lookup(&branch, repo, "main", GIT_BRANCH_LOCAL), "Read local main branch")
                        try Self.check(git_branch_set_upstream(branch, "origin/main"), "Configure branch tracking")
                    }
                }
            }
        }
    }

    private func validateOwnedPrepared(at url: URL, workflowID: UUID, commitOID: String) async throws {
        try await Self.runLocal {
            try Self.coordinate(at: url, writing: false) { root in
                try self.validateLocation(root)
                try Self.withOwnedRepository(root, workflowID: workflowID) { repo, marker in
                    try Self.validateCommit(repo, marker: marker, commitOID: commitOID)
                }
            }
        }
    }

    private func configureOrigin(at url: URL, workflowID: UUID, commitOID: String, remoteURL: String) async throws {
        try await Self.runLocal {
            try Self.coordinate(at: url, writing: true) { root in
                try self.validateLocation(root)
                try Self.withOwnedRepository(root, workflowID: workflowID) { repo, existingMarker in
                    try Self.validateCommit(repo, marker: existingMarker, commitOID: commitOID)
                    var marker = existingMarker
                    guard !remoteURL.isEmpty, marker.remoteURL == nil || marker.remoteURL == remoteURL else {
                        throw FolderGitServiceError.ownershipMismatch
                    }
                    marker.remoteURL = remoteURL
                    try Self.writeMarker(marker, root: root)
                    var remote: OpaquePointer?
                    defer { if let remote { git_remote_free(remote) } }
                    let code = git_remote_lookup(&remote, repo, "origin")
                    if code == GIT_ENOTFOUND.rawValue {
                        try Self.check(git_remote_create(&remote, repo, "origin", remoteURL), "Configure origin")
                    } else {
                        try Self.check(code, "Read origin")
                        try Self.assertOrigin(repo, remoteURL: remoteURL)
                    }
                    try Self.withConfig(repo) { config in
                        try Self.check(git_config_set_string(config, "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*"), "Configure origin fetch")
                        try Self.check(git_config_set_bool(config, "core.precomposeunicode", 1), "Configure Unicode paths")
                    }
                }
            }
        }
    }

    private func validateLocation(_ root: URL) throws {
        guard root.isFileURL else { throw FolderGitServiceError.unsupportedLocation }
        let values = try root.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey, .isPackageKey, .isUbiquitousItemKey])
        guard values.isDirectory == true, values.isSymbolicLink != true, values.isPackage != true,
              values.isUbiquitousItem != true else { throw FolderGitServiceError.unsupportedLocation }
        guard enforceLocalStorage else { return }
        let components = root.standardizedFileURL.resolvingSymlinksInPath().pathComponents
        let documentRoot = components.indices.contains { index in
            guard index + 4 < components.count, components[index] == "Containers", components[index + 1] == "Data",
                  components[index + 2] == "Application", UUID(uuidString: components[index + 3]) != nil,
                  components[index + 4] == "Documents" else { return false }
            let deviceContainer = Array(components[..<index]) == ["/", "private", "var", "mobile"]
            let simulatorContainer = index >= 4 && components[index - 4] == "CoreSimulator"
                && components[index - 3] == "Devices" && UUID(uuidString: components[index - 2]) != nil
                && components[index - 1] == "data"
            return deviceContainer || simulatorContainer
        }
        guard documentRoot else { throw FolderGitServiceError.unsupportedLocation }
    }

    /// A temporary Git directory lets libgit2 evaluate actual nested ignore and
    /// attribute rules without adding any metadata to the user's folder.
    private static func withInspectionRepository<T>(workdir: URL, body: (OpaquePointer?) throws -> T) throws -> T {
        let temporary = FileManager.default.temporaryDirectory.appendingPathComponent("folder-inspection-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: temporary) }
        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        try check(git_repository_init(&repo, temporary.path, 1), "Create inspection metadata")
        try check(git_repository_set_workdir(repo, workdir.path, 0), "Set inspection working directory")
        try withConfig(repo) { config in
            let emptyRules = temporary.appendingPathComponent("inspection-empty-rules")
            try Data().write(to: emptyRules)
            try check(git_config_set_string(config, "core.excludesfile", emptyRules.path), "Set inspection ignore rules")
            try check(git_config_set_string(config, "core.attributesfile", emptyRules.path), "Set inspection attribute rules")
            try check(git_config_set_bool(config, "core.precomposeunicode", 1), "Set inspection Unicode paths")
        }
        return try body(repo)
    }

    private static func inspectFiles(_ root: URL, ownedWorkflow: UUID?) throws -> [FolderPublicationFile] {
        try validateNoEnclosingRepository(root)
        let rootGit = root.appendingPathComponent(".git", isDirectory: true)
        if exists(rootGit) {
            guard let ownedWorkflow else { throw FolderGitServiceError.existingMetadata(rootGit.path) }
            _ = try readMarker(root: root, workflowID: ownedWorkflow)
        }
        if looksBare(root) { throw FolderGitServiceError.existingMetadata(root.path) }
        var enumerationError: Error?
        guard let enumerator = FileManager.default.enumerator(
            at: root,
            includingPropertiesForKeys: [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey, .isPackageKey, .isUbiquitousItemKey],
            options: [],
            errorHandler: { _, error in enumerationError = error; return false }
        ) else { throw FolderGitServiceError.operation("The folder could not be fully inspected.") }
        var rawFiles: [(String, URL)] = []
        var normalizedPaths = Set<String>()
        for case let fileURL as URL in enumerator {
            try Task.checkCancellation()
            let relative = try relativePath(fileURL, root: root)
            if relative == ".git" {
                guard ownedWorkflow != nil else { throw FolderGitServiceError.existingMetadata(fileURL.path) }
                enumerator.skipDescendants()
                continue
            }
            guard fileURL.lastPathComponent != ".git" else { throw FolderGitServiceError.existingMetadata(fileURL.path) }
            try validatePath(relative)
            let values = try fileURL.resourceValues(forKeys: [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey, .isPackageKey, .isUbiquitousItemKey])
            guard values.isSymbolicLink != true, values.isPackage != true, values.isUbiquitousItem != true else {
                throw FolderGitServiceError.unsupportedFile(relative)
            }
            if values.isDirectory == true {
                if looksBare(fileURL) { throw FolderGitServiceError.existingMetadata(fileURL.path) }
                continue
            }
            guard values.isRegularFile == true, normalizedPaths.insert(relative).inserted else {
                throw FolderGitServiceError.unsupportedFile(relative)
            }
            guard rawFiles.count < maximumInspectedFiles else { throw FolderGitServiceError.folderTooManyFiles }
            rawFiles.append((relative, fileURL))
        }
        if let enumerationError { throw enumerationError }
        return try withInspectionRepository(workdir: root) { repo in
            try rawFiles.map { path, fileURL in
                var ignored: Int32 = 0
                try check(git_ignore_path_is_ignored(&ignored, repo, path), "Inspect ignore rules for \(path)")
                let hashed = try hashFile(fileURL)
                return FolderPublicationFile(path: path, size: hashed.size, digest: hashed.digest,
                    isIgnored: ignored != 0, isExecutable: try executableMode(fileURL))
            }.sorted { $0.path < $1.path }
        }
    }

    private static func prepareLocal(
        _ root: URL, workflowID: UUID, files: [FolderPublicationFile], selectedPaths: Set<String>,
        authorName: String, authorEmail: String, message: String,
        availableCapacityProvider: @Sendable (URL) throws -> Int64
    ) throws -> FolderPreparedCommit {
        try LocalGitService.validateFolderPublicationAuthor(name: authorName, email: authorEmail)
        let sortedFiles = normalizedReview(files).sorted { $0.path < $1.path }
        if let oversized = files.first(where: { selectedPaths.contains($0.path) && $0.size > maximumFileBytes }) {
            throw FolderGitServiceError.unsupportedFile(oversized.path)
        }
        let eligible = Set(files.filter { !$0.isIgnored && $0.size <= maximumFileBytes }.map(\.path))
        guard !selectedPaths.isEmpty, selectedPaths.isSubset(of: eligible), Set(files.map(\.path)).count == files.count else {
            throw FolderGitServiceError.invalidSelection
        }
        let budget = try resourceEstimate(for: files.filter { selectedPaths.contains($0.path) })
        for file in files { try validatePath(file.path) }
        let gitdir = root.appendingPathComponent(".git", isDirectory: true)
        var marker: Marker
        if exists(gitdir) {
            marker = try readMarker(root: root, workflowID: workflowID)
            guard normalizedReview(marker.files) == sortedFiles, marker.selectedPaths == selectedPaths.sorted(),
                  marker.authorName == authorName, marker.authorEmail == authorEmail, marker.message == message else {
                throw FolderGitServiceError.ownershipMismatch
            }
        } else {
            marker = Marker(
                workflowID: workflowID, files: sortedFiles, selectedPaths: selectedPaths.sorted(),
                authorName: authorName, authorEmail: authorEmail, message: message,
                timestamp: Int64(Date().timeIntervalSince1970)
            )
        }
        let current = try inspectFiles(root, ownedWorkflow: exists(gitdir) ? workflowID : nil)
        guard current == sortedFiles else {
            let changed = current.first(where: { !sortedFiles.contains($0) })?.path
                ?? sortedFiles.first(where: { !current.contains($0) })?.path ?? "The folder"
            throw FolderGitServiceError.reviewChanged(changed)
        }
        try withInspectionRepository(workdir: root) { inspectionRepo in
            for path in selectedPaths {
                try rejectUnsupportedAttributes(inspectionRepo, path: path)
                guard GitLFSPointer(data: try Data(contentsOf: root.appendingPathComponent(path))) == nil else {
                    throw FolderGitServiceError.unsupportedFile(path)
                }
            }
        }
        // Reserve ownership before libgit2 can leave partial metadata. mkdir is
        // exclusive: a competing initializer cannot turn an existing repo into ours.
        if !exists(gitdir) {
            // Capacity may change after this check. Retain the workflow on later
            // ENOSPC failures; this estimate is a preflight, never a guarantee.
            let availableBytes = try availableCapacityProvider(root)
            guard availableBytes >= budget.estimatedAdditionalBytes else {
                throw FolderGitServiceError.insufficientStorage(required: budget.estimatedAdditionalBytes, available: max(0, availableBytes))
            }
            guard mkdir(gitdir.path, mode_t(0o700)) == 0 else { throw FolderGitServiceError.ownershipMismatch }
            try writeMarker(marker, root: root)
        }
        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        let openCode = git_repository_open_ext(&repo, root.path, UInt32(GIT_REPOSITORY_OPEN_NO_SEARCH.rawValue), nil)
        if openCode == GIT_ENOTFOUND.rawValue {
            var options = git_repository_init_options()
            try check(git_repository_init_options_init(&options, UInt32(GIT_REPOSITORY_INIT_OPTIONS_VERSION)), "Configure initialization")
            options.flags = UInt32(GIT_REPOSITORY_INIT_NO_REINIT.rawValue)
            try "main".withCString { head in
                options.initial_head = head
                try check(git_repository_init_ext(&repo, root.path, &options), "Initialize local repository")
            }
        } else { try check(openCode, "Open owned repository") }
        try withConfig(repo) { config in
            try check(git_config_set_bool(config, "core.precomposeunicode", 1), "Configure Unicode paths")
            try check(git_config_set_bool(config, "core.autocrlf", 0), "Preserve reviewed bytes")
        }
        if let saved = marker.commitOID {
            try validateCommit(repo, marker: marker, commitOID: saved)
            guard let savedTree = marker.treeOID else { throw FolderGitServiceError.ownershipMismatch }
            return FolderPreparedCommit(commitOID: saved, treeOID: savedTree)
        }
        let recovered = try existingInitialCommit(repo, marker: marker)
        if let recovered {
            marker.commitOID = recovered.commitOID
            marker.treeOID = recovered.treeOID
            try writeMarker(marker, root: root)
            return recovered
        }
        var index: OpaquePointer?
        defer { if let index { git_index_free(index) } }
        try check(git_repository_index(&index, repo), "Read initial index")
        let baselineIndexChecksum = try LocalGitService.folderPublicationIndexChecksum(index)
        try check(git_index_clear(index), "Prepare reviewed index")
        for file in sortedFiles where selectedPaths.contains(file.path) {
            try Task.checkCancellation()
            let fileURL = root.appendingPathComponent(file.path)
            let bytes = try Data(contentsOf: fileURL)
            guard Int64(bytes.count) == file.size, digest(bytes) == file.digest,
                  try executableMode(fileURL) == (file.isExecutable ?? false) else {
                throw FolderGitServiceError.reviewChanged(file.path)
            }
            guard GitLFSPointer(data: bytes) == nil else { throw FolderGitServiceError.unsupportedFile(file.path) }
            var entry = git_index_entry()
            entry.mode = UInt32((file.isExecutable ?? false) ? GIT_FILEMODE_BLOB_EXECUTABLE.rawValue : GIT_FILEMODE_BLOB.rawValue)
            entry.file_size = UInt32(bytes.count)
            try file.path.withCString { path in
                entry.path = path
                try bytes.withUnsafeBytes { buffer in
                    // Empty regular files are eligible; libgit2 accepts a null
                    // buffer when its size is zero.
                    try check(git_index_add_from_buffer(index, &entry, buffer.baseAddress, bytes.count), "Stage reviewed file \(file.path)")
                }
            }
        }
        guard Int(git_index_entrycount(index)) == selectedPaths.count else { throw FolderGitServiceError.invalidSelection }
        var treeOID = git_oid()
        try check(git_index_write_tree(&treeOID, index), "Write reviewed tree")
        try validateTree(repo, treeOID: treeOID, marker: marker)
        // Exclusion patterns are local, literal, and retained for later stage-all.
        let exclusions = sortedFiles.filter { !selectedPaths.contains($0.path) }.map { literalIgnorePattern($0.path) }
        let excludeURL = gitdir.appendingPathComponent("info/exclude")
        let begin = "# BEGIN GitSync folder publication \(workflowID.uuidString)"
        let exclusionBlock = begin + "\n" + exclusions.joined(separator: "\n") + "\n# END GitSync folder publication\n"
        let existingExcludes = exists(excludeURL) ? try String(contentsOf: excludeURL, encoding: .utf8) : ""
        if existingExcludes.contains(begin) {
            guard existingExcludes.contains(exclusionBlock) else { throw FolderGitServiceError.ownershipMismatch }
        } else {
            try (existingExcludes + "\n" + exclusionBlock).write(to: excludeURL, atomically: true, encoding: .utf8)
        }
        try LocalGitService.writeFolderPublicationIndex(repo: repo, index: index, baselineChecksum: baselineIndexChecksum)
        var tree: OpaquePointer?
        defer { if let tree { git_tree_free(tree) } }
        try check(git_tree_lookup(&tree, repo, &treeOID), "Read reviewed tree")
        var signature: UnsafeMutablePointer<git_signature>?
        defer { if let signature { git_signature_free(signature) } }
        try check(git_signature_new(&signature, authorName, authorEmail, marker.timestamp, 0), "Set commit author")
        var commitOID = git_oid()
        try check(git_commit_create(&commitOID, repo, nil, signature, signature, nil, message, tree, 0, nil), "Create reviewed initial commit")
        // Publish HEAD only when its branch is still unborn. An external Git
        // client's commit is never silently overwritten by preparation.
        var transaction: OpaquePointer?
        defer { if let transaction { git_transaction_free(transaction) } }
        try check(git_transaction_new(&transaction, repo), "Prepare initial branch transaction")
        try check(git_transaction_lock_ref(transaction, "HEAD"), "Lock initial HEAD")
        try check(git_transaction_lock_ref(transaction, "refs/heads/main"), "Lock initial branch")
        try ensureMain(repo, expectedOID: nil)
        try check(git_transaction_set_target(transaction, "refs/heads/main", &commitOID, signature, "Initial folder publication"), "Set initial branch")
        try check(git_transaction_commit(transaction), "Save initial branch")
        marker.commitOID = hex(commitOID)
        marker.treeOID = hex(treeOID)
        try writeMarker(marker, root: root)
        return FolderPreparedCommit(commitOID: hex(commitOID), treeOID: hex(treeOID))
    }

    private static func existingInitialCommit(_ repo: OpaquePointer?, marker: Marker) throws -> FolderPreparedCommit? {
        var head: OpaquePointer?
        defer { if let head { git_reference_free(head) } }
        let code = git_repository_head(&head, repo)
        if code == GIT_EUNBORNBRANCH.rawValue || code == GIT_ENOTFOUND.rawValue {
            try ensureMain(repo, expectedOID: nil)
            return nil
        }
        try check(code, "Read preparation progress")
        guard let target = git_reference_target(head) else { throw FolderGitServiceError.ownershipMismatch }
        let commitOID = hex(target.pointee)
        try validateCommit(repo, marker: marker, commitOID: commitOID, allowUnrecorded: true)
        var oid = target.pointee
        var commit: OpaquePointer?
        defer { if let commit { git_commit_free(commit) } }
        try check(git_commit_lookup(&commit, repo, &oid), "Recover initial commit")
        return FolderPreparedCommit(commitOID: commitOID, treeOID: hex(git_commit_tree_id(commit).pointee))
    }

    private static func validateCommit(_ repo: OpaquePointer?, marker: Marker, commitOID: String, allowUnrecorded: Bool = false) throws {
        guard allowUnrecorded || marker.commitOID == commitOID else { throw FolderGitServiceError.ownershipMismatch }
        try ensureMain(repo, expectedOID: commitOID)
        var oid = git_oid()
        try check(git_oid_fromstr(&oid, commitOID), "Read saved commit")
        var commit: OpaquePointer?
        defer { if let commit { git_commit_free(commit) } }
        try check(git_commit_lookup(&commit, repo, &oid), "Open saved commit")
        guard git_commit_parentcount(commit) == 0,
              git_commit_message(commit).map({ String(cString: $0) }) == marker.message,
              let author = git_commit_author(commit),
              author.pointee.name.map({ String(cString: $0) }) == marker.authorName,
              author.pointee.email.map({ String(cString: $0) }) == marker.authorEmail,
              author.pointee.when.time == marker.timestamp else { throw FolderGitServiceError.ownershipMismatch }
        let treeOID = git_commit_tree_id(commit).pointee
        if let savedTree = marker.treeOID, savedTree != hex(treeOID) { throw FolderGitServiceError.ownershipMismatch }
        try validateTree(repo, treeOID: treeOID, marker: marker)
        // The marker predates commit creation, so its deterministic timestamp
        // also lets an interrupted prepare recognize only the exact commit it
        // would have made, including the committer and all commit headers.
        var tree: OpaquePointer?
        defer { if let tree { git_tree_free(tree) } }
        var expectedTree = treeOID
        try check(git_tree_lookup(&tree, repo, &expectedTree), "Verify initial commit tree")
        var signature: UnsafeMutablePointer<git_signature>?
        defer { if let signature { git_signature_free(signature) } }
        try check(git_signature_new(&signature, marker.authorName, marker.authorEmail, marker.timestamp, 0), "Verify initial commit signature")
        var buffer = git_buf()
        defer { git_buf_dispose(&buffer) }
        try check(git_commit_create_buffer(&buffer, repo, signature, signature, nil, marker.message, tree, 0, nil), "Verify initial commit contents")
        var expectedCommit = git_oid()
        try check(git_odb_hash(&expectedCommit, buffer.ptr, buffer.size, GIT_OBJECT_COMMIT), "Verify initial commit identity")
        guard hex(expectedCommit) == commitOID else { throw FolderGitServiceError.ownershipMismatch }
    }

    private static func validateTree(_ repo: OpaquePointer?, treeOID: git_oid, marker: Marker) throws {
        guard Set(marker.files.map(\.path)).count == marker.files.count else { throw FolderGitServiceError.ownershipMismatch }
        var tree: OpaquePointer?
        defer { if let tree { git_tree_free(tree) } }
        var oid = treeOID
        try check(git_tree_lookup(&tree, repo, &oid), "Inspect saved tree")
        let entries = try treeFiles(repo, tree: tree, prefix: "")
        guard Set(entries.map(\.path)) == Set(marker.selectedPaths), entries.count == marker.selectedPaths.count else {
            throw FolderGitServiceError.ownershipMismatch
        }
        let reviewed = Dictionary(uniqueKeysWithValues: marker.files.map { ($0.path, $0) })
        let attributeWorkdir = FileManager.default.temporaryDirectory.appendingPathComponent("folder-attributes-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: attributeWorkdir, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: attributeWorkdir) }
        for entry in entries {
            guard let expected = reviewed[entry.path], !expected.isIgnored else {
                throw FolderGitServiceError.unsupportedFile(entry.path)
            }
            let expectedMode = UInt32((expected.isExecutable ?? false) ? GIT_FILEMODE_BLOB_EXECUTABLE.rawValue : GIT_FILEMODE_BLOB.rawValue)
            guard entry.mode == expectedMode else {
                throw FolderGitServiceError.unsupportedFile(entry.path)
            }
            var blob: OpaquePointer?
            defer { if let blob { git_blob_free(blob) } }
            var blobOID = entry.oid
            try check(git_blob_lookup(&blob, repo, &blobOID), "Inspect saved file \(entry.path)")
            let count = Int(git_blob_rawsize(blob))
            guard Int64(count) <= maximumFileBytes, Int64(count) == expected.size else { throw FolderGitServiceError.unsupportedFile(entry.path) }
            let bytes = count == 0 ? Data() : Data(bytes: git_blob_rawcontent(blob)!, count: count)
            guard digest(bytes) == expected.digest else { throw FolderGitServiceError.ownershipMismatch }
            guard GitLFSPointer(data: bytes) == nil else { throw FolderGitServiceError.unsupportedFile(entry.path) }
            if URL(fileURLWithPath: entry.path).lastPathComponent == ".gitattributes" {
                let target = attributeWorkdir.appendingPathComponent(entry.path)
                try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                try bytes.write(to: target)
            }
        }
        try withInspectionRepository(workdir: attributeWorkdir) { inspectionRepo in
            for entry in entries { try rejectUnsupportedAttributes(inspectionRepo, path: entry.path) }
        }
    }

    private static func treeFiles(_ repo: OpaquePointer?, tree: OpaquePointer?, prefix: String) throws -> [TreeFile] {
        var result: [TreeFile] = []
        for index in 0..<git_tree_entrycount(tree) {
            try Task.checkCancellation()
            guard let entry = git_tree_entry_byindex(tree, index), let name = git_tree_entry_name(entry), let target = git_tree_entry_id(entry) else {
                throw FolderGitServiceError.operation("A saved Git tree could not be fully inspected.")
            }
            let path = prefix + String(cString: name)
            try validatePath(path)
            let mode = git_tree_entry_filemode(entry)
            if mode == GIT_FILEMODE_TREE {
                var child: OpaquePointer?
                defer { if let child { git_tree_free(child) } }
                var oid = target.pointee
                try check(git_tree_lookup(&child, repo, &oid), "Inspect saved directory")
                result += try treeFiles(repo, tree: child, prefix: path + "/")
            } else {
                result.append(TreeFile(path: path, oid: target.pointee, mode: UInt32(mode.rawValue)))
            }
        }
        return result
    }

    private static func withOwnedRepository<T>(_ root: URL, workflowID: UUID, body: (OpaquePointer?, Marker) throws -> T) throws -> T {
        let marker = try readMarker(root: root, workflowID: workflowID)
        var repo: OpaquePointer?
        defer { if let repo { git_repository_free(repo) } }
        try check(git_repository_open_ext(&repo, root.path, UInt32(GIT_REPOSITORY_OPEN_NO_SEARCH.rawValue), nil), "Open publication repository")
        guard git_repository_is_bare(repo) == 0, git_repository_workdir(repo).map({ URL(fileURLWithPath: String(cString: $0)).standardizedFileURL.resolvingSymlinksInPath().path }) == root.standardizedFileURL.resolvingSymlinksInPath().path else {
            throw FolderGitServiceError.ownershipMismatch
        }
        return try body(repo, marker)
    }

    private static func ensureMain(_ repo: OpaquePointer?, expectedOID: String?) throws {
        var head: OpaquePointer?
        defer { if let head { git_reference_free(head) } }
        try check(git_reference_lookup(&head, repo, "HEAD"), "Read initial HEAD")
        guard git_reference_symbolic_target(head).map({ String(cString: $0) }) == "refs/heads/main" else { throw FolderGitServiceError.ownershipMismatch }
        var branch: OpaquePointer?
        defer { if let branch { git_reference_free(branch) } }
        let code = git_reference_lookup(&branch, repo, "refs/heads/main")
        if let expectedOID {
            try check(code, "Read saved main branch")
            guard let target = git_reference_target(branch), hex(target.pointee) == expectedOID else { throw FolderGitServiceError.ownershipMismatch }
        } else {
            guard code == GIT_ENOTFOUND.rawValue else { throw FolderGitServiceError.ownershipMismatch }
        }
    }

    private static func assertOrigin(_ repo: OpaquePointer?, remoteURL: String) throws {
        var remote: OpaquePointer?
        defer { if let remote { git_remote_free(remote) } }
        try check(git_remote_lookup(&remote, repo, "origin"), "Read publication destination")
        guard git_remote_url(remote).map({ String(cString: $0) }) == remoteURL,
              (git_remote_pushurl(remote).map({ String(cString: $0) }) ?? remoteURL) == remoteURL else {
            throw FolderGitServiceError.ownershipMismatch
        }
    }

    private static func rejectUnsupportedAttributes(_ repo: OpaquePointer?, path: String) throws {
        // Raw reviewed bytes are staged deliberately. Any conversion policy
        // could make those blobs disagree with later ordinary Git staging.
        for attribute in ["filter", "text", "eol", "ident", "working-tree-encoding"] {
            var value: UnsafePointer<CChar>?
            try check(git_attr_get(&value, repo, UInt32(GIT_ATTR_CHECK_NO_SYSTEM), path, attribute), "Inspect attributes for \(path)")
            if git_attr_value(value) == GIT_ATTR_VALUE_STRING || git_attr_value(value) == GIT_ATTR_VALUE_TRUE {
                throw FolderGitServiceError.unsupportedFile(path)
            }
        }
    }

    private static func validateNoEnclosingRepository(_ root: URL) throws {
        // Foundation can produce /.. when deleting the last component of a
        // directory URL for /. Work with canonical paths and a shrinking bound.
        let rootPath = root.standardizedFileURL.resolvingSymlinksInPath().path
        var parentPath = (rootPath as NSString).deletingLastPathComponent
        while !parentPath.isEmpty && parentPath != rootPath {
            try Task.checkCancellation()
            let parent = URL(fileURLWithPath: parentPath, isDirectory: true)
            // Security scopes may hide ancestors. Inspect only readable ones;
            // the location allowlist still prevents unsupported provider paths.
            if FileManager.default.isReadableFile(atPath: parentPath) {
                if exists(parent.appendingPathComponent(".git")) || looksBare(parent) {
                    throw FolderGitServiceError.existingMetadata(parentPath)
                }
            }
            let next = (parentPath as NSString).deletingLastPathComponent
            if next.count >= parentPath.count { break }
            parentPath = next
        }
    }

    private static func looksBare(_ url: URL) -> Bool {
        exists(url.appendingPathComponent("HEAD")) && exists(url.appendingPathComponent("objects"))
            && exists(url.appendingPathComponent("refs")) && exists(url.appendingPathComponent("config"))
    }

    private static func exists(_ url: URL) -> Bool {
        // lstat detects even dangling symlinks; fileExists would miss them.
        var status = stat()
        return lstat(url.path, &status) == 0
    }

    private static func relativePath(_ url: URL, root: URL) throws -> String {
        // FileManager may resolve /var to /private/var in enumeration results.
        let rootPath = root.standardizedFileURL.resolvingSymlinksInPath().path
        let prefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
        let path = url.standardizedFileURL.resolvingSymlinksInPath().path
        guard path.hasPrefix(prefix) else { throw FolderGitServiceError.unsupportedFile(path) }
        let relative = String(path.dropFirst(prefix.count)).precomposedStringWithCanonicalMapping
        return relative
    }

    private static func validatePath(_ path: String) throws {
        let components = path.split(separator: "/", omittingEmptySubsequences: false)
        guard !path.isEmpty, !path.hasPrefix("/"), !path.contains("\n"), !path.contains("\r"), !path.contains("\0"),
              components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." && $0.lowercased() != ".git" }) else {
            throw FolderGitServiceError.unsupportedFile(path)
        }
    }

    private static func literalIgnorePattern(_ path: String) -> String {
        "/" + path.map { character in
            switch character {
            case "\\", "*", "?", "[", "]", "#", "!", " ", "\t": return "\\\(character)"
            default: return String(character)
            }
        }.joined()
    }

    private static func hashFile(_ url: URL) throws -> (digest: String, size: Int64) {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        var size: Int64 = 0
        while true {
            try Task.checkCancellation()
            let bytes = try handle.read(upToCount: 1024 * 1024) ?? Data()
            if bytes.isEmpty { break }
            hasher.update(data: bytes)
            size += Int64(bytes.count)
        }
        return (hasher.finalize().map { String(format: "%02x", $0) }.joined(), size)
    }

    private static func executableMode(_ url: URL) throws -> Bool {
        var status = stat()
        guard lstat(url.path, &status) == 0, status.st_mode & S_IFMT == S_IFREG else {
            throw FolderGitServiceError.unsupportedFile(url.lastPathComponent)
        }
        // Git records the owner's execute bit as mode 100755; read/write bits
        // and the other permission bits are not part of the Git tree format.
        return status.st_mode & S_IXUSR != 0
    }

    private static func normalizedReview(_ files: [FolderPublicationFile]) -> [FolderPublicationFile] {
        files.map { file in
            var normalized = file
            normalized.isExecutable = file.isExecutable ?? false
            return normalized
        }
    }

    /// Budget loose Git objects, an outgoing pack and temporary overhead using
    /// uncompressed sizes. Deliberately bounded for a foreground first upload.
    static func resourceEstimate(for selectedFiles: [FolderPublicationFile]) throws -> FolderPublicationResourceEstimate {
        guard selectedFiles.count <= maximumSelectedFiles else { throw FolderGitServiceError.selectionTooLarge }
        var total: Int64 = 0
        for file in selectedFiles {
            guard file.size >= 0, file.size <= maximumFileBytes else { throw FolderGitServiceError.unsupportedFile(file.path) }
            let addition = total.addingReportingOverflow(file.size)
            guard !addition.overflow, addition.partialValue <= maximumSelectedBytes else { throw FolderGitServiceError.selectionTooLarge }
            total = addition.partialValue
        }
        // 3x payload covers objects, pack and temporary copies; 4 KiB per file
        // plus 16 MiB covers index, tree, journal and filesystem allocation.
        return FolderPublicationResourceEstimate(
            fileCount: selectedFiles.count, selectedBytes: total,
            estimatedAdditionalBytes: total * 3 + Int64(selectedFiles.count) * 4096 + 16 * 1024 * 1024
        )
    }

    private static func availableStorage(at root: URL) throws -> Int64 {
        let values = try? root.resourceValues(forKeys: [.volumeAvailableCapacityKey])
        if let available = values?.volumeAvailableCapacity, available >= 0 { return Int64(available) }
        let attributes = try FileManager.default.attributesOfFileSystem(forPath: root.path)
        guard let available = attributes[.systemFreeSize] as? NSNumber else {
            throw FolderGitServiceError.operation("Available storage could not be checked. Retry before preparing the folder.")
        }
        return max(0, available.int64Value)
    }

    private static func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func markerURL(_ root: URL) -> URL {
        root.appendingPathComponent(".git/folder-publication.json")
    }

    private static func readMarker(root: URL, workflowID: UUID) throws -> Marker {
        let gitValues = try? root.appendingPathComponent(".git").resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        guard gitValues?.isDirectory == true, gitValues?.isSymbolicLink != true else {
            throw FolderGitServiceError.ownershipMismatch
        }
        let url = markerURL(root)
        let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard values?.isRegularFile == true, values?.isSymbolicLink != true,
              let data = try? Data(contentsOf: url), let marker = try? JSONDecoder().decode(Marker.self, from: data), marker.workflowID == workflowID else {
            throw FolderGitServiceError.ownershipMismatch
        }
        return marker
    }

    private static func writeMarker(_ marker: Marker, root: URL) throws {
        try JSONEncoder().encode(marker).write(to: markerURL(root), options: .atomic)
    }

    private static func hex(_ oid: git_oid) -> String {
        var copy = oid
        var buffer = [CChar](repeating: 0, count: 41)
        git_oid_tostr(&buffer, buffer.count, &copy)
        return String(cString: buffer)
    }

    private static func withConfig<T>(_ repo: OpaquePointer?, body: (OpaquePointer?) throws -> T) throws -> T {
        var config: OpaquePointer?
        defer { if let config { git_config_free(config) } }
        try check(git_repository_config(&config, repo), "Read Git configuration")
        return try body(config)
    }

    private static func check(_ code: Int32, _ context: String) throws {
        guard code >= 0 else {
            let message = git_error_last()?.pointee.message.map { String(cString: $0) } ?? "Git operation failed"
            throw FolderGitServiceError.operation("\(context): \(message)")
        }
    }

    private static func runLocal<T: Sendable>(_ operation: @escaping @Sendable () throws -> T) async throws -> T {
        let task = Task.detached(priority: .userInitiated) { try operation() }
        return try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
    }

    private static func coordinate<T>(at url: URL, writing: Bool, operation: @escaping (URL) throws -> T) throws -> T {
        var result: Result<T, Error>?
        var coordinationError: NSError?
        let accessor: (URL) -> Void = { root in result = Result { try operation(root) } }
        let coordinator = NSFileCoordinator(filePresenter: nil)
        if writing {
            coordinator.coordinate(writingItemAt: url, options: .forMerging, error: &coordinationError, byAccessor: accessor)
        } else {
            coordinator.coordinate(readingItemAt: url, options: .withoutChanges, error: &coordinationError, byAccessor: accessor)
        }
        if let coordinationError { throw coordinationError }
        guard let result else { throw FolderGitServiceError.operation("The folder could not be coordinated.") }
        return try result.get()
    }
}

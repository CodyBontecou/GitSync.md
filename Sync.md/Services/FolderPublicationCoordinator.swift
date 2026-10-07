import Foundation
import Observation

private actor FolderPublicationOperationGuard {
    static let shared = FolderPublicationOperationGuard()
    private var active = Set<String>()
    func acquire(_ key: String) throws {
        guard active.insert(key).inserted else { throw FolderPublicationError.busy }
    }
    func release(_ key: String) { active.remove(key) }
}

/// Owns the durable workflow. The UI never reconstructs or retries external
/// side effects from a transient screen state.
@MainActor @Observable
final class FolderPublicationCoordinator {
    private(set) var records: [FolderPublicationRecord] = []
    private(set) var isBusy = false
    private(set) var progressMessage: String?
    private(set) var loadError: String?

    @ObservationIgnored private let store: FolderPublicationStore
    @ObservationIgnored private let git: any FolderGitHandling
    @ObservationIgnored private let github: any GitHubRepositoryPublishing
    @ObservationIgnored private var cancelOperation: (() -> Void)?
    @ObservationIgnored private let allowUnscopedAccess: Bool

    init(store: FolderPublicationStore = FolderPublicationStore(),
         git: any FolderGitHandling = FolderGitService(),
         github: any GitHubRepositoryPublishing = GitHubRepositoryPublisher(),
         allowUnscopedAccess: Bool = false) {
        self.store = store
        self.git = git
        self.github = github
        self.allowUnscopedAccess = allowUnscopedAccess
        do {
            records = try store.load()
        } catch { loadError = error.localizedDescription }
    }

    func record(id: UUID) -> FolderPublicationRecord? { records.first { $0.id == id } }

    /// Other import/discovery paths must not register intermediate metadata
    /// and make it eligible for ordinary automatic synchronization.
    func validateImportAvailability(at url: URL) throws {
        let path = Self.canonicalPath(url)
        let latest = try store.load()
        guard !latest.contains(where: { $0.folderPath == path && $0.phase != .completed }) else {
            throw FolderPublicationError.unavailable(String(localized: "This folder has unfinished publication progress. Resume it from Publish a Folder before adding it as a repository."))
        }
    }

    func selectFolder(url: URL) async throws -> UUID {
        try await run {
            let path = Self.canonicalPath(url)
            if let pending = self.records.first(where: { $0.folderPath == path && $0.phase != .completed }) {
                return pending.id
            }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard scoped || self.isAppOwned(url) || self.allowUnscopedAccess else {
                throw FolderPublicationError.bookmarkUnavailable
            }
            self.progressMessage = String(localized: "Reviewing folder contents…")
            let inspection = try await self.git.inspect(at: url)
            try Task.checkCancellation()
            let bookmark = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
            let record = FolderPublicationRecord(bookmarkData: bookmark, folderName: url.lastPathComponent,
                folderPath: path, files: inspection.files, warnings: inspection.warnings)
            try self.save(record)
            return record.id
        }
    }

    func refreshReview(id: UUID) async throws {
        try await run {
            var record = try self.requireRecord(id)
            guard record.phase == .review else { throw FolderPublicationError.unavailable(String(localized: "The prepared snapshot is locked. Resume the saved publication.")) }
            let inspection = try await self.withFolder(record) { try await self.git.inspect(at: $0) }
            record.files = inspection.files
            record.warnings = inspection.warnings
            record.selectedPaths = inspection.files.filter { !$0.isIgnored && !$0.isSensitive && $0.size <= 10 * 1024 * 1024 }.map(\.path)
            record.lastError = nil
            try self.save(record)
        }
    }

    func prepare(id: UUID, selectedPaths: Set<String>, authorName: String, authorEmail: String,
                 message: String, accountLogin: String, repositoryName: String, token: String) async throws {
        try await run {
            var record = try self.requireRecord(id)
            guard record.phase == .review || record.phase == .preparing else {
                throw FolderPublicationError.unavailable(String(localized: "This publication already has a saved commit."))
            }
            if record.phase == .review {
                let available = Set(record.files.filter { !$0.isIgnored }.map(\.path))
                guard !selectedPaths.isEmpty, selectedPaths.isSubset(of: available) else { throw FolderPublicationError.emptySelection }
                guard !authorName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      !authorEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      !accountLogin.isEmpty else {
                    throw FolderPublicationError.unavailable(String(localized: "Choose a GitHub account, author name, author email, and commit message."))
                }
                try Self.validateRepositoryName(repositoryName)
                let account = try await self.github.authenticatedAccount(token: token)
                guard account.login.caseInsensitiveCompare(accountLogin) == .orderedSame else {
                    throw FolderPublicationError.accountMismatch
                }
                record.selectedPaths = selectedPaths.sorted()
                record.authorName = authorName.trimmingCharacters(in: .whitespacesAndNewlines)
                record.authorEmail = authorEmail.trimmingCharacters(in: .whitespacesAndNewlines)
                record.commitMessage = message.trimmingCharacters(in: .whitespacesAndNewlines)
                record.accountLogin = accountLogin
                record.accountUserID = account.id
                record.repositoryName = repositoryName
                record.phase = .preparing
                record.lastError = nil
                try self.save(record)
            }
            self.progressMessage = String(localized: "Saving the reviewed initial commit…")
            do {
                let snapshot = record
                let prepared = try await self.withFolder(snapshot) { url in
                    try await self.git.prepare(at: url, workflowID: snapshot.id, files: snapshot.files,
                        selectedPaths: Set(snapshot.selectedPaths), authorName: snapshot.authorName,
                        authorEmail: snapshot.authorEmail, message: snapshot.commitMessage)
                }
                record.commitOID = prepared.commitOID
                record.treeOID = prepared.treeOID
                record.phase = .prepared
                record.lastError = nil
                try self.save(record)
            } catch {
                // If no Git metadata was created, the user can refresh the
                // review. Owned partial metadata is retained for resumption.
                if let hasMetadata = try? await self.withFolder(record, operation: { url in
                    FileManager.default.fileExists(atPath: url.appendingPathComponent(".git").path)
                }), !hasMetadata { record.phase = .review }
                record.lastError = error.localizedDescription
                try self.save(record)
                throw error
            }
        }
    }

    func updateRepositoryName(id: UUID, name: String) async throws {
        try await run {
            var record = try self.requireRecord(id)
            guard record.phase == .prepared else { throw FolderPublicationError.creationNeedsReconciliation }
            try Self.validateRepositoryName(name)
            record.repositoryName = name
            record.lastError = nil
            try self.save(record)
        }
    }

    func publish(id: UUID, token: String) async throws {
        try await run {
            var record = try self.requireRecord(id)
            guard let commit = record.commitOID else { throw FolderPublicationError.unavailable(String(localized: "Prepare the initial commit first.")) }
            guard !token.isEmpty else { throw FolderPublicationError.accountMismatch }
            if record.phase == .completed || record.phase == .published { return }
            guard [.prepared, .remoteCreated, .pushing, .pushUnknown].contains(record.phase) else {
                throw FolderPublicationError.creationNeedsReconciliation
            }
            do {
                try await self.checkAccount(record, token: token)
                try await self.withFolder(record) { try await self.git.validatePrepared(at: $0, workflowID: id, commitOID: commit) }
                try Task.checkCancellation()
                if record.remote == nil {
                    record.phase = .creatingRemote
                    record.lastError = nil
                    try self.save(record)
                    self.progressMessage = String(localized: "Creating a private repository on GitHub…")
                    do {
                        let remote = try await self.github.createPrivateRepository(name: record.repositoryName, token: token)
                        try self.validateRemote(remote, record: record)
                        record.remote = remote
                        record.phase = .remoteCreated
                        try self.save(record)
                    } catch {
                        let uncertain = (error as? GitHubRepositoryPublicationError)?.creationOutcomeIsUnknown ?? true
                        record.phase = uncertain ? .creationUnknown : .prepared
                        throw error
                    }
                }
                guard let remote = record.remote else { throw FolderPublicationError.creationNeedsReconciliation }
                let confirmed = try await self.github.repository(owner: remote.owner, name: remote.name, token: token)
                guard confirmed == remote else { throw FolderPublicationError.changedHistory }
                let oid = try await self.github.branchOID(owner: remote.owner, name: remote.name, branch: "main", token: token)
                if let oid {
                    guard oid == commit else { throw FolderPublicationError.changedHistory }
                } else {
                    guard try await !self.github.hasAnyReferences(owner: remote.owner, name: remote.name, token: token) else {
                        throw FolderPublicationError.remoteNotEmpty
                    }
                    record.phase = .pushing
                    record.lastError = nil
                    try self.save(record)
                    self.progressMessage = String(localized: "Publishing the saved commit…")
                    let snapshot = record
                    try await self.withFolder(snapshot) { url in
                        try await self.git.publish(at: url, workflowID: id, commitOID: commit, remoteURL: remote.cloneURL, token: token)
                    }
                    let publishedOID = try await self.github.branchOID(owner: remote.owner, name: remote.name, branch: "main", token: token)
                    guard publishedOID == commit else { throw FolderPublicationError.changedHistory }
                }
                // Remote success is durable before tracking/registration. Retry
                // reconciles refs and never stages or commits newer contents.
                record.phase = .pushUnknown
                try self.save(record)
                self.progressMessage = String(localized: "Finishing repository setup…")
                let snapshot = record
                try await self.withFolder(snapshot) { url in
                    try await self.git.finish(at: url, workflowID: id, commitOID: commit, remoteURL: remote.cloneURL, token: token)
                }
                record.phase = .published
                record.lastError = nil
                try self.save(record)
            } catch {
                if record.phase == .creatingRemote { record.phase = .creationUnknown }
                if record.phase == .pushing { record.phase = .pushUnknown }
                record.lastError = error.localizedDescription
                try self.save(record)
                throw error
            }
        }
    }

    func reconcileCreation(id: UUID, token: String) async throws -> PublishedGitHubRepository {
        try await run {
            let record = try self.requireRecord(id)
            guard record.phase == .creationUnknown || record.phase == .creatingRemote else { throw FolderPublicationError.creationNeedsReconciliation }
            try await self.checkAccount(record, token: token)
            guard let remote = try await self.github.repository(owner: record.accountLogin, name: record.repositoryName, token: token) else {
                throw FolderPublicationError.creationNeedsReconciliation
            }
            try self.validateRemote(remote, record: record)
            return remote // UI must explicitly approve adoption; no mutation.
        }
    }

    func adoptRecoveredRepository(id: UUID, remote: PublishedGitHubRepository, token: String) async throws {
        try await run {
            var record = try self.requireRecord(id)
            guard record.phase == .creationUnknown || record.phase == .creatingRemote else { throw FolderPublicationError.creationNeedsReconciliation }
            try await self.checkAccount(record, token: token)
            try self.validateRemote(remote, record: record)
            guard try await self.github.repository(owner: remote.owner, name: remote.name, token: token) == remote,
                  try await !self.github.hasAnyReferences(owner: remote.owner, name: remote.name, token: token) else {
                throw FolderPublicationError.remoteNotEmpty
            }
            record.remote = remote
            record.phase = .remoteCreated
            record.lastError = nil
            try self.save(record)
        }
    }

    func reconnect(id: UUID, url: URL) async throws {
        try await run {
            var record = try self.requireRecord(id)
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard scoped || self.isAppOwned(url) || self.allowUnscopedAccess else { throw FolderPublicationError.bookmarkUnavailable }
            if let commit = record.commitOID {
                try await self.git.validatePrepared(at: url, workflowID: id, commitOID: commit)
            } else {
                guard Self.canonicalPath(url) == record.folderPath else { throw FolderPublicationError.bookmarkUnavailable }
            }
            record.bookmarkData = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
            record.folderPath = Self.canonicalPath(url)
            record.folderName = url.lastPathComponent
            record.lastError = nil
            try self.save(record)
        }
    }

    /// Keeps reconnection and another coordinator from changing the folder
    /// while AppState verifies and persists its registration.
    func completeRegistration(id: UUID,
        registration: @escaping @MainActor (FolderPublicationRecord, URL) async throws -> Void
    ) async throws {
        try await run {
            var record = try self.requireRecord(id)
            guard record.phase == .published || record.phase == .completed else { throw FolderPublicationError.changedHistory }
            let url = try self.resolvedFolder(id: id)
            try await registration(record, url)
            record.phase = .completed
            record.lastError = nil
            try self.save(record)
        }
    }

    func cancelCurrentOperation() { cancelOperation?() }

    /// Used by registration to retain the same grant; never uses folderPath as
    /// an alternate location if resolving the bookmark fails.
    func resolvedFolder(id: UUID) throws -> URL {
        let record = try requireRecord(id)
        var stale = false
        let url: URL
        do { url = try URL(resolvingBookmarkData: record.bookmarkData, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) }
        catch { throw FolderPublicationError.bookmarkUnavailable }
        guard Self.canonicalPath(url) == record.folderPath else { throw FolderPublicationError.bookmarkUnavailable }
        return url
    }

    private func checkAccount(_ record: FolderPublicationRecord, token: String) async throws {
        let account = try await github.authenticatedAccount(token: token)
        guard account.id == record.accountUserID,
              account.login.caseInsensitiveCompare(record.accountLogin) == .orderedSame else { throw FolderPublicationError.accountMismatch }
    }

    private func validateRemote(_ remote: PublishedGitHubRepository, record: FolderPublicationRecord) throws {
        guard remote.isPrivate, remote.owner.caseInsensitiveCompare(record.accountLogin) == .orderedSame,
              remote.name.caseInsensitiveCompare(record.repositoryName) == .orderedSame else {
            throw FolderPublicationError.unavailable(String(localized: "GitHub returned a different account, name, or visibility. Check the destination before continuing."))
        }
    }

    private func requireRecord(_ id: UUID) throws -> FolderPublicationRecord {
        guard loadError == nil else { throw FolderPublicationError.unavailable(loadError!) }
        guard let record = record(id: id) else { throw FolderPublicationError.missingRecord }
        return record
    }

    private func save(_ record: FolderPublicationRecord) throws {
        if let previous = self.record(id: record.id) {
            try store.replace(record, expected: previous)
        } else {
            try store.save(record)
        }
        records = try store.load()
    }

    private func withFolder<T>(_ record: FolderPublicationRecord, operation: (URL) async throws -> T) async throws -> T {
        let url = try resolvedFolder(id: record.id)
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard scoped || isAppOwned(url) || allowUnscopedAccess else { throw FolderPublicationError.bookmarkUnavailable }
        return try await operation(url)
    }

    private func isAppOwned(_ url: URL) -> Bool {
        let root = Self.canonicalPath(URL(fileURLWithPath: NSHomeDirectory()))
        let path = Self.canonicalPath(url)
        return path == root || path.hasPrefix(root + "/")
    }

    private static func canonicalPath(_ url: URL) -> String { url.standardizedFileURL.resolvingSymlinksInPath().path }

    private static func validateRepositoryName(_ name: String) throws {
        guard !name.isEmpty, name.count <= 100, name != ".", name != "..",
              name.range(of: "^[A-Za-z0-9_.-]+$", options: .regularExpression) != nil else { throw FolderPublicationError.invalidName }
    }

    private func run<T>(_ operation: @escaping @MainActor () async throws -> T) async throws -> T {
        guard loadError == nil else { throw FolderPublicationError.unavailable(loadError!) }
        guard !isBusy else { throw FolderPublicationError.busy }
        let key = store.url.standardizedFileURL.path
        try await FolderPublicationOperationGuard.shared.acquire(key)
        isBusy = true
        defer { isBusy = false; progressMessage = nil; cancelOperation = nil }
        do {
            records = try store.load()
            // Only the guarded operation can normalize persisted intent. A
            // second AppState loading while publication runs stays read-only.
            for record in records where record.phase == .creatingRemote || record.phase == .pushing {
                var recovered = record
                recovered.phase = record.phase == .creatingRemote ? .creationUnknown : .pushUnknown
                try save(recovered)
            }
            let task = Task { @MainActor in try await operation() }
            cancelOperation = { task.cancel() }
            let result = try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
            await FolderPublicationOperationGuard.shared.release(key)
            return result
        } catch {
            await FolderPublicationOperationGuard.shared.release(key)
            throw error
        }
    }
}

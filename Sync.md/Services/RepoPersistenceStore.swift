import Foundation

/// Locked, atomic repository metadata persistence. Updates are expressed relative
/// to the caller's original snapshot and merged field-by-field into the latest
/// on-disk record so independent AppState instances do not clobber each other.
final class RepoPersistenceStore: @unchecked Sendable {
    enum Change: Sendable {
        case add(RepoConfig)
        case update(original: RepoConfig, modified: RepoConfig)
        case remove(original: RepoConfig)
    }

    enum StoreError: LocalizedError {
        case malformedFile(Error)
        case duplicateIdentifier(UUID)
        case staleUpdateAfterDeletion(UUID)
        case addConflictsWithExisting(UUID)

        var errorDescription: String? {
            switch self {
            case .malformedFile(let error): return "Repository settings could not be read: \(error.localizedDescription)"
            case .duplicateIdentifier(let id): return "Repository settings contain duplicate identifier \(id)."
            case .staleUpdateAfterDeletion(let id): return "Repository \(id) was deleted by another operation; stale changes were not restored."
            case .addConflictsWithExisting(let id): return "Repository \(id) already exists; it was not overwritten."
            }
        }
    }

    static let shared = RepoPersistenceStore()
    /// All store instances in this process coordinate the same read-modify-write
    /// cycle. (Cross-process writers still require a separate coordination layer.)
    private static let lock = NSLock()
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = .prettyPrinted
        self.encoder = encoder
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        self.decoder = decoder
    }

    func load(from fileURL: URL) -> [RepoConfig] {
        do { return try loadStrict(from: fileURL) }
        catch {
            DebugLogger.shared.error("persistence", "Could not load repository settings", detail: error.localizedDescription)
            return []
        }
    }

    func loadStrict(from fileURL: URL) throws -> [RepoConfig] {
        try Self.lock.withLock { try loadUnlocked(from: fileURL) }
    }

    @discardableResult
    func apply(_ changes: [Change], to fileURL: URL) throws -> [RepoConfig] {
        try Self.lock.withLock {
            var persisted = try loadUnlocked(from: fileURL)
            var order = persisted.map(\.id)
            var byID = Dictionary(uniqueKeysWithValues: persisted.map { ($0.id, $0) })

            for change in changes {
                switch change {
                case .add(let repo):
                    guard byID[repo.id] == nil else { throw StoreError.addConflictsWithExisting(repo.id) }
                    order.append(repo.id)
                    byID[repo.id] = repo
                case .update(let original, let modified):
                    guard let latest = byID[original.id] else { throw StoreError.staleUpdateAfterDeletion(original.id) }
                    byID[original.id] = Self.merge(latest: latest, original: original, modified: modified)
                case .remove(let original):
                    byID.removeValue(forKey: original.id)
                    order.removeAll { $0 == original.id }
                }
            }

            persisted = order.compactMap { byID[$0] }
            try write(persisted, to: fileURL)
            return persisted
        }
    }

    func replaceAll(_ repos: [RepoConfig], at fileURL: URL) throws {
        try Self.lock.withLock { try write(repos, to: fileURL) }
    }

    private func loadUnlocked(from fileURL: URL) throws -> [RepoConfig] {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return [] }
        do {
            let repos = try decoder.decode([RepoConfig].self, from: Data(contentsOf: fileURL))
            guard Set(repos.map(\.id)).count == repos.count else {
                throw StoreError.duplicateIdentifier(repos.first!.id)
            }
            return repos
        } catch let error as StoreError { throw error }
        catch { throw StoreError.malformedFile(error) }
    }

    private func write(_ repos: [RepoConfig], to fileURL: URL) throws {
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try encoder.encode(repos).write(to: fileURL, options: .atomic)
    }

    private static func merge(latest: RepoConfig, original: RepoConfig, modified: RepoConfig) -> RepoConfig {
        var merged = latest
        if original.repoURL != modified.repoURL { merged.repoURL = modified.repoURL }
        if original.branch != modified.branch { merged.branch = modified.branch }
        if original.authorName != modified.authorName { merged.authorName = modified.authorName }
        if original.authorEmail != modified.authorEmail { merged.authorEmail = modified.authorEmail }
        if original.vaultFolderName != modified.vaultFolderName { merged.vaultFolderName = modified.vaultFolderName }
        if original.customVaultBookmarkData != modified.customVaultBookmarkData { merged.customVaultBookmarkData = modified.customVaultBookmarkData }
        if original.customLocationIsParent != modified.customLocationIsParent { merged.customLocationIsParent = modified.customLocationIsParent }
        if original.authMethod != modified.authMethod { merged.authMethod = modified.authMethod }
        if original.authUsername != modified.authUsername { merged.authUsername = modified.authUsername }
        if original.gitHubAccountLogin != modified.gitHubAccountLogin { merged.gitHubAccountLogin = modified.gitHubAccountLogin }
        if original.gitState.commitSHA != modified.gitState.commitSHA { merged.gitState.commitSHA = modified.gitState.commitSHA }
        if original.gitState.treeSHA != modified.gitState.treeSHA { merged.gitState.treeSHA = modified.gitState.treeSHA }
        if original.gitState.branch != modified.gitState.branch { merged.gitState.branch = modified.gitState.branch }
        if original.gitState.blobSHAs != modified.gitState.blobSHAs { merged.gitState.blobSHAs = modified.gitState.blobSHAs }
        if original.gitState.lastSyncDate != modified.gitState.lastSyncDate { merged.gitState.lastSyncDate = modified.gitState.lastSyncDate }
        if original.assist.enabled != modified.assist.enabled { merged.assist.enabled = modified.assist.enabled }
        if original.assist.channel != modified.assist.channel { merged.assist.channel = modified.assist.channel }
        if original.assist.selectedBranch != modified.assist.selectedBranch { merged.assist.selectedBranch = modified.assist.selectedBranch }
        if original.assist.networkPolicy != modified.assist.networkPolicy { merged.assist.networkPolicy = modified.assist.networkPolicy }
        if original.assist.powerPolicy != modified.assist.powerPolicy { merged.assist.powerPolicy = modified.assist.powerPolicy }
        if original.assist.health.kind != modified.assist.health.kind { merged.assist.health.kind = modified.assist.health.kind }
        if original.assist.health.attention != modified.assist.health.attention { merged.assist.health.attention = modified.assist.health.attention }
        if original.assist.health.message != modified.assist.health.message { merged.assist.health.message = modified.assist.health.message }
        if original.assist.health.lastAttemptDate != modified.assist.health.lastAttemptDate { merged.assist.health.lastAttemptDate = modified.assist.health.lastAttemptDate }
        if original.assist.health.lastSuccessDate != modified.assist.health.lastSuccessDate { merged.assist.health.lastSuccessDate = modified.assist.health.lastSuccessDate }
        if original.assist.health.commitSHA != modified.assist.health.commitSHA { merged.assist.health.commitSHA = modified.assist.health.commitSHA }
        return merged
    }
}

private extension NSLock {
    func withLock<T>(_ body: () throws -> T) rethrows -> T {
        lock(); defer { unlock() }; return try body()
    }
}

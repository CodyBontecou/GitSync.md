import Foundation

/// Persisted git state — tracks the current HEAD and file blob SHAs
struct GitState: Codable, Equatable, Sendable {
    var commitSHA: String
    var treeSHA: String
    var branch: String
    var blobSHAs: [String: String]  // relative path → blob SHA from last sync
    var lastSyncDate: Date

    static let empty = GitState(
        commitSHA: "",
        treeSHA: "",
        branch: "main",
        blobSHAs: [:],
        lastSyncDate: .distantPast
    )

    // MARK: - Legacy Persistence (for migration from single-repo)

    private static var legacyFileURL: URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let dir = support.appendingPathComponent("SyncMD", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("git_state.json")
    }

    static func loadLegacy() -> GitState? {
        guard let data = try? Data(contentsOf: legacyFileURL) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try? decoder.decode(GitState.self, from: data)
    }

    static func deleteLegacy() {
        try? FileManager.default.removeItem(at: legacyFileURL)
    }
}

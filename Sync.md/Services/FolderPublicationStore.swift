import Foundation
import Darwin

/// Small atomic journal outside Files-visible working copies. A malformed journal
/// blocks writes, so a pending external mutation can never be silently forgotten.
final class FolderPublicationStore: @unchecked Sendable {
    static var defaultURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("FolderPublication/progress.json")
    }

    private static let lock = NSLock()
    let url: URL

    init(url: URL = FolderPublicationStore.defaultURL) { self.url = url }

    func load() throws -> [FolderPublicationRecord] {
        try withLock { try read() }
    }

    func save(_ record: FolderPublicationRecord) throws {
        try write(record, expected: nil)
    }

    func replace(_ record: FolderPublicationRecord, expected: FolderPublicationRecord) throws {
        try write(record, expected: expected)
    }

    private func write(_ record: FolderPublicationRecord, expected: FolderPublicationRecord?) throws {
        try withLock {
            var records = try read()
            if let expected {
                guard records.first(where: { $0.id == record.id }) == expected else {
                    throw FolderPublicationError.unavailable(String(localized: "Publication progress changed in another operation. Reopen the saved progress before continuing."))
                }
            }
            if let index = records.firstIndex(where: { $0.id == record.id }) {
                records[index] = record
            } else {
                guard !records.contains(where: { $0.phase != .completed && $0.folderPath == record.folderPath }) else {
                    throw FolderPublicationError.unavailable(String(localized: "This folder already has a pending publication. Resume it instead."))
                }
                records.append(record)
            }
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            let data = try encoder.encode(records)
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    private func read() throws -> [FolderPublicationRecord] {
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        let records = try JSONDecoder().decode([FolderPublicationRecord].self, from: Data(contentsOf: url))
        guard Set(records.map(\.id)).count == records.count else {
            throw FolderPublicationError.unavailable(String(localized: "Folder publication progress contains duplicate identifiers. The saved file has been preserved."))
        }
        return records
    }

    private func withLock<T>(_ operation: () throws -> T) throws -> T {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let fd = open(url.appendingPathExtension("lock").path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw POSIXError(.EIO) }
        defer { close(fd) }
        guard flock(fd, LOCK_EX) == 0 else { throw POSIXError(.EIO) }
        defer { flock(fd, LOCK_UN) }
        return try operation()
    }
}

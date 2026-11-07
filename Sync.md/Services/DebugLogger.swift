import Foundation

/// Severity level for debug log entries.
enum LogLevel: String, Codable, Sendable, CaseIterable {
    case info
    case warning
    case error
}

/// A single timestamped log entry.
struct LogEntry: Identifiable, Codable, Sendable {
    let id: UUID
    let date: Date
    let level: LogLevel
    let category: String   // e.g. "pull", "push", "revert", "clone"
    let message: String
    let detail: String?    // optional extra context (error description, path, etc.)
    /// Display name of the repository (vault) the entry relates to, if any.
    let repoName: String?
    /// True for entries written by Background Sync work (BGTask, APNs wake).
    let isBackground: Bool

    /// Categories whose entries always belong to background work.
    static let backgroundCategories: Set<String> = ["background-sync"]

    init(level: LogLevel, category: String, message: String, detail: String? = nil,
         repoName: String? = nil, isBackground: Bool = false) {
        self.id = UUID()
        self.date = Date()
        self.level = level
        self.category = category
        self.message = message
        self.detail = detail
        self.repoName = repoName
        self.isBackground = isBackground || Self.backgroundCategories.contains(category)
    }

    // MARK: Codable (backward-compatible)

    private enum CodingKeys: String, CodingKey {
        case id, date, level, category, message, detail, repoName, isBackground
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        date = try c.decode(Date.self, forKey: .date)
        level = try c.decode(LogLevel.self, forKey: .level)
        category = try c.decode(String.self, forKey: .category)
        message = try c.decode(String.self, forKey: .message)
        detail = try c.decodeIfPresent(String.self, forKey: .detail)
        repoName = try c.decodeIfPresent(String.self, forKey: .repoName)
        isBackground = try c.decodeIfPresent(Bool.self, forKey: .isBackground)
            ?? Self.backgroundCategories.contains(category)
    }
}

/// Singleton in-memory log buffer with persistence.
@MainActor
final class DebugLogger {
    static let shared = DebugLogger()

    private(set) var entries: [LogEntry] = []
    private let maxEntries = 500
    private let storageKey = "debug_log_entries"

    private init() {
        load()
    }

    // MARK: - Public API

    /// - Parameters:
    ///   - repo: display name of the repository (vault) this entry relates to.
    ///   - background: marks entries written by Background Sync work.
    func log(_ level: LogLevel, category: String, _ message: String, detail: String? = nil,
             repo: String? = nil, background: Bool = false) {
        let entry = LogEntry(level: level, category: category, message: message, detail: detail,
                             repoName: repo, isBackground: background)
        entries.append(entry)
        if entries.count > maxEntries {
            entries.removeFirst(entries.count - maxEntries)
        }
        save()
    }

    func info(_ category: String, _ message: String, detail: String? = nil,
              repo: String? = nil, background: Bool = false) {
        log(.info, category: category, message, detail: detail, repo: repo, background: background)
    }

    func warning(_ category: String, _ message: String, detail: String? = nil,
                 repo: String? = nil, background: Bool = false) {
        log(.warning, category: category, message, detail: detail, repo: repo, background: background)
    }

    func error(_ category: String, _ message: String, detail: String? = nil,
               repo: String? = nil, background: Bool = false) {
        log(.error, category: category, message, detail: detail, repo: repo, background: background)
    }

    func clear() {
        entries.removeAll()
        save()
    }

    /// Formats all entries (or filtered) as a shareable plain-text string.
    func exportText(filter: LogLevel? = nil) -> String {
        exportText(entries: filter == nil ? entries : entries.filter { $0.level == filter })
    }

    func exportText(entries selected: [LogEntry]) -> String {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withFullDate, .withFullTime, .withFractionalSeconds]

        return selected.map { entry in
            var line = "[\(iso.string(from: entry.date))] [\(entry.level.rawValue.uppercased())] [\(entry.category)]"
            if entry.isBackground { line += " [BG]" }
            if let repo = entry.repoName { line += " [\(repo)]" }
            line += " \(entry.message)"
            if let detail = entry.detail {
                line += "\n  → \(detail)"
            }
            return line
        }.joined(separator: "\n")
    }

    // MARK: - Persistence

    private func save() {
        if let data = try? JSONEncoder().encode(entries) {
            UserDefaults.standard.set(data, forKey: storageKey)
        }
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode([LogEntry].self, from: data) else { return }
        entries = decoded
    }
}

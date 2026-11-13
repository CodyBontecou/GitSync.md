import Foundation

/// Writes a commit message from the changed paths alone — on device, no
/// network: "Add Nov 1 daily note, refine voice memo pipeline".
enum CommitMessageSuggester {
    enum Change: Equatable {
        case added, modified, deleted, renamed
    }

    struct File: Equatable {
        let path: String
        let change: Change
        var added: Int = 0
        var removed: Int = 0
        var oldPath: String? = nil
    }

    struct Suggestion: Equatable {
        let primary: String
        let alternatives: [String]
    }

    static func suggest(for files: [File], now: Date = Date(), locale: Locale = .current) -> Suggestion {
        let day = shortDate(now, locale: locale)
        let fallback = files.count == 1
            ? String(localized: "Update \(subject(for: files[0], locale: locale))")
            : String(localized: "Update notes")
        let primary = files.isEmpty ? fallback : message(for: files, locale: locale)
        var alternatives: [String] = []
        for candidate in [String(localized: "vault backup: \(day)"), fallback, String(localized: "Update notes")]
        where candidate != primary && !alternatives.contains(candidate) {
            alternatives.append(candidate)
        }
        return Suggestion(primary: primary, alternatives: Array(alternatives.prefix(2)))
    }

    // MARK: - Message

    static func message(for files: [File], locale: Locale = .current) -> String {
        let order: [Change] = [.added, .modified, .renamed, .deleted]
        let sorted = files.sorted {
            let a = order.firstIndex(of: $0.change)!, b = order.firstIndex(of: $1.change)!
            if a != b { return a < b }
            return ($0.added + $0.removed) > ($1.added + $1.removed)
        }

        // Many files of one kind in one folder read better grouped.
        if files.count > 3 {
            let kinds = Set(files.map(\.change))
            let folders = Set(files.map { folder(of: $0.path) })
            if kinds.count == 1, let kind = kinds.first {
                let place = folders.count == 1 && !(folders.first ?? "").isEmpty
                    ? " " + String(localized: "in \(folders.first!)/")
                    : ""
                return verb(kind, capitalized: true) + " " + String(localized: "\(files.count) notes") + place
            }
        }

        var clauses: [String] = []
        for (index, file) in sorted.enumerated() {
            let clause = verb(verbKind(file), capitalized: index == 0) + " " + subject(for: file, locale: locale)
            let candidate = (clauses + [clause]).joined(separator: ", ")
            if index >= 2 && candidate.count > 64 { break }
            if index >= 3 { break }
            clauses.append(clause)
        }
        var text = clauses.joined(separator: ", ")
        let remaining = sorted.count - clauses.count
        if remaining > 0 {
            text += remaining == 1
                ? " " + String(localized: "and 1 more")
                : " " + String(localized: "and \(remaining) more")
        }
        return text
    }

    private enum VerbKind { case add, update, refine, remove, rename }

    private static func verbKind(_ file: File) -> VerbKind {
        switch file.change {
        case .added: return .add
        case .deleted: return .remove
        case .renamed: return .rename
        case .modified: return file.added > 0 && file.removed > 0 ? .refine : .update
        }
    }

    private static func verb(_ change: Change, capitalized: Bool) -> String {
        switch change {
        case .added: return verb(.add, capitalized: capitalized)
        case .modified: return verb(.update, capitalized: capitalized)
        case .deleted: return verb(.remove, capitalized: capitalized)
        case .renamed: return verb(.rename, capitalized: capitalized)
        }
    }

    private static func verb(_ kind: VerbKind, capitalized: Bool) -> String {
        let word: String
        switch kind {
        case .add: word = String(localized: "add")
        case .update: word = String(localized: "update")
        case .refine: word = String(localized: "refine")
        case .remove: word = String(localized: "remove")
        case .rename: word = String(localized: "rename")
        }
        return capitalized ? word.prefix(1).uppercased() + word.dropFirst() : word
    }

    // MARK: - Subjects

    /// "Nov 1 daily note" for date-named notes, otherwise the humanised stem.
    static func subject(for file: File, locale: Locale = .current) -> String {
        let name = (file.path as NSString).lastPathComponent
        // Only strip a real extension: "standup 2026.11.07" keeps its date.
        let ext = (name as NSString).pathExtension
        let stem = ext.contains(where: \.isLetter) ? (name as NSString).deletingPathExtension : name
        if let date = dateInName(stem) {
            let dir = folder(of: file.path).lowercased()
            let kind: String
            if dir.contains("week") { kind = String(localized: "weekly note") }
            else if dir.contains("month") { kind = String(localized: "monthly note") }
            else { kind = String(localized: "daily note") }
            return shortDate(date, locale: locale) + " " + kind
        }
        let words = stem
            .replacingOccurrences(of: "-", with: " ")
            .replacingOccurrences(of: "_", with: " ")
            .trimmingCharacters(in: .whitespaces)
        let human = words.isEmpty ? name : words
        // Keep acronyms and Title Case as written; lowercase plain capitalised words.
        return human.split(separator: " ").map { word -> String in
            let w = String(word)
            if w.count > 1, w == w.uppercased() { return w }
            if let first = w.first, first.isUppercase, w.dropFirst().allSatisfy({ $0.isLowercase }) {
                return w.lowercased()
            }
            return w
        }.joined(separator: " ")
    }

    private static func folder(of path: String) -> String {
        (path as NSString).deletingLastPathComponent
    }

    /// Finds yyyy-MM-dd (or yyyy_MM_dd / yyyyMMdd) in a file stem.
    static func dateInName(_ stem: String) -> Date? {
        let patterns = [#"(\d{4})[-_.](\d{2})[-_.](\d{2})"#, #"^(\d{4})(\d{2})(\d{2})$"#]
        for pattern in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern),
                  let match = regex.firstMatch(in: stem, range: NSRange(stem.startIndex..., in: stem)),
                  match.numberOfRanges == 4 else { continue }
            func part(_ i: Int) -> Int? {
                Range(match.range(at: i), in: stem).flatMap { Int(stem[$0]) }
            }
            guard let y = part(1), let m = part(2), let d = part(3),
                  (1...12).contains(m), (1...31).contains(d) else { continue }
            var components = DateComponents()
            components.year = y; components.month = m; components.day = d
            if let date = Calendar(identifier: .gregorian).date(from: components) { return date }
        }
        return nil
    }

    static func shortDate(_ date: Date, locale: Locale = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.setLocalizedDateFormatFromTemplate("MMMd")
        return formatter.string(from: date)
    }
}

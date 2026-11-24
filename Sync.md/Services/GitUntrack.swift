import Foundation
import Darwin

/// What "Stop Tracking" removes from Git: one file, a folder, or a root-anchored
/// glob such as `.obsidian/workspace*.json`. Paths are relative to the vault.
enum GitUntrackTarget: Hashable, Sendable {
    case file(String)
    case folder(String)
    case pattern(String)

    /// Obsidian rewrites its window layout on every device that opens the vault,
    /// so these files conflict between devices when they are tracked.
    static let obsidianWorkspacePattern = ".obsidian/workspace*.json"
    static let obsidianWorkspace = GitUntrackTarget.pattern(obsidianWorkspacePattern)

    static func isObsidianWorkspaceFile(_ path: String) -> Bool {
        GitUntrackTarget.obsidianWorkspace.matches(path)
    }

    /// The vault-relative path or glob, without leading or trailing slashes.
    var path: String {
        switch self {
        case .file(let path), .folder(let path), .pattern(let path):
            return Self.normalize(path)
        }
    }

    /// `notes/a.md`, `notes/`, `.obsidian/workspace*.json`.
    var displayName: String {
        if case .folder = self { return path + "/" }
        return path
    }

    var commitMessage: String {
        "Stop tracking \(displayName)\n\nRemoved from Git and added to .gitignore by GitSync.md. The files stay on each device."
    }

    /// The root `.gitignore` line that ignores this target.
    var ignoreEntry: String {
        switch self {
        case .file(let raw): return "/" + GitIgnoreFile.escape(Self.normalize(raw))
        case .folder(let raw): return "/" + GitIgnoreFile.escape(Self.normalize(raw)) + "/"
        case .pattern(let raw): return "/" + Self.normalize(raw)
        }
    }

    /// Whether the tracked path `candidate` is removed by this target.
    func matches(_ candidate: String) -> Bool {
        let target = path
        switch self {
        case .file:
            return candidate == target
        case .folder:
            return candidate.hasPrefix(target + "/")
        case .pattern:
            // FNM_PATHNAME keeps `*` inside one path component, like Git.
            return fnmatch(target, candidate, FNM_PATHNAME) == 0
        }
    }

    /// Rejects paths that would leave the vault, touch `.git`, or untrack the
    /// ignore file itself.
    func validate() throws {
        let path = self.path
        let components = path.split(separator: "/", omittingEmptySubsequences: false)
        guard !path.isEmpty,
              !components.contains(where: { $0.isEmpty || $0 == "." || $0 == ".." }),
              components.first != ".git",
              path != GitIgnoreFile.name,
              !path.contains("\n"), !path.contains("\r"), !path.contains("\0") else {
            throw GitUntrackError.invalidPath(displayName)
        }
    }

    private static func normalize(_ path: String) -> String {
        var path = path
        while path.hasPrefix("/") { path.removeFirst() }
        while path.hasSuffix("/") { path.removeLast() }
        return path
    }
}

/// Reading and appending to a `.gitignore` without disturbing what is there.
enum GitIgnoreFile {
    static let name = ".gitignore"

    /// Escapes characters gitignore treats as glob syntax, and trailing spaces,
    /// so a literal path matches only itself. Spaces inside a name need no escape.
    static func escape(_ path: String) -> String {
        var escaped = ""
        for character in path {
            if "\\*?[".contains(character) { escaped.append("\\") }
            escaped.append(character)
        }
        var trailingSpaces = 0
        while escaped.hasSuffix(" ") {
            escaped.removeLast()
            trailingSpaces += 1
        }
        escaped += String(repeating: "\\ ", count: trailingSpaces)
        return escaped
    }

    /// Whether `content` already has `entry` as a rule (ignoring unescaped
    /// trailing whitespace and the optional leading `/` on multi-part paths).
    static func contains(_ entry: String, in content: String) -> Bool {
        let wanted = canonicalRule(entry)
        return content.split(omittingEmptySubsequences: false, whereSeparator: \.isNewline)
            .contains { canonicalRule(String($0)) == wanted }
    }

    /// `content` with `entry` on its own line at the end. Keeps CRLF files CRLF
    /// and adds the missing final newline before the new entry.
    static func appending(_ entry: String, to content: String) -> String {
        let newline = content.contains("\r\n") ? "\r\n" : "\n"
        var result = content
        // "\r\n" is one Character, so compare scalars, not characters.
        if let last = result.unicodeScalars.last, last != "\n" { result += newline }
        return result + entry + newline
    }

    private static func canonicalRule(_ line: String) -> String {
        var rule = line
        while let last = rule.last, last == " " || last == "\t" || last == "\r" {
            // An escaped trailing space is part of the rule.
            if last == " ", rule.dropLast().hasSuffix("\\") { break }
            rule.removeLast()
        }
        // `a/b` and `/a/b` are the same anchored rule; `a` and `/a` are not.
        if rule.hasPrefix("/"), rule.dropFirst().dropLast().contains("/") { rule.removeFirst() }
        return rule
    }
}

struct GitUntrackResult: Equatable, Sendable {
    /// Paths removed from the index (and from the commit's tree).
    let removedPaths: [String]
    /// The `.gitignore` line added, or nil when the rules already ignored the target.
    let addedIgnoreEntry: String?
    /// The new commit, or nil when nothing in HEAD changed (e.g. the file was only staged).
    let commitSHA: String?
}

enum GitUntrackError: LocalizedError, Equatable {
    case invalidPath(String)
    case notTracked(String)

    var errorDescription: String? {
        switch self {
        case .invalidPath(let path):
            return String(localized: "GitSync.md can't stop tracking “\(path)”.")
        case .notTracked(let path):
            return String(localized: "“\(path)” isn't tracked by Git and is already ignored.")
        }
    }
}

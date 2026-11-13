import Foundation

/// Rules for the single folder a remote vault is cloned into. The name is
/// appended to a parent directory (Documents or a user-chosen folder), so it
/// must stay one plain path component.
enum VaultFolderName {
    /// APFS limits one path component to 255 UTF-8 bytes.
    static let maximumBytes = 255

    enum Issue: Equatable {
        case empty, reserved, separator, leadingDot, controlCharacter, tooLong

        var message: String {
            switch self {
            case .empty: String(localized: "Enter a folder name.")
            case .reserved: String(localized: "“.” and “..” can't be used as a folder name.")
            case .separator: String(localized: "Folder names can't contain “/” or “:”.")
            case .leadingDot: String(localized: "Folder names can't start with a dot.")
            case .controlCharacter: String(localized: "Folder names can't contain control characters.")
            case .tooLong: String(localized: "That folder name is too long.")
            }
        }
    }

    static func issue(for rawName: String) -> Issue? {
        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty { return .empty }
        if name == "." || name == ".." { return .reserved }
        if name.contains("/") || name.contains(":") || name.contains("\\") { return .separator }
        if name.hasPrefix(".") { return .leadingDot }
        if name.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) { return .controlCharacter }
        if name.utf8.count > maximumBytes { return .tooLong }
        return nil
    }

    static func isValid(_ name: String) -> Bool { issue(for: name) == nil }

    /// First of `base`, `base-2`, `base-3`, … that `isTaken` rejects.
    static func unique(_ base: String, isTaken: (String) -> Bool) -> String {
        var candidate = base
        var suffix = 2
        while isTaken(candidate) {
            candidate = "\(base)-\(suffix)"
            suffix += 1
        }
        return candidate
    }

    /// `true` when `child` resolves to a direct-or-nested descendant of
    /// `parent` (never `parent` itself, never a sibling reached via `..`).
    static func isContained(_ child: URL, in parent: URL) -> Bool {
        func contained(_ childPath: String, _ parentPath: String) -> Bool {
            let prefix = parentPath.hasSuffix("/") ? parentPath : parentPath + "/"
            return childPath.hasPrefix(prefix) && childPath.count > prefix.count
        }
        // Compare lexically (".." removed) and with symlinks resolved, since
        // resolution of a not-yet-existing child may differ from its parent's.
        return contained(child.standardizedFileURL.path, parent.standardizedFileURL.path)
            || contained(child.standardizedFileURL.resolvingSymlinksInPath().path,
                         parent.standardizedFileURL.resolvingSymlinksInPath().path)
    }
}

/// Final file names for new notes: ".md" is added unless the user already
/// typed an extension the editor treats as text.
enum NoteFileName {
    static let maximumBytes = 255

    static let textExtensions: Set<String> = [
        "md", "markdown", "mdown", "mkd", "mdx", "txt", "text",
        "canvas", "base", "csv", "tsv", "org", "rst", "adoc", "tex",
        "json", "yaml", "yml", "toml", "xml", "html", "htm", "css",
        "js", "ts", "swift", "py", "sh",
    ]

    static func hasTextExtension(_ name: String) -> Bool {
        let ext = (name as NSString).pathExtension.lowercased()
        return !ext.isEmpty && textExtensions.contains(ext)
    }

    static func finalName(for trimmedName: String) -> String {
        guard !trimmedName.isEmpty else { return trimmedName }
        return hasTextExtension(trimmedName) ? trimmedName : trimmedName + ".md"
    }

    static func isTooLong(_ name: String) -> Bool { name.utf8.count > maximumBytes }
}

/// A single, visible path component for note/folder creation and renaming.
enum RepositoryItemName {
    static func validationMessage(_ rawName: String, allowsLeadingDot: Bool = true) -> String? {
        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        if rawName.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) {
            return String(localized: "Names can't contain control characters.")
        }
        if name.isEmpty { return String(localized: "Enter a name.") }
        if name == "." || name == ".." {
            return String(localized: "“.” and “..” can't be used as a name.")
        }
        if name.contains("/") || name.contains("\\") || name.contains(":") {
            return String(localized: "Names can't contain “/”, “\\” or “:”.")
        }
        if !allowsLeadingDot && name.hasPrefix(".") {
            return String(localized: "Names can't start with a dot.")
        }
        if NoteFileName.isTooLong(name) {
            return String(localized: "That name is too long. Names can be at most 255 bytes, including the extension.")
        }
        return nil
    }
}

/// Light checks for the author identity written into commit signatures
/// ("Name <email>"), where angle brackets and newlines would corrupt it.
enum GitIdentityValidation {
    /// C0 controls and DEL are invisible in identity fields; NUL also terminates libgit2 strings.
    /// Check raw input before trimming, while allowing Unicode format characters used by emoji.
    static func controlCharacterIssue(_ value: String) -> String? {
        guard containsControlCharacters(value) else { return nil }
        return String(localized: "Author identity cannot contain control characters.")
    }

    private static func containsControlCharacters(_ value: String) -> Bool {
        value.unicodeScalars.contains { $0.value < 0x20 || $0.value == 0x7F }
    }

    /// Empty is not an error here; required-field checks happen elsewhere.
    static func nameIssue(_ rawName: String) -> String? {
        if let issue = controlCharacterIssue(rawName) { return issue }
        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return nil }
        if name.contains(where: { $0 == "<" || $0 == ">" || $0.isNewline }) {
            return String(localized: "Names can't contain “<”, “>” or line breaks.")
        }
        return nil
    }

    static func emailIssue(_ rawEmail: String) -> String? {
        if let issue = controlCharacterIssue(rawEmail) { return issue }
        let email = rawEmail.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !email.isEmpty else { return nil }
        return isPlausibleEmail(email) ? nil : String(localized: "That doesn't look like an email address.")
    }

    static func isPlausibleEmail(_ email: String) -> Bool {
        guard !containsControlCharacters(email) else { return false }
        guard !email.contains(where: { $0.isWhitespace || $0 == "<" || $0 == ">" }) else { return false }
        let parts = email.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty else { return false }
        // Git accepts any `local@host`, including dotless hosts such as
        // `you@localhost` or `you@devbox`; only reject malformed domains.
        let domain = parts[1]
        guard !domain.isEmpty,
              !domain.hasPrefix("."), !domain.hasSuffix("."),
              !domain.contains("..") else { return false }
        return true
    }
}

import Foundation

/// Supported authentication strategies for a repository remote.
enum GitAuthMethod: String, Codable, Equatable, Sendable, CaseIterable {
    /// Use the app-wide GitHub OAuth/PAT token.
    case gitHubPAT = "github_pat"
    /// Do not provide credentials. Works for public HTTPS/git/file remotes.
    case none
    /// Use a username plus token/password stored in Keychain.
    case httpsToken = "https_token"
    /// Use an SSH private key stored in Keychain.
    case sshKey = "ssh_key"
}

/// A transport-only credentials payload used by `LocalGitService`.
///
/// `RepoConfig` stores only the non-secret method/username. Secrets are loaded
/// from Keychain by `AppState` and encoded into this short-lived value right
/// before libgit2 operations start.
struct GitRemoteCredentials: Codable, Equatable, Sendable {
    private static let payloadPrefix = "syncmd-auth-v1:"

    var method: GitAuthMethod
    var username: String
    var password: String
    var publicKey: String
    var privateKey: String
    var passphrase: String

    init(
        method: GitAuthMethod,
        username: String = "",
        password: String = "",
        publicKey: String = "",
        privateKey: String = "",
        passphrase: String = ""
    ) {
        self.method = method
        self.username = username
        self.password = password
        self.publicKey = publicKey
        self.privateKey = privateKey
        self.passphrase = passphrase
    }

    static let none = GitRemoteCredentials(method: .none)

    static func gitHubPAT(_ token: String) -> GitRemoteCredentials {
        GitRemoteCredentials(method: .gitHubPAT, username: "x-access-token", password: token)
    }

    static func httpsToken(username: String, password: String) -> GitRemoteCredentials {
        GitRemoteCredentials(method: .httpsToken, username: username, password: password)
    }

    static func sshKey(username: String, privateKey: String, publicKey: String = "", passphrase: String = "") -> GitRemoteCredentials {
        GitRemoteCredentials(
            method: .sshKey,
            username: username,
            publicKey: publicKey,
            privateKey: privateKey,
            passphrase: passphrase
        )
    }

    var isConfigured: Bool {
        switch method {
        case .none:
            return true
        case .gitHubPAT, .httpsToken:
            return !password.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .sshKey:
            return !privateKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
    }

    /// Encodes credentials into the existing `pat:` plumbing used by the git
    /// protocol. Empty payload means "no credentials". A non-prefixed payload
    /// is treated as the legacy app-wide GitHub PAT for backward compatibility.
    var transportPayload: String {
        guard method != .none else { return "" }
        guard let data = try? JSONEncoder().encode(self) else { return "" }
        return Self.payloadPrefix + data.base64EncodedString()
    }

    static func fromTransportPayload(_ payload: String) -> GitRemoteCredentials {
        let trimmed = payload.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return .none }

        if trimmed.hasPrefix(payloadPrefix) {
            let encoded = String(trimmed.dropFirst(payloadPrefix.count))
            if let data = Data(base64Encoded: encoded),
               let decoded = try? JSONDecoder().decode(GitRemoteCredentials.self, from: data) {
                return decoded
            }
            return .none
        }

        // Legacy call sites passed the GitHub PAT directly.
        return .gitHubPAT(trimmed)
    }
}

/// Parsed metadata for display and validation of Git remote URLs.
struct GitRemoteURL: Equatable, Sendable {
    enum RemoteKind: Equatable, Sendable {
        case github
        case http
        case ssh
        case git
        case file
        case scpLikeSSH
    }

    let rawValue: String
    let kind: RemoteKind
    let host: String?
    let username: String?
    let pathComponents: [String]
    let isGitHubShortcut: Bool

    var isGitHub: Bool {
        if kind == .github { return true }
        return host?.lowercased() == "github.com"
    }

    var isSSH: Bool {
        kind == .ssh || kind == .scpLikeSSH
    }

    var repoName: String {
        let candidate = pathComponents.last ?? rawValue
        return Self.strippingGitSuffix(candidate)
    }

    var ownerName: String? {
        guard pathComponents.count >= 2 else { return host }
        return pathComponents[pathComponents.count - 2]
    }

    var displayPath: String {
        if let ownerName {
            return "\(ownerName)/\(repoName)"
        }
        return repoName
    }

    /// A clone URL suitable for libgit2. This preserves fully-qualified custom
    /// remotes exactly as entered; only the historical `owner/repo` shorthand is
    /// expanded to GitHub.
    var cloneURLString: String {
        if isGitHubShortcut, pathComponents.count == 2 {
            return "https://github.com/\(pathComponents[0])/\(pathComponents[1]).git"
        }
        return rawValue
    }

    static func parse(_ input: String) -> GitRemoteURL? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.contains(" ") else { return nil }

        // Preserve the app's historical GitHub shorthand support.
        let slashParts = trimmed.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        if slashParts.count == 2,
           !trimmed.contains("://"),
           !trimmed.contains(":"),
           slashParts.allSatisfy({ !$0.isEmpty }) {
            return GitRemoteURL(
                rawValue: trimmed,
                kind: .github,
                host: "github.com",
                username: nil,
                pathComponents: slashParts,
                isGitHubShortcut: true
            )
        }

        if let url = URL(string: trimmed), let scheme = url.scheme?.lowercased() {
            switch scheme {
            case "http", "https":
                guard let host = url.host, !pathComponents(from: url).isEmpty else { return nil }
                return GitRemoteURL(
                    rawValue: trimmed,
                    kind: host.lowercased() == "github.com" ? .github : .http,
                    host: host,
                    username: url.user,
                    pathComponents: pathComponents(from: url),
                    isGitHubShortcut: false
                )
            case "ssh", "git+ssh":
                guard let host = url.host, !pathComponents(from: url).isEmpty else { return nil }
                return GitRemoteURL(
                    rawValue: trimmed,
                    kind: .ssh,
                    host: host,
                    username: url.user,
                    pathComponents: pathComponents(from: url),
                    isGitHubShortcut: false
                )
            case "git":
                guard let host = url.host, !pathComponents(from: url).isEmpty else { return nil }
                return GitRemoteURL(
                    rawValue: trimmed,
                    kind: .git,
                    host: host,
                    username: url.user,
                    pathComponents: pathComponents(from: url),
                    isGitHubShortcut: false
                )
            case "file":
                let parts = trimmedFilePathComponents(url)
                guard !parts.isEmpty else { return nil }
                return GitRemoteURL(
                    rawValue: trimmed,
                    kind: .file,
                    host: nil,
                    username: nil,
                    pathComponents: parts,
                    isGitHubShortcut: false
                )
            default:
                break
            }
        }

        // SCP-like SSH syntax: git@example.com:owner/repo.git
        if let parsed = parseSCPStyleSSH(trimmed) {
            return parsed
        }

        return nil
    }

    static func cloneURLString(from input: String) -> String? {
        parse(input)?.cloneURLString
    }

    private static func parseSCPStyleSSH(_ input: String) -> GitRemoteURL? {
        guard let colonIndex = input.firstIndex(of: ":") else { return nil }
        let left = String(input[..<colonIndex])
        let right = String(input[input.index(after: colonIndex)...])
        guard !left.isEmpty, !right.isEmpty, !left.contains("/") else { return nil }

        let userHost = left.split(separator: "@", maxSplits: 1).map(String.init)
        let username: String?
        let host: String
        if userHost.count == 2 {
            username = userHost[0]
            host = userHost[1]
        } else {
            username = nil
            host = userHost[0]
        }
        guard !host.isEmpty else { return nil }

        let parts = right
            .split(separator: "/", omittingEmptySubsequences: true)
            .map(String.init)
        guard !parts.isEmpty else { return nil }

        return GitRemoteURL(
            rawValue: input,
            kind: .scpLikeSSH,
            host: host,
            username: username,
            pathComponents: parts,
            isGitHubShortcut: false
        )
    }

    private static func pathComponents(from url: URL) -> [String] {
        url.pathComponents.filter { $0 != "/" && !$0.isEmpty }
    }

    private static func trimmedFilePathComponents(_ url: URL) -> [String] {
        url.pathComponents.filter { $0 != "/" && !$0.isEmpty }
    }

    private static func strippingGitSuffix(_ value: String) -> String {
        value.hasSuffix(".git") ? String(value.dropLast(4)) : value
    }
}

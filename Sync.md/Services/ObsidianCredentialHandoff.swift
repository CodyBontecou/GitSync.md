import Foundation
import CryptoKit

/// Only public metadata and encrypted credentials cross the shared vault boundary.
/// The native binding lives outside the vault, so a cloned file cannot authorize export.
final class ObsidianCredentialHandoff {
    static let directory = ".git/gitsync-obsidian"
    static let context = "gitsync-obsidian-v1"
    private let defaults: UserDefaults
    private let now: () -> Date
    private let storageKey = "obsidianCredentialHandoff.v1"

    struct Request: Codable {
        let version: Int
        let requestID: String
        let repoID: String
        let remoteURL: String
        let expiresAt: Double
    }

    private struct Binding: Codable {
        let request: Request
        let vaultPath: String
    }

    private struct Recipient: Decodable {
        let version: Int
        let requestID: String
        let publicKey: String
    }

    private struct Credentials: Encodable {
        let version: Int
        let requestID: String
        let remoteURL: String
        let username: String
        let password: String
        let authorName: String
        let authorEmail: String
        let expiresAt: Double
    }

    private struct Envelope: Encodable {
        let version: Int
        let requestID: String
        let publicKey: String
        let nonce: String
        let ciphertext: String
    }

    enum HandoffError: LocalizedError {
        case invalidRequest
        case unsafeLocation
        case unsupportedRemote

        var errorDescription: String? {
            switch self {
            case .invalidRequest:
                return "Obsidian connection expired or is unavailable. Clone again to create a new connection request."
            case .unsafeLocation:
                return "Obsidian connection requires a regular .git folder inside the vault."
            case .unsupportedRemote:
                return "Obsidian connection requires the original GitHub HTTPS remote and a GitHub token."
            }
        }
    }

    init(defaults: UserDefaults = .standard, now: @escaping () -> Date = Date.init) {
        self.defaults = defaults
        self.now = now
    }

    func invalidate(repoID: UUID) {
        var bindings = loadBindings()
        bindings.removeValue(forKey: repoID.uuidString)
        saveBindings(bindings)
    }

    /// Called only after a successful authenticated GitHub HTTPS clone. This also
    /// works when the user installs the companion after opening the cloned vault.
    @discardableResult
    func prepare(vaultURL: URL, repoID: UUID, remoteURL: String) throws -> Request {
        guard Self.isGitHubHTTPS(remoteURL) else { throw HandoffError.unsupportedRemote }
        let directory = try checkedDirectory(vaultURL: vaultURL, create: true)
        guard Self.originURL(in: String(decoding: try read(vaultURL.appendingPathComponent(".git/config")), as: UTF8.self)) == remoteURL else {
            throw HandoffError.unsupportedRemote
        }
        let request = Request(version: 1, requestID: UUID().uuidString, repoID: repoID.uuidString,
                              remoteURL: remoteURL, expiresAt: now().timeIntervalSince1970 + 7 * 86_400)
        for name in ["recipient.json", "response.json"] {
            let file = directory.appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
        }
        try write(request, to: directory.appendingPathComponent("request.json"))
        // Keep public connection metadata after import, so a user can explicitly
        // reconnect an expired/revoked token without replacing their working copy.
        try write(request, to: directory.appendingPathComponent("connection.json"))
        var bindings = loadBindings()
        bindings[repoID.uuidString] = Binding(request: request, vaultPath: vaultURL.resolvingSymlinksInPath().path)
        saveBindings(bindings)
        return request
    }

    /// The callback carries IDs only. The recipient's public key is read from the
    /// same granted vault, and credentials are never returned in a URL.
    func respond(vaultURL: URL, repoID: UUID, requestID: String,
                 remoteURL: String, username: String, token: String,
                 authorName: String, authorEmail: String) throws {
        var bindings = loadBindings()
        guard let binding = bindings[repoID.uuidString],
              binding.request.requestID == requestID,
              binding.request.expiresAt > now().timeIntervalSince1970,
              binding.vaultPath == vaultURL.resolvingSymlinksInPath().path else {
            throw HandoffError.invalidRequest
        }
        guard Self.isGitHubHTTPS(remoteURL), remoteURL == binding.request.remoteURL,
              !username.isEmpty, !token.isEmpty else { throw HandoffError.unsupportedRemote }
        let directory = try checkedDirectory(vaultURL: vaultURL, create: false)
        let config = try read(vaultURL.appendingPathComponent(".git/config"))
        guard Self.originURL(in: String(decoding: config, as: UTF8.self)) == remoteURL else {
            throw HandoffError.unsupportedRemote
        }
        let request = try JSONDecoder().decode(Request.self, from: read(directory.appendingPathComponent("request.json")))
        guard request.requestID == requestID, request.repoID == repoID.uuidString,
              request.remoteURL == remoteURL, request.version == 1 else { throw HandoffError.invalidRequest }
        let recipient = try JSONDecoder().decode(Recipient.self, from: read(directory.appendingPathComponent("recipient.json")))
        guard recipient.version == 1, recipient.requestID == requestID,
              let publicData = Data(base64Encoded: recipient.publicKey) else { throw HandoffError.invalidRequest }
        let publicKey = try P256.KeyAgreement.PublicKey(x963Representation: publicData)
        let privateKey = P256.KeyAgreement.PrivateKey()
        let shared = try privateKey.sharedSecretFromKeyAgreement(with: publicKey)
        let key = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(requestID.utf8),
                                                sharedInfo: Data(Self.context.utf8), outputByteCount: 32)
        let payload = Credentials(version: 1, requestID: requestID, remoteURL: remoteURL,
                                  username: username, password: token,
                                  authorName: authorName, authorEmail: authorEmail,
                                  expiresAt: min(binding.request.expiresAt, now().timeIntervalSince1970 + 300))
        let sealed = try AES.GCM.seal(JSONEncoder().encode(payload), using: key,
                                      authenticating: Data(requestID.utf8))
        let envelope = Envelope(version: 1, requestID: requestID,
                                publicKey: privateKey.publicKey.x963Representation.base64EncodedString(),
                                nonce: sealed.nonce.withUnsafeBytes { Data($0).base64EncodedString() },
                                ciphertext: (sealed.ciphertext + sealed.tag).base64EncodedString())
        try write(envelope, to: directory.appendingPathComponent("response.json"))
        bindings.removeValue(forKey: repoID.uuidString)
        saveBindings(bindings)
    }

    static func isGitHubHTTPS(_ value: String) -> Bool {
        guard let url = URLComponents(string: value), url.scheme == "https", url.host == "github.com",
              url.port == nil, url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else { return false }
        let parts = url.path.split(separator: "/")
        return parts.count == 2 && parts.allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
    }

    /// Reject ambiguous config, includes and alternate push destinations. We
    /// support the ordinary config written by clone, rather than guessing at it.
    static func originURL(in config: String) -> String? {
        var section = ""
        var urls: [String] = []
        for raw in config.components(separatedBy: .newlines) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty || line.hasPrefix("#") || line.hasPrefix(";") { continue }
            if line.hasPrefix("[") {
                section = line.lowercased()
                if section.hasPrefix("[include") || section.hasPrefix("[url ") { return nil }
            } else if section == "[remote \"origin\"]" {
                let pair = line.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
                guard pair.count == 2 else { return nil }
                if pair[0].lowercased() == "pushurl" { return nil }
                if pair[0].lowercased() == "url" { urls.append(pair[1]) }
            }
        }
        return urls.count == 1 ? urls[0] : nil
    }

    private func checkedDirectory(vaultURL: URL, create: Bool) throws -> URL {
        let git = vaultURL.appendingPathComponent(".git", isDirectory: true)
        let directory = vaultURL.appendingPathComponent(Self.directory, isDirectory: true)
        for url in [git, directory] {
            if FileManager.default.fileExists(atPath: url.path) {
                let values = try url.resourceValues(forKeys: [.isSymbolicLinkKey, .isDirectoryKey])
                guard values.isSymbolicLink != true, values.isDirectory == true else { throw HandoffError.unsafeLocation }
            } else if url == git || !create { throw HandoffError.unsafeLocation }
        }
        if create, !FileManager.default.fileExists(atPath: directory.path) {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        }
        return directory
    }

    private func read(_ url: URL) throws -> Data {
        let values = try url.resourceValues(forKeys: [.isSymbolicLinkKey, .isRegularFileKey, .fileSizeKey])
        guard values.isSymbolicLink != true, values.isRegularFile == true,
              let size = values.fileSize, size <= 65_536 else { throw HandoffError.unsafeLocation }
        return try Data(contentsOf: url)
    }

    private func write<T: Encodable>(_ value: T, to url: URL) throws {
        if FileManager.default.fileExists(atPath: url.path) {
            _ = try read(url)
        }
        let data = try JSONEncoder().encode(value)
        #if os(iOS)
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        #else
        try data.write(to: url, options: .atomic)
        #endif
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    private func loadBindings() -> [String: Binding] {
        guard let data = defaults.data(forKey: storageKey),
              let bindings = try? JSONDecoder().decode([String: Binding].self, from: data) else { return [:] }
        return bindings.filter { $0.value.request.expiresAt > now().timeIntervalSince1970 }
    }

    private func saveBindings(_ bindings: [String: Binding]) {
        defaults.set(try? JSONEncoder().encode(bindings), forKey: storageKey)
    }
}

import Foundation
import CryptoKit
import Clibgit2
import libgit2

@discardableResult
private func lfsGitCheck(_ code: Int32, context: String) throws -> Int32 {
    guard code >= 0 else {
        let message: String
        if let err = git_error_last(), let cMessage = err.pointee.message {
            message = String(cString: cMessage)
        } else {
            message = "Unknown git error"
        }
        throw LocalGitError.lfsFailed("\(context): \(message)")
    }
    return code
}

// MARK: - Git LFS Pointer

struct GitLFSPointer: Equatable, Hashable, Sendable {
    static let versionLine = "version https://git-lfs.github.com/spec/v1"

    let oid: String
    let size: Int64

    init(oid: String, size: Int64) {
        self.oid = oid.lowercased()
        self.size = size
    }

    init?(data: Data) {
        guard data.count <= 2048,
              let text = String(data: data, encoding: .utf8) else {
            return nil
        }

        let lines = text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        guard lines.first == Self.versionLine else { return nil }

        var parsedOID: String?
        var parsedSize: Int64?

        for line in lines.dropFirst() {
            if line.hasPrefix("oid sha256:") {
                let value = String(line.dropFirst("oid sha256:".count)).lowercased()
                guard value.count == 64,
                      value.allSatisfy({ $0.isHexDigit }) else {
                    return nil
                }
                parsedOID = value
            } else if line.hasPrefix("size ") {
                parsedSize = Int64(line.dropFirst("size ".count))
            }
        }

        guard let parsedOID, let parsedSize, parsedSize >= 0 else { return nil }
        self.oid = parsedOID
        self.size = parsedSize
    }

    var serializedString: String {
        """
        \(Self.versionLine)
        oid sha256:\(oid)
        size \(size)

        """
    }

    static func sha256Hex(for data: Data) -> String {
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    static func sha256HexAndSize(forFileAt url: URL) throws -> (oid: String, size: Int64) {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }

        var hasher = SHA256()
        var totalSize: Int64 = 0
        while true {
            let chunk = try handle.read(upToCount: 1024 * 1024) ?? Data()
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
            totalSize += Int64(chunk.count)
        }

        let digest = hasher.finalize()
        return (digest.map { String(format: "%02x", $0) }.joined(), totalSize)
    }
}

// MARK: - .gitattributes LFS Matching

struct GitLFSAttributes: Sendable {
    private struct Rule: Sendable {
        let pattern: String
        let isLFS: Bool?
        let isLockable: Bool?
    }

    private let rules: [Rule]

    init(text: String) {
        self.rules = text
            .split(separator: "\n", omittingEmptySubsequences: false)
            .compactMap { rawLine in
                let trimmed = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty, !trimmed.hasPrefix("#") else { return nil }

                let tokens = Self.tokens(in: trimmed)
                guard let pattern = tokens.first else { return nil }
                let attributes = tokens.dropFirst()
                let isLFS: Bool? = attributes.contains("filter=lfs") ? true : (attributes.contains("-filter") ? false : nil)
                let isLockable: Bool? = attributes.contains("lockable") ? true : (attributes.contains("-lockable") ? false : nil)
                return Rule(pattern: pattern, isLFS: isLFS, isLockable: isLockable)
            }
    }

    static func load(from repositoryURL: URL) -> GitLFSAttributes {
        let attributesURL = repositoryURL.appendingPathComponent(".gitattributes")
        let text = (try? String(contentsOf: attributesURL, encoding: .utf8)) ?? ""
        return GitLFSAttributes(text: text)
    }

    func isLFSTracked(path: String) -> Bool {
        lfsTrackingDecision(path: path) ?? false
    }

    func lfsTrackingDecision(path: String) -> Bool? {
        attributeValue(path: path, keyPath: \.isLFS)
    }

    func isLockable(path: String) -> Bool {
        attributeValue(path: path, keyPath: \.isLockable) ?? false
    }

    func hasExplicitLFSTrackingPattern(_ pattern: String) -> Bool {
        rules.contains { $0.pattern == pattern && $0.isLFS == true }
    }

    private func attributeValue(path: String, keyPath: KeyPath<Rule, Bool?>) -> Bool? {
        let normalizedPath = path.replacingOccurrences(of: "\\", with: "/")
        var result: Bool?

        for rule in rules {
            guard Self.matches(pattern: rule.pattern, path: normalizedPath),
                  let value = rule[keyPath: keyPath] else { continue }
            result = value
        }

        return result
    }

    private static func tokens(in line: String) -> [String] {
        var result: [String] = []
        var current = ""
        var isQuoted = false
        var isEscaped = false

        for character in line {
            if isEscaped {
                current.append("\\")
                current.append(character)
                isEscaped = false
                continue
            }

            if character == "\\" {
                isEscaped = true
                continue
            }

            if character == "\"" {
                isQuoted.toggle()
                continue
            }

            if !isQuoted, character == " " || character == "\t" {
                if !current.isEmpty {
                    result.append(current)
                    current = ""
                }
                continue
            }

            current.append(character)
        }

        if isEscaped {
            current.append("\\")
        }
        if !current.isEmpty {
            result.append(current)
        }
        return result
    }

    private static func matches(pattern rawPattern: String, path: String) -> Bool {
        var pattern = rawPattern
        if pattern.hasPrefix("/") {
            pattern.removeFirst()
        }

        if !pattern.contains("/") {
            return wildcardMatch(pattern: pattern, text: URL(fileURLWithPath: path).lastPathComponent)
        }

        return wildcardMatch(pattern: pattern, text: path)
    }

    private static func wildcardMatch(pattern: String, text: String) -> Bool {
        let patternChars = Array(pattern)
        let textChars = Array(text)
        var memo: [String: Bool] = [:]

        func match(_ p: Int, _ t: Int) -> Bool {
            let key = "\(p):\(t)"
            if let cached = memo[key] { return cached }

            let result: Bool
            if p == patternChars.count {
                result = t == textChars.count
            } else if patternChars[p] == "\\" {
                if p + 1 < patternChars.count {
                    result = t < textChars.count && patternChars[p + 1] == textChars[t] && match(p + 2, t + 1)
                } else {
                    result = t < textChars.count && textChars[t] == "\\" && match(p + 1, t + 1)
                }
            } else if patternChars[p] == "*" {
                if p + 1 < patternChars.count, patternChars[p + 1] == "*" {
                    var next = p + 2
                    if next < patternChars.count, patternChars[next] == "/" {
                        next += 1
                    }
                    result = match(next, t) || (t < textChars.count && match(p, t + 1))
                } else {
                    result = match(p + 1, t) || (t < textChars.count && textChars[t] != "/" && match(p, t + 1))
                }
            } else if patternChars[p] == "?" {
                result = t < textChars.count && textChars[t] != "/" && match(p + 1, t + 1)
            } else {
                result = t < textChars.count && patternChars[p] == textChars[t] && match(p + 1, t + 1)
            }

            memo[key] = result
            return result
        }

        return match(0, 0)
    }
}

// MARK: - Git LFS Auto Tracking Policy

struct GitLFSAutoTrackingCandidate: Equatable, Sendable, Identifiable {
    enum Reason: Equatable, Sendable {
        case knownBinaryExtension(String)
        case largeBinary
    }

    var id: String { path }
    let path: String
    let sizeBytes: Int64
    let patterns: [String]
    let reason: Reason
}

struct GitLFSAutoTrackingPolicy: Sendable {
    struct Rule: Sendable {
        let patterns: [String]
    }

    static let lfsAttributes = "filter=lfs diff=lfs merge=lfs -text"

    static let defaultBinaryExtensions: Set<String> = [
        "pdf",
        "mp4", "mov", "m4v", "webm",
        "mp3", "wav", "m4a", "aac", "flac",
        "zip", "tar", "gz", "tgz", "7z", "rar", "dmg",
        "psd", "ai", "sketch", "fig",
        "heic", "heif", "raw", "dng"
    ]

    static let `default` = GitLFSAutoTrackingPolicy()
    static let disabled = GitLFSAutoTrackingPolicy(binaryExtensions: [], largeFileThresholdBytes: .max)

    let binaryExtensions: Set<String>
    let largeFileThresholdBytes: Int64

    init(
        binaryExtensions: Set<String> = GitLFSAutoTrackingPolicy.defaultBinaryExtensions,
        largeFileThresholdBytes: Int64 = 10 * 1024 * 1024
    ) {
        self.binaryExtensions = Set(binaryExtensions.map { $0.lowercased() })
        self.largeFileThresholdBytes = largeFileThresholdBytes
    }

    func rule(forPath path: String, fileURL: URL) throws -> Rule? {
        let normalizedPath = path.replacingOccurrences(of: "\\", with: "/")
        let ext = URL(fileURLWithPath: normalizedPath).pathExtension
        if !ext.isEmpty, binaryExtensions.contains(ext.lowercased()) {
            return Rule(patterns: extensionPatterns(for: ext))
        }

        let size = try fileSize(at: fileURL)
        guard size > largeFileThresholdBytes,
              try isBinaryFile(at: fileURL) else {
            return nil
        }

        return Rule(patterns: [Self.exactPathPattern(for: normalizedPath)])
    }

    private func extensionPatterns(for pathExtension: String) -> [String] {
        let lower = pathExtension.lowercased()
        let upper = lower.uppercased()
        var variants: [String] = []
        for candidate in [lower, upper, pathExtension] where !candidate.isEmpty && !variants.contains(candidate) {
            variants.append(candidate)
        }
        return variants.map { "*.\($0)" }
    }

    private func fileSize(at fileURL: URL) throws -> Int64 {
        let values = try fileURL.resourceValues(forKeys: [.fileSizeKey])
        return Int64(values.fileSize ?? 0)
    }

    private func isBinaryFile(at fileURL: URL, sampleSize: Int = 8192) throws -> Bool {
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }
        let sample = try handle.read(upToCount: sampleSize) ?? Data()
        guard !sample.isEmpty else { return false }
        if sample.contains(0) { return true }
        return String(data: sample, encoding: .utf8) == nil
    }

    private static func exactPathPattern(for path: String) -> String {
        "/" + path.map { character in
            switch character {
            case " ", "\t", "\\", "\"", "*", "?", "[", "]", "#":
                return "\\\(character)"
            default:
                return String(character)
            }
        }.joined()
    }
}

// MARK: - HTTP Transport

protocol GitLFSHTTPTransport: AnyObject, Sendable {
    func response(for request: URLRequest, body: Data?) async throws -> (Data, HTTPURLResponse)
}

extension URLSession: GitLFSHTTPTransport {
    func response(for request: URLRequest, body: Data?) async throws -> (Data, HTTPURLResponse) {
        let tuple: (Data, URLResponse)
        if let body {
            tuple = try await upload(for: request, from: body)
        } else {
            tuple = try await data(for: request)
        }

        guard let response = tuple.1 as? HTTPURLResponse else {
            throw LocalGitError.lfsFailed("Git LFS server returned a non-HTTP response.")
        }
        return (tuple.0, response)
    }
}

// MARK: - Git LFS Auth

enum GitLFSOperation: String, Sendable {
    case download
    case upload
}

struct GitLFSAccess: Equatable, Sendable {
    let href: URL
    let headers: [String: String]
    let expiresAt: Date?

    init(href: URL, headers: [String: String] = [:], expiresAt: Date? = nil) {
        self.href = href
        self.headers = headers
        self.expiresAt = expiresAt
    }

    func isExpired(now: Date, leeway: TimeInterval = 60) -> Bool {
        guard let expiresAt else { return false }
        return now.addingTimeInterval(leeway) >= expiresAt
    }
}

// MARK: - Git LFS Locking

struct GitLFSLockOwner: Codable, Equatable, Sendable {
    let name: String?
    let email: String?

    init(name: String? = nil, email: String? = nil) {
        self.name = name
        self.email = email
    }
}

struct GitLFSLock: Codable, Equatable, Sendable {
    let id: String
    let path: String
    let lockedAt: Date?
    let owner: GitLFSLockOwner?

    init(id: String, path: String, lockedAt: Date? = nil, owner: GitLFSLockOwner? = nil) {
        self.id = id
        self.path = path
        self.lockedAt = lockedAt
        self.owner = owner
    }

    enum CodingKeys: String, CodingKey {
        case id
        case path
        case lockedAt = "locked_at"
        case owner
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try container.decode(String.self, forKey: .id)
        self.path = try container.decode(String.self, forKey: .path)
        self.owner = try container.decodeIfPresent(GitLFSLockOwner.self, forKey: .owner)
        if let lockedAtString = try container.decodeIfPresent(String.self, forKey: .lockedAt) {
            self.lockedAt = Self.parseDate(lockedAtString)
        } else {
            self.lockedAt = nil
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(path, forKey: .path)
        try container.encodeIfPresent(owner, forKey: .owner)
        if let lockedAt {
            try container.encode(Self.dateFormatter.string(from: lockedAt), forKey: .lockedAt)
        }
    }

    private static func parseDate(_ string: String) -> Date? {
        if let date = dateFormatter.date(from: string) { return date }
        return fractionalDateFormatter.date(from: string)
    }

    private static let dateFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    private static let fractionalDateFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}

struct GitLFSListLocksResult: Equatable, Sendable {
    let locks: [GitLFSLock]
    let nextCursor: String?
    let lockingSupported: Bool

    static let unsupported = GitLFSListLocksResult(locks: [], nextCursor: nil, lockingSupported: false)
}

struct GitLFSLockVerificationResult: Equatable, Sendable {
    let ours: [GitLFSLock]
    let theirs: [GitLFSLock]
    let nextCursor: String?
    let lockingSupported: Bool

    static let unsupported = GitLFSLockVerificationResult(ours: [], theirs: [], nextCursor: nil, lockingSupported: false)
}

struct GitLFSSSHAuthRequest: Equatable, Sendable {
    let username: String
    let host: String
    let port: Int
    let repositoryPath: String
    let operation: GitLFSOperation

    init(remote: GitRemoteURL, credentials: GitRemoteCredentials, operation: GitLFSOperation) throws {
        guard remote.isSSH, let host = remote.host else {
            throw LocalGitError.lfsFailed("Git LFS SSH authentication requires an SSH remote URL.")
        }

        let configuredUsername = credentials.username.trimmingCharacters(in: .whitespacesAndNewlines)
        self.username = configuredUsername.isEmpty ? (remote.username ?? "git") : configuredUsername
        self.host = host
        self.port = remote.sshPort ?? 22
        self.repositoryPath = remote.pathComponents.joined(separator: "/")
        self.operation = operation

        guard !repositoryPath.isEmpty else {
            throw LocalGitError.lfsFailed("Git LFS SSH authentication could not determine the repository path.")
        }
    }

    var command: String {
        "git-lfs-authenticate \(Self.shellQuote(repositoryPath)) \(operation.rawValue)"
    }

    private static func shellQuote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }
}

protocol GitLFSSSHAuthenticator: AnyObject, Sendable {
    func authenticate(request: GitLFSSSHAuthRequest, credentials: GitRemoteCredentials) async throws -> GitLFSAccess
}

private struct GitLFSAuthenticationHTTPError: Error {
    let statusCode: Int
    let context: String
    let responseMessage: String?

    init(statusCode: Int, context: String, responseMessage: String? = nil) {
        self.statusCode = statusCode
        self.context = context
        self.responseMessage = responseMessage
    }

    var isAuthFailure: Bool { statusCode == 401 || statusCode == 403 }

    var message: String {
        let base = "\(context) failed with HTTP \(statusCode)."
        guard let responseMessage,
              !responseMessage.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return base
        }
        return "\(base) \(responseMessage)"
    }
}

// MARK: - Git LFS Service

struct GitLFSHydrateResult: Equatable, Sendable {
    let pointerCount: Int
    let downloadedCount: Int
    let checkedOutCount: Int

    static let empty = GitLFSHydrateResult(pointerCount: 0, downloadedCount: 0, checkedOutCount: 0)
}

final class GitLFSService: @unchecked Sendable {
    private struct DiscoveredPointer: Sendable {
        let path: String
        let fileURL: URL
        let pointer: GitLFSPointer
    }

    private struct BatchObject: Codable {
        let oid: String
        let size: Int64
    }

    private struct BatchRequest: Encodable {
        let operation: String
        let transfers: [String]
        let ref: LockRef?
        let objects: [BatchObject]
    }

    private struct BatchResponse: Decodable {
        let transfer: String?
        let objects: [BatchObjectResponse]
    }

    private struct BatchObjectResponse: Decodable {
        struct ObjectError: Decodable {
            let code: Int?
            let message: String?
        }

        let oid: String
        let size: Int64?
        let authenticated: Bool?
        let actions: [String: BatchAction]?
        let error: ObjectError?
    }

    private struct BatchAction: Decodable {
        let href: String
        let header: [String: String]?
        let expiresAt: String?

        enum CodingKeys: String, CodingKey {
            case href
            case header
            case expiresAt = "expires_at"
        }
    }

    private struct LockRef: Codable {
        let name: String
    }

    private struct CreateLockRequest: Encodable {
        let path: String
        let ref: LockRef?
    }

    private struct LockResponse: Decodable {
        let lock: GitLFSLock
    }

    private struct ListLocksResponse: Decodable {
        let locks: [GitLFSLock]
        let nextCursor: String?

        enum CodingKeys: String, CodingKey {
            case locks
            case nextCursor = "next_cursor"
        }
    }

    private struct UnlockLockRequest: Encodable {
        let force: Bool
        let ref: LockRef?
    }

    private struct VerifyLocksRequest: Encodable {
        let ref: LockRef?
        let cursor: String?
        let limit: Int?
    }

    private struct VerifyLocksResponse: Decodable {
        let ours: [GitLFSLock]
        let theirs: [GitLFSLock]
        let nextCursor: String?

        enum CodingKeys: String, CodingKey {
            case ours
            case theirs
            case nextCursor = "next_cursor"
        }
    }

    let localURL: URL
    let credentials: GitRemoteCredentials
    private let transport: GitLFSHTTPTransport
    private let sshAuthenticator: GitLFSSSHAuthenticator?
    private let fileManager: FileManager
    private let now: @Sendable () -> Date
    private var accessCache: [GitLFSOperation: GitLFSAccess] = [:]

    init(
        localURL: URL,
        credentials: GitRemoteCredentials,
        transport: GitLFSHTTPTransport = URLSession.shared,
        sshAuthenticator: GitLFSSSHAuthenticator? = GitLFSCitadelSSHAuthenticator(),
        fileManager: FileManager = .default,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.localURL = localURL
        self.credentials = credentials
        self.transport = transport
        self.sshAuthenticator = sshAuthenticator
        self.fileManager = fileManager
        self.now = now
    }

    func hydrateWorktree() async throws -> GitLFSHydrateResult {
        let discovered = try discoverPointerFiles()
        guard !discovered.isEmpty else { return .empty }

        let uniquePointers = Array(Set(discovered.map(\.pointer))).sorted { $0.oid < $1.oid }
        let missingPointers = uniquePointers.filter { !fileManager.fileExists(atPath: cachedObjectURL(for: $0).path) }
        if !missingPointers.isEmpty {
            try await downloadObjects(missingPointers)
        }

        var checkedOut = 0
        for item in discovered {
            let objectURL = cachedObjectURL(for: item.pointer)
            guard fileManager.fileExists(atPath: objectURL.path) else {
                throw LocalGitError.lfsFailed("Missing downloaded LFS object \(item.pointer.oid) for \(item.path).")
            }
            try fileManager.copyReplacingItem(at: objectURL, to: item.fileURL)
            checkedOut += 1
        }

        return GitLFSHydrateResult(
            pointerCount: discovered.count,
            downloadedCount: missingPointers.count,
            checkedOutCount: checkedOut
        )
    }

    @discardableResult
    func uploadObjects(_ pointers: [GitLFSPointer]) async throws -> Int {
        let uniquePointers = Array(Set(pointers)).sorted { $0.oid < $1.oid }
        guard !uniquePointers.isEmpty else { return 0 }

        var uploaded = 0

        for pointerBatch in batches(of: uniquePointers) {
            let response = try await batch(operation: .upload, pointers: pointerBatch)

            for pointer in pointerBatch {
                guard let object = response.objects.first(where: { $0.oid == pointer.oid }) else {
                    throw LocalGitError.lfsFailed("Git LFS upload response omitted object \(pointer.oid).")
                }
                if let error = object.error {
                    throw LocalGitError.lfsFailed(error.message ?? "Git LFS upload rejected object \(pointer.oid).")
                }

                guard let upload = object.actions?["upload"] else {
                    // No upload action means the server already has the object.
                    continue
                }

                let objectURL = cachedObjectURL(for: pointer)
                guard fileManager.fileExists(atPath: objectURL.path) else {
                    throw LocalGitError.lfsFailed("Missing local LFS object \(pointer.oid).")
                }

                let data = try Data(contentsOf: objectURL)
                var uploadRequest = try request(urlString: upload.href, method: "PUT")
                for (key, value) in upload.header ?? [:] {
                    uploadRequest.setValue(value, forHTTPHeaderField: key)
                }
                let (_, uploadResponse) = try await transport.response(for: uploadRequest, body: data)
                try validateHTTP(uploadResponse, context: "Upload LFS object \(pointer.oid)")
                uploaded += 1

                if let verify = object.actions?["verify"] {
                    var verifyRequest = try request(urlString: verify.href, method: "POST")
                    verifyRequest.setValue("application/vnd.git-lfs+json", forHTTPHeaderField: "Content-Type")
                    verifyRequest.setValue("application/vnd.git-lfs+json", forHTTPHeaderField: "Accept")
                    for (key, value) in verify.header ?? [:] {
                        verifyRequest.setValue(value, forHTTPHeaderField: key)
                    }
                    let verifyBody = try JSONEncoder().encode(BatchObject(oid: pointer.oid, size: pointer.size))
                    let (_, verifyResponse) = try await transport.response(for: verifyRequest, body: verifyBody)
                    try validateHTTP(verifyResponse, context: "Verify LFS object \(pointer.oid)")
                }
            }
        }

        return uploaded
    }

    func createLock(path: String, refName: String? = nil) async throws -> GitLFSLock? {
        let body = try JSONEncoder().encode(
            CreateLockRequest(path: path, ref: refName.map { LockRef(name: $0) })
        )
        let response: LockResponse? = try await lockingRequest(
            method: "POST",
            pathComponents: ["locks"],
            body: body,
            context: "Create Git LFS lock"
        )
        return response?.lock
    }

    func listLocks(
        path: String? = nil,
        id: String? = nil,
        cursor: String? = nil,
        limit: Int? = nil,
        refspec: String? = nil
    ) async throws -> GitLFSListLocksResult {
        var queryItems: [URLQueryItem] = []
        if let path { queryItems.append(URLQueryItem(name: "path", value: path)) }
        if let id { queryItems.append(URLQueryItem(name: "id", value: id)) }
        if let cursor { queryItems.append(URLQueryItem(name: "cursor", value: cursor)) }
        if let limit { queryItems.append(URLQueryItem(name: "limit", value: String(limit))) }
        if let refspec { queryItems.append(URLQueryItem(name: "refspec", value: refspec)) }

        let response: ListLocksResponse? = try await lockingRequest(
            method: "GET",
            pathComponents: ["locks"],
            queryItems: queryItems,
            body: nil,
            context: "List Git LFS locks",
            accessOperation: .download
        )
        guard let response else { return .unsupported }
        return GitLFSListLocksResult(locks: response.locks, nextCursor: response.nextCursor, lockingSupported: true)
    }

    func unlockLock(id: String, force: Bool = false, refName: String? = nil) async throws -> GitLFSLock? {
        let body = try JSONEncoder().encode(
            UnlockLockRequest(force: force, ref: refName.map { LockRef(name: $0) })
        )
        let response: LockResponse? = try await lockingRequest(
            method: "POST",
            pathComponents: ["locks", id, "unlock"],
            body: body,
            context: "Unlock Git LFS lock"
        )
        return response?.lock
    }

    func verifyLocks(refName: String? = nil, cursor: String? = nil, limit: Int? = nil) async throws -> GitLFSLockVerificationResult {
        let body = try JSONEncoder().encode(
            VerifyLocksRequest(ref: refName.map { LockRef(name: $0) }, cursor: cursor, limit: limit)
        )
        let response: VerifyLocksResponse? = try await lockingRequest(
            method: "POST",
            pathComponents: ["locks", "verify"],
            body: body,
            context: "Verify Git LFS locks"
        )
        guard let response else { return .unsupported }
        return GitLFSLockVerificationResult(
            ours: response.ours,
            theirs: response.theirs,
            nextCursor: response.nextCursor,
            lockingSupported: true
        )
    }

    func verifyPushAllowed(changedPaths: [String], refName: String? = nil) async throws {
        let attributes = GitLFSAttributes.load(from: localURL)
        let lockableChangedPaths = Set(
            changedPaths
                .map { $0.replacingOccurrences(of: "\\", with: "/") }
                .filter { attributes.isLockable(path: $0) }
        )
        guard !lockableChangedPaths.isEmpty else { return }

        var cursor: String?
        repeat {
            let verification = try await verifyLocks(refName: refName, cursor: cursor)
            guard verification.lockingSupported else { return }

            let blockingLocks = verification.theirs.filter { lockableChangedPaths.contains($0.path) }
            guard blockingLocks.isEmpty else {
                let detail = blockingLocks
                    .map { lock in
                        if let owner = lock.owner?.name, !owner.isEmpty {
                            return "\(lock.path) (locked by \(owner))"
                        }
                        return lock.path
                    }
                    .joined(separator: ", ")
                throw LocalGitError.lfsFailed("Cannot push because these files are locked by another user: \(detail).")
            }
            cursor = verification.nextCursor
        } while cursor != nil
    }

    private func discoverPointerFiles() throws -> [DiscoveredPointer] {
        guard let enumerator = fileManager.enumerator(
            at: localURL,
            includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
            options: [.skipsPackageDescendants]
        ) else { return [] }

        var result: [DiscoveredPointer] = []
        for case let fileURL as URL in enumerator {
            let relative = relativePath(for: fileURL)
            if relative == ".git" || relative.hasPrefix(".git/") {
                enumerator.skipDescendants()
                continue
            }

            let values = try fileURL.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
            guard values.isRegularFile == true,
                  let size = values.fileSize,
                  size <= 2048 else { continue }

            let data = try Data(contentsOf: fileURL)
            guard let pointer = GitLFSPointer(data: data) else { continue }
            result.append(DiscoveredPointer(path: relative, fileURL: fileURL, pointer: pointer))
        }

        return result
    }

    private func downloadObjects(_ pointers: [GitLFSPointer]) async throws {
        for pointerBatch in batches(of: pointers) {
            let response = try await batch(operation: .download, pointers: pointerBatch)

            for pointer in pointerBatch {
                guard let object = response.objects.first(where: { $0.oid == pointer.oid }) else {
                    throw LocalGitError.lfsFailed("Git LFS download response omitted object \(pointer.oid).")
                }
                if let error = object.error {
                    throw LocalGitError.lfsFailed(error.message ?? "Git LFS download rejected object \(pointer.oid).")
                }
                guard let download = object.actions?["download"] else {
                    throw LocalGitError.lfsFailed("Git LFS server did not provide a download action for \(pointer.oid).")
                }

                var request = try request(urlString: download.href, method: "GET")
                for (key, value) in download.header ?? [:] {
                    request.setValue(value, forHTTPHeaderField: key)
                }

                let (data, httpResponse) = try await transport.response(for: request, body: nil)
                try validateHTTP(httpResponse, context: "Download LFS object \(pointer.oid)")
                guard Int64(data.count) == pointer.size,
                      GitLFSPointer.sha256Hex(for: data) == pointer.oid else {
                    throw LocalGitError.lfsFailed("Downloaded LFS object \(pointer.oid) failed SHA-256/size verification.")
                }

                try storeObject(data: data, pointer: pointer)
            }
        }
    }

    private func batches(of pointers: [GitLFSPointer], size: Int = 100) -> [[GitLFSPointer]] {
        guard pointers.count > size else { return [pointers] }
        return stride(from: 0, to: pointers.count, by: size).map { start in
            Array(pointers[start..<min(start + size, pointers.count)])
        }
    }

    private func batch(operation: GitLFSOperation, pointers: [GitLFSPointer]) async throws -> BatchResponse {
        do {
            return try await performBatch(operation: operation, pointers: pointers, forceRefreshAccess: false)
        } catch let error as GitLFSAuthenticationHTTPError where error.isAuthFailure {
            do {
                return try await performBatch(operation: operation, pointers: pointers, forceRefreshAccess: true)
            } catch let retryError as GitLFSAuthenticationHTTPError {
                throw LocalGitError.lfsFailed(retryError.message)
            }
        } catch let error as GitLFSAuthenticationHTTPError {
            throw LocalGitError.lfsFailed(error.message)
        }
    }

    private func performBatch(operation: GitLFSOperation, pointers: [GitLFSPointer], forceRefreshAccess: Bool) async throws -> BatchResponse {
        let access = try await lfsAccess(for: operation, forceRefresh: forceRefreshAccess)
        var request = URLRequest(url: appendBatchPath(to: access.href))
        request.httpMethod = "POST"
        request.setValue("application/vnd.git-lfs+json", forHTTPHeaderField: "Accept")
        request.setValue("application/vnd.git-lfs+json", forHTTPHeaderField: "Content-Type")
        for (key, value) in access.headers {
            request.setValue(value, forHTTPHeaderField: key)
        }

        let body = try JSONEncoder().encode(
            BatchRequest(
                operation: operation.rawValue,
                transfers: ["basic"],
                ref: currentHEADRefName().map { LockRef(name: $0) },
                objects: pointers.map { BatchObject(oid: $0.oid, size: $0.size) }
            )
        )

        let (data, response) = try await transport.response(for: request, body: body)
        guard (200..<300).contains(response.statusCode) else {
            throw GitLFSAuthenticationHTTPError(
                statusCode: response.statusCode,
                context: "Git LFS \(operation.rawValue) batch",
                responseMessage: batchErrorMessage(from: data)
            )
        }
        return try JSONDecoder().decode(BatchResponse.self, from: data)
    }

    private func lockingRequest<Response: Decodable>(
        method: String,
        pathComponents: [String],
        queryItems: [URLQueryItem] = [],
        body: Data?,
        context: String,
        accessOperation: GitLFSOperation = .upload
    ) async throws -> Response? {
        do {
            return try await performLockingRequest(
                method: method,
                pathComponents: pathComponents,
                queryItems: queryItems,
                body: body,
                context: context,
                accessOperation: accessOperation,
                forceRefreshAccess: false
            )
        } catch let error as GitLFSAuthenticationHTTPError where error.isAuthFailure {
            do {
                return try await performLockingRequest(
                    method: method,
                    pathComponents: pathComponents,
                    queryItems: queryItems,
                    body: body,
                    context: context,
                    accessOperation: accessOperation,
                    forceRefreshAccess: true
                )
            } catch let retryError as GitLFSAuthenticationHTTPError {
                throw LocalGitError.lfsFailed(retryError.message)
            }
        } catch let error as GitLFSAuthenticationHTTPError {
            throw LocalGitError.lfsFailed(error.message)
        }
    }

    private func performLockingRequest<Response: Decodable>(
        method: String,
        pathComponents: [String],
        queryItems: [URLQueryItem],
        body: Data?,
        context: String,
        accessOperation: GitLFSOperation,
        forceRefreshAccess: Bool
    ) async throws -> Response? {
        let access = try await lfsAccess(for: accessOperation, forceRefresh: forceRefreshAccess)
        var request = URLRequest(url: appendLFSPath(pathComponents, queryItems: queryItems, to: access.href))
        request.httpMethod = method
        request.setValue("application/vnd.git-lfs+json", forHTTPHeaderField: "Accept")
        if body != nil {
            request.setValue("application/vnd.git-lfs+json", forHTTPHeaderField: "Content-Type")
        }
        for (key, value) in access.headers {
            request.setValue(value, forHTTPHeaderField: key)
        }

        let (data, response) = try await transport.response(for: request, body: body)
        if response.statusCode == 404 || response.statusCode == 501 {
            return nil
        }
        guard (200..<300).contains(response.statusCode) else {
            throw GitLFSAuthenticationHTTPError(statusCode: response.statusCode, context: context)
        }
        return try JSONDecoder().decode(Response.self, from: data)
    }

    private func lfsAccess(for operation: GitLFSOperation, forceRefresh: Bool) async throws -> GitLFSAccess {
        if !forceRefresh,
           let cached = accessCache[operation],
           !cached.isExpired(now: now()) {
            return cached
        }

        let access = try await resolveLFSAccess(for: operation)
        accessCache[operation] = access
        return access
    }

    private func resolveLFSAccess(for operation: GitLFSOperation) async throws -> GitLFSAccess {
        if let configured = configuredLFSURL() {
            return try accessForConfiguredLFSURL(configured)
        }

        if let remote = originRemoteURL(), let parsed = GitRemoteURL.parse(remote) {
            if parsed.isSSH {
                guard credentials.method == .sshKey else {
                    throw LocalGitError.lfsFailed("Git LFS SSH authentication requires SSH key credentials.")
                }
                guard let sshAuthenticator else {
                    throw LocalGitError.lfsFailed("Git LFS SSH authentication is unavailable in this build.")
                }
                let request = try GitLFSSSHAuthRequest(remote: parsed, credentials: credentials, operation: operation)
                return try await sshAuthenticator.authenticate(request: request, credentials: credentials)
            }

            if let baseURL = lfsBaseURL(fromRemoteURL: remote) {
                return GitLFSAccess(href: baseURL, headers: basicAuthHeaders())
            }
        }

        throw LocalGitError.lfsFailed("Could not determine the Git LFS endpoint for this repository.")
    }

    private func configuredLFSURL() -> URL? {
        for url in [
            localURL.appendingPathComponent(".lfsconfig"),
            localURL.appendingPathComponent(".git/config")
        ] {
            if let value = Self.gitConfigValue(fileURL: url, section: "lfs", key: "url"),
               let parsed = URL(string: value) {
                return parsed
            }
        }
        return nil
    }

    private func accessForConfiguredLFSURL(_ url: URL) throws -> GitLFSAccess {
        guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else {
            throw LocalGitError.lfsFailed("Configured Git LFS URL must be HTTP(S) for this build: \(url.absoluteString)")
        }
        return GitLFSAccess(href: lfsBaseURL(from: url), headers: basicAuthHeaders())
    }

    private func originRemoteURL() -> String? {
        Self.gitConfigValue(fileURL: localURL.appendingPathComponent(".git/config"), section: "remote \"origin\"", key: "url")
    }

    private func lfsBaseURL(fromRemoteURL remote: String) -> URL? {
        let trimmed = remote.trimmingCharacters(in: .whitespacesAndNewlines)
        if let url = URL(string: trimmed), let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" {
            return lfsBaseURL(from: githubHTTPSRemoteURLWithGitSuffixIfNeeded(url))
        }

        return nil
    }

    private func githubHTTPSRemoteURLWithGitSuffixIfNeeded(_ url: URL) -> URL {
        guard url.host?.lowercased() == "github.com" else { return url }
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }

        var path = components.percentEncodedPath
        while path.hasSuffix("/") { path.removeLast() }
        guard !path.isEmpty, !path.hasSuffix(".git") else { return url }

        components.percentEncodedPath = path + ".git"
        return components.url ?? url
    }

    private func lfsBaseURL(from url: URL) -> URL {
        var absolute = url.absoluteString
        while absolute.hasSuffix("/") { absolute.removeLast() }
        if absolute.hasSuffix("/objects/batch") {
            absolute = String(absolute.dropLast("/objects/batch".count))
        }
        if absolute.hasSuffix("/info/lfs") {
            return URL(string: absolute)!
        }
        return URL(string: absolute + "/info/lfs")!
    }

    private func appendBatchPath(to url: URL) -> URL {
        var absolute = url.absoluteString
        while absolute.hasSuffix("/") { absolute.removeLast() }
        if absolute.hasSuffix("/objects/batch") {
            return URL(string: absolute)!
        }
        if absolute.hasSuffix("/info/lfs") {
            return URL(string: absolute + "/objects/batch")!
        }
        return URL(string: absolute + "/info/lfs/objects/batch")!
    }

    private func appendLFSPath(_ pathComponents: [String], queryItems: [URLQueryItem], to url: URL) -> URL {
        var absolute = url.absoluteString
        while absolute.hasSuffix("/") { absolute.removeLast() }
        if absolute.hasSuffix("/objects/batch") {
            absolute = String(absolute.dropLast("/objects/batch".count))
        }
        if !absolute.hasSuffix("/info/lfs") {
            absolute += "/info/lfs"
        }

        for component in pathComponents {
            absolute += "/" + component.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed)!
        }

        var components = URLComponents(string: absolute)!
        if !queryItems.isEmpty {
            components.queryItems = queryItems
        }
        return components.url!
    }

    private func currentHEADRefName() -> String? {
        let headURL = localURL.appendingPathComponent(".git/HEAD")
        guard let text = try? String(contentsOf: headURL, encoding: .utf8) else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("ref: ") else { return nil }
        return String(trimmed.dropFirst("ref: ".count))
    }

    private func batchErrorMessage(from data: Data) -> String? {
        struct ErrorResponse: Decodable {
            let message: String?
            let documentationURL: String?
            let requestID: String?

            enum CodingKeys: String, CodingKey {
                case message
                case documentationURL = "documentation_url"
                case requestID = "request_id"
            }
        }

        if let decoded = try? JSONDecoder().decode(ErrorResponse.self, from: data) {
            return [decoded.message, decoded.documentationURL, decoded.requestID.map { "Request ID: \($0)" }]
                .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .joined(separator: " ")
        }

        guard let text = String(data: data, encoding: .utf8) else { return nil }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        if let htmlTitle = htmlTitle(from: trimmed) {
            return "Server returned an HTML error page: \(htmlTitle)."
        }
        if looksLikeHTML(trimmed) {
            return "Server returned an HTML error page."
        }

        return trimmed.count > 500 ? String(trimmed.prefix(500)) + "…" : trimmed
    }

    private func looksLikeHTML(_ text: String) -> Bool {
        let lowercased = text.lowercased()
        return lowercased.contains("<!doctype html") || lowercased.contains("<html")
    }

    private func htmlTitle(from text: String) -> String? {
        guard let range = text.range(of: "<title", options: [.caseInsensitive]) else { return nil }
        guard let start = text[range.upperBound...].firstIndex(of: ">") else { return nil }
        guard let end = text[start...].range(of: "</title>", options: [.caseInsensitive])?.lowerBound else { return nil }

        let title = text[text.index(after: start)..<end]
            .replacingOccurrences(of: "&middot;", with: "·")
            .replacingOccurrences(of: "&amp;", with: "&")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return title.isEmpty ? nil : title
    }

    private static func gitConfigValue(fileURL: URL, section targetSection: String, key targetKey: String) -> String? {
        guard let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return nil }
        var currentSection: String?

        for rawLine in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty, !line.hasPrefix("#"), !line.hasPrefix(";") else { continue }

            if line.hasPrefix("["), line.hasSuffix("]") {
                currentSection = String(line.dropFirst().dropLast())
                continue
            }

            guard currentSection == targetSection,
                  let equals = line.firstIndex(of: "=") else { continue }
            let key = line[..<equals].trimmingCharacters(in: .whitespacesAndNewlines)
            guard key == targetKey else { continue }
            return line[line.index(after: equals)...].trimmingCharacters(in: .whitespacesAndNewlines)
        }

        return nil
    }

    private func request(urlString: String, method: String) throws -> URLRequest {
        guard let url = URL(string: urlString) else {
            throw LocalGitError.lfsFailed("Invalid Git LFS URL: \(urlString)")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        return request
    }

    private func basicAuthHeaders() -> [String: String] {
        let username = credentials.username.trimmingCharacters(in: .whitespacesAndNewlines)
        let password = credentials.password.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !password.isEmpty else { return [:] }

        let authUsername = username.isEmpty ? "x-access-token" : username
        let token = Data("\(authUsername):\(password)".utf8).base64EncodedString()
        return ["Authorization": "Basic \(token)"]
    }

    private func validateHTTP(_ response: HTTPURLResponse, context: String) throws {
        guard (200..<300).contains(response.statusCode) else {
            throw LocalGitError.lfsFailed("\(context) failed with HTTP \(response.statusCode).")
        }
    }

    private func cachedObjectURL(for pointer: GitLFSPointer) -> URL {
        cachedObjectURL(oid: pointer.oid)
    }

    private func cachedObjectURL(oid: String) -> URL {
        localURL
            .appendingPathComponent(".git/lfs/objects", isDirectory: true)
            .appendingPathComponent(String(oid.prefix(2)), isDirectory: true)
            .appendingPathComponent(String(oid.dropFirst(2).prefix(2)), isDirectory: true)
            .appendingPathComponent(oid)
    }

    private func storeObject(data: Data, pointer: GitLFSPointer) throws {
        let objectURL = cachedObjectURL(for: pointer)
        if fileManager.fileExists(atPath: objectURL.path) { return }
        try fileManager.createDirectory(at: objectURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: objectURL, options: .atomic)
    }

    private func relativePath(for fileURL: URL) -> String {
        let root = localURL.standardizedFileURL.path
        let path = fileURL.standardizedFileURL.path
        if path == root { return "" }
        return String(path.dropFirst(root.count + 1))
    }
}

// MARK: - Local libgit2 Integration

extension GitLFSService {
    static func autoTrackingCandidates(
        repositoryURL: URL,
        candidatePaths: [String]? = nil,
        autoTrackingPolicy: GitLFSAutoTrackingPolicy = .default
    ) throws -> [GitLFSAutoTrackingCandidate] {
        let attributes = GitLFSAttributes.load(from: repositoryURL)
        let paths = try candidatePaths ?? enumerateWorktreeFiles(in: repositoryURL)
        var candidates: [GitLFSAutoTrackingCandidate] = []

        for rawPath in paths {
            let path = rawPath.replacingOccurrences(of: "\\", with: "/")
            let fileURL = repositoryURL.appendingPathComponent(path)
            var isDirectory: ObjCBool = false
            guard FileManager.default.fileExists(atPath: fileURL.path, isDirectory: &isDirectory),
                  !isDirectory.boolValue else { continue }
            guard attributes.lfsTrackingDecision(path: path) == nil else { continue }
            guard let rule = try autoTrackingPolicy.rule(forPath: path, fileURL: fileURL) else { continue }

            let ext = URL(fileURLWithPath: path).pathExtension
            let reason: GitLFSAutoTrackingCandidate.Reason = !ext.isEmpty && autoTrackingPolicy.binaryExtensions.contains(ext.lowercased())
                ? .knownBinaryExtension(ext)
                : .largeBinary
            candidates.append(
                GitLFSAutoTrackingCandidate(
                    path: path,
                    sizeBytes: fileSize(at: fileURL),
                    patterns: rule.patterns,
                    reason: reason
                )
            )
        }

        return candidates.sorted { $0.path.localizedCaseInsensitiveCompare($1.path) == .orderedAscending }
    }

    static func cleanAndStageLFSFiles(
        repo: OpaquePointer?,
        index: OpaquePointer?,
        candidatePaths: [String]? = nil,
        autoTrackingPolicy: GitLFSAutoTrackingPolicy = .default
    ) throws {
        guard let repo,
              let index,
              let workdirPointer = git_repository_workdir(repo) else { return }

        let repositoryURL = URL(fileURLWithPath: String(cString: workdirPointer), isDirectory: true)
        let attributesURL = repositoryURL.appendingPathComponent(".gitattributes")
        var attributesText = (try? String(contentsOf: attributesURL, encoding: .utf8)) ?? ""
        var attributes = GitLFSAttributes(text: attributesText)
        let paths = try candidatePaths ?? enumerateWorktreeFiles(in: repositoryURL)
        var didModifyAttributes = false

        for rawPath in paths {
            let path = rawPath.replacingOccurrences(of: "\\", with: "/")
            let fileURL = repositoryURL.appendingPathComponent(path)
            var isDirectory: ObjCBool = false
            guard FileManager.default.fileExists(atPath: fileURL.path, isDirectory: &isDirectory),
                  !isDirectory.boolValue else { continue }

            let autoRule: GitLFSAutoTrackingPolicy.Rule?
            let shouldTrack: Bool
            if let existingDecision = attributes.lfsTrackingDecision(path: path) {
                shouldTrack = existingDecision
                autoRule = nil
            } else if let rule = try autoTrackingPolicy.rule(forPath: path, fileURL: fileURL) {
                shouldTrack = true
                autoRule = rule
            } else {
                shouldTrack = false
                autoRule = nil
            }
            guard shouldTrack else { continue }

            if let autoRule {
                let appended = try appendLFSAttributeRules(
                    autoRule.patterns,
                    attributesURL: attributesURL,
                    attributesText: &attributesText,
                    attributes: &attributes
                )
                didModifyAttributes = didModifyAttributes || appended
            }

            let pointer: GitLFSPointer
            if let existingPointer = try pointerFile(at: fileURL) {
                pointer = existingPointer
            } else {
                let info = try GitLFSPointer.sha256HexAndSize(forFileAt: fileURL)
                pointer = GitLFSPointer(oid: info.oid, size: info.size)
                try cacheObject(fileURL: fileURL, pointer: pointer, repositoryURL: repositoryURL)
            }

            try addPointer(pointer, path: path, to: index)
        }

        if didModifyAttributes {
            try stageGitattributes(in: index)
        }
    }

    static func pointersInIndex(repo: OpaquePointer?, index: OpaquePointer?) throws -> [GitLFSPointer] {
        guard let repo, let index else { return [] }
        let count = git_index_entrycount(index)
        var pointers: Set<GitLFSPointer> = []

        for i in 0..<count {
            guard let entry = git_index_get_byindex(index, i) else { continue }
            var oid = entry.pointee.id
            var blob: OpaquePointer?
            defer { if let blob { git_blob_free(blob) } }
            guard git_blob_lookup(&blob, repo, &oid) == 0,
                  let blob else { continue }

            if let pointer = pointer(in: blob) {
                pointers.insert(pointer)
            }
        }

        return Array(pointers).sorted { $0.oid < $1.oid }
    }

    static func validateNoLargeNonLFSBlobs(
        repo: OpaquePointer?,
        index: OpaquePointer?,
        candidatePaths: [String]? = nil,
        autoTrackingPolicy: GitLFSAutoTrackingPolicy = .default
    ) throws {
        guard let repo, let index else { return }
        let candidateSet = candidatePaths.map {
            Set($0.map { $0.replacingOccurrences(of: "\\", with: "/").precomposedStringWithCanonicalMapping })
        }
        let count = git_index_entrycount(index)
        var offenders: [(path: String, size: Int64)] = []

        for i in 0..<count {
            guard let entry = git_index_get_byindex(index, i),
                  let entryPath = entry.pointee.path else { continue }
            let path = String(cString: entryPath)
                .replacingOccurrences(of: "\\", with: "/")
                .precomposedStringWithCanonicalMapping
            if let candidateSet, !candidateSet.contains(path) { continue }

            var oid = entry.pointee.id
            var blob: OpaquePointer?
            defer { if let blob { git_blob_free(blob) } }
            guard git_blob_lookup(&blob, repo, &oid) == 0,
                  let blob else { continue }

            let size = Int64(git_blob_rawsize(blob))
            guard size > autoTrackingPolicy.largeFileThresholdBytes else { continue }
            guard pointer(in: blob) == nil else { continue }
            offenders.append((path: path, size: size))
        }

        guard offenders.isEmpty else {
            let detail = offenders
                .map { "\($0.path) (\(byteCountDescription($0.size)))" }
                .joined(separator: ", ")
            throw LocalGitError.lfsFailed(
                "Large files not tracked by Git LFS are staged as regular Git blobs: \(detail). Track them with Git LFS before pushing."
            )
        }
    }

    static func isCleanHydratedLFSFile(repo: OpaquePointer?, path: String, statusFlags: UInt32) -> Bool {
        let indexMask = GIT_STATUS_INDEX_NEW.rawValue
            | GIT_STATUS_INDEX_MODIFIED.rawValue
            | GIT_STATUS_INDEX_DELETED.rawValue
            | GIT_STATUS_INDEX_RENAMED.rawValue
            | GIT_STATUS_INDEX_TYPECHANGE.rawValue
        let allowedWorktreeMask = GIT_STATUS_WT_MODIFIED.rawValue
        guard statusFlags & indexMask == 0,
              statusFlags & allowedWorktreeMask != 0,
              statusFlags & ~allowedWorktreeMask == 0,
              let repo,
              let workdirPointer = git_repository_workdir(repo) else {
            return false
        }

        var index: OpaquePointer?
        defer { if let index { git_index_free(index) } }
        guard git_repository_index(&index, repo) == 0, let index else { return false }

        guard let pointer = indexPointer(repo: repo, index: index, path: path) else { return false }
        let fileURL = URL(fileURLWithPath: String(cString: workdirPointer), isDirectory: true)
            .appendingPathComponent(path)
        guard let info = try? GitLFSPointer.sha256HexAndSize(forFileAt: fileURL) else { return false }
        return info.oid == pointer.oid && info.size == pointer.size
    }

    private static func appendLFSAttributeRules(
        _ patterns: [String],
        attributesURL: URL,
        attributesText: inout String,
        attributes: inout GitLFSAttributes
    ) throws -> Bool {
        let newLines = patterns
            .filter { !attributes.hasExplicitLFSTrackingPattern($0) }
            .map { "\($0) \(GitLFSAutoTrackingPolicy.lfsAttributes)" }
        guard !newLines.isEmpty else { return false }

        if !attributesText.isEmpty, !attributesText.hasSuffix("\n") {
            attributesText.append("\n")
        }
        attributesText.append(newLines.joined(separator: "\n"))
        attributesText.append("\n")
        try attributesText.write(to: attributesURL, atomically: true, encoding: .utf8)
        attributes = GitLFSAttributes(text: attributesText)
        return true
    }

    private static func stageGitattributes(in index: OpaquePointer?) throws {
        _ = try ".gitattributes".withCString { path in
            try lfsGitCheck(
                git_index_add_bypath(index, path),
                context: "Stage .gitattributes for Git LFS auto-tracking"
            )
        }
    }

    private static func pointer(in blob: OpaquePointer?) -> GitLFSPointer? {
        guard let blob else { return nil }
        let size = Int(git_blob_rawsize(blob))
        guard size > 0, size <= 2048, let raw = git_blob_rawcontent(blob) else { return nil }
        return GitLFSPointer(data: Data(bytes: raw, count: size))
    }

    private static func byteCountDescription(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    private static func fileSize(at fileURL: URL) -> Int64 {
        let values = try? fileURL.resourceValues(forKeys: [.fileSizeKey])
        return Int64(values?.fileSize ?? 0)
    }

    private static func enumerateWorktreeFiles(in repositoryURL: URL) throws -> [String] {
        guard let enumerator = FileManager.default.enumerator(
            at: repositoryURL,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsPackageDescendants]
        ) else { return [] }

        var paths: [String] = []
        for case let fileURL as URL in enumerator {
            let relative = relativePath(fileURL, root: repositoryURL)
            if relative == ".git" || relative.hasPrefix(".git/") {
                enumerator.skipDescendants()
                continue
            }
            let values = try fileURL.resourceValues(forKeys: [.isRegularFileKey])
            if values.isRegularFile == true {
                paths.append(relative)
            }
        }
        return paths
    }

    private static func pointerFile(at fileURL: URL) throws -> GitLFSPointer? {
        let values = try fileURL.resourceValues(forKeys: [.fileSizeKey])
        guard let size = values.fileSize, size <= 2048 else { return nil }
        return GitLFSPointer(data: try Data(contentsOf: fileURL))
    }

    private static func cacheObject(fileURL: URL, pointer: GitLFSPointer, repositoryURL: URL) throws {
        let objectURL = cachedObjectURL(oid: pointer.oid, repositoryURL: repositoryURL)
        if FileManager.default.fileExists(atPath: objectURL.path) { return }
        try FileManager.default.createDirectory(at: objectURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: fileURL, to: objectURL)
    }

    private static func cachedObjectURL(oid: String, repositoryURL: URL) -> URL {
        repositoryURL
            .appendingPathComponent(".git/lfs/objects", isDirectory: true)
            .appendingPathComponent(String(oid.prefix(2)), isDirectory: true)
            .appendingPathComponent(String(oid.dropFirst(2).prefix(2)), isDirectory: true)
            .appendingPathComponent(oid)
    }

    private static func addPointer(_ pointer: GitLFSPointer, path: String, to index: OpaquePointer?) throws {
        var entry = git_index_entry()
        entry.mode = UInt32(GIT_FILEMODE_BLOB.rawValue)
        entry.file_size = UInt32(pointer.serializedString.utf8.count)

        let pointerData = Data(pointer.serializedString.utf8)
        try path.withCString { pathCString in
            entry.path = pathCString
            try pointerData.withUnsafeBytes { rawBuffer in
                guard let baseAddress = rawBuffer.baseAddress else { return }
                try lfsGitCheck(
                    git_index_add_from_buffer(index, &entry, baseAddress, pointerData.count),
                    context: "Stage Git LFS pointer for \(path)"
                )
            }
        }
    }

    private static func indexPointer(repo: OpaquePointer?, index: OpaquePointer?, path: String) -> GitLFSPointer? {
        guard let repo, let index else { return nil }
        guard let entry = path.withCString({ git_index_get_bypath(index, $0, 0) }) else { return nil }

        var oid = entry.pointee.id
        var blob: OpaquePointer?
        defer { if let blob { git_blob_free(blob) } }
        guard git_blob_lookup(&blob, repo, &oid) == 0,
              let blob else { return nil }

        let size = Int(git_blob_rawsize(blob))
        guard size > 0, size <= 2048, let raw = git_blob_rawcontent(blob) else { return nil }
        return GitLFSPointer(data: Data(bytes: raw, count: size))
    }

    private static func relativePath(_ fileURL: URL, root: URL) -> String {
        let rootPath = root.standardizedFileURL.path
        let path = fileURL.standardizedFileURL.path
        if path == rootPath { return "" }
        return String(path.dropFirst(rootPath.count + 1))
    }
}

private extension FileManager {
    func copyReplacingItem(at source: URL, to destination: URL) throws {
        if fileExists(atPath: destination.path) {
            try removeItem(at: destination)
        }
        try copyItem(at: source, to: destination)
    }
}

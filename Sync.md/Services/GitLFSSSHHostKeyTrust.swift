import Foundation
import Crypto
import NIO
import NIOSSH

protocol GitLFSSSHHostKeyTrustStore: AnyObject, Sendable {
    func trustedFingerprint(forHost host: String, port: Int) -> String?
    func trust(fingerprint: String, host: String, port: Int) throws
}

extension GitLFSSSHHostKeyTrustStore {
    func validate(fingerprint: String, host: String, port: Int) throws {
        let normalizedHost = GitLFSSSHHostKeyFileTrustStore.normalizeHost(host)
        let normalizedFingerprint = GitLFSSSHHostKeyFileTrustStore.normalizeFingerprint(fingerprint)

        guard let trusted = trustedFingerprint(forHost: normalizedHost, port: port) else {
            throw GitLFSSSHHostKeyTrustError.unknownHostKey(
                host: normalizedHost,
                port: port,
                fingerprint: normalizedFingerprint
            )
        }

        guard trusted == normalizedFingerprint else {
            throw GitLFSSSHHostKeyTrustError.changedHostKey(
                host: normalizedHost,
                port: port,
                expectedFingerprint: trusted,
                actualFingerprint: normalizedFingerprint
            )
        }
    }
}

enum GitLFSSSHHostKeyTrustError: LocalizedError, Equatable, Sendable {
    case unknownHostKey(host: String, port: Int, fingerprint: String)
    case changedHostKey(host: String, port: Int, expectedFingerprint: String, actualFingerprint: String)

    var errorDescription: String? {
        switch self {
        case .unknownHostKey(let host, let port, let fingerprint):
            return "Git LFS SSH host key for \(host):\(port) is not trusted. Fingerprint: \(fingerprint). Confirm this fingerprint before trusting the host."
        case .changedHostKey(let host, let port, let expectedFingerprint, let actualFingerprint):
            return "Git LFS SSH host key for \(host):\(port) changed. Expected \(expectedFingerprint), got \(actualFingerprint). This may indicate a man-in-the-middle attack."
        }
    }
}

final class GitLFSSSHHostKeyFileTrustStore: GitLFSSSHHostKeyTrustStore, @unchecked Sendable {
    struct TrustEntry: Codable, Equatable, Sendable {
        let host: String
        let port: Int
        let fingerprint: String
    }

    private struct TrustKey: Hashable, Sendable {
        let host: String
        let port: Int
    }

    static let `default` = GitLFSSSHHostKeyFileTrustStore()

    private let fileURL: URL
    private let fileManager: FileManager
    private let lock = NSLock()
    private var trustedFingerprints: [TrustKey: String]

    init(fileURL: URL? = nil, fileManager: FileManager = .default) {
        self.fileURL = fileURL ?? Self.defaultFileURL(fileManager: fileManager)
        self.fileManager = fileManager
        self.trustedFingerprints = Self.loadEntries(from: self.fileURL).reduce(into: [:]) { result, entry in
            result[TrustKey(host: Self.normalizeHost(entry.host), port: entry.port)] = Self.normalizeFingerprint(entry.fingerprint)
        }
    }

    func trustedFingerprint(forHost host: String, port: Int) -> String? {
        let key = TrustKey(host: Self.normalizeHost(host), port: port)
        lock.lock()
        defer { lock.unlock() }
        return trustedFingerprints[key]
    }

    func trust(fingerprint: String, host: String, port: Int) throws {
        let key = TrustKey(host: Self.normalizeHost(host), port: port)
        let normalizedFingerprint = Self.normalizeFingerprint(fingerprint)

        lock.lock()
        trustedFingerprints[key] = normalizedFingerprint
        let entries = trustedFingerprints.map { key, fingerprint in
            TrustEntry(host: key.host, port: key.port, fingerprint: fingerprint)
        }
        .sorted { lhs, rhs in
            if lhs.host != rhs.host { return lhs.host < rhs.host }
            return lhs.port < rhs.port
        }
        lock.unlock()

        try persist(entries)
    }

    static func normalizeHost(_ host: String) -> String {
        host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    static func normalizeFingerprint(_ fingerprint: String) -> String {
        fingerprint.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func defaultFileURL(fileManager: FileManager) -> URL {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.temporaryDirectory
        return base
            .appendingPathComponent("Sync.md", isDirectory: true)
            .appendingPathComponent("GitLFSKnownSSHHosts.json")
    }

    private static func loadEntries(from fileURL: URL) -> [TrustEntry] {
        guard let data = try? Data(contentsOf: fileURL), !data.isEmpty else { return [] }
        return (try? JSONDecoder().decode([TrustEntry].self, from: data)) ?? []
    }

    private func persist(_ entries: [TrustEntry]) throws {
        try fileManager.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(entries)
        try data.write(to: fileURL, options: .atomic)
    }
}

enum GitLFSSSHHostKeyFingerprint {
    static func sha256(hostKey: NIOSSHPublicKey) -> String {
        var buffer = ByteBufferAllocator().buffer(capacity: 512)
        hostKey.write(to: &buffer)
        let keyData = Data(buffer.readableBytesView)
        let digest = SHA256.hash(data: keyData)
        let base64 = Data(digest).base64EncodedString().trimmingCharacters(in: CharacterSet(charactersIn: "="))
        return "SHA256:\(base64)"
    }
}

final class GitLFSSSHHostKeyTrustDelegate: NIOSSHClientServerAuthenticationDelegate, @unchecked Sendable {
    private let host: String
    private let port: Int
    private let trustStore: any GitLFSSSHHostKeyTrustStore
    private let lock = NSLock()
    private var recordedFailure: GitLFSSSHHostKeyTrustError?

    init(host: String, port: Int, trustStore: any GitLFSSSHHostKeyTrustStore) {
        self.host = GitLFSSSHHostKeyFileTrustStore.normalizeHost(host)
        self.port = port
        self.trustStore = trustStore
    }

    var failure: GitLFSSSHHostKeyTrustError? {
        lock.lock()
        defer { lock.unlock() }
        return recordedFailure
    }

    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        do {
            let fingerprint = GitLFSSSHHostKeyFingerprint.sha256(hostKey: hostKey)
            try trustStore.validate(fingerprint: fingerprint, host: host, port: port)
            validationCompletePromise.succeed(())
        } catch let error as GitLFSSSHHostKeyTrustError {
            record(error)
            validationCompletePromise.fail(error)
        } catch {
            validationCompletePromise.fail(error)
        }
    }

    private func record(_ error: GitLFSSSHHostKeyTrustError) {
        lock.lock()
        recordedFailure = error
        lock.unlock()
    }
}

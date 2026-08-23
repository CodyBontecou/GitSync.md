import Foundation
import Crypto
import NIO
import NIOSSH
import Clibgit2
import libgit2

// MARK: - Host-Key Pins

/// Per-host SSH host-key pins, bucketed by public-key algorithm.
///
/// GitHub-style SSH servers present a *different* host key for each algorithm
/// (RSA, ECDSA, Ed25519), and the two SSH stacks this app uses — libssh2 for
/// git transport and NIOSSH for Git LFS — can negotiate different algorithms
/// against the same server. Pinning one fingerprint per host therefore
/// misreads a legitimate second key type as a hostile key change. Pins are
/// stored per algorithm, exactly like OpenSSH's `known_hosts`.
struct GitLFSSSHHostKeyPins: Equatable, Sendable {
    /// Pins keyed by SSH public-key algorithm name, e.g. "ssh-ed25519".
    var algorithms: [String: String] = [:]

    /// Fingerprints pinned by app versions before per-algorithm pinning
    /// shipped (no algorithm recorded). Retained so upgrades keep trusting
    /// keys the previous build pinned.
    var legacyFingerprints: Set<String> = []

    var isEmpty: Bool {
        algorithms.isEmpty && legacyFingerprints.isEmpty
    }

    /// Whether any recorded pin — algorithm-specific or legacy — covers the
    /// given fingerprint. A fingerprint match means the very same key
    /// material was pinned before, regardless of which bucket recorded it.
    func accepts(fingerprint: String) -> Bool {
        legacyFingerprints.contains(fingerprint) || algorithms.values.contains(fingerprint)
    }
}

// MARK: - Algorithm Names

/// Normalized SSH public-key algorithm names shared by both SSH stacks.
enum GitLFSSSHHostKeyAlgorithm {
    /// Placeholder used when the negotiated host-key algorithm cannot be
    /// determined (e.g. libssh2 without raw host-key data). Pins recorded
    /// under this name still work; they are simply not algorithm-specific.
    static let unknown = "ssh-unknown"

    /// Maps libgit2's raw host-key type (libssh2 transport) to the SSH
    /// algorithm name used in public key blobs.
    static func name(forLibGit2RawType rawType: git_cert_ssh_raw_type_t) -> String? {
        switch rawType {
        case GIT_CERT_SSH_RAW_TYPE_RSA:
            return "ssh-rsa"
        case GIT_CERT_SSH_RAW_TYPE_DSS:
            return "ssh-dss"
        case GIT_CERT_SSH_RAW_TYPE_KEY_ECDSA_256:
            return "ecdsa-sha2-nistp256"
        case GIT_CERT_SSH_RAW_TYPE_KEY_ECDSA_384:
            return "ecdsa-sha2-nistp384"
        case GIT_CERT_SSH_RAW_TYPE_KEY_ECDSA_521:
            return "ecdsa-sha2-nistp521"
        case GIT_CERT_SSH_RAW_TYPE_KEY_ED25519:
            return "ssh-ed25519"
        default:
            return nil
        }
    }

    /// Reads the algorithm name out of an NIOSSH public key's wire-format
    /// blob (the blob's first SSH string is the algorithm name).
    static func name(forNIOSSHPublicKey key: NIOSSHPublicKey) -> String {
        var buffer = ByteBufferAllocator().buffer(capacity: 512)
        key.write(to: &buffer)
        guard let length = buffer.readInteger(as: UInt32.self), length > 0,
              length <= buffer.readableBytes,
              let bytes = buffer.readBytes(length: Int(length)),
              let name = String(bytes: bytes, encoding: .utf8),
              !name.isEmpty else {
            return unknown
        }
        return name.lowercased()
    }
}

// MARK: - Trust Store

protocol GitLFSSSHHostKeyTrustStore: AnyObject, Sendable {
    /// All pins recorded for a host, including built-in well-known seeds.
    func trustedFingerprints(forHost host: String, port: Int) -> GitLFSSSHHostKeyPins
    func trust(algorithm: String, fingerprint: String, host: String, port: Int) throws
}

extension GitLFSSSHHostKeyTrustStore {
    /// Validates a presented host key against the store's pins.
    ///
    /// - Same algorithm, same fingerprint → accepted.
    /// - Same algorithm, different fingerprint → `changedHostKey`. A pinned
    ///   key type presenting different key material is the classic
    ///   man-in-the-middle signal.
    /// - Fingerprint matches a pin under any other bucket → accepted. It is
    ///   the same key, reached through a different SSH stack or algorithm.
    /// - Unseen fingerprint → `unknownHostKey` prompt. Servers legitimately
    ///   offer several key types, so a type we have not pinned yet is not
    ///   evidence of an attack.
    func validate(algorithm: String, fingerprint: String, host: String, port: Int) throws {
        let normalizedHost = GitLFSSSHHostKeyFileTrustStore.normalizeHost(host)
        let normalizedFingerprint = GitLFSSSHHostKeyFileTrustStore.normalizeFingerprint(fingerprint)
        let normalizedAlgorithm = GitLFSSSHHostKeyFileTrustStore.normalizeAlgorithm(algorithm)
        let pins = trustedFingerprints(forHost: normalizedHost, port: port)

        if let pinned = pins.algorithms[normalizedAlgorithm] {
            guard pinned == normalizedFingerprint else {
                throw GitLFSSSHHostKeyTrustError.changedHostKey(
                    host: normalizedHost,
                    port: port,
                    algorithm: normalizedAlgorithm,
                    expectedFingerprint: pinned,
                    actualFingerprint: normalizedFingerprint
                )
            }
            return
        }

        if pins.accepts(fingerprint: normalizedFingerprint) {
            return
        }

        throw GitLFSSSHHostKeyTrustError.unknownHostKey(
            host: normalizedHost,
            port: port,
            algorithm: normalizedAlgorithm,
            fingerprint: normalizedFingerprint,
            sawOtherKeyTypes: !pins.isEmpty
        )
    }
}

enum GitLFSSSHHostKeyTrustError: LocalizedError, Equatable, Sendable {
    case unknownHostKey(
        host: String,
        port: Int,
        algorithm: String,
        fingerprint: String,
        sawOtherKeyTypes: Bool
    )
    case changedHostKey(
        host: String,
        port: Int,
        algorithm: String,
        expectedFingerprint: String,
        actualFingerprint: String
    )

    var errorDescription: String? {
        switch self {
        case .unknownHostKey(let host, let port, let algorithm, let fingerprint, _):
            let portString = String(port)
            return String(localized: "Git LFS SSH host key for \(host):\(portString) (\(algorithm)) is not trusted. Fingerprint: \(fingerprint). Confirm this fingerprint before trusting the host.")
        case .changedHostKey(let host, let port, let algorithm, let expectedFingerprint, let actualFingerprint):
            let portString = String(port)
            return String(localized: "Git LFS SSH host key for \(host):\(portString) (\(algorithm)) changed. Expected \(expectedFingerprint), got \(actualFingerprint). This may indicate a man-in-the-middle attack.")
        }
    }
}

// MARK: - Well-Known Hosts

/// SHA-256 host-key fingerprints published by well-known Git hosts.
///
/// Pre-trusting these mirrors the HTTPS model of shipping well-known roots:
/// first connections to GitHub never prompt, while any other host still gets
/// the explicit trust dialog.
enum GitLFSSSHKnownHostsSeed {
    /// Fingerprints published at
    /// https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/githubs-ssh-key-fingerprints
    /// and verified against github.com:22 on 2026-08-22.
    ///
    /// `ssh.github.com:443` deliberately has no seed here: GitHub does not
    /// publish separate fingerprints for it, so it stays on the normal
    /// trust-on-first-use path.
    private static let githubDotCom: [String: String] = [
        "ssh-ed25519": "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU",
        "ecdsa-sha2-nistp256": "SHA256:p2QAMXNIC1TJYWeIOttrVc98/R1BUFWu3/LiyKgUfQM",
        "ssh-rsa": "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s"
    ]

    static func fingerprints(forHost host: String, port: Int) -> [String: String]? {
        guard port == 22, host == "github.com" else { return nil }
        return githubDotCom
    }
}

// MARK: - File-Backed Store

final class GitLFSSSHHostKeyFileTrustStore: GitLFSSSHHostKeyTrustStore, @unchecked Sendable {
    struct TrustEntry: Codable, Equatable, Sendable {
        let host: String
        let port: Int
        let fingerprint: String
        /// Pins written before per-algorithm pinning shipped decode with nil.
        var algorithm: String?
    }

    private struct TrustKey: Hashable, Sendable {
        let host: String
        let port: Int
    }

    static let `default` = GitLFSSSHHostKeyFileTrustStore()

    private let fileURL: URL
    private let fileManager: FileManager
    private let lock = NSLock()
    private var algorithmPins: [TrustKey: [String: String]]
    private var legacyPins: [TrustKey: Set<String>]

    init(fileURL: URL? = nil, fileManager: FileManager = .default) {
        self.fileURL = fileURL ?? Self.defaultFileURL(fileManager: fileManager)
        self.fileManager = fileManager
        let entries = Self.loadEntries(from: self.fileURL)
        var algorithmPins: [TrustKey: [String: String]] = [:]
        var legacyPins: [TrustKey: Set<String>] = [:]
        for entry in entries {
            let key = TrustKey(host: Self.normalizeHost(entry.host), port: entry.port)
            let fingerprint = Self.normalizeFingerprint(entry.fingerprint)
            if let algorithm = entry.algorithm.map(Self.normalizeAlgorithm) {
                algorithmPins[key, default: [:]][algorithm] = fingerprint
            } else {
                legacyPins[key, default: []].insert(fingerprint)
            }
        }
        self.algorithmPins = algorithmPins
        self.legacyPins = legacyPins
    }

    func trustedFingerprints(forHost host: String, port: Int) -> GitLFSSSHHostKeyPins {
        let key = TrustKey(host: Self.normalizeHost(host), port: port)
        lock.lock()
        defer { lock.unlock() }

        var pins = GitLFSSSHHostKeyPins(
            algorithms: algorithmPins[key] ?? [:],
            legacyFingerprints: legacyPins[key] ?? []
        )

        // Built-in well-known hosts fill any algorithm the user has not
        // explicitly pinned (or re-pinned) themselves.
        if let seeded = GitLFSSSHKnownHostsSeed.fingerprints(forHost: key.host, port: port) {
            for (algorithm, fingerprint) in seeded where pins.algorithms[algorithm] == nil {
                pins.algorithms[algorithm] = fingerprint
            }
        }

        return pins
    }

    func trust(algorithm: String, fingerprint: String, host: String, port: Int) throws {
        let key = TrustKey(host: Self.normalizeHost(host), port: port)
        let normalizedFingerprint = Self.normalizeFingerprint(fingerprint)
        let normalizedAlgorithm = Self.normalizeAlgorithm(algorithm)

        lock.lock()
        algorithmPins[key, default: [:]][normalizedAlgorithm] = normalizedFingerprint

        var entries: [TrustEntry] = []
        for (entryKey, algorithms) in algorithmPins {
            let sortedAlgorithms = algorithms.sorted { lhs, rhs in lhs.key < rhs.key }
            for (algorithm, entryFingerprint) in sortedAlgorithms {
                entries.append(
                    TrustEntry(
                        host: entryKey.host,
                        port: entryKey.port,
                        fingerprint: entryFingerprint,
                        algorithm: algorithm
                    )
                )
            }
        }
        // Pre-algorithm pins stay on disk until the same fingerprint is
        // pinned under a concrete algorithm, so upgrades never lose trust.
        for (legacyKey, legacyFingerprints) in legacyPins {
            var pinnedFingerprints: Set<String> = []
            if let algorithms = algorithmPins[legacyKey] {
                for pinnedFingerprint in algorithms.values {
                    pinnedFingerprints.insert(pinnedFingerprint)
                }
            }
            for legacyFingerprint in legacyFingerprints where !pinnedFingerprints.contains(legacyFingerprint) {
                entries.append(
                    TrustEntry(
                        host: legacyKey.host,
                        port: legacyKey.port,
                        fingerprint: legacyFingerprint,
                        algorithm: nil
                    )
                )
            }
        }
        entries.sort { lhs, rhs in
            if lhs.host != rhs.host { return lhs.host < rhs.host }
            if lhs.port != rhs.port { return lhs.port < rhs.port }
            if lhs.algorithm != rhs.algorithm { return (lhs.algorithm ?? "") < (rhs.algorithm ?? "") }
            return lhs.fingerprint < rhs.fingerprint
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

    static func normalizeAlgorithm(_ algorithm: String) -> String {
        algorithm.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
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

// MARK: - Fingerprints

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

// MARK: - NIOSSH Validation

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
            let algorithm = GitLFSSSHHostKeyAlgorithm.name(forNIOSSHPublicKey: hostKey)
            try trustStore.validate(algorithm: algorithm, fingerprint: fingerprint, host: host, port: port)
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

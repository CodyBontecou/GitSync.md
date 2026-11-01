import Foundation
import Security
import Crypto
import Citadel

/// An Ed25519 key pair generated on this device, in the formats git hosts and
/// the app's two SSH stacks expect.
struct DeviceSSHKeyPair: Equatable, Sendable {
    /// Unencrypted OpenSSH private key ("-----BEGIN OPENSSH PRIVATE KEY-----").
    /// Accepted by libssh2 (libgit2 transport, OpenSSL backend) and by
    /// Citadel's `Curve25519.Signing.PrivateKey(sshEd25519:)` (Git LFS).
    let privateKeyPEM: String
    /// authorized_keys line: "ssh-ed25519 AAAA… comment".
    let publicKeyLine: String
    /// "SHA256:…" as printed by `ssh-keygen -lf`.
    let fingerprint: String

    /// "SHA256:q8Zt…f2Lk" for compact display.
    var shortFingerprint: String {
        let prefix = "SHA256:"
        guard fingerprint.hasPrefix(prefix) else { return fingerprint }
        let body = fingerprint.dropFirst(prefix.count)
        guard body.count > 10 else { return fingerprint }
        return prefix + body.prefix(4) + "…" + body.suffix(4)
    }
}

enum DeviceSSHKeyError: LocalizedError {
    case keychainWriteFailed(OSStatus)
    case roundTripFailed

    var errorDescription: String? {
        switch self {
        case .keychainWriteFailed(let status):
            return String(localized: "The SSH key could not be saved to the Keychain (\(Int(status))).")
        case .roundTripFailed:
            return String(localized: "The generated SSH key could not be verified. Try again.")
        }
    }
}

/// Generates, stores and loads this device's SSH key. The private key lives
/// only in the Keychain (device-only accessibility); repositories that use it
/// receive their own copy through the normal per-repository credential store.
enum DeviceSSHKeyService {
    static let privateKeyAccount = "deviceSSHKey.ed25519.private"
    static let publicKeyAccount = "deviceSSHKey.ed25519.public"

    /// The stored key pair, if one was generated earlier.
    static func load() -> DeviceSSHKeyPair? {
        guard let pem = KeychainService.load(key: privateKeyAccount),
              let publicLine = KeychainService.load(key: publicKeyAccount),
              let blob = OpenSSHKeyFormat.publicKeyBlob(fromAuthorizedKeysLine: publicLine) else {
            return nil
        }
        return DeviceSSHKeyPair(
            privateKeyPEM: pem,
            publicKeyLine: publicLine,
            fingerprint: OpenSSHKeyFormat.sha256Fingerprint(ofPublicKeyBlob: blob)
        )
    }

    /// Creates a new Ed25519 key, verifies it parses as an OpenSSH key, and
    /// stores it in the Keychain, replacing any previous device key.
    @discardableResult
    static func generate(comment: String) throws -> DeviceSSHKeyPair {
        let key = Curve25519.Signing.PrivateKey()
        let pair = makeKeyPair(privateKey: key, comment: comment)

        // The SSH stacks parse this text, so prove the serialization round-trips
        // through Citadel's OpenSSH parser before handing it out.
        guard parsesAsOpenSSHEd25519(pair.privateKeyPEM, seed: key.rawRepresentation) else {
            throw DeviceSSHKeyError.roundTripFailed
        }

        let privateStatus = KeychainService.save(key: privateKeyAccount, value: pair.privateKeyPEM)
        guard privateStatus == errSecSuccess else { throw DeviceSSHKeyError.keychainWriteFailed(privateStatus) }
        let publicStatus = KeychainService.save(key: publicKeyAccount, value: pair.publicKeyLine)
        guard publicStatus == errSecSuccess else {
            KeychainService.delete(key: privateKeyAccount)
            throw DeviceSSHKeyError.keychainWriteFailed(publicStatus)
        }
        return pair
    }

    /// `true` when Citadel (the Git LFS SSH stack) reads `pem` as an OpenSSH
    /// Ed25519 private key with exactly this 32-byte seed.
    static func parsesAsOpenSSHEd25519(_ pem: String, seed: Data) -> Bool {
        guard let parsed = try? Curve25519.Signing.PrivateKey(sshEd25519: pem) else { return false }
        return parsed.rawRepresentation == seed
    }

    static func makeKeyPair(privateKey: Curve25519.Signing.PrivateKey, comment: String) -> DeviceSSHKeyPair {
        let seed = privateKey.rawRepresentation
        let publicKey = privateKey.publicKey.rawRepresentation
        let blob = OpenSSHKeyFormat.ed25519PublicKeyBlob(publicKey: publicKey)
        return DeviceSSHKeyPair(
            privateKeyPEM: OpenSSHKeyFormat.ed25519PrivateKeyPEM(seed: seed, publicKey: publicKey, comment: comment),
            publicKeyLine: OpenSSHKeyFormat.authorizedKeysLine(publicKeyBlob: blob, comment: comment),
            fingerprint: OpenSSHKeyFormat.sha256Fingerprint(ofPublicKeyBlob: blob)
        )
    }

    /// "cody@iphone" — the comment hosts show next to the key.
    static func defaultComment(username: String) -> String {
        let cleaned = username
            .lowercased()
            .filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" || $0 == "." }
        return "\(cleaned.isEmpty ? "gitsync" : cleaned)@iphone"
    }
}

/// Minimal OpenSSH key serialization (PROTOCOL.key, "openssh-key-v1").
enum OpenSSHKeyFormat {
    static let ed25519KeyType = "ssh-ed25519"

    /// `string "ssh-ed25519" || string publicKey`.
    static func ed25519PublicKeyBlob(publicKey: Data) -> Data {
        var blob = Data()
        appendString(Data(ed25519KeyType.utf8), to: &blob)
        appendString(publicKey, to: &blob)
        return blob
    }

    static func authorizedKeysLine(publicKeyBlob: Data, comment: String) -> String {
        let base = "\(ed25519KeyType) \(publicKeyBlob.base64EncodedString())"
        return comment.isEmpty ? base : "\(base) \(comment)"
    }

    static func publicKeyBlob(fromAuthorizedKeysLine line: String) -> Data? {
        let parts = line.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count >= 2 else { return nil }
        return Data(base64Encoded: String(parts[1]))
    }

    /// "SHA256:" + unpadded base64 of the SHA-256 of the public key blob.
    static func sha256Fingerprint(ofPublicKeyBlob blob: Data) -> String {
        let digest = Data(SHA256.hash(data: blob))
        let base64 = digest.base64EncodedString().trimmingCharacters(in: CharacterSet(charactersIn: "="))
        return "SHA256:\(base64)"
    }

    /// Unencrypted OpenSSH private key for an Ed25519 key.
    ///
    /// - Parameters:
    ///   - seed: 32-byte private seed (CryptoKit `rawRepresentation`).
    ///   - publicKey: 32-byte public key.
    ///   - checkInt: Random check value; injectable for deterministic tests.
    static func ed25519PrivateKeyPEM(
        seed: Data,
        publicKey: Data,
        comment: String,
        checkInt: UInt32 = UInt32.random(in: .min ... .max)
    ) -> String {
        precondition(seed.count == 32 && publicKey.count == 32, "Ed25519 keys are 32 bytes")
        let publicBlob = ed25519PublicKeyBlob(publicKey: publicKey)

        var privateSection = Data()
        appendUInt32(checkInt, to: &privateSection)
        appendUInt32(checkInt, to: &privateSection)
        appendString(Data(ed25519KeyType.utf8), to: &privateSection)
        appendString(publicKey, to: &privateSection)
        // OpenSSH stores the 64-byte "secret key": seed || public key.
        appendString(seed + publicKey, to: &privateSection)
        appendString(Data(comment.utf8), to: &privateSection)
        // Pad to the cipher block size (8 for "none") with 1, 2, 3, …
        var padByte: UInt8 = 1
        while privateSection.count % 8 != 0 {
            privateSection.append(padByte)
            padByte &+= 1
        }

        var body = Data("openssh-key-v1".utf8)
        body.append(0)
        appendString(Data("none".utf8), to: &body)   // cipher
        appendString(Data("none".utf8), to: &body)   // kdf
        appendString(Data(), to: &body)              // kdf options
        appendUInt32(1, to: &body)                   // number of keys
        appendString(publicBlob, to: &body)
        appendString(privateSection, to: &body)

        let base64 = body.base64EncodedString()
        var lines: [String] = []
        var index = base64.startIndex
        while index < base64.endIndex {
            let end = base64.index(index, offsetBy: 70, limitedBy: base64.endIndex) ?? base64.endIndex
            lines.append(String(base64[index..<end]))
            index = end
        }
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n"
            + lines.joined(separator: "\n")
            + "\n-----END OPENSSH PRIVATE KEY-----\n"
    }

    private static func appendUInt32(_ value: UInt32, to data: inout Data) {
        var bigEndian = value.bigEndian
        withUnsafeBytes(of: &bigEndian) { data.append(contentsOf: $0) }
    }

    private static func appendString(_ value: Data, to data: inout Data) {
        appendUInt32(UInt32(value.count), to: &data)
        data.append(value)
    }
}

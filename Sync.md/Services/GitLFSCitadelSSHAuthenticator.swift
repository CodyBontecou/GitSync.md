import Foundation
import NIO
import Crypto
import Citadel

final class GitLFSCitadelSSHAuthenticator: GitLFSSSHAuthenticator {
    private struct AuthenticateResponse: Decodable {
        let href: String
        let header: [String: String]?
        let expiresIn: TimeInterval?
        let expiresAt: String?

        enum CodingKeys: String, CodingKey {
            case href
            case header
            case expiresIn = "expires_in"
            case expiresAt = "expires_at"
        }
    }

    private let hostKeyTrustStore: any GitLFSSSHHostKeyTrustStore
    private let connectTimeout: TimeAmount
    private let maxResponseSize: Int
    private let now: @Sendable () -> Date

    init(
        hostKeyTrustStore: any GitLFSSSHHostKeyTrustStore = GitLFSSSHHostKeyFileTrustStore.default,
        connectTimeout: TimeAmount = .seconds(30),
        maxResponseSize: Int = 64 * 1024,
        now: @escaping @Sendable () -> Date = Date.init
    ) {
        self.hostKeyTrustStore = hostKeyTrustStore
        self.connectTimeout = connectTimeout
        self.maxResponseSize = maxResponseSize
        self.now = now
    }

    func authenticate(request: GitLFSSSHAuthRequest, credentials: GitRemoteCredentials) async throws -> GitLFSAccess {
        guard credentials.method == .sshKey else {
            throw LocalGitError.lfsFailed("Git LFS SSH authentication requires SSH key credentials.")
        }

        let authMethod = try Self.authenticationMethod(username: request.username, credentials: credentials)
        let hostKeyTrustDelegate = GitLFSSSHHostKeyTrustDelegate(
            host: request.host,
            port: request.port,
            trustStore: hostKeyTrustStore
        )
        let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
        defer { try? group.syncShutdownGracefully() }

        let client: SSHClient
        do {
            client = try await SSHClient.connect(
                host: request.host,
                port: request.port,
                authenticationMethod: authMethod,
                hostKeyValidator: .custom(hostKeyTrustDelegate),
                reconnect: .never,
                group: group,
                connectTimeout: connectTimeout
            )
        } catch {
            if let trustError = hostKeyTrustDelegate.failure {
                throw trustError
            }
            if let trustError = error as? GitLFSSSHHostKeyTrustError {
                throw trustError
            }
            throw LocalGitError.lfsFailed("Git LFS SSH authentication failed: \(error.localizedDescription)")
        }

        do {
            var output = try await client.executeCommand(request.command, maxResponseSize: maxResponseSize)
            try? await client.close()
            guard let text = output.readString(length: output.readableBytes) else {
                throw LocalGitError.lfsFailed("Git LFS SSH authentication returned non-UTF8 output.")
            }
            return try Self.parseAccess(from: text, now: now())
        } catch {
            try? await client.close()
            if error is LocalGitError { throw error }
            throw LocalGitError.lfsFailed("Git LFS SSH authentication failed: \(error.localizedDescription)")
        }
    }

    private static func authenticationMethod(username: String, credentials: GitRemoteCredentials) throws -> SSHAuthenticationMethod {
        let privateKey = credentials.privateKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !privateKey.isEmpty else {
            throw LocalGitError.lfsFailed("Missing SSH private key for Git LFS authentication.")
        }

        let passphrase = credentials.passphrase.isEmpty ? nil : Data(credentials.passphrase.utf8)

        if let ed25519 = try? Curve25519.Signing.PrivateKey(sshEd25519: privateKey, decryptionKey: passphrase) {
            return .ed25519(username: username, privateKey: ed25519)
        }

        if let rsa = try? Insecure.RSA.PrivateKey(sshRsa: privateKey, decryptionKey: passphrase) {
            return .rsa(username: username, privateKey: rsa)
        }

        throw LocalGitError.lfsFailed("Unsupported SSH private key format for Git LFS. Use an OpenSSH Ed25519 or RSA private key.")
    }

    private static func parseAccess(from text: String, now: Date) throws -> GitLFSAccess {
        guard let data = text.data(using: .utf8) else {
            throw LocalGitError.lfsFailed("Git LFS SSH authentication returned non-UTF8 output.")
        }

        let decoded: AuthenticateResponse
        do {
            decoded = try JSONDecoder().decode(AuthenticateResponse.self, from: data)
        } catch {
            throw LocalGitError.lfsFailed("Git LFS SSH authentication returned invalid JSON.")
        }

        guard let href = URL(string: decoded.href) else {
            throw LocalGitError.lfsFailed("Git LFS SSH authentication returned an invalid href.")
        }

        let expiresAt: Date?
        if let explicit = decoded.expiresAt {
            expiresAt = ISO8601DateFormatter().date(from: explicit)
        } else if let expiresIn = decoded.expiresIn {
            expiresAt = now.addingTimeInterval(expiresIn)
        } else {
            expiresAt = nil
        }

        return GitLFSAccess(href: href, headers: decoded.header ?? [:], expiresAt: expiresAt)
    }
}

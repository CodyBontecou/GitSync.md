import Foundation

struct PremiumAPIConfiguration: Sendable, Equatable {
    let baseURL: URL?
    init(bundle: Bundle = .main) {
        #if DEBUG
        // Xcode-only relay override for local device testing (set via the
        // shared scheme's launch arguments). Never compiled into Release;
        // non-Xcode launches never register the override, so the app still
        // fails closed by default.
        let override = UserDefaults.standard.string(forKey: "PREMIUM_RELAY_BASE_URL_OVERRIDE")?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        #else
        let override: String? = nil
        #endif
        let raw = override ?? (bundle.object(forInfoDictionaryKey: "PREMIUM_RELAY_BASE_URL") as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        baseURL = raw.flatMap { value in
            guard !value.isEmpty, !value.contains("$("), let url = URL(string: value), url.scheme == "https", url.host != nil else { return nil }
            return url
        }
    }
    init(baseURL: URL?) { self.baseURL = baseURL }
}

protocol PremiumHTTPTransport: Sendable { func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) }
struct URLSessionPremiumHTTPTransport: PremiumHTTPTransport {
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw PremiumAPIError.invalidResponse }
        return (data, http)
    }
}

enum PremiumAPIError: LocalizedError {
    case notConfigured, invalidResponse, rejected(Int), invalidCredential, transport(String)
    var errorDescription: String? {
        switch self {
        case .notConfigured: String(localized: "GitSync Assist relay is not configured.")
        case .invalidResponse: String(localized: "The relay returned an invalid response.")
        case .rejected(let status): String(localized: "The relay rejected the request (HTTP \(status)).")
        case .invalidCredential: String(localized: "The relay authorization is invalid or expired.")
        case .transport(let message): String(localized: "GitSync Assist could not reach the relay: \(message)")
        }
    }
}

protocol PremiumAPIClientProtocol: Sendable {
    func authorizeEntitlement(_ request: PremiumEntitlementUploadRequest) async throws -> PremiumInstallationCredential
    func registerDevice(_ request: PremiumDeviceRegistrationRequest, credential: PremiumInstallationCredential) async throws
    func deleteDevice(_ request: PremiumDeviceDeletionRequest, credential: PremiumInstallationCredential) async throws
}

struct PremiumGitHubLink: Codable, Sendable, Equatable {
    let url: URL
    let expiresAt: Date
}

struct PremiumGitHubInstallationSummary: Codable, Sendable, Equatable, Identifiable {
    let githubInstallationID: Int64
    let linkedAt: Date
    var id: Int64 { githubInstallationID }
}

struct PremiumRepositoryEnrollmentRequest: Codable, Sendable, Equatable {
    let githubInstallationID: Int64
    let repositoryID: Int64
    let branch: String
}

struct PremiumRepositoryEnrollment: Codable, Sendable, Equatable {
    let channel: String
    let githubInstallationID: Int64
    let repositoryID: Int64
    let branch: String
}

protocol PremiumRelayManaging: PremiumAPIClientProtocol {
    func startGitHubLink(credential: PremiumInstallationCredential) async throws -> PremiumGitHubLink
    func githubInstallations(credential: PremiumInstallationCredential) async throws -> [PremiumGitHubInstallationSummary]
    func createEnrollment(_ request: PremiumRepositoryEnrollmentRequest, credential: PremiumInstallationCredential) async throws -> PremiumRepositoryEnrollment
    func deleteEnrollment(channel: String, credential: PremiumInstallationCredential) async throws
    func deleteInstallation(credential: PremiumInstallationCredential) async throws
}

struct PremiumAPIClient: PremiumRelayManaging, Sendable {
    let configuration: PremiumAPIConfiguration
    let transport: any PremiumHTTPTransport
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(configuration: PremiumAPIConfiguration = PremiumAPIConfiguration(), transport: any PremiumHTTPTransport = URLSessionPremiumHTTPTransport()) {
        self.configuration = configuration; self.transport = transport
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601; self.encoder = encoder
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601; self.decoder = decoder
    }

    func authorizeEntitlement(_ request: PremiumEntitlementUploadRequest) async throws -> PremiumInstallationCredential {
        let data = try await send(path: "v1/entitlements", method: "PUT", body: try encoder.encode(request), bearer: nil)
        guard let credential = try? decoder.decode(PremiumInstallationCredential.self, from: data),
              credential.isValid(for: request.installation.installationID) else { throw PremiumAPIError.invalidCredential }
        return credential
    }
    func registerDevice(_ request: PremiumDeviceRegistrationRequest, credential: PremiumInstallationCredential) async throws {
        guard credential.isValid(for: request.installation.installationID) else { throw PremiumAPIError.invalidCredential }
        _ = try await send(path: "v1/devices", method: "PUT", body: try encoder.encode(request), bearer: credential.token)
    }
    func deleteDevice(_ request: PremiumDeviceDeletionRequest, credential: PremiumInstallationCredential) async throws {
        guard credential.isValid(for: request.installationID) else { throw PremiumAPIError.invalidCredential }
        _ = try await send(path: "v1/devices", method: "DELETE", body: try encoder.encode(request), bearer: credential.token)
    }
    func startGitHubLink(credential: PremiumInstallationCredential) async throws -> PremiumGitHubLink {
        guard !credential.token.isEmpty, credential.expiresAt > Date() else { throw PremiumAPIError.invalidCredential }
        let data = try await send(path: "v1/github/link/start", method: "POST", body: Data("{}".utf8), bearer: credential.token)
        return try decode(PremiumGitHubLink.self, from: data)
    }
    func githubInstallations(credential: PremiumInstallationCredential) async throws -> [PremiumGitHubInstallationSummary] {
        guard !credential.token.isEmpty, credential.expiresAt > Date() else { throw PremiumAPIError.invalidCredential }
        let data = try await send(path: "v1/github/link/status", method: "GET", body: nil, bearer: credential.token)
        struct Response: Decodable { let installations: [PremiumGitHubInstallationSummary] }
        return try decode(Response.self, from: data).installations
    }
    func createEnrollment(_ request: PremiumRepositoryEnrollmentRequest, credential: PremiumInstallationCredential) async throws -> PremiumRepositoryEnrollment {
        guard !credential.token.isEmpty, credential.expiresAt > Date() else { throw PremiumAPIError.invalidCredential }
        let data = try await send(path: "v1/enrollments", method: "POST", body: try encoder.encode(request), bearer: credential.token)
        return try decode(PremiumRepositoryEnrollment.self, from: data)
    }
    func deleteEnrollment(channel: String, credential: PremiumInstallationCredential) async throws {
        guard !credential.token.isEmpty, credential.expiresAt > Date() else { throw PremiumAPIError.invalidCredential }
        guard OpaqueAssistIdentifier.isValid(channel) else { throw PremiumAPIError.invalidResponse }
        _ = try await send(path: "v1/enrollments/\(channel)", method: "DELETE", body: nil, bearer: credential.token)
    }
    func deleteInstallation(credential: PremiumInstallationCredential) async throws {
        guard !credential.deletionToken.isEmpty else { throw PremiumAPIError.invalidCredential }
        _ = try await send(path: "v1/installation", method: "DELETE", body: nil, bearer: nil,
            additionalHeaders: ["X-Installation-Deletion-Token": credential.deletionToken])
    }

    private func send(path: String, method: String, body: Data?, bearer: String?, additionalHeaders: [String: String] = [:]) async throws -> Data {
        guard let baseURL = configuration.baseURL else { throw PremiumAPIError.notConfigured }
        var request = URLRequest(url: baseURL.appending(path: path)); request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let bearer { request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        for (name, value) in additionalHeaders { request.setValue(value, forHTTPHeaderField: name) }
        request.httpBody = body
        let data: Data
        let response: HTTPURLResponse
        do {
            (data, response) = try await transport.data(for: request)
        } catch let error as PremiumAPIError {
            throw error
        } catch {
            throw PremiumAPIError.transport(error.localizedDescription)
        }
        guard (200..<300).contains(response.statusCode) else { throw PremiumAPIError.rejected(response.statusCode) }
        return data
    }

    private func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        guard let value = try? decoder.decode(type, from: data) else {
            throw PremiumAPIError.invalidResponse
        }
        return value
    }
}

enum PremiumInstallationIdentity {
    static let defaultsKey = "premium.installation-id.v1"
    static let keychainKey = "premium.installation-id.keychain.v1"

    /// The relay installation identity must survive app reinstall whenever the
    /// device Keychain item survives. Otherwise old deletion capabilities and
    /// an Apple-signed appAccountToken become unreachable under a new UUID.
    static func current(
        defaults: UserDefaults = .standard,
        bundle: Bundle = .main,
        keychainLoad: (String) -> String? = KeychainService.load,
        keychainSave: (String, String) -> Void = { KeychainService.save(key: $0, value: $1) }
    ) -> PremiumInstallation {
        let defaultsID = defaults.string(forKey: defaultsKey).flatMap(UUID.init(uuidString:))
        let keychainID = keychainLoad(keychainKey).flatMap(UUID.init(uuidString:))
        let id: UUID
        if let keychainID {
            // Keychain is authoritative after first persistence because it is
            // the reinstall-durable surface and existing relay credentials are
            // namespaced by this UUID.
            id = keychainID
        } else if let defaultsID {
            id = defaultsID
            keychainSave(keychainKey, id.uuidString)
        } else {
            id = UUID()
            keychainSave(keychainKey, id.uuidString)
        }
        defaults.set(id.uuidString, forKey: defaultsKey)
        return PremiumInstallation(installationID: id, bundleID: bundle.bundleIdentifier ?? "unknown",
            appVersion: bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown")
    }
}

enum APNsDeviceToken {
    static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }
    static var buildEnvironment: APNsEnvironment {
        #if DEBUG
        .sandbox
        #else
        .production
        #endif
    }
}

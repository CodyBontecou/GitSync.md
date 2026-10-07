import Foundation

enum GitHubRepositoryPublicationError: LocalizedError, Sendable, Equatable {
    case invalidInput(String)
    case authenticationRequired
    case forbidden(String)
    case rateLimited(retryAfter: String?)
    case validationFailed(String)
    case repositoryUnavailable
    case remoteMismatch(String)
    case requestFailed(status: Int, message: String)
    case creationOutcomeUnknown(String)
    case unavailable(String)

    var creationOutcomeIsUnknown: Bool {
        if case .creationOutcomeUnknown = self { return true }
        return false
    }

    var errorDescription: String? {
        switch self {
        case .invalidInput(let message), .remoteMismatch(let message), .unavailable(let message):
            return message
        case .authenticationRequired:
            return "GitHub authentication expired or was denied. Sign in again."
        case .forbidden(let message):
            return "GitHub denied access. Check token permissions and account policy. \(message)"
        case .rateLimited:
            return "GitHub rate limited the request. Wait before retrying."
        case .validationFailed(let message):
            return "GitHub rejected this request. \(message)"
        case .repositoryUnavailable:
            return "The GitHub repository is unavailable or this token cannot access it. Its absence has not been confirmed."
        case .requestFailed(let status, let message):
            return "GitHub request failed (HTTP \(status)). \(message)"
        case .creationOutcomeUnknown(let message):
            return "GitHub may have created the repository. Reconcile the original destination before retrying. \(message)"
        }
    }
}

/// Only this fixed API host receives credentials. Returned repository URLs are
/// validated as destination identities and are never used for REST requests.
final class GitHubRepositoryPublisher: GitHubRepositoryPublishing, Sendable {
    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func authenticatedLogin(token: String) async throws -> String {
        try await authenticatedAccount(token: token).login
    }

    func authenticatedAccount(token: String) async throws -> GitHubPublicationAccount {
        let response = try await request(["user"], token: token)
        struct User: Decodable { let id: Int64; let login: String }
        let user: User = try decode(response.data)
        guard user.id > 0 else {
            throw GitHubRepositoryPublicationError.unavailable("GitHub returned an invalid account identity.")
        }
        try Self.validateSegment(user.login, label: "GitHub account")
        return GitHubPublicationAccount(id: user.id, login: user.login)
    }

    func createPrivateRepository(name: String, token: String) async throws -> PublishedGitHubRepository {
        try Self.validateSegment(name, label: "Repository name")
        let owner = try await authenticatedLogin(token: token)
        struct Creation: Encodable {
            let name: String
            let `private` = true
            let auto_init = false
        }
        let body = try JSONEncoder().encode(Creation(name: name))
        let response = try await request(["user", "repos"], token: token, method: "POST", body: body)
        do {
            guard response.status == 201 else {
                throw GitHubRepositoryPublicationError.remoteMismatch("GitHub did not return a repository-creation confirmation.")
            }
            let repository = try decodeRepository(response.data, owner: owner, name: name)
            guard repository.isPrivate else {
                throw GitHubRepositoryPublicationError.remoteMismatch("GitHub did not confirm the requested private visibility.")
            }
            return repository
        } catch {
            throw GitHubRepositoryPublicationError.creationOutcomeUnknown(error.localizedDescription)
        }
    }

    func repository(owner: String, name: String, token: String) async throws -> PublishedGitHubRepository? {
        let response = try await request(["repos", owner, name], token: token)
        return try decodeRepository(response.data, owner: owner, name: name)
    }

    func branchOID(owner: String, name: String, branch: String, token: String) async throws -> String? {
        try Self.validateBranch(branch)
        _ = try await repository(owner: owner, name: name, token: token)
        let branchSegments = branch.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        let response = try await request(
            ["repos", owner, name, "git", "ref", "heads"] + branchSegments,
            token: token,
            allowedStatuses: [404, 409]
        )
        if response.status == 200 {
            let reference: Reference = try decode(response.data)
            return try reference.branchOID(expectedRef: "refs/heads/\(branch)")
        }
        if response.status == 409, !Self.isEmptyRepository(response.data) {
            throw Self.responseError(response)
        }

        // Metadata access alone is insufficient to read Git references. A 404
        // becomes a missing branch only after the reference namespace is readable.
        let references = try await readableReferences(owner: owner, name: name, token: token)
        if let reference = references.first(where: { $0.ref == "refs/heads/\(branch)" }) {
            return try reference.branchOID(expectedRef: "refs/heads/\(branch)")
        }
        return nil
    }

    func hasAnyReferences(owner: String, name: String, token: String) async throws -> Bool {
        _ = try await repository(owner: owner, name: name, token: token)
        return try await !readableReferences(owner: owner, name: name, token: token).isEmpty
    }

    private func readableReferences(owner: String, name: String, token: String) async throws -> [Reference] {
        let response = try await request(
            ["repos", owner, name, "git", "matching-refs", ""],
            token: token,
            allowedStatuses: [409]
        )
        if response.status == 409 {
            guard Self.isEmptyRepository(response.data) else { throw Self.responseError(response) }
            // Recheck after the ref response so a permission change cannot be
            // treated as a newly empty repository solely from stale metadata.
            _ = try await repository(owner: owner, name: name, token: token)
            return []
        }
        let references: [Reference] = try decode(response.data)
        guard references.allSatisfy({ $0.ref.hasPrefix("refs/") && Self.isOID($0.object.sha) }) else {
            throw GitHubRepositoryPublicationError.unavailable("GitHub returned invalid repository references.")
        }
        return references
    }

    private struct Reference: Decodable {
        struct Object: Decodable { let sha: String; let type: String }
        let ref: String
        let object: Object

        func branchOID(expectedRef: String) throws -> String {
            guard ref == expectedRef, object.type == "commit", GitHubRepositoryPublisher.isOID(object.sha) else {
                throw GitHubRepositoryPublicationError.remoteMismatch("GitHub returned a different or invalid branch reference.")
            }
            return object.sha.lowercased()
        }
    }

    private struct RepositoryResponse: Decodable {
        struct Owner: Decodable { let login: String }
        let id: Int64
        let owner: Owner
        let name: String
        let html_url: String
        let clone_url: String
        let `private`: Bool
    }

    private func decodeRepository(_ data: Data, owner: String, name: String) throws -> PublishedGitHubRepository {
        let response: RepositoryResponse = try decode(data)
        guard response.id > 0,
              response.owner.login.caseInsensitiveCompare(owner) == .orderedSame,
              response.name.caseInsensitiveCompare(name) == .orderedSame else {
            throw GitHubRepositoryPublicationError.remoteMismatch("GitHub returned a repository with a different owner or name.")
        }
        try Self.validateSegment(response.owner.login, label: "GitHub account")
        try Self.validateSegment(response.name, label: "Repository name")
        let path = "/\(Self.encodeSegment(response.owner.login))/\(Self.encodeSegment(response.name))"
        guard Self.isCanonicalRepositoryURL(response.html_url, path: path),
              Self.isCanonicalRepositoryURL(response.clone_url, path: path + ".git") else {
            throw GitHubRepositoryPublicationError.remoteMismatch("GitHub returned a noncanonical repository destination.")
        }
        return PublishedGitHubRepository(
            id: response.id,
            owner: response.owner.login,
            name: response.name,
            htmlURL: response.html_url,
            cloneURL: response.clone_url,
            isPrivate: response.private
        )
    }

    private struct Response {
        let status: Int
        let data: Data
        let retryAfter: String?
        let rateLimitRemaining: String?
    }

    private func request(
        _ pathSegments: [String],
        token: String,
        method: String = "GET",
        body: Data? = nil,
        allowedStatuses: Set<Int> = []
    ) async throws -> Response {
        guard !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !token.contains("\r"), !token.contains("\n") else {
            throw GitHubRepositoryPublicationError.authenticationRequired
        }
        for (index, segment) in pathSegments.enumerated() {
            if segment.isEmpty && index == pathSegments.count - 1 { continue }
            try Self.validateSegment(segment, label: "GitHub request path")
        }
        var components = URLComponents()
        components.scheme = "https"
        components.host = "api.github.com"
        components.percentEncodedPath = "/" + pathSegments.map(Self.encodeSegment).joined(separator: "/")
        guard let url = components.url else {
            throw GitHubRepositoryPublicationError.invalidInput("Invalid GitHub request path.")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        request.setValue("GitSync.md", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 30
        request.httpBody = body
        if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }

        let data: Data
        let rawResponse: URLResponse
        do {
            (data, rawResponse) = try await session.data(for: request, delegate: GitHubPublisherNoRedirects.shared)
        } catch {
            if method == "POST" {
                throw GitHubRepositoryPublicationError.creationOutcomeUnknown(error.localizedDescription)
            }
            if error is CancellationError || (error as? URLError)?.code == .cancelled { throw CancellationError() }
            throw GitHubRepositoryPublicationError.unavailable(error.localizedDescription)
        }
        guard let http = rawResponse as? HTTPURLResponse,
              http.url == url else {
            if method == "POST" {
                throw GitHubRepositoryPublicationError.creationOutcomeUnknown("GitHub returned an invalid HTTP response.")
            }
            throw GitHubRepositoryPublicationError.unavailable("GitHub returned an invalid HTTP response.")
        }
        let response = Response(
            status: http.statusCode,
            data: data,
            retryAfter: http.value(forHTTPHeaderField: "Retry-After"),
            rateLimitRemaining: http.value(forHTTPHeaderField: "X-RateLimit-Remaining")
        )
        if (200..<300).contains(response.status) || allowedStatuses.contains(response.status) { return response }
        if method == "POST", !(400..<500).contains(response.status) {
            throw GitHubRepositoryPublicationError.creationOutcomeUnknown("GitHub returned HTTP \(response.status).")
        }
        throw Self.responseError(response)
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T {
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw GitHubRepositoryPublicationError.unavailable("GitHub returned an unreadable response.") }
    }

    private static func responseError(_ response: Response) -> GitHubRepositoryPublicationError {
        let message = responseMessage(response.data)
        switch response.status {
        case 401: return .authenticationRequired
        case 403 where response.rateLimitRemaining == "0" || response.retryAfter != nil || message.lowercased().contains("rate limit"):
            return .rateLimited(retryAfter: response.retryAfter)
        case 403: return .forbidden(message)
        case 404: return .repositoryUnavailable
        case 422: return .validationFailed(message)
        case 429: return .rateLimited(retryAfter: response.retryAfter)
        default: return .requestFailed(status: response.status, message: message)
        }
    }

    private static func responseMessage(_ data: Data) -> String {
        guard let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let message = body["message"] as? String else { return "No additional details." }
        let details = (body["errors"] as? [[String: Any]] ?? []).compactMap { $0["message"] as? String }
        return String(([message] + details).joined(separator: " ").prefix(500))
    }

    private static func isEmptyRepository(_ data: Data) -> Bool {
        responseMessage(data).trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "git repository is empty."
    }

    private static func isOID(_ value: String) -> Bool {
        [40, 64].contains(value.utf8.count) && value.utf8.allSatisfy {
            (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0)
        }
    }

    private static func validateSegment(_ value: String, label: String) throws {
        guard !value.isEmpty, value != ".", value != "..",
              !value.contains("/"), !value.contains("\\"),
              value.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }) else {
            throw GitHubRepositoryPublicationError.invalidInput("\(label) contains an invalid path component.")
        }
    }

    private static func validateBranch(_ branch: String) throws {
        guard !branch.isEmpty else { throw GitHubRepositoryPublicationError.invalidInput("Choose a branch.") }
        for segment in branch.split(separator: "/", omittingEmptySubsequences: false) {
            try validateSegment(String(segment), label: "Branch")
        }
    }

    private static func encodeSegment(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"))!
    }

    private static func isCanonicalRepositoryURL(_ value: String, path: String) -> Bool {
        guard let components = URLComponents(string: value) else { return false }
        return components.scheme?.lowercased() == "https"
            && components.host?.lowercased() == "github.com"
            && components.user == nil && components.password == nil && components.port == nil
            && components.query == nil && components.fragment == nil
            && components.percentEncodedPath.caseInsensitiveCompare(path) == .orderedSame
    }
}

private final class GitHubPublisherNoRedirects: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    static let shared = GitHubPublisherNoRedirects()

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

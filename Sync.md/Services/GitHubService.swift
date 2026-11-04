import Foundation

// MARK: - API Models

struct GitRef: Codable {
    struct Object: Codable {
        let sha: String
    }
    let object: Object
}

struct GitCommit: Codable {
    struct Tree: Codable {
        let sha: String
    }
    struct Parent: Codable {
        let sha: String
    }
    let sha: String
    let tree: Tree
    let parents: [Parent]
}

struct GitTreeResponse: Codable {
    let sha: String
    let tree: [GitTreeEntry]
}

struct GitTreeEntry: Codable {
    let path: String
    let mode: String
    let type: String
    let sha: String
    let size: Int?
}

struct GitBlob: Codable {
    let sha: String
    let content: String
    let encoding: String
    let size: Int
}

struct GitBlobCreate: Codable {
    let content: String
    let encoding: String
}

struct GitBlobResponse: Codable {
    let sha: String
}

struct GitTreeCreate: Codable {
    struct Entry: Codable {
        let path: String
        let mode: String
        let type: String
        let sha: String?

        enum CodingKeys: String, CodingKey {
            case path, mode, type, sha
        }

        /// Always encode `sha`, even when nil.
        /// GitHub requires explicit `"sha": null` to delete a file from the tree.
        func encode(to encoder: Encoder) throws {
            var container = encoder.container(keyedBy: CodingKeys.self)
            try container.encode(path, forKey: .path)
            try container.encode(mode, forKey: .mode)
            try container.encode(type, forKey: .type)
            try container.encode(sha, forKey: .sha)   // encodes null when sha is nil
        }
    }
    let base_tree: String?
    let tree: [Entry]
}

struct GitTreeCreateResponse: Codable {
    let sha: String
}

struct GitCommitCreate: Codable {
    struct Author: Codable {
        let name: String
        let email: String
        let date: String
    }
    let message: String
    let tree: String
    let parents: [String]
    let author: Author
}

struct GitCommitCreateResponse: Codable {
    let sha: String
}

struct GitRefUpdate: Codable {
    let sha: String
    let force: Bool
}

struct GitCompareResponse: Codable {
    struct File: Codable {
        let filename: String
        let status: String  // "added", "removed", "modified", "renamed"
        let sha: String?
        let previous_filename: String?
    }
    let files: [File]?
    let ahead_by: Int
    let behind_by: Int
}

// MARK: - Service Errors

enum GitHubError: LocalizedError {
    case invalidURL
    case unauthorized
    case forbidden
    case notFound(String)       // carries the raw GitHub response for debugging
    case rateLimited
    case conflict(String)
    case apiError(Int, String)
    case decodingError(String)
    case noChanges

    var errorDescription: String? {
        switch self {
        case .invalidURL: return String(localized: "Invalid repository URL")
        case .unauthorized: return String(localized: "Invalid or expired token. Sign out and sign in again.")
        case .forbidden: return String(localized: "GitHub access to this repository was denied.")
        case .notFound(let detail): return String(localized: "Not found: \(detail)")
        case .rateLimited: return String(localized: "GitHub API rate limit exceeded. Try again later.")
        case .conflict(let msg): return String(localized: "Conflict: \(msg)")
        case .apiError(let code, let msg): return String(localized: "GitHub API error (\(code)): \(msg)")
        case .decodingError(let msg): return String(localized: "Failed to parse response: \(msg)")
        case .noChanges: return String(localized: "No changes to push")
        }
    }
}

// MARK: - File Change

struct FileChange: Sendable {
    nonisolated enum ChangeType: Sendable, Equatable {
        case added, modified, deleted
    }
    let path: String
    let type: ChangeType
    let content: Data?  // nil for deletions
}

// MARK: - Clone / Pull Results

struct CloneResult {
    let commitSHA: String
    let treeSHA: String
    let files: [(path: String, content: Data)]
    let blobSHAs: [String: String]  // path -> blob SHA
}

struct PullResult {
    let newCommitSHA: String
    let newTreeSHA: String
    let modifiedFiles: [(path: String, content: Data)]
    let deletedFiles: [String]
    let newBlobSHAs: [String: String]
}

// MARK: - GitHub Service

final class GitHubService: Sendable {
    let pat: String
    let owner: String
    let repo: String

    private let baseURL = "https://api.github.com"
    private let session: URLSession
    private let decoder = JSONDecoder()
    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = .sortedKeys
        return e
    }()

    init(pat: String, owner: String, repo: String) {
        self.pat = pat
        self.owner = owner
        self.repo = repo
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 300
        self.session = URLSession(configuration: config)
    }

    /// Parse only an exact canonical GitHub owner/repository target.
    static func parseRepoURL(_ urlString: String) -> (owner: String, repo: String)? {
        guard let fullName = GitRemoteURL.parse(urlString)?.canonicalGitHubFullName else { return nil }
        let parts = fullName.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return nil }
        return (String(parts[0]), String(parts[1]))
    }

    // MARK: - Low-Level API

    private func request(_ path: String, method: String = "GET", body: Data? = nil) async throws -> Data {
        guard let url = URL(string: "\(baseURL)\(path)") else {
            throw GitHubError.invalidURL
        }

        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("Bearer \(pat)", forHTTPHeaderField: "Authorization")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        if let body = body {
            req.httpBody = body
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }

        let (data, response) = try await session.data(for: req)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw GitHubError.apiError(0, String(localized: "Invalid response"))
        }

        switch httpResponse.statusCode {
        case 200...299:
            return data
        case 401, 403:
            throw GitHubError.unauthorized
        case 404:
            let detail = String(data: data, encoding: .utf8) ?? "No details"
            throw GitHubError.notFound(detail)
        case 409:
            let msg = String(data: data, encoding: .utf8) ?? "Unknown conflict"
            throw GitHubError.conflict(msg)
        case 422:
            let msg = String(data: data, encoding: .utf8) ?? "Validation failed"
            throw GitHubError.apiError(422, msg)
        case 429:
            throw GitHubError.rateLimited
        default:
            let msg = String(data: data, encoding: .utf8) ?? "Unknown error"
            throw GitHubError.apiError(httpResponse.statusCode, msg)
        }
    }

    func getRef(branch: String) async throws -> String {
        let data = try await request("/repos/\(owner)/\(repo)/git/ref/heads/\(branch)")
        let ref = try decoder.decode(GitRef.self, from: data)
        return ref.object.sha
    }

    func getCommit(sha: String) async throws -> GitCommit {
        let data = try await request("/repos/\(owner)/\(repo)/git/commits/\(sha)")
        return try decoder.decode(GitCommit.self, from: data)
    }

    func getTree(sha: String, recursive: Bool = true) async throws -> GitTreeResponse {
        let path = "/repos/\(owner)/\(repo)/git/trees/\(sha)" + (recursive ? "?recursive=1" : "")
        let data = try await request(path)
        return try decoder.decode(GitTreeResponse.self, from: data)
    }

    func getBlob(sha: String) async throws -> Data {
        let data = try await request("/repos/\(owner)/\(repo)/git/blobs/\(sha)")
        let blob = try decoder.decode(GitBlob.self, from: data)
        guard blob.encoding == "base64" else {
            throw GitHubError.decodingError(String(localized: "Unexpected blob encoding: \(blob.encoding)"))
        }
        let cleaned = blob.content.replacingOccurrences(of: "\n", with: "")
        guard let decoded = Data(base64Encoded: cleaned) else {
            throw GitHubError.decodingError(String(localized: "Failed to decode base64 blob"))
        }
        return decoded
    }

    func createBlob(content: Data) async throws -> String {
        let body = GitBlobCreate(
            content: content.base64EncodedString(),
            encoding: "base64"
        )
        let bodyData = try encoder.encode(body)
        let data = try await request("/repos/\(owner)/\(repo)/git/blobs", method: "POST", body: bodyData)
        let response = try decoder.decode(GitBlobResponse.self, from: data)
        return response.sha
    }

    func createTree(baseTree: String?, entries: [GitTreeCreate.Entry]) async throws -> String {
        let body = GitTreeCreate(base_tree: baseTree, tree: entries)
        let bodyData = try encoder.encode(body)
        let data = try await request("/repos/\(owner)/\(repo)/git/trees", method: "POST", body: bodyData)
        let response = try decoder.decode(GitTreeCreateResponse.self, from: data)
        return response.sha
    }

    func createCommit(message: String, treeSHA: String, parents: [String], authorName: String, authorEmail: String) async throws -> String {
        let isoFormatter = ISO8601DateFormatter()
        let body = GitCommitCreate(
            message: message,
            tree: treeSHA,
            parents: parents,
            author: GitCommitCreate.Author(
                name: authorName,
                email: authorEmail,
                date: isoFormatter.string(from: Date())
            )
        )
        let bodyData = try encoder.encode(body)
        let data = try await request("/repos/\(owner)/\(repo)/git/commits", method: "POST", body: bodyData)
        let response = try decoder.decode(GitCommitCreateResponse.self, from: data)
        return response.sha
    }

    func updateRef(branch: String, sha: String, force: Bool = false) async throws {
        let body = GitRefUpdate(sha: sha, force: force)
        let bodyData = try encoder.encode(body)
        _ = try await request("/repos/\(owner)/\(repo)/git/refs/heads/\(branch)", method: "PATCH", body: bodyData)
    }

    func compare(base: String, head: String) async throws -> GitCompareResponse {
        let data = try await request("/repos/\(owner)/\(repo)/compare/\(base)...\(head)")
        return try decoder.decode(GitCompareResponse.self, from: data)
    }

    // MARK: - Get Default Branch

    struct RepoInfo: Codable {
        let id: Int64
        let full_name: String
        let default_branch: String
    }

    func getDefaultBranch() async throws -> String {
        let data = try await request("/repos/\(owner)/\(repo)")
        let info = try decoder.decode(RepoInfo.self, from: data)
        return info.default_branch
    }

    // MARK: - High-Level Operations

    func cloneRepository(branch: String) async throws -> CloneResult {
        // 1. Get latest commit SHA
        let commitSHA = try await getRef(branch: branch)

        // 2. Get commit to find tree SHA
        let commit = try await getCommit(sha: commitSHA)
        let treeSHA = commit.tree.sha

        // 3. Get full tree recursively
        let treeResponse = try await getTree(sha: treeSHA)

        // 4. Filter to blobs only (files, not trees/dirs)
        let blobs = treeResponse.tree.filter { $0.type == "blob" }

        // 5. Download all blobs concurrently
        var files: [(path: String, content: Data)] = []
        var blobSHAs: [String: String] = [:]

        try await withThrowingTaskGroup(of: (String, Data, String).self) { group in
            for blob in blobs {
                let blobCopy = blob
                group.addTask {
                    let content = try await self.getBlob(sha: blobCopy.sha)
                    return (blobCopy.path, content, blobCopy.sha)
                }
            }
            for try await (path, content, sha) in group {
                files.append((path, content))
                blobSHAs[path] = sha
            }
        }

        return CloneResult(
            commitSHA: commitSHA,
            treeSHA: treeSHA,
            files: files,
            blobSHAs: blobSHAs
        )
    }

    func pull(branch: String, currentCommitSHA: String) async throws -> PullResult? {
        // 1. Get latest commit
        let latestSHA = try await getRef(branch: branch)

        // If no changes, return nil
        if latestSHA == currentCommitSHA { return nil }

        // 2. Get the new commit details
        let commit = try await getCommit(sha: latestSHA)
        let newTreeSHA = commit.tree.sha

        // 3. Compare commits to find changes
        let comparison = try await compare(base: currentCommitSHA, head: latestSHA)

        var modifiedFiles: [(path: String, content: Data)] = []
        var deletedFiles: [String] = []
        var newBlobSHAs: [String: String] = [:]

        guard let changedFiles = comparison.files else {
            return PullResult(
                newCommitSHA: latestSHA,
                newTreeSHA: newTreeSHA,
                modifiedFiles: [],
                deletedFiles: [],
                newBlobSHAs: [:]
            )
        }

        // 4. Get the full tree to find blob SHAs
        let treeResponse = try await getTree(sha: newTreeSHA)
        let blobMap = Dictionary(treeResponse.tree.map { ($0.path, $0.sha) }, uniquingKeysWith: { _, new in new })

        // 5. Download changed files
        try await withThrowingTaskGroup(of: (String, Data?, String?).self) { group in
            for file in changedFiles {
                let fileCopy = file
                group.addTask {
                    if fileCopy.status == "removed" {
                        return (fileCopy.filename, nil, nil)
                    } else {
                        // Get the blob SHA from the tree
                        guard let blobSHA = blobMap[fileCopy.filename] else {
                            return (fileCopy.filename, nil, nil)
                        }
                        let content = try await self.getBlob(sha: blobSHA)
                        return (fileCopy.filename, content, blobSHA)
                    }
                }
            }
            for try await (path, content, sha) in group {
                if let content = content, let sha = sha {
                    modifiedFiles.append((path, content))
                    newBlobSHAs[path] = sha
                } else {
                    deletedFiles.append(path)
                }
            }
        }

        return PullResult(
            newCommitSHA: latestSHA,
            newTreeSHA: newTreeSHA,
            modifiedFiles: modifiedFiles,
            deletedFiles: deletedFiles,
            newBlobSHAs: newBlobSHAs
        )
    }

    func push(
        branch: String,
        currentCommitSHA: String,
        currentTreeSHA: String,
        changes: [FileChange],
        message: String,
        authorName: String,
        authorEmail: String
    ) async throws -> (commitSHA: String, treeSHA: String, newBlobSHAs: [String: String]) {
        guard !changes.isEmpty else { throw GitHubError.noChanges }

        // Validate stored state before making API calls
        guard !currentCommitSHA.isEmpty, !currentTreeSHA.isEmpty else {
            throw GitHubError.apiError(0, String(localized: "Repository not synced. Please pull or re-clone first."))
        }

        // Check that the remote hasn't diverged (fast-forward check)
        let latestRemoteSHA: String
        do {
            latestRemoteSHA = try await getRef(branch: branch)
        } catch {
            throw GitHubError.apiError(0, String(localized: "Failed to read remote branch '\(branch)': \(error.localizedDescription)"))
        }

        if latestRemoteSHA != currentCommitSHA {
            throw GitHubError.conflict(
                "Remote branch '\(branch)' has new commits. Pull first, then push."
            )
        }

        var newBlobSHAs: [String: String] = [:]

        // 1. Create blobs for added/modified files
        var treeEntries: [GitTreeCreate.Entry] = []

        try await withThrowingTaskGroup(of: (String, String?, FileChange.ChangeType).self) { group in
            for change in changes {
                let changeCopy = change
                group.addTask {
                    if changeCopy.type == .deleted {
                        return (changeCopy.path, nil, .deleted)
                    } else {
                        guard let content = changeCopy.content else {
                            return (changeCopy.path, nil, changeCopy.type)
                        }
                        let blobSHA = try await self.createBlob(content: content)
                        return (changeCopy.path, blobSHA, changeCopy.type)
                    }
                }
            }
            for try await (path, blobSHA, changeType) in group {
                if changeType == .deleted {
                    treeEntries.append(GitTreeCreate.Entry(
                        path: path,
                        mode: "100644",
                        type: "blob",
                        sha: nil
                    ))
                } else if let sha = blobSHA {
                    treeEntries.append(GitTreeCreate.Entry(
                        path: path,
                        mode: "100644",
                        type: "blob",
                        sha: sha
                    ))
                    newBlobSHAs[path] = sha
                }
            }
        }

        // 2. Create new tree
        let newTreeSHA: String
        do {
            newTreeSHA = try await createTree(baseTree: currentTreeSHA, entries: treeEntries)
        } catch {
            throw GitHubError.apiError(0, String(localized: "Failed to create tree: \(error.localizedDescription)"))
        }

        // 3. Create commit
        let newCommitSHA: String
        do {
            newCommitSHA = try await createCommit(
                message: message,
                treeSHA: newTreeSHA,
                parents: [currentCommitSHA],
                authorName: authorName,
                authorEmail: authorEmail
            )
        } catch {
            throw GitHubError.apiError(0, String(localized: "Failed to create commit: \(error.localizedDescription)"))
        }

        // 4. Update ref
        do {
            try await updateRef(branch: branch, sha: newCommitSHA)
        } catch {
            throw GitHubError.apiError(0, String(localized: "Failed to update ref — remote may have advanced. Pull and retry. (\(error.localizedDescription))"))
        }

        return (newCommitSHA, newTreeSHA, newBlobSHAs)
    }
}

// MARK: - User-Level API (static — no owner/repo needed)

struct GitHubUser: Codable {
    let login: String
    let name: String?
    let email: String?
    let avatar_url: String?
}

struct GitHubRepo: Codable, Identifiable {
    let id: Int64
    let name: String
    let fullName: String
    let description: String?
    let isPrivate: Bool
    let htmlURL: String
    let defaultBranch: String
    let updatedAt: String?
    let owner: Owner
    /// Repository size reported by GitHub, in kilobytes (approximate; it
    /// counts packed history, not the checked-out working tree).
    var size: Int? = nil
    /// Primary linguist language. Markdown-only note vaults usually report
    /// `nil` because linguist does not count prose as a language.
    var language: String? = nil

    struct Owner: Codable {
        let login: String
    }

    enum CodingKeys: String, CodingKey {
        case id, name, description, owner, size, language
        case fullName = "full_name"
        case isPrivate = "private"
        case htmlURL = "html_url"
        case defaultBranch = "default_branch"
        case updatedAt = "updated_at"
    }

    /// Approximate size in bytes, when GitHub reported one.
    var sizeInBytes: Int64? {
        guard let size, size > 0 else { return nil }
        return Int64(size) * 1024
    }

    /// Name-based hint that this repository holds notes rather than code.
    var hasNotesLikeName: Bool {
        let lowered = name.lowercased()
        return Self.notesNameHints.contains { lowered.contains($0) }
    }

    /// Prose-only repositories report no language, or Markdown.
    var hasProseLanguage: Bool {
        guard let language else { return true }
        return language.caseInsensitiveCompare("Markdown") == .orderedSame
    }

    static let notesNameHints = ["vault", "notes", "obsidian", "journal", "zettel", "wiki", "brain", "garden", "notebook", "diary"]
}

extension GitHubService {

    /// Fetch the authenticated user's profile
    static func fetchUser(token: String) async throws -> GitHubUser {
        var req = URLRequest(url: URL(string: "https://api.github.com/user")!)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw GitHubError.unauthorized
        }
        return try JSONDecoder().decode(GitHubUser.self, from: data)
    }

    /// Fetch all authenticated repositories (100 per page) from `page` onward.
    static func fetchRepos(token: String, page: Int = 1) async throws -> [GitHubRepo] {
        var currentPage = max(1, page)
        var result: [GitHubRepo] = []
        while true {
            var components = URLComponents(string: "https://api.github.com/user/repos")!
            components.queryItems = [
                URLQueryItem(name: "sort", value: "updated"),
                URLQueryItem(name: "direction", value: "desc"),
                URLQueryItem(name: "per_page", value: "100"),
                URLQueryItem(name: "page", value: "\(currentPage)"),
                URLQueryItem(name: "affiliation", value: "owner,collaborator,organization_member"),
            ]
            var req = URLRequest(url: components.url!)
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
            let (data, response) = try await URLSession.shared.data(for: req)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                throw GitHubError.unauthorized
            }
            let repositories = try JSONDecoder().decode([GitHubRepo].self, from: data)
            result.append(contentsOf: repositories)
            guard repositories.count == 100 else { return result }
            currentPage += 1
        }
    }

    /// Fetch the repositories the authenticated user has starred (up to 1,000).
    static func fetchStarredRepos(token: String) async throws -> [GitHubRepo] {
        var currentPage = 1
        var result: [GitHubRepo] = []
        while currentPage <= 10 {
            var components = URLComponents(string: "https://api.github.com/user/starred")!
            components.queryItems = [
                URLQueryItem(name: "sort", value: "updated"),
                URLQueryItem(name: "direction", value: "desc"),
                URLQueryItem(name: "per_page", value: "100"),
                URLQueryItem(name: "page", value: "\(currentPage)"),
            ]
            var req = URLRequest(url: components.url!)
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
            let (data, response) = try await URLSession.shared.data(for: req)
            guard let http = response as? HTTPURLResponse else {
                throw GitHubError.apiError(0, String(localized: "Invalid response"))
            }
            guard http.statusCode == 200 else {
                throw repositoryLookupError(
                    statusCode: http.statusCode,
                    headers: http.allHeaderFields.reduce(into: [String: String]()) { $0["\($1.key)"] = "\($1.value)" },
                    body: data
                )
            }
            let repositories = try JSONDecoder().decode([GitHubRepo].self, from: data)
            result.append(contentsOf: repositories)
            guard repositories.count == 100 else { return result }
            currentPage += 1
        }
        return result
    }

    /// `true` when `owner/name` already exists and is visible to `token`,
    /// `false` when GitHub answers 404. Other failures throw.
    static func repositoryExists(owner: String, name: String, token: String) async throws -> Bool {
        let status = try await statusCode(pathSegments: ["repos", owner, name], token: token)
        switch status {
        case 200...299: return true
        case 404: return false
        case 401: throw GitHubError.unauthorized
        case 403, 429: throw GitHubError.rateLimited
        default: throw GitHubError.apiError(status, String(localized: "Could not check the repository name."))
        }
    }

    /// `true` when the repository's default branch has a top-level `.obsidian`
    /// folder (an Obsidian vault). 404 means no such folder.
    static func hasObsidianFolder(owner: String, name: String, token: String) async throws -> Bool {
        let status = try await statusCode(pathSegments: ["repos", owner, name, "contents", ".obsidian"], token: token)
        switch status {
        case 200...299: return true
        case 404: return false
        case 401: throw GitHubError.unauthorized
        case 403, 429: throw GitHubError.rateLimited
        default: throw GitHubError.apiError(status, String(localized: "Could not read the repository contents."))
        }
    }

    /// Issues an authenticated GET and returns only the HTTP status code.
    private static func statusCode(pathSegments: [String], token: String) async throws -> Int {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "api.github.com"
        // Percent-encode each segment so names cannot inject path components.
        let allowed = CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))
        components.percentEncodedPath = "/" + pathSegments
            .map { $0.addingPercentEncoding(withAllowedCharacters: allowed) ?? $0 }
            .joined(separator: "/")
        guard let url = components.url else { throw GitHubError.invalidURL }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        let (_, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse else {
            throw GitHubError.apiError(0, String(localized: "Invalid response"))
        }
        return http.statusCode
    }

    static func repositoryLookupError(
        statusCode: Int,
        headers: [String: String],
        body: Data
    ) -> GitHubError {
        let normalizedHeaders = Dictionary(uniqueKeysWithValues: headers.map {
            ($0.key.lowercased(), $0.value.trimmingCharacters(in: .whitespacesAndNewlines))
        })
        let bodyMessage = String(data: body, encoding: .utf8) ?? ""
        let normalizedBody = bodyMessage.lowercased()
        if statusCode == 429
            || (statusCode == 403 && normalizedHeaders["x-ratelimit-remaining"] == "0")
            || (statusCode == 403 && normalizedHeaders["retry-after"]?.isEmpty == false)
            || (statusCode == 403 && normalizedBody.contains("rate limit")) {
            return .rateLimited
        }
        switch statusCode {
        case 401: return .unauthorized
        case 403: return .forbidden
        case 404: return .notFound(bodyMessage.isEmpty ? "No details" : bodyMessage)
        case 409: return .conflict(bodyMessage.isEmpty ? "Conflict" : bodyMessage)
        default: return .apiError(statusCode, bodyMessage.isEmpty ? "Unknown error" : bodyMessage)
        }
    }

    /// Fetch the user's primary email (if not public)
    static func fetchPrimaryEmail(token: String) async throws -> String? {
        var req = URLRequest(url: URL(string: "https://api.github.com/user/emails")!)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            return nil
        }
        struct Email: Codable {
            let email: String
            let primary: Bool
        }
        let emails = try JSONDecoder().decode([Email].self, from: data)
        return emails.first(where: { $0.primary })?.email ?? emails.first?.email
    }
}

// MARK: - Pre-clone check

/// What GitSync.md learns about a GitHub repository before cloning it, from
/// metadata only (repository, tree and `.gitattributes`); no note contents
/// are downloaded.
struct GitHubRepoPreflight: Equatable, Sendable {
    /// `nil` when GitHub did not report permissions for this token.
    var canPush: Bool?
    var defaultBranch: String
    var sizeInBytes: Int64?
    /// Files on the default branch; `nil` for an empty repository.
    var fileCount: Int?
    /// GitHub truncated the tree listing, so `fileCount` is a lower bound.
    var fileCountIsPartial: Bool = false
    /// Files matching a `filter=lfs` rule in `.gitattributes`.
    var lfsFileCount: Int = 0
    var hasObsidianFolder: Bool = false
    var hasObsidianGitPlugin: Bool = false
    var isEmpty: Bool = false

    /// The check found nothing that would stop a normal two-way sync.
    var isReady: Bool { canPush != false }
}

extension GitHubService {

    private struct PreflightRepoResponse: Decodable {
        struct Permissions: Decodable {
            let push: Bool?
            let maintain: Bool?
            let admin: Bool?
        }
        let defaultBranch: String?
        let size: Int?
        let permissions: Permissions?

        enum CodingKeys: String, CodingKey {
            case size, permissions
            case defaultBranch = "default_branch"
        }
    }

    private struct PreflightTreeResponse: Decodable {
        let tree: [GitTreeEntry]
        let truncated: Bool?
    }

    /// Runs the pre-clone check for `owner/name`. `onRepository` reports the
    /// first answer (access, branch, size) before the tree is listed.
    static func preflight(
        owner: String,
        name: String,
        token: String,
        onRepository: (@MainActor @Sendable (GitHubRepoPreflight) -> Void)? = nil
    ) async throws -> GitHubRepoPreflight {
        let (repoData, repoStatus) = try await preflightGET(["repos", owner, name], token: token)
        try throwIfPreflightFailed(status: repoStatus, data: repoData)
        let repoInfo = try JSONDecoder().decode(PreflightRepoResponse.self, from: repoData)
        let branch = repoInfo.defaultBranch ?? "main"
        let permissions = repoInfo.permissions
        var result = GitHubRepoPreflight(
            canPush: permissions.map { ($0.push ?? false) || ($0.maintain ?? false) || ($0.admin ?? false) },
            defaultBranch: branch,
            sizeInBytes: repoInfo.size.flatMap { $0 > 0 ? Int64($0) * 1024 : nil }
        )
        if let onRepository { await onRepository(result) }

        let (treeData, treeStatus) = try await preflightGET(
            ["repos", owner, name, "git", "trees", branch],
            query: [URLQueryItem(name: "recursive", value: "1")],
            token: token
        )
        // 409 / 404 on the tree: an empty repository with no commits yet.
        if treeStatus == 409 || treeStatus == 404 {
            result.isEmpty = true
            result.fileCount = 0
            return result
        }
        try throwIfPreflightFailed(status: treeStatus, data: treeData)
        let tree = try JSONDecoder().decode(PreflightTreeResponse.self, from: treeData)

        var patterns: [String] = []
        if let attributes = tree.tree.first(where: { $0.path == ".gitattributes" && $0.type == "blob" }) {
            let (blobData, blobStatus) = try await preflightGET(
                ["repos", owner, name, "git", "blobs", attributes.sha], token: token
            )
            if (200...299).contains(blobStatus),
               let blob = try? JSONDecoder().decode(GitBlob.self, from: blobData),
               blob.encoding == "base64",
               let decoded = Data(base64Encoded: blob.content, options: .ignoreUnknownCharacters),
               let text = String(data: decoded, encoding: .utf8) {
                patterns = lfsPatterns(fromGitAttributes: text)
            }
        }
        applyTree(tree.tree, truncated: tree.truncated ?? false, lfsPatterns: patterns, to: &result)
        return result
    }

    /// Fills file count, LFS and Obsidian facts from a recursive tree listing.
    static func applyTree(
        _ entries: [GitTreeEntry],
        truncated: Bool,
        lfsPatterns: [String],
        to result: inout GitHubRepoPreflight
    ) {
        let blobs = entries.filter { $0.type == "blob" }
        result.fileCount = blobs.count
        result.fileCountIsPartial = truncated
        result.isEmpty = entries.isEmpty
        result.hasObsidianFolder = entries.contains { $0.path == ".obsidian" || $0.path.hasPrefix(".obsidian/") }
        result.hasObsidianGitPlugin = entries.contains {
            $0.path == ".obsidian/plugins/obsidian-git" || $0.path.hasPrefix(".obsidian/plugins/obsidian-git/")
        }
        guard !lfsPatterns.isEmpty else {
            result.lfsFileCount = 0
            return
        }
        result.lfsFileCount = blobs.filter { matchesLFSPattern(path: $0.path, patterns: lfsPatterns) }.count
    }

    /// Patterns from `.gitattributes` lines that set `filter=lfs`.
    static func lfsPatterns(fromGitAttributes text: String) -> [String] {
        text.split(whereSeparator: \.isNewline).compactMap { rawLine in
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty, !line.hasPrefix("#") else { return nil }
            let fields = line.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
            guard let pattern = fields.first, fields.dropFirst().contains("filter=lfs") else { return nil }
            return pattern
        }
    }

    /// gitattributes-style match: a pattern without a slash matches the file
    /// name at any depth; one with a slash matches the path from the root.
    static func matchesLFSPattern(path: String, patterns: [String]) -> Bool {
        let fileName = (path as NSString).lastPathComponent
        return patterns.contains { rawPattern in
            var pattern = rawPattern
            if pattern.hasPrefix("/") { pattern.removeFirst() }
            if pattern.hasSuffix("/**") {
                let prefix = String(pattern.dropLast(3))
                return path.hasPrefix(prefix + "/")
            }
            if pattern.contains("/") {
                return fnmatch(pattern, path, 0) == 0
            }
            return fnmatch(pattern, fileName, 0) == 0
        }
    }

    private static func throwIfPreflightFailed(status: Int, data: Data) throws {
        guard !(200...299).contains(status) else { return }
        throw repositoryLookupError(statusCode: status, headers: [:], body: data)
    }

    private static func preflightGET(
        _ pathSegments: [String],
        query: [URLQueryItem] = [],
        token: String
    ) async throws -> (Data, Int) {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "api.github.com"
        let allowed = CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))
        components.percentEncodedPath = "/" + pathSegments
            .map { $0.addingPercentEncoding(withAllowedCharacters: allowed) ?? $0 }
            .joined(separator: "/")
        if !query.isEmpty { components.queryItems = query }
        guard let url = components.url else { throw GitHubError.invalidURL }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse else {
            throw GitHubError.apiError(0, String(localized: "Invalid response"))
        }
        return (data, http.statusCode)
    }
}

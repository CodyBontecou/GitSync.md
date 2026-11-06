import Foundation
import Observation

/// The free vault check that runs before the paywall.
///
/// Confirms GitSync.md can clone and push a repository without writing
/// anything to disk: an `ls-remote`-style connection with the chosen
/// credentials, then (for GitHub) permission, size, file count, Git LFS and
/// Obsidian facts read from the API. Only metadata is fetched.
@MainActor
@Observable
final class VaultPreflight: Identifiable {
    struct Target {
        /// Vault name shown on the card ("notes").
        let name: String
        /// Remote URL as the user chose it.
        let repoURL: String
        /// Branch the vault will track, when already chosen.
        var branch: String?
        /// `GitRemoteCredentials.transportPayload` for the connection test.
        let transportPayload: String
        /// GitHub token when the remote is GitHub and the user signed in;
        /// enables the permission, size and contents checks.
        var gitHubToken: String?
    }

    enum Kind: CaseIterable, Identifiable {
        case access, branch, size, lfs, obsidian
        var id: Self { self }
    }

    enum Status: Equatable {
        case pending, running, passed, warning, failed, skipped
    }

    struct Check: Identifiable, Equatable {
        let kind: Kind
        var status: Status = .pending
        var detail: String? = nil
        var id: Kind { kind }

        var title: String {
            switch kind {
            case .access: return String(localized: "Read & write access")
            case .branch: return String(localized: "Branch")
            case .size: return String(localized: "Size & file count")
            case .lfs: return String(localized: "Git LFS files")
            case .obsidian: return String(localized: "Obsidian vault")
            }
        }
    }

    enum Blocker: Equatable {
        /// The remote could not be reached with these credentials.
        case connection(String)
        /// GitHub reports read access only.
        case readOnly
    }

    enum Phase: Equatable {
        case checking
        case ready
        case blocked(Blocker)
    }

    let target: Target
    private(set) var checks: [Check] = Kind.allCases.map { Check(kind: $0) }
    private(set) var phase: Phase = .checking
    private(set) var defaultBranch: String?
    private(set) var sizeBytes: Int64?
    private(set) var fileCount: Int?
    private(set) var hasObsidianGitPlugin = false
    /// Set when the user chooses to clone a read-only repository anyway.
    private(set) var acceptedReadOnly = false

    /// Pause between rows so the checklist reads as a sequence, not a flash.
    var stepDelay: Duration = .milliseconds(220)

    init(target: Target) {
        self.target = target
    }

    var gitHubFullName: (owner: String, repo: String)? {
        GitHubService.parseRepoURL(target.repoURL)
    }

    /// Owner/repo or host/path plus branch: "cody/notes · main".
    var remoteLine: String {
        let remote = gitHubFullName.map { "\($0.owner)/\($0.repo)" }
            ?? GitRemoteURL.parse(target.repoURL).map { "\($0.host ?? "")/\($0.repoName)" }
            ?? target.repoURL
        guard let branch = resolvedBranch else { return remote }
        return "\(remote) · \(branch)"
    }

    var resolvedBranch: String? {
        let chosen = target.branch?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let chosen, !chosen.isEmpty { return chosen }
        return defaultBranch
    }

    func continueReadOnly() {
        guard phase == .blocked(.readOnly) else { return }
        acceptedReadOnly = true
        phase = .ready
    }

    func run() async {
        checks = Kind.allCases.map { Check(kind: $0) }
        phase = .checking
        acceptedReadOnly = false

        // 1. Connection with the credentials the clone will use.
        set(.access, .running)
        let remoteURL = GitRemoteURL.cloneURLString(from: target.repoURL) ?? target.repoURL
        do {
            let check = try await LocalGitService.checkRemoteConnection(
                remoteURL: remoteURL, pat: target.transportPayload
            )
            defaultBranch = check.defaultBranch
            // Only reading is proven here; GitHub's permissions refine it below.
            set(.access, .passed, String(localized: "connected"))
        } catch LocalGitError.sshHostKeyTrustRequired(_) {
            set(.access, .passed, String(localized: "confirm key on clone"))
        } catch is CancellationError {
            return
        } catch LocalGitError.cloneFailed(let message) {
            fail(message.replacingOccurrences(of: "Connect to remote: ", with: ""))
            return
        } catch {
            fail(error.localizedDescription)
            return
        }

        guard let token = target.gitHubToken, !token.isEmpty, let repo = gitHubFullName else {
            await finishWithoutContents()
            return
        }

        // 2. GitHub: permission and size.
        let meta = try? await Self.fetchRepositoryMeta(owner: repo.owner, repo: repo.repo, token: token)
        var readOnly = false
        switch meta?.permissions?.push {
        case true?:
            set(.access, .passed, String(localized: "read · write"))
        case false?:
            readOnly = true
            set(.access, .failed, String(localized: "read only"))
        case nil:
            break
        }
        if defaultBranch == nil { defaultBranch = meta?.defaultBranch }
        await pause()
        set(.branch, .passed, resolvedBranch ?? "HEAD")

        // 3. Tree: file count, LFS, Obsidian.
        await pause()
        set(.size, .running)
        let service = GitHubService(pat: token, owner: repo.owner, repo: repo.repo)
        var tree: [GitTreeEntry] = []
        if let branch = resolvedBranch,
           let sha = try? await service.getRef(branch: branch),
           let response = try? await service.getTree(sha: sha, recursive: true) {
            tree = response.tree
        }
        if let kb = meta?.size, kb > 0 { sizeBytes = Int64(kb) * 1024 }
        let files = tree.filter { $0.type == "blob" }
        fileCount = files.isEmpty ? nil : files.count
        set(.size, sizeBytes == nil && fileCount == nil ? .skipped : .passed, sizeDetail)

        await pause()
        set(.lfs, .running)
        if tree.isEmpty {
            set(.lfs, .skipped, String(localized: "checked on clone"))
        } else if let attributes = files.first(where: { $0.path == ".gitattributes" }),
                  let data = try? await service.getBlob(sha: attributes.sha),
                  String(decoding: data, as: UTF8.self).contains("filter=lfs") {
            set(.lfs, .passed, String(localized: "used · supported"))
        } else {
            set(.lfs, .passed, String(localized: "none"))
        }

        await pause()
        set(.obsidian, .running)
        if tree.isEmpty {
            set(.obsidian, .skipped, String(localized: "checked on clone"))
        } else if tree.contains(where: { $0.path == ".obsidian" || $0.path.hasPrefix(".obsidian/") }) {
            hasObsidianGitPlugin = tree.contains { $0.path.hasPrefix(".obsidian/plugins/obsidian-git/") }
            set(.obsidian, .passed, ".obsidian/")
        } else {
            set(.obsidian, .warning, String(localized: "no .obsidian yet"))
        }

        phase = readOnly ? .blocked(.readOnly) : .ready
    }

    // MARK: - Helpers

    private var sizeDetail: String? {
        var parts: [String] = []
        if let sizeBytes {
            parts.append(ByteCountFormatter.string(fromByteCount: sizeBytes, countStyle: .file))
        }
        if let fileCount {
            parts.append(fileCount.formatted())
        }
        return parts.isEmpty ? String(localized: "checked on clone") : parts.joined(separator: " · ")
    }

    /// Non-GitHub remotes: the connection is all that can be checked
    /// without downloading, so the rest is confirmed during the clone.
    private func finishWithoutContents() async {
        await pause()
        set(.branch, .passed, resolvedBranch ?? "HEAD")
        for kind in [Kind.size, .lfs, .obsidian] {
            set(kind, .skipped, String(localized: "checked on clone"))
        }
        phase = .ready
    }

    private func fail(_ message: String) {
        set(.access, .failed, String(localized: "can't connect"))
        for index in checks.indices where checks[index].kind != .access {
            checks[index].status = .skipped
            checks[index].detail = nil
        }
        phase = .blocked(.connection(message))
    }

    private func set(_ kind: Kind, _ status: Status, _ detail: String? = nil) {
        guard let index = checks.firstIndex(where: { $0.kind == kind }) else { return }
        checks[index].status = status
        if detail != nil || status != .running { checks[index].detail = detail }
    }

    private func pause() async {
        try? await Task.sleep(for: stepDelay)
    }

    private struct RepositoryMeta: Decodable {
        struct Permissions: Decodable { let push: Bool? }
        let size: Int?
        let permissions: Permissions?
        let defaultBranch: String?

        enum CodingKeys: String, CodingKey {
            case size, permissions
            case defaultBranch = "default_branch"
        }
    }

    private static func fetchRepositoryMeta(owner: String, repo: String, token: String) async throws -> RepositoryMeta {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "api.github.com"
        let allowed = CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))
        components.percentEncodedPath = "/repos/"
            + [owner, repo].map { $0.addingPercentEncoding(withAllowedCharacters: allowed) ?? $0 }.joined(separator: "/")
        guard let url = components.url else { throw GitHubError.invalidURL }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw GitHubError.apiError((response as? HTTPURLResponse)?.statusCode ?? 0, "")
        }
        return try JSONDecoder().decode(RepositoryMeta.self, from: data)
    }
}

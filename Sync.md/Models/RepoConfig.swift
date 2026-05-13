import Foundation

/// Configuration and state for a single managed repository
struct RepoConfig: Codable, Identifiable, Equatable {
    let id: UUID
    var repoURL: String
    var branch: String
    var authorName: String
    var authorEmail: String
    var vaultFolderName: String
    var customVaultBookmarkData: Data?
    /// When `true`, the custom vault bookmark points to a parent directory
    /// and `vaultFolderName` should be appended to form the actual repo path.
    /// This mirrors `git clone` behaviour: clone into `<parent>/<repoName>/`.
    var customLocationIsParent: Bool
    var authMethod: GitAuthMethod
    var authUsername: String
    var gitState: GitState

    init(
        id: UUID = UUID(),
        repoURL: String,
        branch: String,
        authorName: String,
        authorEmail: String,
        vaultFolderName: String,
        customVaultBookmarkData: Data? = nil,
        customLocationIsParent: Bool = false,
        authMethod: GitAuthMethod? = nil,
        authUsername: String = "",
        gitState: GitState = .empty
    ) {
        self.id = id
        self.repoURL = repoURL
        self.branch = branch
        self.authorName = authorName
        self.authorEmail = authorEmail
        self.vaultFolderName = vaultFolderName
        self.customVaultBookmarkData = customVaultBookmarkData
        self.customLocationIsParent = customLocationIsParent
        if let authMethod {
            self.authMethod = authMethod
        } else if let remote = GitRemoteURL.parse(repoURL), remote.isGitHub && !remote.isSSH {
            self.authMethod = .gitHubPAT
        } else {
            self.authMethod = .none
        }
        self.authUsername = authUsername
        self.gitState = gitState
    }

    // MARK: - Codable (backward-compatible)

    private enum CodingKeys: String, CodingKey {
        case id, repoURL, branch, authorName, authorEmail
        case vaultFolderName, customVaultBookmarkData
        case customLocationIsParent, authMethod, authUsername, gitState
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id                      = try c.decode(UUID.self, forKey: .id)
        repoURL                 = try c.decode(String.self, forKey: .repoURL)
        branch                  = try c.decode(String.self, forKey: .branch)
        authorName              = try c.decode(String.self, forKey: .authorName)
        authorEmail             = try c.decode(String.self, forKey: .authorEmail)
        vaultFolderName         = try c.decode(String.self, forKey: .vaultFolderName)
        customVaultBookmarkData = try c.decodeIfPresent(Data.self, forKey: .customVaultBookmarkData)
        customLocationIsParent  = try c.decodeIfPresent(Bool.self, forKey: .customLocationIsParent) ?? false
        if let decodedAuthMethod = try c.decodeIfPresent(GitAuthMethod.self, forKey: .authMethod) {
            authMethod = decodedAuthMethod
        } else if let remote = GitRemoteURL.parse(repoURL), remote.isGitHub && !remote.isSSH {
            authMethod = .gitHubPAT
        } else {
            authMethod = .none
        }
        authUsername            = try c.decodeIfPresent(String.self, forKey: .authUsername) ?? ""
        gitState                = try c.decode(GitState.self, forKey: .gitState)
    }

    // MARK: - Computed

    var displayName: String {
        GitRemoteURL.parse(repoURL)?.repoName ?? vaultFolderName
    }

    var ownerName: String? {
        GitRemoteURL.parse(repoURL)?.ownerName
    }

    var isCloned: Bool {
        !gitState.commitSHA.isEmpty
    }

    var defaultVaultURL: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        return docs.appendingPathComponent(vaultFolderName, isDirectory: true)
    }
}

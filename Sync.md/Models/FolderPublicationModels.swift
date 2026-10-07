import Foundation

struct FolderPublicationFile: Codable, Sendable, Equatable, Identifiable {
    let path: String
    let size: Int64
    let digest: String
    let isIgnored: Bool
    var isExecutable: Bool? = nil

    var id: String { path }

    /// Suggested defaults only; the file review still owns the selection.
    var isSensitive: Bool {
        let name = URL(fileURLWithPath: path).lastPathComponent.lowercased()
        return name == ".env" || name.hasPrefix(".env.")
            || ["credentials", "credentials.json", "secrets.json", "id_rsa", "id_ed25519"].contains(name)
            || ["pem", "key", "p12", "pfx"].contains(URL(fileURLWithPath: name).pathExtension)
    }
}

struct FolderInspection: Sendable {
    let files: [FolderPublicationFile]
    let warnings: [String]
}

struct FolderPreparedCommit: Sendable {
    let commitOID: String
    let treeOID: String
}

struct PublishedGitHubRepository: Codable, Sendable, Equatable {
    let id: Int64
    let owner: String
    let name: String
    let htmlURL: String
    let cloneURL: String
    let isPrivate: Bool
}

struct GitHubPublicationAccount: Sendable, Equatable {
    let id: Int64
    let login: String
}

enum FolderPublicationPhase: String, Codable, Sendable {
    case review, preparing, prepared, creatingRemote, creationUnknown
    case remoteCreated, pushing, pushUnknown, published, completed
}

/// A recovery journal, never a credential store or a substitute filesystem path.
struct FolderPublicationRecord: Codable, Sendable, Equatable, Identifiable {
    let id: UUID
    var bookmarkData: Data
    var folderName: String
    var folderPath: String
    var files: [FolderPublicationFile]
    var warnings: [String]
    var selectedPaths: [String]
    var authorName: String
    var authorEmail: String
    var commitMessage: String
    var accountLogin: String
    var accountUserID: Int64?
    var repositoryName: String
    var commitOID: String?
    var treeOID: String?
    var remote: PublishedGitHubRepository?
    var phase: FolderPublicationPhase
    var lastError: String?
    let createdAt: Date

    init(id: UUID = UUID(), bookmarkData: Data, folderName: String, folderPath: String,
         files: [FolderPublicationFile], warnings: [String] = []) {
        self.id = id
        self.bookmarkData = bookmarkData
        self.folderName = folderName
        self.folderPath = folderPath
        self.files = files
        self.warnings = warnings
        selectedPaths = files.filter { !$0.isIgnored && !$0.isSensitive && $0.size <= 10 * 1024 * 1024 }.map(\.path)
        authorName = ""
        authorEmail = ""
        commitMessage = "Initial commit"
        accountLogin = ""
        repositoryName = folderName
        phase = .review
        createdAt = Date()
    }
}

protocol FolderGitHandling: Sendable {
    func inspect(at url: URL) async throws -> FolderInspection
    func prepare(at url: URL, workflowID: UUID, files: [FolderPublicationFile], selectedPaths: Set<String>,
                 authorName: String, authorEmail: String, message: String) async throws -> FolderPreparedCommit
    func validatePrepared(at url: URL, workflowID: UUID, commitOID: String) async throws
    func publish(at url: URL, workflowID: UUID, commitOID: String, remoteURL: String, token: String) async throws
    func finish(at url: URL, workflowID: UUID, commitOID: String, remoteURL: String, token: String) async throws
}

protocol GitHubRepositoryPublishing: Sendable {
    func authenticatedAccount(token: String) async throws -> GitHubPublicationAccount
    func authenticatedLogin(token: String) async throws -> String
    func createPrivateRepository(name: String, token: String) async throws -> PublishedGitHubRepository
    func repository(owner: String, name: String, token: String) async throws -> PublishedGitHubRepository?
    func branchOID(owner: String, name: String, branch: String, token: String) async throws -> String?
    func hasAnyReferences(owner: String, name: String, token: String) async throws -> Bool
}

enum FolderPublicationError: LocalizedError {
    case unavailable(String)
    case missingRecord, busy, bookmarkUnavailable, emptySelection, invalidName
    case accountMismatch, creationNeedsReconciliation, remoteNotEmpty, changedHistory

    var errorDescription: String? {
        switch self {
        case .unavailable(let message): return message
        case .missingRecord: return String(localized: "This folder publication could not be found.")
        case .busy: return String(localized: "A folder publication is already running. Wait or cancel it before continuing.")
        case .bookmarkUnavailable: return String(localized: "Access to the original folder is unavailable. Reconnect the folder to continue.")
        case .emptySelection: return String(localized: "Select at least one file for the initial commit. Git does not store empty folders.")
        case .invalidName: return String(localized: "Use a repository name containing letters, numbers, hyphens, underscores, or periods.")
        case .accountMismatch: return String(localized: "Sign in to the GitHub account selected for this publication. It will not be sent to a different account.")
        case .creationNeedsReconciliation: return String(localized: "GitHub may have created the repository. Check GitHub and confirm the destination before continuing.")
        case .remoteNotEmpty: return String(localized: "The destination repository already has history. First publication requires an empty repository.")
        case .changedHistory: return String(localized: "The destination branch differs from the reviewed commit. Its history has been preserved.")
        }
    }
}

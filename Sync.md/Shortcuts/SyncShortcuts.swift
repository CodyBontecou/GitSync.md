import AppIntents
import Foundation

struct GitRepositoryEntity: AppEntity, Identifiable {
    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "Repository")
    static var defaultQuery = GitRepositoryEntityQuery()

    let id: String

    @Property(title: "Name")
    var name: String

    @Property(title: "Branch")
    var branch: String

    @Property(title: "Remote URL")
    var repoURL: String

    @Property(title: "Cloned")
    var isCloned: Bool

    var displayRepresentation: DisplayRepresentation {
        let subtitle = branch.isEmpty ? repoURL : "\(branch) • \(repoURL)"
        return DisplayRepresentation(title: "\(name)", subtitle: "\(subtitle)")
    }

    init(id: String, name: String, branch: String, repoURL: String, isCloned: Bool) {
        self.id = id
        self.name = name
        self.branch = branch
        self.repoURL = repoURL
        self.isCloned = isCloned
    }

    init(repo: RepoConfig) {
        self.init(
            id: repo.id.uuidString,
            name: repo.displayName,
            branch: repo.gitState.branch.isEmpty ? repo.branch : repo.gitState.branch,
            repoURL: repo.repoURL,
            isCloned: repo.isCloned
        )
    }
}

struct GitRepositoryEntityQuery: EntityStringQuery {
    func entities(for identifiers: [GitRepositoryEntity.ID]) async throws -> [GitRepositoryEntity] {
        let entitiesByID = Dictionary(uniqueKeysWithValues: Self.allRepositories().map { ($0.id, $0) })
        return identifiers.compactMap { entitiesByID[$0] }
    }

    func suggestedEntities() async throws -> [GitRepositoryEntity] {
        Self.allRepositories().filter(\.isCloned)
    }

    func entities(matching string: String) async throws -> [GitRepositoryEntity] {
        let query = string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return try await suggestedEntities() }

        return Self.allRepositories().filter { entity in
            entity.isCloned
                && (entity.name.localizedCaseInsensitiveContains(query)
                    || entity.repoURL.localizedCaseInsensitiveContains(query)
                    || entity.branch.localizedCaseInsensitiveContains(query))
        }
    }

    private static func allRepositories() -> [GitRepositoryEntity] {
        AppState.loadPersistedRepos()
            .map(GitRepositoryEntity.init(repo:))
            .sorted { lhs, rhs in
                lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
            }
    }
}

struct PullAllRepositoriesIntent: AppIntent {
    static var title: LocalizedStringResource = "Pull All Repositories"
    static var description = IntentDescription("Fetch and fast-forward every cloned GitSync.md repository. Use this in a Personal Automation that runs when GitSync.md opens to auto-pull on launch.")
    static var openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let message = await GitShortcutRunner.pullAllRepositories()
        return .result(dialog: "\(message)")
    }
}

struct PullRepositoryIntent: AppIntent {
    static var title: LocalizedStringResource = "Pull Repository"
    static var description = IntentDescription("Fetch and fast-forward one cloned GitSync.md repository.")
    static var openAppWhenRun = false

    @Parameter(title: "Repository", requestValueDialog: "Which repository should GitSync.md pull?")
    var repository: GitRepositoryEntity

    static var parameterSummary: some ParameterSummary {
        Summary("Pull \(\.$repository)")
    }

    init() {}

    init(repository: GitRepositoryEntity) {
        self.repository = repository
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let message = await GitShortcutRunner.pullRepository(id: repository.id)
        return .result(dialog: "\(message)")
    }
}

struct SyncMDAppShortcutsProvider: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: PullAllRepositoriesIntent(),
            phrases: [
                "Pull all repositories in \(.applicationName)",
                "Sync all repositories in \(.applicationName)",
                "Update my notes in \(.applicationName)"
            ],
            shortTitle: "Pull All",
            systemImageName: "arrow.down.circle.fill"
        )

        AppShortcut(
            intent: PullRepositoryIntent(),
            phrases: [
                "Pull \(\.$repository) in \(.applicationName)",
                "Sync \(\.$repository) in \(.applicationName)",
                "Update \(\.$repository) in \(.applicationName)"
            ],
            shortTitle: "Pull Repo",
            systemImageName: "arrow.down.circle"
        )

        AppShortcut(
            intent: PushRepositoryIntent(),
            phrases: [
                "Push \(\.$repository) in \(.applicationName)",
                "Commit and push \(\.$repository) in \(.applicationName)"
            ],
            shortTitle: "Push Repo",
            systemImageName: "arrow.up.circle.fill"
        )

        AppShortcut(
            intent: SyncRepositoryIntent(),
            phrases: [
                "Pull and push \(\.$repository) in \(.applicationName)"
            ],
            shortTitle: "Pull & Push",
            systemImageName: "arrow.triangle.2.circlepath.circle.fill"
        )
    }

    static var shortcutTileColor: ShortcutTileColor = .blue
}

@MainActor
enum GitShortcutRunner {
    static func pullAllRepositories() async -> String {
        let state = AppState()
        let clonedRepos = state.repos.filter(\.isCloned)

        guard !clonedRepos.isEmpty else {
            return String(localized: "No cloned repositories to pull yet. Open GitSync.md and clone a repository first.")
        }

        var results: [GitShortcutPullResult] = []
        for repo in clonedRepos {
            results.append(await pull(repo: repo, in: state))
        }

        return GitShortcutPullSummary(results: results).dialog
    }

    static func pullRepository(id: String) async -> String {
        guard let repoID = UUID(uuidString: id) else {
            return String(localized: "Repository not found. Pick a repository again in Shortcuts.")
        }

        let state = AppState()
        guard let repo = state.repo(id: repoID) else {
            return String(localized: "Repository not found. Pick a repository again in Shortcuts.")
        }

        guard repo.isCloned else {
            return String(localized: "\(repo.displayName) has not been cloned yet. Open GitSync.md and clone it first.")
        }

        return (await pull(repo: repo, in: state)).dialog
    }

    private static func pull(repo: RepoConfig, in state: AppState) async -> GitShortcutPullResult {
        let pullResult = await state.pullOnly(repoID: repo.id, showsProgressDelay: false)
        let outcome = state.pullOutcomeByRepo[repo.id]
        let message = outcome?.message
            ?? (pullResult.completedWithoutAttention ? String(localized: "Already up to date") : state.lastError ?? String(localized: "Pull failed"))
        let status = GitShortcutPullResult.Status(result: pullResult)

        return GitShortcutPullResult(
            repositoryName: repo.displayName,
            status: status,
            message: message
        )
    }
}

private struct GitShortcutPullResult {
    enum Status {
        case updated
        case upToDate
        case blocked
        case failed

        init(result: RepositoryPullResult) {
            switch result {
            case .updated:
                self = .updated
            case .updatedWithAttention:
                self = .blocked
            case .upToDate:
                self = .upToDate
            case .blockedByLocalChanges, .diverged, .remoteBranchMissing:
                self = .blocked
            case .wrongBranch, .authenticationOrTrustRequired, .unavailable, .failed:
                self = .failed
            }
        }
    }

    let repositoryName: String
    let status: Status
    let message: String

    var dialog: String {
        "\(repositoryName): \(message)"
    }

    var needsAttention: Bool {
        status == .blocked || status == .failed
    }
}

private struct GitShortcutPullSummary {
    let results: [GitShortcutPullResult]

    var dialog: String {
        guard results.count != 1 else { return results[0].dialog }

        var parts: [String] = []
        appendCount(
            for: .updated,
            singular: String(localized: "updated"),
            plural: String(localized: "updated"),
            into: &parts
        )
        appendCount(
            for: .upToDate,
            singular: String(localized: "already up to date"),
            plural: String(localized: "already up to date"),
            into: &parts
        )
        appendCount(
            for: .blocked,
            singular: String(localized: "needs attention"),
            plural: String(localized: "need attention"),
            into: &parts
        )
        appendCount(
            for: .failed,
            singular: String(localized: "failed"),
            plural: String(localized: "failed"),
            into: &parts
        )

        let repoWord = results.count == 1 ? String(localized: "repository") : String(localized: "repositories")
        var message = String(localized: "Pulled \(results.count) \(repoWord)")
        if !parts.isEmpty {
            message += ": " + parts.joined(separator: ", ")
        }
        message += "."

        let attentionItems = results.filter(\.needsAttention).prefix(3).map(\.dialog)
        if !attentionItems.isEmpty {
            message += " " + attentionItems.joined(separator: " ")
        }

        return message
    }

    private func appendCount(
        for status: GitShortcutPullResult.Status,
        singular: String,
        plural: String,
        into parts: inout [String]
    ) {
        let count = results.filter { $0.status == status }.count
        guard count > 0 else { return }
        parts.append("\(count) \(count == 1 ? singular : plural)")
    }
}

// MARK: - Push / Sync Intents

/// Hard failures surfaced to Shortcuts as intent errors. Soft outcomes
/// (pushed / noChanges / blocked) are returned as a `GitSyncResultEntity`
/// so automations are not halted by expected states.
enum GitShortcutError: LocalizedError {
    case repositoryNotFound
    case notCloned(String)
    case authenticationRequired(String)
    case operationFailed(String)

    var errorDescription: String? {
        switch self {
        case .repositoryNotFound:
            return String(localized: "Repository not found. Pick a repository again in Shortcuts.")
        case .notCloned(let name):
            return String(localized: "\(name) has not been cloned yet. Open GitSync.md and clone it first.")
        case .authenticationRequired(let message):
            return message
        case .operationFailed(let message):
            return message
        }
    }
}

/// Structured outcome returned to Shortcuts so automations can branch on
/// `status`, read the pushed `commitSHA`, or surface `message` in a
/// notification — without parsing dialog text.
struct GitSyncResultEntity: AppEntity, Identifiable {
    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "Sync Result")
    static var defaultQuery = GitSyncResultEntityQuery()

    /// One of: `pushed`, `noChanges`, `upToDate`, `blocked`.
    static let statusPushed = "pushed"
    static let statusNoChanges = "noChanges"
    static let statusUpToDate = "upToDate"
    static let statusBlocked = "blocked"

    let id: String

    @Property(title: "Status")
    var status: String

    @Property(title: "Commit SHA")
    var commitSHA: String

    @Property(title: "Message")
    var message: String

    @Property(title: "Repository")
    var repositoryName: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(
            title: "\(repositoryName): \(status)",
            subtitle: "\(message)"
        )
    }

    init(id: String, status: String, commitSHA: String, message: String, repositoryName: String) {
        self.id = id
        self.status = status
        self.commitSHA = commitSHA
        self.message = message
        self.repositoryName = repositoryName
    }
}

/// Output-only entity: resolves nothing on lookup.
struct GitSyncResultEntityQuery: EntityQuery {
    func entities(for identifiers: [GitSyncResultEntity.ID]) async throws -> [GitSyncResultEntity] { [] }
}

struct PushRepositoryIntent: AppIntent {
    static var title: LocalizedStringResource = "Push Repository"
    static var description = IntentDescription("Stage all changes, commit, and push one cloned GitSync.md repository — without opening the app.")
    static var openAppWhenRun = false

    @Parameter(title: "Repository", requestValueDialog: "Which repository should GitSync.md push?")
    var repository: GitRepositoryEntity

    @Parameter(
        title: "Commit Message",
        description: "Optional commit message. Defaults to \"Update from GitSync.md\"."
    )
    var message: String?

    static var parameterSummary: some ParameterSummary {
        Summary("Push \(\.$repository)")
    }

    init() {}

    init(repository: GitRepositoryEntity, message: String? = nil) {
        self.repository = repository
        self.message = message
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<GitSyncResultEntity> {
        let outcome = try await GitShortcutRunner.push(id: repository.id, message: message)
        return .result(value: outcome.entity, dialog: "\(outcome.dialog)")
    }
}

struct SyncRepositoryIntent: AppIntent {
    static var title: LocalizedStringResource = "Sync Repository"
    static var description = IntentDescription("Pull one cloned GitSync.md repository, then commit and push local changes — without opening the app.")
    static var openAppWhenRun = false

    @Parameter(title: "Repository", requestValueDialog: "Which repository should GitSync.md sync?")
    var repository: GitRepositoryEntity

    @Parameter(
        title: "Commit Message",
        description: "Optional commit message. Defaults to \"Update from GitSync.md\"."
    )
    var message: String?

    static var parameterSummary: some ParameterSummary {
        Summary("Pull and push \(\.$repository)")
    }

    init() {}

    init(repository: GitRepositoryEntity, message: String? = nil) {
        self.repository = repository
        self.message = message
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<GitSyncResultEntity> {
        let outcome = try await GitShortcutRunner.sync(id: repository.id, message: message)
        return .result(value: outcome.entity, dialog: "\(outcome.dialog)")
    }
}

@MainActor
extension GitShortcutRunner {
    struct ShortcutOutcome {
        let entity: GitSyncResultEntity
        let dialog: String
    }

    static func resolveRepo(id: String) throws -> (AppState, RepoConfig) {
        guard let repoID = UUID(uuidString: id) else {
            throw GitShortcutError.repositoryNotFound
        }
        let state = AppState()
        guard let repo = state.repo(id: repoID) else {
            throw GitShortcutError.repositoryNotFound
        }
        guard repo.isCloned else {
            throw GitShortcutError.notCloned(repo.displayName)
        }
        return (state, repo)
    }

    static func outcome(
        repo: RepoConfig,
        status: String,
        commitSHA: String,
        message: String
    ) -> ShortcutOutcome {
        ShortcutOutcome(
            entity: GitSyncResultEntity(
                id: "\(repo.id)-\(status)",
                status: status,
                commitSHA: commitSHA,
                message: message,
                repositoryName: repo.displayName
            ),
            dialog: "\(repo.displayName): \(message)"
        )
    }

    static func push(id: String, message: String?) async throws -> ShortcutOutcome {
        let (state, repo) = try resolveRepo(id: id)
        return try pushOutcome(
            repo: repo,
            result: await state.pushOnly(repoID: repo.id, message: message)
        )
    }

    static func pushOutcome(repo: RepoConfig, result: RepositoryPushResult) throws -> ShortcutOutcome {
        switch result {
        case .pushed(let commitSHA):
            let short = String(commitSHA.prefix(7))
            return outcome(
                repo: repo,
                status: GitSyncResultEntity.statusPushed,
                commitSHA: commitSHA,
                message: String(localized: "Pushed \(short)")
            )
        case .noChanges:
            return outcome(
                repo: repo,
                status: GitSyncResultEntity.statusNoChanges,
                commitSHA: "",
                message: String(localized: "No local changes to push")
            )
        case .blocked(let message):
            return outcome(repo: repo, status: GitSyncResultEntity.statusBlocked, commitSHA: "", message: message)
        case .commitSavedNotPushed(let commitSHA, let message, let trustError):
            if trustError != nil { throw GitShortcutError.authenticationRequired(message) }
            throw GitShortcutError.operationFailed(String(localized: "The push did not complete. Local commit: \(commitSHA.prefix(7)). \(message)"))
        case .authenticationOrTrustRequired(let message, _):
            throw GitShortcutError.authenticationRequired(message)
        case .failed(let message):
            throw GitShortcutError.operationFailed(message)
        }
    }

    static func sync(id: String, message: String?) async throws -> ShortcutOutcome {
        let (state, repo) = try resolveRepo(id: id)
        return try syncOutcome(
            repo: repo,
            result: await state.syncRepository(repoID: repo.id, message: message)
        )
    }

    static func syncOutcome(repo: RepoConfig, result: RepositorySyncResult) throws -> ShortcutOutcome {
        switch result.outcome {
        case .synced:
            let sha = if case .pushed(let commitSHA) = result.push { commitSHA } else { "" }
            let short = String(sha.prefix(7))
            return outcome(
                repo: repo,
                status: GitSyncResultEntity.statusPushed,
                commitSHA: sha,
                message: String(localized: "Synced \(short)")
            )
        case .pushSkipped:
            return outcome(
                repo: repo,
                status: GitSyncResultEntity.statusNoChanges,
                commitSHA: result.pull?.newCommitSHA ?? "",
                message: String(localized: "Synced — no local changes")
            )
        case .blocked:
            return outcome(
                repo: repo,
                status: GitSyncResultEntity.statusBlocked,
                commitSHA: result.push?.finalLocalCommitSHA ?? result.pull?.newCommitSHA ?? "",
                message: result.message
            )
        case .authenticationOrTrustRequired(let message, _):
            throw GitShortcutError.authenticationRequired(message)
        case .failed(let message):
            throw GitShortcutError.operationFailed(message)
        }
    }
}

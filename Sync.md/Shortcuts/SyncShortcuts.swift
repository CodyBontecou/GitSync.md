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
    }

    static var shortcutTileColor: ShortcutTileColor = .blue
}

@MainActor
private enum GitShortcutRunner {
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
        let succeeded = await state.pull(repoID: repo.id, showsProgressDelay: false)
        let outcome = state.pullOutcomeByRepo[repo.id]
        let message = outcome?.message
            ?? (succeeded ? String(localized: "Already up to date") : state.lastError ?? String(localized: "Pull failed"))
        let status = GitShortcutPullResult.Status(kind: outcome?.kind, succeeded: succeeded)

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

        init(kind: PullOutcomeKind?, succeeded: Bool) {
            switch kind {
            case .fastForwarded, .rebased:
                self = .updated
            case .upToDate:
                self = .upToDate
            case .blockedByLocalChanges, .diverged, .rebaseConflicts, .remoteBranchMissing:
                self = .blocked
            case .failed:
                self = .failed
            case .none:
                self = succeeded ? .upToDate : .failed
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

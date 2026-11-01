import AppIntents
import Foundation

// MARK: - Sync all, status and open (Settings › Shortcuts & Siri lists 7)

struct SyncAllRepositoriesIntent: AppIntent {
    static var title: LocalizedStringResource = "Sync All Repositories"
    static var description = IntentDescription("Pull every cloned GitSync.md repository, then commit and push local edits. Stops safely on conflicts.")
    static var openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let message = await GitShortcutRunner.syncAllRepositories()
        return .result(dialog: "\(message)")
    }
}

struct RepositoryStatusIntent: AppIntent {
    static var title: LocalizedStringResource = "Get Repository Status"
    static var description = IntentDescription("Report how many files changed on this iPhone and how many commits are waiting to push or pull — without touching the network.")
    static var openAppWhenRun = false

    @Parameter(title: "Repository", requestValueDialog: "Which repository should GitSync.md check?")
    var repository: GitRepositoryEntity

    static var parameterSummary: some ParameterSummary {
        Summary("Get the status of \(\.$repository)")
    }

    init() {}

    init(repository: GitRepositoryEntity) {
        self.repository = repository
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<String> {
        let message = try await GitShortcutRunner.status(id: repository.id)
        return .result(value: message, dialog: "\(message)")
    }
}

struct OpenRepositoryIntent: AppIntent {
    static var title: LocalizedStringResource = "Open Repository"
    static var description = IntentDescription("Open one repository in GitSync.md.")
    static var openAppWhenRun = true

    @Parameter(title: "Repository", requestValueDialog: "Which repository should GitSync.md open?")
    var repository: GitRepositoryEntity

    static var parameterSummary: some ParameterSummary {
        Summary("Open \(\.$repository)")
    }

    init() {}

    init(repository: GitRepositoryEntity) {
        self.repository = repository
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        guard let repoID = UUID(uuidString: repository.id) else {
            throw GitShortcutError.repositoryNotFound
        }
        SyncRuntimeLocator.openVault(repoID: repoID)
        return .result()
    }
}

@MainActor
extension GitShortcutRunner {
    static func syncAllRepositories() async -> String {
        let state = AppState()
        let repos = state.repos.filter(\.isCloned)
        guard !repos.isEmpty else {
            return String(localized: "No cloned repositories to sync yet. Open GitSync.md and clone a repository first.")
        }

        var lines: [String] = []
        for repo in repos {
            let line: String
            do {
                line = try syncOutcome(repo: repo, result: await state.syncRepository(repoID: repo.id, message: nil)).dialog
            } catch {
                line = "\(repo.displayName): \(error.localizedDescription)"
            }
            lines.append(line)
        }
        return lines.joined(separator: "\n")
    }

    static func status(id: String) async throws -> String {
        let (state, repo) = try resolveRepo(id: id)
        let service = LocalGitService(localURL: state.vaultURL(for: repo.id))
        let info = try await service.repoInfo()
        let counts = await service.localAheadBehind()

        var parts: [String] = []
        parts.append(info.changeCount == 0
            ? String(localized: "no local changes")
            : String(localized: "\(info.changeCount) changed files"))
        if let counts {
            if counts.ahead > 0 { parts.append(String(localized: "↑\(counts.ahead) to push")) }
            if counts.behind > 0 { parts.append(String(localized: "↓\(counts.behind) to pull")) }
            if counts.ahead == 0, counts.behind == 0, info.changeCount == 0 {
                parts = [String(localized: "in sync")]
            }
        }
        return "\(repo.displayName) · \(info.branch): " + parts.joined(separator: " · ")
    }
}

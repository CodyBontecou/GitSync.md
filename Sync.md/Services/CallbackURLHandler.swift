import Foundation
import UIKit

// MARK: - Callback Action

/// Actions supported by the x-callback-url handler.
enum CallbackAction: String {
    case pull
    case push
    case sync
    case status
}

// MARK: - Callback URL Handler

/// Handles x-callback-url requests from external apps (e.g. the Obsidian plugin).
///
/// URL format:
///   syncmd://x-callback-url/<action>?repo=<vaultFolderName>&x-success=<url>&x-error=<url>
///
/// Supported actions:
///   - `pull`   — Fetch and fast-forward the repository
///   - `push`   — Stage all changes, commit, and push to remote
///   - `sync`   — Pull then push (no-changes-to-push is not an error)
///   - `status` — Return repository info without modifying anything
///
/// The handler navigates to the repo's VaultView, shows progress using
/// the existing sync UI, displays a result banner, and then redirects
/// back to the calling app.
@MainActor
final class CallbackURLHandler {

    private let appState: AppState

    init(appState: AppState) {
        self.appState = appState
    }

    // MARK: - Public

    /// Returns `true` if the URL is an x-callback-url that this handler should process.
    func canHandle(_ url: URL) -> Bool {
        url.scheme == "syncmd" && url.host == "x-callback-url"
    }

    /// Parse the incoming URL, navigate to the repo, and execute the action.
    func handle(_ url: URL) {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return
        }

        // Action is the path component, e.g. "/pull" → "pull"
        let path = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let action = CallbackAction(rawValue: path) else {
            redirectError(from: components, message: String(localized: "Unknown action: \(path)"))
            return
        }

        let params = Self.queryDict(from: components)

        guard let repoName = params["repo"] else {
            redirectError(from: components, message: String(localized: "Missing required 'repo' parameter"))
            return
        }

        guard let repo = appState.repos.first(where: { $0.vaultFolderName == repoName }) else {
            redirectError(from: components, message: String(localized: "Repository '\(repoName)' not found in GitSync.md"))
            return
        }

        guard repo.isCloned else {
            redirectError(from: components, message: String(localized: "Repository '\(repoName)' is not cloned yet — open GitSync.md and clone it first"))
            return
        }

        let message    = params["message"] ?? ""
        let successURL = params["x-success"]
        let errorURL   = params["x-error"]

        // 1. Navigate to the repo's VaultView
        appState.callbackNavigateToRepoID = repo.id

        // 2. Show syncing state immediately
        appState.isSyncing = true
        appState.syncingRepoID = repo.id
        appState.syncProgress = progressLabel(for: action)

        Task {
            // Brief pause so the navigation animation can start
            try? await Task.sleep(for: .milliseconds(400))

            await execute(
                action: action,
                repoID: repo.id,
                message: message,
                successURL: successURL,
                errorURL: errorURL
            )
        }
    }

    // MARK: - Execution

    private func execute(
        action: CallbackAction,
        repoID: UUID,
        message: String,
        successURL: String?,
        errorURL: String?
    ) async {
        do {
            var result: [String: String] = ["action": action.rawValue]

            let serialized = try appState.serializedRepository(repoID: repoID)
            switch action {
            case .pull:
                appState.syncProgress = "Pulling from remote…"
                let pullResult = try await serialized.withLease { repository in
                    try await self.performPull(repoID: repoID, repository: repository)
                }
                result["sha"] = pullResult.newCommitSHA
                result["updated"] = pullResult.updated ? "true" : "false"
                if let attention = pullResult.attention { throw attention }

            case .push:
                appState.syncProgress = "Committing & pushing…"
                let pushResult = try await serialized.withLease { repository in
                    try await self.performPush(repoID: repoID, message: message, repository: repository)
                }
                result["sha"] = pushResult.commitSHA

            case .sync:
                try await serialized.withLease { repository in
                    self.appState.syncProgress = "Pulling from remote…"
                    let pullResult = try await self.performPull(repoID: repoID, repository: repository)
                    result["pull_updated"] = pullResult.updated ? "true" : "false"
                    if let attention = pullResult.attention { throw attention }

                    self.appState.syncProgress = "Pushing local changes…"
                    do {
                        let pushResult = try await self.performPush(repoID: repoID, message: message, repository: repository)
                        result["sha"] = pushResult.commitSHA
                    } catch LocalGitError.noChanges {
                        result["sha"] = pullResult.newCommitSHA
                        result["push_skipped"] = "true"
                    }
                }

            case .status:
                appState.syncProgress = "Reading status…"
                let info = try await serialized.withLease { repository in
                    try await self.performStatus(repository: repository)
                }
                result["branch"] = info.branch
                result["sha"] = info.commitSHA
                result["changes"] = "\(info.changeCount)"
            }

            result["status"] = "ok"

            // Show success state
            appState.isSyncing = false
            appState.syncingRepoID = nil

            let sha = result["sha"].map { String($0.prefix(7)) } ?? ""
            appState.callbackResult = CallbackResultState(
                repoID: repoID,
                action: action.rawValue,
                isSuccess: true,
                message: successMessage(action: action, params: result, sha: sha)
            )

            // Hold the result banner briefly, then redirect
            try? await Task.sleep(for: .seconds(1.5))
            redirect(to: successURL, params: result)

            // Clean up after redirect
            try? await Task.sleep(for: .milliseconds(300))
            appState.callbackResult = nil
            appState.callbackNavigateToRepoID = nil

        } catch {
            // Show error state
            appState.isSyncing = false
            appState.syncingRepoID = nil

            appState.callbackResult = CallbackResultState(
                repoID: repoID,
                action: action.rawValue,
                isSuccess: false,
                message: error.localizedDescription
            )

            let errorParams: [String: String] = [
                "action":  action.rawValue,
                "status":  "error",
                "message": error.localizedDescription,
            ]

            try? await Task.sleep(for: .seconds(2))
            redirect(to: errorURL ?? successURL, params: errorParams)

            try? await Task.sleep(for: .milliseconds(300))
            appState.callbackResult = nil
            appState.callbackNavigateToRepoID = nil
        }
    }

    // MARK: - Display Helpers

    private func progressLabel(for action: CallbackAction) -> String {
        switch action {
        case .pull:   return String(localized: "Pulling from remote…")
        case .push:   return String(localized: "Committing & pushing…")
        case .sync:   return String(localized: "Syncing…")
        case .status: return String(localized: "Reading status…")
        }
    }

    private func successMessage(action: CallbackAction, params: [String: String], sha: String) -> String {
        switch action {
        case .pull:
            let updated = params["updated"] == "true"
            return updated ? String(localized: "Pulled \(sha)") : String(localized: "Already up to date")
        case .push:
            return String(localized: "Pushed \(sha)")
        case .sync:
            let skipped = params["push_skipped"] == "true"
            return skipped ? String(localized: "Synced — no local changes") : String(localized: "Synced \(sha)")
        case .status:
            let branch = params["branch"] ?? "?"
            let changes = params["changes"] ?? "0"
            return String(localized: "\(branch) · \(changes) changes")
        }
    }

    // MARK: - Git Operations

    private func performPull(
        repoID: UUID,
        repository: any GitRepositoryProtocol
    ) async throws -> LocalPullResult {
        guard let idx = appState.repoIndex(id: repoID), repository.hasGitDirectory else {
            throw LocalGitError.notCloned
        }

        let repo = appState.repos[idx]
        let result = try await repository.pull(pat: appState.authPayload(for: repo))

        if result.updated, let currentIndex = appState.repoIndex(id: repoID) {
            appState.repos[currentIndex].gitState.commitSHA = result.newCommitSHA
            appState.repos[currentIndex].gitState.lastSyncDate = Date()
            appState.saveRepos()
            appState.detectChanges(repoID: repoID)
        }

        return result
    }

    private func performPush(
        repoID: UUID,
        message: String,
        repository: any GitRepositoryProtocol
    ) async throws -> LocalPushResult {
        guard let idx = appState.repoIndex(id: repoID), repository.hasGitDirectory else {
            throw LocalGitError.notCloned
        }

        let repo = appState.repos[idx]

        // The encompassing lease keeps every status/stage pass, commit, and
        // push indivisible relative to all other in-process repository work.
        try await stageAllLocalChanges(repository: repository)

        let commitMsg = message.isEmpty ? "Update from GitSync.md" : message

        let result = try await repository.commitAndPush(
            message: commitMsg,
            authorName: repo.authorName,
            authorEmail: repo.authorEmail,
            pat: appState.authPayload(for: repo)
        )

        if let currentIndex = appState.repoIndex(id: repoID) {
            appState.repos[currentIndex].gitState.commitSHA = result.commitSHA
            appState.repos[currentIndex].gitState.lastSyncDate = Date()
            appState.saveRepos()
        }
        appState.detectChanges(repoID: repoID)

        return result
    }

    /// Stages all local changes for callback pushes, with a short settle window
    /// to absorb delayed file-system events (e.g. Obsidian rename = copy+delete
    /// where the delete can arrive shortly after the new file appears).
    private func stageAllLocalChanges(repository: any GitRepositoryProtocol) async throws {
        var sawAnyChanges = false

        // Run multiple add/update passes over a short window so delayed rename
        // deletions are captured before commit.
        for pass in 0..<8 {
            let before = try await repository.repoInfo()
            if !before.statusEntries.isEmpty {
                sawAnyChanges = true
            }

            try await repository.stageAll()

            if pass < 7 {
                try? await Task.sleep(for: .milliseconds(250))
            }
        }

        var finalInfo = try await repository.repoInfo()
        if finalInfo.statusEntries.contains(where: { $0.indexStatus != nil }) {
            return
        }

        // Fallback to per-entry staging if libgit2 add/update missed anything.
        if !finalInfo.statusEntries.isEmpty {
            var seen = Set<String>()
            for entry in finalInfo.statusEntries {
                guard entry.path != "<unknown>" else { continue }
                let key = "\(entry.path)\u{0}\(entry.oldPath ?? "")"
                guard seen.insert(key).inserted else { continue }
                try await repository.stage(path: entry.path, oldPath: entry.oldPath)
            }

            finalInfo = try await repository.repoInfo()
            if finalInfo.statusEntries.contains(where: { $0.indexStatus != nil }) {
                return
            }
        }

        if sawAnyChanges {
            // We saw changes but couldn't stage them into the index.
            throw LocalGitError.commitFailed(String(localized: "Could not stage local file changes before push."))
        }

        throw LocalGitError.noChanges
    }

    private func performStatus(repository: any GitRepositoryProtocol) async throws -> LocalRepoInfo {
        guard repository.hasGitDirectory else { throw LocalGitError.notCloned }
        return try await repository.repoInfo()
    }

    // MARK: - Redirect Helpers

    private func redirect(to baseURL: String?, params: [String: String]) {
        guard let baseURL,
              var components = URLComponents(string: baseURL) else { return }

        let existing  = components.queryItems ?? []
        let additions = params.map { URLQueryItem(name: $0.key, value: $0.value) }
        components.queryItems = existing + additions

        guard let url = components.url else { return }
        UIApplication.shared.open(url)
    }

    private func redirectError(from components: URLComponents, message: String) {
        let params   = Self.queryDict(from: components)
        let errorURL = params["x-error"] ?? params["x-success"]
        redirect(to: errorURL, params: [
            "status":  "error",
            "message": message,
        ])
    }

    // MARK: - Parsing Helpers

    private static func queryDict(from components: URLComponents) -> [String: String] {
        Dictionary(
            (components.queryItems ?? []).compactMap { item in
                guard let value = item.value else { return nil }
                return (item.name, value)
            },
            uniquingKeysWith: { _, last in last }
        )
    }
}

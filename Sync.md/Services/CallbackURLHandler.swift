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

/// Handles x-callback-url requests from external triggers (e.g. a tapped link in an Obsidian note, or an iOS Shortcut).
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
                let mapping = Self.mapPullResult(
                    await appState.pullOnly(repoID: repoID, showsProgressDelay: false)
                )
                result.merge(mapping.params, uniquingKeysWith: { _, latest in latest })
                if let message = mapping.errorMessage {
                    throw CallbackActionError(message: message, params: mapping.params)
                }

            case .push:
                appState.syncProgress = "Committing & pushing…"
                let mapping = Self.mapPushResult(
                    await appState.pushOnly(repoID: repoID, message: message)
                )
                result.merge(mapping.params, uniquingKeysWith: { _, latest in latest })
                if let message = mapping.errorMessage {
                    throw CallbackActionError(message: message, params: mapping.params)
                }

            case .sync:
                appState.syncProgress = String(localized: "Syncing repository…")
                let mapping = Self.mapSyncResult(
                    await appState.syncRepository(repoID: repoID, message: message)
                )
                result.merge(mapping.params, uniquingKeysWith: { _, latest in latest })
                if let message = mapping.errorMessage {
                    throw CallbackActionError(message: message, params: mapping.params)
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

            var errorParams: [String: String] = [
                "action":  action.rawValue,
                "status":  "error",
                "message": error.localizedDescription,
            ]
            if let callbackError = error as? CallbackActionError {
                errorParams.merge(callbackError.params, uniquingKeysWith: { current, _ in current })
            }

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

    // MARK: - Typed Result Mapping

    struct PullResponseMapping: Equatable {
        let params: [String: String]
        let errorMessage: String?
    }

    static func mapPullResult(_ pull: RepositoryPullResult) -> PullResponseMapping {
        switch pull {
        case .updated(_, let commitSHA):
            return .init(params: ["sha": commitSHA, "updated": "true"], errorMessage: nil)
        case .upToDate(_, let commitSHA):
            return .init(params: ["sha": commitSHA, "updated": "false"], errorMessage: nil)
        case .updatedWithAttention(_, let commitSHA, let attention):
            return .init(
                params: ["sha": commitSHA, "updated": "true"],
                errorMessage: attention.localizedDescription
            )
        case .blockedByLocalChanges:
            return .init(
                params: ["updated": "false"],
                errorMessage: String(localized: "Local changes need attention.")
            )
        case .diverged:
            return .init(
                params: ["updated": "false"],
                errorMessage: String(localized: "Local and remote history diverged.")
            )
        case .remoteBranchMissing(let branch):
            return .init(
                params: ["updated": "false"],
                errorMessage: String(localized: "Remote branch '\(branch)' was not found.")
            )
        case .wrongBranch(let expected, let actual):
            return .init(
                params: ["updated": "false"],
                errorMessage: String(localized: "Expected branch '\(expected)', but '\(actual)' is checked out.")
            )
        case .authenticationOrTrustRequired(let message, _),
             .unavailable(let message),
             .failed(let message):
            return .init(params: ["updated": "false"], errorMessage: message)
        }
    }

    static func mapPushResult(_ push: RepositoryPushResult) -> PullResponseMapping {
        switch push {
        case .pushed(let commitSHA):
            return .init(params: ["sha": commitSHA], errorMessage: nil)
        case .noChanges:
            return .init(params: [:], errorMessage: LocalGitError.noChanges.localizedDescription)
        case .blocked(let message),
             .authenticationOrTrustRequired(let message, _),
             .failed(let message):
            return .init(params: [:], errorMessage: message)
        case .commitSavedNotPushed(let commitSHA, let message, _):
            return .init(
                params: ["sha": commitSHA, "commit_saved": "true"],
                errorMessage: message
            )
        }
    }

    static func mapSyncResult(_ sync: RepositorySyncResult) -> PullResponseMapping {
        let pullUpdated: Bool
        switch sync.pull {
        case .updated, .updatedWithAttention: pullUpdated = true
        default: pullUpdated = false
        }
        var params = ["pull_updated": pullUpdated ? "true" : "false"]
        if let commitSHA = sync.push?.finalLocalCommitSHA ?? sync.pull?.newCommitSHA {
            params["sha"] = commitSHA
        }
        if case .commitSavedNotPushed = sync.push {
            params["commit_saved"] = "true"
        }

        switch sync.outcome {
        case .synced:
            return .init(params: params, errorMessage: nil)
        case .pushSkipped:
            params["push_skipped"] = "true"
            return .init(params: params, errorMessage: nil)
        case .blocked:
            return .init(params: params, errorMessage: sync.message)
        case .authenticationOrTrustRequired(let message, _), .failed(let message):
            return .init(params: params, errorMessage: message)
        }
    }

    // MARK: - Git Operations

    /// Carries an already-localized failure message and any completed-work
    /// metadata out to x-error (or the x-success fallback).
    private struct CallbackActionError: LocalizedError {
        let message: String
        let params: [String: String]
        init(message: String, params: [String: String] = [:]) {
            self.message = message
            self.params = params
        }
        var errorDescription: String? { message }
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
        let params = Self.queryDict(from: components)
        let errorURL = params["x-error"] ?? params["x-success"]
        let action = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var errorParams = [
            "status": "error",
            "message": message,
        ]
        if !action.isEmpty { errorParams["action"] = action }
        redirect(to: errorURL, params: errorParams)
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

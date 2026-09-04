import Foundation

/// MainActor locator that lets code running *in the app process* — App
/// Intents forwarded from widget/Control Center taps, deep links — reach the
/// live `PremiumRuntime` owned by `Sync_mdApp`.
///
/// `PullAllControlIntent` compiles into both the app and the widget
/// extension, but with `openAppWhenRun` the system only ever executes it
/// in the app process, where these references are populated.
@MainActor
enum SyncRuntimeLocator {
    private static weak var runtime: PremiumRuntime?
    private static weak var state: AppState?

    static func configure(runtime: PremiumRuntime, state: AppState) {
        self.runtime = runtime
        self.state = state
    }

    /// Runs an immediate, cooldown-bypassing reconciliation pass over all
    /// repositories — the same path as the in-app "Sync now" button.
    static func requestPullAll() {
        guard let runtime else {
            DebugLogger.shared.warning("pull-all", "PremiumRuntime unavailable; app locator was never configured")
            return
        }
        Task { @MainActor in
            await runtime.reconcileNow()
        }
    }

    /// Navigates the app to a repository (used by push-notification taps).
    static func reveal(repoID: UUID) {
        state?.callbackNavigateToRepoID = repoID
    }

    /// Current repositories for push registration and notification routing.
    static func currentRepos() -> [RepoConfig] {
        state?.repos ?? []
    }

    /// Navigates to the repo whose remote URL matches a GitHub `owner/name`,
    /// then runs an immediate pull-all — the response to a push-notification tap.
    static func handlePushNotificationTap(fullName: String?) {
        if let fullName,
           let repo = currentRepos().first(where: { repo in
               guard let remote = GitRemoteURL.parse(repo.repoURL), let owner = remote.ownerName else { return false }
               return "\(owner)/\(remote.repoName)".lowercased() == fullName.lowercased()
           }) {
            reveal(repoID: repo.id)
        }
        requestPullAll()
    }
}

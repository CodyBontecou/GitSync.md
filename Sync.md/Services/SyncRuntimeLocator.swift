import Foundation

/// MainActor locator that lets code running *in the app process* — App
/// Intents forwarded from widget/Control Center taps, deep links, and push
/// notification taps — reach the live `AppState` owned by `Sync_mdApp`.
///
/// `PullAllControlIntent` compiles into both the app and the widget
/// extension, but with `openAppWhenRun` the system only ever executes it
/// in the app process, where this reference is populated.
@MainActor
enum SyncRuntimeLocator {
    private static weak var state: AppState?

    static func configure(state: AppState) {
        self.state = state
    }

    /// Runs an explicit pull-only pass over all cloned repositories. Widget,
    /// Control Center, and notification taps are user actions, so they remain
    /// available even when automatic Background Sync is disabled and can never
    /// inherit automatic-push consent.
    static func requestPullAll() {
        guard let state else {
            DebugLogger.shared.warning("pull-all", "AppState unavailable; app locator was never configured")
            return
        }
        Task { @MainActor in
            for repo in state.repos where repo.isCloned {
                _ = await state.pullOnly(repoID: repo.id, showsProgressDelay: false)
            }
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

    /// Navigates to and explicitly pulls the repository named by a Push Sync
    /// alert. If an older payload has no routable repository, fall back to the
    /// existing pull-all action.
    static func handlePushNotificationTap(fullName: String?) {
        guard let fullName,
              let repo = matchingRepository(fullName: fullName) else {
            requestPullAll()
            return
        }
        reveal(repoID: repo.id)
        guard let state else { return }
        Task { @MainActor in
            _ = await state.pullOnly(repoID: repo.id, showsProgressDelay: false)
        }
    }

    private static func matchingRepository(fullName: String) -> RepoConfig? {
        currentRepos().first { repo in
            guard let canonical = GitRemoteURL.parse(repo.repoURL)?.canonicalGitHubFullName else {
                return false
            }
            return canonical.caseInsensitiveCompare(fullName) == .orderedSame
        }
    }
}

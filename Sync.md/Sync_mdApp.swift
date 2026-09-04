import AppIntents
import SwiftUI

#if DEBUG
extension Sync_mdApp {
    static func resetPersistedAuthAndReposForUITest() {
        let defaults = UserDefaults.standard
        if let accountsData = defaults.data(forKey: "gitHubAccounts"),
           let accounts = try? JSONDecoder().decode([GitHubAccount].self, from: accountsData) {
            for account in accounts {
                KeychainService.delete(key: "github_pat_\(account.login.lowercased())")
            }
        }
        KeychainService.delete(key: "github_pat")
        for key in [
            "gitHubUsername", "gitHubDisplayName", "gitHubAvatarURL",
            "gitHubAccounts", "activeGitHubAccountLogin",
            "hasSeenOnboarding", "hasCompletedOnboarding",
        ] {
            defaults.removeObject(forKey: key)
        }
        if FileManager.default.fileExists(atPath: AppState.persistedReposFileURL.path) {
            try? FileManager.default.removeItem(at: AppState.persistedReposFileURL)
        }
        // Reset the Keychain-backed "previously cloned" history too: suite runs
        // that clone a repo (e.g. the local-git-fixture clone UI test) record
        // its identifier, which would otherwise surface as a ghost card and
        // replace the deterministic "No Repositories" empty state in later
        // signed-out launches.
        for identifier in RepositoryHistoryStore.shared.seenRepoIdentifiers() {
            RepositoryHistoryStore.shared.forgetSeenRepoIdentifier(identifier)
        }
    }
}
#endif

@MainActor
@main
struct Sync_mdApp: App {
    @UIApplicationDelegateAdaptor(SyncAppDelegate.self) private var appDelegate
    @State private var appState: AppState
    @State private var premiumRuntime: PremiumRuntime
    @State private var assistForegroundReconciliationTask: Task<Void, Never>? = nil
    @State private var pushRegistrationTask: Task<Void, Never>? = nil
    @Environment(\.scenePhase) private var scenePhase

    init() {
        #if DEBUG
        // Deterministic signed-out starting state for UI regression tests
        // (SyncMDUITests). Never compiled into release builds.
        if ProcessInfo.processInfo.arguments.contains("-SignedOutUITest") {
            Self.resetPersistedAuthAndReposForUITest()
        }
        #endif
        let appState = AppState()
        let coordinator = BackgroundSyncCoordinator(
            repositoryProvider: appState,
            conditionsProvider: SystemBackgroundSyncConditions()
        )
        _appState = State(initialValue: appState)
        let runtime = PremiumRuntime(
            coordinator: coordinator,
            repositoryProvider: appState
        )
        _premiumRuntime = State(initialValue: runtime)
        SyncRuntimeLocator.configure(runtime: runtime, state: appState)
        if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil {
            SyncMDAppShortcutsProvider.updateAppShortcutParameters()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(appState)
                .environment(premiumRuntime)
                .task {
                    if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil {
                        if FeatureFlags.gitSyncAssistEnabled {
                            assistForegroundReconciliationTask = Task { @MainActor in
                                await premiumRuntime.reconcileForeground()
                            }
                        }
                    }
                    #if DEBUG
                    // Allow injecting a GitHub PAT via environment variable for simulator testing.
                    // Launch with: SIMCTL_CHILD_INJECT_PAT=ghp_xxx xcrun simctl launch booted <bundle-id>
                    if let injectedPAT = ProcessInfo.processInfo.environment["INJECT_PAT"],
                       !injectedPAT.isEmpty,
                       appState.pat.isEmpty {
                        await appState.signInWithPAT(token: injectedPAT)
                    }
                    #endif
                }
                .onOpenURL { url in
                    // Widget deep link: one-tap full pull from the Home Screen.
                    if url.scheme == "syncmd", url.host == "pull-all" {
                        SyncRuntimeLocator.requestPullAll()
                        return
                    }
                    // x-callback-url from external triggers (e.g. a link tapped in Obsidian, or an iOS Shortcut)
                    // Format: syncmd://x-callback-url/<action>?repo=<name>&x-success=<url>
                    let handler = CallbackURLHandler(appState: appState)
                    if handler.canHandle(url) {
                        handler.handle(url)
                    }
                }
        }
        .onChange(of: scenePhase) { oldPhase, newPhase in
            if newPhase == .active {
                #if DEBUG
                guard !MarketingCapture.usesSeededData else { return }
                #endif

                // Re-validate repos when returning to foreground —
                // the user may have deleted files via the Files app.
                appState.validateClonedRepos()

                // Refresh change counts for repos that are still cloned, but
                // avoid immediately re-scanning large vaults every time the app
                // bounces through inactive/active (Control Center, app switcher).
                appState.refreshClonedRepos(deferredBy: 0.5, skipIfRecentlyStartedWithin: 15)
                if FeatureFlags.gitSyncAssistEnabled {
                    // Never cancel-and-restart here: the runtime coalesces a
                    // running pass and applies a short bounce cooldown.
                    assistForegroundReconciliationTask = Task { @MainActor in
                        await premiumRuntime.reconcileForeground()
                    }
                }
                // Keep push-sync registration in sync with the current repo set.
                pushRegistrationTask?.cancel()
                pushRegistrationTask = Task { @MainActor in
                    await PushSyncManager.shared.refreshRegistration(repos: appState.repos)
                }
            } else {
                assistForegroundReconciliationTask?.cancel()
                assistForegroundReconciliationTask = nil
                premiumRuntime.cancelForegroundReconciliation()
            }
        }
    }
}

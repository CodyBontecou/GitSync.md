import AppIntents
import SwiftUI

enum AssistLinkCompletionURL {
    static func matches(_ url: URL) -> Bool {
        url.scheme == "syncmd"
            && url.host == "assist-linked"
            && url.user == nil
            && url.password == nil
            && url.port == nil
            && url.query == nil
            && url.fragment == nil
            && (url.path.isEmpty || url.path == "/")
    }
}

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
    }
}
#endif

@MainActor
@main
struct Sync_mdApp: App {
    @UIApplicationDelegateAdaptor(SyncMDApplicationDelegate.self) private var applicationDelegate
    @State private var appState: AppState
    @State private var entitlementStore: PremiumEntitlementStore
    @State private var premiumRuntime: PremiumRuntime
    @State private var assistForegroundReconciliationTask: Task<Void, Never>? = nil
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
        let entitlementStore = PremiumEntitlementStore()
        let coordinator = BackgroundSyncCoordinator(
            entitlementIsActive: { entitlementStore.state.isActive },
            repositoryProvider: appState,
            conditionsProvider: SystemBackgroundSyncConditions()
        )
        _appState = State(initialValue: appState)
        _entitlementStore = State(initialValue: entitlementStore)
        _premiumRuntime = State(initialValue: PremiumRuntime(
            entitlementStore: entitlementStore,
            coordinator: coordinator,
            repositoryProvider: appState
        ))
        if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil {
            SyncMDAppShortcutsProvider.updateAppShortcutParameters()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(appState)
                .environment(entitlementStore)
                .environment(premiumRuntime)
                .task {
                    if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil {
                        // Terminal deletion is installation safety state, not an
                        // enabled-feature path. Retry it even in a release where
                        // Assist remains gated off.
                        await premiumRuntime.recoverPendingDeletion()
                        guard !Task.isCancelled else { return }
                        if FeatureFlags.gitSyncAssistEnabled {
                            await premiumRuntime.start()
                            guard !Task.isCancelled else { return }
                            assistForegroundReconciliationTask?.cancel()
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
                    if FeatureFlags.gitSyncAssistEnabled, AssistLinkCompletionURL.matches(url) {
                        Task { @MainActor in
                            await premiumRuntime.prepareForSettings()
                        }
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
                    assistForegroundReconciliationTask?.cancel()
                    assistForegroundReconciliationTask = Task { @MainActor in
                        await premiumRuntime.reconcileForeground()
                    }
                }
            } else {
                assistForegroundReconciliationTask?.cancel()
                assistForegroundReconciliationTask = nil
                premiumRuntime.cancelForegroundReconciliation()
            }
        }
    }
}

import AppIntents
import SwiftUI

@MainActor
@main
struct Sync_mdApp: App {
    @State private var appState = AppState()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        if ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil {
            SyncMDAppShortcutsProvider.updateAppShortcutParameters()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(appState)
                .task {
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
                    // x-callback-url from external apps (e.g. Obsidian plugin)
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
                guard !MarketingCapture.isActive else { return }
                #endif

                // Re-validate repos when returning to foreground —
                // the user may have deleted files via the Files app.
                appState.validateClonedRepos()

                // Refresh change counts for repos that are still cloned, but
                // avoid immediately re-scanning large vaults every time the app
                // bounces through inactive/active (Control Center, app switcher).
                appState.refreshClonedRepos(deferredBy: 0.5, skipIfRecentlyStartedWithin: 15)
            }
        }
    }
}

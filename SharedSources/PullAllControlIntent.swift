import AppIntents

/// Shared App Intent used by the Control Center control (and any future
/// widget button).
///
/// Because repositories live in the app's Documents directory (not an App
/// Group), the intent cannot do git work from inside the widget extension
/// process. It sets `openAppWhenRun`, so the system launches GitSync.md and
/// performs the intent **in the app process**, where full data access and the
/// live `PremiumRuntime` are available.
///
/// The widget target compiles this file with `WIDGET_EXTENSION` defined; that
/// copy exists only so the extension can reference the type — the system
/// always forwards execution to the app before `perform()` runs.
struct PullAllControlIntent: AppIntent {
    static var title: LocalizedStringResource = "Pull All Repositories"
    static var description = IntentDescription("Fetch and fast-forward every cloned repository now.")
    static var openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        #if !WIDGET_EXTENSION
        SyncRuntimeLocator.requestPullAll()
        #endif
        return .result()
    }
}

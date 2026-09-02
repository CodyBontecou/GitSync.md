import Foundation

/// Compile-time feature availability switches.
enum FeatureFlags {
    /// Whether Background Sync and its best-effort updates are visible and
    /// active in this build.
    ///
    /// While `false`, every Background Sync entry point is hidden: the App
    /// Settings control, per-repository exclusion policy, sync health
    /// indicators, and the Background Sync runtime (scheduling and
    /// reconciliation). Existing manual Git, Shortcuts, callback, and local
    /// repository features are unaffected.
    ///
    /// Background Sync is part of the paid-up-front app — there is no
    /// subscription tier. Because this is a compile-time constant, Swift
    /// dead-code eliminates the disabled branches from Release builds.
    static let gitSyncAssistEnabled = true
}

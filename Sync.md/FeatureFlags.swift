import Foundation

/// Compile-time feature availability switches.
enum FeatureFlags {
    /// Whether the GitSync Assist subscription tier and its pull-only
    /// background sync are visible and active in this build.
    ///
    /// While `false`, every Assist entry point is hidden: the App Settings
    /// subscription row, the per-repository automation section, Assist health
    /// indicators, and premium runtime startup (entitlement listener, APNs
    /// registration, relay reconciliation). Existing manual Git, Shortcuts,
    /// callback, and local repository features are unaffected.
    ///
    /// Because this is a compile-time constant, Swift dead-code eliminates the
    /// disabled branches from Release builds. Flip to `true` when the tier is
    /// ready to ship alongside its relay and App Store Connect subscription
    /// state.
    static let gitSyncAssistEnabled = false
}

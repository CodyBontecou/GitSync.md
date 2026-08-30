import Foundation

/// Compile-time feature availability switches.
enum FeatureFlags {
    /// Whether the Background Sync subscription tier and its pull-only
    /// updates are visible and active in this build.
    ///
    /// While `false`, every Background Sync entry point is hidden: the App Settings
    /// subscription/global control, per-repository exclusion policy, sync health
    /// indicators, and premium runtime startup (entitlement listener, APNs
    /// registration, relay reconciliation). Existing manual Git, Shortcuts,
    /// callback, and local repository features are unaffected.
    ///
    /// Because this is a compile-time constant, Swift dead-code eliminates the
    /// disabled branches from Release builds. Flip to `true` when the tier is
    /// ready to ship alongside its relay and App Store Connect subscription
    /// state.
    static let gitSyncAssistEnabled = true
}

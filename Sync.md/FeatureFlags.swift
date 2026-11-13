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

    /// Whether adding a vault requires the one-time unlock (`AppUnlock`).
    ///
    /// Turn on only in the build that ships with the App Store price set to
    /// free, and set `AppUnlock.firstFreeBuild` to that build's number.
    /// While the app is still paid up front, a new buyer's receipt would name
    /// a build at or above the cutoff and they would be asked to pay twice.
    static let hardPaywallEnabled = true
}

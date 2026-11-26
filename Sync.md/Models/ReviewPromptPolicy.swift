import Foundation

/// When GitSync.md may ask for an App Store rating.
///
/// Successful syncs are counted (foreground and background alike). Once the
/// person has synced at least `minimumSuccesses` times on `minimumDistinctDays`
/// different days, the next good foreground moment may show the system prompt.
/// After a prompt, a later app version may ask again once `cooldown` has passed.
///
/// The Android port (`android/.../review/ReviewPromptPolicy.kt`) uses the same
/// constants; change both together.
struct ReviewPromptPolicy {
    static let minimumSuccesses = 5
    static let minimumDistinctDays = 3
    /// Days between prompts, and the version must have changed too.
    static let cooldown: TimeInterval = 120 * 24 * 60 * 60
    /// One sync can report a pull and a push; successes this close count once.
    static let successSpacing: TimeInterval = 60
    /// No prompt this soon after the unlock purchase.
    static let purchaseQuietPeriod: TimeInterval = 24 * 60 * 60
    /// The pre-2026-11 key: set once, the prompt never came back.
    static let legacyRequestedKey = "hasRequestedReview"

    enum Key {
        static let successCount = "reviewPrompt.successCount"
        static let distinctDays = "reviewPrompt.distinctDays"
        static let lastSuccessDay = "reviewPrompt.lastSuccessDay"
        static let lastCountedAt = "reviewPrompt.lastCountedAt"
        static let lastPromptAt = "reviewPrompt.lastPromptAt"
        static let lastPromptVersion = "reviewPrompt.lastPromptVersion"
        static let lastPurchaseAt = "reviewPrompt.lastPurchaseAt"
    }

    let defaults: UserDefaults
    var calendar: Calendar = .current

    init(defaults: UserDefaults = .standard, calendar: Calendar = .current) {
        self.defaults = defaults
        self.calendar = calendar
    }

    static var currentAppVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    var successCount: Int { defaults.integer(forKey: Key.successCount) }
    var distinctDays: Int { defaults.integer(forKey: Key.distinctDays) }
    var lastPromptAt: Date? { defaults.object(forKey: Key.lastPromptAt) as? Date }
    var lastPromptVersion: String? { defaults.string(forKey: Key.lastPromptVersion) }

    /// Users asked under the old one-time key start their cooldown now. The
    /// version they were asked on is unknown, so only the cooldown applies.
    func migrateLegacyKeyIfNeeded(now: Date = Date()) {
        guard defaults.bool(forKey: Self.legacyRequestedKey) else { return }
        if lastPromptAt == nil {
            defaults.set(now, forKey: Key.lastPromptAt)
            defaults.removeObject(forKey: Key.lastPromptVersion)
        }
        defaults.removeObject(forKey: Self.legacyRequestedKey)
    }

    /// Counts one successful sync. Returns `false` when it fell inside
    /// `successSpacing` of the previous one and was not counted.
    @discardableResult
    func recordSuccess(now: Date = Date()) -> Bool {
        if let last = defaults.object(forKey: Key.lastCountedAt) as? Date,
           now >= last, now.timeIntervalSince(last) < Self.successSpacing {
            return false
        }
        defaults.set(now, forKey: Key.lastCountedAt)
        defaults.set(successCount + 1, forKey: Key.successCount)
        let day = dayString(now)
        if defaults.string(forKey: Key.lastSuccessDay) != day {
            defaults.set(day, forKey: Key.lastSuccessDay)
            defaults.set(distinctDays + 1, forKey: Key.distinctDays)
        }
        return true
    }

    func notePurchase(now: Date = Date()) {
        defaults.set(now, forKey: Key.lastPurchaseAt)
    }

    func isEligible(now: Date = Date(), appVersion: String = Self.currentAppVersion) -> Bool {
        guard successCount >= Self.minimumSuccesses,
              distinctDays >= Self.minimumDistinctDays else { return false }
        if let purchase = defaults.object(forKey: Key.lastPurchaseAt) as? Date,
           now.timeIntervalSince(purchase) < Self.purchaseQuietPeriod {
            return false
        }
        guard let lastPromptAt else { return true }
        if let lastPromptVersion, lastPromptVersion == appVersion { return false }
        return now.timeIntervalSince(lastPromptAt) >= Self.cooldown
    }

    func markPrompted(now: Date = Date(), appVersion: String = Self.currentAppVersion) {
        defaults.set(now, forKey: Key.lastPromptAt)
        defaults.set(appVersion, forKey: Key.lastPromptVersion)
    }

    private func dayString(_ date: Date) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }
}

/// The foreground moments that may show the rating prompt.
enum ReviewPromptMoment: Equatable, Sendable {
    /// A pull, push or sync the person started succeeded.
    case userSync
    /// The person dismissed a healthy background-sync summary.
    case backgroundSummaryDismissed
    /// A merge conflict was resolved and pushed with nothing left conflicting.
    case conflictResolved
}

/// Marks work the person started from the app's own UI. Only syncs run inside
/// `userStarted` may lead to a prompt; Shortcuts, x-callback, widgets, Control
/// Center, background runs, offline replay, re-auth and Undo push never set it.
enum ReviewPromptContext {
    @TaskLocal static var isUserStarted = false

    static func userStarted<T>(_ operation: () async -> T) async -> T {
        await $isUserStarted.withValue(true) { await operation() }
    }

    static func notUserStarted<T>(_ operation: () async -> T) async -> T {
        await $isUserStarted.withValue(false) { await operation() }
    }
}

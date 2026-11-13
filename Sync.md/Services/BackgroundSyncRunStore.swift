import Foundation
import Observation

/// One Background Sync pass: when it ran, what woke it, and what happened to
/// each vault it touched. Persisted as a small ring buffer for the
/// "Recent runs" list and the Upgrade sheet's last-run stamp.
struct BackgroundSyncRunRecord: Codable, Identifiable, Equatable, Sendable {
    enum Trigger: String, Codable, Sendable {
        /// BGAppRefreshTask / BGProcessingTask.
        case scheduled
        /// APNs wake from Push Sync.
        case push
        /// Foreground pass when the app became active.
        case foreground
    }

    struct VaultOutcome: Codable, Equatable, Sendable {
        enum Kind: String, Codable, Sendable {
            case pulled
            case pushed
            case pulledAndPushed
            case nothingNew
            case skipped
            case error
        }

        let repoName: String
        let kind: Kind
        /// Reason for `skipped`, message for `error`.
        let detail: String?

        var pulled: Bool { kind == .pulled || kind == .pulledAndPushed }
        var pushed: Bool { kind == .pushed || kind == .pulledAndPushed }
    }

    let id: UUID
    let date: Date
    let trigger: Trigger
    let duration: TimeInterval
    /// Execution budget iOS granted, when known (≈30s for refresh and APNs wakes).
    let budget: TimeInterval?
    let outcomes: [VaultOutcome]

    init(id: UUID = UUID(), date: Date, trigger: Trigger, duration: TimeInterval,
         budget: TimeInterval?, outcomes: [VaultOutcome]) {
        self.id = id
        self.date = date
        self.trigger = trigger
        self.duration = duration
        self.budget = budget
        self.outcomes = outcomes
    }

    var pulledCount: Int { outcomes.filter(\.pulled).count }
    var pushedCount: Int { outcomes.filter(\.pushed).count }
    var errorCount: Int { outcomes.filter { $0.kind == .error }.count }
    var skippedCount: Int { outcomes.filter { $0.kind == .skipped }.count }
    var didTransferData: Bool { pulledCount + pushedCount > 0 }

    /// "pulled 2 · pushed 1", "4 vaults, nothing new", "notes skipped: local edits".
    var summary: String {
        var parts: [String] = []
        if pulledCount > 0 { parts.append(String(localized: "pulled \(pulledCount)")) }
        if pushedCount > 0 { parts.append(String(localized: "pushed \(pushedCount)")) }
        for outcome in outcomes where outcome.kind == .skipped || outcome.kind == .error {
            let reason = outcome.detail.map(Self.shortReason) ?? ""
            if outcome.kind == .skipped {
                parts.append(reason.isEmpty
                             ? String(localized: "\(outcome.repoName) skipped")
                             : String(localized: "\(outcome.repoName) skipped: \(reason)"))
            } else {
                parts.append(reason.isEmpty
                             ? String(localized: "\(outcome.repoName) failed")
                             : String(localized: "\(outcome.repoName) error: \(reason)"))
            }
        }
        if parts.isEmpty {
            if outcomes.isEmpty { return String(localized: "no vaults included") }
            return outcomes.count == 1
                ? String(localized: "\(outcomes[0].repoName), nothing new")
                : String(localized: "\(outcomes.count) vaults, nothing new")
        }
        return parts.joined(separator: " · ")
    }

    /// Single-vault transfer lines read better with the vault name ("notes ↓ ↑").
    var compactSummary: String {
        if outcomes.count == 1, didTransferData, let only = outcomes.first {
            let arrows = [only.pulled ? "↓" : nil, only.pushed ? "↑" : nil].compactMap { $0 }.joined(separator: " ")
            return "\(only.repoName) \(arrows)"
        }
        return summary
    }

    var tint: Status {
        if errorCount > 0 { return .error }
        if skippedCount > 0 { return .attention }
        return didTransferData ? .transferred : .idle
    }

    enum Status { case transferred, idle, attention, error }

    /// First clause of a long Git message, lowercased for the compact list.
    static func shortReason(_ message: String) -> String {
        let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
        let firstClause = trimmed.split(whereSeparator: { ".\n".contains($0) }).first.map(String.init) ?? trimmed
        let clipped = firstClause.count > 48 ? String(firstClause.prefix(47)) + "…" : firstClause
        guard let first = clipped.first else { return clipped }
        return first.lowercased() + clipped.dropFirst()
    }
}

/// Ring buffer of the most recent Background Sync runs, persisted in
/// UserDefaults. Written by `PremiumRuntime` at the end of every background
/// (and every eventful foreground) pass.
@MainActor
@Observable
final class BackgroundSyncRunStore {
    static let shared = BackgroundSyncRunStore()

    static let capacity = 20
    private static let storageKey = "background-sync.recent-runs.v1"

    /// Newest first.
    private(set) var runs: [BackgroundSyncRunRecord] = []
    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: Self.storageKey),
           let decoded = try? JSONDecoder().decode([BackgroundSyncRunRecord].self, from: data) {
            runs = decoded
        }
    }

    var lastRun: BackgroundSyncRunRecord? { runs.first }
    var lastTransferringRun: BackgroundSyncRunRecord? { runs.first(where: \.didTransferData) }

    func record(_ run: BackgroundSyncRunRecord) {
        runs.insert(run, at: 0)
        if runs.count > Self.capacity { runs.removeLast(runs.count - Self.capacity) }
        if let data = try? JSONEncoder().encode(runs) {
            defaults.set(data, forKey: Self.storageKey)
        }
    }

    func clear() {
        runs.removeAll()
        defaults.removeObject(forKey: Self.storageKey)
    }
}

extension BackgroundSyncRunRecord.VaultOutcome {
    /// Maps a coordinator disposition to a list outcome. `nil` for ignored work.
    init?(repoName: String, disposition: BackgroundSyncDisposition) {
        switch disposition {
        case .ignored:
            return nil
        case .deferred(let message):
            self.init(repoName: repoName, kind: .skipped, detail: message)
        case .completed(let result):
            switch result.outcome {
            case .upToDate: self.init(repoName: repoName, kind: .nothingNew, detail: nil)
            case .pulled: self.init(repoName: repoName, kind: .pulled, detail: nil)
            case .pushed: self.init(repoName: repoName, kind: .pushed, detail: nil)
            case .pulledAndPushed: self.init(repoName: repoName, kind: .pulledAndPushed, detail: nil)
            case .blocked:
                self.init(repoName: repoName, kind: .skipped,
                          detail: result.message ?? String(localized: "Needs attention"))
            case .authenticationOrTrustRequired:
                self.init(repoName: repoName, kind: .skipped,
                          detail: result.message ?? String(localized: "Sign-in or trust needed"))
            case .failed:
                self.init(repoName: repoName, kind: .error,
                          detail: result.message ?? String(localized: "Background Sync failed."))
            }
        }
    }
}

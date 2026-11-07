import Foundation
#if canImport(ActivityKit)
import ActivityKit
#endif

/// App Group shared between GitSync.md and its widget extension. The group
/// must be registered for both bundle IDs in the Apple Developer portal; when
/// it is unavailable (`containerURL` nil) widgets fall back to placeholders.
enum SyncAppGroup {
    static let identifier = "group.bontecou.Sync-md"

    static var defaults: UserDefaults? { UserDefaults(suiteName: identifier) }
}

// MARK: - Vault status snapshot

/// Compact, widget-friendly state of one vault. Written by the app whenever
/// status changes; read by Home Screen / Lock Screen widgets.
struct VaultStatusSnapshot: Codable, Hashable, Identifiable, Sendable {
    enum State: String, Codable, Sendable {
        case inSync, ahead, behind, diverged, paused, syncing, notCloned, unknown
    }

    let id: UUID
    var name: String
    var owner: String?
    var branch: String
    /// Local commits not yet on the remote.
    var ahead: Int
    /// Remote commits not yet on this iPhone.
    var behind: Int
    /// Uncommitted local file changes.
    var changes: Int
    var state: State
    var lastSync: Date?

    /// Everything waiting to go up: unpushed commits + uncommitted changes.
    var toPush: Int { ahead + changes }
}

struct BackgroundSyncSnapshot: Codable, Hashable, Sendable {
    /// Whether Background Sync exists in this build at all.
    var available: Bool
    var enabled: Bool
    var pull: Bool
    var push: Bool

    static let off = BackgroundSyncSnapshot(available: false, enabled: false, pull: false, push: false)
}

struct SyncStatusSnapshot: Codable, Hashable, Sendable {
    var vaults: [VaultStatusSnapshot]
    var updatedAt: Date
    var backgroundSync: BackgroundSyncSnapshot

    static let empty = SyncStatusSnapshot(vaults: [], updatedAt: .distantPast, backgroundSync: .off)

    var inSyncCount: Int { vaults.filter { $0.state == .inSync }.count }
    var aheadCount: Int { vaults.filter { $0.toPush > 0 }.count }
    var behindCount: Int { vaults.filter { $0.behind > 0 }.count }
    /// Most recent successful sync across all vaults.
    var lastSync: Date? { vaults.compactMap(\.lastSync).max() }
}

enum SyncStatusSnapshotStore {
    private static let snapshotKey = "sync.status-snapshot.v1"
    private static let pendingBackgroundSyncKey = "sync.pending-background-sync-toggle.v1"

    static func read() -> SyncStatusSnapshot {
        guard let data = SyncAppGroup.defaults?.data(forKey: snapshotKey),
              let snapshot = try? JSONDecoder().decode(SyncStatusSnapshot.self, from: data) else {
            return .empty
        }
        return snapshot
    }

    /// Returns true when the stored bytes changed.
    @discardableResult
    static func write(_ snapshot: SyncStatusSnapshot) -> Bool {
        guard let defaults = SyncAppGroup.defaults,
              let data = try? JSONEncoder().encode(snapshot) else { return false }
        if let old = defaults.data(forKey: snapshotKey),
           let decoded = try? JSONDecoder().decode(SyncStatusSnapshot.self, from: old),
           decoded.vaults == snapshot.vaults, decoded.backgroundSync == snapshot.backgroundSync {
            return false
        }
        defaults.set(data, forKey: snapshotKey)
        return true
    }

    /// A Background Sync toggle requested from Control Center while the app
    /// process was not running. The app applies and clears it on activation.
    static var pendingBackgroundSyncToggle: Bool? {
        get { (SyncAppGroup.defaults?.object(forKey: pendingBackgroundSyncKey) as? NSNumber)?.boolValue }
        set {
            if let newValue { SyncAppGroup.defaults?.set(newValue, forKey: pendingBackgroundSyncKey) }
            else { SyncAppGroup.defaults?.removeObject(forKey: pendingBackgroundSyncKey) }
        }
    }

    /// Optimistically flip the mirrored master switch so the control reflects
    /// the tap immediately.
    static func setMirroredBackgroundSync(enabled: Bool) {
        var snapshot = read()
        snapshot.backgroundSync.enabled = enabled
        if enabled, !snapshot.backgroundSync.pull, !snapshot.backgroundSync.push {
            snapshot.backgroundSync.pull = true
        }
        if let data = try? JSONEncoder().encode(snapshot) {
            SyncAppGroup.defaults?.set(data, forKey: snapshotKey)
        }
    }
}

// MARK: - Live Activity

#if canImport(ActivityKit)
/// Lock Screen / Dynamic Island activity for a foreground push, pull, sync
/// or clone that keeps going after the user leaves the screen.
struct SyncActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// "Pushing…", "Pulling…", "Pushed", "Couldn't push"
        var title: String
        /// "2 of 3 commits sent", "Upload to origin/main"
        var detail: String
        /// 0...1 when known.
        var fraction: Double?
        /// "412 KB / 610 KB"
        var bytes: String?
        /// "~3s left"
        var eta: String?
        var direction: Direction
        var finished: Bool
        var failed: Bool
    }

    enum Direction: String, Codable, Hashable {
        case push, pull, sync, clone
    }

    var vaultName: String
    var branch: String
    var repoID: UUID
}
#endif

import AppIntents
import SwiftUI
import WidgetKit

/// Control Center toggle for Background Sync, showing "Pull on · Push off".
@available(iOS 18.0, *)
struct BackgroundSyncControl: ControlWidget {
    static let kind = "com.bontecou.Sync-md.background-sync"

    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Self.kind, provider: BackgroundSyncValueProvider()) { value in
            ControlWidgetToggle(
                String(localized: "Background Sync"),
                isOn: value.enabled,
                action: BackgroundSyncToggleIntent()
            ) { isOn in
                Label(value.statusText(isOn: isOn), systemImage: isOn ? "arrow.triangle.2.circlepath" : "pause.circle")
            }
            .tint(.green)
        }
        .displayName("Background Sync")
        .description("Turn Background Sync on or off.")
    }
}

@available(iOS 18.0, *)
struct BackgroundSyncControlValue {
    let enabled: Bool
    let snapshot: BackgroundSyncSnapshot

    func statusText(isOn: Bool) -> String {
        guard snapshot.available else { return String(localized: "Not available") }
        guard isOn else { return String(localized: "Off") }
        let pull = snapshot.pull ? String(localized: "Pull on") : String(localized: "Pull off")
        let push = snapshot.push ? String(localized: "Push on") : String(localized: "Push off")
        return "\(pull) · \(push)"
    }
}

@available(iOS 18.0, *)
struct BackgroundSyncValueProvider: ControlValueProvider {
    var previewValue: BackgroundSyncControlValue {
        BackgroundSyncControlValue(
            enabled: true,
            snapshot: BackgroundSyncSnapshot(available: true, enabled: true, pull: true, push: false)
        )
    }

    func currentValue() async throws -> BackgroundSyncControlValue {
        let snapshot = SyncStatusSnapshotStore.read().backgroundSync
        return BackgroundSyncControlValue(enabled: snapshot.enabled, snapshot: snapshot)
    }
}

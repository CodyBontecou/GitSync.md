import AppIntents
import SwiftUI
import WidgetKit

/// Control Center control (iOS 18+). Tapping runs `PullAllControlIntent`,
/// which opens the app straight into a full reconciliation pass — the same
/// path as the "Sync now" button, including its cooldown bypass.
@available(iOS 18.0, *)
struct PullAllControl: ControlWidget {
    static let kind = "com.bontecou.Sync-md.pull-all"

    @available(iOS 18.0, *)
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Self.kind) {
            ControlWidgetButton(action: PullAllControlIntent()) {
                Label("Pull All", systemImage: "arrow.down.circle.fill")
            }
        }
    }
}

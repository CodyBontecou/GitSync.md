import SwiftUI
import WidgetKit

@main
struct SyncWidgetBundle: WidgetBundle {
    var body: some Widget {
        PullAllWidget()
        AllVaultsStatusWidget()
        SingleVaultWidget()
        VaultListWidget()
        SyncLiveActivityWidget()
        if #available(iOS 18.0, *) {
            PullAllControl()
            BackgroundSyncControl()
        }
    }
}

import SwiftUI
import WidgetKit

@main
struct SyncWidgetBundle: WidgetBundle {
    var body: some Widget {
        PullAllWidget()
        if #available(iOS 18.0, *) {
            PullAllControl()
        }
    }
}

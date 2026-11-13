import ActivityKit
import SwiftUI
import WidgetKit

/// Lock Screen + Dynamic Island presentation of a running sync.
struct SyncLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SyncActivityAttributes.self) { context in
            SyncLiveActivityLockScreen(context: context)
                .activityBackgroundTint(WidgetPalette.tile)
                .activitySystemActionForegroundColor(WidgetPalette.paper)
                .widgetURL(URL(string: "syncmd://vault/\(context.attributes.repoID.uuidString)"))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label {
                        Text(context.attributes.vaultName).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                    } icon: {
                        Image(systemName: icon(context.state))
                    }
                    .foregroundStyle(tint(context.state))
                }
                DynamicIslandExpandedRegion(.trailing) {
                    if let fraction = context.state.fraction {
                        Text(verbatim: "\(Int((fraction * 100).rounded()))%")
                            .font(.system(size: 14, weight: .semibold, design: .monospaced))
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(context.state.title).font(.system(size: 15, weight: .semibold))
                            Text(context.state.detail).font(.system(size: 13)).foregroundStyle(.secondary).lineLimit(1)
                            Spacer()
                            if let eta = context.state.eta {
                                Text(eta).font(.system(size: 12, design: .monospaced)).foregroundStyle(.secondary)
                            }
                        }
                        if let fraction = context.state.fraction {
                            ProgressView(value: fraction).tint(tint(context.state))
                        }
                    }
                }
            } compactLeading: {
                Image(systemName: icon(context.state)).foregroundStyle(tint(context.state))
            } compactTrailing: {
                if let fraction = context.state.fraction, !context.state.finished {
                    Text(verbatim: "\(Int((fraction * 100).rounded()))%")
                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                } else {
                    Image(systemName: context.state.failed ? "xmark" : "checkmark")
                        .foregroundStyle(tint(context.state))
                }
            } minimal: {
                Image(systemName: icon(context.state)).foregroundStyle(tint(context.state))
            }
            .widgetURL(URL(string: "syncmd://vault/\(context.attributes.repoID.uuidString)"))
        }
    }

    private func icon(_ state: SyncActivityAttributes.ContentState) -> String {
        if state.failed { return "exclamationmark.triangle.fill" }
        if state.finished { return "checkmark.circle.fill" }
        switch state.direction {
        case .push: return "arrow.up.circle.fill"
        case .pull, .clone: return "arrow.down.circle.fill"
        case .sync: return "arrow.triangle.2.circlepath.circle.fill"
        }
    }

    private func tint(_ state: SyncActivityAttributes.ContentState) -> Color {
        if state.failed { return .red }
        if state.finished { return WidgetPalette.syncedOnDark }
        switch state.direction {
        case .push, .sync: return WidgetPalette.aheadOnDark
        case .pull, .clone: return Color(red: 0x7C / 255, green: 0x95 / 255, blue: 0xFF / 255)
        }
    }
}

private struct SyncLiveActivityLockScreen: View {
    let context: ActivityViewContext<SyncActivityAttributes>

    private var accent: Color {
        if context.state.failed { return Color(red: 1, green: 0x7A / 255, blue: 0x6B / 255) }
        if context.state.finished { return WidgetPalette.syncedOnDark }
        switch context.state.direction {
        case .push, .sync: return WidgetPalette.aheadOnDark
        case .pull, .clone: return Color(red: 0x7C / 255, green: 0x95 / 255, blue: 0xFF / 255)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text(context.attributes.vaultName)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(WidgetPalette.meta)
                Text(verbatim: "· \(context.attributes.branch)")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(WidgetPalette.meta)
                Spacer()
                (Text(verbatim: "gitsync").foregroundColor(WidgetPalette.meta)
                    + Text(verbatim: ".md").foregroundColor(Color(red: 0x7C / 255, green: 0x95 / 255, blue: 0xFF / 255)))
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
            }
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(context.state.title)
                        .font(.system(size: 24, weight: .semibold))
                        .tracking(-0.9)
                        .foregroundStyle(WidgetPalette.paper)
                    Text(context.state.detail)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Color(red: 0xB5 / 255, green: 0xB5 / 255, blue: 0xBB / 255))
                        .lineLimit(1)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    if let bytes = context.state.bytes { Text(bytes) }
                    if let eta = context.state.eta { Text(eta) }
                }
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(Color(red: 0xB5 / 255, green: 0xB5 / 255, blue: 0xBB / 255))
            }
            if let fraction = context.state.fraction {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color(red: 0x3A / 255, green: 0x3A / 255, blue: 0x40 / 255)).frame(height: 3)
                        Capsule().fill(accent).frame(width: max(6, geo.size.width * fraction), height: 3)
                    }
                }
                .frame(height: 3)
            }
        }
        .padding(16)
    }
}

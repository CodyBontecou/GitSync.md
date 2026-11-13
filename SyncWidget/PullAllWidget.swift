import SwiftUI
import WidgetKit

/// A single-tap Home Screen widget. Tapping it deep-links into the app via
/// `syncmd://pull-all`, which the app handles by running an immediate,
/// cooldown-bypassing reconciliation pass over every cloned repository.
struct PullAllWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "PullAllWidget", provider: PullAllProvider()) { _ in
            PullAllWidgetView()
        }
        .configurationDisplayName(String(localized: "Pull All Repositories"))
        .description(String(localized: "One tap to fetch and fast-forward every repository."))
        .supportedFamilies([.systemSmall])
    }
}

private struct PullAllEntry: TimelineEntry {
    let date = Date()
}

private struct PullAllProvider: TimelineProvider {
    func placeholder(in context: Context) -> PullAllEntry {
        PullAllEntry()
    }

    func getSnapshot(in context: Context, completion: @escaping (PullAllEntry) -> Void) {
        completion(PullAllEntry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<PullAllEntry>) -> Void) {
        completion(Timeline(entries: [PullAllEntry()], policy: .never))
    }
}

// MARK: - Tide palette
//
// The widget extension can't see the app's TideDesignSystem, and Geist isn't
// registered here, so it carries the few Tide hex values it needs and uses the
// system font. The tile stays dark in both appearances, like the design.

private enum WidgetTide {
    static let tile = Color(red: 0x11 / 255, green: 0x11 / 255, blue: 0x13 / 255)        // #111113
    static let paper = Color(red: 0xF2 / 255, green: 0xF1 / 255, blue: 0xEC / 255)       // #F2F1EC
    static let meta = Color(red: 0x9C / 255, green: 0x9C / 255, blue: 0xA3 / 255)        // #9C9CA3
    static let line = Color(red: 0x3A / 255, green: 0x3A / 255, blue: 0x40 / 255)        // #3A3A40
    static let behind = Color(red: 0x7C / 255, green: 0x95 / 255, blue: 0xFF / 255)      // #7C95FF (behind on dark)
}

private struct PullAllWidgetView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            (Text(verbatim: "gitsync").foregroundStyle(WidgetTide.meta)
                + Text(verbatim: ".md").foregroundStyle(WidgetTide.behind))
                .font(.system(size: 11, weight: .semibold, design: .monospaced))

            Spacer(minLength: 0)

            Text("Pull every vault")
                .font(.system(size: 22, weight: .semibold))
                .tracking(-0.8)
                .foregroundStyle(WidgetTide.paper)
                .lineLimit(2)
                .minimumScaleFactor(0.8)

            remoteRail
                .padding(.top, 8)

            HStack(spacing: 6) {
                Image(systemName: "arrow.down")
                    .font(.system(size: 12, weight: .bold))
                Text("Pull all")
                    .font(.system(size: 13, weight: .semibold))
            }
            .foregroundStyle(WidgetTide.tile)
            .frame(maxWidth: .infinity, minHeight: 36)
            .background(Capsule().fill(WidgetTide.paper))
            .padding(.top, 12)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .containerBackground(WidgetTide.tile, for: .widget)
        .widgetURL(URL(string: "syncmd://pull-all"))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(localized: "Pull all vaults"))
    }

    /// Mini sync rail: remote rings waiting to come down to the sync point.
    private var remoteRail: some View {
        HStack(spacing: 5) {
            Circle().strokeBorder(WidgetTide.paper, lineWidth: 2)
                .frame(width: 10, height: 10)
            Rectangle().fill(WidgetTide.line).frame(height: 1)
            ForEach(0..<3, id: \.self) { _ in
                Circle().strokeBorder(WidgetTide.behind, lineWidth: 2)
                    .frame(width: 7, height: 7)
            }
        }
        .accessibilityHidden(true)
    }
}

#Preview(as: .systemSmall) {
    PullAllWidget()
} timeline: {
    PullAllEntry()
}

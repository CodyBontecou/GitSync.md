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

private struct PullAllWidgetView: View {
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "arrow.down.circle.fill")
                .font(.system(size: 40, weight: .medium))
                .foregroundStyle(.tint)
            VStack(spacing: 2) {
                Text("Pull All")
                    .font(.headline.weight(.semibold))
                Text("Repositories")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .widgetURL(URL(string: "syncmd://pull-all"))
    }
}

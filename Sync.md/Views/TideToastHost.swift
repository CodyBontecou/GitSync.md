import SwiftUI

/// Shows `AppState.currentToast` floating above the primary button.
/// Mounted once at the root (ContentView) so every screen gets feedback.
struct TideToastHost: View {
    @Environment(AppState.self) private var state

    var body: some View {
        VStack {
            Spacer(minLength: 0)
            if let toast = state.currentToast {
                TideToast(
                    title: toast.title,
                    detail: toast.detail,
                    style: style(for: toast.kind),
                    actionTitle: toast.actionTitle,
                    action: toast.action.map { action in
                        { Task { await state.performToastAction(action) } }
                    }
                )
                .padding(.horizontal, Tide.gutter)
                // Clear of the 60pt primary button and its 12pt padding.
                .padding(.bottom, 84)
                .id(toast.id)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .gesture(DragGesture(minimumDistance: 10).onEnded { value in
                    if value.translation.height > 20 { state.dismissToast() }
                })
            }
        }
        .animation(.spring(duration: 0.35, bounce: 0.12), value: state.currentToast)
    }

    private func style(for kind: TideToastItem.Kind) -> TideToast.Style {
        switch kind {
        case .pushed, .committed: return .success
        case .pulled: return .behind
        case .error: return .danger
        case .info: return .neutral
        }
    }
}

/// Short relative time in the Tide meta voice: "just now", "4m ago", "2h ago".
enum TideRelativeTime {
    static func string(since date: Date?, now: Date = Date()) -> String {
        guard let date, date != .distantPast else { return String(localized: "never") }
        let seconds = max(0, now.timeIntervalSince(date))
        if seconds < 60 { return String(localized: "just now") }
        if seconds < 3600 { return String(localized: "\(Int(seconds / 60))m ago") }
        if seconds < 86_400 { return String(localized: "\(Int(seconds / 3600))h ago") }
        return String(localized: "\(Int(seconds / 86_400))d ago")
    }

    /// Minutes only, for "last checked 18 min ago".
    static func minutes(since date: Date?, now: Date = Date()) -> String {
        guard let date else { return String(localized: "a while ago") }
        let minutes = Int(max(0, now.timeIntervalSince(date)) / 60)
        return minutes < 1 ? String(localized: "just now") : String(localized: "\(minutes) min ago")
    }
}

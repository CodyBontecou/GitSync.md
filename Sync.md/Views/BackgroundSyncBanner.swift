import SwiftUI

/// Transient activity banner shown while Background Sync is reconciling
/// repositories. Foreground passes perform real Git work (fetch/pull/push),
/// which can momentarily lag the UI — this banner makes that work visible
/// instead of mysterious. Rendered as Tide's dark floating pill.
struct BackgroundSyncBanner: View {
    var repositoryCount: Int

    private var message: String {
        repositoryCount > 1
            ? String(localized: "Background Sync: \(repositoryCount) repositories")
            : String(localized: "Background Sync running")
    }

    var body: some View {
        HStack(spacing: 10) {
            ProgressView()
                .controlSize(.small)
                .tint(Tide.heroText)
            Text(message)
                .font(TideFont.sans(14, .semibold, relativeTo: .subheadline))
                .foregroundStyle(Tide.heroText)
                .lineLimit(1)
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 40)
        .background(Capsule().fill(Tide.heroBg))
        .overlay(Capsule().strokeBorder(Tide.heroBorder, lineWidth: 1))
        .shadow(color: .black.opacity(0.18), radius: 14, y: 8)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(message)
    }
}

#Preview {
    VStack(spacing: 20) {
        BackgroundSyncBanner(repositoryCount: 1)
        BackgroundSyncBanner(repositoryCount: 3)
    }
    .padding()
    .background(Tide.paper)
}

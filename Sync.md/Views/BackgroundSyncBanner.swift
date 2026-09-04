import SwiftUI

/// Transient activity banner shown while Background Sync is reconciling
/// repositories. Foreground passes perform real Git work (fetch/pull/push),
/// which can momentarily lag the UI — this banner makes that work visible
/// instead of mysterious.
struct BackgroundSyncBanner: View {
    var repositoryCount: Int

    private var message: String {
        repositoryCount > 1
            ? String(localized: "Background Sync: \(repositoryCount) repositories")
            : String(localized: "Background Sync running")
    }

    var body: some View {
        HStack(spacing: 8) {
            ProgressView()
                .controlSize(.small)
                .tint(Color.brutalText)
            Text(message.uppercased())
                .bType(.monoCaption, weight: .black)
                .tracking(2)
                .foregroundStyle(Color.brutalText)
                .lineLimit(1)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Color.brutalSurface)
        .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))
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
}

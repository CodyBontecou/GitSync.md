import SwiftUI

/// Dismissible dark Tide banner at the top of Home inviting people to the
/// Discord community.
struct DiscordPromoBanner: View {
    @AppStorage("discordPromoDismissed") private var dismissed: Bool = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private static let inviteURL = URL(string: "https://discord.gg/RaQYS4t6gn")!

    var body: some View {
        if !dismissed {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(localized: "Join the GitSync.md Discord"))
                        .font(TideFont.sans(15, .semibold, relativeTo: .subheadline))
                        .foregroundStyle(Tide.heroText)
                    // A promo must not crowd out the vaults at accessibility
                    // sizes: keep the headline only (still capped below).
                    if !dynamicTypeSize.isAccessibilitySize {
                        Text(String(localized: "Tips, beta builds, feature votes."))
                            .font(TideFont.sans(13.5, relativeTo: .footnote))
                            .foregroundStyle(Tide.heroMeta)
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityElement(children: .combine)
                .accessibilityLabel(String(localized: "Join the Discord community."))

                Spacer(minLength: 8)

                Button {
                    UIApplication.shared.open(Self.inviteURL)
                } label: {
                    Text(String(localized: "Join"))
                        .font(TideFont.sans(13, .semibold, relativeTo: .footnote))
                        .foregroundStyle(Tide.heroBg)
                        .padding(.horizontal, 14)
                        .frame(height: 36)
                        .background(Capsule().fill(Tide.heroText))
                        // 36pt pill, 44pt hit target.
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                Button {
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                        dismissed = true
                    }
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Tide.heroMeta)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "Dismiss Discord banner"))
            }
            .padding(.leading, 16)
            .padding(.trailing, 4)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(Tide.heroBg)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .strokeBorder(Tide.heroBorder, lineWidth: 1)
            )
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            .padding(.horizontal, Tide.gutter)
            .padding(.top, 8)
            .padding(.bottom, 4)
            .transition(.move(edge: .top).combined(with: .opacity))
        }
    }
}

#Preview {
    VStack(spacing: 0) {
        DiscordPromoBanner()
        Spacer()
    }
    .background(Tide.paper)
}

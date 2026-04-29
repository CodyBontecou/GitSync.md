import SwiftUI

struct DiscordPromoBanner: View {
    @AppStorage("discordPromoDismissed") private var dismissed: Bool = false

    private static let inviteURL = URL(string: "https://discord.gg/RaQYS4t6gn")!

    var body: some View {
        if !dismissed {
            BCard(padding: 12, bg: .brutalSurface) {
                HStack(spacing: 12) {
                    HStack(spacing: 12) {
                        Image(systemName: "bubble.left.and.bubble.right.fill")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(Color.brutalText)
                            .frame(width: 28)

                        VStack(alignment: .leading, spacing: 2) {
                            Text("Join the community")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(Color.brutalText)
                            Text("Chat with us on Discord")
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundStyle(Color.brutalTextMid)
                        }
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("Join the Discord community.")

                    Spacer(minLength: 8)

                    Button {
                        UIApplication.shared.open(Self.inviteURL)
                    } label: {
                        Text("JOIN")
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color.brutalAccent)
                            .tracking(1)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(Color.brutalAccent.opacity(0.10))
                            .overlay(Rectangle().strokeBorder(Color.brutalAccent.opacity(0.30), lineWidth: 1))
                    }
                    .buttonStyle(.plain)

                    Button {
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                            dismissed = true
                        }
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(Color.brutalTextMid)
                            .frame(width: 28, height: 28)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Dismiss Discord banner")
                }
            }
            .padding(.horizontal, 20)
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
    .background(Color.brutalBg)
}

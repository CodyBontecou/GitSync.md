import SwiftUI

/// Displays a GitHub user avatar loaded from a URL, with a fallback SF Symbol.
struct GitHubAvatarView: View {
    let avatarURL: String
    var size: CGFloat = 32

    var body: some View {
        Group {
            if let url = URL(string: avatarURL), !avatarURL.isEmpty {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFill()
                    case .failure:
                        fallbackAvatar
                    case .empty:
                        ProgressView()
                            .controlSize(.mini)
                            .frame(width: size, height: size)
                    @unknown default:
                        fallbackAvatar
                    }
                }
            } else {
                fallbackAvatar
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay(
            Circle()
                .strokeBorder(.quaternary, lineWidth: 0.5)
        )
    }

    private var fallbackAvatar: some View {
        Image(systemName: "person.circle.fill")
            .resizable()
            .scaledToFit()
            .foregroundStyle(.secondary)
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

#Preview {
    HStack(spacing: 16) {
        // With a valid URL
        GitHubAvatarView(
            avatarURL: "https://avatars.githubusercontent.com/u/1?v=4",
            size: 40
        )

        // With empty URL (fallback)
        GitHubAvatarView(
            avatarURL: "",
            size: 40
        )

        // Small size
        GitHubAvatarView(
            avatarURL: "https://avatars.githubusercontent.com/u/1?v=4",
            size: 24
        )
    }
    .padding()
}

import SwiftUI

struct OnboardingView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    @State private var currentPage = 0
    @State private var appeared = false

    private let slides: [OnboardingSlide] = [
        OnboardingSlide(
            title: ["SYNC", ".MD"],
            accentIndex: 1,
            subtitle: "YOUR REPOS, ON YOUR IPHONE",
            description: "Clone any Git repository to your device — GitHub, self-hosted, SSH, or public remotes — and keep it in sync from your pocket."
        ),
        OnboardingSlide(
            title: ["EDIT", "ANYWHERE"],
            accentIndex: 1,
            subtitle: "MARKDOWN-FIRST WORKFLOW",
            description: "Your files live in the Files app. Edit with any text editor, then come back to commit and push your changes upstream."
        ),
        OnboardingSlide(
            title: ["FULL", "GIT"],
            accentIndex: 1,
            subtitle: "BRANCHES, DIFFS, HISTORY",
            description: "Switch branches, view diffs, browse commit history, manage tags, and resolve conflicts — real Git, not a watered-down sync."
        ),
    ]

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $currentPage) {
                ForEach(Array(slides.enumerated()), id: \.offset) { index, slide in
                    slideView(slide)
                        .tag(index)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .animation(.easeInOut(duration: 0.3), value: currentPage)

            // Bottom section
            VStack(spacing: 20) {
                // Page indicators
                HStack(spacing: 8) {
                    ForEach(0..<slides.count, id: \.self) { index in
                        Rectangle()
                            .fill(index == currentPage ? Color.brutalText : Color.brutalBorderSoft)
                            .frame(width: index == currentPage ? 24 : 8, height: 3)
                            .animation(.easeInOut(duration: 0.25), value: currentPage)
                    }
                }

                if currentPage == slides.count - 1 {
                    BPrimaryButton(title: "Get Started", icon: "arrow.right") {
                        finishOnboarding()
                    }
                    .accessibilityIdentifier("onboarding.getStartedButton")
                    .padding(.horizontal, 24)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
                } else {
                    BPrimaryButton(title: "Continue", icon: "arrow.right") {
                        withAnimation {
                            currentPage += 1
                        }
                    }
                    .accessibilityIdentifier("onboarding.continueButton")
                    .padding(.horizontal, 24)
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
                }

                BGhostButton(title: "Skip") {
                    finishOnboarding()
                }
                .accessibilityIdentifier("onboarding.skipButton")
                .padding(.bottom, 8)
            }
            .padding(.bottom, 40)
            .animation(.easeInOut(duration: 0.25), value: currentPage)
        }
        .background(Color.brutalBg)
        .opacity(appeared ? 1 : 0)
        .onAppear {
            withAnimation(.easeOut(duration: 0.5)) {
                appeared = true
            }
        }
    }

    // MARK: - Slide View

    private func slideView(_ slide: OnboardingSlide) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer()

            // Title lines
            ForEach(Array(slide.title.enumerated()), id: \.offset) { index, line in
                Text(line)
                    .font(.system(size: 56, weight: .black))
                    .foregroundStyle(index == slide.accentIndex ? Color.brutalAccent : Color.brutalText)
                    .tracking(-2)
            }
            .padding(.bottom, 12)

            Rectangle()
                .fill(Color.brutalBorder)
                .frame(height: 2)
                .padding(.bottom, 10)

            // Subtitle
            HStack(spacing: 10) {
                Rectangle()
                    .fill(Color.brutalBorder)
                    .frame(width: 20, height: 1)
                Text(slide.subtitle)
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .tracking(1.5)
            }
            .padding(.bottom, 20)

            // Description
            Text(slide.description)
                .font(.system(size: 16, weight: .regular))
                .foregroundStyle(Color.brutalTextMid)
                .lineSpacing(4)
                .fixedSize(horizontal: false, vertical: true)

            Spacer()
            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24)
    }

    // MARK: - Actions

    private func finishOnboarding() {
        state.hasSeenOnboarding = true
        state.saveGlobalSettings()
        dismiss()
    }
}

// MARK: - Slide Model

private struct OnboardingSlide {
    let title: [String]
    let accentIndex: Int
    let subtitle: String
    let description: String
}

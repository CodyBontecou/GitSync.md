import SwiftUI

struct OnboardingView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    @State private var currentPage = 0
    @State private var appeared = false
    @State private var trackedSteps: Set<OnboardingAnalyticsStep> = []

    /// `true` when onboarding is re-presented from App Settings for an
    /// existing user. Replays always start from the first slide, never resume
    /// at the sign-in step, and treat "Skip" as a dismissal.
    private let isReplay: Bool

    init(isReplay: Bool = false) {
        self.isReplay = isReplay
    }

    private let analytics = OnboardingAnalyticsClient.shared

    /// The Background Sync feature slide is only offered while the legacy
    /// `gitSyncAssistEnabled` feature flag is enabled. While `false`, onboarding
    /// shows exactly the three informational slides (then the sign-in step).
    private var showsAssistSlide: Bool { FeatureFlags.gitSyncAssistEnabled }

    /// Total pages: informational slides, the optional Background Sync feature
    /// slide, and the final sign-in step. The sign-in step is always last so
    /// it stays swipe-navigable like every other onboarding page.
    private var pageCount: Int { slides.count + (showsAssistSlide ? 1 : 0) + 1 }

    /// Index of the embedded sign-in step (`SetupView`).
    private var signInIndex: Int { pageCount - 1 }

    private let slides: [OnboardingSlide] = [
        OnboardingSlide(
            title: [String(localized: "SYNC"), ".MD"],
            accentIndex: 1,
            subtitle: String(localized: "YOUR REPOS, ON YOUR IPHONE"),
            description: String(localized: "Clone any Git repository to your device — GitHub, self-hosted, SSH, or public remotes — and keep it in sync from your pocket.")
        ),
        OnboardingSlide(
            title: [String(localized: "EDIT"), String(localized: "ANYWHERE")],
            accentIndex: 1,
            subtitle: String(localized: "MARKDOWN-FIRST WORKFLOW"),
            description: String(localized: "Your files live in the Files app. Edit with any text editor, then come back to commit and push your changes upstream.")
        ),
        OnboardingSlide(
            title: [String(localized: "FULL"), "GIT"],
            accentIndex: 1,
            subtitle: String(localized: "BRANCHES, DIFFS, HISTORY"),
            description: String(localized: "Switch branches, view diffs, browse commit history, manage tags, and resolve conflicts — real Git, not a watered-down sync.")
        ),
    ]

    private var assistSlide: OnboardingSlide {
        OnboardingSlide(
            title: [String(localized: "BACKGROUND"), String(localized: "SYNC")],
            accentIndex: 1,
            subtitle: String(localized: "BEST-EFFORT — INCLUDED WITH GITSYNC.MD"),
            description: ""
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $currentPage) {
                ForEach(Array(slides.enumerated()), id: \.offset) { index, slide in
                    slideView(slide)
                        .tag(index)
                }
                if showsAssistSlide {
                    assistSlideView
                        .tag(slides.count)
                }
                SetupView(onBack: {
                    withAnimation(.easeInOut(duration: 0.3)) {
                        currentPage = signInIndex - 1
                    }
                })
                .tag(signInIndex)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .animation(.easeInOut(duration: 0.3), value: currentPage)

            // Bottom section — hidden on the sign-in step, which provides its
            // own calls to action (and its own back affordance).
            if currentPage != signInIndex {
                VStack(spacing: 20) {
                    // Page indicators
                    HStack(spacing: 8) {
                        ForEach(0..<pageCount, id: \.self) { index in
                            Rectangle()
                                .fill(index == currentPage ? Color.brutalText : Color.brutalBorderSoft)
                                .frame(width: index == currentPage ? 24 : 8, height: 3)
                                .animation(.easeInOut(duration: 0.25), value: currentPage)
                        }
                    }

                    if currentPage == pageCount - 2 {
                        BPrimaryButton(title: lastPagePrimaryTitle, icon: "arrow.right") {
                            advanceToSignIn()
                        }
                        .padding(.horizontal, 24)
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                    } else {
                        BPrimaryButton(title: String(localized: "Continue"), icon: "arrow.right") {
                            withAnimation {
                                currentPage += 1
                            }
                        }
                        .padding(.horizontal, 24)
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                    }

                    BGhostButton(title: String(localized: "Skip")) {
                        skipTapped()
                    }
                    .padding(.bottom, 8)
                }
                .padding(.bottom, 40)
                .animation(.easeInOut(duration: 0.25), value: currentPage)
            }
        }
        .background(Color.brutalBg)
        .opacity(appeared ? 1 : 0)
        .onAppear {
            if !isReplay, state.hasSeenOnboarding, currentPage != signInIndex {
                // Resume an interrupted first run directly at the sign-in
                // step instead of replaying the slides.
                currentPage = signInIndex
            }
            trackCurrentPageIfNeeded(markOnboardingStart: true)
            withAnimation(.easeOut(duration: 0.5)) {
                appeared = true
            }
        }
        .onChange(of: currentPage) { _, newPage in
            if newPage == signInIndex, !state.hasSeenOnboarding {
                // The user has passed the slides. Persist it so an
                // interrupted first run resumes at the sign-in step.
                state.hasSeenOnboarding = true
                state.saveGlobalSettings()
            }
            trackCurrentPageIfNeeded()
        }
    }

    private var lastPagePrimaryTitle: String {
        String(localized: "Get Started")
    }

    // MARK: - Slide View

    private func slideView(_ slide: OnboardingSlide) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer()

            slideHeader(slide)

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

    /// Giant hero title, hairline divider, and monospaced micro-label shared
    /// by every onboarding page. Informational slides use the default 56pt
    /// hero; the Background Sync slide passes a compact size to fit one screen.
    private func slideHeader(_ slide: OnboardingSlide, titleSize: CGFloat = 56) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            // Title lines
            ForEach(Array(slide.title.enumerated()), id: \.offset) { index, line in
                Text(line)
                    .font(.system(size: titleSize, weight: .black))
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
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: - Background Sync Feature Slide

    /// Feature slide: hero, feature list, and an "included" note. Background
    /// Sync is part of the app — no purchase surface here. Vertical scrolling
    /// keeps disclosures reachable on compact devices and longer localizations
    /// while the CTA stays pinned below the page.
    private var assistSlideView: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 14) {
                Spacer(minLength: 0)

                slideHeader(assistSlide, titleSize: 40)

                // What you get — one line each.
                VStack(spacing: 8) {
                    assistFeatureRow(
                        icon: "bolt.badge.clock.fill",
                        title: String(localized: "Syncs in the background when iOS allows")
                    )
                    assistFeatureRow(
                        icon: "square.stack.3d.up.fill",
                        title: String(localized: "One switch covers all your repositories")
                    )
                    assistFeatureRow(
                        icon: "checkmark.shield.fill",
                        title: String(localized: "Control automatic pull and push separately")
                    )
                }
                .padding(12)
                .background(Color.brutalBg)
                .overlay(Rectangle().strokeBorder(Color.brutalBorder, lineWidth: 1))

                Spacer(minLength: 0)

                HStack(spacing: 8) {
                    BBadge(text: String(localized: "Included with GitSync.md"), style: .success)
                    Text(String(localized: "No subscription. Runs entirely on this device."))
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(Color.brutalTextMid)
                }

                AssistFinePrint(
                    "Enable it anytime in App Settings → Background Sync. It runs only when iOS grants background time and is not real time. Manual Git stays included."
                )
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24)
            .padding(.top, 8)
            .padding(.bottom, 12)
        }
        .scrollIndicators(.hidden)
    }

    private func assistFeatureRow(icon: String, title: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Color.brutalText)
                .frame(width: 28, height: 28)
                .background(Color.brutalSurface)
                .overlay(Rectangle().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                .accessibilityHidden(true)
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Color.brutalText)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }

    // MARK: - Actions

    /// Advances to the embedded sign-in step — the last onboarding page.
    private func advanceToSignIn() {
        withAnimation(.easeInOut(duration: 0.3)) {
            currentPage = signInIndex
        }
    }

    /// "Skip" jumps past the remaining slides to the sign-in step. On
    /// a replay from App Settings it simply dismisses, matching the old
    /// escape hatch for existing users.
    private func skipTapped() {
        if isReplay {
            dismiss()
        } else {
            advanceToSignIn()
        }
    }

    private func trackCurrentPageIfNeeded(markOnboardingStart: Bool = false) {
        guard let step = analyticsStep(for: currentPage) else { return }
        if markOnboardingStart {
            analytics.trackOnboardingStarted(step: step)
        }
        guard trackedSteps.insert(step).inserted else { return }
        analytics.trackOnboardingStepViewed(step)
    }

    /// Step analytics for a page index. The embedded sign-in step reports its
    /// own `accountChoice` step from within `SetupView`, so it maps to `nil`
    /// here to avoid double tracking.
    private func analyticsStep(for page: Int) -> OnboardingAnalyticsStep? {
        switch page {
        case 0: return .welcome
        case 1: return .editAnywhere
        case 2: return .fullGit
        case 3: return showsAssistSlide ? .backgroundSync : nil
        default: return nil
        }
    }
}

// MARK: - Slide Model

private struct OnboardingSlide {
    let title: [String]
    let accentIndex: Int
    let subtitle: String
    let description: String
}

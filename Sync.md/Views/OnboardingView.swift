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
            title: [String(localized: "Your notes."), String(localized: "Real git.")],
            mutedLine: String(localized: "Always in step."),
            subtitle: String(localized: "YOUR REPOS, ON YOUR IPHONE"),
            description: String(localized: "Clone any repo into Files, write in Obsidian or anything else, and sync with one tap."),
            symbol: "checkmark",
            local: 3,
            remote: 3
        ),
        OnboardingSlide(
            title: [String(localized: "Edit"), String(localized: "anywhere.")],
            mutedLine: String(localized: "Push when ready."),
            subtitle: String(localized: "MARKDOWN-FIRST WORKFLOW"),
            description: String(localized: "Your files live in the Files app. Edit with any text editor, then come back to commit and push your changes upstream."),
            symbol: "arrow.up",
            local: 3,
            remote: 0
        ),
        OnboardingSlide(
            title: [String(localized: "Full git.")],
            mutedLine: String(localized: "Nothing watered down."),
            subtitle: String(localized: "BRANCHES, DIFFS, HISTORY"),
            description: String(localized: "Switch branches, view diffs, browse commit history, manage tags, and resolve conflicts — real Git, not a watered-down sync."),
            symbol: "arrow.triangle.branch",
            local: 2,
            remote: 2
        ),
    ]

    private var assistSlide: OnboardingSlide {
        OnboardingSlide(
            title: [String(localized: "Background")],
            mutedLine: String(localized: "sync."),
            subtitle: String(localized: "BEST-EFFORT — INCLUDED WITH GITSYNC.MD"),
            description: "",
            symbol: "clock",
            local: 0,
            remote: 3
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            if currentPage != signInIndex {
                wordmark
                    .padding(.horizontal, 24)
                    .padding(.top, 16)
                    .transition(.opacity)
            }

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
                VStack(spacing: 10) {
                    pageIndicator
                        .padding(.bottom, 10)

                    if currentPage == pageCount - 2 {
                        OnboardingPrimaryButton(title: lastPagePrimaryTitle) {
                            advanceToSignIn()
                        }
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                    } else {
                        OnboardingPrimaryButton(title: String(localized: "Continue")) {
                            withAnimation {
                                currentPage += 1
                            }
                        }
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                    }

                    Button {
                        skipTapped()
                    } label: {
                        Text(String(localized: "Skip"))
                            .font(TideFont.sans(15, .medium, relativeTo: .body))
                            .foregroundStyle(Tide.heroText)
                            .frame(maxWidth: .infinity, minHeight: 54)
                            .overlay(Capsule().strokeBorder(Tide.heroLine, lineWidth: 1))
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 24)
                .animation(.easeInOut(duration: 0.25), value: currentPage)
            }
        }
        .background {
            // The slides are a dark, appearance-independent stage; the
            // embedded sign-in step paints its own background.
            (currentPage == signInIndex ? Color.brutalBg : Tide.heroBg)
                .ignoresSafeArea()
        }
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

    // MARK: - Chrome

    private var wordmark: some View {
        (Text("gitsync").foregroundStyle(Tide.heroText)
            + Text(".md").foregroundStyle(Tide.heroBehind))
            .font(TideFont.mono(13, .semibold, relativeTo: .headline))
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityLabel("GitSync.md")
    }

    private var pageIndicator: some View {
        HStack(spacing: 6) {
            ForEach(0..<pageCount, id: \.self) { index in
                Capsule()
                    .fill(index == currentPage ? Tide.heroText : Tide.heroLine)
                    .frame(width: index == currentPage ? 20 : 6, height: 6)
                    .animation(.easeInOut(duration: 0.25), value: currentPage)
            }
        }
        .accessibilityElement()
        .accessibilityLabel(String(localized: "Page \(currentPage + 1) of \(pageCount)"))
    }

    // MARK: - Slide View

    private func slideView(_ slide: OnboardingSlide) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            OnboardingSyncIllustration(symbol: slide.symbol, local: slide.local, remote: slide.remote)
                .frame(maxHeight: 220)
                .padding(.top, 12)

            Spacer(minLength: 12)

            slideHeader(slide)

            // Description
            Text(slide.description)
                .font(TideFont.sans(16, relativeTo: .body))
                .foregroundStyle(Tide.heroMeta)
                .lineSpacing(4)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 16)

            Spacer(minLength: 16)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24)
    }

    /// Mono micro-label above the big Tide headline (last line muted),
    /// shared by every onboarding page. The Background Sync slide passes a
    /// smaller display size to fit one screen.
    private func slideHeader(_ slide: OnboardingSlide, size: CGFloat = 44) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(slide.subtitle)
                .font(TideFont.section)
                .tracking(0.66)
                .foregroundStyle(Tide.heroMeta)
                .padding(.bottom, 14)

            VStack(alignment: .leading, spacing: -size * 0.1) {
                ForEach(Array(slide.title.enumerated()), id: \.offset) { _, line in
                    Text(line).foregroundStyle(Tide.heroText)
                }
                Text(slide.mutedLine).foregroundStyle(OnboardingPalette.muted)
            }
            .font(TideFont.display(size))
            .tracking(-size * 0.045)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Background Sync Feature Slide

    /// Feature slide: hero, feature list, and an "included" note. Background
    /// Sync is part of the app — no purchase surface here. Vertical scrolling
    /// keeps disclosures reachable on compact devices and longer localizations
    /// while the CTA stays pinned below the page.
    private var assistSlideView: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 16) {
                OnboardingSyncIllustration(symbol: assistSlide.symbol, local: assistSlide.local, remote: assistSlide.remote)
                    .frame(height: 110)

                slideHeader(assistSlide, size: 38)

                // What you get — one line each.
                VStack(spacing: 0) {
                    assistFeatureRow(
                        icon: "bolt.badge.clock.fill",
                        title: String(localized: "Syncs in the background when iOS allows")
                    )
                    Rectangle().fill(Tide.heroLine).frame(height: 1)
                    assistFeatureRow(
                        icon: "square.stack.3d.up.fill",
                        title: String(localized: "One switch covers all your repositories")
                    )
                    Rectangle().fill(Tide.heroLine).frame(height: 1)
                    assistFeatureRow(
                        icon: "checkmark.shield.fill",
                        title: String(localized: "Control automatic pull and push separately")
                    )
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 2)
                .background(
                    RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous)
                        .fill(Color.white.opacity(0.06))
                )

                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(String(localized: "Included with GitSync.md"))
                        .font(TideFont.metaStrong)
                        .foregroundStyle(Tide.heroSynced)
                        .padding(.horizontal, 9).padding(.vertical, 5)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Tide.heroSynced.opacity(0.16)))
                    Text(String(localized: "No subscription. Runs entirely on this device."))
                        .font(TideFont.sans(13, relativeTo: .footnote))
                        .foregroundStyle(Tide.heroMeta)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Text("Enable it anytime in App Settings → Background Sync. It runs only when iOS grants background time and is not real time. Manual Git stays included.")
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.heroMeta)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24)
            .padding(.top, 8)
            .padding(.bottom, 12)
        }
        .scrollIndicators(.hidden)
    }

    private func assistFeatureRow(icon: String, title: String) -> some View {
        HStack(spacing: 12) {
            // Decorative icon ornament (a11y-hidden, fixed 28pt disc) —
            // fixed size per Issue #16's decorative allowance.
            Image(systemName: icon)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(Tide.heroBehind)
                .frame(width: 28, height: 28)
                .background(Circle().fill(Tide.heroBehind.opacity(0.16)))
                .accessibilityHidden(true)
            Text(title)
                .font(TideFont.sans(15, .medium, relativeTo: .subheadline))
                .foregroundStyle(Tide.heroText)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.vertical, 12)
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
    /// Headline lines in full ink.
    let title: [String]
    /// Closing headline line, drawn muted.
    let mutedLine: String
    /// Mono caps micro-label above the headline.
    let subtitle: String
    let description: String
    /// SF Symbol in the sync-point node of the illustration.
    let symbol: String
    /// Filled local-commit dots (left) and remote-commit rings (right).
    let local: Int
    let remote: Int
}

// MARK: - Components

/// Fixed colors for the always-dark onboarding stage.
private enum OnboardingPalette {
    static let muted = Color(hex: 0x9C9CA3)
    static let localShades: [Color] = [Tide.heroAhead, Color(hex: 0x8A4523), Color(hex: 0x5A3220)]
    static let remoteShades: [Color] = [Tide.heroBehind, Color(hex: 0x3F52A0), Color(hex: 0x2A3566)]
}

/// Paper pill on the dark onboarding stage.
private struct OnboardingPrimaryButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(TideFont.sans(17, .semibold, relativeTo: .body))
                .foregroundStyle(Color(hex: 0x111113))
                .frame(maxWidth: .infinity, minHeight: 58)
                .background(Capsule().fill(Tide.heroText))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// "this iPhone" and "GitHub" meeting at one sync point: fading local dots,
/// a ringed node, and fading remote rings on a hairline rail.
private struct OnboardingSyncIllustration: View {
    let symbol: String
    let local: Int
    let remote: Int

    private static let sizes: [CGFloat] = [14, 11, 8]

    var body: some View {
        ZStack {
            Rectangle().fill(Tide.heroLine).frame(height: 1)

            HStack {
                Text(String(localized: "this iPhone"))
                Spacer()
                Text(verbatim: "GitHub")
            }
            .font(TideFont.mono(11))
            .foregroundStyle(OnboardingPalette.muted)
            .offset(y: -28)

            HStack(spacing: 10) {
                ForEach((0..<min(local, 3)).reversed(), id: \.self) { i in
                    Circle().fill(OnboardingPalette.localShades[i])
                        .frame(width: Self.sizes[i], height: Self.sizes[i])
                }
                Image(systemName: symbol)
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(Tide.heroText)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(Tide.heroBg))
                    .overlay(Circle().strokeBorder(Tide.heroText, lineWidth: 2))
                    .padding(.horizontal, 6)
                ForEach(0..<min(remote, 3), id: \.self) { i in
                    Circle().strokeBorder(OnboardingPalette.remoteShades[i], lineWidth: 2)
                        .frame(width: Self.sizes[i], height: Self.sizes[i])
                }
            }
        }
        .frame(maxWidth: .infinity, minHeight: 90, maxHeight: .infinity)
        .accessibilityElement()
        .accessibilityLabel(String(localized: "Notes on your phone and commits on GitHub meeting at one sync point"))
        .accessibilityAddTraits(.isImage)
    }
}

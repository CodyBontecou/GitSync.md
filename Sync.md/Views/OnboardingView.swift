import SwiftUI

/// First run: one welcome screen that leads straight into sign-in.
///
/// "Continue with GitHub" starts OAuth right here and, on success, moves to
/// the default-save-location step. "SSH, token or another host" opens the
/// token / any-host choices (`SetupView`). The demo link swaps in a seeded
/// vault for a look around.
struct OnboardingView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    /// `true` when onboarding is re-presented from App Settings for an
    /// existing user. Replays never offer the demo takeover and can close.
    private let isReplay: Bool

    init(isReplay: Bool = false) {
        self.isReplay = isReplay
    }

    private enum Route: Equatable {
        case welcome
        /// Token / other-host choices.
        case otherWays
        /// Signed in with GitHub; choose the default save location.
        case saveLocation
    }

    @State private var route: Route = .welcome
    @State private var appeared = false
    @State private var isSigningIn = false

    private let analytics = OnboardingAnalyticsClient.shared

    var body: some View {
        ZStack {
            switch route {
            case .welcome:
                welcome
                    .transition(.asymmetric(
                        insertion: .move(edge: .leading).combined(with: .opacity),
                        removal: .move(edge: .leading).combined(with: .opacity)
                    ))
            case .otherWays:
                SetupView(onBack: { navigate(to: .welcome) })
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            case .saveLocation:
                SetupView(startAtSaveLocation: true, completedAuthMethod: .githubOAuth)
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.3), value: route)
        .opacity(appeared ? 1 : 0)
        .onAppear {
            analytics.trackOnboardingStarted(step: .welcome)
            analytics.trackOnboardingStepViewed(.welcome)
            withAnimation(.easeOut(duration: 0.5)) {
                appeared = true
            }
        }
    }

    // MARK: - Welcome

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                wordmark
                Spacer()
                if isReplay {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(Tide.heroMeta)
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(localized: "Close"))
                }
            }
            .frame(minHeight: 44)
            .padding(.horizontal, 24)
            .padding(.top, 8)

            OnboardingSyncIllustration(symbol: "checkmark", local: 3, remote: 3)
                .frame(maxHeight: 220)
                .padding(.horizontal, 24)
                .padding(.top, 12)

            Spacer(minLength: 12)

            headline
                .padding(.horizontal, 24)

            Text(String(localized: "Clone any repo into Files, write in Obsidian or anything else, and sync with one tap."))
                .font(TideFont.sans(16, relativeTo: .body))
                .foregroundStyle(Tide.heroMeta)
                .lineSpacing(4)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 24)
                .padding(.top, 16)

            Spacer(minLength: 16)

            actions
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(Tide.heroBg.ignoresSafeArea())
        .alert("Error", isPresented: Binding(
            get: { route == .welcome && state.showError },
            set: { state.showError = $0 }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(state.lastError ?? String(localized: "Unknown error"))
        }
    }

    private var wordmark: some View {
        (Text(verbatim: "gitsync").foregroundStyle(Tide.heroText)
            + Text(verbatim: ".md").foregroundStyle(Tide.heroBehind))
            .font(TideFont.mono(13, .semibold, relativeTo: .headline))
            .accessibilityLabel("GitSync.md")
    }

    private var headline: some View {
        let size: CGFloat = 44
        return VStack(alignment: .leading, spacing: -size * 0.1) {
            Text(String(localized: "Your notes.")).foregroundStyle(Tide.heroText)
            Text(String(localized: "Real git.")).foregroundStyle(Tide.heroText)
            Text(String(localized: "Always in step.")).foregroundStyle(OnboardingPalette.muted)
        }
        .font(TideFont.display(size))
        .tracking(-size * 0.045)
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }

    private var actions: some View {
        VStack(spacing: 10) {
            OnboardingPrimaryButton(
                title: isSigningIn ? String(localized: "Signing in…") : String(localized: "Continue with GitHub"),
                imageName: "GitHubLogo",
                isLoading: isSigningIn
            ) {
                continueWithGitHub()
            }
            .accessibilityIdentifier("onboarding.continueWithGitHub")

            Button {
                analytics.trackOnboardingStepViewed(.accountChoice)
                markSeen()
                navigate(to: .otherWays)
            } label: {
                Text(String(localized: "SSH, token or another host"))
                    .font(TideFont.sans(15, .medium, relativeTo: .body))
                    .foregroundStyle(Tide.heroText)
                    .frame(maxWidth: .infinity, minHeight: 54)
                    .overlay(Capsule().strokeBorder(Tide.heroLine, lineWidth: 1))
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(isSigningIn)
            .accessibilityIdentifier("onboarding.otherWays")

            if showsDemoMode {
                Button {
                    startDemo()
                } label: {
                    Text(String(localized: "Look around with a demo vault →"))
                        .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                        .foregroundStyle(Tide.heroBehind)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(isSigningIn)
                .accessibilityIdentifier("onboarding.demo")
            }
        }
    }

    /// Demo mode is a first-run affordance; it swaps in a seeded demo
    /// repository and fake credentials. Never offered on a replay.
    private var showsDemoMode: Bool {
        !isReplay && !state.hasCompletedOnboarding && state.repos.isEmpty
    }

    // MARK: - Actions

    private func navigate(to newRoute: Route) {
        withAnimation(.easeInOut(duration: 0.3)) {
            route = newRoute
        }
    }

    /// Persist that the welcome was passed so an interrupted first run is
    /// recognised as started.
    private func markSeen() {
        guard !state.hasSeenOnboarding else { return }
        state.hasSeenOnboarding = true
        state.saveGlobalSettings()
    }

    private func continueWithGitHub() {
        guard !isSigningIn else { return }
        analytics.trackOnboardingAuthStarted(method: .githubOAuth)
        analytics.trackOnboardingStepViewed(.githubSignIn)
        isSigningIn = true
        Task {
            await state.signInWithGitHub()
            isSigningIn = false
            if state.isSignedIn {
                analytics.trackOnboardingAuthCompleted(method: .githubOAuth, outcome: .succeeded)
                markSeen()
                navigate(to: .saveLocation)
            } else {
                analytics.trackOnboardingAuthCompleted(
                    method: .githubOAuth,
                    outcome: .failed,
                    errorCategory: .authFailed
                )
            }
        }
    }

    private func startDemo() {
        analytics.trackOnboardingAuthCompleted(method: .demo, outcome: .succeeded)
        analytics.trackOnboardingCompleted(authMethod: .demo, saveLocationPreference: .defaultAppFolder)
        state.activateDemoMode()
        state.hasSeenOnboarding = true
        state.hasCompletedOnboarding = true
        state.saveGlobalSettings()
        dismiss()
    }
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
    var imageName: String? = nil
    var isLoading = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if isLoading {
                    ProgressView().tint(Color(hex: 0x111113))
                } else if let imageName {
                    Image(imageName)
                        .resizable()
                        .renderingMode(.template)
                        .scaledToFit()
                        .frame(width: 20, height: 20)
                        .accessibilityHidden(true)
                }
                Text(title)
                    .font(TideFont.sans(17, .semibold, relativeTo: .body))
            }
            .foregroundStyle(Color(hex: 0x111113))
            .frame(maxWidth: .infinity, minHeight: 58)
            .background(Capsule().fill(Tide.heroText))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(isLoading)
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

import SwiftUI
import UniformTypeIdentifiers

struct SetupView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    private let analytics = OnboardingAnalyticsClient.shared

    /// Requests a return to the previous onboarding page. When non-`nil`, the
    /// account-choice step renders a back affordance so this view reads as
    /// the final page of the paged onboarding flow rather than a separate
    /// screen.
    private let onBack: (() -> Void)?

    init(onBack: (() -> Void)? = nil) {
        self.onBack = onBack
    }

    // PAT flow
    @State private var showPATFlow = false
    @State private var patToken = ""
    @State private var showPAT = false
    @State private var isSigningIn = false

    // Save location flow
    @State private var showSaveLocationStep = false
    @State private var showFolderPicker = false
    @State private var selectedFolderURL: URL? = nil

    // Analytics
    @State private var trackedSteps: Set<OnboardingAnalyticsStep> = []
    @State private var completedAuthMethod: OnboardingAnalyticsAuthMethod? = nil

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    if showSaveLocationStep {
                        saveLocationStepView
                            .transition(.asymmetric(
                                insertion: .move(edge: .trailing).combined(with: .opacity),
                                removal: .move(edge: .leading).combined(with: .opacity)
                            ))
                    } else {
                        if let onBack, !showPATFlow {
                            onboardingBackButton(onBack)
                        }

                        hero
                            .padding(.bottom, 40)

                        if showPATFlow {
                            patFlowView
                                .transition(.asymmetric(
                                    insertion: .move(edge: .trailing).combined(with: .opacity),
                                    removal: .move(edge: .leading).combined(with: .opacity)
                                ))
                        } else {
                            signInOptions
                                .transition(.asymmetric(
                                    insertion: .move(edge: .leading).combined(with: .opacity),
                                    removal: .move(edge: .trailing).combined(with: .opacity)
                                ))
                        }
                    }
                }
                .padding(.bottom, 60)
            }
            .scrollIndicators(.hidden)
            .background(Color.brutalBg)
            .navigationBarTitleDisplayMode(.inline)
            .alert("Error", isPresented: Binding(
                get: { state.showError },
                set: { state.showError = $0 }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(state.lastError ?? String(localized: "Unknown error"))
            }
            .fileImporter(
                isPresented: $showFolderPicker,
                allowedContentTypes: [.folder],
                allowsMultipleSelection: false
            ) { result in
                if case .success(let urls) = result, let url = urls.first {
                    analytics.trackOnboardingSaveLocationSelected(preference: .customFolder)
                    withAnimation(.easeInOut(duration: 0.2)) {
                        selectedFolderURL = url
                    }
                }
            }
            .onAppear {
                trackSetupStep(.accountChoice)
            }
        }
    }

    // MARK: - Hero

    private var hero: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Big monospaced title
            Text("SYNC")
                .bType(.hero)
                .foregroundStyle(Color.brutalText)
                .tracking(-2)
                .padding(.bottom, 0)

            Text(".MD")
                .bType(.hero)
                .foregroundStyle(Color.brutalAccent)
                .tracking(-2)
                .padding(.bottom, 16)

            Rectangle()
                .fill(Color.brutalBorder)
                .frame(height: 2)
                .padding(.bottom, 10)

            HStack(spacing: 10) {
                Rectangle()
                    .fill(Color.brutalBorder)
                    .frame(width: 20, height: 1)
                Text("ANY GIT REPO, SYNCED TO YOUR IPHONE")
                    .bType(.monoCaption, weight: .medium)
                    .foregroundStyle(Color.brutalText)
                    .tracking(1.5)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24)
        .padding(.top, 60)
    }

    // MARK: - Sign In Options

    private var signInOptions: some View {
        VStack(spacing: 0) {
            // Primary: OAuth
            BPrimaryButton(title: String(localized: "Sign in with GitHub"), icon: "person.fill") {
                analytics.trackOnboardingAuthStarted(method: .githubOAuth)
                trackSetupStep(.githubSignIn)
                Task {
                    await state.signInWithGitHub()
                    if state.isSignedIn {
                        completedAuthMethod = .githubOAuth
                        analytics.trackOnboardingAuthCompleted(method: .githubOAuth, outcome: .succeeded)
                        presentSaveLocationStep()
                    } else {
                        analytics.trackOnboardingAuthCompleted(
                            method: .githubOAuth,
                            outcome: .failed,
                            errorCategory: .authFailed
                        )
                    }
                }
            }
            .padding(.horizontal, 24)

            // Divider
            BDivider(label: String(localized: "or"))
                .padding(.horizontal, 24)
                .padding(.vertical, 20)

            // Secondary: PAT
            BSecondaryButton(title: String(localized: "Personal Access Token"), icon: "key.fill") {
                trackSetupStep(.personalAccessToken)
                withAnimation(.easeInOut(duration: 0.25)) {
                    showPATFlow = true
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 16)

            // Continue without GitHub for self-hosted, SSH, public, or local repos.
            BSecondaryButton(title: String(localized: "Continue without GitHub"), icon: "network") {
                completedAuthMethod = .none
                analytics.trackOnboardingAuthCompleted(method: .none, outcome: .skipped)
                presentSaveLocationStep()
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 16)

            // Demo Mode — first-run only. Existing users replaying onboarding
            // from App Settings keep their real data and must not be offered a
            // demo-mode takeover.
            if showsDemoMode {
                BGhostButton(title: String(localized: "Try Demo"), icon: "play.fill") {
                    completedAuthMethod = .demo
                    analytics.trackOnboardingAuthCompleted(method: .demo, outcome: .succeeded)
                    analytics.trackOnboardingCompleted(
                        authMethod: .demo,
                        saveLocationPreference: .defaultAppFolder
                    )
                    state.activateDemoMode()
                    state.hasSeenOnboarding = true
                    state.hasCompletedOnboarding = true
                    state.saveGlobalSettings()
                    dismiss()
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
            }
        }
    }

    /// Demo mode is a first-run affordance; it swaps in a seeded demo
    /// repository and fake credentials.
    private var showsDemoMode: Bool {
        !state.hasCompletedOnboarding && state.repos.isEmpty
    }

    // MARK: - Onboarding Back

    /// Monospaced "← BACK" affordance mirroring the PAT flow's back button so
    /// the sign-in step reads as just another onboarding page.
    private func onboardingBackButton(_ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Text("←")
                    .bType(.mono, weight: .regular)
                Text("BACK")
                    .bType(.monoSm)
                    .tracking(1)
            }
            .foregroundStyle(Color.brutalText)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 24)
        .padding(.top, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - PAT Flow

    private var patFlowView: some View {
        VStack(alignment: .leading, spacing: 20) {
            // Back button
            Button {
                withAnimation(.easeInOut(duration: 0.25)) { showPATFlow = false }
            } label: {
                HStack(spacing: 6) {
                    Text("←")
                        .bType(.mono, weight: .regular)
                    Text("BACK")
                        .bType(.monoSm)
                        .tracking(1)
                }
                .foregroundStyle(Color.brutalText)
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 24)

            // Token field
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 0) {
                    if showPAT {
                        TextField("ghp_...", text: $patToken)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .bType(.mono, weight: .regular)
                    } else {
                        SecureField("ghp_...", text: $patToken)
                            .bType(.mono, weight: .regular)
                    }
                    Spacer()
                    Button { showPAT.toggle() } label: {
                        Image(systemName: showPAT ? "eye.slash" : "eye")
                            .bType(.mono, weight: .regular)
                            .foregroundStyle(Color.brutalText)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(showPAT ? String(localized: "Hide Token") : String(localized: "Show Token"))
                    .padding(.leading, 8)
                }
                .padding(13)
                .background(Color.brutalSurface)

                Text("PERSONAL ACCESS TOKEN")
                    .bType(.monoCaption, weight: .semibold)
                    .foregroundStyle(Color.brutalText)
                    .tracking(2)

                Link(destination: URL(string: "https://github.com/settings/tokens/new?scopes=repo,user:email&description=GitSync.md")!) {
                    Text("CREATE A PAT ON GITHUB →")
                        .bType(.monoCaption, weight: .semibold)
                        .foregroundStyle(Color.brutalAccent)
                        .tracking(1)
                }
            }
            .padding(.horizontal, 24)

            // Sign in button
            BPrimaryButton(
                title: isSigningIn ? String(localized: "Signing in…") : String(localized: "Sign In"),
                isLoading: isSigningIn,
                isDisabled: patToken.trimmingCharacters(in: .whitespaces).isEmpty,
                icon: isSigningIn ? nil : "arrow.right"
            ) {
                isSigningIn = true
                analytics.trackOnboardingAuthStarted(method: .personalAccessToken)
                trackSetupStep(.personalAccessToken)
                Task {
                    await state.signInWithPAT(token: patToken)
                    isSigningIn = false
                    if state.isSignedIn {
                        completedAuthMethod = .personalAccessToken
                        analytics.trackOnboardingAuthCompleted(method: .personalAccessToken, outcome: .succeeded)
                        presentSaveLocationStep()
                    } else {
                        analytics.trackOnboardingAuthCompleted(
                            method: .personalAccessToken,
                            outcome: .failed,
                            errorCategory: .authFailed
                        )
                    }
                }
            }
            .padding(.horizontal, 24)
        }
    }

    // MARK: - Save Location Step

    private func presentSaveLocationStep() {
        trackSetupStep(.saveLocation)
        withAnimation(.easeInOut(duration: 0.3)) {
            showSaveLocationStep = true
        }
    }

    private func finishOnboarding() {
        let saveLocationPreference: OnboardingAnalyticsSaveLocationPreference
        if let url = selectedFolderURL {
            saveLocationPreference = .customFolder
            state.setDefaultSaveLocation(url)
        } else {
            saveLocationPreference = .defaultAppFolder
        }
        analytics.trackOnboardingCompleted(
            authMethod: completedAuthMethod ?? (state.isSignedIn ? .githubOAuth : .none),
            saveLocationPreference: saveLocationPreference
        )
        state.hasSeenOnboarding = true
        state.hasCompletedOnboarding = true
        state.saveGlobalSettings()
        // No-op when this view is embedded in ContentView's root hierarchy
        // (the flag change above swaps in RepoListView); dismisses the
        // fullScreenCover when onboarding was replayed from App Settings.
        dismiss()
    }

    private func trackSetupStep(_ step: OnboardingAnalyticsStep) {
        guard trackedSteps.insert(step).inserted else { return }
        analytics.trackOnboardingStepViewed(step)
    }

    private var saveLocationStepView: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Hero
            VStack(alignment: .leading, spacing: 0) {
                Text("DEFAULT")
                    .bType(.displayLg)
                    .foregroundStyle(Color.brutalText)
                    .tracking(-1)

                Text("SAVE")
                    .bType(.displayLg)
                    .foregroundStyle(Color.brutalText)
                    .tracking(-1)

                Text("LOCATION")
                    .bType(.displayLg)
                    .foregroundStyle(Color.brutalAccent)
                    .tracking(-1)
                    .padding(.bottom, 12)

                Rectangle()
                    .fill(Color.brutalBorder)
                    .frame(height: 2)
                    .padding(.bottom, 8)

                Text("CHOOSE WHERE NEW REPOSITORIES ARE SAVED")
                    .bType(.monoCaption, weight: .medium)
                    .foregroundStyle(Color.brutalText)
                    .tracking(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24)
            .padding(.top, 60)
            .padding(.bottom, 32)

            // Selected location
            if let url = selectedFolderURL {
                BCard(padding: 14, bg: .brutalSurface) {
                    HStack(spacing: 12) {
                        // Decorative emoji glyph (folder ornament, carries no
                        // information) - fixed size per Issue #16's decorative
                        // allowance; the adjacent filename/path do scale.
                        Text("📁")
                            .font(.system(size: 22))

                        VStack(alignment: .leading, spacing: 3) {
                            Text(url.lastPathComponent)
                                .bType(.mono, weight: .semibold)
                                .foregroundStyle(Color.brutalText)
                            Text(url.path)
                                .bType(.monoSm, weight: .regular)
                                .foregroundStyle(Color.brutalText)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }

                        Spacer()

                        Button {
                            withAnimation(.easeInOut(duration: 0.2)) { selectedFolderURL = nil }
                        } label: {
                            Image(systemName: "xmark")
                                .bType(.mono, weight: .bold)
                                .foregroundStyle(Color.brutalText)
                                .padding(6)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(String(localized: "Use Default Location"))
                    }
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
                .transition(.scale.combined(with: .opacity))
            } else {
                // Info
                HStack(spacing: 8) {
                    Image(systemName: "info.circle")
                        .bType(.monoCaption, weight: .regular)
                        .foregroundStyle(Color.brutalText)
                        .accessibilityHidden(true)
                    Text("Without a default, repos save to Files › On My iPhone › GitSync.md")
                        .bType(.mono, weight: .regular)
                        .foregroundStyle(Color.brutalText)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
            }

            VStack(spacing: 12) {
                BPrimaryButton(
                    title: selectedFolderURL != nil ? String(localized: "Change Location") : String(localized: "Choose Location"),
                    icon: "folder.badge.plus"
                ) { showFolderPicker = true }
                .padding(.horizontal, 24)

                BGhostButton(
                    title: selectedFolderURL != nil ? String(localized: "Continue →") : String(localized: "Skip for Now")
                ) { finishOnboarding() }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
            }
        }
    }
}

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

    /// - Parameters:
    ///   - onBack: Returns to the welcome screen from the account choices.
    ///   - startAtSaveLocation: Opens directly on the default-save-location
    ///     step (the user already signed in from the welcome screen).
    ///   - completedAuthMethod: How the user signed in before arriving here.
    init(
        onBack: (() -> Void)? = nil,
        startAtSaveLocation: Bool = false,
        completedAuthMethod: OnboardingAnalyticsAuthMethod? = nil
    ) {
        self.onBack = onBack
        _showSaveLocationStep = State(initialValue: startAtSaveLocation)
        _completedAuthMethod = State(initialValue: completedAuthMethod)
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

    @FocusState private var patFocused: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if showSaveLocationStep {
                        saveLocationStepView
                            .transition(.asymmetric(
                                insertion: .move(edge: .trailing).combined(with: .opacity),
                                removal: .move(edge: .leading).combined(with: .opacity)
                            ))
                    } else if showPATFlow {
                        patFlowView
                            .transition(.asymmetric(
                                insertion: .move(edge: .trailing).combined(with: .opacity),
                                removal: .move(edge: .leading).combined(with: .opacity)
                            ))
                    } else {
                        accountChoiceView
                            .transition(.asymmetric(
                                insertion: .move(edge: .leading).combined(with: .opacity),
                                removal: .move(edge: .trailing).combined(with: .opacity)
                            ))
                    }
                }
                .padding(.bottom, 40)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
            .background(Tide.paper.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
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
                trackSetupStep(showSaveLocationStep ? .saveLocation : .accountChoice)
            }
        }
    }

    // MARK: - Account choice

    private var accountChoiceView: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let onBack {
                AddVaultTopBar(onBack: onBack)
                    .padding(.top, 8)
            } else {
                Color.clear.frame(height: 52)
            }

            wordmark
                .padding(.horizontal, 20)
                .padding(.top, 8)

            AddVaultHeadline(
                title: String(localized: "Other ways"),
                muted: String(localized: "in."),
                subtitle: String(localized: "Use a GitHub token, or continue without GitHub for SSH keys, HTTPS tokens, GitLab, Gitea, Codeberg or your own server."),
                size: 38
            )
            .padding(.top, 18)

            signInOptions
                .padding(.top, 32)
        }
    }

    private var wordmark: some View {
        (Text(verbatim: "gitsync").foregroundStyle(Tide.ink)
            + Text(verbatim: ".md").foregroundStyle(Tide.behind))
            .font(TideFont.mono(13, .semibold, relativeTo: .headline))
            .accessibilityLabel("GitSync.md")
    }

    // MARK: - Sign In Options

    private var signInOptions: some View {
        // GitHub OAuth and the demo vault live on the welcome screen; these
        // are the remaining ways in.
        VStack(spacing: 10) {
            // GitHub without OAuth: a personal access token.
            BSecondaryButton(title: String(localized: "Personal Access Token"), icon: "key") {
                trackSetupStep(.personalAccessToken)
                withAnimation(.easeInOut(duration: 0.25)) {
                    showPATFlow = true
                }
            }

            // Continue without GitHub for self-hosted, SSH, public, or local repos.
            BSecondaryButton(title: String(localized: "Continue without GitHub"), icon: "network") {
                completedAuthMethod = OnboardingAnalyticsAuthMethod.none
                analytics.trackOnboardingAuthCompleted(method: .none, outcome: .skipped)
                presentSaveLocationStep()
            }

            Text(String(localized: "Without GitHub: SSH, HTTPS tokens, any host, or local folders."))
                .font(TideFont.sans(13, relativeTo: .footnote))
                .foregroundStyle(Tide.inkFaint)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity)
                .padding(.top, 2)
        }
        .padding(.horizontal, Tide.gutter)
    }

    // MARK: - PAT Flow

    private var patFlowView: some View {
        VStack(alignment: .leading, spacing: 0) {
            AddVaultTopBar {
                withAnimation(.easeInOut(duration: 0.25)) { showPATFlow = false }
            }
            .padding(.top, 8)

            AddVaultHeadline(
                title: String(localized: "Personal access token"),
                subtitle: String(localized: "Paste a GitHub token with repo access. It is stored in the Keychain.")
            )
            .padding(.top, 6)

            VStack(alignment: .leading, spacing: 8) {
                AddVaultSectionLabel(title: String(localized: "Token"))

                HStack(spacing: 0) {
                    Group {
                        if showPAT {
                            TextField("ghp_...", text: $patToken)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                        } else {
                            SecureField("ghp_...", text: $patToken)
                        }
                    }
                    .focused($patFocused)
                    .font(TideFont.mono(15, .medium, relativeTo: .body))
                    .foregroundStyle(Tide.ink)
                    .accessibilityLabel(String(localized: "Personal access token"))

                    Button { showPAT.toggle() } label: {
                        Image(systemName: showPAT ? "eye.slash" : "eye")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(Tide.inkMid)
                            // Icon-only toggle: 44x44pt hit target.
                            .frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(showPAT ? String(localized: "Hide Token") : String(localized: "Show Token"))
                }
                .padding(.trailing, -10)
                .addVaultFieldChrome(focused: patFocused)
                .padding(.horizontal, Tide.gutter)

                Link(destination: URL(string: "https://github.com/settings/tokens/new?scopes=repo,user:email&description=GitSync.md")!) {
                    HStack(spacing: 5) {
                        Text(String(localized: "Create a PAT on GitHub"))
                            .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                        Image(systemName: "arrow.up.right")
                            .font(.system(size: 11, weight: .semibold))
                            .accessibilityHidden(true)
                    }
                    .foregroundStyle(Tide.behind)
                    // 44x44pt hit target, text stays compact.
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
                }
                .padding(.horizontal, 20)
            }
            .padding(.top, 22)

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
            .padding(.horizontal, Tide.gutter)
            .padding(.top, 18)
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
            HStack {
                wordmark
                Spacer()
                if state.isSignedIn && !state.gitHubUsername.isEmpty {
                    Text(verbatim: "@\(state.gitHubUsername)")
                        .font(TideFont.mono(12, relativeTo: .caption))
                        .foregroundStyle(Tide.inkMid)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 60)

            AddVaultHeadline(
                title: String(localized: "Default save"),
                muted: String(localized: "location."),
                subtitle: String(localized: "Choose where new repositories are saved."),
                size: 38
            )
            .padding(.top, 18)

            // Selected location
            Group {
                if let url = selectedFolderURL {
                    AddVaultCard {
                        HStack(spacing: 14) {
                            AddVaultIconTile(systemImage: "folder", size: 40)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(url.lastPathComponent)
                                    .font(TideFont.sans(16, .semibold, relativeTo: .body))
                                    .foregroundStyle(Tide.ink)
                                    .lineLimit(1)
                                Text(url.path)
                                    .font(TideFont.mono(11))
                                    .foregroundStyle(Tide.inkFaint)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            }
                            Spacer(minLength: 8)
                            Button {
                                withAnimation(.easeInOut(duration: 0.2)) { selectedFolderURL = nil }
                            } label: {
                                Image(systemName: "xmark")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(Tide.inkMid)
                                    // Icon-only clear affordance: 44x44pt hit target.
                                    .frame(width: 44, height: 44)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(String(localized: "Use Default Location"))
                        }
                        .padding(.vertical, 12)
                    }
                    .transition(.scale.combined(with: .opacity))
                } else {
                    AddVaultCard {
                        HStack(spacing: 14) {
                            AddVaultIconTile(systemImage: "iphone", size: 40)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(String(localized: "On My iPhone"))
                                    .font(TideFont.sans(16, .semibold, relativeTo: .body))
                                    .foregroundStyle(Tide.ink)
                                Text("Without a default, repos save to Files › On My iPhone › GitSync.md")
                                    .font(TideFont.sans(13, relativeTo: .footnote))
                                    .foregroundStyle(Tide.inkFaint)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.vertical, 14)
                    }
                }
            }
            .padding(.top, 24)

            VStack(spacing: 10) {
                BPrimaryButton(
                    title: selectedFolderURL != nil ? String(localized: "Change Location") : String(localized: "Choose Location"),
                    icon: "folder.badge.plus"
                ) { showFolderPicker = true }

                Button {
                    finishOnboarding()
                } label: {
                    Text(selectedFolderURL != nil ? String(localized: "Continue") : String(localized: "Skip for Now"))
                        .font(TideFont.sans(15, .medium, relativeTo: .body))
                        .foregroundStyle(Tide.ink)
                        .frame(maxWidth: .infinity, minHeight: 54)
                        .background(Capsule().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, Tide.gutter)
            .padding(.top, 24)
        }
    }
}

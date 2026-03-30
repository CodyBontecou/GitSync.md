import SwiftUI
import UniformTypeIdentifiers

struct SetupView: View {
    @Environment(AppState.self) private var state

    // PAT flow
    @State private var showPATFlow = false
    @State private var patToken = ""
    @State private var showPAT = false
    @State private var isSigningIn = false

    // Save location flow
    @State private var showSaveLocationStep = false
    @State private var showFolderPicker = false
    @State private var selectedFolderURL: URL? = nil

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
                    withAnimation(.easeInOut(duration: 0.2)) {
                        selectedFolderURL = url
                    }
                }
            }
        }
    }

    // MARK: - Hero

    private var hero: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Big monospaced title
            Text("SYNC")
                .font(.system(size: 72, weight: .black))
                .foregroundStyle(Color.brutalText)
                .tracking(-2)
                .padding(.bottom, 0)

            Text(".MD")
                .font(.system(size: 72, weight: .black))
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
                Text("MARKDOWN NOTES SYNCED WITH GIT")
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)
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
            BPrimaryButton(title: "Sign in with GitHub", icon: "person.fill") {
                Task {
                    await state.signInWithGitHub()
                    if state.isSignedIn {
                        presentSaveLocationStep()
                    }
                }
            }
            .padding(.horizontal, 24)

            // Divider
            BDivider(label: "or")
                .padding(.horizontal, 24)
                .padding(.vertical, 20)

            // Secondary: PAT
            BSecondaryButton(title: "Personal Access Token", icon: "key.fill") {
                withAnimation(.easeInOut(duration: 0.25)) {
                    showPATFlow = true
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 16)

            // Demo Mode
            BGhostButton(title: "Try Demo", icon: "play.fill") {
                state.activateDemoMode()
                state.hasCompletedOnboarding = true
                state.saveGlobalSettings()
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
        }
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
                        .font(.system(size: 14, design: .monospaced))
                    Text("BACK")
                        .font(.system(size: 11, weight: .medium, design: .monospaced))
                        .tracking(1)
                }
                .foregroundStyle(Color.brutalTextMid)
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
                            .font(.system(size: 15, design: .monospaced))
                    } else {
                        SecureField("ghp_...", text: $patToken)
                            .font(.system(size: 15, design: .monospaced))
                    }
                    Spacer()
                    Button { showPAT.toggle() } label: {
                        Image(systemName: showPAT ? "eye.slash" : "eye")
                            .font(.system(size: 14))
                            .foregroundStyle(Color.brutalTextMid)
                    }
                    .buttonStyle(.plain)
                    .padding(.leading, 8)
                }
                .padding(13)
                .background(Color.brutalSurface)
                .overlay(Rectangle().stroke(Color.brutalBorderSoft, lineWidth: 1))

                Text("PERSONAL ACCESS TOKEN")
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Color.brutalTextFaint)
                    .tracking(2)

                Link(destination: URL(string: "https://github.com/settings/tokens/new?scopes=repo,user:email&description=Sync.md")!) {
                    Text("CREATE A PAT ON GITHUB →")
                        .font(.system(size: 10, weight: .semibold, design: .monospaced))
                        .foregroundStyle(Color.brutalAccent)
                        .tracking(1)
                }
            }
            .padding(.horizontal, 24)

            // Sign in button
            BPrimaryButton(
                title: isSigningIn ? "Signing in…" : "Sign In",
                isLoading: isSigningIn,
                isDisabled: patToken.trimmingCharacters(in: .whitespaces).isEmpty,
                icon: isSigningIn ? nil : "arrow.right"
            ) {
                isSigningIn = true
                Task {
                    await state.signInWithPAT(token: patToken)
                    isSigningIn = false
                    if state.isSignedIn { presentSaveLocationStep() }
                }
            }
            .padding(.horizontal, 24)
        }
    }

    // MARK: - Save Location Step

    private func presentSaveLocationStep() {
        withAnimation(.easeInOut(duration: 0.3)) {
            showSaveLocationStep = true
        }
    }

    private func finishOnboarding() {
        if let url = selectedFolderURL {
            state.setDefaultSaveLocation(url)
        }
        state.hasCompletedOnboarding = true
        state.saveGlobalSettings()
    }

    private var saveLocationStepView: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Hero
            VStack(alignment: .leading, spacing: 0) {
                Text("DEFAULT")
                    .font(.system(size: 48, weight: .black))
                    .foregroundStyle(Color.brutalText)
                    .tracking(-1)

                Text("SAVE")
                    .font(.system(size: 48, weight: .black))
                    .foregroundStyle(Color.brutalText)
                    .tracking(-1)

                Text("LOCATION")
                    .font(.system(size: 48, weight: .black))
                    .foregroundStyle(Color.brutalAccent)
                    .tracking(-1)
                    .padding(.bottom, 12)

                Rectangle()
                    .fill(Color.brutalBorder)
                    .frame(height: 2)
                    .padding(.bottom, 8)

                Text("CHOOSE WHERE NEW REPOSITORIES ARE SAVED")
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)
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
                        Text("📁")
                            .font(.system(size: 22))

                        VStack(alignment: .leading, spacing: 3) {
                            Text(url.lastPathComponent)
                                .font(.system(size: 14, weight: .semibold, design: .monospaced))
                                .foregroundStyle(Color.brutalText)
                            Text(url.path)
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(Color.brutalTextFaint)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }

                        Spacer()

                        Button {
                            withAnimation(.easeInOut(duration: 0.2)) { selectedFolderURL = nil }
                        } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(Color.brutalTextMid)
                                .padding(6)
                                .overlay(Rectangle().stroke(Color.brutalBorderSoft, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
                .transition(.scale.combined(with: .opacity))
            } else {
                // Info
                HStack(spacing: 8) {
                    Image(systemName: "info.circle")
                        .font(.system(size: 12))
                        .foregroundStyle(Color.brutalTextFaint)
                    Text("Without a default, repos save to Files › On My iPhone › Sync.md")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Color.brutalTextFaint)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 16)
            }

            VStack(spacing: 12) {
                BPrimaryButton(
                    title: selectedFolderURL != nil ? "Change Location" : "Choose Location",
                    icon: "folder.badge.plus"
                ) { showFolderPicker = true }
                .padding(.horizontal, 24)

                BGhostButton(
                    title: selectedFolderURL != nil ? "Continue →" : "Skip for Now"
                ) { finishOnboarding() }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
            }
        }
    }
}

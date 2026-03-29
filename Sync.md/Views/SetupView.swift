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
    @State private var saveLocationAppeared = false

    // Animation
    @State private var heroAppeared = false
    @State private var contentAppeared = false

    var body: some View {
        NavigationStack {
            ZStack {
                FloatingOrbs()

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
                                .padding(.bottom, 32)

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
            }
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
                    withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) {
                        selectedFolderURL = url
                    }
                }
            }
        }
    }

    // MARK: - Hero

    private var hero: some View {
        VStack(spacing: 16) {
            ZStack {
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [SyncTheme.blue.opacity(0.3), .clear],
                            center: .center,
                            startRadius: 20,
                            endRadius: 60
                        )
                    )
                    .frame(width: 120, height: 120)
                    .blur(radius: 10)

                ZStack {
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .fill(.ultraThinMaterial)
                        .frame(width: 80, height: 80)
                        .shadow(color: SyncTheme.blue.opacity(0.2), radius: 20, x: 0, y: 8)

                    Image(systemName: "arrow.triangle.2.circlepath.doc.on.clipboard")
                        .font(.system(size: 34, weight: .medium))
                        .foregroundStyle(SyncTheme.primaryGradient)
                }
            }
            .scaleEffect(heroAppeared ? 1 : 0.5)
            .opacity(heroAppeared ? 1 : 0)

            VStack(spacing: 6) {
                Text("Sync.md")
                    .font(.system(size: 34, weight: .bold, design: .rounded))

                Text("Markdown notes synced with Git")
                    .font(.system(size: 15, weight: .medium, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            .opacity(heroAppeared ? 1 : 0)
            .offset(y: heroAppeared ? 0 : 10)
        }
        .padding(.top, 60)
        .onAppear {
            withAnimation(.spring(response: 0.8, dampingFraction: 0.7).delay(0.1)) {
                heroAppeared = true
            }
            withAnimation(.spring(response: 0.6, dampingFraction: 0.8).delay(0.4)) {
                contentAppeared = true
            }
        }
    }

    // MARK: - Sign In Options

    private var signInOptions: some View {
        VStack(spacing: 20) {
            // Primary: OAuth
            Button {
                Task {
                    await state.signInWithGitHub()
                    if state.isSignedIn {
                        presentSaveLocationStep()
                    }
                }
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "person.circle.fill")
                        .font(.system(size: 20))
                    Text("Sign in with GitHub")
                }
            }
            .buttonStyle(LiquidButtonStyle(gradient: SyncTheme.primaryGradient))
            .padding(.horizontal, 24)
            .staggeredAppear(index: 0)

            // Divider
            HStack(spacing: 12) {
                Capsule()
                    .fill(.quaternary)
                    .frame(height: 1)
                Text("or")
                    .font(.system(size: 13, weight: .medium, design: .rounded))
                    .foregroundStyle(.tertiary)
                Capsule()
                    .fill(.quaternary)
                    .frame(height: 1)
            }
            .padding(.horizontal, 40)
            .staggeredAppear(index: 1)

            // Secondary: PAT
            Button {
                withAnimation(.spring(response: 0.5, dampingFraction: 0.85)) {
                    showPATFlow = true
                }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "key.fill")
                        .font(.system(size: 14))
                    Text("Use a Personal Access Token")
                }
            }
            .buttonStyle(SubtleButtonStyle())
            .staggeredAppear(index: 2)

            // Demo Mode
            Button {
                state.activateDemoMode()
                state.hasCompletedOnboarding = true
                state.saveGlobalSettings()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 14))
                    Text("Try Demo")
                }
            }
            .buttonStyle(SubtleButtonStyle())
            .staggeredAppear(index: 3)
        }
        .opacity(contentAppeared ? 1 : 0)
    }

    // MARK: - PAT Flow

    private var patFlowView: some View {
        VStack(spacing: 20) {
            // Back button
            HStack {
                Button {
                    withAnimation(.spring(response: 0.5, dampingFraction: 0.85)) {
                        showPATFlow = false
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 14, weight: .semibold))
                        Text("Back")
                            .font(.system(size: 15, weight: .medium, design: .rounded))
                    }
                    .foregroundStyle(SyncTheme.accent)
                }
                Spacer()
            }
            .padding(.horizontal, 20)

            // PAT token field
            VStack(alignment: .leading, spacing: 8) {
                Label("Personal Access Token", systemImage: "key.fill")
                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 4)

                HStack(spacing: 10) {
                    Group {
                        if showPAT {
                            TextField("ghp_...", text: $patToken)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                        } else {
                            SecureField("ghp_...", text: $patToken)
                        }
                    }
                    .font(.system(size: 16, design: .rounded))

                    Button { showPAT.toggle() } label: {
                        Image(systemName: showPAT ? "eye.slash.fill" : "eye.fill")
                            .font(.system(size: 15))
                            .foregroundStyle(.tertiary)
                    }
                }
                .padding(14)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                Link(destination: URL(string: "https://github.com/settings/tokens/new?scopes=repo,user:email&description=Sync.md")!) {
                    HStack(spacing: 4) {
                        Text("Create a PAT on GitHub")
                        Image(systemName: "arrow.up.right")
                            .font(.system(size: 10, weight: .bold))
                    }
                    .font(.system(size: 13, weight: .medium, design: .rounded))
                    .foregroundStyle(SyncTheme.accent)
                }
                .padding(.horizontal, 4)
            }
            .padding(.horizontal, 20)

            // Sign in button
            Button {
                isSigningIn = true
                Task {
                    await state.signInWithPAT(token: patToken)
                    isSigningIn = false
                    if state.isSignedIn {
                        presentSaveLocationStep()
                    }
                }
            } label: {
                HStack(spacing: 10) {
                    if isSigningIn {
                        ProgressView()
                            .tint(.white)
                            .controlSize(.small)
                        Text("Signing in…")
                    } else {
                        Image(systemName: "arrow.right.circle.fill")
                        Text("Sign In")
                    }
                }
            }
            .buttonStyle(LiquidButtonStyle(gradient: SyncTheme.primaryGradient))
            .disabled(patToken.trimmingCharacters(in: .whitespaces).isEmpty || isSigningIn)
            .opacity(patToken.trimmingCharacters(in: .whitespaces).isEmpty || isSigningIn ? 0.6 : 1)
            .padding(.horizontal, 24)
        }
    }

    // MARK: - Save Location Step

    private func presentSaveLocationStep() {
        withAnimation(.spring(response: 0.5, dampingFraction: 0.85)) {
            showSaveLocationStep = true
        }
        withAnimation(.spring(response: 0.6, dampingFraction: 0.8).delay(0.2)) {
            saveLocationAppeared = true
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
        VStack(spacing: 0) {
            // Hero
            VStack(spacing: 16) {
                ZStack {
                    Circle()
                        .fill(
                            RadialGradient(
                                colors: [SyncTheme.blue.opacity(0.3), .clear],
                                center: .center,
                                startRadius: 20,
                                endRadius: 60
                            )
                        )
                        .frame(width: 120, height: 120)
                        .blur(radius: 10)

                    ZStack {
                        RoundedRectangle(cornerRadius: 24, style: .continuous)
                            .fill(.ultraThinMaterial)
                            .frame(width: 80, height: 80)
                            .shadow(color: SyncTheme.blue.opacity(0.2), radius: 20, x: 0, y: 8)

                        Image(systemName: "folder.fill.badge.gearshape")
                            .font(.system(size: 34, weight: .medium))
                            .foregroundStyle(SyncTheme.primaryGradient)
                    }
                }
                .scaleEffect(saveLocationAppeared ? 1 : 0.5)
                .opacity(saveLocationAppeared ? 1 : 0)

                VStack(spacing: 6) {
                    Text("Default Save Location")
                        .font(.system(size: 28, weight: .bold, design: .rounded))

                    Text("Choose where new repositories\nare saved on your device")
                        .font(.system(size: 15, weight: .medium, design: .rounded))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .opacity(saveLocationAppeared ? 1 : 0)
                .offset(y: saveLocationAppeared ? 0 : 10)
            }
            .padding(.top, 60)
            .padding(.bottom, 32)

            // Content
            VStack(spacing: 20) {
                // Selected location display
                if let url = selectedFolderURL {
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(SyncTheme.blue.opacity(0.12))
                                .frame(width: 40, height: 40)
                            Image(systemName: "folder.fill")
                                .font(.system(size: 18))
                                .foregroundStyle(SyncTheme.accent)
                        }

                        VStack(alignment: .leading, spacing: 2) {
                            Text(url.lastPathComponent)
                                .font(.system(size: 15, weight: .medium, design: .rounded))
                            Text(url.path)
                                .font(.system(size: 12, design: .rounded))
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }

                        Spacer()

                        Button {
                            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                                selectedFolderURL = nil
                            }
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 20))
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .padding(14)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .padding(.horizontal, 24)
                    .transition(.scale.combined(with: .opacity))
                } else {
                    // Info hint
                    HStack(spacing: 8) {
                        Image(systemName: "info.circle.fill")
                            .font(.system(size: 13))
                            .foregroundStyle(.tertiary)
                        Text("Without a default, repos save to\nFiles › On My iPhone › Sync.md")
                            .font(.system(size: 13, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 28)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                // Choose folder button
                Button {
                    showFolderPicker = true
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "folder.badge.plus")
                            .font(.system(size: 18))
                        Text(selectedFolderURL != nil ? "Change Location" : "Choose Location")
                    }
                }
                .buttonStyle(LiquidButtonStyle(gradient: SyncTheme.primaryGradient))
                .padding(.horizontal, 24)

                // Skip / Continue
                Button {
                    finishOnboarding()
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: selectedFolderURL != nil ? "checkmark.circle.fill" : "arrow.right.circle.fill")
                            .font(.system(size: 14))
                        Text(selectedFolderURL != nil ? "Continue" : "Skip for Now")
                    }
                }
                .buttonStyle(SubtleButtonStyle())
            }
            .opacity(saveLocationAppeared ? 1 : 0)
        }
    }
}

// MARK: - Liquid Text Field

struct LiquidTextField: View {
    let icon: String
    let label: String
    let placeholder: String
    @Binding var text: String
    var disableAutocorrect: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(label, systemImage: icon)
                .font(.system(size: 13, weight: .semibold, design: .rounded))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 4)

            TextField(placeholder, text: $text)
                .font(.system(size: 16, design: .rounded))
                .autocorrectionDisabled(disableAutocorrect)
                .textInputAutocapitalization(disableAutocorrect ? .never : .words)
                .padding(14)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .padding(.horizontal, 20)
    }
}

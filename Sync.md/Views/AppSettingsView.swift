import SwiftUI
import UniformTypeIdentifiers

struct AppSettingsView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    @State private var showFolderPicker = false
    @State private var showClearConfirm = false
    @State private var showMailCompose = false
    @State private var showOnboarding = false
    @State private var showPremiumSettings = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.brutalBg.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 12) {
                        // Account
                        settingsSection(title: String(localized: "Account")) {
                            if state.isSignedIn {
                                VStack(spacing: 0) {
                                    if !state.gitHubDisplayName.isEmpty {
                                        dataRow(label: String(localized: "Name"), value: state.gitHubDisplayName)
                                        BDivider().padding(.horizontal, 16)
                                    }
                                    dataRow(label: String(localized: "Username"), value: "@\(state.gitHubUsername)")
                                    if !state.defaultAuthorEmail.isEmpty {
                                        BDivider().padding(.horizontal, 16)
                                        dataRow(label: String(localized: "Email"), value: state.defaultAuthorEmail)
                                    }
                                }
                            } else {
                                Button {
                                    Task { await state.signInWithGitHub() }
                                } label: {
                                    HStack(spacing: 12) {
                                        Image(systemName: "person.crop.circle.badge.plus")
                                            .accessibilityHidden(true)
                                        Text("Sign in with GitHub")
                                            .bType(.mono, weight: .semibold)
                                        Spacer()
                                        Image(systemName: "chevron.right")
                                            .accessibilityHidden(true)
                                    }
                                    .padding(.horizontal, 16)
                                }
                                .frame(maxWidth: .infinity, minHeight: 44)
                                .contentShape(Rectangle())
                                .buttonStyle(.plain)
                                .accessibilityLabel(String(localized: "Sign in with GitHub"))
                            }
                        }

                        // Default Save Location
                        settingsSection(title: String(localized: "Default Save Location")) {
                            VStack(spacing: 0) {
                                if let url = state.resolvedDefaultSaveURL {
                                    HStack(spacing: 12) {
                                        // Decorative emoji glyph (folder ornament, carries no
                                        // information) - fixed per Issue #16's decorative allowance.
                                        Text("📁").font(.system(size: 18))
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(url.lastPathComponent)
                                                .bType(.monoSm, weight: .semibold)
                                                .foregroundStyle(Color.brutalText)
                                            Text(url.path)
                                                .bType(.mono, weight: .regular)
                                                .foregroundStyle(Color.brutalText)
                                                .lineLimit(1)
                                                .truncationMode(.middle)
                                        }
                                        Spacer()
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 14)

                                    BDivider().padding(.horizontal, 16)

                                    HStack(spacing: 20) {
                                        Button {
                                            showFolderPicker = true
                                        } label: {
                                            Text("CHANGE")
                                                .bType(.mono, weight: .bold)
                                                .foregroundStyle(Color.brutalAccent)
                                                .tracking(1)
                                                // 44x44pt hit target, text stays compact.
                                                .frame(minWidth: 44, minHeight: 44)
                                                .contentShape(Rectangle())
                                        }
                                        .buttonStyle(.plain)

                                        Spacer()

                                        Button {
                                            showClearConfirm = true
                                        } label: {
                                            Text("REMOVE")
                                                .bType(.mono, weight: .bold)
                                                .foregroundStyle(Color.brutalError)
                                                .tracking(1)
                                                // 44x44pt hit target, text stays compact.
                                                .frame(minWidth: 44, minHeight: 44)
                                                .contentShape(Rectangle())
                                        }
                                        .buttonStyle(.plain)
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 12)
                                } else {
                                    VStack(spacing: 10) {
                                        HStack(spacing: 6) {
                                            Image(systemName: "info.circle")
                                                .bType(.monoCaption, weight: .regular)
                                                .foregroundStyle(Color.brutalText)
                                                .accessibilityHidden(true)
                                            Text("New repositories will be saved to the app's default location.")
                                                .bType(.mono, weight: .regular)
                                                .foregroundStyle(Color.brutalText)
                                        }
                                        .padding(.horizontal, 16)
                                        .padding(.top, 14)

                                        BDivider().padding(.horizontal, 16)

                                        Button {
                                            showFolderPicker = true
                                        } label: {
                                            HStack(spacing: 6) {
                                                Text("📂")
                                                Text("CHOOSE DEFAULT LOCATION")
                                                    .bType(.mono, weight: .bold)
                                                    .foregroundStyle(Color.brutalAccent)
                                                    .tracking(1)
                                            }
                                            .padding(.horizontal, 16)
                                            .padding(.vertical, 12)
                                            // 44x44pt hit target, text stays compact.
                                            .frame(minWidth: 44, minHeight: 44)
                                            .contentShape(Rectangle())
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                        }

                        // Optional subscription. Existing manual Git, Shortcuts,
                        // callbacks, and local repository features remain available.
                        // Hidden behind the legacy gitSyncAssistEnabled feature flag until the
                        // Background Sync tier is ready to ship.
                        if FeatureFlags.gitSyncAssistEnabled {
                            settingsSection(title: String(localized: "Background Sync")) {
                                actionRow(
                                    icon: "⚡️",
                                    title: String(localized: "Background Sync"),
                                    subtitle: String(localized: "Independent automatic pull and push controls")
                                ) { showPremiumSettings = true }
                            }
                        }

                        // Shortcuts
                        settingsSection(title: String(localized: "Shortcuts")) {
                            HStack(alignment: .top, spacing: 14) {
                                // Decorative emoji glyph sized for the fixed 28pt icon
                                // column - non-text, so fixed per Issue #16 (scaling would
                                // break column alignment; the adjacent texts do scale).
                                Text("⚡️")
                                    .font(.system(size: 18))
                                    .frame(width: 28)

                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Git actions from Shortcuts")
                                        .bType(.body, weight: .medium)
                                        .foregroundStyle(Color.brutalText)
                                    Text("Use Pull All, Pull Repository, Push Repository, or Sync Repository in Apple Shortcuts. Push and Sync stage all non-ignored changes, stop on conflicts or unsafe remote state, and publish directly to your configured remote.")
                                        .bType(.monoSm, weight: .regular)
                                        .foregroundStyle(Color.brutalText)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 14)
                        }

                        // Feedback
                        settingsSection(title: String(localized: "Feedback")) {
                            VStack(spacing: 0) {
                                actionRow(
                                    icon: "✉️",
                                    title: String(localized: "Send Feedback"),
                                    subtitle: String(localized: "Questions, ideas, or issues")
                                ) {
                                    if FeedbackHelper.canSendMail {
                                        showMailCompose = true
                                    } else {
                                        FeedbackHelper.openMailClient()
                                    }
                                }
                                BDivider().padding(.horizontal, 16)
                                actionRow(
                                    icon: "💬",
                                    title: String(localized: "Join our Discord"),
                                    subtitle: String(localized: "Chat with us on Discord")
                                ) {
                                    if let url = URL(string: "https://discord.gg/RaQYS4t6gn") {
                                        UIApplication.shared.open(url)
                                    }
                                }
                            }
                        }

                        // Help
                        settingsSection(title: String(localized: "Help")) {
                            VStack(spacing: 0) {
                                actionRow(
                                    icon: "👋",
                                    title: String(localized: "Show App Tour"),
                                    subtitle: String(localized: "Re-experience the onboarding flow")
                                ) {
                                    showOnboarding = true
                                }
                            }
                        }

                        // About
                        settingsSection(title: String(localized: "About")) {
                            VStack(spacing: 0) {
                                dataRow(
                                    label: String(localized: "Version"),
                                    value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
                                )
                                BDivider().padding(.horizontal, 16)
                                dataRow(label: String(localized: "Repositories"), value: "\(state.visibleRepos.count)")
                            }
                        }
                    }
                    .padding(.top, 12)
                    .padding(.bottom, 40)
                }
                .scrollIndicators(.hidden)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text(String(localized: "App Settings").uppercased())
                        .bType(.monoCaption, weight: .black)
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
            .sheet(isPresented: $showMailCompose) { MailComposeView() }
            .sheet(isPresented: $showPremiumSettings) { PremiumSettingsView() }
            .fullScreenCover(isPresented: $showOnboarding) { OnboardingView(isReplay: true) }
            .fileImporter(
                isPresented: $showFolderPicker,
                allowedContentTypes: [.folder],
                allowsMultipleSelection: false
            ) { result in
                if case .success(let urls) = result, let url = urls.first {
                    state.setDefaultSaveLocation(url)
                }
            }
            .alert("Remove Default Location?", isPresented: $showClearConfirm) {
                Button("Cancel", role: .cancel) {}
                Button("Remove", role: .destructive) { state.clearDefaultSaveLocation() }
            } message: {
                Text("New repositories will be saved to the app's default location instead.")
            }
        }
    }

    // MARK: - Layout Helpers

    private func settingsSection<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            BSectionHeader(title: title)
                .padding(.horizontal, 20)

            BCard(padding: 0) {
                content()
            }
            .padding(.horizontal, 20)
        }
    }

    private func dataRow(label: String, value: String) -> some View {
        HStack {
            Text(label.uppercased())
                .bType(.monoCaption, weight: .medium)
                .foregroundStyle(Color.brutalText)
                .tracking(1)
            Spacer()
            Text(value)
                .bType(.monoSm, weight: .regular)
                .foregroundStyle(Color.brutalText)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
    }

    private func actionRow(icon: String, title: String, subtitle: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                // Decorative emoji glyph sized for the fixed 28pt icon
                // column - non-text, so fixed per Issue #16 (scaling would
                // break column alignment; the adjacent title/subtitle do scale).
                Text(icon)
                    .font(.system(size: 18))
                    .frame(width: 28)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .bType(.body, weight: .medium)
                        .foregroundStyle(Color.brutalText)
                    Text(subtitle)
                        .bType(.monoSm, weight: .regular)
                        .foregroundStyle(Color.brutalText)
                }

                Spacer()

                Text("→")
                    .bType(.monoSm, weight: .regular)
                    .foregroundStyle(Color.brutalText)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(title))
        .accessibilityHint(Text(subtitle))
    }
}

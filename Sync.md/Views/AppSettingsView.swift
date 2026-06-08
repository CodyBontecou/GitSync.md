import SwiftUI
import UniformTypeIdentifiers

struct AppSettingsView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    @State private var showFolderPicker = false
    @State private var showClearConfirm = false
    @State private var showMailCompose = false
    @State private var showOnboarding = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.brutalBg.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 12) {
                        // Account
                        settingsSection(title: "Account") {
                            VStack(spacing: 0) {
                                if !state.gitHubDisplayName.isEmpty {
                                    dataRow(label: "Name", value: state.gitHubDisplayName)
                                    BDivider().padding(.horizontal, 16)
                                }
                                dataRow(label: "Username", value: "@\(state.gitHubUsername)")
                                if !state.defaultAuthorEmail.isEmpty {
                                    BDivider().padding(.horizontal, 16)
                                    dataRow(label: "Email", value: state.defaultAuthorEmail)
                                }
                            }
                        }

                        // Default Save Location
                        settingsSection(title: "Default Save Location") {
                            VStack(spacing: 0) {
                                if let url = state.resolvedDefaultSaveURL {
                                    HStack(spacing: 12) {
                                        Text("📁").font(.system(size: 18))
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(url.lastPathComponent)
                                                .font(.system(size: 13, weight: .semibold, design: .monospaced))
                                                .foregroundStyle(Color.brutalText)
                                            Text(url.path)
                                                .font(.system(size: 14, design: .monospaced))
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
                                                .font(.system(size: 14, weight: .bold, design: .monospaced))
                                                .foregroundStyle(Color.brutalAccent)
                                                .tracking(1)
                                        }
                                        .buttonStyle(.plain)

                                        Spacer()

                                        Button {
                                            showClearConfirm = true
                                        } label: {
                                            Text("REMOVE")
                                                .font(.system(size: 14, weight: .bold, design: .monospaced))
                                                .foregroundStyle(Color.brutalError)
                                                .tracking(1)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 12)
                                } else {
                                    VStack(spacing: 10) {
                                        HStack(spacing: 6) {
                                            Image(systemName: "info.circle")
                                                .font(.system(size: 11))
                                                .foregroundStyle(Color.brutalText)
                                            Text("New repositories will be saved to the app's default location.")
                                                .font(.system(size: 14, design: .monospaced))
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
                                                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                                                    .foregroundStyle(Color.brutalAccent)
                                                    .tracking(1)
                                            }
                                            .padding(.horizontal, 16)
                                            .padding(.vertical, 12)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                        }

                        // Shortcuts
                        settingsSection(title: "Shortcuts") {
                            HStack(alignment: .top, spacing: 14) {
                                Text("⚡️")
                                    .font(.system(size: 18))
                                    .frame(width: 28)

                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Pull from Shortcuts")
                                        .font(.system(size: 14, weight: .medium))
                                        .foregroundStyle(Color.brutalText)
                                    Text("Use Pull All Repositories in Apple Shortcuts. Add a Personal Automation → App → GitSync.md is opened to auto-pull on launch.")
                                        .font(.system(size: 13, design: .monospaced))
                                        .foregroundStyle(Color.brutalText)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 14)
                        }

                        // Feedback
                        settingsSection(title: "Feedback") {
                            VStack(spacing: 0) {
                                actionRow(icon: "✉️", title: "Send Feedback", subtitle: "Questions, ideas, or issues") {
                                    if FeedbackHelper.canSendMail {
                                        showMailCompose = true
                                    } else {
                                        FeedbackHelper.openMailClient()
                                    }
                                }
                                BDivider().padding(.horizontal, 16)
                                actionRow(icon: "💬", title: "Join our Discord", subtitle: "Chat with us on Discord") {
                                    if let url = URL(string: "https://discord.gg/RaQYS4t6gn") {
                                        UIApplication.shared.open(url)
                                    }
                                }
                            }
                        }

                        // Help
                        settingsSection(title: "Help") {
                            VStack(spacing: 0) {
                                actionRow(icon: "👋", title: "Show App Tour", subtitle: "Re-experience the onboarding flow") {
                                    showOnboarding = true
                                }
                            }
                        }

                        // About
                        settingsSection(title: "About") {
                            VStack(spacing: 0) {
                                dataRow(
                                    label: "Version",
                                    value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
                                )
                                BDivider().padding(.horizontal, 16)
                                dataRow(label: "Repositories", value: "\(state.repos.count)")
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
                    Text("APP SETTINGS")
                        .font(.system(size: 12, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Text("DONE")
                            .font(.system(size: 14, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color(.systemBackground))
                            .tracking(1)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(Color.brutalText)
                    }
                    .buttonStyle(.plain)
                }
            }
            .sheet(isPresented: $showMailCompose) { MailComposeView() }
            .fullScreenCover(isPresented: $showOnboarding) { OnboardingView() }
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
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .tracking(1)
            Spacer()
            Text(value)
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(Color.brutalText)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
    }

    private func actionRow(icon: String, title: String, subtitle: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Text(icon)
                    .font(.system(size: 18))
                    .frame(width: 28)

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(Color.brutalText)
                    Text(subtitle)
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                }

                Spacer()

                Text("→")
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

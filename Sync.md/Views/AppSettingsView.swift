import SwiftUI
import UniformTypeIdentifiers

struct AppSettingsView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    @State private var showFolderPicker = false
    @State private var showClearConfirm = false
    @State private var showMailCompose = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color(.systemGroupedBackground)
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 20) {
                        // Account Section
                        settingsSection(title: "Account", icon: "person.fill", iconColor: SyncTheme.accent) {
                            VStack(spacing: 14) {
                                if !state.gitHubDisplayName.isEmpty {
                                    settingsRow(label: "Name") {
                                        Text(state.gitHubDisplayName)
                                            .font(.system(size: 15, design: .rounded))
                                            .foregroundStyle(.secondary)
                                    }
                                    Divider().opacity(0.3)
                                }

                                settingsRow(label: "Username") {
                                    Text("@\(state.gitHubUsername)")
                                        .font(.system(size: 15, design: .rounded))
                                        .foregroundStyle(.secondary)
                                }

                                if !state.defaultAuthorEmail.isEmpty {
                                    Divider().opacity(0.3)
                                    settingsRow(label: "Email") {
                                        Text(state.defaultAuthorEmail)
                                            .font(.system(size: 15, design: .rounded))
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                        .staggeredAppear(index: 0)

                        // Default Save Location Section
                        settingsSection(title: "Default Save Location", icon: "folder.fill", iconColor: SyncTheme.accent) {
                            VStack(spacing: 14) {
                                if let url = state.resolvedDefaultSaveURL {
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
                                    }

                                    Divider().opacity(0.3)

                                    HStack(spacing: 16) {
                                        Button {
                                            showFolderPicker = true
                                        } label: {
                                            HStack(spacing: 6) {
                                                Image(systemName: "folder.badge.plus")
                                                    .font(.system(size: 13))
                                                Text("Change")
                                                    .font(.system(size: 14, weight: .medium, design: .rounded))
                                            }
                                            .foregroundStyle(SyncTheme.accent)
                                        }

                                        Spacer()

                                        Button {
                                            showClearConfirm = true
                                        } label: {
                                            HStack(spacing: 6) {
                                                Image(systemName: "xmark.circle.fill")
                                                    .font(.system(size: 13))
                                                Text("Remove")
                                                    .font(.system(size: 14, weight: .medium, design: .rounded))
                                            }
                                            .foregroundStyle(.red.opacity(0.8))
                                        }
                                    }
                                } else {
                                    VStack(spacing: 12) {
                                        HStack(spacing: 8) {
                                            Image(systemName: "info.circle.fill")
                                                .font(.system(size: 13))
                                                .foregroundStyle(.tertiary)
                                            Text("New repositories will be saved to the app's default location.")
                                                .font(.system(size: 13, design: .rounded))
                                                .foregroundStyle(.secondary)
                                        }

                                        Button {
                                            showFolderPicker = true
                                        } label: {
                                            HStack(spacing: 8) {
                                                Image(systemName: "folder.badge.plus")
                                                    .font(.system(size: 15))
                                                Text("Choose Default Location")
                                                    .font(.system(size: 15, weight: .medium, design: .rounded))
                                            }
                                            .foregroundStyle(SyncTheme.accent)
                                            .frame(maxWidth: .infinity)
                                            .padding(.vertical, 12)
                                            .background(SyncTheme.accent.opacity(0.08), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                        }
                                    }
                                }
                            }
                        }
                        .staggeredAppear(index: 1)

                        // Feedback Section
                        settingsSection(title: "Feedback", icon: "bubble.left.and.bubble.right.fill", iconColor: SyncTheme.accent) {
                            VStack(spacing: 14) {
                                Button {
                                    if FeedbackHelper.canSendMail {
                                        showMailCompose = true
                                    } else {
                                        FeedbackHelper.openMailClient()
                                    }
                                } label: {
                                    settingsActionRow(
                                        icon: "envelope.fill",
                                        title: "Send Feedback",
                                        subtitle: "Questions, ideas, or issues"
                                    )
                                }
                                .buttonStyle(.plain)

                                Divider().opacity(0.3)

                                Button {
                                    FeedbackHelper.openGitHubIssue()
                                } label: {
                                    settingsActionRow(
                                        icon: "ladybug.fill",
                                        title: "Report a Bug",
                                        subtitle: "Open an issue on GitHub"
                                    )
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .staggeredAppear(index: 2)

                        // Info Section
                        settingsSection(title: "About", icon: "info.circle.fill", iconColor: .secondary) {
                            VStack(spacing: 14) {
                                settingsRow(label: "Version") {
                                    Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                                        .font(.system(size: 15, weight: .medium, design: .rounded))
                                        .foregroundStyle(.secondary)
                                }

                                Divider().opacity(0.3)

                                settingsRow(label: "Repositories") {
                                    Text("\(state.repos.count)")
                                        .font(.system(size: 15, weight: .medium, design: .rounded))
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .staggeredAppear(index: 3)
                    }
                    .padding(.top, 12)
                    .padding(.bottom, 40)
                }
                .scrollIndicators(.hidden)
            }
            .navigationTitle("App Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Text("Done")
                            .font(.system(size: 16, weight: .bold, design: .rounded))
                    }
                }
            }
            .sheet(isPresented: $showMailCompose) {
                MailComposeView()
            }
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
                Button("Remove", role: .destructive) {
                    state.clearDefaultSaveLocation()
                }
            } message: {
                Text("New repositories will be saved to the app's default location instead.")
            }
        }
    }

    // MARK: - Settings Section

    private func settingsSection<Content: View>(
        title: String,
        icon: String,
        iconColor: Color,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(iconColor)
                Text(title)
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
                    .tracking(0.5)
            }
            .padding(.horizontal, 4)

            content()
                .padding(16)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .shadow(color: .black.opacity(0.04), radius: 8, x: 0, y: 2)
        }
        .padding(.horizontal, 20)
    }

    private func settingsRow<Content: View>(label: String, @ViewBuilder value: () -> Content) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 15, weight: .medium, design: .rounded))
                .foregroundStyle(.secondary)
            Spacer()
            value()
        }
    }

    private func settingsActionRow(icon: String, title: String, subtitle: String) -> some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(SyncTheme.blue.opacity(0.12))
                    .frame(width: 34, height: 34)
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(SyncTheme.accent)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 15, weight: .medium, design: .rounded))
                    .foregroundStyle(.primary)
                Text(subtitle)
                    .font(.system(size: 12, design: .rounded))
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
    }
}

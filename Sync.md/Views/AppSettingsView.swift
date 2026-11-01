import SwiftUI
import UniformTypeIdentifiers

struct AppSettingsView: View {
    @Environment(AppState.self) private var state
    @Environment(PremiumRuntime.self) private var premiumRuntime
    @Environment(\.dismiss) private var dismiss

    @State private var showFolderPicker = false
    @State private var showClearConfirm = false
    @State private var showMailCompose = false
    @State private var showOnboarding = false
    @State private var showPremiumSettings = false
    @State private var showRemoveGitTracking = false
    @ObservedObject private var pushSyncStatusObject = PushSyncManager.shared

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Tide.paper.ignoresSafeArea()

                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(String(localized: "Settings"))
                            .tideDisplay(34)
                            .accessibilityAddTraits(.isHeader)
                            .padding(.horizontal, 20)
                            .padding(.top, 2)

                        accountCard
                            .padding(.horizontal, Tide.gutter)
                            .padding(.top, 18)

                        // Sync — Background Sync (optional; hidden behind the legacy
                        // gitSyncAssistEnabled flag until the tier ships) and the
                        // Shortcuts actions. Manual Git, Shortcuts, callbacks, and
                        // local repository features remain available either way.
                        settingsSection(title: String(localized: "Sync")) {
                            if FeatureFlags.gitSyncAssistEnabled {
                                Button { showPremiumSettings = true } label: {
                                    SettingsRow(
                                        title: String(localized: "Background Sync"),
                                        value: premiumRuntime.automaticallySyncAllRepositories
                                            ? String(localized: "on")
                                            : String(localized: "off"),
                                        valueTint: premiumRuntime.automaticallySyncAllRepositories ? Tide.synced : Tide.inkMid,
                                        accessory: .chevron
                                    )
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(String(localized: "Background Sync"))
                                .accessibilityValue(premiumRuntime.automaticallySyncAllRepositories
                                                    ? String(localized: "on")
                                                    : String(localized: "off"))
                                .accessibilityHint(String(localized: "Independent automatic pull and push controls"))
                                SettingsSeparator()
                            }

                            VStack(alignment: .leading, spacing: 4) {
                                Text("Git actions from Shortcuts")
                                    .font(TideFont.body)
                                    .foregroundStyle(Tide.ink)
                                Text("Use Pull All, Pull Repository, Push Repository, or Sync Repository in Apple Shortcuts. Push and Sync stage all non-ignored changes, stop on conflicts or unsafe remote state, and publish directly to your configured remote.")
                                    .font(TideFont.sans(12.5, relativeTo: .footnote))
                                    .foregroundStyle(Tide.inkMid)
                                    .lineSpacing(2)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 18)
                            .padding(.vertical, 14)
                            .accessibilityElement(children: .combine)
                        }

                        // Push Sync — installation-global APNs registration plus a
                        // one-time GitHub App installation per account/organization.
                        settingsSection(title: String(localized: "Push Sync")) {
                            PushSyncSettingsContent(
                                manager: pushSyncStatusObject,
                                scope: .global
                            )
                        }

                        settingsSection(title: String(localized: "New vaults")) {
                            saveLocationRows
                        }

                        settingsSection(title: String(localized: "Folder tools")) {
                            Button { showRemoveGitTracking = true } label: {
                                SettingsRow(
                                    title: String(localized: "Remove Git tracking"),
                                    subtitle: String(localized: "Keep your files, with an optional history backup"),
                                    accessory: .chevron
                                )
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(String(localized: "Remove Git tracking"))
                            .accessibilityHint(String(localized: "Keep your files, with an optional history backup"))
                            .accessibilityIdentifier("appSettings.removeGitTracking")
                        }

                        settingsSection(title: String(localized: "Help & feedback")) {
                            Button {
                                if FeedbackHelper.canSendMail {
                                    showMailCompose = true
                                } else {
                                    FeedbackHelper.openMailClient()
                                }
                            } label: {
                                SettingsRow(
                                    title: String(localized: "Send feedback"),
                                    subtitle: String(localized: "Questions, ideas, or issues"),
                                    accessory: .chevron
                                )
                            }
                            .buttonStyle(.plain)

                            SettingsSeparator()

                            Button {
                                if let url = URL(string: "https://discord.gg/RaQYS4t6gn") {
                                    UIApplication.shared.open(url)
                                }
                            } label: {
                                SettingsRow(
                                    title: String(localized: "Join the Discord"),
                                    subtitle: String(localized: "Chat with us on Discord"),
                                    accessory: .external
                                )
                            }
                            .buttonStyle(.plain)

                            SettingsSeparator()

                            NavigationLink {
                                DebugLogView()
                            } label: {
                                SettingsRow(
                                    title: String(localized: "Debug log"),
                                    value: String(localized: "\(DebugLogger.shared.entries.count) entries"),
                                    accessory: .chevron
                                )
                            }
                            .buttonStyle(.plain)

                            SettingsSeparator()

                            Button { showOnboarding = true } label: {
                                SettingsRow(
                                    title: String(localized: "Show app tour"),
                                    subtitle: String(localized: "Re-experience the onboarding flow"),
                                    accessory: .chevron
                                )
                            }
                            .buttonStyle(.plain)
                        }

                        footer
                            .padding(.horizontal, 20)
                            .padding(.top, 22)
                    }
                    .padding(.bottom, 30)
                }
                .scrollIndicators(.hidden)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .tint(Tide.ink)
                }
            }
            .sheet(isPresented: $showMailCompose) { MailComposeView() }
            .sheet(isPresented: $showPremiumSettings) { PremiumSettingsView() }
            .sheet(isPresented: $showRemoveGitTracking) { RemoveGitTrackingView() }
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

    // MARK: - Account

    @ViewBuilder
    private var accountCard: some View {
        if state.isSignedIn {
            HStack(spacing: 14) {
                GitHubAvatarView(avatarURL: state.gitHubAvatarURL, size: 46)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 3) {
                    Text(state.gitHubDisplayName.isEmpty ? "@\(state.gitHubUsername)" : state.gitHubDisplayName)
                        .font(TideFont.sans(17, .semibold, relativeTo: .headline))
                        .tracking(-0.25)
                        .foregroundStyle(Tide.ink)
                        .lineLimit(1)
                    Text("@\(state.gitHubUsername) · GitHub")
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .lineLimit(1)
                    if !state.defaultAuthorEmail.isEmpty {
                        Text(state.defaultAuthorEmail)
                            .font(TideFont.mono(11))
                            .foregroundStyle(Tide.inkFaint)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }

                Spacer(minLength: 8)

                HStack(spacing: 5) {
                    Image(systemName: "checkmark")
                        .font(.system(size: 11, weight: .bold))
                        .accessibilityHidden(true)
                    Text("signed in")
                        .font(TideFont.metaStrong)
                }
                .foregroundStyle(Tide.synced)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
            .accessibilityElement(children: .combine)
        } else {
            Button {
                Task { await state.signInWithGitHub() }
            } label: {
                HStack(spacing: 14) {
                    Image("GitHubLogo")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 22, height: 22)
                        .foregroundStyle(Tide.paper)
                        .frame(width: 46, height: 46)
                        .background(Circle().fill(Tide.ink))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Sign in with GitHub")
                            .font(TideFont.sans(17, .semibold, relativeTo: .headline))
                            .tracking(-0.25)
                            .foregroundStyle(Tide.ink)
                        Text("Not signed in")
                            .font(TideFont.sans(13, relativeTo: .footnote))
                            .foregroundStyle(Tide.inkMid)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Tide.inkFaint)
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 16)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
                .contentShape(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "Sign in with GitHub"))
        }
    }

    // MARK: - Default save location

    @ViewBuilder
    private var saveLocationRows: some View {
        if let url = state.resolvedDefaultSaveURL {
            Button { showFolderPicker = true } label: {
                SettingsRow(
                    title: String(localized: "Save new clones in"),
                    subtitle: url.path,
                    subtitleIsPath: true,
                    value: url.lastPathComponent,
                    accessory: .chevron
                )
            }
            .buttonStyle(.plain)
            .accessibilityHint(String(localized: "Choose a different default location"))

            SettingsSeparator()

            Button { showClearConfirm = true } label: {
                SettingsRow(
                    title: String(localized: "Use the app's default location"),
                    titleTint: Tide.danger
                )
            }
            .buttonStyle(.plain)
        } else {
            Button { showFolderPicker = true } label: {
                SettingsRow(
                    title: String(localized: "Save new clones in"),
                    subtitle: String(localized: "New repositories will be saved to the app's default location."),
                    value: String(localized: "App default"),
                    valueIsMono: false,
                    accessory: .chevron
                )
            }
            .buttonStyle(.plain)
            .accessibilityHint(String(localized: "Choose a default location"))
        }
    }

    // MARK: - Footer

    private var footer: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("GitSync.md \(appVersion)")
                Text(String(localized: "\(state.visibleRepos.count) repositories"))
            }
            .font(TideFont.mono(11))
            .foregroundStyle(Tide.inkFaint)
            .accessibilityElement(children: .combine)

            Spacer(minLength: 8)

            HStack(spacing: 14) {
                Link(destination: URL(string: "https://gitsync.md/privacy.html")!) {
                    Text("Privacy")
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                Link(destination: URL(string: "https://gitsync.md/terms.html")!) {
                    Text("Terms")
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
            }
            .font(TideFont.mono(11))
            .foregroundStyle(Tide.inkMid)
        }
    }

    // MARK: - Layout Helpers

    private func settingsSection<Content: View>(title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            TideSectionLabel(title: title)
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, 20)

            VStack(spacing: 0) {
                content()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
            .clipShape(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous))
            .padding(.horizontal, Tide.gutter)
        }
        .padding(.top, 18)
    }
}

// MARK: - Rows

/// Hairline between rows inside a grouped Tide card.
private struct SettingsSeparator: View {
    var body: some View {
        Rectangle().fill(Tide.line).frame(height: 1).padding(.horizontal, 18)
    }
}

/// Label/value row in a grouped Tide card: sentence-case title, optional
/// subtitle, mono value (git facts) and a trailing accessory.
private struct SettingsRow: View {
    enum Accessory { case none, chevron, external }

    let title: String
    var titleTint: Color = Tide.ink
    var subtitle: String? = nil
    var subtitleIsPath = false
    var value: String? = nil
    var valueIsMono = true
    var valueTint: Color = Tide.inkMid
    var accessory: Accessory = .none

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(TideFont.body)
                    .foregroundStyle(titleTint)
                if let subtitle {
                    Text(subtitle)
                        .font(subtitleIsPath ? TideFont.mono(11) : TideFont.sans(12.5, relativeTo: .footnote))
                        .foregroundStyle(subtitleIsPath ? Tide.inkFaint : Tide.inkMid)
                        .lineLimit(subtitleIsPath ? 1 : nil)
                        .truncationMode(.middle)
                        .fixedSize(horizontal: false, vertical: !subtitleIsPath)
                }
            }

            Spacer(minLength: 8)

            if let value {
                Text(value)
                    .font(valueIsMono ? TideFont.mono(12, valueTint == Tide.synced ? .semibold : .regular)
                                      : TideFont.sans(14, relativeTo: .subheadline))
                    .foregroundStyle(valueTint)
                    .lineLimit(1)
            }

            switch accessory {
            case .none:
                EmptyView()
            case .chevron:
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Tide.inkFaint)
                    .accessibilityHidden(true)
            case .external:
                Image(systemName: "arrow.up.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Tide.inkFaint)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
        .contentShape(Rectangle())
    }
}

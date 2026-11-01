import SwiftUI
import UniformTypeIdentifiers

struct AppSettingsView: View {
    @Environment(AppState.self) private var state
    @Environment(PremiumRuntime.self) private var premiumRuntime
    @Environment(\.dismiss) private var dismiss
    @AppStorage(AppAppearance.storageKey) private var appearanceRaw = AppAppearance.system.rawValue

    @State private var showFolderPicker = false
    @State private var showClearConfirm = false
    @State private var showMailCompose = false
    @State private var showOnboarding = false
    @State private var showPremiumSettings = false
    @State private var showRemoveGitTracking = false
    @State private var showSignOutConfirm = false
    @ObservedObject private var pushSyncStatusObject = PushSyncManager.shared

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    }

    private var buildNumber: String? {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String
    }

    /// "GitSync.md 3.4 (412)"
    private var versionLine: String {
        if let buildNumber, !buildNumber.isEmpty, buildNumber != appVersion {
            return "GitSync.md \(appVersion) (\(buildNumber))"
        }
        return "GitSync.md \(appVersion)"
    }

    private var appearance: AppAppearance { AppAppearance(rawValue: appearanceRaw) ?? .system }

    /// "on · 4 vaults" / "off"
    private var backgroundSyncValue: String {
        guard premiumRuntime.automaticallySyncAllRepositories else { return String(localized: "off") }
        let included = premiumRuntime.automaticSyncSummary.included
        return included == 1
            ? String(localized: "on · 1 vault")
            : String(localized: "on · \(included) vaults")
    }

    private var pushNotificationsOn: Bool { pushSyncStatusObject.isEnabled }

    private var commitAuthorValue: String {
        let name = state.defaultAuthorName.trimmingCharacters(in: .whitespacesAndNewlines)
        if !name.isEmpty { return name }
        let email = state.defaultAuthorEmail.trimmingCharacters(in: .whitespacesAndNewlines)
        return email.isEmpty ? String(localized: "Not set") : email
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

                        settingsSection(title: String(localized: "Sync")) {
                            // Background Sync stays behind the gitSyncAssistEnabled
                            // flag until the tier ships. Manual Git, Shortcuts,
                            // callbacks, and local features remain available.
                            if FeatureFlags.gitSyncAssistEnabled {
                                Button { showPremiumSettings = true } label: {
                                    AppSettingsRow(
                                        title: String(localized: "Background Sync"),
                                        value: backgroundSyncValue,
                                        valueTint: premiumRuntime.automaticallySyncAllRepositories ? Tide.synced : Tide.inkMid,
                                        accessory: .chevron
                                    )
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(String(localized: "Background Sync"))
                                .accessibilityValue(backgroundSyncValue)
                                .accessibilityHint(String(localized: "Independent automatic pull and push controls"))
                                AppSettingsSeparator()
                            }

                            NavigationLink {
                                PushNotificationsSettingsView()
                            } label: {
                                AppSettingsRow(
                                    title: String(localized: "Push notifications from GitHub"),
                                    value: pushNotificationsOn ? String(localized: "on") : String(localized: "off"),
                                    valueTint: pushNotificationsOn ? Tide.synced : Tide.inkMid,
                                    accessory: .chevron
                                )
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("appSettings.pushNotifications")

                            AppSettingsSeparator()

                            NavigationLink {
                                ShortcutsSettingsView()
                            } label: {
                                AppSettingsRow(
                                    title: String(localized: "Shortcuts & Siri actions"),
                                    value: "\(ShortcutActionCatalog.actions.count)",
                                    accessory: .chevron
                                )
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("appSettings.shortcuts")
                        }

                        settingsSection(title: String(localized: "New vaults")) {
                            saveLocationRows

                            AppSettingsSeparator()

                            NavigationLink {
                                CommitAuthorSettingsView()
                            } label: {
                                AppSettingsRow(
                                    title: String(localized: "Commit as"),
                                    value: commitAuthorValue,
                                    valueIsMono: false,
                                    accessory: .chevron
                                )
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("appSettings.commitAs")
                        }

                        settingsSection(title: String(localized: "App")) {
                            NavigationLink {
                                AppearanceSettingsView()
                            } label: {
                                AppSettingsRow(
                                    title: String(localized: "Appearance"),
                                    value: appearance.title,
                                    valueIsMono: false,
                                    accessory: .chevron
                                )
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("appSettings.appearance")

                            AppSettingsSeparator()

                            NavigationLink {
                                DebugLogView()
                            } label: {
                                AppSettingsRow(
                                    title: String(localized: "Debug log"),
                                    value: String(localized: "\(DebugLogger.shared.entries.count) entries"),
                                    accessory: .chevron
                                )
                            }
                            .buttonStyle(.plain)

                            AppSettingsSeparator()

                            Button {
                                if let url = URL(string: "https://discord.gg/RaQYS4t6gn") {
                                    UIApplication.shared.open(url)
                                }
                            } label: {
                                AppSettingsRow(title: String(localized: "Join the Discord"), accessory: .external)
                            }
                            .buttonStyle(.plain)

                            AppSettingsSeparator()

                            Button {
                                if FeedbackHelper.canSendMail {
                                    showMailCompose = true
                                } else {
                                    FeedbackHelper.openMailClient()
                                }
                            } label: {
                                AppSettingsRow(
                                    title: String(localized: "Send feedback"),
                                    subtitle: String(localized: "Questions, ideas, or issues"),
                                    accessory: .chevron
                                )
                            }
                            .buttonStyle(.plain)

                            AppSettingsSeparator()

                            Button { showOnboarding = true } label: {
                                AppSettingsRow(
                                    title: String(localized: "Show app tour"),
                                    subtitle: String(localized: "Re-experience the onboarding flow"),
                                    accessory: .chevron
                                )
                            }
                            .buttonStyle(.plain)
                        }

                        settingsSection(title: String(localized: "Folder tools")) {
                            Button { showRemoveGitTracking = true } label: {
                                AppSettingsRow(
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
            .confirmationDialog(
                String(localized: "Sign out of GitHub?"),
                isPresented: $showSignOutConfirm,
                titleVisibility: .visible
            ) {
                Button(String(localized: "Sign out"), role: .destructive) { state.signOut() }
                Button(String(localized: "Cancel"), role: .cancel) {}
            } message: {
                Text(signOutMessage)
            }
        }
    }

    private var signOutMessage: String {
        let login = state.gitHubUsername
        let account = login.isEmpty ? String(localized: "this GitHub account") : "@\(login)"
        return String(localized: "GitSync.md forgets the token for \(account) on this device. Your vaults and their files stay where they are.")
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
                    Text("@\(state.gitHubUsername) · GitHub OAuth")
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .lineLimit(1)
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
                AppSettingsRow(
                    title: String(localized: "Save new clones in"),
                    subtitle: url.path,
                    subtitleIsPath: true,
                    value: url.lastPathComponent,
                    accessory: .chevron
                )
            }
            .buttonStyle(.plain)
            .accessibilityHint(String(localized: "Choose a different default location"))

            AppSettingsSeparator()

            Button { showClearConfirm = true } label: {
                AppSettingsRow(
                    title: String(localized: "Use the app's default location"),
                    titleTint: Tide.danger
                )
            }
            .buttonStyle(.plain)
        } else {
            Button { showFolderPicker = true } label: {
                AppSettingsRow(
                    title: String(localized: "Save new clones in"),
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
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: versionLine)
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

            if state.isSignedIn || state.isDemoMode {
                Button { showSignOutConfirm = true } label: {
                    Text(String(localized: "Sign out"))
                        .font(TideFont.sans(15, .medium, relativeTo: .body))
                        .foregroundStyle(Tide.danger)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .overlay(Capsule().strokeBorder(Tide.line, lineWidth: 1))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("appSettings.signOut")
            }
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
struct AppSettingsSeparator: View {
    var body: some View {
        Rectangle().fill(Tide.line).frame(height: 1).padding(.horizontal, 18)
    }
}

/// Label/value row in a grouped Tide card: sentence-case title, optional
/// subtitle, mono value (git facts) and a trailing accessory.
struct AppSettingsRow: View {
    enum Accessory { case none, chevron, external, checkmark }

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
            case .checkmark:
                Image(systemName: "checkmark")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(Tide.synced)
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
        .contentShape(Rectangle())
    }
}

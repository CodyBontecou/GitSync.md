import SwiftUI

// MARK: - Appearance

/// App-wide appearance override, persisted in UserDefaults and applied at the
/// root scene with `preferredColorScheme`.
enum AppAppearance: String, CaseIterable, Identifiable {
    case system
    case light
    case dark

    static let storageKey = "app.appearance.v1"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .system: String(localized: "System")
        case .light: String(localized: "Light")
        case .dark: String(localized: "Dark")
        }
    }

    var interfaceStyle: UIUserInterfaceStyle {
        switch self {
        case .system: .unspecified
        case .light: .light
        case .dark: .dark
        }
    }

    @MainActor
    func applyToWindows() {
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows {
                window.overrideUserInterfaceStyle = interfaceStyle
            }
        }
    }

    /// `nil` follows the system setting.
    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

struct AppearanceSettingsView: View {
    @AppStorage(AppAppearance.storageKey) private var appearanceRaw = AppAppearance.system.rawValue

    var body: some View {
        SettingsDetailScaffold(title: String(localized: "Appearance")) {
            SettingsDetailCard {
                ForEach(Array(AppAppearance.allCases.enumerated()), id: \.element) { index, option in
                    if index > 0 { AppSettingsSeparator() }
                    Button {
                        appearanceRaw = option.rawValue
                    } label: {
                        AppSettingsRow(
                            title: option.title,
                            accessory: appearanceRaw == option.rawValue ? .checkmark : .none
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(appearanceRaw == option.rawValue ? .isSelected : [])
                }
            }

            SettingsDetailFootnote(String(localized: "System follows your iPhone's Light or Dark setting."))
        }
    }
}

// MARK: - Commit as

/// Default author identity for commits made in new vaults.
struct CommitAuthorSettingsView: View {
    @Environment(AppState.self) private var state
    @State private var name = ""
    @State private var email = ""
    @State private var loaded = false
    @FocusState private var focusedField: Field?

    private enum Field { case name, email }

    private var emailLooksInvalid: Bool {
        let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
        return !trimmed.isEmpty && !(trimmed.contains("@") && !trimmed.hasPrefix("@") && !trimmed.hasSuffix("@"))
    }

    var body: some View {
        SettingsDetailScaffold(title: String(localized: "Commit as")) {
            SettingsDetailCard {
                fieldRow(label: String(localized: "Name"), text: $name, field: .name,
                         contentType: .name, keyboard: .default)
                AppSettingsSeparator()
                fieldRow(label: String(localized: "Email"), text: $email, field: .email,
                         contentType: .emailAddress, keyboard: .emailAddress)
            }

            if emailLooksInvalid {
                Text(String(localized: "That doesn't look like an email address."))
                    .font(TideFont.sans(13, .medium, relativeTo: .footnote))
                    .foregroundStyle(Tide.ahead)
                    .padding(.horizontal, 20)
            }

            SettingsDetailFootnote(String(localized: "Used as the author of commits in vaults you add from now on. Existing vaults keep their own author in Vault settings. Use your GitHub noreply address to keep your email private."))
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            name = state.defaultAuthorName
            email = state.defaultAuthorEmail
        }
        .onSubmit(save)
        .onDisappear(perform: save)
    }

    private func fieldRow(label: String, text: Binding<String>, field: Field,
                          contentType: UITextContentType, keyboard: UIKeyboardType) -> some View {
        HStack(spacing: 12) {
            Text(label)
                .font(TideFont.body)
                .foregroundStyle(Tide.ink)
                .frame(width: 64, alignment: .leading)
            TextField(label, text: text)
                .font(TideFont.mono(14, relativeTo: .subheadline))
                .foregroundStyle(Tide.ink)
                .textContentType(contentType)
                .keyboardType(keyboard)
                .textInputAutocapitalization(field == .email ? .never : .words)
                .autocorrectionDisabled()
                .submitLabel(field == .name ? .next : .done)
                .focused($focusedField, equals: field)
                .onSubmit { if field == .name { focusedField = .email } }
        }
        .padding(.horizontal, 18)
        .frame(minHeight: 52)
    }

    private func save() {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmedName != state.defaultAuthorName || trimmedEmail != state.defaultAuthorEmail else { return }
        state.defaultAuthorName = trimmedName
        state.defaultAuthorEmail = trimmedEmail
        state.saveGlobalSettings()
    }
}

// MARK: - Shortcuts & Siri

/// The App Shortcuts and intents GitSync.md exposes (see SyncShortcuts.swift).
enum ShortcutActionCatalog {
    struct Action: Identifiable {
        let id: String
        let title: String
        let detail: String
        let phrase: String
        let systemImage: String
    }

    static var actions: [Action] {
        [
            Action(
                id: "pull-all",
                title: String(localized: "Pull All Repositories"),
                detail: String(localized: "Fetch and fast-forward every cloned vault. Pair it with a Personal Automation to pull when Obsidian opens."),
                phrase: String(localized: "“Pull all repositories in GitSync.md”"),
                systemImage: "arrow.down.circle.fill"
            ),
            Action(
                id: "pull-repo",
                title: String(localized: "Pull Repository"),
                detail: String(localized: "Fetch and fast-forward one vault you choose."),
                phrase: String(localized: "“Pull notes in GitSync.md”"),
                systemImage: "arrow.down.circle"
            ),
            Action(
                id: "push-repo",
                title: String(localized: "Push Repository"),
                detail: String(localized: "Stage everything, commit, and push one vault without opening the app."),
                phrase: String(localized: "“Push notes in GitSync.md”"),
                systemImage: "arrow.up.circle.fill"
            ),
            Action(
                id: "sync-repo",
                title: String(localized: "Sync Repository"),
                detail: String(localized: "Pull one vault, then commit and push your edits. Stops on conflicts."),
                phrase: String(localized: "“Pull and push notes in GitSync.md”"),
                systemImage: "arrow.triangle.2.circlepath.circle.fill"
            ),
        ]
    }
}

struct ShortcutsSettingsView: View {
    var body: some View {
        SettingsDetailScaffold(title: String(localized: "Shortcuts & Siri")) {
            SettingsDetailFootnote(String(localized: "Run these from the Shortcuts app, an automation, or by asking Siri. Each one runs headless and stops safely on conflicts."))

            SettingsDetailCard {
                ForEach(Array(ShortcutActionCatalog.actions.enumerated()), id: \.element.id) { index, action in
                    if index > 0 { AppSettingsSeparator() }
                    HStack(alignment: .top, spacing: 14) {
                        Image(systemName: action.systemImage)
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(Tide.behind)
                            .frame(width: 36, height: 36)
                            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Tide.behind.opacity(0.12)))
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(action.title)
                                .font(TideFont.sans(15, .semibold, relativeTo: .subheadline))
                                .foregroundStyle(Tide.ink)
                            Text(action.detail)
                                .font(TideFont.sans(13, relativeTo: .footnote))
                                .foregroundStyle(Tide.inkMid)
                                .fixedSize(horizontal: false, vertical: true)
                            Text(action.phrase)
                                .font(TideFont.mono(11))
                                .foregroundStyle(Tide.inkFaint)
                                .padding(.top, 2)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 14)
                    .accessibilityElement(children: .combine)
                }
            }

            SettingsDetailCard {
                Button {
                    if let url = URL(string: "shortcuts://") { UIApplication.shared.open(url) }
                } label: {
                    AppSettingsRow(title: String(localized: "Open Shortcuts"), accessory: .external)
                }
                .buttonStyle(.plain)
            }

            SettingsDetailFootnote(String(localized: "Links from Obsidian can also call syncmd://x-callback-url/pull, push, or sync with a repo= name."))
        }
    }
}

// MARK: - Push notifications

struct PushNotificationsSettingsView: View {
    @ObservedObject private var manager = PushSyncManager.shared

    var body: some View {
        SettingsDetailScaffold(title: String(localized: "Push notifications")) {
            SettingsDetailFootnote(String(localized: "Get a nudge the moment GitHub changes, so the pull can start right away."))
            SettingsDetailCard {
                PushSyncSettingsContent(manager: manager, scope: .global)
            }
        }
        .task { _ = await manager.refreshGitHubAppStatus() }
    }
}

// MARK: - Shared scaffolding

/// Paper background, big Tide title, scrolling stack of cards.
struct SettingsDetailScaffold<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        ZStack {
            Tide.paper.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(title)
                        .tideDisplay(30)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityAddTraits(.isHeader)
                        .padding(.horizontal, 20)
                        .padding(.top, 2)
                    content
                }
                .padding(.bottom, 30)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct SettingsDetailCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
            .clipShape(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous))
            .padding(.horizontal, Tide.gutter)
    }
}

struct SettingsDetailFootnote: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(TideFont.sans(13, relativeTo: .footnote))
            .foregroundStyle(Tide.inkMid)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 20)
    }
}

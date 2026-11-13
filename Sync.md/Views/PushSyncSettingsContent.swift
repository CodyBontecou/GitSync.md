import SwiftUI

/// Push Sync rows, laid out for a grouped Tide card (18pt row insets,
/// hairline separators). Callers own the card container.
struct PushSyncSettingsContent: View {
    enum Scope {
        case global
        case repository
    }

    @ObservedObject var manager: PushSyncManager
    let scope: Scope
    @State private var pendingRemoval: PushSyncGitHubInstallation?

    var body: some View {
        VStack(spacing: 0) {
            Toggle(isOn: Binding(
                get: { manager.isEnabled },
                set: { enabled in
                    Task { await manager.setEnabled(enabled) }
                }
            )) {
                Text("Notify when GitHub changes")
                    .font(TideFont.body)
                    .foregroundStyle(Tide.ink)
            }
            .tint(Tide.synced)
            .padding(.horizontal, 18)
            .frame(minHeight: 56)

            if manager.isEnabled {
                separator

                if manager.isLoadingGitHubAppStatus || manager.isRegistering {
                    HStack(spacing: 10) {
                        ProgressView().controlSize(.small).tint(Tide.ink)
                        Text("Checking GitHub connection…")
                            .font(TideFont.sans(13, relativeTo: .footnote))
                            .foregroundStyle(Tide.inkMid)
                        Spacer()
                    }
                    .padding(.horizontal, 18)
                    .frame(minHeight: 52)
                } else {
                    if manager.linkedGitHubInstallations.isEmpty {
                        Text("Connect GitHub to finish setup. Push events cannot arrive until an account or organization is connected.")
                            .font(TideFont.sans(13, .medium, relativeTo: .footnote))
                            .foregroundStyle(Tide.ahead)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 18)
                            .padding(.top, 14)
                    }

                    ForEach(manager.linkedGitHubInstallations) { installation in
                        installationRow(installation)
                        separator
                    }

                    Button {
                        Task { await manager.connectGitHubApp() }
                    } label: {
                        HStack(spacing: 10) {
                            if manager.isConnectingGitHubApp {
                                ProgressView().controlSize(.small).tint(Tide.behind)
                            } else {
                                Image(systemName: manager.linkedGitHubInstallations.isEmpty
                                      ? "link.badge.plus"
                                      : "arrow.trianglehead.2.clockwise.rotate.90")
                                    .font(.system(size: 14, weight: .semibold))
                                    .accessibilityHidden(true)
                            }
                            Text(manager.linkedGitHubInstallations.isEmpty
                                 ? String(localized: "Connect GitHub")
                                 : String(localized: "Add or update GitHub access"))
                                .font(TideFont.sans(15, .semibold, relativeTo: .subheadline))
                            Spacer()
                            Image(systemName: "arrow.up.right")
                                .font(.system(size: 12, weight: .semibold))
                                .accessibilityHidden(true)
                        }
                        .foregroundStyle(Tide.behind)
                        .padding(.horizontal, 18)
                        .frame(minHeight: 52)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(manager.isConnectingGitHubApp || manager.isRegistering)
                    .accessibilityHint(String(localized: "Choose the GitHub accounts, organizations, and repositories that may send Push Sync events."))
                }
            }

            if let error = manager.lastError {
                separator
                Text(error)
                    .font(TideFont.sans(13, relativeTo: .footnote))
                    .foregroundStyle(Tide.danger)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 14)
            }

            separator

            VStack(alignment: .leading, spacing: 8) {
                if let date = manager.lastRegistrationDate {
                    Text("Notification device registered \(relativeDate(date))")
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                }

                Text(disclosure)
                    .font(TideFont.sans(12.5, relativeTo: .footnote))
                    .foregroundStyle(Tide.inkMid)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 18)
            .padding(.vertical, 14)

            separator

            Link(destination: URL(string: "https://github.com/settings/installations")!) {
                HStack {
                    Text("Manage installed GitHub apps")
                        .font(TideFont.body)
                    Spacer()
                    Image(systemName: "arrow.up.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Tide.inkFaint)
                        .accessibilityHidden(true)
                }
                .foregroundStyle(Tide.ink)
                .padding(.horizontal, 18)
                .frame(minHeight: 52)
                .contentShape(Rectangle())
            }
            .accessibilityHint(String(localized: "Opens GitHub to change repository access or uninstall the GitSync.md GitHub App."))
        }
        .alert(item: $pendingRemoval) { installation in
            Alert(
                title: Text("Remove GitHub connection from this device?"),
                message: Text("This removes Push Sync routing for @\(installation.accountLogin) on this device. It does not uninstall the GitHub App. Use Manage on GitHub if you want GitHub to stop sending events."),
                primaryButton: .destructive(Text("Remove Connection")) {
                    Task { await manager.unlinkGitHubAppInstallation(id: installation.id) }
                },
                secondaryButton: .cancel()
            )
        }
    }

    private var separator: some View {
        Rectangle().fill(Tide.line).frame(height: 1).padding(.horizontal, 18)
    }

    @ViewBuilder
    private func installationRow(_ installation: PushSyncGitHubInstallation) -> some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: installation.accountType == "Organization" ? "building.2" : "person.crop.circle")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(installation.isSuspended ? Tide.danger : Tide.synced)
                .frame(width: 26, height: 26)
                .background(RoundedRectangle(cornerRadius: 8).fill(Tide.well))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("@\(installation.accountLogin)")
                    .font(TideFont.mono(13, .semibold, relativeTo: .footnote))
                    .foregroundStyle(Tide.ink)
                Text(installation.isSuspended
                     ? String(localized: "Access suspended on GitHub")
                     : installation.coversAllRepositories
                        ? String(localized: "All repositories, including future repositories")
                        : String(localized: "Selected repositories"))
                    .font(TideFont.sans(12.5, relativeTo: .footnote))
                    .foregroundStyle(installation.isSuspended ? Tide.danger : Tide.inkMid)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            HStack(spacing: 4) {
                Link(destination: installation.htmlURL) {
                    Text("Manage")
                        .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                        .foregroundStyle(Tide.behind)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(String(localized: "Manage GitHub access for @\(installation.accountLogin)"))

                Button {
                    pendingRemoval = installation
                } label: {
                    Text("Remove")
                        .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                        .foregroundStyle(Tide.danger)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "Remove @\(installation.accountLogin) from this device"))
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 6)
    }

    private var disclosure: String {
        let target = scope == .global
            ? String(localized: "matching cloned repositories")
            : String(localized: "this repository")
        return String(localized: "Connect the GitSync.md GitHub App once per personal account or organization; an account or organization owner must approve the connection. Choose all repositories to include future repositories automatically, or select specific repositories. GitHub grants read-only Contents permission so it can send push events; the relay does not use that permission to read files. Organization installations also grant read-only Members permission so the relay can verify the connecting user is an owner. When someone pushes to \(target), GitSync.md shows a notification and asks iOS to reconcile the configured branch when Background Sync and automatic pull are enabled. iOS may suppress the background attempt; tapping the notification performs an explicit pull. The relay stores repository names, GitHub installation and account identifiers, and APNs registration data for up to 90 days after the last registration; disabling requests immediate deletion. During connection it uses a short-lived GitHub token only for the ownership check, immediately requests token revocation, and never stores it. GitHub's signed event passes transiently through the relay, but commit messages, changed-file details, and sender data are not logged, stored, or sent to APNs. The relay never receives local paths or Git credentials and never stores file contents. Removing a connection or disabling Push Sync removes this device's routing, but the GitHub App remains installed until you uninstall it through Manage on GitHub.")
    }

    private func relativeDate(_ date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}

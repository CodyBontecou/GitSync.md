import SwiftUI

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
            Toggle("Notify when GitHub changes", isOn: Binding(
                get: { manager.isEnabled },
                set: { enabled in
                    Task { await manager.setEnabled(enabled) }
                }
            ))
            .frame(minHeight: 44)
            .padding(.horizontal, 16)

            if manager.isEnabled {
                BDivider().padding(.horizontal, 16)

                if manager.isLoadingGitHubAppStatus || manager.isRegistering {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Checking GitHub connection…")
                            .bType(.monoCaption, weight: .regular)
                        Spacer()
                    }
                    .padding(16)
                } else {
                    if manager.linkedGitHubInstallations.isEmpty {
                        Text("Connect GitHub to finish setup. Push events cannot arrive until an account or organization is connected.")
                            .bType(.monoCaption, weight: .semibold)
                            .foregroundStyle(Color.brutalWarning)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 16)
                            .padding(.top, 12)
                    }

                    ForEach(manager.linkedGitHubInstallations) { installation in
                        installationRow(installation)
                        BDivider().padding(.horizontal, 16)
                    }

                    Button {
                        Task { await manager.connectGitHubApp() }
                    } label: {
                        HStack(spacing: 10) {
                            if manager.isConnectingGitHubApp {
                                ProgressView()
                            } else {
                                Image(systemName: manager.linkedGitHubInstallations.isEmpty
                                      ? "link.badge.plus"
                                      : "arrow.trianglehead.2.clockwise.rotate.90")
                                    .accessibilityHidden(true)
                            }
                            Text(manager.linkedGitHubInstallations.isEmpty
                                 ? "CONNECT GITHUB"
                                 : "ADD OR UPDATE GITHUB ACCESS")
                                .bType(.monoCaption, weight: .bold)
                                .tracking(1)
                            Spacer()
                            Image(systemName: "arrow.up.right")
                                .accessibilityHidden(true)
                        }
                        .foregroundStyle(Color.brutalAccent)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 48)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(manager.isConnectingGitHubApp || manager.isRegistering)
                    .accessibilityHint(String(localized: "Choose the GitHub accounts, organizations, and repositories that may send Push Sync events."))
                }
            }

            if let error = manager.lastError {
                BDivider().padding(.horizontal, 16)
                Text(error)
                    .bType(.monoCaption, weight: .regular)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
            }

            if let date = manager.lastRegistrationDate {
                Text("Notification device registered \(relativeDate(date))")
                    .bType(.monoCaption, weight: .regular)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
            }

            Text(disclosure)
                .bType(.monoCaption, weight: .regular)
                .foregroundStyle(Color.brutalText)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)

            Link(destination: URL(string: "https://github.com/settings/installations")!) {
                HStack {
                    Text("MANAGE INSTALLED GITHUB APPS")
                        .bType(.monoCaption, weight: .bold)
                        .tracking(1)
                    Spacer()
                    Image(systemName: "arrow.up.right")
                        .accessibilityHidden(true)
                }
                .foregroundStyle(Color.brutalAccent)
                .padding(.horizontal, 16)
                .frame(minHeight: 44)
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

    @ViewBuilder
    private func installationRow(_ installation: PushSyncGitHubInstallation) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: installation.accountType == "Organization" ? "building.2" : "person.crop.circle")
                .foregroundStyle(installation.isSuspended ? Color.brutalError : Color.brutalSuccess)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text("@\(installation.accountLogin)")
                    .bType(.monoSm, weight: .semibold)
                    .foregroundStyle(Color.brutalText)
                Text(installation.isSuspended
                     ? "Access suspended on GitHub"
                     : installation.coversAllRepositories
                        ? "All repositories, including future repositories"
                        : "Selected repositories")
                    .bType(.monoCaption, weight: .regular)
                    .foregroundStyle(installation.isSuspended ? Color.brutalError : Color.brutalTextFaint)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 0) {
                Link(destination: installation.htmlURL) {
                    Text("MANAGE")
                        .bType(.monoCaption, weight: .bold)
                        .tracking(1)
                        .foregroundStyle(Color.brutalAccent)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(String(localized: "Manage GitHub access for @\(installation.accountLogin)"))

                Button {
                    pendingRemoval = installation
                } label: {
                    Text("REMOVE")
                        .bType(.monoCaption, weight: .bold)
                        .tracking(1)
                        .foregroundStyle(Color.brutalError)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "Remove @\(installation.accountLogin) from this device"))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
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

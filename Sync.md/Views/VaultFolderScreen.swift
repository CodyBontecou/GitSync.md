import SwiftUI
import UniformTypeIdentifiers

/// Vault settings › Folder: where the working copy lives, and Move.
struct VaultFolderScreen: View {
    @Environment(AppState.self) private var state
    let repoID: UUID
    /// Receives the folder picked as the vault's new parent.
    let onMove: (URL) -> Void

    @State private var showMovePicker = false

    private var breadcrumb: [String] { state.vaultFolderBreadcrumb(repoID: repoID) }

    var body: some View {
        TideToolScreen(title: String(localized: "Folder"), subtitle: state.repo(id: repoID)?.displayName) {
            TideGroupedCard {
                TideValueRow(
                    title: String(localized: "Location"),
                    value: breadcrumb.joined(separator: " › "),
                    mono: false,
                    showsChevron: false
                )
                TideRowDivider()
                VStack(alignment: .leading, spacing: 4) {
                    Text(String(localized: "Path"))
                        .font(TideFont.body)
                        .foregroundStyle(Tide.ink)
                    Text(state.vaultDisplayPath(for: repoID))
                        .font(TideFont.mono(11.5))
                        .foregroundStyle(Tide.inkFaint)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 14)
            }

            TideGroupedCard {
                Button { showMovePicker = true } label: {
                    actionRow(String(localized: "Move vault…"), icon: "folder.badge.plus")
                }
                TideRowDivider()
                Button { openInFiles() } label: {
                    actionRow(String(localized: "Open in Files"), icon: "folder")
                }
            }
            .buttonStyle(.plain)
            .disabled(!state.hasLocalRepositoryAccess(repoID: repoID))
            .padding(.top, 14)

            Text(String(localized: "Moving keeps history and settings. Pick the folder the vault should live in."))
                .font(TideFont.mono(11))
                .foregroundStyle(Tide.inkFaint)
                .padding(.horizontal, 4)
                .padding(.top, 10)
        }
        .fileImporter(
            isPresented: $showMovePicker,
            allowedContentTypes: [.folder],
            allowsMultipleSelection: false
        ) { result in
            if case .success(let urls) = result, let url = urls.first {
                onMove(url)
            }
        }
    }

    private func actionRow(_ title: String, icon: String) -> some View {
        HStack(spacing: 12) {
            Text(title)
                .font(TideFont.body)
                .foregroundStyle(Tide.ink)
            Spacer(minLength: 8)
            Image(systemName: icon)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Tide.inkFaint)
                .accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity, minHeight: 52)
        .contentShape(Rectangle())
    }

    private func openInFiles() {
        guard let url = state.vaultURLForOperation(for: repoID) else { return }
        let rawPath = url.path
        let path = rawPath.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? rawPath
        if let url = URL(string: "shareddocuments://\(path)"), UIApplication.shared.canOpenURL(url) {
            UIApplication.shared.open(url)
        }
    }
}

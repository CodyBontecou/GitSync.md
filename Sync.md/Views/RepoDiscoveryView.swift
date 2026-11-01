import SwiftUI
import UniformTypeIdentifiers

/// Discovers git working copies and reconnects them in one batch.
///
/// Two scan sources are supported:
/// 1. **GitSync.md storage** — the app's own Documents directory, scanned
///    automatically on appear. This recovers repositories that survived a
///    reinstall or backup restore while `repos.json` did not.
/// 2. **A user-granted folder** — picked once through the document picker.
///    The picker's security scope covers every descendant, so all working
///    copies found beneath it can be re-opened later from a single bookmark.
struct RepoDiscoveryView: View {
    /// Called after repositories were added successfully so the presenting
    /// sheet flow (typically `AddRepoView`) can close itself.
    var onComplete: (() -> Void)? = nil

    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    // MARK: - Scan state

    private enum ScanState: Equatable {
        case idle
        case scanning
        case done
        case failed(String)

        var isScanning: Bool { self == .scanning }
    }

    @State private var containerScan: ScanState = .idle
    @State private var containerResults: [DiscoveredRepo] = []

    @State private var grantRootURL: URL? = nil
    @State private var grantRootBookmark: Data? = nil
    @State private var grantScan: ScanState = .idle
    @State private var grantResults: [DiscoveredRepo] = []
    @State private var grantError: String? = nil
    @State private var showFolderPicker = false

    // MARK: - Selection & author

    @State private var selectedRepoIDs: Set<String> = []
    @State private var authorName: String = ""
    @State private var authorEmail: String = ""
    @State private var isAdding: Bool = false
    @State private var validationMessage: String? = nil
    @State private var showValidationAlert = false

    private var trimmedAuthorName: String { authorName.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var trimmedAuthorEmail: String { authorEmail.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var selectedContainerRepos: [DiscoveredRepo] {
        containerResults.filter { selectedRepoIDs.contains($0.id) }
    }

    /// Results already shown in GitSync.md storage are omitted from the grant
    /// section. A user can grant a folder inside Documents, so both scans may
    /// otherwise surface and process the exact same working copy.
    private var visibleGrantResults: [DiscoveredRepo] {
        GitRepoScanner.deduplicatedRepositories(grantResults, excluding: containerResults)
    }

    private var selectedGrantRepos: [DiscoveredRepo] {
        visibleGrantResults.filter { selectedRepoIDs.contains($0.id) }
    }

    private var selectedCount: Int {
        selectedRepoIDs.count
    }

    private var isScanning: Bool { containerScan.isScanning || grantScan.isScanning }

    private var foundCount: Int { containerResults.count + visibleGrantResults.count }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    AddVaultTopBar(backLabel: String(localized: "Cancel")) { dismiss() }
                        .padding(.top, 8)

                    headline
                        .padding(.top, 6)

                    scanStatusLine
                        .padding(.top, 10)

                    containerSection
                        .padding(.top, 20)

                    grantSection
                        .padding(.top, 22)

                    infoNote
                        .padding(.top, 14)

                    authorSection
                        .padding(.top, 22)
                }
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
            .background(Tide.paper.ignoresSafeArea())
            .safeAreaInset(edge: .bottom) {
                AddVaultBottomBar { addButton }
            }
            .toolbar(.hidden, for: .navigationBar)
            .fileImporter(
                isPresented: $showFolderPicker,
                allowedContentTypes: [.folder],
                allowsMultipleSelection: false
            ) { result in
                if case .success(let urls) = result, let url = urls.first {
                    handleGrantSelection(url)
                }
            }
            .onAppear {
                if trimmedAuthorName.isEmpty { authorName = state.defaultAuthorName }
                if trimmedAuthorEmail.isEmpty { authorEmail = state.defaultAuthorEmail }
                scanContainerIfNeeded()
            }
            .alert(
                String(localized: "Missing Required Fields"),
                isPresented: $showValidationAlert
            ) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(validationMessage ?? String(localized: "Please fill in the required fields."))
            }
        }
    }

    // MARK: - Headline

    @ViewBuilder
    private var headline: some View {
        if foundCount > 0 {
            AddVaultHeadline(
                title: foundCount == 1
                    ? String(localized: "Found 1 repo")
                    : String(localized: "Found \(foundCount) repos"),
                muted: String(localized: "already here.")
            )
        } else if isScanning {
            AddVaultHeadline(
                title: String(localized: "Looking for repos"),
                muted: String(localized: "on this iPhone.")
            )
        } else {
            AddVaultHeadline(
                title: String(localized: "Find clones"),
                muted: String(localized: "on this iPhone.")
            )
        }
    }

    /// Mono status line: blue dot while scanning, plain text after.
    private var scanStatusLine: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(isScanning ? Tide.behind : Tide.synced)
                .frame(width: 8, height: 8)
                .accessibilityHidden(true)
            Text(verbatim: scanStatusText)
                .font(TideFont.mono(12))
                .foregroundStyle(Tide.inkMid)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .padding(.horizontal, 20)
        .accessibilityElement(children: .combine)
    }

    private var scanStatusText: String {
        if grantScan.isScanning, let grantURL = grantRootURL {
            return String(localized: "scanning \(grantURL.lastPathComponent)…")
        }
        if containerScan.isScanning {
            return String(localized: "scanning On My iPhone › GitSync.md…")
        }
        return String(localized: "Scan GitSync.md storage or any folder you grant access to.")
    }

    // MARK: - GitSync.md storage section

    private var containerSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            AddVaultSectionLabel(
                title: String(localized: "GitSync.md Storage"),
                trailing: containerScan == .done ? "\(containerResults.count)" : nil
            )

            AddVaultCard {
                if containerScan.isScanning {
                    statusRow {
                        ProgressView().controlSize(.small)
                        Text(String(localized: "Scanning…"))
                            .font(TideFont.meta)
                            .foregroundStyle(Tide.inkMid)
                    }
                } else if case .failed(let message) = containerScan {
                    errorRow(message)
                } else if containerResults.isEmpty {
                    statusRow {
                        Text(String(localized: "No repositories found"))
                            .font(TideFont.sans(14, relativeTo: .subheadline))
                            .foregroundStyle(Tide.inkMid)
                    }
                } else {
                    ForEach(Array(containerResults.enumerated()), id: \.element.id) { index, repo in
                        if index > 0 { AddVaultHairline() }
                        repoRow(repo)
                    }
                }
            }
        }
    }

    // MARK: - Granted folder section

    private var grantSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            AddVaultSectionLabel(
                title: String(localized: "Other Folders"),
                trailing: grantScan == .done ? "\(visibleGrantResults.count)" : nil
            )

            if let grantURL = grantRootURL {
                AddVaultCard {
                    HStack(spacing: 14) {
                        AddVaultIconTile(systemImage: "folder", size: 40)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(grantURL.lastPathComponent)
                                .font(TideFont.sans(16, .semibold, relativeTo: .body))
                                .foregroundStyle(Tide.ink)
                                .lineLimit(1)
                            Text(grantURL.path)
                                .font(TideFont.mono(11))
                                .foregroundStyle(Tide.inkFaint)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                        Spacer(minLength: 8)
                        if grantScan.isScanning {
                            ProgressView().controlSize(.small)
                        }
                    }
                    .padding(.vertical, 14)

                    if case .failed(let message) = grantScan {
                        AddVaultHairline()
                        errorRow(message)
                    } else if grantScan == .done && visibleGrantResults.isEmpty {
                        AddVaultHairline()
                        statusRow {
                            Text(
                                grantResults.isEmpty
                                    ? String(localized: "No git repositories found inside this folder.")
                                    : String(localized: "Repositories from this folder are already listed above.")
                            )
                            .font(TideFont.sans(14, relativeTo: .subheadline))
                            .foregroundStyle(Tide.inkMid)
                            .fixedSize(horizontal: false, vertical: true)
                        }
                    } else {
                        ForEach(visibleGrantResults) { repo in
                            AddVaultHairline()
                            repoRow(repo)
                        }
                    }
                }
            } else if case .failed(let message) = grantScan {
                AddVaultStatusLine(text: message, tint: Tide.danger, systemImage: "exclamationmark.triangle")
            } else {
                AddVaultFootnote(text: String(localized: "iCloud Drive, other apps' folders, or anywhere in Files"))
            }

            Button {
                showFolderPicker = true
            } label: {
                Text(grantRootURL == nil
                     ? String(localized: "Choose folder to scan…")
                     : String(localized: "Scan another folder…"))
                    .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                    .foregroundStyle(Tide.ink)
                    .frame(maxWidth: .infinity, minHeight: 46)
                    .background(Capsule().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(grantScan.isScanning)
            .padding(.horizontal, Tide.gutter)
            .padding(.top, 4)
        }
    }

    private var infoNote: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "info.circle")
                .font(.system(size: 16))
                .foregroundStyle(Tide.inkMid)
                .accessibilityHidden(true)
            Text(String(localized: "Repos stay where they are. GitSync.md only remembers the folder, so Obsidian keeps working as before."))
                .font(TideFont.sans(13.5, relativeTo: .footnote))
                .foregroundStyle(Tide.inkMid)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(Color.brutalBorderSoft, style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
        )
        .padding(.horizontal, Tide.gutter)
    }

    private func statusRow<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        HStack(spacing: 10) {
            content()
            Spacer(minLength: 0)
        }
        .padding(.vertical, 15)
        .frame(minHeight: 44)
    }

    private func errorRow(_ message: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 12, weight: .semibold))
                .accessibilityHidden(true)
            Text(message)
                .font(TideFont.sans(14, relativeTo: .subheadline))
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .foregroundStyle(Tide.danger)
        .padding(.vertical, 15)
        .accessibilityElement(children: .combine)
    }

    // MARK: - Repo row

    private func repoRow(_ repo: DiscoveredRepo) -> some View {
        let isTracked = state.isRepoAlreadyTracked(atPath: repo.id)
        let isSelected = selectedRepoIDs.contains(repo.id)

        return Button {
            guard !isTracked else { return }
            toggleSelection(repo)
        } label: {
            HStack(spacing: 12) {
                AddVaultCheckbox(isOn: isSelected || isTracked, isEnabled: !isTracked)

                VStack(alignment: .leading, spacing: 3) {
                    Text(repo.name)
                        .font(TideFont.sans(16, .semibold, relativeTo: .body))
                        .tracking(-0.24)
                        .foregroundStyle(isTracked ? Tide.inkFaint : Tide.ink)
                        .lineLimit(1)
                    Text(verbatim: Self.locationLine(for: repo))
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .lineLimit(2)
                        .truncationMode(.middle)
                }

                Spacer(minLength: 8)

                if isTracked {
                    Text(String(localized: "added"))
                        .font(TideFont.metaStrong)
                        .foregroundStyle(Tide.synced)
                } else if repo.remoteURL == nil {
                    Text(String(localized: "local only"))
                        .font(TideFont.meta)
                        .foregroundStyle(Tide.inkFaint)
                }
            }
            .padding(.vertical, 14)
            // Defensive 44pt row minimum — content already exceeds it (Issue #17).
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isTracked)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    static func repositorySubtitle(for repo: DiscoveredRepo) -> String {
        if let remote = repo.remoteURL, let parsed = GitRemoteURL.parse(remote) {
            return parsed.displayPath
        }
        if let remote = repo.remoteURL, !remote.isEmpty {
            return remote
        }
        return String(localized: "No remote")
    }

    /// "Obsidian/journal · owner/journal · github.com" — where it lives, then its remote.
    static func locationLine(for repo: DiscoveredRepo) -> String {
        let path = repo.relativePath.isEmpty ? repo.name : repo.relativePath
        var parts = [path, repositorySubtitle(for: repo)]
        if let remote = repo.remoteURL, let host = GitRemoteURL.parse(remote)?.host, !host.isEmpty {
            parts.append(host)
        }
        return parts.joined(separator: " · ")
    }

    // MARK: - Author

    private var authorSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            AddVaultSectionLabel(title: String(localized: "Commit Author"))

            VStack(spacing: 12) {
                BTextField(
                    label: String(localized: "Author Name"),
                    text: $authorName,
                    placeholder: String(localized: "Your Name")
                )
                BTextField(
                    label: String(localized: "Author Email"),
                    text: $authorEmail,
                    placeholder: "you@example.com",
                    keyboardType: .emailAddress,
                    textContentType: .emailAddress,
                    autocapitalization: .never
                )
            }
            .padding(.horizontal, Tide.gutter)
        }
    }

    // MARK: - Add button

    private var addButton: some View {
        BPrimaryButton(
            title: selectedCount == 0
                ? String(localized: "Add vaults")
                : (selectedCount == 1
                    ? String(localized: "Add 1 vault")
                    : String(localized: "Add \(selectedCount) vaults")),
            isLoading: isAdding,
            isDisabled: selectedCount == 0 || isAdding
        ) {
            addSelected()
        }
    }

    // MARK: - Actions

    private func scanContainerIfNeeded() {
        guard containerScan == .idle else { return }
        containerScan = .scanning
        Task {
            let results = await Task.detached(priority: .userInitiated) {
                GitRepoScanner.discoverRepositoriesInAppContainer()
            }.value
            containerResults = results
            containerScan = .done
        }
    }

    private func handleGrantSelection(_ url: URL) {
        grantError = nil
        guard url.startAccessingSecurityScopedResource() else {
            grantScan = .failed(String(localized: "Could not access the selected folder."))
            return
        }

        guard let bookmark = try? url.bookmarkData(
            options: [],
            includingResourceValuesForKeys: nil,
            relativeTo: nil
        ) else {
            url.stopAccessingSecurityScopedResource()
            grantScan = .failed(String(localized: "Could not create a bookmark for the selected folder."))
            return
        }

        // Deselect anything that belonged to the previous grant.
        let previousGrantIDs = Set(grantResults.map(\.id))
        selectedRepoIDs.subtract(previousGrantIDs)

        grantRootURL = url
        grantRootBookmark = bookmark
        grantResults = []
        grantScan = .scanning

        // Keep the security scope alive for the detached filesystem scan. The
        // old function-level defer released it as soon as this method returned,
        // so the scan commonly ran after access had already been revoked.
        Task {
            defer { url.stopAccessingSecurityScopedResource() }
            let results = await Task.detached(priority: .userInitiated) {
                GitRepoScanner.discoverRepositories(root: url)
            }.value

            // Ignore a stale result if the user selected another folder while
            // this scan was still running.
            guard grantRootURL == url else { return }
            grantResults = results
            grantScan = .done
        }
    }

    private func toggleSelection(_ repo: DiscoveredRepo) {
        if selectedRepoIDs.contains(repo.id) {
            selectedRepoIDs.remove(repo.id)
        } else {
            selectedRepoIDs.insert(repo.id)
        }
    }

    private func addSelected() {
        var missing: [String] = []
        if trimmedAuthorName.isEmpty { missing.append(String(localized: "Author Name")) }
        if trimmedAuthorEmail.isEmpty { missing.append(String(localized: "Author Email")) }
        guard missing.isEmpty else {
            validationMessage = missing.count == 1
                ? String(localized: "Please fill in \(missing[0]).")
                : String(localized: "Please fill in these fields: \(missing.joined(separator: ", ")).")
            showValidationAlert = true
            return
        }

        let containerSelections = GitRepoScanner.deduplicatedRepositories(selectedContainerRepos)
        // Container storage takes precedence if scan state changes while the
        // user is selecting rows. Never process one working copy twice.
        let grantSelections = GitRepoScanner.deduplicatedRepositories(
            selectedGrantRepos,
            excluding: containerSelections
        )
        guard !containerSelections.isEmpty || !grantSelections.isEmpty else { return }
        let grantBookmark: Data
        if grantSelections.isEmpty {
            grantBookmark = Data()
        } else if let bookmark = grantRootBookmark {
            grantBookmark = bookmark
        } else {
            validationMessage = String(localized: "Could not access the scanned folder. Please scan it again.")
            showValidationAlert = true
            return
        }

        isAdding = true
        Task {
            // Container discoveries need no bookmark; granted-folder discoveries
            // all share the single grant-root bookmark from the scan.
            for repo in containerSelections {
                await state.relinkManagedRepo(
                    atURL: repo.url,
                    authorName: trimmedAuthorName,
                    authorEmail: trimmedAuthorEmail
                )
            }
            for repo in grantSelections {
                await state.addLocalRepo(
                    url: repo.url,
                    bookmarkData: grantBookmark,
                    authorName: trimmedAuthorName,
                    authorEmail: trimmedAuthorEmail,
                    relativePath: repo.relativePath
                )
            }
            isAdding = false
            onComplete?()
            dismiss()
        }
    }
}

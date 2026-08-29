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

    private var selectedGrantRepos: [DiscoveredRepo] {
        grantResults.filter { selectedRepoIDs.contains($0.id) }
    }

    private var selectedCount: Int {
        selectedRepoIDs.count
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.brutalBg.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 12) {
                        introCard

                        containerSection

                        grantSection

                        authorSection

                        addButton
                    }
                    .padding(.top, 12)
                    .padding(.bottom, 40)
                }
                .scrollIndicators(.hidden)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("DISCOVER REPOSITORIES")
                        .font(.system(size: 12, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .tracking(2)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
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

    // MARK: - Intro

    private var introCard: some View {
        BCard(padding: 14) {
            HStack(alignment: .top, spacing: 10) {
                Text("🔍")
                    .font(.system(size: 18))
                Text("Scan GitSync.md storage or any folder you grant access to. Every git working copy inside is listed for one-tap reconnect.")
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
            }
        }
        .padding(.horizontal, 20)
    }

    // MARK: - GitSync.md storage section

    private var containerSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            BSectionHeader(
                title: String(localized: "GitSync.md Storage"),
                subtitle: containerScan == .done && containerResults.isEmpty
                    ? String(localized: "No repositories found")
                    : nil
            )
            .padding(.horizontal, 20)

            BCard(padding: 0) {
                VStack(spacing: 0) {
                    HStack(spacing: 8) {
                        Text("📦").font(.system(size: 16))
                        Text("On My iPhone › GitSync.md")
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(Color.brutalText)
                        Spacer()
                        if containerScan.isScanning {
                            ProgressView().controlSize(.small)
                        } else if !containerResults.isEmpty {
                            BBadge(text: String(localized: "\(containerResults.count) found"), style: .accent)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)

                    if containerScan.isScanning {
                        BDivider().padding(.horizontal, 16)
                        HStack(spacing: 8) {
                            Image(systemName: "magnifyingglass")
                            Text("Scanning…")
                                .font(.system(size: 13, design: .monospaced))
                        }
                        .foregroundStyle(Color.brutalText)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                    } else if case .failed(let message) = containerScan {
                        BDivider().padding(.horizontal, 16)
                        HStack(spacing: 6) {
                            BBadge(text: String(localized: "ERROR"), style: .error)
                            Text(message)
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundStyle(Color.brutalError)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                    } else {
                        ForEach(Array(containerResults.enumerated()), id: \.element.id) { index, repo in
                            if index > 0 { BDivider().padding(.horizontal, 16) }
                            repoRow(repo)
                        }
                    }
                }
            }
            .padding(.horizontal, 20)
        }
    }

    // MARK: - Granted folder section

    private var grantSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            BSectionHeader(
                title: String(localized: "Other Folders"),
                subtitle: grantRootURL == nil
                    ? String(localized: "iCloud Drive, other apps' folders, or anywhere in Files")
                    : nil
            )
            .padding(.horizontal, 20)

            BCard(padding: 0) {
                VStack(spacing: 0) {
                    if let grantURL = grantRootURL {
                        HStack(spacing: 12) {
                            Text("📁").font(.system(size: 18))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(grantURL.lastPathComponent)
                                    .font(.system(size: 15, weight: .semibold, design: .monospaced))
                                    .foregroundStyle(Color.brutalText)
                                Text(grantURL.path)
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundStyle(Color.brutalText)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            }
                            Spacer()
                            if grantScan.isScanning {
                                ProgressView().controlSize(.small)
                            } else if !grantResults.isEmpty {
                                BBadge(text: String(localized: "\(grantResults.count) found"), style: .accent)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)

                        if case .failed(let message) = grantScan {
                            BDivider().padding(.horizontal, 16)
                            HStack(spacing: 6) {
                                BBadge(text: String(localized: "ERROR"), style: .error)
                                Text(message)
                                    .font(.system(size: 13, design: .monospaced))
                                    .foregroundStyle(Color.brutalError)
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                        } else if grantScan == .done && grantResults.isEmpty {
                            BDivider().padding(.horizontal, 16)
                            Text("No git repositories found inside this folder.")
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundStyle(Color.brutalText)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 12)
                        } else {
                            ForEach(Array(grantResults.enumerated()), id: \.element.id) { index, repo in
                                if index > 0 { BDivider().padding(.horizontal, 16) }
                                repoRow(repo)
                            }
                        }
                    }

                    BDivider().padding(.horizontal, 16)

                    Button {
                        showFolderPicker = true
                    } label: {
                        HStack(spacing: 6) {
                            Text("📂")
                            Text(
                                grantRootURL == nil
                                    ? String(localized: "CHOOSE FOLDER TO SCAN")
                                    : String(localized: "SCAN DIFFERENT FOLDER")
                            )
                                .font(.system(size: 12, weight: .bold, design: .monospaced))
                                .foregroundStyle(Color.brutalAccent)
                                .tracking(1)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20)
        }
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
                Image(systemName: isSelected ? "checkmark.square.fill" : "square")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(isSelected ? Color.brutalAccent : Color.brutalText)

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(repo.name)
                            .font(.system(size: 15, weight: .semibold, design: .monospaced))
                            .foregroundStyle(isTracked ? Color.brutalText.opacity(0.5) : Color.brutalText)
                            .lineLimit(1)
                        if isTracked {
                            BBadge(text: String(localized: "added"), style: .success)
                        }
                    }
                    Text(subtitle(for: repo))
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isTracked)
    }

    private func subtitle(for repo: DiscoveredRepo) -> String {
        if let remote = repo.remoteURL, let parsed = GitRemoteURL.parse(remote) {
            return "\(parsed.ownerName)/\(parsed.repoName)"
        }
        if let remote = repo.remoteURL, !remote.isEmpty {
            return remote
        }
        return String(localized: "No remote")
    }

    // MARK: - Author

    private var authorSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            BSectionHeader(title: String(localized: "Commit Author"))
                .padding(.horizontal, 20)

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
            .padding(.horizontal, 20)
        }
    }

    // MARK: - Add button

    private var addButton: some View {
        BPrimaryButton(
            title: selectedCount == 0
                ? String(localized: "Add Repositories")
                : String(localized: "Add \(selectedCount) Repositor\(selectedCount == 1 ? "y" : "ies")"),
            isLoading: isAdding,
            isDisabled: selectedCount == 0 || isAdding,
            icon: "square.stack.3d.up.badge.automatic"
        ) {
            addSelected()
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
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
        defer { url.stopAccessingSecurityScopedResource() }

        guard let bookmark = try? url.bookmarkData(
            options: [],
            includingResourceValuesForKeys: nil,
            relativeTo: nil
        ) else {
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

        Task {
            let results = await Task.detached(priority: .userInitiated) {
                GitRepoScanner.discoverRepositories(root: url)
            }.value
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

        let containerSelections = selectedContainerRepos
        let grantSelections = selectedGrantRepos
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

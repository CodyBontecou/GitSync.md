import SwiftUI
import UniformTypeIdentifiers

/// Discovers git working copies and reconnects them in one batch.
///
/// The automatic scan covers every location the app may already read:
/// 1. **GitSync.md storage** — the app's own Documents directory. This
///    recovers repositories that survived a reinstall or backup restore while
///    `repos.json` did not.
/// 2. **The default save location** — when the user picked one.
/// 3. **Folders granted here before** — remembered security-scoped bookmarks
///    (typically On My iPhone › Obsidian).
///
/// "Scan another folder…" grants a new folder through the document picker.
/// Its security scope covers every descendant, so all working copies found
/// beneath it can be re-opened later from that single bookmark.
struct RepoDiscoveryView: View {
    /// Called after repositories were added successfully so the presenting
    /// sheet flow (typically `AddRepoView`) can close itself.
    var onComplete: (() -> Void)? = nil

    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    // MARK: - Scan sources

    /// One place to scan. Container results are relinked without a bookmark;
    /// every other source shares its own bookmark with the repos found in it.
    private struct ScanSource: Identifiable, Equatable {
        enum Kind: Equatable { case container, defaultSaveLocation, granted }

        let id: String
        let kind: Kind
        let url: URL
        let bookmark: Data?
        let label: String
    }

    private enum ScanState: Equatable {
        case idle
        case scanning
        case done
        case failed(String)

        var isScanning: Bool { self == .scanning }
    }

    @State private var sources: [ScanSource] = []
    @State private var scanStates: [String: ScanState] = [:]
    @State private var resultsBySource: [String: [DiscoveredRepo]] = [:]
    @State private var currentScanLabel: String? = nil
    @State private var didStartAutomaticScan = false
    @State private var showFolderPicker = false
    @State private var pickerError: String? = nil

    // MARK: - Selection & author

    @State private var selectedRepoIDs: Set<String> = []
    @State private var authorName: String = ""
    @State private var authorEmail: String = ""
    @State private var isAdding: Bool = false
    @State private var validationMessage: String? = nil
    @State private var showValidationAlert = false

    private var trimmedAuthorName: String { authorName.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var trimmedAuthorEmail: String { authorEmail.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Every result with its source, earlier sources winning when two scans
    /// surface the same working copy (a granted folder can contain storage).
    private var visibleResults: [(repo: DiscoveredRepo, source: ScanSource)] {
        var seen = Set<String>()
        var rows: [(DiscoveredRepo, ScanSource)] = []
        for source in sources {
            for repo in resultsBySource[source.id] ?? [] where seen.insert(repo.id).inserted {
                rows.append((repo, source))
            }
        }
        return rows
    }

    private var isScanning: Bool { scanStates.values.contains(.scanning) }

    private var foundCount: Int { visibleResults.count }

    private var selectedCount: Int {
        visibleResults.filter { selectedRepoIDs.contains($0.repo.id) }.count
    }

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

                    resultsSection
                        .padding(.top, 20)

                    scanAnotherSection
                        .padding(.top, 14)

                    infoNote
                        .padding(.top, 18)

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
                startAutomaticScanIfNeeded()
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
                muted: String(localized: "on this iPhone.").forThisDevice
            )
        } else {
            AddVaultHeadline(
                title: String(localized: "Find clones"),
                muted: String(localized: "on this iPhone.").forThisDevice
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
        if isScanning, let label = currentScanLabel {
            return String(localized: "scanning \(label)…")
        }
        guard !sources.isEmpty else {
            return String(localized: "Scan GitSync.md storage or any folder you grant access to.")
        }
        let scanned = sources.map(\.label).joined(separator: ", ")
        return String(localized: "scanned \(scanned)")
    }

    // MARK: - Results

    private var resultsSection: some View {
        let rows = visibleResults
        let failures = sources.compactMap { source -> (ScanSource, String)? in
            if case .failed(let message) = scanStates[source.id] { return (source, message) }
            return nil
        }
        return VStack(alignment: .leading, spacing: 8) {
            AddVaultSectionLabel(
                title: String(localized: "On this iPhone").forThisDevice,
                trailing: isScanning ? nil : "\(rows.count)"
            )

            AddVaultCard {
                if rows.isEmpty {
                    if isScanning {
                        statusRow {
                            ProgressView().controlSize(.small)
                            Text(String(localized: "Scanning…"))
                                .font(TideFont.meta)
                                .foregroundStyle(Tide.inkMid)
                        }
                    } else if failures.isEmpty {
                        statusRow {
                            Text(String(localized: "No repositories found"))
                                .font(TideFont.sans(14, relativeTo: .subheadline))
                                .foregroundStyle(Tide.inkMid)
                        }
                    }
                } else {
                    ForEach(Array(rows.enumerated()), id: \.element.repo.id) { index, row in
                        if index > 0 { AddVaultHairline() }
                        repoRow(row.repo, source: row.source)
                    }
                    if isScanning {
                        AddVaultHairline()
                        statusRow {
                            ProgressView().controlSize(.small)
                            Text(String(localized: "Scanning…"))
                                .font(TideFont.meta)
                                .foregroundStyle(Tide.inkMid)
                        }
                    }
                }

                ForEach(failures, id: \.0.id) { failure in
                    if !rows.isEmpty || isScanning { AddVaultHairline() }
                    errorRow("\(failure.0.label): \(failure.1)")
                }
            }
        }
    }

    private var scanAnotherSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let pickerError {
                AddVaultStatusLine(text: pickerError, tint: Tide.danger, systemImage: "exclamationmark.triangle")
            }
            Button {
                showFolderPicker = true
            } label: {
                Text(String(localized: "Scan another folder…"))
                    .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                    .foregroundStyle(Tide.ink)
                    .frame(maxWidth: .infinity, minHeight: 46)
                    .background(Capsule().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(isScanning)
            .padding(.horizontal, Tide.gutter)
            .accessibilityHint(String(localized: "iCloud Drive, other apps' folders, or anywhere in Files"))
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

    private func repoRow(_ repo: DiscoveredRepo, source: ScanSource) -> some View {
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
                    Text(verbatim: Self.locationLine(for: repo, sourceName: source.url.lastPathComponent))
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .lineLimit(2)
                        .truncationMode(.middle)
                }

                Spacer(minLength: 8)

                trailingStatus(repo, isTracked: isTracked)
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

    @ViewBuilder
    private func trailingStatus(_ repo: DiscoveredRepo, isTracked: Bool) -> some View {
        if isTracked {
            Text(String(localized: "added"))
                .font(TideFont.metaStrong)
                .foregroundStyle(Tide.synced)
        } else if repo.remoteURL == nil {
            Text(String(localized: "local only"))
                .font(TideFont.meta)
                .foregroundStyle(Tide.inkFaint)
        } else if let changes = repo.changeCount {
            if changes == 0 {
                Text(String(localized: "clean"))
                    .font(TideFont.metaStrong)
                    .foregroundStyle(Tide.synced)
            } else {
                Text(String(localized: "\(changes) changed"))
                    .font(TideFont.metaStrong)
                    .foregroundStyle(Tide.ahead)
            }
        }
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

    /// "Obsidian/journal · main · github.com" — where it lives, its branch,
    /// then its remote host ("no remote" for local-only repositories).
    static func locationLine(for repo: DiscoveredRepo, sourceName: String? = nil) -> String {
        var path = repo.relativePath.isEmpty ? repo.name : repo.relativePath
        if let sourceName, !sourceName.isEmpty, !repo.relativePath.isEmpty {
            path = "\(sourceName)/\(repo.relativePath)"
        }
        var parts = [path]
        if let branch = repo.branch, !branch.isEmpty { parts.append(branch) }
        if let remote = repo.remoteURL, let parsed = GitRemoteURL.parse(remote) {
            if let host = parsed.host, !host.isEmpty {
                parts.append(host)
            } else {
                parts.append(parsed.displayPath)
            }
        } else if repo.remoteURL == nil {
            parts.append(String(localized: "no remote"))
        } else {
            parts.append(repositorySubtitle(for: repo))
        }
        return parts.joined(separator: " · ")
    }

    /// "On My iPhone › Obsidian" for File Provider storage, "iCloud Drive › …"
    /// for iCloud, otherwise the folder name.
    static func friendlyLocation(for url: URL) -> String {
        let path = AppState.canonicalFilePath(for: url)
        let components: [String]
        let prefix: String
        if let range = path.range(of: "/File Provider Storage/") {
            prefix = String(localized: "On My iPhone").forThisDevice
            components = path[range.upperBound...].split(separator: "/").map(String.init)
        } else if let range = path.range(of: "/Mobile Documents/com~apple~CloudDocs/") {
            prefix = String(localized: "iCloud Drive")
            components = path[range.upperBound...].split(separator: "/").map(String.init)
        } else {
            return url.lastPathComponent
        }
        return ([prefix] + components.suffix(2)).joined(separator: " › ")
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

    // MARK: - Scanning

    /// Builds the automatic sources once and scans them in order.
    private func startAutomaticScanIfNeeded() {
        guard !didStartAutomaticScan else { return }
        didStartAutomaticScan = true

        var automatic: [ScanSource] = []
        let documents = AppState.appDocumentsDirectory
        automatic.append(ScanSource(
            id: AppState.canonicalFilePath(for: documents),
            kind: .container,
            url: documents,
            bookmark: nil,
            label: String(localized: "On My iPhone › GitSync.md").forThisDevice
        ))

        if let defaultURL = state.resolvedDefaultSaveURL,
           let bookmark = state.defaultSaveLocationBookmarkData {
            automatic.append(ScanSource(
                id: AppState.canonicalFilePath(for: defaultURL),
                kind: .defaultSaveLocation,
                url: defaultURL,
                bookmark: bookmark,
                label: Self.friendlyLocation(for: defaultURL)
            ))
        }

        for bookmark in DiscoveryScanRootStore.load() {
            var stale = false
            guard let url = try? URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) else {
                DiscoveryScanRootStore.forget(bookmark)
                continue
            }
            var current = bookmark
            if stale, url.startAccessingSecurityScopedResource() {
                if let refreshed = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) {
                    DiscoveryScanRootStore.remember(refreshed, for: url)
                    current = refreshed
                }
                url.stopAccessingSecurityScopedResource()
            }
            automatic.append(ScanSource(
                id: AppState.canonicalFilePath(for: url),
                kind: .granted,
                url: url,
                bookmark: current,
                label: Self.friendlyLocation(for: url)
            ))
        }

        let unique = automatic.reduce(into: [ScanSource]()) { list, source in
            if !list.contains(where: { $0.id == source.id }) { list.append(source) }
        }
        sources = unique
        for source in unique { scanStates[source.id] = .idle }

        Task {
            for source in unique {
                await scan(source, preselect: true)
            }
        }
    }

    /// Scans one source and reads each result's branch and working-tree
    /// status while that source's security scope is held.
    private func scan(_ source: ScanSource, preselect: Bool) async {
        scanStates[source.id] = .scanning
        currentScanLabel = source.label
        defer { if currentScanLabel == source.label { currentScanLabel = nil } }

        let url = source.url
        let isContainer = source.kind == .container
        let scoped = isContainer ? false : url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        if !isContainer && !scoped && !FileManager.default.isReadableFile(atPath: url.path) {
            scanStates[source.id] = .failed(String(localized: "Access to this folder expired. Scan it again."))
            return
        }

        let found = await Task.detached(priority: .userInitiated) {
            GitRepoScanner.discoverRepositories(root: url, isInsideAppContainer: isContainer)
        }.value
        // Show rows immediately, then fill in branch and status.
        resultsBySource[source.id] = found
        if preselect { preselectUntracked(found) }

        let withStatus = await Task.detached(priority: .userInitiated) {
            await GitRepoScanner.withWorkingTreeStatus(found)
        }.value
        resultsBySource[source.id] = withStatus
        scanStates[source.id] = .done
    }

    /// Repositories with a remote start selected; local-only ones are opt-in.
    private func preselectUntracked(_ repos: [DiscoveredRepo]) {
        for repo in repos where repo.remoteURL != nil && !state.isRepoAlreadyTracked(atPath: repo.id) {
            selectedRepoIDs.insert(repo.id)
        }
    }

    private func handleGrantSelection(_ url: URL) {
        pickerError = nil
        guard url.startAccessingSecurityScopedResource() else {
            pickerError = String(localized: "Could not access the selected folder.")
            return
        }
        defer { url.stopAccessingSecurityScopedResource() }

        guard let bookmark = try? url.bookmarkData(
            options: [],
            includingResourceValuesForKeys: nil,
            relativeTo: nil
        ) else {
            pickerError = String(localized: "Could not create a bookmark for the selected folder.")
            return
        }

        DiscoveryScanRootStore.remember(bookmark, for: url)
        let source = ScanSource(
            id: AppState.canonicalFilePath(for: url),
            kind: .granted,
            url: url,
            bookmark: bookmark,
            label: Self.friendlyLocation(for: url)
        )
        // Replace an earlier source for the same folder (fresh bookmark).
        if let index = sources.firstIndex(where: { $0.id == source.id }) {
            sources[index] = source
        } else {
            sources.append(source)
        }
        Task { await scan(source, preselect: true) }
    }

    private func toggleSelection(_ repo: DiscoveredRepo) {
        if selectedRepoIDs.contains(repo.id) {
            selectedRepoIDs.remove(repo.id)
        } else {
            selectedRepoIDs.insert(repo.id)
        }
    }

    // MARK: - Adding

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

        let selections = visibleResults.filter {
            selectedRepoIDs.contains($0.repo.id) && !state.isRepoAlreadyTracked(atPath: $0.repo.id)
        }
        guard !selections.isEmpty else { return }
        if selections.contains(where: { $0.source.kind != .container && $0.source.bookmark == nil }) {
            validationMessage = String(localized: "Could not access the scanned folder. Please scan it again.")
            showValidationAlert = true
            return
        }

        isAdding = true
        Task {
            // Container discoveries need no bookmark; every other discovery
            // shares the bookmark of the folder it was found in.
            for selection in selections {
                if selection.source.kind == .container {
                    await state.relinkManagedRepo(
                        atURL: selection.repo.url,
                        authorName: trimmedAuthorName,
                        authorEmail: trimmedAuthorEmail
                    )
                } else if let bookmark = selection.source.bookmark {
                    await state.addLocalRepo(
                        url: selection.repo.url,
                        bookmarkData: bookmark,
                        authorName: trimmedAuthorName,
                        authorEmail: trimmedAuthorEmail,
                        relativePath: selection.repo.relativePath
                    )
                }
            }
            isAdding = false
            onComplete?()
            dismiss()
        }
    }
}

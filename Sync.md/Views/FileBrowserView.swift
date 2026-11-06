import SwiftUI

// MARK: - Navigation Destination

struct FileBrowserDestination: Hashable {
    let repoID: UUID
    let relativePath: String  // "" = vault root
}

// MARK: - File Item

struct FileItem: Identifiable {
    let url: URL
    let name: String
    /// Path relative to the vault root (e.g. "src/lib/store.js"). Built from
    /// the directory we enumerated plus the entry name — never by stripping
    /// the vault path prefix off `url.path`.
    let relativePath: String
    let isDirectory: Bool
    var id: String { url.path }
}

/// On-disk facts shown under a row: modification time + size, or a folder's
/// item count, plus Git LFS state. Read once per load, never invented.
private struct FileItemMeta {
    var modified: Date?
    var size: Int?
    var childCount: Int?
    /// Matches a `filter=lfs` rule in .gitattributes.
    var isLFSTracked = false
    /// The file on disk is still an un-hydrated LFS pointer.
    var lfsPointer: GitLFSPointer?
}

// MARK: - Full-screen browser

struct FileBrowserView: View {
    @Environment(AppState.self) private var state
    let repoID: UUID
    let relativePath: String

    private var navTitle: String {
        relativePath.isEmpty
            ? (state.repo(id: repoID)?.displayName ?? String(localized: "Files"))
            : URL(fileURLWithPath: relativePath).lastPathComponent
    }

    var body: some View {
        ZStack {
            Tide.paper.ignoresSafeArea()
            ScrollView {
                FileBrowserListing(repoID: repoID, relativePath: relativePath, fillsEmptyState: true)
                    .padding(.horizontal, Tide.gutter)
                    .padding(.top, 4)
                    .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(navTitle)
                    .font(TideFont.sans(17, .semibold, relativeTo: .headline))
                    .foregroundStyle(Tide.ink)
                    .lineLimit(1)
                    .accessibilityAddTraits(.isHeader)
            }
        }
    }

    /// Lists a directory for the file browser.
    ///
    /// Relative paths are composed from `parentRelativePath` + entry name.
    /// String-prefix math against `url.path` is explicitly avoided: on iOS the
    /// vault URL comes back through the `/var -> /private/var` symlink while
    /// `contentsOfDirectory` returns child URLs with symlinks resolved, so
    /// prefix stripping silently fails and every folder two or more levels
    /// deep navigates to a non-existent path and renders as "Empty Directory".
    static func listContents(
        of directory: URL,
        parentRelativePath: String
    ) throws -> [FileItem] {
        let contents = try FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: .skipsHiddenFiles
        )

        return contents.compactMap { url -> FileItem? in
            let name = url.lastPathComponent
            guard name != ".git" else { return nil }
            let isDir = (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false
            let relativePath = parentRelativePath.isEmpty
                ? name
                : parentRelativePath + "/" + name
            return FileItem(url: url, name: name, relativePath: relativePath, isDirectory: isDir)
        }
        .sorted {
            if $0.isDirectory != $1.isDirectory { return $0.isDirectory }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }
}

// MARK: - Listing (inline in the Vault Files tab and inside the browser)

/// One folder of the vault as a Tide card: breadcrumb + count, rows with
/// git status, LFS badges and pointer "Fetch", and long-press actions
/// (Open in…, Rename, History, Delete). Not a List, so it can sit inside the
/// Vault's scroll view.
struct FileBrowserListing: View {
    @Environment(AppState.self) private var state
    let repoID: UUID
    let relativePath: String
    /// Full-screen browser: show the empty state large.
    var fillsEmptyState = false

    @State private var items: [FileItem] = []
    @State private var meta: [String: FileItemMeta] = [:]
    @State private var loadErrorMessage: String?
    @State private var renameItem: FileItem?
    @State private var newName = ""
    @State private var showNewFile = false
    @State private var deleteItem: FileItem?
    @State private var historyItem: FileItem?
    @State private var shareItem: FileItem?
    @State private var fetchingPaths: Set<String> = []
    @State private var openAfterCreate: FileEditorDestination?
    @State private var operationError: String?

    private var vaultURL: URL { state.vaultURL(for: repoID) }
    private var currentURL: URL {
        relativePath.isEmpty ? vaultURL : vaultURL.appendingPathComponent(relativePath)
    }
    private var statusEntries: [GitStatusEntry] { state.statusEntriesByRepo[repoID] ?? [] }
    private var vaultName: String { state.repo(id: repoID)?.displayName ?? String(localized: "Files") }
    /// "ideas/" — the folder new files land in, as a git path.
    private var folderLabel: String { relativePath.isEmpty ? "/" : relativePath + "/" }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            breadcrumb
                .padding(.horizontal, 4)
                .padding(.bottom, 8)

            if items.isEmpty {
                emptyState
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                        fileRow(item)
                        if index < items.count - 1 {
                            Rectangle().fill(Tide.line).frame(height: 1)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 2)
                .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))

                Text(String(localized: "long-press any file for actions"))
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 12)
            }
        }
        .task(id: currentURL.standardizedFileURL.path) { loadItems() }
        .onChange(of: statusEntries) { loadItems() }
        .sheet(isPresented: Binding(get: { renameItem != nil }, set: { if !$0 { renameItem = nil } })) {
            if let item = renameItem {
                TideFileNameSheet(
                    title: String(localized: "Rename"),
                    location: item.name,
                    placeholder: item.name,
                    confirmLabel: String(localized: "Rename"),
                    text: $newName,
                    conflictMessage: { renameConflict(for: item, name: $0) },
                    onConfirm: {
                        let target = newName
                        renameItem = nil
                        performRename(item, to: target)
                    },
                    onCancel: { renameItem = nil }
                )
            }
        }
        .sheet(isPresented: $showNewFile) {
            NewFileSheet(directoryURL: currentURL, folderLabel: folderLabel, vaultName: vaultName) { url, isFolder in
                showNewFile = false
                loadItems()
                state.detectChanges(repoID: repoID)
                if !isFolder {
                    // Let the sheet finish dismissing before pushing the editor.
                    Task {
                        try? await Task.sleep(for: .milliseconds(350))
                        openAfterCreate = FileEditorDestination(repoID: repoID, fileURL: url)
                    }
                }
            }
        }
        .sheet(item: Binding(get: { historyItem.map(HistoryTarget.init) }, set: { historyItem = $0?.item })) { target in
            FileHistorySheet(repoID: repoID, path: target.item.relativePath)
        }
        .sheet(item: Binding(get: { shareItem.map(HistoryTarget.init) }, set: { shareItem = $0?.item })) { target in
            ActivityView(items: [target.item.url])
                .presentationDetents([.medium, .large])
        }
        .alert(
            deleteItem.map { String(localized: "Delete \"\($0.name)\"?") } ?? "",
            isPresented: Binding(get: { deleteItem != nil }, set: { if !$0 { deleteItem = nil } })
        ) {
            Button(String(localized: "Delete"), role: .destructive) {
                if let item = deleteItem { performDelete(item) }
                deleteItem = nil
            }
            Button(String(localized: "Cancel"), role: .cancel) { deleteItem = nil }
        } message: {
            Text(deleteItem?.isDirectory == true
                 ? String(localized: "The folder and everything in it will show up in git status as deleted.")
                 : String(localized: "This will be reflected in git status as a deletion."))
        }
        .alert(
            String(localized: "Unable to Update File"),
            isPresented: Binding(get: { operationError != nil }, set: { if !$0 { operationError = nil } })
        ) {
            Button(String(localized: "OK"), role: .cancel) { operationError = nil }
        } message: {
            Text(operationError ?? "")
        }
        .navigationDestination(item: $openAfterCreate) { dest in
            FileEditorView(repoID: dest.repoID, fileURL: dest.fileURL)
        }
    }

    /// Identifiable wrapper so `.sheet(item:)` can drive per-file sheets.
    private struct HistoryTarget: Identifiable {
        let item: FileItem
        var id: String { item.id }
    }

    // MARK: Breadcrumb

    /// "VoxVault / ideas      9 items  +"
    private var breadcrumb: some View {
        HStack(spacing: 6) {
            Group {
                Text(vaultName)
                    .foregroundStyle(relativePath.isEmpty ? Tide.ink : Tide.inkMid)
                    .fontWeight(relativePath.isEmpty ? .semibold : .regular)
                    .lineLimit(1)
                    .layoutPriority(1)
                Text("/").foregroundStyle(Tide.inkFaint)
                if !relativePath.isEmpty {
                    Text(relativePath)
                        .foregroundStyle(Tide.ink)
                        .fontWeight(.semibold)
                        .lineLimit(1)
                        .truncationMode(.head)
                }
                Spacer(minLength: 8)
                Text(items.count == 1
                     ? String(localized: "1 item")
                     : String(localized: "\(items.count) items"))
                    .foregroundStyle(Tide.inkMid)
                    .fixedSize()
            }
            .font(TideFont.mono(12))
            .accessibilityElement(children: .combine)

            Button {
                showNewFile = true
            } label: {
                Image(systemName: "plus")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Tide.ink)
                    .frame(width: 30, height: 30)
                    .background(Circle().fill(Tide.card))
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(String(localized: "Create New File"))
        }
    }

    // MARK: Rows

    @ViewBuilder
    private func fileRow(_ item: FileItem) -> some View {
        let info = meta[item.id]
        Group {
            if item.isDirectory {
                NavigationLink(value: FileBrowserDestination(repoID: repoID, relativePath: item.relativePath)) {
                    rowContent(item: item, info: info)
                }
            } else if info?.lfsPointer != nil {
                // Pointer files aren't readable notes; Fetch hydrates them.
                rowContent(item: item, info: info)
            } else {
                NavigationLink(value: FileEditorDestination(repoID: repoID, fileURL: item.url)) {
                    rowContent(item: item, info: info)
                }
            }
        }
        .buttonStyle(.plain)
        .contextMenu { actions(for: item) }
    }

    @ViewBuilder
    private func actions(for item: FileItem) -> some View {
        if !item.isDirectory {
            Button { shareItem = item } label: {
                Label(String(localized: "Open in…"), systemImage: "square.and.arrow.up")
            }
        }
        Button { beginRename(item) } label: {
            Label(String(localized: "Rename"), systemImage: "pencil")
        }
        if !item.isDirectory {
            Button { historyItem = item } label: {
                Label(String(localized: "History"), systemImage: "clock.arrow.circlepath")
            }
        }
        if meta[item.id]?.lfsPointer != nil {
            Button { fetch(item) } label: {
                Label(String(localized: "Fetch from LFS"), systemImage: "arrow.down.circle")
            }
        }
        Divider()
        Button(role: .destructive) { deleteItem = item } label: {
            Label(String(localized: "Delete"), systemImage: "trash")
        }
    }

    private func rowContent(item: FileItem, info: FileItemMeta?) -> some View {
        let gitStatus = gitStatusFor(item)
        return HStack(spacing: 12) {
            Image(systemName: iconName(for: item))
                .font(.system(size: 17, weight: .regular))
                .foregroundStyle(Tide.inkMid)
                .frame(width: 22)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                    .font(TideFont.body)
                    .foregroundStyle(info?.lfsPointer != nil ? Tide.inkMid : Tide.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if !item.isDirectory, let detail = metaLine(for: info) {
                    Text(detail)
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 8)

            if info?.lfsPointer != nil {
                fetchButton(item)
            } else if info?.isLFSTracked == true {
                Text("LFS")
                    .font(TideFont.mono(10, .semibold))
                    .foregroundStyle(Tide.behind)
                    .padding(.horizontal, 6)
                    .frame(height: 20)
                    .background(RoundedRectangle(cornerRadius: 6).fill(Tide.behind.opacity(0.12)))
                    .accessibilityLabel(String(localized: "Stored with Git LFS"))
            }

            if let status = gitStatus {
                let tint = statusTint(for: status)
                Text(statusLabel(for: status))
                    .font(TideFont.mono(11, .semibold))
                    .foregroundStyle(tint)
                    .frame(width: 22, height: 22)
                    .background(RoundedRectangle(cornerRadius: 7).fill(tint.opacity(0.12)))
                    .accessibilityLabel(statusWord(for: status))
            }

            if item.isDirectory {
                HStack(spacing: 4) {
                    if let count = info?.childCount {
                        Text("\(count)").font(TideFont.mono(12))
                    }
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .accessibilityHidden(true)
                }
                .foregroundStyle(Tide.inkFaint)
            }
        }
        .padding(.vertical, 13)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }

    private func fetchButton(_ item: FileItem) -> some View {
        Button { fetch(item) } label: {
            Group {
                if fetchingPaths.contains(item.relativePath) {
                    ProgressView().controlSize(.mini).tint(Tide.ink)
                } else {
                    Text(String(localized: "Fetch"))
                        .font(TideFont.sans(13, .semibold, relativeTo: .footnote))
                }
            }
            .foregroundStyle(Tide.ink)
            .padding(.horizontal, 12)
            .frame(height: 30)
            .background(Capsule().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
            .frame(minHeight: 44)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(fetchingPaths.contains(item.relativePath))
        .accessibilityLabel(String(localized: "Fetch \(item.name) from Git LFS"))
    }

    // MARK: Empty State

    private var emptyState: some View {
        VStack(spacing: 0) {
            Image(systemName: loadErrorMessage == nil ? "folder" : "lock")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(loadErrorMessage == nil ? Tide.inkMid : Tide.danger)
                .frame(width: 48, height: 48)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(loadErrorMessage == nil ? Tide.well : Tide.danger.opacity(0.12)))
                .accessibilityHidden(true)
                .padding(.bottom, 16)
            Text(loadErrorMessage == nil ? String(localized: "Empty Directory") : String(localized: "Couldn't Read Folder"))
                .tideDisplay(fillsEmptyState ? 26 : 20)
                .multilineTextAlignment(.center)
            Text(loadErrorMessage ?? String(localized: "No files found"))
                .font(TideFont.meta)
                .foregroundStyle(Tide.inkFaint)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 32)
        .padding(.vertical, fillsEmptyState ? 120 : 32)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous)
            .fill(fillsEmptyState ? Color.clear : Tide.card))
    }

    // MARK: Data Loading

    private func loadItems() {
        do {
            items = try FileBrowserView.listContents(of: currentURL, parentRelativePath: relativePath)
            loadErrorMessage = nil
        } catch {
            // A failed read is not an empty directory — surface it distinctly
            // so navigation bugs and sandbox failures can't masquerade as
            // "No files found" (which is exactly how the /private/var bug hid).
            items = []
            loadErrorMessage = error.localizedDescription
            DebugLogger.shared.error("files", "Could not list \(currentURL.path)", detail: error.localizedDescription)
        }
        meta = Self.loadMeta(for: items, attributes: GitLFSAttributes.load(from: vaultURL))
    }

    private static func loadMeta(for items: [FileItem], attributes: GitLFSAttributes) -> [String: FileItemMeta] {
        var result: [String: FileItemMeta] = [:]
        for item in items {
            if item.isDirectory {
                let children = try? FileManager.default.contentsOfDirectory(
                    at: item.url,
                    includingPropertiesForKeys: nil,
                    options: .skipsHiddenFiles
                )
                result[item.id] = FileItemMeta(childCount: children?.count)
            } else {
                let values = try? item.url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])
                var info = FileItemMeta(modified: values?.contentModificationDate, size: values?.fileSize)
                info.isLFSTracked = attributes.isLFSTracked(path: item.relativePath)
                if let size = values?.fileSize, size <= 2048,
                   let data = try? Data(contentsOf: item.url) {
                    info.lfsPointer = GitLFSPointer(data: data)
                }
                result[item.id] = info
            }
        }
        return result
    }

    // MARK: Helpers

    private func gitStatusFor(_ item: FileItem) -> GitStatusEntry? {
        // Normalise to NFC before comparing: git stores paths as NFC while
        // APFS/HFS+ gives back NFD from FileManager, so a straight == fails
        // for Korean, Japanese, and other non-ASCII filenames.
        let rel = item.relativePath.precomposedStringWithCanonicalMapping
        if item.isDirectory {
            return statusEntries.first { $0.path.hasPrefix(rel + "/") }
        }
        return statusEntries.first { $0.path == rel }
    }

    /// "edited 9m ago · 3.1 KB", or "LFS pointer · 38 MB on remote".
    private func metaLine(for info: FileItemMeta?) -> String? {
        guard let info else { return nil }
        if let pointer = info.lfsPointer {
            let size = ByteCountFormatter.string(fromByteCount: pointer.size, countStyle: .file)
            return String(localized: "LFS pointer · \(size) on remote")
        }
        var parts: [String] = []
        if let modified = info.modified {
            let fmt = RelativeDateTimeFormatter()
            fmt.unitsStyle = .abbreviated
            parts.append(modified.timeIntervalSinceNow > -60
                         ? String(localized: "edited just now")
                         : String(localized: "edited \(fmt.localizedString(for: modified, relativeTo: Date()))"))
        }
        if let size = info.size {
            parts.append(ByteCountFormatter.string(fromByteCount: Int64(size), countStyle: .file))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private func statusLabel(for entry: GitStatusEntry) -> String {
        if entry.isConflicted { return "!" }
        switch entry.indexStatus ?? entry.workTreeStatus {
        case .added, .untracked: return "A"
        case .modified:    return "M"
        case .deleted:     return "D"
        case .renamed:     return "R"
        case .conflicted:  return "!"
        default:           return "~"
        }
    }

    /// Spoken equivalent of the single-letter status tile.
    private func statusWord(for entry: GitStatusEntry) -> String {
        if entry.isConflicted { return String(localized: "conflicted") }
        switch entry.indexStatus ?? entry.workTreeStatus {
        case .added:       return String(localized: "added")
        case .modified:    return String(localized: "modified")
        case .deleted:     return String(localized: "deleted")
        case .renamed:     return String(localized: "renamed")
        case .untracked:   return String(localized: "untracked")
        default:           return String(localized: "changed")
        }
    }

    /// Same palette as the Vault's changed-file tiles.
    private func statusTint(for entry: GitStatusEntry) -> Color {
        if entry.isConflicted { return Tide.danger }
        switch entry.indexStatus ?? entry.workTreeStatus {
        case .added, .untracked: return Tide.synced
        case .deleted, .conflicted: return Tide.danger
        default: return Tide.behind
        }
    }

    private func iconName(for item: FileItem) -> String {
        if item.isDirectory { return "folder" }
        switch URL(fileURLWithPath: item.name).pathExtension.lowercased() {
        case "png", "jpg", "jpeg", "gif", "webp", "heic", "svg": return "photo"
        case "pdf":                                  return "doc.richtext"
        case "m4a", "mp3", "wav", "aac", "caf":      return "waveform"
        case "mov", "mp4", "m4v":                    return "film"
        case "json", "yml", "yaml", "swift", "js", "ts", "py", "sh", "css", "html":
            return "chevron.left.forwardslash.chevron.right"
        default:                                     return "doc.text"
        }
    }

    private func beginRename(_ item: FileItem) {
        newName = item.name
        renameItem = item
    }

    private func renameConflict(for item: FileItem, name: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != item.name else { return nil }
        let dest = item.url.deletingLastPathComponent().appendingPathComponent(trimmed)
        guard FileManager.default.fileExists(atPath: dest.path) else { return nil }
        return String(localized: "\(trimmed) already exists here. Try another name.")
    }

    // MARK: File Operations

    private func performRename(_ item: FileItem, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        newName = ""
        guard !trimmed.isEmpty, trimmed != item.name, !trimmed.contains("/") else { return }
        let source = item.url
        let dest = source.deletingLastPathComponent().appendingPathComponent(trimmed)
        let repositoryURL = vaultURL
        Task {
            do {
                try await RepositoryOperationCoordinator.shared.withRepository(at: repositoryURL) {
                    guard !FileManager.default.fileExists(atPath: dest.path) else {
                        throw CocoaError(.fileWriteFileExists)
                    }
                    try FileManager.default.moveItem(at: source, to: dest)
                }
            } catch {
                operationError = error.localizedDescription
            }
            loadItems()
            // Refresh git status so the rename appears in VaultView
            state.detectChanges(repoID: repoID)
        }
    }

    private func performDelete(_ item: FileItem) {
        let target = item.url
        let repositoryURL = vaultURL
        Task {
            do {
                try await RepositoryOperationCoordinator.shared.withRepository(at: repositoryURL) {
                    try FileManager.default.removeItem(at: target)
                }
            } catch {
                operationError = error.localizedDescription
            }
            loadItems()
            state.detectChanges(repoID: repoID)
        }
    }

    private func fetch(_ item: FileItem) {
        let path = item.relativePath
        guard !fetchingPaths.contains(path) else { return }
        fetchingPaths.insert(path)
        Task {
            await state.fetchLFSFile(repoID: repoID, path: path)
            fetchingPaths.remove(path)
            loadItems()
        }
    }
}

// MARK: - Share sheet

/// UIKit share sheet ("Open in…").
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

// MARK: - Rename sheet

/// Tide bottom sheet for renaming a file: display headline with a mono
/// location, one white field, an inline "already exists" warning, and a
/// single pill action.
struct TideFileNameSheet: View {
    let title: String
    /// Mono git fact after the headline — a folder path or the current name.
    let location: String
    let placeholder: String
    let confirmLabel: String
    @Binding var text: String
    let conflictMessage: (String) -> String?
    let onConfirm: () -> Void
    let onCancel: () -> Void

    @FocusState private var isFocused: Bool

    private var trimmed: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var conflict: String? { conflictMessage(text) }
    private var canConfirm: Bool { !trimmed.isEmpty && conflict == nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Button(String(localized: "Cancel"), role: .cancel, action: onCancel)
                    .font(TideFont.sans(15, relativeTo: .subheadline))
                    .foregroundStyle(Tide.inkMid)
                    .frame(minHeight: 44)
                    .buttonStyle(.plain)
                Spacer()
            }

            (Text(title + " ")
                .font(TideFont.display(26))
             + Text(location)
                .font(TideFont.mono(22, .semibold, relativeTo: .title)))
                .tracking(-0.6)
                .foregroundStyle(Tide.ink)
                .lineLimit(2)
                .truncationMode(.middle)
                .padding(.top, 8)
                .accessibilityAddTraits(.isHeader)

            TextField(placeholder, text: $text)
                .focused($isFocused)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .submitLabel(.done)
                .onSubmit { if canConfirm { onConfirm() } }
                .font(TideFont.sans(17, .medium, relativeTo: .body))
                .foregroundStyle(Tide.ink)
                .padding(.horizontal, 16)
                .frame(minHeight: 56)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Tide.card))
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(conflict != nil ? Tide.danger : (isFocused ? Tide.ink : Tide.line),
                                      lineWidth: conflict != nil || isFocused ? 1.5 : 1)
                )
                .accessibilityLabel(String(localized: "File name"))
                .padding(.top, 16)

            if let conflict {
                TideInlineWarning(text: conflict).padding(.top, 14)
            }

            Spacer(minLength: 20)

            BPrimaryButton(title: confirmLabel, isDisabled: !canConfirm, action: onConfirm)
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 16)
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(30)
        .presentationBackground(Tide.paper)
        .onAppear { isFocused = true }
    }
}

/// Red rounded warning row ("reading-list.md already exists in ideas/").
struct TideInlineWarning: View {
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "exclamationmark.circle")
                .font(.system(size: 14, weight: .semibold))
                .accessibilityHidden(true)
            Text(text)
                .font(TideFont.sans(13, relativeTo: .footnote))
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(Tide.danger)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tide.danger.opacity(0.1)))
    }
}

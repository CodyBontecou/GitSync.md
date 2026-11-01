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
/// item count. Read once per load, never invented.
private struct FileItemMeta {
    var modified: Date?
    var size: Int?
    var childCount: Int?
}

// MARK: - View

struct FileBrowserView: View {
    @Environment(AppState.self) private var state
    let repoID: UUID
    let relativePath: String

    @State private var items: [FileItem] = []
    @State private var meta: [String: FileItemMeta] = [:]
    @State private var loadErrorMessage: String?
    @State private var renameItem: FileItem? = nil
    @State private var newName: String = ""
    @State private var showRenameAlert = false
    @State private var showCreateFileAlert = false
    @State private var newFileName: String = ""

    private var vaultURL: URL { state.vaultURL(for: repoID) }
    private var currentURL: URL {
        relativePath.isEmpty
            ? vaultURL
            : vaultURL.appendingPathComponent(relativePath)
    }
    private var statusEntries: [GitStatusEntry] {
        state.statusEntriesByRepo[repoID] ?? []
    }
    private var vaultName: String {
        state.repo(id: repoID)?.displayName ?? String(localized: "Files")
    }
    private var navTitle: String {
        relativePath.isEmpty
            ? String(localized: "Files")
            : URL(fileURLWithPath: relativePath).lastPathComponent
    }
    /// "ideas/" — the folder new files land in, as a git path.
    private var folderLabel: String {
        relativePath.isEmpty ? "/" : relativePath + "/"
    }

    var body: some View {
        ZStack {
            Tide.paper.ignoresSafeArea()

            if items.isEmpty {
                emptyState
            } else {
                fileList
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(relativePath.isEmpty ? vaultName : navTitle)
                    .font(TideFont.sans(17, .semibold, relativeTo: .headline))
                    .foregroundStyle(Tide.ink)
                    .lineLimit(1)
                    .accessibilityAddTraits(.isHeader)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    newFileName = ""
                    showCreateFileAlert = true
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Tide.ink)
                        // Icon-only toolbar action: 44x44pt hit target.
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(String(localized: "Create New File"))
            }
        }
        .sheet(isPresented: $showRenameAlert, onDismiss: {
            renameItem = nil
            newName = ""
        }) {
            if let item = renameItem {
                TideFileNameSheet(
                    title: String(localized: "Rename"),
                    location: item.name,
                    placeholder: item.name,
                    confirmLabel: String(localized: "Rename"),
                    text: $newName,
                    conflictMessage: { name in
                        renameConflict(for: item, name: name)
                    },
                    onConfirm: {
                        let target = newName
                        showRenameAlert = false
                        performRename(item, to: target)
                    },
                    onCancel: { showRenameAlert = false }
                )
            }
        }
        .sheet(isPresented: $showCreateFileAlert, onDismiss: { newFileName = "" }) {
            TideFileNameSheet(
                title: String(localized: "New file in"),
                location: folderLabel,
                placeholder: "filename.md",
                confirmLabel: String(localized: "Create"),
                text: $newFileName,
                conflictMessage: createConflict(for:),
                onConfirm: {
                    showCreateFileAlert = false
                    performCreateFile()
                },
                onCancel: { showCreateFileAlert = false }
            )
        }
        .task(id: currentURL.standardizedFileURL.path) {
            loadItems()
        }
    }

    // MARK: - File List

    // SwipeActions only work inside a List — using List here is required.
    // Rows draw their own white card segment (rounded at the ends) so the
    // list reads as one Tide card with hairline separators.
    private var fileList: some View {
        List {
            breadcrumb
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 10, leading: 20, bottom: 10, trailing: 20))
                .listRowSeparator(.hidden)

            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                let isFirst = index == 0
                let isLast = index == items.count - 1
                fileRow(item, showsSeparator: !isLast)
                    .padding(.top, isFirst ? 2 : 0)
                    .padding(.bottom, isLast ? 2 : 0)
                    .listRowBackground(cardSegment(isFirst: isFirst, isLast: isLast))
                    .listRowInsets(EdgeInsets(top: 0, leading: Tide.gutter + 16, bottom: 0, trailing: Tide.gutter + 16))
                    .listRowSeparator(.hidden)
            }

            Text(String(localized: "Swipe or long-press a file to rename it"))
                .font(TideFont.mono(11))
                .foregroundStyle(Tide.inkFaint)
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 10, leading: 20, bottom: 24, trailing: 20))
                .listRowSeparator(.hidden)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .scrollIndicators(.hidden)
        .background(Tide.paper)
    }

    /// "VoxVault / ideas            9 items"
    private var breadcrumb: some View {
        HStack(spacing: 6) {
            Text(vaultName)
                .foregroundStyle(relativePath.isEmpty ? Tide.ink : Tide.inkMid)
                .fontWeight(relativePath.isEmpty ? .semibold : .regular)
                .lineLimit(1)
                .layoutPriority(1)
            if !relativePath.isEmpty {
                Text("/").foregroundStyle(Tide.inkFaint)
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
    }

    private func cardSegment(isFirst: Bool, isLast: Bool) -> some View {
        let r = Tide.radiusCard
        return UnevenRoundedRectangle(
            topLeadingRadius: isFirst ? r : 0,
            bottomLeadingRadius: isLast ? r : 0,
            bottomTrailingRadius: isLast ? r : 0,
            topTrailingRadius: isFirst ? r : 0,
            style: .continuous
        )
        .fill(Tide.card)
        .padding(.horizontal, Tide.gutter)
    }

    // MARK: - File Row

    @ViewBuilder
    private func fileRow(_ item: FileItem, showsSeparator: Bool) -> some View {
        let gitStatus = gitStatusFor(item)

        Group {
            if item.isDirectory {
                NavigationLink(value: FileBrowserDestination(
                    repoID: repoID,
                    relativePath: item.relativePath
                )) {
                    rowContent(item: item, gitStatus: gitStatus)
                }
                .buttonStyle(.plain)
            } else {
                NavigationLink(value: FileEditorDestination(repoID: repoID, fileURL: item.url)) {
                    rowContent(item: item, gitStatus: gitStatus)
                }
                .buttonStyle(.plain)
            }
        }
        .overlay(alignment: .bottom) {
            if showsSeparator {
                Rectangle().fill(Tide.line).frame(height: 1)
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button {
                beginRename(item)
            } label: {
                Label("Rename", systemImage: "pencil")
            }
            .tint(Tide.behind)
        }
        .contextMenu {
            Button {
                beginRename(item)
            } label: {
                Label("Rename", systemImage: "pencil")
            }
        }
    }

    private func rowContent(item: FileItem, gitStatus: GitStatusEntry?) -> some View {
        HStack(spacing: 12) {
            Image(systemName: iconName(for: item))
                .font(.system(size: 17, weight: .regular))
                .foregroundStyle(Tide.inkMid)
                .frame(width: 22)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                    .font(TideFont.body)
                    .foregroundStyle(Tide.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if !item.isDirectory, let detail = metaLine(for: item) {
                    Text(detail)
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 8)

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
                    if let count = meta[item.id]?.childCount {
                        Text("\(count)")
                            .font(TideFont.mono(12))
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

    // MARK: - Empty State

    private var emptyState: some View {
        VStack(spacing: 0) {
            Spacer()
            Image(systemName: loadErrorMessage == nil ? "folder" : "lock")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(loadErrorMessage == nil ? Tide.inkMid : Tide.danger)
                .frame(width: 48, height: 48)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(loadErrorMessage == nil ? Tide.well : Tide.danger.opacity(0.12)))
                .accessibilityHidden(true)
                .padding(.bottom, 16)
            Text(loadErrorMessage == nil ? String(localized: "Empty Directory") : String(localized: "Couldn't Read Folder"))
                .tideDisplay(26)
                .multilineTextAlignment(.center)
            Text(loadErrorMessage ?? String(localized: "No files found"))
                .font(TideFont.meta)
                .foregroundStyle(Tide.inkFaint)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
            Spacer()
        }
        .padding(.horizontal, 32)
    }

    // MARK: - Data Loading

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

    private func loadItems() {
        do {
            items = try Self.listContents(of: currentURL, parentRelativePath: relativePath)
            loadErrorMessage = nil
        } catch {
            // A failed read is not an empty directory — surface it distinctly
            // so navigation bugs and sandbox failures can't masquerade as
            // "No files found" (which is exactly how the /private/var bug hid).
            items = []
            loadErrorMessage = error.localizedDescription
            DebugLogger.shared.error(
                "files",
                "Could not list \(currentURL.path)",
                detail: error.localizedDescription
            )
        }
        meta = Self.loadMeta(for: items)
    }

    private static func loadMeta(for items: [FileItem]) -> [String: FileItemMeta] {
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
                result[item.id] = FileItemMeta(modified: values?.contentModificationDate, size: values?.fileSize)
            }
        }
        return result
    }

    // MARK: - Helpers

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

    /// "edited 9m ago · 3.1 KB"
    private func metaLine(for item: FileItem) -> String? {
        guard let info = meta[item.id] else { return nil }
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
        let kind = entry.indexStatus ?? entry.workTreeStatus
        switch kind {
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
        let kind = entry.indexStatus ?? entry.workTreeStatus
        switch kind {
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
        let kind = entry.indexStatus ?? entry.workTreeStatus
        switch kind {
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
        renameItem = item
        newName = item.name
        showRenameAlert = true
    }

    private func renameConflict(for item: FileItem, name: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != item.name else { return nil }
        let dest = item.url.deletingLastPathComponent().appendingPathComponent(trimmed)
        guard FileManager.default.fileExists(atPath: dest.path) else { return nil }
        return String(localized: "\(trimmed) already exists here. Try another name.")
    }

    private func createConflict(for name: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard FileManager.default.fileExists(atPath: currentURL.appendingPathComponent(trimmed).path) else { return nil }
        return String(localized: "\(trimmed) already exists in \(folderLabel). Try another name.")
    }

    // MARK: - File Operations

    private func performRename(_ item: FileItem, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        renameItem = nil
        newName = ""
        guard !trimmed.isEmpty, trimmed != item.name else { return }
        let dest = item.url.deletingLastPathComponent().appendingPathComponent(trimmed)
        try? FileManager.default.moveItem(at: item.url, to: dest)
        loadItems()
        // Refresh git status so the rename appears in VaultView
        state.detectChanges(repoID: repoID)
    }

    private func performCreateFile() {
        let trimmed = newFileName.trimmingCharacters(in: .whitespacesAndNewlines)
        newFileName = ""
        guard !trimmed.isEmpty else { return }
        let dest = currentURL.appendingPathComponent(trimmed)
        guard !FileManager.default.fileExists(atPath: dest.path) else { return }
        FileManager.default.createFile(atPath: dest.path, contents: nil)
        loadItems()
        state.detectChanges(repoID: repoID)
    }
}

// MARK: - New file / rename sheet

/// Tide bottom sheet for naming a file ("New file in ideas/" / "Rename …"):
/// display headline with a mono location, one white field, an inline
/// "already exists" warning, and a single pill action.
private struct TideFileNameSheet: View {
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

            Text(String(localized: "Include the file extension (e.g. notes.md)"))
                .font(TideFont.meta)
                .foregroundStyle(Tide.inkFaint)
                .padding(.top, 8)
                .padding(.leading, 4)

            if let conflict {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.circle")
                        .font(.system(size: 14, weight: .semibold))
                        .accessibilityHidden(true)
                    Text(conflict)
                        .font(TideFont.sans(13, relativeTo: .footnote))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundStyle(Tide.danger)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Tide.danger.opacity(0.1)))
                .padding(.top, 14)
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

import SwiftUI

// MARK: - Navigation Destination

struct FileBrowserDestination: Hashable {
    let repoID: UUID
    let relativePath: String  // "" = vault root
}

// MARK: - File Item

private struct FileItem: Identifiable {
    let url: URL
    let name: String
    let isDirectory: Bool
    var id: String { url.path }
}

// MARK: - View

struct FileBrowserView: View {
    @Environment(AppState.self) private var state
    let repoID: UUID
    let relativePath: String

    @State private var items: [FileItem] = []
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
    private var navTitle: String {
        relativePath.isEmpty
            ? "Files"
            : URL(fileURLWithPath: relativePath).lastPathComponent
    }

    var body: some View {
        ZStack {
            Color.brutalBg.ignoresSafeArea()

            if items.isEmpty {
                emptyState
            } else {
                fileList
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(navTitle.uppercased())
                    .font(.brutalScaled(size: 12, weight: .black, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .tracking(2)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    newFileName = ""
                    showCreateFileAlert = true
                } label: {
                    Image(systemName: "plus")
                        .font(.brutalScaled(size: 14, weight: .semibold))
                        .foregroundStyle(Color.brutalText)
                }
            }
        }
        .navigationDestination(for: FileBrowserDestination.self) { dest in
            FileBrowserView(repoID: dest.repoID, relativePath: dest.relativePath)
        }
        .navigationDestination(for: FileEditorDestination.self) { dest in
            FileEditorView(repoID: dest.repoID, fileURL: dest.fileURL)
        }
        .alert("Rename", isPresented: $showRenameAlert, presenting: renameItem) { item in
            TextField("New name", text: $newName)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            Button("Rename") { performRename(item, to: newName) }
            Button("Cancel", role: .cancel) {
                renameItem = nil
                newName = ""
            }
        } message: { item in
            Text("Enter a new name for \"\(item.name)\"")
        }
        .alert("New File", isPresented: $showCreateFileAlert) {
            TextField("filename.md", text: $newFileName)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            Button("Create") { performCreateFile() }
            Button("Cancel", role: .cancel) { newFileName = "" }
        } message: {
            Text("Enter a name for the new file in \"\(navTitle)\"")
        }
        .onAppear { loadItems() }
    }

    // MARK: - File List

    // SwipeActions only work inside a List — using List here is required.
    private var fileList: some View {
        List {
            if !relativePath.isEmpty {
                HStack(spacing: 6) {
                    Image(systemName: "folder")
                        .font(.brutalScaled(size: 11, weight: .semibold))
                        .foregroundStyle(Color.brutalTextFaint)
                    Text(relativePath)
                        .font(.brutalScaled(size: 12, weight: .medium, design: .monospaced))
                        .foregroundStyle(Color.brutalTextFaint)
                        .lineLimit(1)
                        .truncationMode(.head)
                }
                .padding(.vertical, 8)
                .listRowBackground(Color.brutalSurface)
                .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                .listRowSeparatorTint(Color.brutalBorder)
            }

            ForEach(items) { item in
                fileRow(item)
                    .listRowBackground(Color.brutalBg)
                    .listRowInsets(EdgeInsets())
                    .listRowSeparatorTint(Color.brutalBorder)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .scrollIndicators(.hidden)
        .background(Color.brutalBg)
    }

    // MARK: - File Row

    @ViewBuilder
    private func fileRow(_ item: FileItem) -> some View {
        let gitStatus = gitStatusFor(item)

        Group {
            if item.isDirectory {
                NavigationLink(value: FileBrowserDestination(
                    repoID: repoID,
                    relativePath: relativePathFor(item)
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
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button {
                renameItem = item
                newName = item.name
                showRenameAlert = true
            } label: {
                Label("Rename", systemImage: "pencil")
            }
            .tint(Color.brutalAccent)
        }
    }

    private func rowContent(item: FileItem, gitStatus: GitStatusEntry?) -> some View {
        HStack(spacing: 12) {
            Text(item.isDirectory ? "📁" : fileEmoji(for: item.name))
                .font(.brutalScaled(size: 17))
                .frame(width: 26)

            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                    .font(.brutalScaled(size: 14, weight: .medium, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            Spacer(minLength: 8)

            if let status = gitStatus {
                BBadge(
                    text: statusLabel(for: status).uppercased(),
                    style: statusBadgeStyle(for: status)
                )
            }

            if item.isDirectory {
                Image(systemName: "chevron.right")
                    .font(.brutalScaled(size: 11, weight: .semibold))
                    .foregroundStyle(Color.brutalTextFaint)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
        .contentShape(Rectangle())
    }

    // MARK: - Empty State

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Text("📂")
                .font(.brutalScaled(size: 44))
            Text("Empty Directory")
                .font(.brutalScaled(size: 14, weight: .bold, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .tracking(1)
            Text("No files found")
                .font(.brutalScaled(size: 12, design: .monospaced))
                .foregroundStyle(Color.brutalTextFaint)
            Spacer()
        }
    }

    // MARK: - Data Loading

    private func loadItems() {
        guard let contents = try? FileManager.default.contentsOfDirectory(
            at: currentURL,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: .skipsHiddenFiles
        ) else {
            items = []
            return
        }

        items = contents.compactMap { url -> FileItem? in
            guard url.lastPathComponent != ".git" else { return nil }
            let isDir = (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false
            return FileItem(url: url, name: url.lastPathComponent, isDirectory: isDir)
        }
        .sorted {
            if $0.isDirectory != $1.isDirectory { return $0.isDirectory }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    // MARK: - Helpers

    private func relativePathFor(_ item: FileItem) -> String {
        let vaultPath = vaultURL.path
        let itemPath = item.url.path
        guard itemPath.hasPrefix(vaultPath) else { return item.name }
        let rel = String(itemPath.dropFirst(vaultPath.count))
        return rel.hasPrefix("/") ? String(rel.dropFirst()) : rel
    }

    private func gitStatusFor(_ item: FileItem) -> GitStatusEntry? {
        // Normalise to NFC before comparing: git stores paths as NFC while
        // APFS/HFS+ gives back NFD from FileManager, so a straight == fails
        // for Korean, Japanese, and other non-ASCII filenames.
        let rel = relativePathFor(item).precomposedStringWithCanonicalMapping
        if item.isDirectory {
            return statusEntries.first { $0.path.hasPrefix(rel + "/") }
        }
        return statusEntries.first { $0.path == rel }
    }

    private func statusLabel(for entry: GitStatusEntry) -> String {
        let kind = entry.indexStatus ?? entry.workTreeStatus
        switch kind {
        case .added:       return "A"
        case .modified:    return "M"
        case .deleted:     return "D"
        case .renamed:     return "R"
        case .untracked:   return "?"
        case .conflicted:  return "!"
        default:           return "~"
        }
    }

    private func statusBadgeStyle(for entry: GitStatusEntry) -> BBadge.BBadgeStyle {
        if entry.isConflicted { return .error }
        let kind = entry.indexStatus ?? entry.workTreeStatus
        switch kind {
        case .added:       return .success
        case .modified:    return .warning
        case .deleted:     return .error
        case .renamed:     return .accent
        case .untracked:   return .default
        default:           return .default
        }
    }

    private func fileEmoji(for name: String) -> String {
        switch URL(fileURLWithPath: name).pathExtension.lowercased() {
        case "md", "markdown": return "📝"
        case "json":           return "📋"
        case "txt":            return "📄"
        case "png", "jpg", "jpeg", "gif", "webp", "heic": return "🖼️"
        case "pdf":            return "📕"
        case "swift":          return "🔧"
        case "yml", "yaml":    return "⚙️"
        case "gitignore":      return "🚫"
        default:               return "📄"
        }
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

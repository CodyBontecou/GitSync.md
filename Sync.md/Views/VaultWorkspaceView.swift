import SwiftUI

/// Board "iPad": the wide detail column becomes a workspace — the vault's
/// state hero over a FILES tree, and the picked file (diff or preview) beside
/// it. With no file picked the right side is the regular vault screen.
struct VaultWorkspaceView: View {
    @Environment(AppState.self) private var state
    let repoID: UUID

    @State private var selectedPath: String?
    @State private var expanded: Set<String> = []
    @State private var children: [String: [FileItem]] = [:]
    @State private var lfs = GitLFSAttributes(text: "")
    @State private var didAutoExpand = false

    static let filesColumnWidth: CGFloat = 300

    private var repo: RepoConfig? { state.repo(id: repoID) }
    private var entries: [GitStatusEntry] { state.statusEntriesByRepo[repoID] ?? [] }
    private var changeCount: Int { state.changeCounts[repoID] ?? 0 }
    private var vaultURL: URL { state.vaultURL(for: repoID) }

    var body: some View {
        HStack(spacing: 0) {
            filesColumn
                .frame(width: Self.filesColumnWidth)
            Rectangle().fill(Tide.line).frame(width: 1).ignoresSafeArea()
            detail
                .frame(maxWidth: .infinity)
        }
        .background(Tide.paper.ignoresSafeArea())
        .task(id: repo?.gitState.commitSHA) { reloadTree() }
        .onChange(of: entries) {
            reloadTree()
            if let selectedPath, !FileManager.default.fileExists(atPath: vaultURL.appendingPathComponent(selectedPath).path),
               !entries.contains(where: { $0.path == selectedPath }) {
                self.selectedPath = nil
            }
        }
    }

    // MARK: - Detail

    @ViewBuilder
    private var detail: some View {
        if let selectedPath {
            WorkspaceFileDetail(repoID: repoID, path: selectedPath) { self.selectedPath = nil }
                .id(selectedPath)
        } else {
            VaultView(repoID: repoID)
        }
    }

    // MARK: - Files column

    private var filesColumn: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if let repo {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(repo.ownerName.map { "\(repo.gitState.branch) · \($0)/\(repo.repoName)" } ?? repo.gitState.branch)
                            .font(TideFont.mono(11.5))
                            .foregroundStyle(Tide.inkFaint)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Text(repo.displayName)
                            .tideDisplay(28)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                    }
                    .padding(.horizontal, 8)
                    .padding(.top, 12)
                }

                stateCard
                    .padding(.top, 14)

                TideSectionLabel(title: String(localized: "Files"))
                    .padding(.horizontal, 8)
                    .padding(.top, 18)
                    .padding(.bottom, 6)

                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(visibleRows, id: \.item.id) { row in
                        treeRow(row.item, depth: row.depth)
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
    }

    /// "3 changes to push" with the rail — the white version of the hero.
    private var stateCard: some View {
        let syncState = tideState
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                if changeCount > 0 {
                    Text("\(changeCount)")
                        .font(TideFont.sans(32, .semibold, relativeTo: .largeTitle))
                        .tracking(-1.6)
                        .foregroundStyle(Tide.ahead)
                    Text(changeCount == 1 ? String(localized: "change to push") : String(localized: "changes to push"))
                        .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                        .foregroundStyle(Tide.ink)
                } else {
                    Text(syncState.headline)
                        .font(TideFont.sans(22, .semibold, relativeTo: .title2))
                        .foregroundStyle(syncState.tint)
                }
            }
            SyncRail(state: syncState)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Tide.card))
        .accessibilityElement(children: .combine)
    }

    private var tideState: TideSyncState {
        let meta = state.vaultMetaByRepo[repoID]
        let ahead = meta?.ahead ?? 0
        let behind = meta?.behind ?? 0
        let local = ahead > 0 ? ahead : changeCount
        switch state.syncStateByRepo[repoID] ?? .unknown {
        case .diverged: return .diverged(local: max(local, 1), remote: behind)
        case .behind: return local > 0 ? .diverged(local: local, remote: behind) : .behind(behind)
        case .ahead: return .ahead(max(local, 1))
        case .upToDate, .unknown: return changeCount > 0 ? .ahead(changeCount) : .inSync
        }
    }

    // MARK: - Tree

    private struct TreeRow {
        let item: FileItem
        let depth: Int
    }

    /// Expanded folders flattened into rows, depth-first.
    private var visibleRows: [TreeRow] {
        var rows: [TreeRow] = []
        func append(_ folder: String, depth: Int) {
            for item in children[folder] ?? [] {
                rows.append(TreeRow(item: item, depth: depth))
                if item.isDirectory, expanded.contains(item.relativePath) {
                    append(item.relativePath, depth: depth + 1)
                }
            }
        }
        append("", depth: 0)
        return rows
    }

    private func treeRow(_ item: FileItem, depth: Int) -> some View {
        let rel = item.relativePath.precomposedStringWithCanonicalMapping
        let selected = !item.isDirectory && selectedPath == rel
        let isOpen = expanded.contains(item.relativePath)
        let entry = item.isDirectory ? nil : entries.first { $0.path == rel }
        let folderHasChanges = item.isDirectory && entries.contains { $0.path.hasPrefix(rel + "/") }
        let isLFS = item.isDirectory
            ? lfs.isLFSTracked(path: rel + "/.lfs-probe")
            : lfs.isLFSTracked(path: rel)

        return Button {
            if item.isDirectory {
                toggle(item)
            } else {
                selectedPath = rel
            }
        } label: {
            HStack(spacing: 8) {
                Text(item.isDirectory ? (isOpen ? "▾ " : "▸ ") + item.name : item.name)
                    .font(TideFont.sans(14, selected ? .semibold : .regular, relativeTo: .subheadline))
                    .foregroundStyle(item.isDirectory ? Tide.inkMid : Tide.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer(minLength: 6)
                if isLFS {
                    Text("LFS")
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkMid)
                }
                if let entry {
                    Text(Self.letter(entry))
                        .font(TideFont.mono(11, .semibold))
                        .foregroundStyle(Self.tint(entry))
                } else if folderHasChanges {
                    Circle().fill(Tide.behind).frame(width: 6, height: 6)
                        .accessibilityHidden(true)
                }
            }
            .padding(.vertical, 9)
            .padding(.leading, 10 + CGFloat(depth) * 18)
            .padding(.trailing, 10)
            .frame(minHeight: 40)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(selected ? Tide.well : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel(item, entry: entry, isOpen: isOpen, isLFS: isLFS))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func accessibilityLabel(_ item: FileItem, entry: GitStatusEntry?, isOpen: Bool, isLFS: Bool) -> String {
        var parts = [item.name]
        if item.isDirectory {
            parts.append(isOpen ? String(localized: "folder, expanded") : String(localized: "folder, collapsed"))
        }
        if let entry { parts.append(Self.statusWord(entry)) }
        if isLFS { parts.append(String(localized: "Git LFS")) }
        return parts.joined(separator: ", ")
    }

    private func toggle(_ item: FileItem) {
        if expanded.contains(item.relativePath) {
            expanded.remove(item.relativePath)
        } else {
            expanded.insert(item.relativePath)
            load(folder: item.relativePath)
        }
    }

    private func load(folder: String) {
        let url = folder.isEmpty ? vaultURL : vaultURL.appendingPathComponent(folder)
        children[folder] = (try? FileBrowserView.listContents(of: url, parentRelativePath: folder)) ?? []
    }

    /// Re-lists the root and every open folder; on first load, opens the
    /// folders that hold changes (the board shows them expanded).
    private func reloadTree() {
        lfs = GitLFSAttributes.load(from: vaultURL)
        if !didAutoExpand {
            didAutoExpand = true
            for entry in entries {
                var components = entry.path.split(separator: "/").map(String.init).dropLast()
                while !components.isEmpty {
                    expanded.insert(components.joined(separator: "/"))
                    components = components.dropLast()
                }
            }
        }
        load(folder: "")
        for folder in expanded { load(folder: folder) }
    }

    // MARK: - Status styling

    static func kind(_ entry: GitStatusEntry) -> GitFileStatusKind? {
        entry.workTreeStatus ?? entry.indexStatus
    }

    static func letter(_ entry: GitStatusEntry) -> String {
        if entry.isConflicted { return "!" }
        switch kind(entry) {
        case .added, .untracked: return "A"
        case .modified: return "M"
        case .deleted: return "D"
        case .renamed: return "R"
        case .typeChanged: return "T"
        case .conflicted: return "!"
        case nil: return "·"
        }
    }

    static func tint(_ entry: GitStatusEntry) -> Color {
        if entry.isConflicted { return Tide.danger }
        switch kind(entry) {
        case .added, .untracked: return Tide.synced
        case .deleted, .conflicted: return Tide.danger
        default: return Tide.behind
        }
    }

    static func statusWord(_ entry: GitStatusEntry) -> String {
        if entry.isConflicted { return String(localized: "conflicted") }
        switch kind(entry) {
        case .added, .untracked: return String(localized: "new file")
        case .modified: return String(localized: "modified")
        case .deleted: return String(localized: "deleted")
        case .renamed: return String(localized: "renamed")
        case .typeChanged: return String(localized: "type changed")
        case .conflicted: return String(localized: "conflicted")
        case nil: return String(localized: "unchanged")
        }
    }
}

// MARK: - File detail

/// The workspace's right pane for one file: "<file> · <dir> · new file · +42",
/// Diff / Preview, Edit and "Commit & push ↑N".
private struct WorkspaceFileDetail: View {
    @Environment(AppState.self) private var state
    let repoID: UUID
    let path: String
    let onClose: () -> Void

    private enum Mode: Hashable { case diff, preview }

    @State private var mode: Mode = .preview
    @State private var didPickMode = false
    @State private var fileText: String?
    @State private var isLoading = true
    @State private var showCommitSheet = false

    private var entry: GitStatusEntry? {
        state.statusEntriesByRepo[repoID]?.first { $0.path == path }
    }
    private var changeCount: Int { state.changeCounts[repoID] ?? 0 }
    private var fileURL: URL { state.vaultURL(for: repoID).appendingPathComponent(path) }
    private var fileExists: Bool { FileManager.default.fileExists(atPath: fileURL.path) }
    private var filename: String { URL(fileURLWithPath: path).lastPathComponent }
    private var directory: String {
        let dir = URL(fileURLWithPath: path).deletingLastPathComponent().relativePath
        return dir == "." ? "" : dir
    }
    private var isMarkdown: Bool {
        ["md", "markdown", "mdown"].contains(URL(fileURLWithPath: path).pathExtension.lowercased())
    }

    private var patch: String {
        guard let result = state.diffByRepo[repoID] else { return "" }
        if let file = result.files.first(where: { $0.path == path || $0.newPath == path || $0.oldPath == path }) {
            return file.patch.isEmpty ? result.rawPatch : file.patch
        }
        return ""
    }

    /// "daily · new file · +42"
    private var metaLine: String {
        var parts: [String] = []
        if !directory.isEmpty { parts.append(directory) }
        if let entry {
            parts.append(VaultWorkspaceView.statusWord(entry))
            if let stats = state.lineStats(repoID: repoID, path: path) {
                var counts: [String] = []
                if stats.added > 0 { counts.append("+\(stats.added)") }
                if stats.removed > 0 { counts.append("−\(stats.removed)") }
                if !counts.isEmpty { parts.append(counts.joined(separator: " ")) }
            }
        } else {
            parts.append(String(localized: "no changes"))
        }
        return parts.joined(separator: " · ")
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            Rectangle().fill(Tide.line).frame(height: 1)
            ScrollView {
                Group {
                    if isLoading {
                        BLoading(text: String(localized: "Loading"))
                            .frame(maxWidth: .infinity)
                            .padding(.top, 40)
                    } else if mode == .diff {
                        if patch.isEmpty {
                            note(String(localized: "No diff for this file. It may be binary."))
                        } else {
                            DiffLinesView(patch: patch)
                        }
                    } else {
                        preview
                    }
                }
                .padding(.horizontal, 28)
                .padding(.vertical, 20)
            }
            .scrollIndicators(.hidden)
        }
        .background(Tide.card.ignoresSafeArea())
        .sheet(isPresented: $showCommitSheet) { CommitSheetView(repoID: repoID) }
        .task(id: entry) { await load() }
    }

    /// One row when it fits (iPad landscape); the controls drop to a second
    /// row on narrower panes (iPhone Duo open, iPad portrait).
    private var header: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                headerClose
                headerTitle
                headerControls
            }
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 12) {
                    headerClose
                    headerTitle
                }
                HStack(spacing: 10) {
                    headerControls
                }
                .padding(.leading, 56)
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, 24)
        .padding(.top, 12)
        .padding(.bottom, 12)
    }

    private var headerClose: some View {
        Button(action: onClose) {
            Image(systemName: "xmark")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Tide.inkMid)
                .frame(width: 36, height: 36)
                .background(Circle().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "Close file"))
    }

    private var headerTitle: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(filename)
                .font(TideFont.sans(15, .semibold, relativeTo: .headline))
                .foregroundStyle(Tide.ink)
                .lineLimit(1)
                .truncationMode(.middle)
            Text(metaLine)
                .font(TideFont.mono(11))
                .foregroundStyle(Tide.inkFaint)
                .lineLimit(1)
                .truncationMode(.head)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var headerControls: some View {
        modePicker

        if fileExists {
            NavigationLink(value: FileEditorDestination(repoID: repoID, fileURL: fileURL)) {
                Text(String(localized: "Edit"))
                    .font(TideFont.sans(13, .medium, relativeTo: .subheadline))
                    .foregroundStyle(Tide.ink)
                    .padding(.horizontal, 14)
                    .frame(height: 38)
                    .overlay(Capsule().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                    .frame(minHeight: 44)
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
        }

        Button {
            showCommitSheet = true
        } label: {
            HStack(spacing: 8) {
                Text(String(localized: "Commit & push"))
                    .font(TideFont.sans(14, .semibold, relativeTo: .subheadline))
                if changeCount > 0 {
                    Text("↑\(changeCount)")
                        .font(TideFont.mono(12, .medium))
                        .foregroundStyle(Tide.paper.opacity(0.72))
                }
            }
            .foregroundStyle(changeCount == 0 ? Tide.inkMid : Tide.paper)
            .padding(.horizontal, 16)
            .frame(height: 38)
            .background(Capsule().fill(changeCount == 0 ? Color.brutalBorderSoft : Tide.ink))
            .frame(minHeight: 44)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(changeCount == 0 || state.isSyncing)
        .accessibilityIdentifier("workspace.commitPush")
    }

    private var modePicker: some View {
        HStack(spacing: 4) {
            modeButton(.diff, title: String(localized: "Diff"), enabled: entry != nil)
            modeButton(.preview, title: String(localized: "Preview"), enabled: fileExists)
        }
        .padding(3)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Tide.well))
    }

    private func modeButton(_ option: Mode, title: String, enabled: Bool) -> some View {
        let selected = mode == option
        return Button {
            mode = option
            didPickMode = true
        } label: {
            Text(title)
                .font(TideFont.sans(12.5, selected ? .semibold : .medium, relativeTo: .footnote))
                .fixedSize()
                .foregroundStyle(selected ? Tide.ink : (enabled ? Tide.inkMid : Tide.inkFaint))
                .padding(.horizontal, 12)
                .frame(height: 30)
                .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(selected ? Tide.card : .clear))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    @ViewBuilder
    private var preview: some View {
        if let fileText {
            if isMarkdown {
                TideMarkdownView(text: fileText)
                    .frame(maxWidth: 680, alignment: .leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                Text(fileText)
                    .font(TideFont.mono(12.5, relativeTo: .footnote))
                    .foregroundStyle(Tide.ink)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        } else {
            note(fileExists
                 ? String(localized: "This file isn't text, so it can't be shown here.")
                 : String(localized: "This file was deleted on this iPhone."))
        }
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(TideFont.sans(14, relativeTo: .subheadline))
            .foregroundStyle(Tide.inkMid)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func load() async {
        if !didPickMode {
            // Changed files open on their diff; the rest on the file itself.
            mode = entry != nil ? .diff : .preview
        }
        if entry != nil {
            await state.loadUnifiedDiff(repoID: repoID, path: path)
        }
        let url = fileURL
        fileText = await Task.detached(priority: .userInitiated) {
            guard let data = try? Data(contentsOf: url), data.count <= 2_000_000 else { return nil }
            return String(data: data, encoding: .utf8)
        }.value
        isLoading = false
    }
}

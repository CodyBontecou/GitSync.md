import SwiftUI

// MARK: - Navigation Destination

struct FileEditorDestination: Hashable {
    let repoID: UUID
    let fileURL: URL
}

// MARK: - View

struct FileEditorView: View {
    @Environment(AppState.self) private var state
    let repoID: UUID
    let fileURL: URL

    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @State private var liveURL: URL
    @State private var content: String = ""
    @State private var originalContent: String = ""
    @State private var loadedBytes = Data()
    @State private var isBinary = false
    @State private var isSaving = false
    @State private var operationError: String?
    @State private var showDeleteConfirm = false
    @State private var showRenameModal = false
    @State private var renameText: String = ""
    @State private var renameError: String?
    @State private var isRenaming = false
    @State private var showSaveToast = false
    @State private var showCommitSheet = false
    @State private var showPreview = false
    @State private var autosaveTask: Task<Void, Never>?
    /// Lines that differ from HEAD, and which ones (for the gutter bar).
    @State private var linesChanged: Int?
    @State private var changedLines: Set<Int> = []
    /// Width this screen gets; at regular width with room for two panes
    /// (iPhone Duo open, iPad) the commit panel sits beside the editor.
    @State private var screenWidth: CGFloat = 0

    private static let autosaveDelay: Duration = .seconds(2)

    init(repoID: UUID, fileURL: URL) {
        self.repoID = repoID
        self.fileURL = fileURL
        self._liveURL = State(initialValue: fileURL)
    }

    private var fileName: String { liveURL.lastPathComponent }
    private var isDirty: Bool { content != originalContent }
    private var language: SyntaxLanguage { SyntaxLanguage.detect(fileExtension: liveURL.pathExtension) }

    private var renameIssue: String? {
        guard let repository = state.vaultURL(for: repoID) else {
            return AppState.VaultAccessError.unavailableFolder.localizedDescription
        }
        do {
            _ = try RepositoryFileRename.destination(
                for: liveURL, name: renameText, in: repository
            )
            return nil
        } catch { return error.localizedDescription }
    }

    /// Path of the open file relative to the vault root, symlinks resolved on
    /// both sides (the vault URL comes back through /var -> /private/var).
    private var relativePath: String? {
        guard let repository = state.vaultURL(for: repoID) else { return nil }
        let base = repository.resolvingSymlinksInPath().standardizedFileURL.pathComponents
        let file = liveURL.resolvingSymlinksInPath().standardizedFileURL.pathComponents
        guard file.count > base.count, Array(file.prefix(base.count)) == base else { return nil }
        return file.dropFirst(base.count).joined(separator: "/")
    }

    /// "VoxVault / daily" — where this file lives.
    private var locationLine: String {
        let vault = state.repo(id: repoID)?.displayName ?? ""
        guard let rel = relativePath else { return vault }
        let folder = (rel as NSString).deletingLastPathComponent
        return folder.isEmpty ? vault : "\(vault) / \(folder)"
    }

    /// "VoxVault / daily · saved" — beside the commit panel (board "DuoEditor")
    /// the floating saved pill is hidden, so the save state rides the subtitle.
    private var editorSubtitle: String {
        guard showsCommitPanel, !isBinary else { return locationLine }
        let status = isSaving
            ? String(localized: "saving…")
            : (isDirty ? String(localized: "editing") : String(localized: "saved"))
        return locationLine.isEmpty ? status : "\(locationLine) · \(status)"
    }

    /// True when git sees this file as changed since the last commit.
    private var hasUncommittedChanges: Bool {
        guard let rel = relativePath?.precomposedStringWithCanonicalMapping else { return false }
        return (state.statusEntriesByRepo[repoID] ?? []).contains { $0.path == rel }
    }

    private var showsSyncPill: Bool {
        !showsCommitPanel && !isBinary && !isDirty && (showSaveToast || hasUncommittedChanges)
    }

    private var showsCommitPanel: Bool {
        horizontalSizeClass == .regular && screenWidth >= EditorLayout.commitPanelMinWidth
    }

    private enum EditorLayout {
        static let commitPanelMinWidth: CGFloat = 700
    }

    var body: some View {
        HStack(spacing: 0) {
            editorPane
            if showsCommitPanel {
                Rectangle().fill(Tide.line).frame(width: 1).ignoresSafeArea()
                CommitSheetView(repoID: repoID, embedded: true)
                    .frame(width: min(440, screenWidth * 0.46))
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { screenWidth = $0 }
        .animation(.easeInOut(duration: 0.2), value: showsCommitPanel)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { editorToolbar }
        .sheet(isPresented: $showCommitSheet) { CommitSheetView(repoID: repoID) }
        .onAppear { loadContent() }
        .task { await refreshChangeSummary() }
        .onChange(of: content) { scheduleAutosave() }
        .onChange(of: renameText) { renameError = nil }
        .onChange(of: state.statusEntriesByRepo[repoID] ?? []) {
            Task { await refreshChangeSummary() }
        }
        .onDisappear {
            autosaveTask?.cancel()
            if isDirty { performSave(dismissKeyboard: false) }
        }
        .alert(
            String(localized: "Unable to Update File"),
            isPresented: Binding(
                get: { operationError != nil },
                set: { if !$0 { operationError = nil } }
            )
        ) {
            Button(String(localized: "OK"), role: .cancel) { operationError = nil }
        } message: {
            Text(operationError ?? "")
        }
    }

    private var editorPane: some View {
        ZStack {
            Tide.card.ignoresSafeArea()

            if isBinary {
                binaryState
            } else {
                if showPreview && language == .markdown {
                    ScrollView {
                        TideMarkdownView(text: content)
                            .padding(.horizontal, 20)
                            .padding(.top, 14)
                            .padding(.bottom, 96)
                    }
                    .scrollIndicators(.hidden)
                } else {
                    CodeEditorView(text: $content, language: language, changedLines: changedLines)
                        .padding(.horizontal, 4)
                }
            }

            if showDeleteConfirm {
                BConfirmModal(
                    title: String(localized: "Delete \"\(fileName)\"?"),
                    message: String(localized: "This will be reflected in git status as a deletion."),
                    confirmLabel: String(localized: "Delete"),
                    isDestructive: true,
                    onConfirm: performDelete,
                    onCancel: { showDeleteConfirm = false }
                )
                .transition(.opacity.combined(with: .scale(scale: 0.97)))
            }

            if showRenameModal {
                BRenameModal(
                    title: String(localized: "Rename File"),
                    text: $renameText,
                    validationMessage: renameIssue ?? renameError,
                    isDisabled: renameIssue != nil || isSaving || isRenaming
                        || renameText.trimmingCharacters(in: .whitespacesAndNewlines) == fileName,
                    isLoading: isRenaming,
                    onConfirm: performRename,
                    onCancel: { showRenameModal = false; renameText = "" }
                )
                .transition(.opacity.combined(with: .scale(scale: 0.97)))
            }
        }
        .overlay(alignment: .bottom) {
            Group {
                if showsSyncPill && !showDeleteConfirm && !showRenameModal {
                    syncPill
                        .padding(.horizontal, Tide.gutter)
                        .padding(.bottom, 14)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.spring(duration: 0.4, bounce: 0.15), value: showsSyncPill)
        }
        .animation(.easeOut(duration: 0.15), value: showDeleteConfirm)
        .animation(.easeOut(duration: 0.15), value: showRenameModal)
    }

    @ToolbarContentBuilder
    private var editorToolbar: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            VStack(spacing: 1) {
                Text(fileName)
                    .font(TideFont.sans(15, .semibold, relativeTo: .headline))
                    .foregroundStyle(Tide.ink)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if !editorSubtitle.isEmpty {
                    Text(editorSubtitle)
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .lineLimit(1)
                        .truncationMode(.head)
                }
            }
            .accessibilityElement(children: .combine)
        }
        ToolbarItem(placement: .topBarTrailing) {
            HStack(spacing: 4) {
                if !isBinary && language == .markdown {
                    Button {
                        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                        showPreview.toggle()
                    } label: {
                        Text(showPreview ? String(localized: "Edit") : String(localized: "Preview"))
                            .font(TideFont.sans(13, .medium, relativeTo: .subheadline))
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .foregroundStyle(Tide.ink)
                            .padding(.horizontal, 14)
                            .frame(height: 34)
                            .overlay(Capsule().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(showPreview ? String(localized: "Edit") : String(localized: "Preview"))
                }
                if !isBinary {
                    Button {
                        performSave()
                    } label: {
                        Text("Save")
                            .font(TideFont.sans(13, .semibold, relativeTo: .subheadline))
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .foregroundStyle(isDirty ? Tide.paper : Tide.inkFaint)
                            .padding(.horizontal, 14)
                            .frame(height: 34)
                            .background(Capsule().fill(isDirty ? Tide.ink : Color.clear))
                            .overlay(
                                Capsule().strokeBorder(isDirty ? Color.clear : Color.brutalBorderSoft, lineWidth: 1)
                            )
                            // 44x44pt hit target, pill stays compact.
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!isDirty || isSaving)
                }
                Menu {
                    Button {
                        renameText = fileName
                        renameError = nil
                        showRenameModal = true
                    } label: {
                        Label(String(localized: "Rename File"), systemImage: "pencil")
                    }
                    Button(role: .destructive) {
                        showDeleteConfirm = true
                    } label: {
                        Label(String(localized: "Delete File"), systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Tide.ink)
                        // Icon-only toolbar action: 44x44pt hit target.
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                        .accessibilityHidden(true)
                }
                .accessibilityLabel(String(localized: "More file options"))
            }
            .fixedSize(horizontal: true, vertical: false)
        }
    }

    // MARK: - Saved pill

    /// Dark floating pill: saved state, plus a Commit shortcut while this
    /// file has changes git hasn't committed yet.
    private var syncPill: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(hasUncommittedChanges ? Tide.heroAhead : Tide.heroSynced)
                .frame(width: 8, height: 8)
                .accessibilityHidden(true)
            Text(savedPillText)
                .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                .foregroundStyle(Tide.heroText)
                .lineLimit(1)
            if hasUncommittedChanges {
                Button {
                    showCommitSheet = true
                } label: {
                    Text(String(localized: "Commit"))
                        .font(TideFont.sans(14, .semibold, relativeTo: .subheadline))
                        .foregroundStyle(Tide.heroBg)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 38)
                        .background(Capsule().fill(Tide.heroText))
                        .frame(minHeight: 44)
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, hasUncommittedChanges ? 4 : 16)
        .frame(minHeight: 50)
        .background(Capsule().fill(Tide.heroBg))
        .shadow(color: .black.opacity(0.22), radius: 15, y: 10)
        .accessibilityElement(children: .contain)
    }

    // MARK: - Binary Fallback

    private var binaryState: some View {
        VStack(spacing: 0) {
            Spacer()
            Image(systemName: "lock")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(Tide.inkMid)
                .frame(width: 48, height: 48)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Tide.well))
                .accessibilityHidden(true)
                .padding(.bottom, 16)
            Text("Binary File")
                .tideDisplay(26)
            Text("This file cannot be edited as text")
                .font(TideFont.sans(15, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
            Spacer()
        }
        .padding(.horizontal, 32)
    }

    // MARK: - Operations

    private func loadContent() {
        guard state.vaultURLForOperation(for: repoID) != nil else { return }
        guard let data = try? Data(contentsOf: liveURL) else {
            isBinary = true
            return
        }
        loadedBytes = data
        guard let text = String(data: data, encoding: .utf8) else {
            isBinary = true
            return
        }
        content = text
        originalContent = text
    }

    /// "Saved · 3 lines changed" — the change is measured against HEAD.
    private var savedPillText: String {
        guard hasUncommittedChanges else { return String(localized: "Saved") }
        guard let linesChanged, linesChanged > 0 else { return String(localized: "Saved · not committed") }
        return linesChanged == 1
            ? String(localized: "Saved · 1 line changed")
            : String(localized: "Saved · \(linesChanged) lines changed")
    }

    /// Debounced autosave: writes once typing pauses.
    private func scheduleAutosave() {
        autosaveTask?.cancel()
        guard isDirty, !isBinary, !isRenaming else { return }
        autosaveTask = Task {
            try? await Task.sleep(for: Self.autosaveDelay)
            guard !Task.isCancelled, isDirty else { return }
            performSave(dismissKeyboard: false)
        }
    }

    private func refreshChangeSummary() async {
        guard !isBinary, let rel = relativePath else { return }
        let summary = await state.changeSummary(repoID: repoID, path: rel)
        linesChanged = summary.count
        changedLines = summary.lines
    }

    private func performSave(dismissKeyboard: Bool = true) {
        if dismissKeyboard {
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        }
        autosaveTask?.cancel()
        guard !isSaving, !isRenaming, let replacement = content.data(using: .utf8) else { return }
        let expected = loadedBytes
        let savedContent = content
        let destination = liveURL
        guard let repositoryURL = state.vaultURLForOperation(for: repoID) else { return }
        isSaving = true

        Task {
            do {
                try await RepositoryOperationCoordinator.shared.withRepository(at: repositoryURL) {
                    try CoordinatedFileMutation.replace(
                        itemAt: destination,
                        expected: .bytes(expected),
                        with: replacement,
                        temporaryPrefix: ".syncmd-editor-"
                    )
                }
                // `content` may have changed while the lease was queued. Advance
                // the saved baseline only to the snapshot actually written so
                // those newer edits remain dirty and visible.
                loadedBytes = replacement
                originalContent = savedContent
                state.detectChanges(repoID: repoID)
                isSaving = false
                await refreshChangeSummary()
                // Edits typed while the write was queued still need saving.
                scheduleAutosave()
                showSaveToast = true
                try? await Task.sleep(for: .seconds(1.8))
                showSaveToast = false
            } catch {
                operationError = error.localizedDescription
                isSaving = false
            }
        }
    }

    private func performDelete() {
        showDeleteConfirm = false
        let expected = loadedBytes
        let destination = liveURL
        guard let repositoryURL = state.vaultURLForOperation(for: repoID) else { return }
        Task {
            do {
                try await RepositoryOperationCoordinator.shared.withRepository(at: repositoryURL) {
                    try CoordinatedFileMutation.remove(itemAt: destination, expected: .bytes(expected))
                }
                state.detectChanges(repoID: repoID)
                dismiss()
            } catch {
                operationError = error.localizedDescription
            }
        }
    }

    private func performRename() {
        let trimmed = renameText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !isSaving, !isRenaming, trimmed != fileName, renameIssue == nil,
              let repository = state.vaultURLForOperation(for: repoID) else { return }
        autosaveTask?.cancel()
        isRenaming = true
        let source = liveURL
        let expected = loadedBytes
        Task {
            defer {
                isRenaming = false
                scheduleAutosave()
            }
            do {
                let destination = try await RepositoryOperationCoordinator.shared.withRepository(at: repository) {
                    try RepositoryFileRename.rename(source, to: trimmed, in: repository, expected: expected)
                }
                liveURL = destination
                showRenameModal = false
                renameText = ""
                state.detectChanges(repoID: repoID)
            } catch {
                renameError = error.localizedDescription
            }
        }
    }
}

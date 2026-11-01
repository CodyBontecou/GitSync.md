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
    @State private var showSaveToast = false
    @State private var showCommitSheet = false

    init(repoID: UUID, fileURL: URL) {
        self.repoID = repoID
        self.fileURL = fileURL
        self._liveURL = State(initialValue: fileURL)
    }

    private var fileName: String { liveURL.lastPathComponent }
    private var isDirty: Bool { content != originalContent }
    private var language: SyntaxLanguage { SyntaxLanguage.detect(fileExtension: liveURL.pathExtension) }

    /// Path of the open file relative to the vault root, symlinks resolved on
    /// both sides (the vault URL comes back through /var -> /private/var).
    private var relativePath: String? {
        let base = state.vaultURL(for: repoID).resolvingSymlinksInPath().standardizedFileURL.pathComponents
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

    /// True when git sees this file as changed since the last commit.
    private var hasUncommittedChanges: Bool {
        guard let rel = relativePath?.precomposedStringWithCanonicalMapping else { return false }
        return (state.statusEntriesByRepo[repoID] ?? []).contains { $0.path == rel }
    }

    private var showsSyncPill: Bool {
        !isBinary && !isDirty && (showSaveToast || hasUncommittedChanges)
    }

    var body: some View {
        ZStack {
            Tide.card.ignoresSafeArea()

            if isBinary {
                binaryState
            } else {
                CodeEditorView(text: $content, language: language)
                    .padding(.horizontal, 4)
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
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 1) {
                    Text(fileName)
                        .font(TideFont.sans(15, .semibold, relativeTo: .headline))
                        .foregroundStyle(Tide.ink)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    if !locationLine.isEmpty {
                        Text(locationLine)
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
                    if !isBinary {
                        Button {
                            performSave()
                        } label: {
                            Text("Save")
                                .font(TideFont.sans(13, .semibold, relativeTo: .subheadline))
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
            }
        }
        .sheet(isPresented: $showCommitSheet) { GitControlSheet(repoID: repoID) }
        .onAppear { loadContent() }
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

    // MARK: - Saved pill

    /// Dark floating pill: saved state, plus a Commit shortcut while this
    /// file has changes git hasn't committed yet.
    private var syncPill: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(hasUncommittedChanges ? Tide.heroAhead : Tide.heroSynced)
                .frame(width: 8, height: 8)
                .accessibilityHidden(true)
            Text(hasUncommittedChanges
                 ? String(localized: "Saved · not committed")
                 : String(localized: "Saved"))
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

    private func performSave() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        guard !isSaving, let replacement = content.data(using: .utf8) else { return }
        let expected = loadedBytes
        let savedContent = content
        let destination = liveURL
        let repositoryURL = state.vaultURL(for: repoID)
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
        let repositoryURL = state.vaultURL(for: repoID)
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
        showRenameModal = false
        renameText = ""
        guard !trimmed.isEmpty, trimmed != liveURL.lastPathComponent else { return }
        let dest = liveURL.deletingLastPathComponent().appendingPathComponent(trimmed)
        guard !FileManager.default.fileExists(atPath: dest.path) else { return }
        do {
            try FileManager.default.moveItem(at: liveURL, to: dest)
            liveURL = dest
            state.detectChanges(repoID: repoID)
        } catch {}
    }
}

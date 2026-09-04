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

    init(repoID: UUID, fileURL: URL) {
        self.repoID = repoID
        self.fileURL = fileURL
        self._liveURL = State(initialValue: fileURL)
    }

    private var fileName: String { liveURL.lastPathComponent }
    private var isDirty: Bool { content != originalContent }
    private var language: SyntaxLanguage { SyntaxLanguage.detect(fileExtension: liveURL.pathExtension) }

    var body: some View {
        ZStack {
            Color.brutalBg.ignoresSafeArea()

            if isBinary {
                binaryState
            } else {
                CodeEditorView(text: $content, language: language)
                    .padding(.horizontal, 8)
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
                if showSaveToast {
                    BToast(message: String(localized: "Saved"), systemImage: "checkmark")
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .allowsHitTesting(false)
                }
            }
            .animation(.spring(duration: 0.4, bounce: 0.15), value: showSaveToast)
        }
        .animation(.easeOut(duration: 0.15), value: showDeleteConfirm)
        .animation(.easeOut(duration: 0.15), value: showRenameModal)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(fileName.uppercased())
                    .font(.system(size: 12, weight: .black, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .tracking(2)
                    .lineLimit(1)
            }
            ToolbarItem(placement: .topBarTrailing) {
                HStack(spacing: 16) {
                    if !isBinary {
                        Button("Save") { performSave() }
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundStyle(isDirty ? Color.brutalAccent : Color.brutalTextFaint)
                            .disabled(!isDirty || isSaving)
                    }
                    Button {
                        renameText = fileName
                        showRenameModal = true
                    } label: {
                        Image(systemName: "pencil")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Color.brutalText)
                    }
                    .accessibilityLabel(String(localized: "Rename File"))
                    Button {
                        showDeleteConfirm = true
                    } label: {
                        Image(systemName: "trash")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Color.brutalError)
                    }
                    .accessibilityLabel(String(localized: "Delete File"))
                }
            }
        }
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

    // MARK: - Binary Fallback

    private var binaryState: some View {
        VStack(spacing: 12) {
            Spacer()
            Text("🔒")
                .font(.system(size: 44))
            Text("Binary File")
                .font(.system(size: 14, weight: .bold, design: .monospaced))
                .foregroundStyle(Color.brutalText)
                .tracking(1)
            Text("This file cannot be edited as text")
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(Color.brutalTextFaint)
            Spacer()
        }
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

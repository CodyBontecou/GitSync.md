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

    @State private var content: String = ""
    @State private var originalContent: String = ""
    @State private var isBinary = false
    @State private var showDeleteConfirm = false

    private var fileName: String { fileURL.lastPathComponent }
    private var isDirty: Bool { content != originalContent }
    private var language: SyntaxLanguage { SyntaxLanguage.detect(fileExtension: fileURL.pathExtension) }

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
                    title: "Delete \"\(fileName)\"?",
                    message: "This will be reflected in git status as a deletion.",
                    confirmLabel: "Delete",
                    isDestructive: true,
                    onConfirm: performDelete,
                    onCancel: { showDeleteConfirm = false }
                )
                .transition(.opacity.combined(with: .scale(scale: 0.97)))
            }
        }
        .animation(.easeOut(duration: 0.15), value: showDeleteConfirm)
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
                            .disabled(!isDirty)
                    }
                    Button {
                        showDeleteConfirm = true
                    } label: {
                        Image(systemName: "trash")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Color.brutalError)
                    }
                }
            }
        }
        .onAppear { loadContent() }
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
        guard let data = try? Data(contentsOf: fileURL),
              let text = String(data: data, encoding: .utf8) else {
            isBinary = true
            return
        }
        content = text
        originalContent = text
    }

    private func performSave() {
        guard let data = content.data(using: .utf8) else { return }
        try? data.write(to: fileURL, options: .atomic)
        originalContent = content
        state.detectChanges(repoID: repoID)
    }

    private func performDelete() {
        try? FileManager.default.removeItem(at: fileURL)
        state.detectChanges(repoID: repoID)
        dismiss()
    }
}

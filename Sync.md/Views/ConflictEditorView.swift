import SwiftUI

// MARK: - Navigation Destination

struct ConflictEditorDestination: Hashable {
    let repoID: UUID
    let path: String
}

// MARK: - View

struct ConflictEditorView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss

    let repoID: UUID
    let path: String

    @State private var detail: ConflictFileDetail?
    @State private var isLoading = true
    @State private var loadError: String?
    @State private var resultText: String = ""
    @State private var keepPath: String
    @State private var showResolveConfirm = false
    @State private var showResolved = false

    init(repoID: UUID, path: String) {
        self.repoID = repoID
        self.path = path
        self._keepPath = State(initialValue: path)
    }

    private var oursText: String { detail?.ours?.content.flatMap { String(data: $0, encoding: .utf8) } ?? "" }
    private var theirsText: String { detail?.theirs?.content.flatMap { String(data: $0, encoding: .utf8) } ?? "" }
    private var oursIsBinary: Bool { detail?.ours?.isBinary ?? false }
    private var theirsIsBinary: Bool { detail?.theirs?.isBinary ?? false }

    private var canResolve: Bool {
        guard let detail else { return false }
        if oursIsBinary || theirsIsBinary { return false }
        if detail.isRenameRename {
            return !keepPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        return true
    }

    var body: some View {
        ZStack {
            Color.brutalBg.ignoresSafeArea()

            if isLoading {
                ProgressView()
                    .progressViewStyle(.circular)
            } else if let error = loadError {
                BEmptyState(
                    title: String(localized: "Could not load conflict"),
                    subtitle: error
                )
            } else if showResolved || detail == nil {
                BEmptyState(
                    title: String(localized: "Conflict resolved"),
                    subtitle: String(localized: "All edits for this path have been staged.")
                )
            } else if let detail {
                editorBody(detail: detail)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(String(localized: "RESOLVE CONFLICT"))
                    .font(.system(size: 12, weight: .black, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .tracking(2)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showResolveConfirm = true
                } label: {
                    Text(String(localized: "RESOLVE"))
                        .font(.system(size: 12, weight: .bold, design: .monospaced))
                        .foregroundStyle(canResolve ? Color.brutalAccent : Color.brutalTextFaint)
                        .tracking(1)
                }
                .disabled(!canResolve || state.isSyncing)
                .accessibilityIdentifier("conflict.resolveButton")
                .accessibilityLabel("Resolve conflict")
            }
        }
        .task { await loadDetail() }
        .overlay {
            if showResolveConfirm, let detail {
                BConfirmModal(
                    title: String(localized: "Mark as resolved?"),
                    message: confirmMessage(for: detail),
                    confirmLabel: String(localized: "Resolve"),
                    isDestructive: false,
                    onConfirm: {
                        showResolveConfirm = false
                        Task { await performResolve(detail: detail) }
                    },
                    onCancel: { showResolveConfirm = false }
                )
                .transition(.opacity.combined(with: .scale(scale: 0.97)))
            }
        }
        .animation(.easeOut(duration: 0.15), value: showResolveConfirm)
    }

    @ViewBuilder
    private func editorBody(detail: ConflictFileDetail) -> some View {
        ScrollView {
            VStack(spacing: 12) {
                pathBanner(detail: detail)

                if detail.isRenameRename {
                    renamePicker(detail: detail)
                }

                if oursIsBinary || theirsIsBinary {
                    binaryNotice
                } else {
                    sideBySidePanes
                    resultEditor
                    actionButtons
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
    }

    // MARK: - Sub-views

    private func pathBanner(detail: ConflictFileDetail) -> some View {
        BCard(padding: 0) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    BSectionHeader(title: String(localized: "Conflicted Path"))
                    Spacer()
                    BBadge(text: conflictKindLabel(detail), style: .error)
                }
                Text(path)
                    .font(.system(size: 13, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .lineLimit(2)
                    .truncationMode(.middle)
            }
            .padding(16)
        }
    }

    private func renamePicker(detail: ConflictFileDetail) -> some View {
        BCard(padding: 0) {
            VStack(alignment: .leading, spacing: 10) {
                BSectionHeader(title: String(localized: "Pick Filename"))
                Text(String(localized: "This file was renamed differently on each side. Choose which name to keep — the other will be removed."))
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)

                if let ours = detail.ours {
                    pathOption(label: String(localized: "Ours"), candidate: ours.path)
                }
                if let theirs = detail.theirs {
                    pathOption(label: String(localized: "Theirs"), candidate: theirs.path)
                }
            }
            .padding(16)
        }
    }

    private func pathOption(label: String, candidate: String) -> some View {
        Button {
            keepPath = candidate
        } label: {
            HStack(spacing: 10) {
                Image(systemName: keepPath == candidate ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 16))
                    .foregroundStyle(keepPath == candidate ? Color.brutalAccent : Color.brutalTextMid)
                VStack(alignment: .leading, spacing: 2) {
                    Text(label.uppercased())
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color.brutalTextMid)
                        .tracking(1)
                    Text(candidate)
                        .font(.system(size: 13, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .lineLimit(2)
                        .truncationMode(.middle)
                }
                Spacer()
            }
            .padding(.vertical, 8)
            .padding(.horizontal, 10)
            .overlay(
                Rectangle().strokeBorder(
                    keepPath == candidate ? Color.brutalAccent : Color.brutalBorderSoft,
                    lineWidth: 1
                )
            )
        }
        .buttonStyle(.plain)
    }

    private var binaryNotice: some View {
        BCard {
            VStack(alignment: .leading, spacing: 8) {
                Text(String(localized: "Binary file"))
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                Text(String(localized: "Binary content can't be merged in-app. Use the Conflict Center to pick Ours or Theirs at the file level."))
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)
            }
        }
    }

    private var sideBySidePanes: some View {
        VStack(spacing: 12) {
            sidePane(
                title: String(localized: "Ours"),
                subtitle: String(localized: "your version"),
                text: oursText,
                accent: .brutalAccent
            )
            .accessibilityIdentifier("conflict.oursPane")
            sidePane(
                title: String(localized: "Theirs"),
                subtitle: String(localized: "remote version"),
                text: theirsText,
                accent: .brutalWarning
            )
            .accessibilityIdentifier("conflict.theirsPane")
        }
    }

    private func sidePane(title: String, subtitle: String, text: String, accent: Color) -> some View {
        BCard(padding: 0) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    Text(title.uppercased())
                        .font(.system(size: 12, weight: .black, design: .monospaced))
                        .foregroundStyle(accent)
                        .tracking(1)
                    Text(subtitle)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(Color.brutalTextMid)
                    Spacer()
                    Button {
                        resultText = text
                    } label: {
                        Text(String(localized: "USE THIS"))
                            .font(.system(size: 11, weight: .bold, design: .monospaced))
                            .foregroundStyle(accent)
                            .tracking(1)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .overlay(Rectangle().strokeBorder(accent.opacity(0.4), lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("conflict.use\(title)InlineButton")
                    .accessibilityLabel("Use \(title)")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)

                BDivider()

                ScrollView {
                    Text(text.isEmpty ? "(empty)" : text)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(Color.brutalText)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .textSelection(.enabled)
                }
                .frame(maxHeight: 220)
                .background(Color.brutalSurface)
            }
        }
    }

    private var resultEditor: some View {
        BCard(padding: 0) {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(String(localized: "RESULT"))
                        .font(.system(size: 12, weight: .black, design: .monospaced))
                        .foregroundStyle(Color.brutalSuccess)
                        .tracking(1)
                    Spacer()
                    Text(String(localized: "this is what gets staged"))
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(Color.brutalTextMid)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)

                BDivider()

                TextEditor(text: $resultText)
                    .font(.system(size: 13, design: .monospaced))
                    .scrollContentBackground(.hidden)
                    .background(Color.brutalSurface)
                    .frame(minHeight: 220)
                    .padding(8)
                    .accessibilityIdentifier("conflict.resultEditor")
                    .accessibilityLabel("Conflict result")
            }
        }
    }

    private var actionButtons: some View {
        HStack(spacing: 12) {
            Button {
                resultText = oursText
            } label: {
                Text(String(localized: "USE OURS"))
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .tracking(1)
                    .foregroundStyle(Color.brutalAccent)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .overlay(Rectangle().strokeBorder(Color.brutalAccent.opacity(0.4), lineWidth: 1))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("conflict.useOursButton")
            .accessibilityLabel("Use ours")

            Button {
                resultText = theirsText
            } label: {
                Text(String(localized: "USE THEIRS"))
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .tracking(1)
                    .foregroundStyle(Color.brutalWarning)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .overlay(Rectangle().strokeBorder(Color.brutalWarning.opacity(0.4), lineWidth: 1))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("conflict.useTheirsButton")
            .accessibilityLabel("Use theirs")
        }
    }

    // MARK: - Logic

    private func conflictKindLabel(_ detail: ConflictFileDetail) -> String {
        if detail.isRenameRename { return String(localized: "RENAME/RENAME") }
        if detail.isDeleteModify { return String(localized: "DELETE/MODIFY") }
        if detail.ancestor == nil { return String(localized: "ADD/ADD") }
        return String(localized: "CONFLICT")
    }

    private func confirmMessage(for detail: ConflictFileDetail) -> String {
        if detail.isRenameRename {
            let removed = detail.allPaths.filter { $0 != keepPath }
            let removedList = removed.joined(separator: ", ")
            return String(localized: "Save the result as \"\(keepPath)\" and remove \(removedList)?")
        }
        return String(localized: "Save the result and mark this file resolved?")
    }

    private func loadDetail() async {
        isLoading = true
        loadError = nil

        let result = await state.loadConflictDetail(repoID: repoID, path: path)

        if let result {
            detail = result
            // Seed result with ours by default; user can switch to theirs or edit.
            if let oursContent = result.ours?.content,
               let oursString = String(data: oursContent, encoding: .utf8) {
                resultText = oursString
            } else if let theirsContent = result.theirs?.content,
                      let theirsString = String(data: theirsContent, encoding: .utf8) {
                resultText = theirsString
            }
            // Default keep-path: prefer ours-side path for rename/rename.
            if result.isRenameRename, let ours = result.ours {
                keepPath = ours.path
            }
            isLoading = false
        } else {
            isLoading = false
            // No detail returned could mean the conflict is already gone.
            showResolved = true
        }
    }

    private func performResolve(detail: ConflictFileDetail) async {
        guard let data = resultText.data(using: .utf8) else {
            loadError = String(localized: "Result text could not be encoded as UTF-8.")
            return
        }

        let extras: [String]
        if detail.isRenameRename {
            extras = detail.allPaths.filter { $0 != keepPath }
        } else {
            extras = detail.allPaths.filter { $0 != path }
        }

        await state.resolveConflictWithContent(
            repoID: repoID,
            path: keepPath,
            content: data,
            additionalPathsToRemove: extras
        )

        dismiss()
    }
}

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
    @State private var showManualEditor = false

    init(repoID: UUID, path: String) {
        self.repoID = repoID
        self.path = path
        self._keepPath = State(initialValue: path)
    }

    private enum Side { case ours, theirs }

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

    /// Which version the current result matches, if either.
    private var selectedSide: Side? {
        if resultText == oursText { return .ours }
        if resultText == theirsText { return .theirs }
        return nil
    }

    /// Position of this file among the vault's conflicted paths ("1 of 2").
    private var conflictPosition: (index: Int, total: Int)? {
        let conflicted = (state.statusEntriesByRepo[repoID] ?? []).filter(\.isConflicted).map(\.path)
        guard let i = conflicted.firstIndex(of: path) else { return nil }
        return (i + 1, conflicted.count)
    }

    var body: some View {
        ZStack {
            Tide.paper.ignoresSafeArea()

            if isLoading {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(Tide.ink)
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
                progressDashes
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showResolveConfirm = true
                } label: {
                    Text(String(localized: "Resolve"))
                        .font(TideFont.sans(15, .semibold, relativeTo: .subheadline))
                        .foregroundStyle(canResolve ? Tide.ink : Tide.inkFaint)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!canResolve || state.isSyncing)
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
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .animation(.easeOut(duration: 0.15), value: showResolveConfirm)
    }

    /// One dash per conflicted file, the current one in ink.
    @ViewBuilder
    private var progressDashes: some View {
        if let pos = conflictPosition, pos.total > 1 {
            HStack(spacing: 5) {
                ForEach(1...min(pos.total, 8), id: \.self) { i in
                    Capsule()
                        .fill(i == pos.index ? Tide.ink : Color.brutalBorderSoft)
                        .frame(width: 22, height: 4)
                }
            }
            .accessibilityElement()
            .accessibilityLabel(String(localized: "Conflict \(pos.index) of \(pos.total)"))
        } else {
            Text(String(localized: "Resolve conflict"))
                .font(TideFont.sans(15, .semibold, relativeTo: .headline))
                .foregroundStyle(Tide.ink)
        }
    }

    @ViewBuilder
    private func editorBody(detail: ConflictFileDetail) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header(detail: detail)
                    .padding(.horizontal, 4)
                    .padding(.top, 8)

                VStack(spacing: 10) {
                    if detail.isRenameRename {
                        renamePicker(detail: detail)
                    }

                    if oursIsBinary || theirsIsBinary {
                        binaryNotice
                    } else {
                        versionCard(.ours, detail: detail)
                        versionCard(.theirs, detail: detail)
                        secondaryActions(detail: detail)
                            .padding(.top, 4)
                        if showManualEditor {
                            resultEditor
                                .padding(.top, 4)
                                .transition(.opacity.combined(with: .move(edge: .top)))
                        }
                    }
                }
                .padding(.top, 18)
            }
            .padding(.horizontal, Tide.gutter)
            .padding(.bottom, 40)
            .animation(.easeOut(duration: 0.2), value: showManualEditor)
        }
        .scrollIndicators(.hidden)
        .scrollDismissesKeyboard(.interactively)
    }

    // MARK: - Header

    private func header(detail: ConflictFileDetail) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Group {
                if let pos = conflictPosition {
                    Text(String(localized: "Conflict \(pos.index) of \(pos.total)") + " · " + path)
                } else {
                    Text(conflictKindLabel(detail) + " · " + path)
                }
            }
            .font(TideFont.mono(11.5))
            .foregroundStyle(Tide.ahead)
            .lineLimit(2)
            .truncationMode(.middle)

            Text(headline(for: detail))
                .tideDisplay(30)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)

            Text(String(localized: "Pick the version to keep, or keep both and tidy up after."))
                .font(TideFont.sans(14, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func headline(for detail: ConflictFileDetail) -> String {
        if detail.isRenameRename { return String(localized: "Renamed two different ways.") }
        if detail.isDeleteModify { return String(localized: "Deleted on one side, edited on the other.") }
        if detail.ancestor == nil { return String(localized: "Added on both sides.") }
        return String(localized: "Both sides edited this file.")
    }

    // MARK: - Version cards

    private func versionCard(_ side: Side, detail: ConflictFileDetail) -> some View {
        let isOurs = side == .ours
        let fileSide = isOurs ? detail.ours : detail.theirs
        let text = isOurs ? oursText : theirsText
        let tint = isOurs ? Tide.ahead : Tide.behind
        let selected = selectedSide == side

        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Group {
                    if isOurs {
                        Circle().fill(tint)
                    } else {
                        Circle().strokeBorder(tint, lineWidth: 2)
                    }
                }
                .frame(width: 9, height: 9)
                .accessibilityHidden(true)

                Text(isOurs ? String(localized: "This iPhone") : String(localized: "Remote"))
                    .font(TideFont.sans(14, .semibold, relativeTo: .subheadline))
                    .foregroundStyle(Tide.ink)
                Text(isOurs ? String(localized: "your version") : String(localized: "remote version"))
                    .font(TideFont.sans(13, relativeTo: .footnote))
                    .foregroundStyle(Tide.inkFaint)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(sideMeta(fileSide, text: text, detail: detail))
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }

            ScrollView {
                Text(previewText(fileSide, text: text))
                    .font(TideFont.mono(12.5, relativeTo: .footnote))
                    .foregroundStyle(fileSide == nil || text.isEmpty ? Tide.inkFaint : Tide.ink)
                    .lineSpacing(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .textSelection(.enabled)
            }
            .frame(maxHeight: 200)
            .fixedSize(horizontal: false, vertical: true)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(tint.opacity(0.07)))

            Button {
                resultText = text
                if canResolve { showResolveConfirm = true }
            } label: {
                HStack(spacing: 8) {
                    if selected {
                        Image(systemName: "checkmark")
                            .font(.system(size: 13, weight: .bold))
                            .accessibilityHidden(true)
                    }
                    Text(String(localized: "Keep this version"))
                        .font(TideFont.sans(15, .semibold))
                }
                .foregroundStyle(selected ? Tide.paper : Tide.ink)
                .frame(maxWidth: .infinity, minHeight: 46)
                .background(
                    Capsule().fill(selected ? Tide.ink : Color.clear)
                )
                .overlay(
                    Capsule().strokeBorder(selected ? Color.clear : Color.brutalBorderSoft, lineWidth: 1)
                )
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(state.isSyncing)
            .accessibilityLabel(isOurs
                ? String(localized: "Keep this iPhone's version")
                : String(localized: "Keep the remote version"))
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
        .overlay(
            RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous)
                .strokeBorder(selected ? Tide.ink : Color.clear, lineWidth: 2)
        )
    }

    /// Right-hand meta: the side's path for rename conflicts, else its size.
    private func sideMeta(_ side: ConflictFileSide?, text: String, detail: ConflictFileDetail) -> String {
        guard let side else { return String(localized: "deleted") }
        if detail.isRenameRename { return side.path }
        let lines = text.isEmpty ? 0 : text.components(separatedBy: "\n").count
        return lines == 1 ? String(localized: "1 line") : String(localized: "\(lines) lines")
    }

    private func previewText(_ side: ConflictFileSide?, text: String) -> String {
        if side == nil { return String(localized: "(deleted on this side)") }
        return text.isEmpty ? String(localized: "(empty)") : text
    }

    // MARK: - Keep both / Edit by hand

    private func secondaryActions(detail: ConflictFileDetail) -> some View {
        HStack(spacing: 10) {
            outlinePill(String(localized: "Keep both")) {
                resultText = combinedText
                showManualEditor = true
            }
            .disabled(detail.ours == nil || detail.theirs == nil)
            .opacity(detail.ours == nil || detail.theirs == nil ? 0.4 : 1)

            outlinePill(String(localized: "Edit by hand")) {
                showManualEditor = true
            }
        }
    }

    /// Both versions, this iPhone's first, for tidying up by hand.
    private var combinedText: String {
        let ours = oursText.hasSuffix("\n") ? oursText : oursText + "\n"
        return ours + "\n" + theirsText
    }

    private func outlinePill(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(TideFont.sans(14, .medium, relativeTo: .subheadline))
                .foregroundStyle(Tide.ink)
                .frame(maxWidth: .infinity, minHeight: 46)
                .background(Capsule().strokeBorder(Color.brutalBorderSoft, lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private var resultEditor: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                TideSectionLabel(title: String(localized: "Result"))
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Text(String(localized: "this is what gets staged"))
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
            }

            TextEditor(text: $resultText)
                .font(TideFont.mono(13, relativeTo: .footnote))
                .foregroundStyle(Tide.ink)
                .scrollContentBackground(.hidden)
                .padding(8)
                .frame(minHeight: 220)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Tide.well))

            HStack(spacing: 8) {
                smallPill(String(localized: "Use this iPhone's"), tint: Tide.ahead) { resultText = oursText }
                smallPill(String(localized: "Use remote"), tint: Tide.behind) { resultText = theirsText }
            }

            BPrimaryButton(
                title: String(localized: "Resolve with this result"),
                isDisabled: !canResolve || state.isSyncing
            ) {
                showResolveConfirm = true
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
    }

    private func smallPill(_ title: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(TideFont.sans(13, .semibold, relativeTo: .footnote))
                .foregroundStyle(tint)
                .padding(.horizontal, 14)
                .frame(minHeight: 36)
                .background(Capsule().fill(tint.opacity(0.12)))
                .frame(minHeight: 44)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Rename picker / binary

    private func renamePicker(detail: ConflictFileDetail) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(String(localized: "Pick a filename"))
                .font(TideFont.sans(15, .semibold))
                .foregroundStyle(Tide.ink)
                .accessibilityAddTraits(.isHeader)
            Text(String(localized: "This file was renamed differently on each side. Choose which name to keep — the other will be removed."))
                .font(TideFont.sans(13, relativeTo: .footnote))
                .foregroundStyle(Tide.inkMid)
                .fixedSize(horizontal: false, vertical: true)

            VStack(spacing: 0) {
                if let ours = detail.ours {
                    pathOption(label: String(localized: "This iPhone"), candidate: ours.path)
                }
                if detail.ours != nil, detail.theirs != nil {
                    Rectangle().fill(Tide.line).frame(height: 1)
                }
                if let theirs = detail.theirs {
                    pathOption(label: String(localized: "Remote"), candidate: theirs.path)
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
    }

    private func pathOption(label: String, candidate: String) -> some View {
        let selected = keepPath == candidate
        return Button {
            keepPath = candidate
        } label: {
            HStack(spacing: 12) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 18))
                    .foregroundStyle(selected ? Tide.ink : Tide.inkFaint)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                        .font(TideFont.body)
                        .foregroundStyle(Tide.ink)
                    Text(candidate)
                        .font(TideFont.mono(11))
                        .foregroundStyle(Tide.inkFaint)
                        .lineLimit(2)
                        .truncationMode(.middle)
                }
                Spacer()
            }
            .padding(.vertical, 11)
            // Full option row is a ≥44pt-tall pick target.
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var binaryNotice: some View {
        TideCard(padding: 16) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "doc.badge.ellipsis")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Tide.ahead)
                    .frame(width: 26, height: 26)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Tide.ahead.opacity(0.12)))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(String(localized: "Binary file"))
                        .font(TideFont.sans(15, .semibold))
                        .foregroundStyle(Tide.ink)
                    Text(String(localized: "Binary content can't be merged in-app. Use the Conflict Center to pick Ours or Theirs at the file level."))
                        .font(TideFont.sans(13, relativeTo: .footnote))
                        .foregroundStyle(Tide.inkMid)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    // MARK: - Logic

    private func conflictKindLabel(_ detail: ConflictFileDetail) -> String {
        if detail.isRenameRename { return String(localized: "Rename/rename") }
        if detail.isDeleteModify { return String(localized: "Delete/modify") }
        if detail.ancestor == nil { return String(localized: "Add/add") }
        return String(localized: "Conflict")
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

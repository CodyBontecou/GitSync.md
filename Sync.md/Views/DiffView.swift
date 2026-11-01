import SwiftUI

// MARK: - Navigation Destination

struct DiffDestination: Hashable {
    let repoID: UUID
    let path: String
}

// MARK: - Diff Line Model

enum DiffLineKind {
    case fileHeader   // diff --git, index, ---, +++
    case hunkHeader   // @@ ... @@
    case added        // +
    case removed      // -
    case context      // unchanged
    case noNewline    // \ No newline at end of file
}

struct DiffLine: Identifiable {
    let id = UUID()
    let kind: DiffLineKind
    let raw: String
    let oldLineNo: Int?
    let newLineNo: Int?

    var body: String {
        switch kind {
        case .added, .removed: return String(raw.dropFirst())
        // Unified diff prefixes unchanged lines with a single space.
        case .context: return raw.hasPrefix(" ") ? String(raw.dropFirst()) : raw
        default: return raw
        }
    }
}

// MARK: - Parser

private func parsePatch(_ patch: String) -> [DiffLine] {
    var lines: [DiffLine] = []
    var oldLine = 0
    var newLine = 0

    for raw in patch.components(separatedBy: "\n") {
        if raw.hasPrefix("diff ") || raw.hasPrefix("index ") ||
           raw.hasPrefix("--- ") || raw.hasPrefix("+++ ") {
            lines.append(DiffLine(kind: .fileHeader, raw: raw, oldLineNo: nil, newLineNo: nil))
        } else if raw.hasPrefix("@@ ") {
            let scanner = Scanner(string: raw)
            scanner.charactersToBeSkipped = nil
            _ = scanner.scanUpToString("-")
            _ = scanner.scanString("-")
            let oldStart = scanner.scanInt() ?? 1
            _ = scanner.scanUpToString("+")
            _ = scanner.scanString("+")
            let newStart = scanner.scanInt() ?? 1
            oldLine = oldStart
            newLine = newStart
            // Hunk headers carry the new-side start line for the Tide caption.
            lines.append(DiffLine(kind: .hunkHeader, raw: raw, oldLineNo: oldStart, newLineNo: newStart))
        } else if raw.hasPrefix("+") {
            lines.append(DiffLine(kind: .added, raw: raw, oldLineNo: nil, newLineNo: newLine))
            newLine += 1
        } else if raw.hasPrefix("-") {
            lines.append(DiffLine(kind: .removed, raw: raw, oldLineNo: oldLine, newLineNo: nil))
            oldLine += 1
        } else if raw.hasPrefix("\\") {
            lines.append(DiffLine(kind: .noNewline, raw: raw, oldLineNo: nil, newLineNo: nil))
        } else if !raw.isEmpty {
            lines.append(DiffLine(kind: .context, raw: raw, oldLineNo: oldLine, newLineNo: newLine))
            oldLine += 1
            newLine += 1
        }
    }
    return lines
}

// MARK: - Main View

struct FileDiffView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    let repoID: UUID
    let path: String

    @State private var isLoading = true
    @State private var showRevertConfirm = false
    @State private var isReverting = false
    @State private var showRevertModal = false
    @State private var editorDestination: FileEditorDestination?

    // MARK: Derived

    private var filename: String {
        URL(fileURLWithPath: path).lastPathComponent
    }
    private var directory: String {
        let dir = URL(fileURLWithPath: path).deletingLastPathComponent().path
        return (dir == "." || dir == "/") ? "" : dir
    }
    private var entry: GitStatusEntry? {
        state.statusEntriesByRepo[repoID]?.first { $0.path == path }
    }
    private var patch: String {
        guard let result = state.diffByRepo[repoID] else { return "" }
        if let file = result.files.first(where: {
            $0.path == path || $0.newPath == path || $0.oldPath == path
        }) {
            return file.patch.isEmpty ? result.rawPatch : file.patch
        }
        return result.rawPatch
    }
    private var parsedLines: [DiffLine] { parsePatch(patch) }
    private var diffLines: [DiffLine]   { parsedLines.filter { $0.kind != .fileHeader } }
    private var addedCount: Int         { parsedLines.filter { $0.kind == .added }.count }
    private var removedCount: Int       { parsedLines.filter { $0.kind == .removed }.count }

    /// HEAD commit the working copy is compared against.
    private var headSHA: String? {
        guard let sha = state.repo(id: repoID)?.gitState.commitSHA, !sha.isEmpty else { return nil }
        return String(sha.prefix(7))
    }

    /// The file on disk, when it still exists (deleted files can't be opened).
    private var fileURL: URL? {
        let url = state.vaultURL(for: repoID).appendingPathComponent(path)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    // MARK: Body

    var body: some View {
        ZStack {
            Tide.paper.ignoresSafeArea()

            if isLoading {
                VStack(spacing: 12) {
                    BLoading(text: String(localized: "Loading diff"))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if patch.isEmpty {
                ContentUnavailableView(
                    String(localized: "No Diff Available"),
                    systemImage: "doc.text",
                    description: Text(String(localized: "This file may be binary or has no staged content."))
                )
            } else {
                content
            }
        }
        .safeAreaInset(edge: .bottom) { bottomBar }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(directory.isEmpty ? "/" : directory + "/")
                    .font(TideFont.mono(12, relativeTo: .headline))
                    .foregroundStyle(Tide.inkMid)
                    .lineLimit(1)
                    .truncationMode(.head)
            }
        }
        .navigationDestination(item: $editorDestination) { dest in
            FileEditorView(repoID: dest.repoID, fileURL: dest.fileURL)
        }
        .overlay {
            if showRevertModal {
                RevertConfirmModal(
                    title: String(localized: "Revert Changes"),
                    filename: filename,
                    files: [path],
                    confirmLabel: String(localized: "Revert"),
                    onConfirm: {
                        showRevertModal = false
                        Task {
                            isReverting = true
                            await state.discardFileChanges(repoID: repoID, path: path)
                            isReverting = false
                            dismiss()
                        }
                    },
                    onCancel: { showRevertModal = false }
                )
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .animation(.easeOut(duration: 0.18), value: showRevertModal)
        .task {
            #if DEBUG
            if MarketingCapture.isActive {
                isLoading = false
                return
            }
            #endif
            await state.loadUnifiedDiff(repoID: repoID, path: path)
            isLoading = false
        }
    }

    // MARK: - Content

    private var content: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 0) {
                headerSection
                    .padding(.horizontal, 20)
                    .padding(.top, 4)

                diffCard
                    .padding(.horizontal, Tide.gutter)
                    .padding(.top, 16)
                    .padding(.bottom, 24)
            }
        }
        .scrollIndicators(.hidden)
    }

    // MARK: - Header

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(filename)
                    .tideDisplay(26)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
                if let entry { entryBadge(entry) }
            }

            statsLine
        }
    }

    /// "+8 −3 · vs last commit a41c9e2"
    private var statsLine: some View {
        HStack(spacing: 6) {
            Text("+\(addedCount)")
                .foregroundStyle(addedCount > 0 ? Tide.synced : Tide.inkFaint)
            Text("−\(removedCount)")
                .foregroundStyle(removedCount > 0 ? Tide.danger : Tide.inkFaint)
            if let headSHA {
                Text(String(localized: "· vs last commit \(headSHA)"))
                    .fontWeight(.regular)
                    .foregroundStyle(Tide.inkFaint)
                    .lineLimit(1)
            }
        }
        .font(TideFont.mono(12, .semibold))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "\(addedCount) lines added, \(removedCount) lines removed"))
    }

    @ViewBuilder
    private func entryBadge(_ entry: GitStatusEntry) -> some View {
        if entry.isConflicted {
            BBadge(text: String(localized: "Conflict"), style: .error)
        } else if let index = entry.indexStatus {
            BBadge(text: statusWord(index), style: .success)
        } else if let work = entry.workTreeStatus {
            BBadge(text: statusWord(work), style: work == .untracked ? .accent : .default)
        }
    }

    private func statusWord(_ kind: GitFileStatusKind) -> String {
        switch kind {
        case .added:       return String(localized: "Added")
        case .modified:    return String(localized: "Modified")
        case .deleted:     return String(localized: "Deleted")
        case .renamed:     return String(localized: "Renamed")
        case .typeChanged: return String(localized: "Type Chg")
        case .untracked:   return String(localized: "Untracked")
        case .conflicted:  return String(localized: "Conflict")
        }
    }

    // MARK: - Diff Card

    private var diffCard: some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            ForEach(diffLines) { line in
                lineRow(line)
            }
        }
        .padding(.bottom, 8)
        .background(Tide.card)
        .clipShape(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous))
    }

    @ViewBuilder
    private func lineRow(_ line: DiffLine) -> some View {
        switch line.kind {
        case .hunkHeader:
            Text(hunkCaption(line))
                .font(TideFont.mono(11))
                .foregroundStyle(Tide.inkFaint)
                .lineLimit(1)
                .truncationMode(.tail)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(DiffPalette.hunkBg)
                .accessibilityLabel(line.raw)
        default:
            let cfg = lineStyle(line.kind)
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text(gutterText(line))
                    .font(TideFont.mono(11))
                    .foregroundStyle(cfg.gutterFg)
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                    .frame(width: 30, alignment: .leading)
                    .accessibilityHidden(true)

                Text(line.kind == .noNewline ? line.raw : line.body)
                    .font(TideFont.mono(12.5, relativeTo: .footnote))
                    .italic(line.kind == .noNewline)
                    .foregroundStyle(cfg.textFg)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 2)
            .background(cfg.rowBg)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(accessibilityPrefix(line.kind) + line.body)
        }
    }

    /// "@@ ## Steps · line 12" — the hunk's section context and new-side start.
    private func hunkCaption(_ line: DiffLine) -> String {
        let parts = line.raw.components(separatedBy: "@@")
        let context = parts.count > 2
            ? parts[2...].joined(separator: "@@").trimmingCharacters(in: .whitespaces)
            : ""
        let start = line.newLineNo.map { String(localized: "line \($0)") } ?? ""
        return ["@@", context, context.isEmpty || start.isEmpty ? start : "· " + start]
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    private func gutterText(_ line: DiffLine) -> String {
        switch line.kind {
        case .added:   return "+"
        case .removed: return "−"
        case .context: return line.newLineNo.map(String.init) ?? ""
        default:       return ""
        }
    }

    private func accessibilityPrefix(_ kind: DiffLineKind) -> String {
        switch kind {
        case .added:   return String(localized: "Added: ")
        case .removed: return String(localized: "Removed: ")
        default:       return ""
        }
    }

    // MARK: - Bottom Bar

    private var bottomBar: some View {
        HStack(spacing: 10) {
            Button {
                showRevertModal = true
            } label: {
                Group {
                    if isReverting {
                        ProgressView().controlSize(.small).tint(Tide.danger)
                    } else {
                        Text(String(localized: "Discard"))
                            .font(TideFont.sans(15, .medium))
                    }
                }
                .foregroundStyle(Tide.danger)
                .padding(.horizontal, 20)
                .frame(minHeight: 56)
                .background(Capsule().strokeBorder(Tide.danger.opacity(0.3), lineWidth: 1))
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(isLoading || isReverting)
            .accessibilityLabel(String(localized: "Revert Changes"))
            .accessibilityHint(String(localized: "Discard local edits to this file"))

            if let fileURL {
                BPrimaryButton(title: String(localized: "Open in editor")) {
                    editorDestination = FileEditorDestination(repoID: repoID, fileURL: fileURL)
                }
            } else {
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, Tide.gutter)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background(Tide.paper.ignoresSafeArea(edges: .bottom))
    }

    // MARK: - Styling

    struct LineStyle {
        var rowBg: Color
        var textFg: Color
        var gutterFg: Color
    }

    private func lineStyle(_ kind: DiffLineKind) -> LineStyle {
        switch kind {
        case .added:
            return LineStyle(rowBg: DiffPalette.addedBg, textFg: DiffPalette.addedText, gutterFg: DiffPalette.addedSign)
        case .removed:
            return LineStyle(rowBg: DiffPalette.removedBg, textFg: DiffPalette.removedText, gutterFg: DiffPalette.removedSign)
        case .noNewline:
            return LineStyle(rowBg: .clear, textFg: Tide.inkFaint, gutterFg: Tide.inkFaint)
        default:
            return LineStyle(rowBg: .clear, textFg: Tide.inkMid, gutterFg: DiffPalette.lineNumber)
        }
    }
}

// MARK: - Diff palette

/// Tide diff tints: green rows for additions, red for removals, a paper-grey
/// band for hunk headers. Light values from the Diff artboard.
private enum DiffPalette {
    static let hunkBg      = Color(light: Color(hex: 0xEEEDE7), dark: Color(hex: 0x232327))
    static let lineNumber  = Color(light: Color(hex: 0xB5B5BB), dark: Color(hex: 0x5C5C63))
    static let addedBg     = Color(light: Color(hex: 0xE5F1EA), dark: Color(hex: 0x15291E))
    static let addedText   = Color(light: Color(hex: 0x0F4F2C), dark: Color(hex: 0xA6E3BF))
    static let addedSign   = Color(light: Color(hex: 0x6FA987), dark: Color(hex: 0x5BC489))
    static let removedBg   = Color(light: Color(hex: 0xFBE9E7), dark: Color(hex: 0x33191A))
    static let removedText = Color(light: Color(hex: 0x8A1C12), dark: Color(hex: 0xFFB4A9))
    static let removedSign = Color(light: Color(hex: 0xC9837B), dark: Color(hex: 0xFF7A6B))
}

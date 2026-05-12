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
            lines.append(DiffLine(kind: .hunkHeader, raw: raw, oldLineNo: nil, newLineNo: nil))
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
    private var commitSHAs: (old: String, new: String)? {
        for line in parsedLines where line.kind == .fileHeader {
            if line.raw.hasPrefix("index ") {
                let parts = line.raw.dropFirst(6).components(separatedBy: " ")
                if let shas = parts.first?.components(separatedBy: ".."), shas.count == 2 {
                    return (String(shas[0].prefix(7)), String(shas[1].prefix(7)))
                }
            }
        }
        return nil
    }

    // MARK: Body

    var body: some View {
        ZStack {
            Color.brutalBg.ignoresSafeArea()

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
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(filename.uppercased())
                    .font(.system(size: 12, weight: .black, design: .monospaced))
                    .foregroundStyle(Color.brutalText)
                    .tracking(2)
            }
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showRevertModal = true
                } label: {
                    if isReverting {
                        ProgressView()
                            .controlSize(.small)
                            .tint(Color.brutalError)
                    } else {
                        Image(systemName: "arrow.uturn.backward")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Color.brutalError)
                    }
                }
                .disabled(isLoading || isReverting)
                .accessibilityLabel("Revert Changes")
                .accessibilityHint("Opens a confirmation before discarding local changes to \(filename).")
            }
        }
        .overlay {
            if showRevertModal {
                RevertConfirmModal(
                    title: String(localized: "Revert Changes"),
                    filename: filename,
                    files: [],
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
                .transition(.opacity.combined(with: .scale(scale: 0.96)))
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
                // Header
                headerSection
                    .padding(.horizontal, 20)
                    .padding(.top, 12)
                    .padding(.bottom, 16)

                // Divider before diff
                Rectangle()
                    .fill(Color.brutalBorder)
                    .frame(height: 1)

                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(diffLines) { line in
                        lineRow(line)
                    }
                }
                .padding(.bottom, 48)
            }
        }
        .scrollIndicators(.hidden)
    }

    // MARK: - Header

    private var headerSection: some View {
        BCard(padding: 0) {
            VStack(spacing: 0) {
                // Filename + badge
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(filename)
                            .font(.system(size: 24, weight: .black))
                            .foregroundStyle(Color.brutalText)
                        if !directory.isEmpty {
                            Text(directory)
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundStyle(Color.brutalTextFaint)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                    }
                    Spacer(minLength: 12)
                    if let entry { entryBadge(entry) }
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 14)

                Rectangle()
                    .fill(Color.brutalBorderSoft)
                    .frame(height: 1)
                    .padding(.horizontal, 16)

                // Stats row
                HStack(spacing: 0) {
                    statPill(
                        count: addedCount,
                        label: String(localized: "Added").uppercased(),
                        color: Color(hex: 0x1A7A1A)
                    )
                    Spacer()
                    Rectangle()
                        .fill(Color.brutalBorderSoft)
                        .frame(width: 1, height: 32)
                    Spacer()
                    statPill(
                        count: removedCount,
                        label: String(localized: "Removed").uppercased(),
                        color: Color(hex: 0xD70015)
                    )
                    if let shas = commitSHAs {
                        Spacer()
                        Rectangle()
                            .fill(Color.brutalBorderSoft)
                            .frame(width: 1, height: 32)
                        Spacer()
                        shaChip(old: shas.old, new: shas.new)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
            }
        }
    }

    private func statPill(count: Int, label: String, color: Color) -> some View {
        VStack(spacing: 2) {
            Text("\(count)")
                .font(.system(size: 22, weight: .black, design: .monospaced))
                .foregroundStyle(count > 0 ? color : Color.brutalTextFaint)
            Text(label)
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundStyle(Color.brutalTextFaint)
                .tracking(1)
        }
    }

    private func shaChip(old: String, new: String) -> some View {
        VStack(spacing: 2) {
            HStack(spacing: 4) {
                Text(old)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)
                Image(systemName: "arrow.right")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(Color.brutalTextFaint)
                    .accessibilityHidden(true)
                Text(new)
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(Color.brutalTextMid)
            }
            Text(String(localized: "Commit").uppercased())
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundStyle(Color.brutalTextFaint)
                .tracking(1)
        }
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

    // MARK: - Diff Line Row

    private func lineRow(_ line: DiffLine) -> some View {
        let cfg = lineStyle(line.kind)

        return HStack(alignment: .top, spacing: 0) {
            // Old line number
            Text(line.oldLineNo.map { "\($0)" } ?? "")
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(cfg.gutterFg)
                .frame(width: 40, alignment: .trailing)
                .padding(.vertical, 3)

            // New line number
            Text(line.newLineNo.map { "\($0)" } ?? "")
                .font(.system(size: 11, design: .monospaced))
                .foregroundStyle(cfg.gutterFg)
                .frame(width: 40, alignment: .trailing)
                .padding(.vertical, 3)

            // Gutter rule
            Rectangle()
                .fill(Color.brutalBorderSoft)
                .frame(width: 1)
                .padding(.vertical, 1)

            // +/−/↕ prefix
            Text(linePrefix(line.kind))
                .font(.system(size: 13, weight: .bold, design: .monospaced))
                .foregroundStyle(cfg.prefixFg)
                .frame(width: 22, alignment: .center)
                .padding(.vertical, 3)

            // Content
            Text(line.body)
                .font(.system(size: 13, design: .monospaced))
                .foregroundStyle(cfg.textFg)
                .padding(.vertical, 3)
                .padding(.trailing, 24)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(cfg.rowBg)
    }

    private func linePrefix(_ kind: DiffLineKind) -> String {
        switch kind {
        case .added:      return "+"
        case .removed:    return "−"
        case .hunkHeader: return "↕"
        default:          return " "
        }
    }

    // MARK: - Styling

    struct LineStyle {
        var rowBg: Color
        var textFg: Color
        var prefixFg: Color
        var gutterFg: Color
    }

    private func lineStyle(_ kind: DiffLineKind) -> LineStyle {
        switch kind {
        case .added:
            return LineStyle(
                rowBg:    Color(hex: 0x1A7A1A).opacity(0.10),
                textFg:   Color.brutalText,
                prefixFg: Color(hex: 0x1A7A1A),
                gutterFg: Color(hex: 0x1A7A1A).opacity(0.55)
            )
        case .removed:
            return LineStyle(
                rowBg:    Color(hex: 0xD70015).opacity(0.08),
                textFg:   Color.brutalText,
                prefixFg: Color(hex: 0xD70015),
                gutterFg: Color(hex: 0xD70015).opacity(0.55)
            )
        case .hunkHeader:
            return LineStyle(
                rowBg:    Color(hex: 0x007AFF).opacity(0.06),
                textFg:   Color(hex: 0x007AFF),
                prefixFg: Color(hex: 0x007AFF),
                gutterFg: Color.brutalTextFaint
            )
        case .noNewline:
            return LineStyle(
                rowBg:    Color.brutalSurface.opacity(0.4),
                textFg:   Color.brutalTextFaint,
                prefixFg: Color.brutalTextFaint,
                gutterFg: Color.brutalTextFaint
            )
        default:
            return LineStyle(
                rowBg:    Color.clear,
                textFg:   Color.brutalText,
                prefixFg: Color.brutalTextFaint,
                gutterFg: Color.brutalTextFaint
            )
        }
    }
}

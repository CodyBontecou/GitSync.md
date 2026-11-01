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
    /// Word runs (text, changed) when this line pairs with an opposite line.
    var wordRuns: [(String, Bool)]? = nil

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
    return attachWordRuns(lines)
}

/// Pairs each run of removed lines with the added run that follows it and
/// marks the words that actually changed on both sides.
private func attachWordRuns(_ input: [DiffLine]) -> [DiffLine] {
    var lines = input
    var i = 0
    while i < lines.count {
        guard lines[i].kind == .removed else { i += 1; continue }
        let removedStart = i
        while i < lines.count, lines[i].kind == .removed { i += 1 }
        let addedStart = i
        while i < lines.count, lines[i].kind == .added { i += 1 }
        let removedCount = addedStart - removedStart
        let addedCount = i - addedStart
        for k in 0..<min(removedCount, addedCount) {
            let old = lines[removedStart + k], new = lines[addedStart + k]
            let runs = TideTextDiff.wordRuns(old: old.body, new: new.body)
            // Only highlight when the lines are recognisably related.
            let unchanged = runs.new.filter { !$0.1 }.map(\.0.count).reduce(0, +)
            guard unchanged * 3 >= new.body.count else { continue }
            lines[removedStart + k].wordRuns = runs.old
            lines[addedStart + k].wordRuns = runs.new
        }
    }
    return lines
}

// MARK: - Main View

struct FileDiffView: View {
    @Environment(AppState.self) private var state
    @Environment(\.dismiss) private var dismiss
    let repoID: UUID

    private enum Mode: String, CaseIterable, Identifiable {
        case changes, wholeFile, preview
        var id: Self { self }
        var title: String {
            switch self {
            case .changes: String(localized: "Changes")
            case .wholeFile: String(localized: "Whole file")
            case .preview: String(localized: "Preview")
            }
        }
    }

    @State private var path: String
    @State private var mode: Mode = .changes
    @State private var isLoading = true
    @State private var isReverting = false
    @State private var showRevertModal = false
    @State private var editorDestination: FileEditorDestination?
    @State private var fileText: String?

    /// Shown as the right pane of a two-pane vault (iPhone Duo open, iPad):
    /// no navigation-bar items, and closing hands control back to the host
    /// instead of popping the vault.
    private let embedded: Bool
    private let onClose: (() -> Void)?

    init(repoID: UUID, path: String, embedded: Bool = false, onClose: (() -> Void)? = nil) {
        self.repoID = repoID
        self._path = State(initialValue: path)
        self.embedded = embedded
        self.onClose = onClose
    }

    // MARK: Derived

    private var filename: String {
        URL(fileURLWithPath: path).lastPathComponent
    }
    private var directory: String {
        let dir = URL(fileURLWithPath: path).deletingLastPathComponent().path
        return (dir == "." || dir == "/") ? "" : dir
    }
    private var isMarkdown: Bool {
        ["md", "markdown", "mdown"].contains(URL(fileURLWithPath: path).pathExtension.lowercased())
    }
    private var entry: GitStatusEntry? {
        state.statusEntriesByRepo[repoID]?.first { $0.path == path }
    }
    /// The vault's changed files in the Changes-tab order (conflicts open
    /// their own editor, so they're skipped).
    private var changedPaths: [String] {
        (state.statusEntriesByRepo[repoID] ?? [])
            .filter { !$0.isConflicted }
            .map(\.path)
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }
    private var position: (index: Int, total: Int)? {
        guard let i = changedPaths.firstIndex(of: path) else { return nil }
        return (i + 1, changedPaths.count)
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
            } else {
                content
            }
        }
        .safeAreaInset(edge: .bottom) { bottomBar }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                if !embedded {
                    Text(positionCaption)
                        .font(TideFont.mono(12, relativeTo: .headline))
                        .foregroundStyle(Tide.inkMid)
                        .lineLimit(1)
                        .truncationMode(.head)
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                if !embedded {
                    HStack(spacing: 0) {
                        navButton(systemImage: "chevron.up", label: String(localized: "Previous changed file"), offset: -1)
                        navButton(systemImage: "chevron.down", label: String(localized: "Next changed file"), offset: 1)
                    }
                }
            }
        }
        .navigationDestination(item: $editorDestination) { dest in
            FileEditorView(repoID: dest.repoID, fileURL: dest.fileURL)
        }
        .overlay {
            if showRevertModal {
                RevertConfirmModal(
                    repoID: repoID,
                    title: String(localized: "Revert Changes"),
                    filename: filename,
                    files: [path],
                    confirmLabel: String(localized: "Revert"),
                    onConfirm: { stashFirst in
                        showRevertModal = false
                        let target = path
                        Task {
                            isReverting = true
                            await state.discardChanges(repoID: repoID, paths: [target], stashFirst: stashFirst)
                            isReverting = false
                            if embedded { onClose?() } else { dismiss() }
                        }
                    },
                    onCancel: { showRevertModal = false }
                )
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .animation(.easeOut(duration: 0.18), value: showRevertModal)
        .task(id: path) { await load() }
    }

    /// "ideas/ · 2 of 3 · vs a41c9e2" — the pane's own caption when there's
    /// no navigation bar to carry it.
    private var embeddedCaption: String {
        var caption = positionCaption
        if let headSHA { caption += " · " + String(localized: "vs \(headSHA)") }
        return caption
    }

    /// "ideas/ · 2 of 3"
    private var positionCaption: String {
        let dir = directory.isEmpty ? "/" : directory + "/"
        guard let position else { return dir }
        return dir + " · " + String(localized: "\(position.index) of \(position.total)")
    }

    private func navButton(systemImage: String, label: String, offset: Int) -> some View {
        let target: String? = position.flatMap { pos in
            let next = pos.index - 1 + offset
            return changedPaths.indices.contains(next) ? changedPaths[next] : nil
        }
        return Button {
            if let target { path = target }
        } label: {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(target == nil ? Tide.inkFaint : Tide.ink)
                .frame(width: 40, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(target == nil)
        .accessibilityLabel(label)
    }

    private func load() async {
        #if DEBUG
        if MarketingCapture.isActive {
            isLoading = false
            return
        }
        #endif
        await state.loadUnifiedDiff(repoID: repoID, path: path)
        fileText = fileURL.flatMap { try? Data(contentsOf: $0) }.flatMap { String(data: $0, encoding: .utf8) }
        if mode == .preview && !isMarkdown { mode = .changes }
        isLoading = false
    }

    // MARK: - Content

    private var content: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 0) {
                headerSection
                    .padding(.horizontal, 20)
                    .padding(.top, 4)

                modePicker
                    .padding(.horizontal, Tide.gutter)
                    .padding(.top, 14)

                Group {
                    switch mode {
                    case .changes:
                        if patch.isEmpty { emptyDiff } else { diffCard }
                    case .wholeFile:
                        wholeFileCard
                    case .preview:
                        previewCard
                    }
                }
                .padding(.horizontal, Tide.gutter)
                .padding(.top, 12)
                .padding(.bottom, 24)
            }
        }
        .scrollIndicators(.hidden)
    }

    private var emptyDiff: some View {
        TideCard {
            VStack(alignment: .leading, spacing: 4) {
                Text(String(localized: "No Diff Available"))
                    .font(TideFont.sans(15, .semibold))
                    .foregroundStyle(Tide.ink)
                Text(String(localized: "This file may be binary or has no staged content."))
                    .font(TideFont.meta)
                    .foregroundStyle(Tide.inkFaint)
            }
        }
    }

    private var modePicker: some View {
        HStack(spacing: 4) {
            ForEach(Mode.allCases) { option in
                let selected = mode == option
                let enabled = option != .preview || isMarkdown
                Button {
                    mode = option
                } label: {
                    Text(option.title)
                        .font(TideFont.sans(13, selected ? .semibold : .medium, relativeTo: .footnote))
                        .foregroundStyle(selected ? Tide.ink : (enabled ? Tide.inkMid : Tide.inkFaint))
                        .frame(maxWidth: .infinity, minHeight: 32)
                        .background(RoundedRectangle(cornerRadius: 10).fill(selected ? Tide.card : Color.clear))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!enabled)
                .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
            }
        }
        .padding(4)
        .background(RoundedRectangle(cornerRadius: 14).fill(Tide.well))
    }

    // MARK: - Header

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            if embedded {
                Text(embeddedCaption)
                    .font(TideFont.mono(11))
                    .foregroundStyle(Tide.inkFaint)
                    .lineLimit(1)
                    .truncationMode(.head)
                    .padding(.top, 12)
            }
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
        DiffLinesView(lines: diffLines)
    }

    // MARK: - Whole file / Preview

    /// New-side line numbers this change added or edited.
    private var addedLineNumbers: Set<Int> {
        Set(parsedLines.filter { $0.kind == .added }.compactMap(\.newLineNo))
    }

    @ViewBuilder
    private var wholeFileCard: some View {
        if let fileText {
            let lines = TideTextDiff.lines(fileText)
            let added = addedLineNumbers
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(Array(lines.enumerated()), id: \.offset) { index, text in
                    let isAdded = added.contains(index + 1)
                    HStack(alignment: .firstTextBaseline, spacing: 0) {
                        Text("\(index + 1)")
                            .font(TideFont.mono(11))
                            .foregroundStyle(isAdded ? DiffPalette.addedSign : DiffPalette.lineNumber)
                            .minimumScaleFactor(0.6)
                            .lineLimit(1)
                            .frame(width: 30, alignment: .leading)
                            .accessibilityHidden(true)
                        Text(text.isEmpty ? " " : text)
                            .font(TideFont.mono(12.5, relativeTo: .footnote))
                            .foregroundStyle(isAdded ? DiffPalette.addedText : Tide.ink)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 2)
                    .background(isAdded ? DiffPalette.addedBg : Color.clear)
                }
            }
            .padding(.vertical, 10)
            .background(Tide.card)
            .clipShape(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous))
        } else {
            unavailableCard(fileURL == nil
                ? String(localized: "This file was deleted on this iPhone.")
                : String(localized: "This file isn't text, so it can't be shown here."))
        }
    }

    @ViewBuilder
    private var previewCard: some View {
        if let fileText {
            TideMarkdownView(text: fileText)
                .padding(18)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: Tide.radiusCard, style: .continuous).fill(Tide.card))
        } else {
            unavailableCard(String(localized: "This file was deleted on this iPhone."))
        }
    }

    private func unavailableCard(_ text: String) -> some View {
        TideCard {
            Text(text)
                .font(TideFont.sans(14, relativeTo: .subheadline))
                .foregroundStyle(Tide.inkMid)
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
                        Text(embedded ? String(localized: "Discard file") : String(localized: "Discard"))
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
            .disabled(isLoading || isReverting || entry == nil)
            .accessibilityLabel(String(localized: "Revert Changes"))
            .accessibilityHint(String(localized: "Discard local edits to this file"))

            if let fileURL {
                BPrimaryButton(title: embedded ? String(localized: "Edit") : String(localized: "Open in editor")) {
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

}

// MARK: - Diff lines

/// The unified diff as Tide rows: hunk captions, +/− gutters and word-level
/// highlights. Shared by the diff screen, the Duo diff pane and the iPad
/// workspace detail.
struct DiffLinesView: View {
    let lines: [DiffLine]

    init(lines: [DiffLine]) { self.lines = lines }

    /// Builds the rows straight from a unified patch (file headers dropped).
    init(patch: String) {
        self.lines = parsePatch(patch).filter { $0.kind != .fileHeader }
    }

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            ForEach(lines) { line in
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

                lineText(line, cfg: cfg)
                    .font(TideFont.mono(12.5, relativeTo: .footnote))
                    .italic(line.kind == .noNewline)
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

    /// Line body; paired add/remove lines tint just the words that changed.
    private func lineText(_ line: DiffLine, cfg: LineStyle) -> Text {
        guard let runs = line.wordRuns else {
            return Text(line.kind == .noNewline ? line.raw : line.body).foregroundColor(cfg.textFg)
        }
        var attributed = AttributedString()
        let highlight = line.kind == .added ? DiffPalette.addedWord : DiffPalette.removedWord
        for (text, changed) in runs {
            var run = AttributedString(text)
            run.foregroundColor = cfg.textFg
            if changed { run.backgroundColor = highlight }
            attributed += run
        }
        return Text(attributed)
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
/// band for hunk headers, and stronger word tints for the exact edits.
/// Light values from the Diff artboard.
enum DiffPalette {
    static let hunkBg      = Color(light: Color(hex: 0xEEEDE7), dark: Color(hex: 0x232327))
    static let lineNumber  = Color(light: Color(hex: 0xB5B5BB), dark: Color(hex: 0x5C5C63))
    static let addedBg     = Color(light: Color(hex: 0xE5F1EA), dark: Color(hex: 0x15291E))
    static let addedText   = Color(light: Color(hex: 0x0F4F2C), dark: Color(hex: 0xA6E3BF))
    static let addedSign   = Color(light: Color(hex: 0x6FA987), dark: Color(hex: 0x5BC489))
    static let addedWord   = Color(light: Color(hex: 0xC6E3D1), dark: Color(hex: 0x245A3A))
    static let removedBg   = Color(light: Color(hex: 0xFBE9E7), dark: Color(hex: 0x33191A))
    static let removedText = Color(light: Color(hex: 0x8A1C12), dark: Color(hex: 0xFFB4A9))
    static let removedSign = Color(light: Color(hex: 0xC9837B), dark: Color(hex: 0xFF7A6B))
    static let removedWord = Color(light: Color(hex: 0xF5C9C3), dark: Color(hex: 0x5C2522))
}

import SwiftUI

/// Read-only rendered Markdown for the editor and diff "Preview" modes.
/// Block structure (headings, lists, tasks, quotes, code fences, rules) is
/// parsed here; inline styling goes through `AttributedString(markdown:)`.
/// Obsidian wiki links render as links-coloured text.
struct TideMarkdownView: View {
    let text: String

    private enum Block: Identifiable {
        case heading(level: Int, text: String, id: Int)
        case paragraph(String, id: Int)
        case bullet(String, indent: Int, id: Int)
        case ordered(String, marker: String, indent: Int, id: Int)
        case task(String, done: Bool, indent: Int, id: Int)
        case quote(String, id: Int)
        case code(String, id: Int)
        case rule(id: Int)
        case frontmatter(String, id: Int)

        var id: Int {
            switch self {
            case .heading(_, _, let id), .paragraph(_, let id), .bullet(_, _, let id),
                 .ordered(_, _, _, let id), .task(_, _, _, let id), .quote(_, let id),
                 .code(_, let id), .rule(let id), .frontmatter(_, let id):
                return id
            }
        }
    }

    var body: some View {
        let blocks = Self.parse(text)
        VStack(alignment: .leading, spacing: 10) {
            if blocks.isEmpty {
                Text(String(localized: "Nothing to preview"))
                    .font(TideFont.meta)
                    .foregroundStyle(Tide.inkFaint)
            }
            ForEach(blocks) { block in
                view(for: block)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .textSelection(.enabled)
    }

    @ViewBuilder
    private func view(for block: Block) -> some View {
        switch block {
        case .heading(let level, let text, _):
            Text(Self.inline(text))
                .font(TideFont.sans(level == 1 ? 26 : level == 2 ? 21 : 18, .semibold, relativeTo: level == 1 ? .title : .title3))
                .tracking(level == 1 ? -0.6 : -0.2)
                .foregroundStyle(Tide.ink)
                .padding(.top, level <= 2 ? 6 : 2)
                .accessibilityAddTraits(.isHeader)
        case .paragraph(let text, _):
            Text(Self.inline(text))
                .font(TideFont.sans(16, relativeTo: .body))
                .foregroundStyle(Tide.ink)
                .lineSpacing(3)
        case .bullet(let text, let indent, _):
            listRow(marker: Text("•"), text: text, indent: indent)
        case .ordered(let text, let marker, let indent, _):
            listRow(marker: Text(marker).font(TideFont.mono(14)), text: text, indent: indent)
        case .task(let text, let done, let indent, _):
            listRow(
                marker: Image(systemName: done ? "checkmark.square.fill" : "square")
                    .foregroundStyle(done ? Tide.synced : Tide.inkFaint),
                text: text, indent: indent, struck: done
            )
        case .quote(let text, _):
            HStack(alignment: .top, spacing: 10) {
                Rectangle().fill(Tide.line).frame(width: 3)
                Text(Self.inline(text))
                    .font(TideFont.sans(16, relativeTo: .body))
                    .foregroundStyle(Tide.inkMid)
            }
            .fixedSize(horizontal: false, vertical: true)
        case .code(let code, _), .frontmatter(let code, _):
            ScrollView(.horizontal, showsIndicators: false) {
                Text(code)
                    .font(TideFont.mono(13, relativeTo: .footnote))
                    .foregroundStyle(Tide.ink)
                    .padding(12)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Tide.well))
        case .rule:
            Rectangle().fill(Tide.line).frame(height: 1).padding(.vertical, 4)
        }
    }

    private func listRow<Marker: View>(marker: Marker, text: String, indent: Int, struck: Bool = false) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            marker
                .font(TideFont.sans(16, relativeTo: .body))
                .foregroundStyle(Tide.inkMid)
                .frame(minWidth: 14, alignment: .leading)
            Text(Self.inline(text))
                .font(TideFont.sans(16, relativeTo: .body))
                .foregroundStyle(struck ? Tide.inkFaint : Tide.ink)
                .strikethrough(struck, color: Tide.inkFaint)
                .lineSpacing(3)
        }
        .padding(.leading, CGFloat(indent) * 18)
    }

    // MARK: - Parsing

    private static func parse(_ text: String) -> [Block] {
        var blocks: [Block] = []
        var paragraph: [String] = []
        var codeLines: [String]? = nil
        var nextID = 0
        func id() -> Int { defer { nextID += 1 }; return nextID }
        func flush() {
            if !paragraph.isEmpty {
                blocks.append(.paragraph(paragraph.joined(separator: "\n"), id: id()))
                paragraph = []
            }
        }

        var lines = text.components(separatedBy: "\n")
        // YAML front matter.
        if lines.first?.trimmingCharacters(in: .whitespaces) == "---",
           let end = lines.dropFirst().firstIndex(where: { $0.trimmingCharacters(in: .whitespaces) == "---" }) {
            blocks.append(.frontmatter(lines[1..<end].joined(separator: "\n"), id: id()))
            lines = Array(lines[(end + 1)...])
        }

        for raw in lines {
            let line = raw.replacingOccurrences(of: "\t", with: "    ")
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if var code = codeLines {
                if trimmed.hasPrefix("```") || trimmed.hasPrefix("~~~") {
                    blocks.append(.code(code.joined(separator: "\n"), id: id()))
                    codeLines = nil
                } else {
                    code.append(raw)
                    codeLines = code
                }
                continue
            }
            if trimmed.hasPrefix("```") || trimmed.hasPrefix("~~~") {
                flush(); codeLines = []; continue
            }
            if trimmed.isEmpty { flush(); continue }

            let indent = (line.prefix(while: { $0 == " " }).count) / 2
            if TideTextDiff.isHeading(trimmed) {
                flush()
                let level = trimmed.prefix(while: { $0 == "#" }).count
                blocks.append(.heading(level: level, text: TideTextDiff.headingTitle(trimmed), id: id()))
            } else if ["---", "***", "___"].contains(trimmed) {
                flush(); blocks.append(.rule(id: id()))
            } else if let task = taskItem(trimmed) {
                flush(); blocks.append(.task(task.text, done: task.done, indent: indent, id: id()))
            } else if trimmed.hasPrefix("- ") || trimmed.hasPrefix("* ") || trimmed.hasPrefix("+ ") {
                flush(); blocks.append(.bullet(String(trimmed.dropFirst(2)), indent: indent, id: id()))
            } else if let ordered = orderedItem(trimmed) {
                flush(); blocks.append(.ordered(ordered.text, marker: ordered.marker, indent: indent, id: id()))
            } else if trimmed.hasPrefix(">") {
                flush(); blocks.append(.quote(String(trimmed.dropFirst()).trimmingCharacters(in: .whitespaces), id: id()))
            } else {
                paragraph.append(trimmed)
            }
        }
        if let code = codeLines { blocks.append(.code(code.joined(separator: "\n"), id: id())) }
        flush()
        return blocks
    }

    private static func taskItem(_ line: String) -> (text: String, done: Bool)? {
        for prefix in ["- [ ] ", "* [ ] ", "- [x] ", "* [x] ", "- [X] ", "* [X] "] where line.hasPrefix(prefix) {
            return (String(line.dropFirst(prefix.count)), !prefix.contains("[ ]"))
        }
        return nil
    }

    private static func orderedItem(_ line: String) -> (text: String, marker: String)? {
        let digits = line.prefix(while: \.isNumber)
        guard !digits.isEmpty, digits.count < 4 else { return nil }
        let rest = line.dropFirst(digits.count)
        guard rest.hasPrefix(". ") || rest.hasPrefix(") ") else { return nil }
        return (String(rest.dropFirst(2)), String(digits) + ".")
    }

    /// Inline Markdown (bold, italic, code, links), with `[[wiki links]]`
    /// shown as their target and tinted like links.
    static func inline(_ text: String) -> AttributedString {
        var source = text
        // [[target|alias]] → **alias**-ish marker we can tint afterwards.
        var wikiTitles: [String] = []
        while let open = source.range(of: "[["),
              let close = source.range(of: "]]", range: open.upperBound..<source.endIndex) {
            let inner = String(source[open.upperBound..<close.lowerBound])
            let title = inner.components(separatedBy: "|").last ?? inner
            wikiTitles.append(title)
            source.replaceSubrange(open.lowerBound..<close.upperBound, with: title)
        }
        var result = (try? AttributedString(
            markdown: source,
            options: AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        )) ?? AttributedString(source)
        for title in wikiTitles where !title.isEmpty {
            if let range = result.range(of: title) {
                result[range].foregroundColor = Tide.behind
            }
        }
        return result
    }
}

#Preview {
    ScrollView {
        TideMarkdownView(text: """
        # Sunday, Nov 1

        ## Morning
        - Walked the beach, 40 min
        - Recorded 3 voice memos
          → see [[voice-memo-pipeline]]

        ## Today
        - [x] Ship Tide mockups
        - [ ] Port tokens to **SwiftUI**
        """)
        .padding(20)
    }
    .background(Tide.card)
}

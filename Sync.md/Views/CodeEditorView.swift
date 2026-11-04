import SwiftUI
import UIKit

/// A UITextView-backed editor with debounced syntax highlighting.
struct CodeEditorView: UIViewRepresentable {
    @Binding var text: String
    let language: SyntaxLanguage
    /// Draw a line-number gutter on the leading edge.
    var showsLineNumbers = true
    /// 1-based lines that differ from the last commit (green gutter bar).
    var changedLines: Set<Int> = []
    @Environment(\.colorScheme) private var colorScheme
    // Dynamic Type (Issue #16): SwiftUI re-invokes updateUIView whenever the
    // content size category changes; the coordinator rescales the editor's
    // fonts through BEditorType at that point (clamped, see BrutalDesignSystem).
    @Environment(\.sizeCategory) private var sizeCategory

    func makeUIView(context: Context) -> UITextView {
        // TextKit 1 so the gutter can read line fragments from the layout manager.
        // Built through the designated initializer: `UITextView(usingTextLayoutManager:)`
        // skips the subclass's own init, leaving its stored properties uninitialized.
        let tv = GutterTextView.makeTextKit1()
        tv.showsLineNumbers = showsLineNumbers
        tv.delegate = context.coordinator
        tv.isEditable = true
        tv.isScrollEnabled = true
        tv.autocorrectionType = .no
        tv.autocapitalizationType = .none
        tv.smartDashesType = .no
        tv.smartQuotesType = .no
        tv.smartInsertDeleteType = .no
        tv.backgroundColor = .clear
        tv.tintColor = UIColor(Tide.behind)
        // Bottom inset leaves room for the editor's floating saved pill.
        tv.textContainerInset = UIEdgeInsets(top: 14, left: 12, bottom: 88, right: 12)
        return tv
    }

    func updateUIView(_ uiView: UITextView, context: Context) {
        let coord = context.coordinator
        coord.parent = self

        if let gutter = uiView as? GutterTextView {
            gutter.showsLineNumbers = showsLineNumbers
            gutter.changedLines = changedLines
        }

        // A rename can turn a note into (or out of) Markdown.
        let wantsBar = language == .markdown
        if wantsBar != (uiView.inputAccessoryView is MarkdownAccessoryBar) {
            uiView.inputAccessoryView = wantsBar ? MarkdownAccessoryBar(textView: uiView) : nil
            uiView.reloadInputViews()
        }

        let textChanged = uiView.text != text
        let schemeChanged = coord.lastColorScheme != colorScheme
        let sizeCategoryChanged = coord.lastSizeCategory != sizeCategory
        guard textChanged || schemeChanged || sizeCategoryChanged else { return }

        coord.applyHighlighting(
            to: uiView,
            overrideText: textChanged ? text : nil,
            sizeCategory: sizeCategory
        )
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    // MARK: - Coordinator

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: CodeEditorView
        var lastColorScheme: ColorScheme = .light
        // nil until the first update so even an empty document receives the
        // scaled monospaced typing attributes once.
        var lastSizeCategory: ContentSizeCategory? = nil
        private var debounce: DispatchWorkItem?

        init(_ parent: CodeEditorView) { self.parent = parent }

        // Called both on external text changes and after the debounce period.
        func applyHighlighting(
            to textView: UITextView,
            overrideText: String? = nil,
            sizeCategory: ContentSizeCategory = .large
        ) {
            let content = overrideText ?? textView.text ?? ""
            let theme = parent.colorScheme == .dark ? SyntaxTheme.dark : SyntaxTheme.light
            let selection = textView.selectedRange
            let offset = textView.contentOffset

            // Issue #16: editor text scales with Dynamic Type, clamped by
            // BEditorType (min 13 / max 22 — rationale in BrutalDesignSystem).
            // SyntaxHighlighter emits a uniform 13pt monospaced base font, so
            // every font run is rescaled proportionally; weights, the
            // monospaced design, and all foreground colors survive intact.
            let editorSize = BEditorType.scaledSize(for: sizeCategory)
            textView.attributedText = Self.rescaled(
                SyntaxHighlighter.highlight(content, language: parent.language, theme: theme),
                to: editorSize
            )
            textView.typingAttributes = [
                .font: UIFont.monospacedSystemFont(ofSize: editorSize, weight: .regular),
                .foregroundColor: theme.plain
            ]

            if overrideText != nil {
                // New file loaded — reset to top
                textView.selectedRange = NSRange(location: 0, length: 0)
            } else {
                // Re-highlight only — preserve cursor and scroll
                let length = (textView.text ?? "").utf16.count
                let safeLoc = min(selection.location, length)
                let safeLen = min(selection.length, length - safeLoc)
                textView.selectedRange = NSRange(location: safeLoc, length: safeLen)
                textView.setContentOffset(offset, animated: false)
            }

            lastColorScheme = parent.colorScheme
            lastSizeCategory = sizeCategory
            (textView as? GutterTextView)?.refreshGutter()
        }

        /// Re-scales every font in the highlighted attributed string to
        /// `targetSize`. `UIFont.withSize` preserves the monospaced design,
        /// weight, and family of each run.
        private static func rescaled(_ source: NSAttributedString, to targetSize: CGFloat) -> NSAttributedString {
            let factor = targetSize / BEditorType.baseSize
            guard factor != 1 else { return source }
            let scaled = NSMutableAttributedString(attributedString: source)
            let fullRange = NSRange(location: 0, length: scaled.length)
            scaled.enumerateAttribute(.font, in: fullRange) { value, range, _ in
                guard let font = value as? UIFont else { return }
                scaled.addAttribute(.font, value: font.withSize(font.pointSize * factor), range: range)
            }
            return scaled
        }

        // MARK: UITextViewDelegate

        func textViewDidChange(_ textView: UITextView) {
            parent.text = textView.text ?? ""
            (textView as? GutterTextView)?.refreshGutter()

            debounce?.cancel()
            let item = DispatchWorkItem { [weak self, weak textView] in
                guard let self, let textView else { return }
                self.applyHighlighting(to: textView)
            }
            debounce = item
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: item)
        }

        func textViewDidChangeSelection(_ textView: UITextView) {
            (textView as? GutterTextView)?.updateCursorLine(active: textView.isFirstResponder)
        }

        func textViewDidBeginEditing(_ textView: UITextView) {
            (textView as? GutterTextView)?.updateCursorLine(active: true)
        }

        func textViewDidEndEditing(_ textView: UITextView) {
            (textView as? GutterTextView)?.updateCursorLine(active: false)
        }
    }
}

// MARK: - Line-number gutter

/// UITextView that draws right-aligned line numbers (one per logical line,
/// not per wrapped fragment) in its leading inset, plus a green bar and tint
/// behind lines that differ from the last commit and a blue bar and tint
/// behind the line the cursor is on.
final class GutterTextView: UITextView {
    var showsLineNumbers = true {
        didSet { if showsLineNumbers != oldValue { refreshGutter() } }
    }
    var changedLines: Set<Int> = [] {
        didSet { if changedLines != oldValue { setNeedsDisplay() } }
    }
    /// 1-based line holding the cursor while editing; nil when not editing.
    private(set) var cursorLine: Int? {
        didSet { if cursorLine != oldValue { setNeedsDisplay() } }
    }

    /// Recomputes `cursorLine` from the selection.
    func updateCursorLine(active: Bool) {
        guard active, showsLineNumbers else {
            cursorLine = nil
            return
        }
        let ns = (text ?? "") as NSString
        let location = min(selectedRange.location, ns.length)
        cursorLine = ns.substring(to: location).utf16.reduce(into: 1) { count, unit in
            if unit == 0x0A { count += 1 }
        }
    }

    private static let baseInset: CGFloat = 12
    private var gutterWidth: CGFloat = 0
    /// The text view only weakly reaches its storage through the layout manager.
    private var ownedTextStorage: NSTextStorage?

    /// A TextKit 1 text view (layout manager + container) built via the
    /// designated initializer so this subclass's stored properties are set up.
    static func makeTextKit1() -> GutterTextView {
        let storage = NSTextStorage()
        let layoutManager = NSLayoutManager()
        storage.addLayoutManager(layoutManager)
        let container = NSTextContainer(size: CGSize(width: 0, height: CGFloat.greatestFiniteMagnitude))
        container.widthTracksTextView = true
        layoutManager.addTextContainer(container)
        let view = GutterTextView(frame: .zero, textContainer: container)
        view.ownedTextStorage = storage
        return view
    }

    override init(frame: CGRect, textContainer: NSTextContainer?) {
        super.init(frame: frame, textContainer: textContainer)
        contentMode = .redraw
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        contentMode = .redraw
    }

    private var numberFont: UIFont {
        let size = max(10, ((font ?? typingAttributes[.font] as? UIFont)?.pointSize ?? 13) - 2)
        return UIFont(name: "GeistMono-Regular", size: size) ?? .monospacedDigitSystemFont(ofSize: size, weight: .regular)
    }

    /// Recomputes the gutter width from the line count and redraws.
    func refreshGutter() {
        let width: CGFloat
        if showsLineNumbers {
            let lines = max(1, (text ?? "").reduce(into: 1) { count, ch in if ch == "\n" { count += 1 } })
            let digits = max(2, String(lines).count)
            let sample = String(repeating: "8", count: digits) as NSString
            width = ceil(sample.size(withAttributes: [.font: numberFont]).width) + 14
        } else {
            width = 0
        }
        if width != gutterWidth {
            gutterWidth = width
            var inset = textContainerInset
            inset.left = Self.baseInset + width
            textContainerInset = inset
        }
        setNeedsDisplay()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if showsLineNumbers { setNeedsDisplay() }
    }

    override var contentOffset: CGPoint {
        didSet { if showsLineNumbers && contentOffset != oldValue { setNeedsDisplay() } }
    }

    override func draw(_ rect: CGRect) {
        super.draw(rect)
        guard showsLineNumbers, gutterWidth > 0 else { return }
        let layout = layoutManager
        let container = textContainer
        let storage = textStorage
        let ns = storage.string as NSString
        let inset = textContainerInset
        let attributes: [NSAttributedString.Key: Any] = [
            .font: numberFont,
            .foregroundColor: UIColor(Tide.inkFaint)
        ]
        let changedColor = UIColor(Tide.synced)
        let cursorColor = UIColor(Tide.behind)
        let numberRight = Self.baseInset + gutterWidth - 10

        /// Row tint + leading bar for every fragment of a changed or current line.
        func drawRowMarker(_ number: Int, lineRect: CGRect) {
            let color: UIColor
            if number == cursorLine {
                color = cursorColor
            } else if changedLines.contains(number) {
                color = changedColor
            } else {
                return
            }
            let y = lineRect.minY + inset.top
            color.withAlphaComponent(0.07).setFill()
            UIBezierPath(rect: CGRect(x: 0, y: y, width: bounds.width, height: lineRect.height)).fill()
            color.setFill()
            UIBezierPath(rect: CGRect(x: 2, y: y, width: 3, height: lineRect.height)).fill()
        }

        func drawNumber(_ number: Int, lineRect: CGRect) {
            let y = lineRect.minY + inset.top
            let label = String(number) as NSString
            let size = label.size(withAttributes: attributes)
            let baselineOffset = max(0, (lineRect.height - size.height) / 2)
            label.draw(at: CGPoint(x: numberRight - size.width, y: y + baselineOffset), withAttributes: attributes)
        }

        guard ns.length > 0 else {
            let rect = CGRect(x: 0, y: 0, width: 1, height: numberFont.lineHeight * 1.25)
            drawRowMarker(1, lineRect: rect)
            drawNumber(1, lineRect: rect)
            return
        }

        // Visible rect in text-container coordinates.
        let visible = CGRect(x: 0, y: bounds.minY - inset.top, width: bounds.width, height: bounds.height)
        let glyphRange = layout.glyphRange(forBoundingRect: visible, in: container)
        let firstChar = layout.characterIndexForGlyph(at: glyphRange.location)

        // Line number of the first visible character.
        var lineNumber = 1
        if firstChar > 0 {
            var index = 0
            while index < firstChar {
                let range = ns.lineRange(for: NSRange(location: index, length: 0))
                if NSMaxRange(range) > firstChar { break }
                index = NSMaxRange(range)
                lineNumber += 1
            }
        }

        layout.enumerateLineFragments(forGlyphRange: glyphRange) { lineRect, _, _, fragmentGlyphs, _ in
            let charIndex = layout.characterIndexForGlyph(at: fragmentGlyphs.location)
            let startsLine = charIndex == 0 || ns.character(at: charIndex - 1) == 0x0A
            drawRowMarker(lineNumber, lineRect: lineRect)
            if startsLine {
                // Count lines skipped between the first visible character and here.
                drawNumber(lineNumber, lineRect: lineRect)
            }
            let fragmentChars = layout.characterRange(forGlyphRange: fragmentGlyphs, actualGlyphRange: nil)
            let end = NSMaxRange(fragmentChars)
            if end > 0, end <= ns.length, ns.character(at: end - 1) == 0x0A {
                lineNumber += 1
            }
        }

        let extra = layout.extraLineFragmentRect
        if !extra.isEmpty, extra.maxY >= visible.minY, extra.minY <= visible.maxY {
            drawRowMarker(lineNumber, lineRect: extra)
            drawNumber(lineNumber, lineRect: extra)
        }
    }
}

// MARK: - Markdown accessory bar

/// Tide keyboard bar for Markdown files: one-tap heading, checklist, wiki
/// link, bold, code and indent. Edits go through UITextInput so undo works,
/// and the delegate is notified so the binding and highlighting follow.
private final class MarkdownAccessoryBar: UIView {
    private weak var textView: UITextView?

    private enum Action {
        case linePrefix(String)
        case wrap(String, String)
        case insert(String)
        case dismiss
    }

    init(textView: UITextView) {
        self.textView = textView
        super.init(frame: CGRect(x: 0, y: 0, width: 320, height: 56))
        autoresizingMask = .flexibleWidth
        backgroundColor = UIColor(Tide.well)

        let scroll = UIScrollView()
        scroll.showsHorizontalScrollIndicator = false
        scroll.translatesAutoresizingMaskIntoConstraints = false
        addSubview(scroll)

        let stack = UIStackView()
        stack.axis = .horizontal
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)

        let items: [(String, String, Action, Bool)] = [
            ("#", String(localized: "Heading"), .linePrefix("# "), true),
            ("[ ]", String(localized: "Checklist item"), .linePrefix("- [ ] "), true),
            ("[[ ]]", String(localized: "Wiki link"), .wrap("[[", "]]"), true),
            ("B", String(localized: "Bold"), .wrap("**", "**"), false),
            ("`", String(localized: "Code"), .wrap("`", "`"), true),
            ("⇥", String(localized: "Indent"), .insert("\t"), true),
        ]
        for (title, label, action, mono) in items {
            stack.addArrangedSubview(makeButton(title: title, label: label, mono: mono, action: action))
        }

        let done = makeButton(title: nil, label: String(localized: "Hide keyboard"), mono: false, action: .dismiss)
        addSubview(done)

        NSLayoutConstraint.activate([
            scroll.leadingAnchor.constraint(equalTo: leadingAnchor),
            scroll.topAnchor.constraint(equalTo: topAnchor),
            scroll.bottomAnchor.constraint(equalTo: bottomAnchor),
            scroll.trailingAnchor.constraint(equalTo: done.leadingAnchor, constant: -6),
            done.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -10),
            done.centerYAnchor.constraint(equalTo: centerYAnchor),

            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 10),
            stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -10),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -8),
            stack.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor, constant: -16),
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    private func makeButton(title: String?, label: String, mono: Bool, action: Action) -> UIButton {
        var config = UIButton.Configuration.filled()
        config.baseBackgroundColor = UIColor(Tide.card)
        config.baseForegroundColor = UIColor(Tide.ink)
        config.background.cornerRadius = 10
        config.cornerStyle = .fixed
        config.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: 10, bottom: 0, trailing: 10)
        if let title {
            let font: UIFont = mono
                ? (UIFont(name: "GeistMono-SemiBold", size: 14) ?? .monospacedSystemFont(ofSize: 14, weight: .semibold))
                : (UIFont(name: "Geist-Bold", size: 15) ?? .systemFont(ofSize: 15, weight: .bold))
            config.attributedTitle = AttributedString(title, attributes: AttributeContainer([.font: font]))
        } else {
            config.image = UIImage(systemName: "keyboard.chevron.compact.down")
            config.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 15, weight: .semibold)
        }

        let button = UIButton(configuration: config, primaryAction: UIAction { [weak self] _ in
            self?.perform(action)
        })
        button.accessibilityLabel = label
        button.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            button.widthAnchor.constraint(greaterThanOrEqualToConstant: 44),
            button.heightAnchor.constraint(equalToConstant: 40),
        ])
        return button
    }

    private func perform(_ action: Action) {
        guard let tv = textView else { return }
        let ns = (tv.text ?? "") as NSString
        let selection = tv.selectedRange

        switch action {
        case .dismiss:
            tv.resignFirstResponder()
            return
        case .insert(let text):
            tv.insertText(text)
        case .linePrefix(let prefix):
            let line = ns.lineRange(for: NSRange(location: selection.location, length: 0))
            guard let start = tv.position(from: tv.beginningOfDocument, offset: line.location),
                  let range = tv.textRange(from: start, to: start) else { return }
            tv.replace(range, withText: prefix)
            tv.selectedRange = NSRange(location: selection.location + (prefix as NSString).length,
                                       length: selection.length)
        case .wrap(let open, let close):
            let selected = ns.substring(with: selection)
            guard let range = tv.selectedTextRange else { return }
            tv.replace(range, withText: open + selected + close)
            tv.selectedRange = NSRange(location: selection.location + (open as NSString).length,
                                       length: (selected as NSString).length)
        }
        tv.delegate?.textViewDidChange?(tv)
    }
}

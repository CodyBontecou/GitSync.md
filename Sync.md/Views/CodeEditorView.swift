import SwiftUI
import UIKit

/// A UITextView-backed editor with debounced syntax highlighting.
struct CodeEditorView: UIViewRepresentable {
    @Binding var text: String
    let language: SyntaxLanguage
    @Environment(\.colorScheme) private var colorScheme
    // Dynamic Type (Issue #16): SwiftUI re-invokes updateUIView whenever the
    // content size category changes; the coordinator rescales the editor's
    // fonts through BEditorType at that point (clamped, see BrutalDesignSystem).
    @Environment(\.sizeCategory) private var sizeCategory

    func makeUIView(context: Context) -> UITextView {
        let tv = UITextView()
        tv.delegate = context.coordinator
        tv.isEditable = true
        tv.isScrollEnabled = true
        tv.autocorrectionType = .no
        tv.autocapitalizationType = .none
        tv.smartDashesType = .no
        tv.smartQuotesType = .no
        tv.smartInsertDeleteType = .no
        tv.backgroundColor = .clear
        tv.textContainerInset = UIEdgeInsets(top: 8, left: 4, bottom: 8, right: 4)
        return tv
    }

    func updateUIView(_ uiView: UITextView, context: Context) {
        let coord = context.coordinator
        coord.parent = self

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

            debounce?.cancel()
            let item = DispatchWorkItem { [weak self, weak textView] in
                guard let self, let textView else { return }
                self.applyHighlighting(to: textView)
            }
            debounce = item
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: item)
        }
    }
}

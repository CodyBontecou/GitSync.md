import SwiftUI
import UIKit

/// A UITextView-backed editor with debounced syntax highlighting.
struct CodeEditorView: UIViewRepresentable {
    @Binding var text: String
    let language: SyntaxLanguage
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

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
        tv.adjustsFontForContentSizeCategory = true
        tv.backgroundColor = .clear
        tv.textContainerInset = UIEdgeInsets(top: 8, left: 4, bottom: 8, right: 4)
        return tv
    }

    func updateUIView(_ uiView: UITextView, context: Context) {
        let coord = context.coordinator
        coord.parent = self

        let textChanged = uiView.text != text
        let schemeChanged = coord.lastColorScheme != colorScheme
        let typeSizeChanged = coord.lastDynamicTypeSize != dynamicTypeSize
        guard textChanged || schemeChanged || typeSizeChanged else { return }

        coord.applyHighlighting(to: uiView, overrideText: textChanged ? text : nil)
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    // MARK: - Coordinator

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: CodeEditorView
        var lastColorScheme: ColorScheme = .light
        var lastDynamicTypeSize: DynamicTypeSize = .medium
        private var debounce: DispatchWorkItem?

        init(_ parent: CodeEditorView) { self.parent = parent }

        // Called both on external text changes and after the debounce period.
        func applyHighlighting(to textView: UITextView, overrideText: String? = nil) {
            let content = overrideText ?? textView.text ?? ""
            let theme = parent.colorScheme == .dark ? SyntaxTheme.dark : SyntaxTheme.light
            let font = UIFont.brutalScaledMonospaced(compatibleWith: textView.traitCollection)
            let selection = textView.selectedRange
            let offset = textView.contentOffset

            textView.attributedText = SyntaxHighlighter.highlight(content, language: parent.language, theme: theme, font: font)
            textView.typingAttributes = [
                .font: font,
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
            lastDynamicTypeSize = parent.dynamicTypeSize
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

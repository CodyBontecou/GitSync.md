import UIKit

// MARK: - Theme

struct SyntaxTheme {
    let plain: UIColor
    let keyword: UIColor
    let string: UIColor
    let comment: UIColor
    let number: UIColor
    let type: UIColor
    let function_: UIColor
    let property: UIColor

    // VSCode Dark+
    static let dark = SyntaxTheme(
        plain:     .vsHex(0xD4D4D4),
        keyword:   .vsHex(0x569CD6),
        string:    .vsHex(0xCE9178),
        comment:   .vsHex(0x6A9955),
        number:    .vsHex(0xB5CEA8),
        type:      .vsHex(0x4EC9B0),
        function_: .vsHex(0xDCDCAA),
        property:  .vsHex(0x9CDCFE)
    )

    // VSCode Light+
    static let light = SyntaxTheme(
        plain:     .vsHex(0x000000),
        keyword:   .vsHex(0x0000FF),
        string:    .vsHex(0xA31515),
        comment:   .vsHex(0x008000),
        number:    .vsHex(0x098658),
        type:      .vsHex(0x267F99),
        function_: .vsHex(0x795E26),
        property:  .vsHex(0x001080)
    )
}

private extension UIColor {
    static func vsHex(_ v: UInt32) -> UIColor {
        UIColor(
            red:   CGFloat((v >> 16) & 0xFF) / 255,
            green: CGFloat((v >>  8) & 0xFF) / 255,
            blue:  CGFloat( v        & 0xFF) / 255,
            alpha: 1
        )
    }
}

extension UIFont {
    static func brutalScaledMonospaced(
        size: CGFloat = 13,
        weight: UIFont.Weight = .regular,
        textStyle: UIFont.TextStyle = .body,
        compatibleWith traitCollection: UITraitCollection? = nil
    ) -> UIFont {
        let baseFont = UIFont.monospacedSystemFont(ofSize: size, weight: weight)
        return UIFontMetrics(forTextStyle: textStyle).scaledFont(for: baseFont, compatibleWith: traitCollection)
    }
}

// MARK: - Language

enum SyntaxLanguage {
    case swift_, markdown, json, yaml, javascript, typescript, python, bash, html, css, unknown

    static func detect(fileExtension ext: String) -> SyntaxLanguage {
        switch ext.lowercased() {
        case "swift":                           return .swift_
        case "md", "markdown":                  return .markdown
        case "json":                            return .json
        case "yaml", "yml":                     return .yaml
        case "js", "mjs", "jsx", "cjs":        return .javascript
        case "ts", "tsx":                       return .typescript
        case "py", "pyi":                       return .python
        case "sh", "bash", "zsh", "fish":      return .bash
        case "html", "htm", "xhtml", "xml", "svg": return .html
        case "css", "scss", "less":            return .css
        default:                                return .unknown
        }
    }
}

// MARK: - Highlighter

enum SyntaxHighlighter {

    private static let maxBytes = 150_000

    /// Returns a syntax-highlighted attributed string for `text`.
    static func highlight(
        _ text: String,
        language: SyntaxLanguage,
        theme: SyntaxTheme,
        font: UIFont = .brutalScaledMonospaced()
    ) -> NSAttributedString {
        let base: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: theme.plain
        ]
        let result = NSMutableAttributedString(string: text, attributes: base)
        guard !text.isEmpty, text.utf8.count <= maxBytes else { return result }

        let nsText = text as NSString
        let fullRange = NSRange(location: 0, length: nsText.length)

        for rule in rules(for: language) {
            guard let regex = try? NSRegularExpression(pattern: rule.pattern, options: rule.options) else { continue }
            let color = rule.color(theme)
            for match in regex.matches(in: text, options: [], range: fullRange) {
                // Use capture group 1 when present (strips surrounding non-code chars), else whole match.
                let range = match.numberOfRanges > 1 ? match.range(at: 1) : match.range
                if range.location != NSNotFound {
                    result.addAttribute(.foregroundColor, value: color, range: range)
                }
            }
        }

        return result
    }

    // MARK: - Rule definition

    private struct Rule {
        let pattern: String
        let color: (SyntaxTheme) -> UIColor
        let options: NSRegularExpression.Options

        init(_ pattern: String, _ color: @escaping (SyntaxTheme) -> UIColor, _ options: NSRegularExpression.Options = []) {
            self.pattern = pattern
            self.color = color
            self.options = options
        }
    }

    private static func rules(for language: SyntaxLanguage) -> [Rule] {
        switch language {
        case .swift_:                  return swiftRules
        case .json:                    return jsonRules
        case .yaml:                    return yamlRules
        case .javascript:              return jsRules
        case .typescript:              return jsRules
        case .python:                  return pythonRules
        case .bash:                    return bashRules
        case .html:                    return htmlRules
        case .css:                     return cssRules
        case .markdown:                return markdownRules
        case .unknown:                 return genericRules
        }
    }

    // MARK: Rules — later entries override earlier ones (strings/comments win over keywords)

    private static let swiftRules: [Rule] = [
        .init(#"\b(?:import|class|struct|enum|protocol|extension|func|var|let|return|if|else|guard|switch|case|default|for|while|in|break|continue|throw|throws|rethrows|try|catch|do|defer|lazy|static|final|open|public|internal|fileprivate|private|override|mutating|nonmutating|weak|unowned|init|deinit|self|super|true|false|nil|where|typealias|associatedtype|some|any|async|await|actor|nonisolated|consuming|borrowing)\b"#, { $0.keyword }),
        .init(#"@\w+"#,                                              { $0.keyword }),
        .init(#"\b[A-Z][a-zA-Z0-9_]*\b"#,                          { $0.type }),
        .init(#"\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|0x[0-9a-fA-F]+\b"#, { $0.number }),
        .init(#""{3}[\s\S]*?"{3}"#,                                 { $0.string }, .dotMatchesLineSeparators),
        .init(#""(?:[^"\\]|\\.)*""#,                                { $0.string }),
        .init(#"//[^\n]*"#,                                         { $0.comment }),
        .init(#"/\*[\s\S]*?\*/"#,                                   { $0.comment }, .dotMatchesLineSeparators),
    ]

    private static let jsonRules: [Rule] = [
        .init(#"\b-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b"#,            { $0.number }),
        .init(#"\b(?:true|false|null)\b"#,                          { $0.keyword }),
        .init(#""(?:[^"\\]|\\.)*""#,                                { $0.string }),
        // Key strings (followed by colon) — overrides string color
        .init(#""(?:[^"\\]|\\.)*"(?=\s*:)"#,                       { $0.property }),
    ]

    private static let yamlRules: [Rule] = [
        .init(#"\b\d+(?:\.\d+)?\b"#,                               { $0.number }),
        .init(#"\b(?:true|false|null|yes|no|on|off)\b"#,            { $0.keyword }, .caseInsensitive),
        .init(#"[&*][a-zA-Z_]\w*"#,                                 { $0.type }),
        .init(#""[^"]*"|'[^']*'"#,                                  { $0.string }),
        // Keys: capture group 1 = key name only (no leading indent or colon)
        .init(#"^[ \t]*([a-zA-Z_][a-zA-Z0-9_\-]*)[ \t]*:"#,        { $0.property }, .anchorsMatchLines),
        .init(#"#[^\n]*"#,                                          { $0.comment }),
    ]

    private static let jsRules: [Rule] = [
        .init(#"\b(?:const|let|var|function|return|if|else|for|while|do|break|continue|switch|case|default|class|extends|import|export|from|new|this|typeof|instanceof|void|delete|in|of|try|catch|finally|throw|async|await|yield|static|get|set|null|undefined|true|false|type|interface|enum|declare|abstract|implements|readonly|override|as|satisfies)\b"#, { $0.keyword }),
        .init(#"\b[A-Z][a-zA-Z0-9_]*\b"#,                          { $0.type }),
        .init(#"\b\d+(?:\.\d+)?\b|0x[0-9a-fA-F]+\b"#,              { $0.number }),
        .init(#"`(?:[^`\\]|\\.)*`"#,                                { $0.string }, .dotMatchesLineSeparators),
        .init(#""(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'"#,              { $0.string }),
        .init(#"//[^\n]*"#,                                         { $0.comment }),
        .init(#"/\*[\s\S]*?\*/"#,                                   { $0.comment }, .dotMatchesLineSeparators),
    ]

    private static let pythonRules: [Rule] = [
        .init(#"\b(?:def|class|return|if|elif|else|for|while|in|not|and|or|import|from|as|pass|break|continue|try|except|finally|raise|with|yield|lambda|True|False|None|global|nonlocal|del|assert|async|await|is)\b"#, { $0.keyword }),
        .init(#"@\w+"#,                                             { $0.keyword }),
        .init(#"\b[A-Z][a-zA-Z0-9_]*\b"#,                          { $0.type }),
        .init(#"\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b"#,              { $0.number }),
        .init(#"(?:"""|''')[\s\S]*?(?:"""|''')"#,                   { $0.string }, .dotMatchesLineSeparators),
        .init(#""(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'"#,              { $0.string }),
        .init(#"#[^\n]*"#,                                          { $0.comment }),
    ]

    private static let bashRules: [Rule] = [
        .init(#"\b(?:if|then|else|elif|fi|for|while|do|done|case|esac|function|return|exit|in|echo|export|source|local|readonly|declare|shift|set|unset|trap|alias|break|continue)\b"#, { $0.keyword }),
        .init(#"\b\d+\b"#,                                          { $0.number }),
        .init(#"\$\{?[a-zA-Z_][a-zA-Z0-9_]*\}?"#,                  { $0.property }),
        .init(#""(?:[^"\\]|\\.)*"|'[^']*'"#,                        { $0.string }),
        .init(#"#[^\n]*"#,                                          { $0.comment }),
    ]

    private static let htmlRules: [Rule] = [
        .init(#"<!DOCTYPE[^>]*>"#,                                  { $0.comment }, .caseInsensitive),
        .init(#"<!--[\s\S]*?-->"#,                                  { $0.comment }, .dotMatchesLineSeparators),
        // Group 1 = tag name only
        .init(#"</?([a-zA-Z][a-zA-Z0-9-]*)"#,                      { $0.keyword }),
        // Group 1 = attribute name only (no leading space)
        .init(#"\s([a-zA-Z-]+)(?==)"#,                             { $0.property }),
        .init(#"="(?:[^"\\]|\\.)*"|='[^']*'"#,                     { $0.string }),
    ]

    private static let cssRules: [Rule] = [
        .init(#"/\*[\s\S]*?\*/"#,                                   { $0.comment }, .dotMatchesLineSeparators),
        .init(#"@[a-zA-Z-]+"#,                                      { $0.keyword }),
        .init(#"::?[a-zA-Z-]+"#,                                    { $0.function_ }),
        // Group 1 = property name only
        .init(#"([a-z-]+)(?=\s*:)"#,                                { $0.property }),
        .init(#"#[0-9a-fA-F]{3,8}\b"#,                              { $0.number }),
        .init(#"\b\d+(?:\.\d+)?(?:px|em|rem|vh|vw|%|pt|s|ms|deg|fr|ch|ex|vmin|vmax)?\b"#, { $0.number }),
        .init(#"\b(?:auto|none|inherit|initial|unset|normal|bold|italic|center|left|right|flex|grid|block|inline|absolute|relative|fixed|sticky|solid|dashed|dotted|transparent|currentColor)\b"#, { $0.keyword }),
        .init(#""[^"]*"|'[^']*'"#,                                  { $0.string }),
    ]

    private static let markdownRules: [Rule] = [
        .init(#"```[\s\S]*?```"#,                                   { $0.string }, .dotMatchesLineSeparators),
        .init(#"`[^`\n]+`"#,                                        { $0.string }),
        .init(#"^#{1,6} .*$"#,                                      { $0.keyword }, .anchorsMatchLines),
        .init(#"\*\*[^*\n]+\*\*|__[^_\n]+__"#,                     { $0.type }),
        .init(#"\*[^*\n]+\*|_[^_\n]+_"#,                           { $0.function_ }),
        .init(#"!\[[^\]\n]*\]\([^\)\n]+\)"#,                        { $0.function_ }),
        .init(#"\[[^\]\n]+\]\([^\)\n]+\)"#,                         { $0.property }),
        .init(#"^>.*$"#,                                            { $0.comment }, .anchorsMatchLines),
        .init(#"^[ \t]*[-*+] "#,                                    { $0.keyword }, .anchorsMatchLines),
        .init(#"^[ \t]*\d+\. "#,                                    { $0.keyword }, .anchorsMatchLines),
        .init(#"^(?:---|\*\*\*|___)\s*$"#,                          { $0.comment }, .anchorsMatchLines),
    ]

    private static let genericRules: [Rule] = [
        .init(#"\b\d+(?:\.\d+)?\b"#,                               { $0.number }),
        .init(#""(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'"#,              { $0.string }),
        .init(#"//[^\n]*|#[^\n]*"#,                                 { $0.comment }),
        .init(#"/\*[\s\S]*?\*/"#,                                   { $0.comment }, .dotMatchesLineSeparators),
    ]
}

package com.bontecou.syncmd.services.files

/** Token ranges style the original text; they never insert characters or change cursor offsets. */
data class SyntaxToken(val start: Int, val end: Int, val kind: SyntaxKind)
enum class SyntaxKind { COMMENT, STRING, KEYWORD, NUMBER, HEADING, TAG }

object SyntaxHighlighter {
    const val MAX_HIGHLIGHT_CHARACTERS = 150_000
    fun language(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "swift" -> "swift"
        "md", "markdown" -> "markdown"
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "js", "mjs", "jsx", "cjs" -> "javascript"
        "ts", "tsx" -> "typescript"
        "py", "pyi" -> "python"
        "sh", "bash", "zsh", "fish" -> "bash"
        "html", "htm", "xhtml", "xml", "svg" -> "html"
        "css", "scss", "less" -> "css"
        else -> "text"
    }

    private val quoted = "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"
    private val keywords = "\\b(?:let|var|func|class|struct|enum|protocol|extension|import|public|private|static|return|if|else|for|while|in|switch|case|break|continue|try|catch|throw|throws|async|await|true|false|null|nil|const|function|export|default|interface|type|extends|new|this|def|from|as|with|lambda|pass|None|True|False|elif|except|finally|yield|fi|then|do|done|echo|local|select|where)\\b"
    private val number = "\\b(?:0[xX][0-9a-fA-F]+|[0-9]+(?:\\.[0-9]+)?)\\b"
    private fun group(name: String, pattern: String) = "(?<$name>$pattern)"
    private val patterns: Map<String, Regex> by lazy {
        val result = mutableMapOf<String, Regex>()
        for (language in listOf("swift", "javascript", "typescript", "css", "json", "yaml", "python", "bash", "html", "markdown")) {
            val comment = when (language) {
                "json" -> "(?!)"
                "html" -> "<!--[\\s\\S]*?-->"
                "python", "bash", "yaml" -> "#[^\\n]*"
                "markdown" -> "<!--[\\s\\S]*?-->"
                else -> "//[^\\n]*|/\\*[\\s\\S]*?\\*/"
            }
            val string = when (language) {
                "markdown" -> "```[\\s\\S]*?```|`[^`\\n]*`"
                "python" -> "\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|$quoted"
                else -> quoted
            }
            val heading = if (language == "markdown") "^#{1,6}[^\\n]*|\\*\\*[^*\\n]+\\*\\*" else "(?!)"
            val tag = when (language) {
                "html" -> "</?[A-Za-z][^>]*>"
                "css" -> "[.#][A-Za-z_][A-Za-z0-9_-]*|[A-Za-z-]+(?=\\s*:)"
                "yaml" -> "^[ \\t]*[A-Za-z_][A-Za-z0-9_-]*(?=\\s*:)"
                else -> "(?!)"
            }
            result[language] = Regex(listOf(group("comment", comment), group("string", string), group("heading", heading), group("tag", tag), group("keyword", if (language == "markdown") "(?!)" else keywords), group("number", if (language == "markdown") "(?!)" else number)).joinToString("|"), RegexOption.MULTILINE)
        }
        result
    }

    fun highlight(path: String, content: String): List<SyntaxToken> {
        if (content.length > MAX_HIGHLIGHT_CHARACTERS) return emptyList()
        val pattern = patterns[language(path)] ?: return emptyList()
        val kinds = listOf("comment" to SyntaxKind.COMMENT, "string" to SyntaxKind.STRING, "heading" to SyntaxKind.HEADING, "tag" to SyntaxKind.TAG, "keyword" to SyntaxKind.KEYWORD, "number" to SyntaxKind.NUMBER)
        return pattern.findAll(content).take(30_000).map { match ->
            val kind = kinds.first { match.groups[it.first] != null }.second
            SyntaxToken(match.range.first, match.range.last + 1, kind)
        }.toList()
    }
}

package com.bontecou.syncmd.services.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyntaxHighlighterTest {
    @Test fun `detects the same source language families as the iOS editor`() {
        val samples = mapOf("a.swift" to "swift", "a.MD" to "markdown", "a.json" to "json", "a.yaml" to "yaml", "a.jsx" to "javascript", "a.tsx" to "typescript", "a.py" to "python", "a.zsh" to "bash", "a.svg" to "html", "a.scss" to "css", "LICENSE" to "text")
        samples.forEach { (path, expected) -> assertThat(SyntaxHighlighter.language(path)).isEqualTo(expected) }
    }
    @Test fun `comment syntax inside a string stays part of the string and offsets preserve Unicode`() {
        val source = "let value = \"https://example.com/🌱\" // comment\nreturn 42"
        val tokens = SyntaxHighlighter.highlight("a.swift", source)
        assertThat(tokens.filter { it.kind == SyntaxKind.STRING }.map { source.substring(it.start, it.end) }).containsExactly("\"https://example.com/🌱\"")
        assertThat(tokens.filter { it.kind == SyntaxKind.COMMENT }.map { source.substring(it.start, it.end) }).containsExactly("// comment")
        assertThat(tokens.last().kind).isEqualTo(SyntaxKind.NUMBER)
        assertThat(tokens.zipWithNext().all { (a, b) -> a.end <= b.start }).isTrue()
    }
    @Test fun `all supported families tokenize without changing the input`() {
        for (path in listOf("a.swift", "a.md", "a.json", "a.yml", "a.js", "a.ts", "a.py", "a.sh", "a.html", "a.css")) {
            val source = "# heading\nlet text = \"hello\"\ntrue 123\n<!-- comment -->"
            val tokens = SyntaxHighlighter.highlight(path, source)
            assertThat(tokens).isNotEmpty()
            assertThat(tokens.all { it.start >= 0 && it.end <= source.length && it.start < it.end }).isTrue()
        }
    }
    @Test fun `large and unknown files bypass expensive highlighting`() {
        assertThat(SyntaxHighlighter.highlight("large.swift", "a".repeat(150_001))).isEmpty()
        assertThat(SyntaxHighlighter.highlight("image.dat", "let test = 1")).isEmpty()
    }
}

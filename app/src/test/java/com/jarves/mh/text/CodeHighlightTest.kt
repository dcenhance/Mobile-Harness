package com.jarves.mh.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor colouring must mark the right characters, never mark text it consumed as a whole
 * (a keyword inside a string or a comment), and always produce usable spans — ordered, non-overlapping
 * and inside the text — because the gutter beside it stays aligned only if the text is unchanged.
 */
class CodeHighlightTest {

    private fun spans(text: String, language: CodeLanguage) = CodeHighlight.spansFor(text, language)

    private fun kindsOf(text: String, language: CodeLanguage): List<Pair<String, TokenKind>> =
        spans(text, language).map { it.kind to text.substring(it.start, it.end) }.map { (k, v) -> v to k }

    private fun assertUsable(text: String, language: CodeLanguage) {
        val collected = spans(text, language)
        var previousEnd = -1
        collected.forEach { span ->
            assertTrue("span starts inside the text: $span", span.start in 0..text.length)
            assertTrue("span ends inside the text: $span", span.end in span.start..text.length)
            assertTrue("span is not empty: $span", span.end > span.start)
            assertTrue("spans are ordered and do not overlap: $span after end $previousEnd", span.start >= previousEnd)
            previousEnd = span.end
        }
    }

    @Test
    fun `file extensions map to the language the editor colours`() {
        assertEquals(CodeLanguage.KOTLIN, CodeHighlight.languageForFile("app/src/Main.kt"))
        assertEquals(CodeLanguage.KOTLIN, CodeHighlight.languageForFile("build.gradle.kts"))
        assertEquals(CodeLanguage.JAVA, CodeHighlight.languageForFile("Main.java"))
        assertEquals(CodeLanguage.JAVASCRIPT, CodeHighlight.languageForFile("ui/index.jsx"))
        assertEquals(CodeLanguage.TYPESCRIPT, CodeHighlight.languageForFile("ui/view.tsx"))
        assertEquals(CodeLanguage.PYTHON, CodeHighlight.languageForFile("scripts/run.py"))
        assertEquals(CodeLanguage.JSON, CodeHighlight.languageForFile("package.json"))
        assertEquals(CodeLanguage.XML, CodeHighlight.languageForFile("res/layout/main.xml"))
        assertEquals(CodeLanguage.CSS, CodeHighlight.languageForFile("styles/app.scss"))
        assertEquals(CodeLanguage.SHELL, CodeHighlight.languageForFile("deploy.sh"))
        assertEquals(CodeLanguage.YAML, CodeHighlight.languageForFile("ci/build.yml"))
        assertEquals(CodeLanguage.MARKDOWN, CodeHighlight.languageForFile("README.md"))
        assertEquals(CodeLanguage.PLAIN, CodeHighlight.languageForFile("LICENSE"))
        assertEquals(CodeLanguage.PLAIN, CodeHighlight.languageForFile("data.bin"))
    }

    @Test
    fun `kotlin keywords, types, strings, numbers and annotations are recognised`() {
        val source = """
            @Composable
            fun Box(var count: Int = 42) {
                val label = "hello"
                count += 1
            }
        """.trimIndent()
        val kinds = kindsOf(source, CodeLanguage.KOTLIN)
        assertTrue(kinds.contains("@Composable" to TokenKind.ANNOTATION))
        assertTrue(kinds.contains("fun" to TokenKind.KEYWORD))
        assertTrue(kinds.contains("fun" to TokenKind.KEYWORD))
        assertTrue(kinds.contains("Int" to TokenKind.TYPE))
        assertTrue(kinds.contains("val" to TokenKind.KEYWORD))
        assertTrue(kinds.contains("\"hello\"" to TokenKind.STRING))
        assertTrue(kinds.any { it.second == TokenKind.NUMBER && it.first == "42" })
        assertUsable(source, CodeLanguage.KOTLIN)
    }

    @Test
    fun `a keyword inside a string is part of the string, not a keyword`() {
        val source = "val text = \"fun val class\""
        val kinds = kindsOf(source, CodeLanguage.KOTLIN)
        assertTrue(kinds.contains("\"fun val class\"" to TokenKind.STRING))
        assertEquals(1, kinds.count { it.second == TokenKind.KEYWORD }) // only the leading `val`
    }

    @Test
    fun `a keyword inside a comment stays inside the comment`() {
        val source = """
            // fun val class are only words here
            val x = 1
        """.trimIndent()
        val kinds = kindsOf(source, CodeLanguage.KOTLIN)
        assertTrue(kinds.contains("// fun val class are only words here" to TokenKind.COMMENT))
        assertEquals(1, kinds.count { it.second == TokenKind.KEYWORD })
        assertEquals(1, kinds.count { it.second == TokenKind.NUMBER })
    }

    @Test
    fun `a block comment may cover several lines`() {
        val source = "/* one\n   fun two */\nval done = true"
        val kinds = kindsOf(source, CodeLanguage.KOTLIN)
        assertTrue(kinds.contains("/* one\n   fun two */" to TokenKind.COMMENT))
        assertTrue(kinds.contains("true" to TokenKind.KEYWORD))
        assertEquals(1, kinds.count { it.second == TokenKind.COMMENT })
    }

    @Test
    fun `an escaped quote does not end the string`() {
        val source = """val path = "a\"b" + 1"""
        val kinds = kindsOf(source, CodeLanguage.KOTLIN)
        assertTrue(kinds.contains("\"a\\\"b\"" to TokenKind.STRING))
        assertTrue(kinds.any { it.second == TokenKind.NUMBER })
    }

    @Test
    fun `a triple-quoted kotlin string spans its lines`() {
        val source = "val sql = \"\"\"\n  select * from t\n\"\"\"\nfun go() = Unit"
        val kinds = kindsOf(source, CodeLanguage.KOTLIN)
        assertTrue(kinds.contains("\"\"\"\n  select * from t\n\"\"\"" to TokenKind.STRING))
        assertTrue(kinds.contains("fun" to TokenKind.KEYWORD))
    }

    @Test
    fun `an unterminated string stops at the line end for c-like languages`() {
        val source = "val a = \"open\nval b = 2"
        val kinds = kindsOf(source, CodeLanguage.KOTLIN)
        assertTrue(kinds.contains("\"open" to TokenKind.STRING))
        assertTrue(kinds.contains("val" to TokenKind.KEYWORD))
    }

    @Test
    fun `python uses hash comments and its own keywords`() {
        val source = "def run(count: int):  # comment\ndef other(): pass"
        val kinds = kindsOf(source, CodeLanguage.PYTHON)
        assertTrue(kinds.contains("def" to TokenKind.KEYWORD))
        assertTrue(kinds.contains("int" to TokenKind.TYPE))
        assertTrue(kinds.contains("pass" to TokenKind.KEYWORD))
        assertTrue(kinds.any { it.second == TokenKind.COMMENT })
    }

    @Test
    fun `shell scripts keep their keywords and comments apart`() {
        val source = "if [ -f x ]; then\n  echo done # note\nfi"
        val kinds = kindsOf(source, CodeLanguage.SHELL)
        assertTrue(kinds.contains("if" to TokenKind.KEYWORD))
        assertTrue(kinds.contains("then" to TokenKind.KEYWORD))
        assertTrue(kinds.contains("fi" to TokenKind.KEYWORD))
        assertTrue(kinds.contains("# note" to TokenKind.COMMENT))
    }

    @Test
    fun `json keys and values are told apart`() {
        val source = """{"name": "mobile", "count": 3, "active": true}"""
        val kinds = kindsOf(source, CodeLanguage.JSON)
        assertTrue(kinds.contains("\"name\"" to TokenKind.PROPERTY))
        assertTrue(kinds.contains("\"mobile\"" to TokenKind.STRING))
        assertTrue(kinds.contains("3" to TokenKind.NUMBER))
        assertTrue(kinds.contains("true" to TokenKind.KEYWORD))
    }

    @Test
    fun `yaml keys, values and comments are recognised`() {
        val source = "# build config\nname: deploy\nsteps:\n  - run: \"make\"\nenabled: true"
        val kinds = kindsOf(source, CodeLanguage.YAML)
        assertTrue(kinds.contains("# build config" to TokenKind.COMMENT))
        assertTrue(kinds.contains("name" to TokenKind.PROPERTY))
        assertTrue(kinds.contains("steps" to TokenKind.PROPERTY))
        assertTrue(kinds.contains("\"make\"" to TokenKind.STRING))
        assertTrue(kinds.contains("true" to TokenKind.KEYWORD))
    }

    @Test
    fun `xml tags, attributes and values are distinguished`() {
        val source = """<!-- note --><LinearLayout orientation="vertical"><TextView /></LinearLayout>"""
        val kinds = kindsOf(source, CodeLanguage.XML)
        assertTrue(kinds.contains("LinearLayout" to TokenKind.TYPE))
        assertTrue(kinds.contains("orientation" to TokenKind.PROPERTY))
        assertTrue(kinds.contains("\"vertical\"" to TokenKind.STRING))
        assertTrue(kinds.contains("TextView" to TokenKind.TYPE))
        assertTrue(kinds.any { it.second == TokenKind.COMMENT })
    }

    @Test
    fun `css properties and at-rules are recognised`() {
        val source = "@media (max-width: 600px) { .card { color: red; margin: 8px; } }"
        val kinds = kindsOf(source, CodeLanguage.CSS)
        assertTrue(kinds.contains("@media" to TokenKind.ANNOTATION))
        assertTrue(kinds.contains("color" to TokenKind.PROPERTY))
        assertTrue(kinds.contains("margin" to TokenKind.PROPERTY))
        assertTrue(kinds.any { it.second == TokenKind.NUMBER })
    }

    @Test
    fun `markdown headings, fences and inline code are marked`() {
        val source = "# Title\n\nRun `npm test`.\n\n```\ncode line\n```"
        val kinds = kindsOf(source, CodeLanguage.MARKDOWN)
        assertTrue(kinds.contains("# Title" to TokenKind.KEYWORD))
        assertTrue(kinds.contains("`npm test`" to TokenKind.STRING))
        assertTrue(kinds.contains("code line" to TokenKind.STRING))
    }

    @Test
    fun `plain text and empty input produce nothing`() {
        assertTrue(spans("", CodeLanguage.KOTLIN).isEmpty())
        assertTrue(spans("hello world", CodeLanguage.PLAIN).isEmpty())
    }

    @Test
    fun `a file above the size bound is left plain instead of colouring it`() {
        val large = "val x = 1\n".repeat(CodeHighlight.MAX_HIGHLIGHTED_CHARS / 5)
        assertTrue(large.length > CodeHighlight.MAX_HIGHLIGHTED_CHARS)
        assertTrue(spans(large, CodeLanguage.KOTLIN).isEmpty())
    }

    @Test
    fun `line starts and per-line spans line up with the file`() {
        val text = "fun a() {\n  val b = 1\n}"
        val starts = CodeHighlight.lineStarts(text)
        // Line 1 is nine characters plus its newline, so line 2 starts at 10 and line 3 at 22.
        assertEquals(listOf(0, 10, 22), starts.toList())
        val spans = CodeHighlight.spansFor(text, CodeLanguage.KOTLIN)
        val secondLine = CodeHighlight.spansForLine(starts[1], starts[2] - 1, spans)
        // Line-local offsets: `val` right at the start of the indented line, the number further right.
        assertTrue(secondLine.any { it.kind == TokenKind.KEYWORD && it.start == 2 && it.end == 5 })
        assertTrue(secondLine.any { it.kind == TokenKind.NUMBER && text.substring(starts[1] + it.start, starts[1] + it.end) == "1" })
        assertTrue(secondLine.all { it.start >= 0 && it.end <= 11 })
    }

    @Test
    fun `an empty line has no spans`() {
        assertEquals(listOf(0), CodeHighlight.lineStarts("").toList())
        assertTrue(CodeHighlight.spansForLine(5, 5, listOf(HighlightSpan(0, 9, TokenKind.KEYWORD))).isEmpty())
    }

    @Test
    fun `every produced span is usable for a mixed corpus`() {
        val corpus = mapOf(
            CodeLanguage.KOTLIN to "fun main() { val s = \"x\" /* c */ return 0 }",
            CodeLanguage.JAVA to "public static void main(String[] args) { int i = 1; }",
            CodeLanguage.JAVASCRIPT to "const f = async () => { return 1 } // done",
            CodeLanguage.TYPESCRIPT to "interface A { name: string }\nconst x: number = 2",
            CodeLanguage.PYTHON to "class A:\n    def b(self): return None",
            CodeLanguage.SHELL to "for f in *; do echo \"\$f\"; done",
            CodeLanguage.JSON to "{\"a\": [1, 2, null]}",
            CodeLanguage.XML to "<a b=\"c\">d</a>",
            CodeLanguage.CSS to "a { b: 1px; }",
            CodeLanguage.YAML to "a: 1\nb:\n  - c: true",
            CodeLanguage.MARKDOWN to "# h\n`c`\n```\ncode\n```",
            CodeLanguage.PLAIN to "nothing here",
        )
        corpus.forEach { (language, text) -> assertUsable(text, language) }
    }

    @Test
    fun `a plain-looking keyword is not invented from a longer identifier`() {
        val source = "value = 1\nvalueless = 2\nval = 3"
        val kinds = kindsOf(source, CodeLanguage.KOTLIN)
        assertFalse(kinds.any { it.first == "valueless" })
        assertTrue(kinds.contains("val" to TokenKind.KEYWORD))
    }
}

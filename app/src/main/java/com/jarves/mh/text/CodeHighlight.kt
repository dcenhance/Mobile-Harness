package com.jarves.mh.text

/**
 * A small, linear colouring pass for the file editor.
 *
 * The editor keeps its line numbers beside a fixed-height gutter, so the colouring must not change the
 * text at all — it only produces spans that a `VisualTransformation` attaches to the same characters.
 * Nothing here claims to parse a language: it recognises the token kinds a reader actually needs
 * (keywords, built-in types, strings, comments, numbers, annotations, keys) and stops at a size where
 * a phone would otherwise stutter.
 */
enum class CodeLanguage {
    KOTLIN, JAVA, JAVASCRIPT, TYPESCRIPT, PYTHON, JSON, XML, CSS, SHELL, YAML, MARKDOWN, PLAIN,
}

enum class TokenKind { KEYWORD, TYPE, STRING, COMMENT, NUMBER, ANNOTATION, PROPERTY }

data class HighlightSpan(val start: Int, val end: Int, val kind: TokenKind)

object CodeHighlight {

    /** Above this the editor shows plain text: a colouring pass is not worth a frozen UI. */
    const val MAX_HIGHLIGHTED_CHARS = 200_000

    fun languageForFile(path: String): CodeLanguage {
        val name = path.substringAfterLast('/')
        val extension = name.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "kt", "kts", "gradle" -> CodeLanguage.KOTLIN
            "java" -> CodeLanguage.JAVA
            "js", "mjs", "cjs", "jsx" -> CodeLanguage.JAVASCRIPT
            "ts", "tsx", "mts", "cts" -> CodeLanguage.TYPESCRIPT
            "py", "pyw" -> CodeLanguage.PYTHON
            "json", "jsonl", "webmanifest" -> CodeLanguage.JSON
            "xml", "html", "htm", "svg", "xhtml" -> CodeLanguage.XML
            "css", "scss", "sass", "less" -> CodeLanguage.CSS
            "sh", "bash", "zsh", "fish", "env" -> CodeLanguage.SHELL
            "yml", "yaml" -> CodeLanguage.YAML
            "md", "markdown" -> CodeLanguage.MARKDOWN
            else -> CodeLanguage.PLAIN
        }
    }

    /**
     * Ordered, non-overlapping spans. A string, a comment or a number is consumed as a whole, so a
     * keyword inside one is never highlighted again.
     */
    fun spansFor(text: String, language: CodeLanguage): List<HighlightSpan> {
        if (language == CodeLanguage.PLAIN || text.isEmpty()) return emptyList()
        if (text.length > MAX_HIGHLIGHTED_CHARS) return emptyList()
        return when (language) {
            CodeLanguage.XML -> xmlSpans(text)
            CodeLanguage.MARKDOWN -> markdownSpans(text)
            CodeLanguage.CSS -> cssSpans(text)
            CodeLanguage.JSON -> jsonSpans(text)
            CodeLanguage.YAML -> yamlSpans(text)
            else -> cLikeSpans(text, language)
        }
    }

    /**
     * Offset of every line start. Index `i` is the first character of line `i`, so a per-line renderer
     * can colour a line without scanning the text again.
     */
    fun lineStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        text.forEachIndexed { index, character -> if (character == '\n') starts += index + 1 }
        return starts.toIntArray()
    }

    /**
     * The spans of one line, in offsets relative to that line. [spans] must be the result of
     * [spansFor] for the whole text (passing it in keeps a per-line renderer linear instead of
     * re-parsing the file for every line).
     */
    fun spansForLine(lineStart: Int, lineEnd: Int, spans: List<HighlightSpan>): List<HighlightSpan> {
        if (lineEnd <= lineStart) return emptyList()
        return spans.filter { it.start < lineEnd && it.end > lineStart }.map { span ->
            HighlightSpan(
                start = (span.start - lineStart).coerceAtLeast(0),
                end = (span.end - lineStart).coerceAtMost(lineEnd - lineStart),
                kind = span.kind,
            )
        }
    }

    private val keywordSets: Map<CodeLanguage, Set<String>> = mapOf(
        CodeLanguage.KOTLIN to setOf(
            "fun", "val", "var", "if", "else", "when", "for", "while", "do", "return", "class", "object",
            "interface", "data", "sealed", "enum", "override", "private", "public", "internal", "protected",
            "suspend", "inline", "lateinit", "companion", "init", "constructor", "import", "package", "is",
            "in", "as", "null", "true", "false", "this", "super", "try", "catch", "finally", "throw",
            "typealias", "by", "where", "out", "vararg", "const", "abstract", "open", "actual", "expect",
            "reified", "crossinline", "noinline", "tailrec", "operator", "infix", "external", "annotation",
            "break", "continue", "sealed",
        ),
        CodeLanguage.JAVA to setOf(
            "public", "private", "protected", "static", "final", "class", "interface", "extends",
            "implements", "new", "return", "if", "else", "for", "while", "do", "switch", "case", "break",
            "continue", "try", "catch", "finally", "throw", "throws", "import", "package", "this", "super",
            "null", "true", "false", "abstract", "synchronized", "volatile", "transient", "native",
            "instanceof", "enum", "record", "var", "void",
        ),
        CodeLanguage.JAVASCRIPT to setOf(
            "function", "const", "let", "var", "if", "else", "for", "while", "do", "return", "class",
            "extends", "new", "this", "null", "true", "false", "undefined", "async", "await", "yield",
            "import", "export", "from", "default", "try", "catch", "finally", "throw", "switch", "case",
            "break", "continue", "typeof", "instanceof", "delete", "in", "of", "void", "static", "get", "set",
        ),
        CodeLanguage.TYPESCRIPT to setOf(
            "function", "const", "let", "var", "if", "else", "for", "while", "do", "return", "class",
            "extends", "new", "this", "null", "true", "false", "undefined", "async", "await", "yield",
            "import", "export", "from", "default", "try", "catch", "finally", "throw", "switch", "case",
            "break", "continue", "typeof", "instanceof", "delete", "in", "of", "void", "static", "get", "set",
            "interface", "type", "enum", "implements", "public", "private", "protected", "readonly", "as",
            "namespace", "declare", "abstract",
        ),
        CodeLanguage.PYTHON to setOf(
            "def", "class", "if", "elif", "else", "for", "while", "return", "import", "from", "as", "with",
            "try", "except", "finally", "raise", "lambda", "yield", "async", "await", "global", "nonlocal",
            "pass", "break", "continue", "in", "is", "not", "and", "or", "None", "True", "False", "match",
            "case", "self", "del", "assert",
        ),
        CodeLanguage.SHELL to setOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function",
            "in", "return", "export", "local", "source", "exit", "set", "unset", "read", "cd", "shift",
            "trap", "echo", "printf",
        ),
        CodeLanguage.JSON to setOf("true", "false", "null"),
        CodeLanguage.YAML to setOf("true", "false", "null", "yes", "no", "on", "off"),
    )

    private val typeSets: Map<CodeLanguage, Set<String>> = mapOf(
        CodeLanguage.KOTLIN to setOf(
            "Int", "Long", "Double", "Float", "Boolean", "String", "Char", "Byte", "Short", "Unit", "Any",
            "Nothing", "List", "Map", "Set", "MutableList", "MutableMap", "MutableSet", "Array", "Pair",
            "Flow", "Result", "Exception", "Throwable",
        ),
        CodeLanguage.JAVA to setOf(
            "int", "long", "double", "float", "boolean", "char", "byte", "short", "String", "Object",
            "Integer", "Long", "Double", "Float", "Boolean", "Character", "List", "Map", "Set", "Exception",
        ),
        CodeLanguage.TYPESCRIPT to setOf("string", "number", "boolean", "any", "unknown", "never", "object", "Array", "Promise", "Record"),
        CodeLanguage.PYTHON to setOf("int", "float", "str", "bool", "list", "dict", "set", "tuple", "bytes", "Exception"),
    )

    private fun languageOf(keyword: String, language: CodeLanguage): TokenKind? =
        when {
            keywordSets[language]?.contains(keyword) == true -> TokenKind.KEYWORD
            typeSets[language]?.contains(keyword) == true -> TokenKind.TYPE
            else -> null
        }

    private fun isIdentifierStart(character: Char): Boolean = character.isLetter() || character == '_' || character == '$'

    private fun isIdentifierPart(character: Char): Boolean = character.isLetterOrDigit() || character == '_' || character == '$'

    private fun isDigit(character: Char): Boolean = character.isDigit()

    /** Kotlin, Java, JavaScript, TypeScript, Python and Shell share the C-style comment/string marks. */
    private fun cLikeSpans(text: String, language: CodeLanguage): List<HighlightSpan> {
        val spans = ArrayList<HighlightSpan>()
        val lineComment = if (language == CodeLanguage.PYTHON || language == CodeLanguage.SHELL) '#' else '/'
        val hasBlockComment = language != CodeLanguage.PYTHON && language != CodeLanguage.SHELL
        val hasAnnotations = language == CodeLanguage.KOTLIN || language == CodeLanguage.JAVA
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                lineComment == '#' && character == '#' -> {
                    val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                    spans += HighlightSpan(index, end, TokenKind.COMMENT)
                    index = end
                }

                character == '/' && index + 1 < text.length && text[index + 1] == '/' -> {
                    val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                    spans += HighlightSpan(index, end, TokenKind.COMMENT)
                    index = end
                }

                hasBlockComment && character == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                    val close = text.indexOf("*/", index + 2)
                    val end = if (close < 0) text.length else close + 2
                    spans += HighlightSpan(index, end, TokenKind.COMMENT)
                    index = end
                }

                character == '"' || character == '\'' -> {
                    val end = stringEnd(text, index, character, language)
                    spans += HighlightSpan(index, end, TokenKind.STRING)
                    index = end
                }

                hasAnnotations && character == '@' && index + 1 < text.length && isIdentifierStart(text[index + 1]) -> {
                    var end = index + 2
                    while (end < text.length && isIdentifierPart(text[end])) end++
                    spans += HighlightSpan(index, end, TokenKind.ANNOTATION)
                    index = end
                }

                isDigit(character) -> {
                    var end = index + 1
                    while (end < text.length && (isDigit(text[end]) || text[end] == '_' || text[end] == '.' ||
                            text[end] == 'x' || text[end] == 'X' || text[end] == 'L' || text[end] == 'f' ||
                            (text[end] in 'a'..'f' || text[end] in 'A'..'F'))
                    ) {
                        end++
                    }
                    spans += HighlightSpan(index, end, TokenKind.NUMBER)
                    index = end
                }

                isIdentifierStart(character) -> {
                    var end = index + 1
                    while (end < text.length && isIdentifierPart(text[end])) end++
                    val word = text.substring(index, end)
                    languageOf(word, language)?.let { kind -> spans += HighlightSpan(index, end, kind) }
                    index = end
                }

                else -> index++
            }
        }
        return spans
    }

    /**
     * End offset of the string starting at [start]. An unterminated string runs to the end of the file
     * (that is what the reader sees too), a triple-quoted Kotlin or Python string takes the whole block,
     * and an escaped quote inside does not close it.
     */
    private fun stringEnd(text: String, start: Int, quote: Char, language: CodeLanguage): Int {
        val triple = start + 2 < text.length && text[start + 1] == quote && text[start + 2] == quote &&
            (language == CodeLanguage.KOTLIN || language == CodeLanguage.PYTHON)
        if (triple) {
            val close = text.indexOf("$quote$quote$quote", start + 3)
            return if (close < 0) text.length else close + 3
        }
        var index = start + 1
        while (index < text.length) {
            val character = text[index]
            if (character == '\\' && index + 1 < text.length) {
                index += 2
                continue
            }
            if (character == quote) return index + 1
            if (character == '\n' && language != CodeLanguage.SHELL && language != CodeLanguage.PYTHON) return index
            index++
        }
        return text.length
    }

    private fun xmlSpans(text: String): List<HighlightSpan> {
        val spans = ArrayList<HighlightSpan>()
        var index = 0
        while (index < text.length) {
            when {
                text.startsWith("<!--", index) -> {
                    val close = text.indexOf("-->", index + 4)
                    val end = if (close < 0) text.length else close + 3
                    spans += HighlightSpan(index, end, TokenKind.COMMENT)
                    index = end
                }

                text[index] == '<' -> {
                    var cursor = index + 1
                    val closing = cursor < text.length && text[cursor] == '/'
                    if (closing) cursor++
                    val nameStart = cursor
                    while (cursor < text.length && (isIdentifierPart(text[cursor]) || text[cursor] == ':' || text[cursor] == '-')) cursor++
                    if (cursor > nameStart) spans += HighlightSpan(nameStart, cursor, TokenKind.TYPE)
                    // Attributes up to '>', with their values as strings.
                    while (cursor < text.length && text[cursor] != '>') {
                        when {
                            text[cursor] == '"' || text[cursor] == '\'' -> {
                                val end = stringEnd(text, cursor, text[cursor], CodeLanguage.XML)
                                spans += HighlightSpan(cursor, end, TokenKind.STRING)
                                cursor = end
                            }

                            isIdentifierStart(text[cursor]) -> {
                                val attributeStart = cursor
                                while (cursor < text.length && (isIdentifierPart(text[cursor]) || text[cursor] == '-' || text[cursor] == ':')) cursor++
                                spans += HighlightSpan(attributeStart, cursor, TokenKind.PROPERTY)
                            }

                            else -> cursor++
                        }
                    }
                    index = if (cursor < text.length) cursor + 1 else text.length
                }

                else -> index++
            }
        }
        return spans
    }

    private fun jsonSpans(text: String): List<HighlightSpan> {
        val spans = ArrayList<HighlightSpan>()
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                character == '"' -> {
                    val end = stringEnd(text, index, '"', CodeLanguage.JSON)
                    var lookahead = end
                    while (lookahead < text.length && text[lookahead].isWhitespace()) lookahead++
                    val kind = if (lookahead < text.length && text[lookahead] == ':') TokenKind.PROPERTY else TokenKind.STRING
                    spans += HighlightSpan(index, end, kind)
                    index = end
                }

                character == '#' -> {
                    val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                    spans += HighlightSpan(index, end, TokenKind.COMMENT)
                    index = end
                }

                isDigit(character) || (character == '-' && index + 1 < text.length && isDigit(text[index + 1])) -> {
                    var end = index + 1
                    while (end < text.length && (isDigit(text[end]) || text[end] == '.' || text[end] == 'e' ||
                            text[end] == 'E' || text[end] == '+' || text[end] == '-')
                    ) {
                        end++
                    }
                    spans += HighlightSpan(index, end, TokenKind.NUMBER)
                    index = end
                }

                isIdentifierStart(character) -> {
                    var end = index + 1
                    while (end < text.length && isIdentifierPart(text[end])) end++
                    val word = text.substring(index, end)
                    if (keywordSets.getValue(CodeLanguage.JSON).contains(word)) {
                        spans += HighlightSpan(index, end, TokenKind.KEYWORD)
                    }
                    index = end
                }

                else -> index++
            }
        }
        return spans
    }

    private fun yamlSpans(text: String): List<HighlightSpan> {
        val spans = ArrayList<HighlightSpan>()
        var lineStart = 0
        while (lineStart <= text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd)
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length
            when {
                trimmed.startsWith("#") -> spans += HighlightSpan(lineStart + indent, lineEnd, TokenKind.COMMENT)
                trimmed.isEmpty() -> Unit
                else -> {
                    val colon = trimmed.indexOf(':')
                    if (colon > 0) {
                        val key = trimmed.substring(0, colon).trim()
                        if (key.isNotEmpty() && !key.contains(' ')) {
                            spans += HighlightSpan(lineStart + indent, lineStart + indent + key.length, TokenKind.PROPERTY)
                        }
                        val valueStart = lineStart + indent + colon + 1
                        val value = text.substring(valueStart, lineEnd)
                        val trimmedValue = value.trimStart()
                        val valueOffset = valueStart + (value.length - trimmedValue.length)
                        when {
                            trimmedValue.startsWith("|") || trimmedValue.startsWith(">") -> Unit
                            trimmedValue.startsWith("\"") || trimmedValue.startsWith("'") -> {
                                val end = stringEnd(text, valueOffset, trimmedValue[0], CodeLanguage.YAML)
                                spans += HighlightSpan(valueOffset, minOf(end, lineEnd), TokenKind.STRING)
                            }

                            keywordSets.getValue(CodeLanguage.YAML).contains(trimmedValue.lowercase()) ->
                                spans += HighlightSpan(valueOffset, lineEnd, TokenKind.KEYWORD)
                        }
                    }
                }
            }
            if (lineEnd >= text.length) break
            lineStart = lineEnd + 1
        }
        return spans
    }

    private fun cssSpans(text: String): List<HighlightSpan> {
        val spans = ArrayList<HighlightSpan>()
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                text.startsWith("/*", index) -> {
                    val close = text.indexOf("*/", index + 2)
                    val end = if (close < 0) text.length else close + 2
                    spans += HighlightSpan(index, end, TokenKind.COMMENT)
                    index = end
                }

                character == '"' || character == '\'' -> {
                    val end = stringEnd(text, index, character, CodeLanguage.CSS)
                    spans += HighlightSpan(index, end, TokenKind.STRING)
                    index = end
                }

                character == '@' -> {
                    var end = index + 1
                    while (end < text.length && isIdentifierPart(text[end])) end++
                    spans += HighlightSpan(index, end, TokenKind.ANNOTATION)
                    index = end
                }

                isDigit(character) -> {
                    var end = index + 1
                    while (end < text.length && (isDigit(text[end]) || text[end] == '.' || text[end] == '%' ||
                            text[end] in "a-zA-Z")
                    ) {
                        end++
                    }
                    spans += HighlightSpan(index, end, TokenKind.NUMBER)
                    index = end
                }

                isIdentifierStart(character) -> {
                    var end = index + 1
                    while (end < text.length && (isIdentifierPart(text[end]) || text[end] == '-')) end++
                    var lookahead = end
                    while (lookahead < text.length && text[lookahead].isWhitespace()) lookahead++
                    if (lookahead < text.length && text[lookahead] == ':' && (lookahead + 1 >= text.length || text[lookahead + 1] != ':')) {
                        spans += HighlightSpan(index, end, TokenKind.PROPERTY)
                    }
                    index = end
                }

                else -> index++
            }
        }
        return spans
    }

    /** Markdown stays nearly plain: headings, fences and inline code, nothing that pretends to be prose. */
    private fun markdownSpans(text: String): List<HighlightSpan> {
        val spans = ArrayList<HighlightSpan>()
        var lineStart = 0
        var inFence = false
        while (lineStart <= text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd)
            if (line.trimStart().startsWith("```")) {
                spans += HighlightSpan(lineStart, lineEnd, TokenKind.STRING)
                inFence = !inFence
            } else if (inFence) {
                spans += HighlightSpan(lineStart, lineEnd, TokenKind.STRING)
            } else if (line.trimStart().startsWith("#")) {
                spans += HighlightSpan(lineStart, lineEnd, TokenKind.KEYWORD)
            } else {
                var index = 0
                while (index < line.length) {
                    if (line[index] == '`') {
                        val close = line.indexOf('`', index + 1)
                        val end = if (close < 0) line.length else close + 1
                        spans += HighlightSpan(lineStart + index, lineStart + end, TokenKind.STRING)
                        index = end
                    } else {
                        index++
                    }
                }
            }
            if (lineEnd >= text.length) break
            lineStart = lineEnd + 1
        }
        return spans
    }
}

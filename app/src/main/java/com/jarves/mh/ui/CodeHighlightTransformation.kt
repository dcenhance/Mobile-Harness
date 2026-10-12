package com.jarves.mh.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.jarves.mh.text.CodeHighlight
import com.jarves.mh.text.CodeLanguage
import com.jarves.mh.text.TokenKind

/**
 * Colours editor text without changing it: the same characters come back with span styles attached, so
 * the 48 dp line-number gutter beside the field keeps its alignment and every cursor offset stays valid.
 */
data class CodeHighlightColors(
    val keyword: Color,
    val type: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val annotation: Color,
    val property: Color,
) {
    fun styleFor(kind: TokenKind): SpanStyle = when (kind) {
        TokenKind.KEYWORD -> SpanStyle(color = keyword)
        TokenKind.TYPE -> SpanStyle(color = type)
        TokenKind.STRING -> SpanStyle(color = string)
        TokenKind.COMMENT -> SpanStyle(color = comment)
        TokenKind.NUMBER -> SpanStyle(color = number)
        TokenKind.ANNOTATION -> SpanStyle(color = annotation)
        TokenKind.PROPERTY -> SpanStyle(color = property)
    }
}

class CodeHighlightTransformation(
    private val language: CodeLanguage,
    private val colors: CodeHighlightColors,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val spans = CodeHighlight.spansFor(text.text, language)
        if (spans.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val builder = AnnotatedString.Builder(text)
        spans.forEach { span -> builder.addStyle(colors.styleFor(span.kind), span.start, span.end) }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

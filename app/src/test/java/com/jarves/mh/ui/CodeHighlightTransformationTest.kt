package com.jarves.mh.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.jarves.mh.text.CodeLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colouring must be purely visual: same characters, same offsets. The editor's line-number gutter and
 * the caret position depend on it.
 */
class CodeHighlightTransformationTest {

    private val colors = CodeHighlightColors(
        keyword = Color(0xFF112233),
        type = Color(0xFF223344),
        string = Color(0xFF334455),
        comment = Color(0xFF445566),
        number = Color(0xFF556677),
        annotation = Color(0xFF667788),
        property = Color(0xFF778899),
    )

    @Test
    fun `the text comes back unchanged`() {
        val source = "fun main() { val x = 1 } // note"
        val transformed = CodeHighlightTransformation(CodeLanguage.KOTLIN, colors)
            .filter(AnnotatedString(source))
        assertEquals(source, transformed.text.text)
    }

    @Test
    fun `the offset mapping stays the identity so the caret keeps its place`() {
        val source = "val a = 1"
        val transformed = CodeHighlightTransformation(CodeLanguage.KOTLIN, colors)
            .filter(AnnotatedString(source))
        for (index in 0..source.length) {
            assertEquals(index, transformed.offsetMapping.originalToTransformed(index))
            assertEquals(index, transformed.offsetMapping.transformedToOriginal(index))
        }
    }

    @Test
    fun `marked ranges carry the colour of their token kind`() {
        val source = "val name = \"text\""
        val transformed = CodeHighlightTransformation(CodeLanguage.KOTLIN, colors)
            .filter(AnnotatedString(source))
        val styles = transformed.text.spanStyles
        assertTrue("a keyword and a string are expected", styles.size >= 2)
        val keyword = styles.first { source.substring(it.start, it.end) == "val" }
        assertEquals(colors.keyword, keyword.item.color)
        val string = styles.first { source.substring(it.start, it.end) == "\"text\"" }
        assertEquals(colors.string, string.item.color)
    }

    @Test
    fun `plain text is passed through without any style`() {
        val source = "just a note, nothing to colour"
        val transformed = CodeHighlightTransformation(CodeLanguage.PLAIN, colors)
            .filter(AnnotatedString(source))
        assertEquals(source, transformed.text.text)
        assertTrue(transformed.text.spanStyles.isEmpty())
    }

    @Test
    fun `an empty field stays empty`() {
        val transformed = CodeHighlightTransformation(CodeLanguage.KOTLIN, colors)
            .filter(AnnotatedString(""))
        assertEquals("", transformed.text.text)
        assertTrue(transformed.text.spanStyles.isEmpty())
    }
}

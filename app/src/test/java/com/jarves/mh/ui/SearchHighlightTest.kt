package com.jarves.mh.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Color
import com.jarves.mh.ui.chat.literalMatchRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Marker placement on the rendered text, plus the shared literal range helper. */
class SearchHighlightTest {

    private val match = SpanStyle(background = Color(0x220000FF))
    private val current = SpanStyle(background = Color(0x880000FF))

    @Test
    fun `ranges are literal, ordered and non-overlapping`() {
        // Case-insensitive matching walks every character position, so all three positions match.
        val ranges = literalMatchRanges("aAa", "a")
        assertEquals(listOf(0 until 1, 1 until 2, 2 until 3), ranges)
    }

    @Test
    fun `a limit stops the scan`() {
        assertEquals(2, literalMatchRanges("aaaa", "a", limit = 2).size)
        assertTrue(literalMatchRanges("aaaa", "a", limit = 0).isEmpty())
    }

    @Test
    fun `every occurrence is marked and the current one is stronger`() {
        val marked = AnnotatedString("paste the paste").withLiteralHighlight(
            query = "paste",
            currentOccurrence = 1,
            matchStyle = match,
            currentStyle = current,
        )

        assertEquals("paste the paste", marked.text)
        val backgrounds = marked.spanStyles.filter { it.item.background != Color.Unspecified }
        assertEquals(2, backgrounds.size)
        assertEquals(match, backgrounds[0].item)
        assertEquals(current, backgrounds[1].item)
    }

    @Test
    fun `marking a rendered text works even when the source differs`() {
        // The markdown renderer drops the markers, so only the drawn text can be marked.
        val rendered = AnnotatedString("deploy the build")
        val marked = rendered.withLiteralHighlight("build", null, match)

        assertEquals("deploy the build", marked.text)
        assertEquals(1, marked.spanStyles.size)
    }

    @Test
    fun `a blank query or a miss leaves the text untouched`() {
        val text = AnnotatedString("nothing to mark")
        assertEquals(0, text.withLiteralHighlight("", null, match).spanStyles.size)
        assertEquals(0, text.withLiteralHighlight("absent", null, match).spanStyles.size)
    }
}

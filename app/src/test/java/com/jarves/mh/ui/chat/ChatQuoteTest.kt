package com.jarves.mh.ui.chat

import com.jarves.mh.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatQuoteTest {

    private fun message(text: String, fromUser: Boolean = false) = ChatMessage(fromUser = fromUser, text = text)

    @Test
    fun `every quoted line is marked as a quote`() {
        val quote = ChatQuote.quote("first\nsecond")
        assertEquals("> first\n> second\n\n", quote)
    }

    @Test
    fun `blank lines are dropped and the quote stays bounded`() {
        val text = (1..20).joinToString("\n\n") { "line $it" }
        val quote = ChatQuote.quote(text)
        val quotedLines = quote.trim().lines()

        assertEquals(ChatQuote.MAX_QUOTE_LINES, quotedLines.size)
        assertTrue(quotedLines.all { it.startsWith("> ") })
    }

    @Test
    fun `a very long line is cut with an explicit ellipsis`() {
        val quote = ChatQuote.quote("x".repeat(ChatQuote.MAX_QUOTE_CHARS * 3))
        assertTrue(quote.trim().length <= ChatQuote.MAX_QUOTE_CHARS + 1)
        assertTrue(quote.trim().endsWith("…"))
    }

    @Test
    fun `a message without quotable text produces no quote`() {
        assertEquals("", ChatQuote.quote("   \n  \n"))
        assertEquals("", ChatQuote.quote(message("   ")))
    }

    @Test
    fun `quoting appends to the draft instead of replacing it`() {
        val draft = "Please explain this part:"
        val combined = ChatQuote.append(draft, ChatQuote.quote("the plan changed"))

        assertTrue(combined.startsWith(draft))
        assertTrue(combined.contains("> the plan changed"))
    }

    @Test
    fun `quoting into an empty draft is just the quote`() {
        val combined = ChatQuote.append("", ChatQuote.quote("context"))
        assertEquals("> context\n\n", combined)
    }

    @Test
    fun `an empty quote never changes the draft`() {
        val draft = "unchanged"
        assertEquals(draft, ChatQuote.append(draft, ""))
        assertFalse(ChatQuote.append(draft, "").contains(">"))
    }

    @Test
    fun `the draft stays bounded`() {
        val nearlyFull = "y".repeat(ChatQuote.MAX_DRAFT_CHARS - 10)
        val grown = ChatQuote.append(nearlyFull, ChatQuote.quote("tail"))
        assertTrue(grown.length <= ChatQuote.MAX_DRAFT_CHARS)
        assertTrue(grown.length > nearlyFull.length)

        val full = "y".repeat(ChatQuote.MAX_DRAFT_CHARS)
        assertEquals(ChatQuote.MAX_DRAFT_CHARS, ChatQuote.append(full, ChatQuote.quote("tail")).length)
    }
}

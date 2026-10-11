package com.jarves.mh.ui.chat

import com.jarves.mh.model.ChatMessage
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real conversation-find engine, exercised on real [ChatMessage] transcripts. */
class ChatSearchTest {

    private fun message(text: String, fromUser: Boolean = false, id: String = UUID.randomUUID().toString()) =
        ChatMessage(id = id, fromUser = fromUser, text = text)

    @Test
    fun `counts every occurrence in transcript order`() {
        val first = message("Wire the clock into the plan", fromUser = true)
        val second = message("The plan needs a clock, and the plan needs a test.")
        val result = ChatSearch.find(listOf(first, second), "plan")

        assertEquals(3, result.hits.size)
        assertFalse(result.truncated)
        assertEquals(listOf(0, 1, 1), result.hits.map { it.messageIndex })
        assertEquals(listOf(0, 0, 1), result.hits.map { it.occurrenceInMessage })
        assertEquals(first.id, result.hits.first().messageId)
    }

    @Test
    fun `matching is literal and case-insensitive`() {
        val text = message("Call debounce() and Debounce_ again", fromUser = true)
        val result = ChatSearch.find(listOf(text), "DEBOUNCE")

        assertEquals(2, result.hits.size)
        // The offsets address the original message text, not a case-folded copy.
        result.hits.forEach { hit ->
            assertEquals("debounce", text.text.substring(hit.start, hit.start + 8).lowercase())
        }
    }

    @Test
    fun `regex metacharacters stay literal`() {
        val messages = listOf(message("a.c matches itself", fromUser = true), message("abc does not", fromUser = true))
        val result = ChatSearch.find(messages, "a.c")

        assertEquals(1, result.hits.size)
        assertEquals(0, result.hits.single().messageIndex)
    }

    @Test
    fun `matching does not overlap`() {
        val result = ChatSearch.find(listOf(message("aaaa", fromUser = true)), "aa")
        assertEquals(2, result.hits.size)
        assertEquals(listOf(0, 2), result.hits.map { it.start })
    }

    @Test
    fun `blank query finds nothing and is not truncated`() {
        val messages = listOf(message("anything", fromUser = true))
        for (query in listOf("", "   ")) {
            val result = ChatSearch.find(messages, query)
            assertTrue(result.hits.isEmpty())
            assertFalse(result.truncated)
            assertEquals(0, result.position(-1))
        }
    }

    @Test
    fun `query is trimmed before matching`() {
        val result = ChatSearch.find(listOf(message("run the tests", fromUser = true)), "  tests ")
        assertEquals(1, result.hits.size)
        assertEquals("tests", result.query)
    }

    @Test
    fun `stepping wraps in both directions and starts from no selection`() {
        val result = ChatSearch.find(listOf(message("one two one", fromUser = true)), "one")
        assertEquals(2, result.hits.size)

        assertEquals(0, result.step(-1, forward = true))
        assertEquals(1, result.step(-1, forward = false))
        assertEquals(1, result.step(0, forward = true))
        assertEquals(0, result.step(1, forward = true))
        assertEquals(0, result.step(1, forward = false))
        assertEquals(1, result.step(0, forward = false))
    }

    @Test
    fun `stepping a single hit stays on it`() {
        val result = ChatSearch.find(listOf(message("only once", fromUser = true)), "once")
        assertEquals(0, result.step(0, forward = true))
        assertEquals(0, result.step(0, forward = false))
    }

    @Test
    fun `stepping without a match has no target`() {
        val result = ChatSearch.find(listOf(message("nothing here", fromUser = true)), "absent")
        assertNull(result.step(-1, forward = true))
        assertNull(result.hitMessageIndex(0))
        assertNull(result.currentHit(0))
    }

    @Test
    fun `position is one-based and clamps a stale selection`() {
        val result = ChatSearch.find(listOf(message("alpha alpha alpha", fromUser = true)), "alpha")
        assertEquals(1, result.position(-1))
        assertEquals(1, result.position(0))
        assertEquals(3, result.position(2))
        assertEquals(3, result.position(99))
    }

    @Test
    fun `truncation is reported instead of hidden`() {
        val long = message("match ".repeat(ChatSearch.MAX_HITS + 25), fromUser = true)
        val result = ChatSearch.find(listOf(long), "match")

        assertEquals(ChatSearch.MAX_HITS, result.hits.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `active hit occurrence is reported only for its own message`() {
        val messages = listOf(message("hit hit", fromUser = true), message("hit", fromUser = true))
        val result = ChatSearch.find(messages, "hit")
        val secondHit = result.currentHit(2)

        assertEquals(0, result.occurrenceInMessage(secondHit, 1))
        assertNull(result.occurrenceInMessage(secondHit, 0))
        assertEquals(1, result.hitMessageIndex(2))
    }

    @Test
    fun `offsets stay correct after multi-byte characters`() {
        val text = message("Grüße 🚀 → deploy the build", fromUser = true)
        val hit = ChatSearch.find(listOf(text), "build").hits.single()

        assertEquals("build", text.text.substring(hit.start, hit.end))
    }
}

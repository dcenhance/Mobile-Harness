package com.jarves.mh.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyFoldTest {

    private fun lines(count: Int) = (1..count).joinToString("\n") { "line $it" }

    @Test
    fun `line count follows the message text`() {
        assertEquals(0, ReplyFoldPolicy.lineCount(""))
        assertEquals(1, ReplyFoldPolicy.lineCount("one line"))
        assertEquals(3, ReplyFoldPolicy.lineCount("a\nb\nc"))
        assertEquals(2, ReplyFoldPolicy.lineCount("a\n"))
    }

    @Test
    fun `the newest reply never folds`() {
        assertFalse(
            ReplyFoldPolicy.shouldFold(
                text = lines(200),
                fromUser = false,
                isNewestMessage = true,
                expanded = false,
            ),
        )
    }

    @Test
    fun `the reader's own messages never fold`() {
        assertFalse(
            ReplyFoldPolicy.shouldFold(
                text = lines(200),
                fromUser = true,
                isNewestMessage = false,
                expanded = false,
            ),
        )
    }

    @Test
    fun `an older long reply folds until it is reopened`() {
        val long = lines(ReplyFoldPolicy.FOLD_LINES + 1)
        assertTrue(ReplyFoldPolicy.shouldFold(long, fromUser = false, isNewestMessage = false, expanded = false))
        assertFalse(ReplyFoldPolicy.shouldFold(long, fromUser = false, isNewestMessage = false, expanded = true))
    }

    @Test
    fun `a reply at the threshold stays open`() {
        val atThreshold = lines(ReplyFoldPolicy.FOLD_LINES)
        assertFalse(ReplyFoldPolicy.isFoldable(atThreshold, fromUser = false))
        assertFalse(ReplyFoldPolicy.shouldFold(atThreshold, fromUser = false, isNewestMessage = false, expanded = false))
    }
}

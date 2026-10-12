package com.jarves.mh.runtime

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Naming, containment and plausibility rules for a captured screen frame: two captures in the same
 * second must not overwrite each other, a chat id must not escape its attachment folder, and a
 * zero-byte or truncated write must never be handed on as a screenshot.
 */
class ScreenFrameFilesTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** 2026-10-12T22:30:45Z, so the expected name is a literal rather than the same formatter. */
    private val stamp: Long = 1791844245000L

    @Test
    fun `a frame is named after the second it was taken`() {
        assertEquals("screenshot-20261012-223045.png", ScreenFrameFiles.frameFileName(stamp, emptySet(), utc))
    }

    @Test
    fun `a second capture in the same second does not overwrite the first`() {
        val first = ScreenFrameFiles.frameFileName(stamp, emptySet(), utc)
        val second = ScreenFrameFiles.frameFileName(stamp, setOf(first), utc)
        assertEquals("screenshot-20261012-223045-2.png", second)
        val third = ScreenFrameFiles.frameFileName(stamp, setOf(first, second), utc)
        assertEquals("screenshot-20261012-223045-3.png", third)
    }

    @Test
    fun `only this feature's own names are recognised as frames`() {
        assertTrue(ScreenFrameFiles.isFrameName("screenshot-20261012-223045.png"))
        assertTrue(ScreenFrameFiles.isFrameName("screenshot-20261012-223045-12.png"))
        assertFalse(ScreenFrameFiles.isFrameName("photo-20261012-223045.jpg"))
        assertFalse(ScreenFrameFiles.isFrameName("screenshot-20261012-223045"))
        assertFalse(ScreenFrameFiles.isFrameName("screenshot-notatimestamp.png"))
        assertFalse(ScreenFrameFiles.isFrameName("../screenshot-20261012-223045.png"))
    }

    @Test
    fun `a frame lands in the attachment folder of its chat`() {
        assertEquals(
            "attachments/8f14e45f/screenshot-20261012-223045.png",
            ScreenFrameFiles.relativeAttachmentPath("8f14e45f", "screenshot-20261012-223045.png"),
        )
    }

    @Test
    fun `a chat or file name that is not one plain segment is refused`() {
        assertNull(ScreenFrameFiles.relativeAttachmentPath("../other", "shot.png"))
        assertNull(ScreenFrameFiles.relativeAttachmentPath("chat", "../shot.png"))
        assertNull(ScreenFrameFiles.relativeAttachmentPath("chat/child", "shot.png"))
        assertNull(ScreenFrameFiles.relativeAttachmentPath("chat\\child", "shot.png"))
        assertNull(ScreenFrameFiles.relativeAttachmentPath("", "shot.png"))
        assertNull(ScreenFrameFiles.relativeAttachmentPath("..", "shot.png"))
        assertNull(ScreenFrameFiles.relativeAttachmentPath("chat", "shot\u0000.png"))
    }

    @Test
    fun `plain segments are trimmed and otherwise unchanged`() {
        assertEquals("chat-1", ScreenFrameFiles.plainSegment(" chat-1 "))
        assertEquals("screenshot-20261012-223045.png", ScreenFrameFiles.plainSegment("screenshot-20261012-223045.png"))
        assertNull(ScreenFrameFiles.plainSegment("   "))
        assertNull(ScreenFrameFiles.plainSegment("."))
    }

    @Test
    fun `a real png of a plausible size is accepted`() {
        val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(ScreenFrameFiles.isPlausibleFrame(4096L, header))
        assertTrue(ScreenFrameFiles.isPlausibleFrame(ScreenFrameFiles.MAX_FRAME_BYTES, header))
    }

    @Test
    fun `an empty, oversized or non-png write is refused`() {
        val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertFalse("zero bytes", ScreenFrameFiles.isPlausibleFrame(0L, header))
        assertFalse("negative", ScreenFrameFiles.isPlausibleFrame(-1L, header))
        assertFalse("over the bound", ScreenFrameFiles.isPlausibleFrame(ScreenFrameFiles.MAX_FRAME_BYTES + 1, header))
        assertFalse("jpeg bytes", ScreenFrameFiles.isPlausibleFrame(4096L, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertFalse("short header", ScreenFrameFiles.isPlausibleFrame(4096L, byteArrayOf(0x89.toByte(), 0x50)))
        assertFalse("no header", ScreenFrameFiles.isPlausibleFrame(4096L, null))
    }

    @Test
    fun `every refusal has its own reason`() {
        // The UI maps each reason to its own sentence; an added reason without a sentence would compile
        // but silently say nothing useful, so the set is pinned here.
        assertEquals(
            listOf("DENIED", "UNSUPPORTED", "NO_TARGET", "NO_FRAME", "WRITE_FAILED", "IMPLAUSIBLE_FRAME"),
            ScreenFrameFiles.Refusal.entries.map { it.name },
        )
    }
}

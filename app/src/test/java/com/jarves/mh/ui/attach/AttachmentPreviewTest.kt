package com.jarves.mh.ui.attach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounds and decisions behind the attachment thumbnail: which files get one, how far a camera photo
 * is downscaled, and what the chip reports instead when no preview is possible.
 */
class AttachmentPreviewTest {

    @Test
    fun `pictures are previewable by declared type`() {
        assertTrue(AttachmentPreview.isPreviewable("image/jpeg", "IMG_20261012.jpg"))
        assertTrue(AttachmentPreview.isPreviewable("image/png", "screenshot.png"))
        assertTrue(AttachmentPreview.isPreviewable("image/webp", "shot.webp"))
        assertEquals("image/jpeg", AttachmentPreview.mimeOf("IMAGE/JPEG", "IMG.jpg"))
    }

    @Test
    fun `a generic provider type falls back to the file extension`() {
        // File providers hand out octet-stream for real photos; the extension still decides.
        assertTrue(AttachmentPreview.isPreviewable("application/octet-stream", "photo.JPG"))
        assertTrue(AttachmentPreview.isPreviewable("", "bild.png"))
        assertTrue(AttachmentPreview.isPreviewable(null, "scan.webp"))
    }

    @Test
    fun `text and unknown uploads get no thumbnail and are reported as unsupported`() {
        assertFalse(AttachmentPreview.isPreviewable("text/plain", "notes.txt"))
        assertFalse(AttachmentPreview.isPreviewable("application/json", "config.json"))
        assertEquals(
            AttachmentPreview.Refusal.UNSUPPORTED_TYPE,
            AttachmentPreview.refusal("text/markdown", "README.md", 4096L),
        )
    }

    @Test
    fun `a declared image type we cannot decode is not previewed`() {
        // HEIC and friends would need an API-level decoder we do not ship; do not guess a thumbnail.
        assertFalse(AttachmentPreview.isPreviewable("image/heic", "IMG_0001.heic"))
        assertFalse(AttachmentPreview.isPreviewable("image/svg+xml", "logo.svg"))
        assertNull(AttachmentPreview.mimeOf("image/heic", "IMG_0001.heic"))
    }

    @Test
    fun `a picture above the decode bound is refused with a reason`() {
        val tooLarge = AttachmentPreview.MAX_PREVIEW_BYTES + 1
        assertEquals(
            AttachmentPreview.Refusal.TOO_LARGE,
            AttachmentPreview.refusal("image/jpeg", "huge.jpg", tooLarge),
        )
    }

    @Test
    fun `a picture exactly at the decode bound is still previewed`() {
        assertNull(
            AttachmentPreview.refusal("image/jpeg", "at-the-limit.jpg", AttachmentPreview.MAX_PREVIEW_BYTES),
        )
    }

    @Test
    fun `a large camera photo is downscaled to the wanted edge, never below`() {
        // 8000x6000: /8 is 1000, /16 would fall under the 512 edge.
        assertEquals(8, AttachmentPreview.sampleSizeFor(8000, 6000))
        assertEquals(1000, AttachmentPreview.decodedEdge(8000, 6000))
        assertTrue(AttachmentPreview.decodedEdge(8000, 6000) >= AttachmentPreview.MAX_PREVIEW_EDGE)
    }

    @Test
    fun `small pictures are decoded at full size`() {
        assertEquals(1, AttachmentPreview.sampleSizeFor(400, 300))
        assertEquals(400, AttachmentPreview.decodedEdge(400, 300))
    }

    @Test
    fun `an exact multiple of the edge halves once`() {
        assertEquals(2, AttachmentPreview.sampleSizeFor(1024, 1024))
        assertEquals(4, AttachmentPreview.sampleSizeFor(2048, 512))
    }

    @Test
    fun `portrait pictures use the longer edge too`() {
        assertEquals(AttachmentPreview.sampleSizeFor(6000, 8000), AttachmentPreview.sampleSizeFor(8000, 6000))
    }

    @Test
    fun `unknown dimensions decode at full size instead of dividing by zero`() {
        assertEquals(1, AttachmentPreview.sampleSizeFor(0, 0))
        assertEquals(1, AttachmentPreview.sampleSizeFor(-4, 100))
        assertEquals(0, AttachmentPreview.decodedEdge(0, 123))
        assertEquals(1, AttachmentPreview.sampleSizeFor(8000, 6000, maxEdge = 0))
    }

    @Test
    fun `the cache key follows path, size and type`() {
        val base = AttachmentPreview.cacheKey("attachments/c1/a.png", 1000L, "image/png")
        assertEquals(base, AttachmentPreview.cacheKey(" attachments/c1/a.png ", 1000L, "IMAGE/PNG"))
        assertFalse(base == AttachmentPreview.cacheKey("attachments/c1/a.png", 1001L, "image/png"))
        assertFalse(base == AttachmentPreview.cacheKey("attachments/c1/b.png", 1000L, "image/png"))
        assertEquals(
            AttachmentPreview.cacheKey("attachments/c1/a.png", 1000L),
            AttachmentPreview.cacheKey("attachments/c1/a.png", 1000L, null),
        )
    }
}

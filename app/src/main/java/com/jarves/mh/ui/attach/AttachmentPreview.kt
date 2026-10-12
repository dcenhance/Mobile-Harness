package com.jarves.mh.ui.attach

/**
 * Decisions for attachment thumbnails, kept free of Android types so they can be tested on the JVM.
 *
 * The app already lets a phone attach a photo and references it by path in the agent prompt, but it
 * only ever showed a filename chip: the person could not check which picture, in which orientation,
 * was being sent. Decoding a camera photo on a phone is the risky part (a 50 MP JPEG is ~8000x6000),
 * so the bound lives here and the UI layer only follows it.
 */
object AttachmentPreview {

    /** Above this a preview is refused with a reason instead of risking an out-of-memory decode. */
    const val MAX_PREVIEW_BYTES: Long = 24L * 1024 * 1024

    /** Longest edge of a thumbnail bitmap; the chip draws 44 dp, so this stays sharp on any density. */
    const val MAX_PREVIEW_EDGE: Int = 512

    /** Image types the decoders on every supported API level can read from the copied file. */
    private val previewableMimeTypes = setOf(
        "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif", "image/bmp",
    )

    private val previewableExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    enum class Refusal {
        /** The type is not a picture we can decode (text, json, archives, unlabelled uploads). */
        UNSUPPORTED_TYPE,

        /** The file is a picture but too large to decode for a 44 dp chip. */
        TOO_LARGE,
    }

    /**
     * True only for pictures this build can decode. A blank or generic MIME type falls back to the
     * extension, because some file providers hand out `application/octet-stream` for real photos.
     */
    fun isPreviewable(mimeType: String?, displayName: String?): Boolean =
        mimeOf(mimeType, displayName) != null

    /** The MIME type used for the decision, or null when the file is not a previewable picture. */
    fun mimeOf(mimeType: String?, displayName: String?): String? {
        val declared = mimeType?.trim()?.lowercase().orEmpty()
        if (declared in previewableMimeTypes) return declared
        if (declared.startsWith("image/")) return null
        val extension = displayName?.trim()?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return if (extension in previewableExtensions) "image/$extension" else null
    }

    /**
     * Why a preview is refused, or null when it should be attempted. The size bound applies to
     * pictures only: a text attachment never gets a thumbnail, so it is not a "refusal" to report.
     */
    fun refusal(mimeType: String?, displayName: String?, sizeBytes: Long): Refusal? {
        if (mimeOf(mimeType, displayName) == null) return Refusal.UNSUPPORTED_TYPE
        return if (sizeBytes > MAX_PREVIEW_BYTES) Refusal.TOO_LARGE else null
    }

    /**
     * Power-of-two downscale factor for `BitmapFactory.Options.inSampleSize`: the decoded picture
     * stays at or above the wanted edge, never below, so the chip is not blurrier than necessary.
     * Unknown (0 or negative) dimensions decode at full size, which is what the decoder does too.
     */
    fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, maxEdge: Int = MAX_PREVIEW_EDGE): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxEdge <= 0) return 1
        val longest = maxOf(sourceWidth, sourceHeight)
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** What the thumbnail of these dimensions will cost in memory, for logs and honest tests. */
    fun decodedEdge(sourceWidth: Int, sourceHeight: Int, maxEdge: Int = MAX_PREVIEW_EDGE): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 0
        val sample = sampleSizeFor(sourceWidth, sourceHeight, maxEdge)
        return maxOf(sourceWidth, sourceHeight) / sample
    }

    /**
     * Cache key for a decoded thumbnail. The attachment model has no timestamp, so the same path and
     * size reuse one bitmap; a re-attached file of the same name but different size re-decodes.
     */
    fun cacheKey(relativePath: String, sizeBytes: Long, mimeType: String? = null): String =
        "${relativePath.trim()}|$sizeBytes|${mimeType?.trim()?.lowercase().orEmpty()}"
}

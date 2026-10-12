package com.jarves.mh.ui.attach

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.jarves.mh.model.ChatAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decoded thumbnail for an attachment, or null while decoding, when no picture was attached, or when
 * the preview was refused. Decoding happens off the main thread and is bounded by
 * [AttachmentPreview.sampleSizeFor] so a large camera photo cannot exhaust the heap.
 *
 * The workspace root is passed in: attachment chips live in the composer and in the transcript, and
 * both already know which project they belong to.
 */
@Composable
internal fun rememberAttachmentThumbnail(
    attachment: ChatAttachment,
    rootPath: String?,
    maxEdge: Int = AttachmentPreview.MAX_PREVIEW_EDGE,
): ImageBitmap? {
    val key = remember(attachment.relativePath, attachment.sizeBytes, attachment.mimeType, rootPath) {
        AttachmentPreview.cacheKey(attachment.relativePath, attachment.sizeBytes, attachment.mimeType)
    }
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key) {
        if (rootPath.isNullOrBlank()) return@LaunchedEffect
        val refusal = AttachmentPreview.refusal(attachment.mimeType, attachment.displayName, attachment.sizeBytes)
        if (refusal != null) return@LaunchedEffect
        val root = File(rootPath)
        val file = File(root, attachment.relativePath)
        bitmap = withContext(Dispatchers.IO) { decodeAttachmentThumbnail(file, root, maxEdge) }
    }
    return bitmap
}

/**
 * Reads the copied attachment as a downscaled bitmap. The file must be a regular file inside the
 * workspace root: the same containment rule the app applies when it opens or deletes an attachment.
 * Any failure returns null, which the caller shows as the plain icon instead of a broken image.
 */
internal fun decodeAttachmentThumbnail(file: File, root: File, maxEdge: Int): ImageBitmap? = runCatching {
    val canonicalRoot = root.canonicalFile
    val canonicalFile = file.canonicalFile
    if (!canonicalFile.toPath().startsWith(canonicalRoot.toPath())) return@runCatching null
    if (!canonicalFile.isFile) return@runCatching null
    val length = canonicalFile.length()
    if (length <= 0L || length > AttachmentPreview.MAX_PREVIEW_BYTES) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(canonicalFile.absolutePath, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return@runCatching null
    val options = BitmapFactory.Options().apply {
        inSampleSize = AttachmentPreview.sampleSizeFor(width, height, maxEdge)
        inScaled = false
    }
    BitmapFactory.decodeFile(canonicalFile.absolutePath, options)?.asImageBitmap()
}.getOrNull()

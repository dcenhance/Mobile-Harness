package com.jarves.mh.runtime

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Naming, path and plausibility rules for a captured screen frame, free of Android types so they can
 * be tested on the JVM.
 *
 * A frame is written into the chat's attachment folder, so the existing attachment path does the rest:
 * it is shown with a thumbnail, it travels to the agent as a file reference, and it can be removed
 * like any other attachment. That also means this file only has to answer two risky questions — where
 * may the frame be written, and is what came back really a picture.
 */
object ScreenFrameFiles {

    const val PREFIX = "screenshot-"
    const val EXTENSION = "png"

    /** Refused above this: a phone screen is a few megabytes, not a memory problem. */
    const val MAX_FRAME_BYTES: Long = 24L * 1024 * 1024

    /** Attachment folder of one chat, as [com.jarves.mh.model.ChatAttachment.relativePath] expects. */
    const val ATTACHMENT_FOLDER = "attachments"

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private val FRAME_NAME = Regex("^screenshot-\\d{8}-\\d{6}(-\\d+)?\\.png$")

    /** Why a capture did not produce an attachment; the UI maps each one to its own sentence. */
    enum class Refusal {
        /** The system screen-cast dialog was dismissed, or the token was refused. */
        DENIED,

        /** This Android build has no screen-cast service, or the platform refused to start it. */
        UNSUPPORTED,

        /** No chat/project is open, so there is nowhere to put the frame. */
        NO_TARGET,

        /** The projection produced no frame (a screen that is off yields no fresh content). */
        NO_FRAME,

        /** The frame could not be written into the workspace. */
        WRITE_FAILED,

        /** Something was written, but it is not a PNG of a plausible size. */
        IMPLAUSIBLE_FRAME,
    }

    /**
     * `screenshot-yyyyMMdd-HHmmss.png`, with `-2`, `-3` … appended when a capture in the same second
     * already exists, so two frames never overwrite each other.
     */
    fun frameFileName(
        timestampMillis: Long,
        taken: Set<String> = emptySet(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .apply { timeZone = zone }
            .format(Date(timestampMillis))
        val base = "$PREFIX$stamp"
        if ("$base.$EXTENSION" !in taken) return "$base.$EXTENSION"
        var suffix = 2
        while ("$base-$suffix.$EXTENSION" in taken) suffix++
        return "$base-$suffix.$EXTENSION"
    }

    /** True for names this feature itself produces; used to recognise its own frames. */
    fun isFrameName(name: String): Boolean = FRAME_NAME.matches(name.trim())

    /**
     * Workspace-relative target path. Both segments are reduced to a single plain path component, so
     * a chat id or file name that carries separators cannot escape the chat's attachment folder.
     */
    fun relativeAttachmentPath(chatId: String, fileName: String): String? {
        val chat = plainSegment(chatId) ?: return null
        val name = plainSegment(fileName) ?: return null
        return "$ATTACHMENT_FOLDER/$chat/$name"
    }

    /**
     * A written frame is only accepted when the bytes start with the PNG signature and the size is
     * plausible. A zero-byte file, a truncated write or a PNG header with nothing behind it is a
     * failed capture, not an attachment.
     */
    fun isPlausibleFrame(bytes: Long, header: ByteArray?): Boolean {
        if (bytes <= 0L || bytes > MAX_FRAME_BYTES) return false
        if (header == null || header.size < PNG_SIGNATURE.size) return false
        return PNG_SIGNATURE.indices.all { header[it] == PNG_SIGNATURE[it] }
    }

    /**
     * One plain path component: no separators, no `.`/`..`, no blank name, no NUL. Returns null when
     * the value cannot be used as one.
     */
    fun plainSegment(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return null
        if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains('\u0000')) return null
        return trimmed
    }
}

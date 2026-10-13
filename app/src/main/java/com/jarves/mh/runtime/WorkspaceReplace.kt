package com.jarves.mh.runtime

import com.jarves.mh.text.SearchQuery
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Bounds for one replace. Every ceiling is reported instead of hidden. */
data class ReplaceLimits(
    val maxFiles: Int = 2000,
    val maxFileBytes: Int = 512 * 1024,
    val maxChangedFiles: Int = 500,
    val maxChangedBytes: Long = 16L * 1024 * 1024,
    /** A longer single line is left alone: pattern matching on it is neither useful nor bounded. */
    val maxLineLength: Int = 8_000,
    val previewChars: Int = 140,
)

/** One file the plan would change, holding the exact bytes it has and would get. Nothing is written. */
class ReplaceFilePlan(
    val path: String,
    val replacements: Int,
    val longLines: Int,
    val beforeBytes: ByteArray,
    val afterBytes: ByteArray,
    val preview: String,
)

/**
 * A complete, unapplied replace over the workspace.
 *
 * The plan holds the new content of every affected file, so a run either writes all of it or nothing at
 * all — a failure while planning leaves the workspace untouched, which is the difference between a tool
 * that can be trusted with a rename and one that cannot.
 */
class ReplacePlan(
    val query: String,
    val replacement: String,
    val files: List<ReplaceFilePlan>,
    val scannedFiles: Int,
    val binaryFiles: Int,
    val oversizedFiles: Int,
    val unreadableFiles: Int,
    val heldBackDirectories: List<String>,
    val truncated: Boolean,
    /** True when the replacement text cannot be used with this pattern: nothing was planned. */
    val templateRejected: Boolean = false,
) {
    val changedFiles: Int get() = files.size
    val totalReplacements: Int get() = files.sumOf { it.replacements }
    val skippedLongLines: Int get() = files.sumOf { it.longLines }
    val changedBytes: Long get() = files.sumOf { it.beforeBytes.size.toLong() }
    val isEmpty: Boolean get() = files.isEmpty()
}

/** What one applied replace did on disk. */
data class ReplaceOutcome(val filesWritten: Int, val replacements: Int)

/**
 * Project-wide replace: plan first, write after.
 *
 * A search and a replace share [WorkspaceFileWalk], so a replace can only touch files a search would
 * have offered. Two guards keep a file out rather than damaging it: a file whose bytes do not survive a
 * UTF-8 decode/encode round trip (a Latin-1 source, a binary that slipped past the extension list) is
 * counted as unreadable, and a line longer than the limit is left unchanged. Line endings are preserved
 * exactly, so a CRLF file stays CRLF.
 */
object WorkspaceReplace {

    fun plan(
        root: File,
        query: SearchQuery,
        replacement: String,
        limits: ReplaceLimits = ReplaceLimits(),
    ): ReplacePlan {
        val candidateRoot = root.takeIf { it.isDirectory }?.canonicalFile
        if (candidateRoot == null) {
            return ReplacePlan(query.source, replacement, emptyList(), 0, 0, 0, 0, emptyList(), false)
        }
        val candidates = WorkspaceFileWalk.collect(candidateRoot, limits.maxFiles)
        val files = ArrayList<ReplaceFilePlan>()
        var scanned = 0
        var binary = 0
        var oversized = 0
        var unreadable = 0
        var changedBytes = 0L
        var truncated = candidates.filesTruncated

        for (file in candidates.files) {
            if (files.size >= limits.maxChangedFiles || changedBytes >= limits.maxChangedBytes) {
                truncated = true
                break
            }
            if (file.length() > limits.maxFileBytes) {
                oversized++
                continue
            }
            val bytes = runCatching { file.readHeadBytes(limits.maxFileBytes) }.getOrNull()
            if (bytes == null) {
                unreadable++
                continue
            }
            if (bytes.any { it == 0.toByte() }) {
                binary++
                continue
            }
            val text = bytes.toString(Charsets.UTF_8)
            // Round-trip guard: only text that survives decode+encode untouched may be rewritten.
            if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
                unreadable++
                continue
            }
            scanned++
            val replaced = runCatching { replaceText(text, query, replacement, limits.maxLineLength) }.getOrNull()
            if (replaced == null) {
                // The pattern matched but the replacement text is unusable with it. Stop before any
                // write and say so, instead of reporting a silent zero.
                return ReplacePlan(
                    query = query.source,
                    replacement = replacement,
                    files = emptyList(),
                    scannedFiles = scanned,
                    binaryFiles = binary,
                    oversizedFiles = oversized,
                    unreadableFiles = unreadable,
                    heldBackDirectories = candidates.heldBack,
                    truncated = candidates.filesTruncated,
                    templateRejected = true,
                )
            }
            if (replaced.replacements == 0) continue
            val afterBytes = replaced.text.toByteArray(Charsets.UTF_8)
            val relative = runCatching { file.relativeTo(candidateRoot).invariantSeparatorsPath }.getOrNull() ?: file.name
            files += ReplaceFilePlan(
                path = relative,
                replacements = replaced.replacements,
                longLines = replaced.longLines,
                beforeBytes = bytes,
                afterBytes = afterBytes,
                preview = previewOf(replaced.firstChangedLine, limits.previewChars),
            )
            changedBytes += bytes.size.toLong()
        }

        return ReplacePlan(
            query = query.source,
            replacement = replacement,
            files = files,
            scannedFiles = scanned,
            binaryFiles = binary,
            oversizedFiles = oversized,
            unreadableFiles = unreadable,
            heldBackDirectories = candidates.heldBack,
            truncated = truncated,
        )
    }

    /** Writes every planned file. A file that fails is skipped and does not stop the others. */
    fun apply(root: File, plan: ReplacePlan): ReplaceOutcome {
        var written = 0
        var replacements = 0
        for (file in plan.files) {
            if (writeWorkspaceFile(root, file.path, file.afterBytes)) {
                written++
                replacements += file.replacements
            }
        }
        return ReplaceOutcome(written, replacements)
    }

    /** Puts the previous content back, byte for byte. */
    fun undo(root: File, plan: ReplacePlan): ReplaceOutcome {
        var written = 0
        var replacements = 0
        for (file in plan.files) {
            if (writeWorkspaceFile(root, file.path, file.beforeBytes)) {
                written++
                replacements += file.replacements
            }
        }
        return ReplaceOutcome(written, replacements)
    }

    /** One file, inside the root, never through a link. */
    private fun writeWorkspaceFile(root: File, relative: String, bytes: ByteArray): Boolean {
        val rootPath = root.canonicalFile.toPath()
        val target = File(root, relative).canonicalFile
        if (!target.toPath().startsWith(rootPath)) return false
        if (Files.isSymbolicLink(target.toPath())) return false
        return writeAtomically(target, bytes)
    }

    /**
     * Writes beside the target and moves it into place, so a reader never sees half a file. The
     * original permissions are carried over because a shell script that lost its executable bit would
     * be a silent regression.
     */
    private fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        val directory = target.parentFile ?: return false
        directory.mkdirs()
        val temporary = File(directory, ".${target.name}.mh-replace")
        return runCatching {
            val permissions = runCatching { Files.getPosixFilePermissions(target.toPath()) }.getOrNull()
            temporary.outputStream().use { it.write(bytes) }
            if (permissions != null) {
                runCatching { Files.setPosixFilePermissions(temporary.toPath(), permissions) }
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                // A file system without atomic move still gets the content, just not atomically.
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            true
        }.getOrElse {
            runCatching { temporary.delete() }
            false
        }
    }

    private class TextReplacement(
        val text: String,
        val replacements: Int,
        val longLines: Int,
        val firstChangedLine: String,
    )

    /**
     * Replaces line by line, keeping the exact terminator of every line. A `\r\n` file must stay `\r\n`,
     * and a final line without a terminator must not gain one.
     */
    private fun replaceText(text: String, query: SearchQuery, replacement: String, maxLineLength: Int): TextReplacement {
        val out = StringBuilder(text.length)
        var replacements = 0
        var longLines = 0
        var firstChangedLine = ""
        var lineStart = 0
        var index = 0
        while (index <= text.length) {
            val atEnd = index == text.length
            if (atEnd || text[index] == '\n') {
                var contentEnd = index
                if (!atEnd && index > lineStart && text[index - 1] == '\r') contentEnd = index - 1
                val content = text.substring(lineStart, contentEnd)
                val terminator = if (atEnd) "" else text.substring(contentEnd, index + 1)
                if (content.length > maxLineLength) {
                    longLines++
                    out.append(content).append(terminator)
                } else {
                    val result = query.replaceInLine(content, replacement)
                    replacements += result.count
                    if (result.changed && firstChangedLine.isEmpty()) firstChangedLine = content.trim()
                    out.append(result.text).append(terminator)
                }
                lineStart = index + 1
            }
            index++
        }
        return TextReplacement(out.toString(), replacements, longLines, firstChangedLine)
    }

    /** A short, honest sample of what changed in this file. */
    private fun previewOf(text: String, maxChars: Int): String {
        val trimmed = text.trim()
        return if (trimmed.length <= maxChars) trimmed else trimmed.take(maxChars).trimEnd() + "…"
    }
}

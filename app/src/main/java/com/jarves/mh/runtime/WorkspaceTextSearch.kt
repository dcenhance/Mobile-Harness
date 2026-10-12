package com.jarves.mh.runtime

import com.jarves.mh.text.literalMatchRanges
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** One literal match inside one workspace file. */
data class WorkspaceSearchMatch(
    /** Workspace-relative path with `/` separators. */
    val path: String,
    /** 1-based line number of the match. */
    val line: Int,
    /** 1-based column of the match inside that line. */
    val column: Int,
    /** Trimmed, bounded copy of the matching line. */
    val preview: String,
)

/**
 * Result of one project-wide search.
 *
 * The counters are one universe and reconcile: [candidateFiles] is what the walk counted and
 * [scannedFiles] + [binaryFiles] + [oversizedFiles] + [unreadableFiles] is what happened to it.
 * [heldBackDirectories] names the generated/private trees that were never entered. [truncated] says
 * a ceiling stopped the scan, so [matches] is a prefix and not a total.
 */
data class WorkspaceSearchResult(
    val query: String,
    val matches: List<WorkspaceSearchMatch>,
    val candidateFiles: Int,
    val scannedFiles: Int,
    val binaryFiles: Int,
    val oversizedFiles: Int,
    val unreadableFiles: Int,
    val heldBackDirectories: List<String>,
    val truncated: Boolean,
    /** First 8 hex of sha256 over the sorted candidate path list: equal id = same file set. */
    val fileSetId: String,
) {
    /** Files that produced at least one match. */
    val matchingFiles: Int get() = matches.map { it.path }.distinct().size

    companion object {
        fun idle(query: String = ""): WorkspaceSearchResult = WorkspaceSearchResult(
            query = query,
            matches = emptyList(),
            candidateFiles = 0,
            scannedFiles = 0,
            binaryFiles = 0,
            oversizedFiles = 0,
            unreadableFiles = 0,
            heldBackDirectories = emptyList(),
            truncated = false,
            fileSetId = "",
        )
    }
}

/** Bounds for one search. Every ceiling is reported rather than hidden. */
data class WorkspaceSearchLimits(
    val maxFiles: Int = 2000,
    val maxFileBytes: Int = 512 * 1024,
    val maxScannedBytes: Long = 24L * 1024 * 1024,
    val maxMatches: Int = 500,
    val maxPreviewChars: Int = 160,
)

/**
 * Literal, case-insensitive project search.
 *
 * Directories follow the file browser's visibility rules (no symlinks, no `.claude*` runtime
 * metadata, nothing outside the project root) and additionally skip the generated/private trees that
 * only hold build output. Files are read with a per-file ceiling, and a NUL byte marks a file as
 * binary instead of searching it — only a search classifies content, a listing does not.
 */
object WorkspaceTextSearch {

    /** Generated or private trees the browser shows but a text search refuses to enter. */
    val HELD_BACK_DIRECTORIES = listOf(
        ".git", ".gradle", ".kotlin", ".idea", ".cxx", ".externalNativeBuild", "build", "node_modules",
    )

    private val BINARY_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svgz", "apk", "aab", "apks", "jar", "aar",
        "dex", "so", "dylib", "dll", "class", "zip", "gz", "xz", "zst", "bz2", "7z", "tar", "rar",
        "pdf", "ttf", "otf", "woff", "woff2", "mp3", "m4a", "ogg", "opus", "wav", "flac", "mp4",
        "mov", "mkv", "webm", "db", "sqlite", "keystore", "jks", "bin", "exe", "whl", "pyc", "nupkg",
    )

    fun search(root: File, query: String, limits: WorkspaceSearchLimits = WorkspaceSearchLimits()): WorkspaceSearchResult {
        val needle = query.trim()
        val candidateRoot = root.takeIf { it.isDirectory }?.canonicalFile
            ?: return WorkspaceSearchResult.idle(needle)
        if (needle.isEmpty()) return WorkspaceSearchResult.idle(needle)

        val candidates = collect(candidateRoot, limits)
        val matches = ArrayList<WorkspaceSearchMatch>()
        var scanned = 0
        var binary = 0
        var oversized = 0
        var unreadable = 0
        var scannedBytes = 0L
        var truncated = candidates.filesTruncated

        for (file in candidates.files) {
            if (matches.size >= limits.maxMatches || scannedBytes >= limits.maxScannedBytes) {
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
            val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
            if (text == null) {
                unreadable++
                continue
            }
            scanned++
            scannedBytes += bytes.size
            val relative = file.relativeTo(candidateRoot).invariantSeparatorsPath
            collectMatches(relative, text, needle, limits, matches)
        }

        return WorkspaceSearchResult(
            query = needle,
            matches = matches,
            candidateFiles = candidates.files.size,
            scannedFiles = scanned,
            binaryFiles = binary,
            oversizedFiles = oversized,
            unreadableFiles = unreadable,
            heldBackDirectories = candidates.heldBack,
            truncated = truncated,
            fileSetId = fileSetId(candidates.files, candidateRoot),
        )
    }

    /** First 8 hex of sha256 over the sorted candidate path list. */
    fun fileSetId(files: List<File>, root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.asSequence()
            .map { runCatching { it.relativeTo(root).invariantSeparatorsPath }.getOrNull() ?: it.name }
            .sorted()
            .forEach {
                digest.update(it.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(8)
    }

    private class Candidates(val files: List<File>, val heldBack: List<String>, val filesTruncated: Boolean)

    private fun collect(root: File, limits: WorkspaceSearchLimits): Candidates {
        val rootPath = root.toPath()
        val files = ArrayList<File>()
        val heldBack = LinkedHashSet<String>()
        var filesTruncated = false
        val pending = ArrayDeque<File>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            val children = runCatching { directory.listFiles() }.getOrNull() ?: continue
            for (child in children.sortedBy { it.name }) {
                val relative = runCatching { child.relativeTo(root).invariantSeparatorsPath }.getOrNull() ?: continue
                if (isRuntimeMetadata(relative)) continue
                // Never follow a link: the browser hides them and a search must not escape the root.
                if (Files.isSymbolicLink(child.toPath())) continue
                if (!runCatching { child.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)) continue
                when {
                    child.isDirectory -> {
                        if (child.name in HELD_BACK_DIRECTORIES) {
                            heldBack += relative
                        } else {
                            pending.add(child)
                        }
                    }
                    child.isFile -> {
                        if (looksBinary(child.name)) continue
                        if (files.size >= limits.maxFiles) {
                            filesTruncated = true
                            continue
                        }
                        files.add(child)
                    }
                }
            }
        }
        return Candidates(files, heldBack.sorted(), filesTruncated)
    }

    private fun looksBinary(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension.isNotEmpty() && extension in BINARY_EXTENSIONS
    }

    private fun isRuntimeMetadata(relativePath: String): Boolean =
        relativePath == ".claude" || relativePath == ".claude.json" || relativePath.startsWith(".claude/")

    private fun collectMatches(
        relative: String,
        text: String,
        needle: String,
        limits: WorkspaceSearchLimits,
        into: MutableList<WorkspaceSearchMatch>,
    ) {
        var lineNumber = 0
        for (rawLine in text.lineSequence()) {
            lineNumber++
            if (into.size >= limits.maxMatches) return
            val ranges = literalMatchRanges(rawLine, needle, limits.maxMatches - into.size)
            if (ranges.isEmpty()) continue
            val preview = rawLine.trim().let {
                if (it.length <= limits.maxPreviewChars) it else it.take(limits.maxPreviewChars).trimEnd() + "…"
            }
            for (range in ranges) {
                into.add(
                    WorkspaceSearchMatch(
                        path = relative,
                        line = lineNumber,
                        column = range.first + 1,
                        preview = preview,
                    ),
                )
            }
        }
    }
}

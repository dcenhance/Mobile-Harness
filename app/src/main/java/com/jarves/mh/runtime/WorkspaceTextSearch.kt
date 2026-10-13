package com.jarves.mh.runtime

import com.jarves.mh.text.SearchOptions
import com.jarves.mh.text.SearchQuery
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
    /** The options this result was produced with, so a replace can be judged by the same rules. */
    val options: SearchOptions = SearchOptions(),
    /** True when the text is not something this search can run with (an unusable pattern). */
    val invalidQuery: Boolean = false,
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
    val HELD_BACK_DIRECTORIES = WorkspaceFileWalk.HELD_BACK_DIRECTORIES

    fun search(
        root: File,
        query: String,
        limits: WorkspaceSearchLimits = WorkspaceSearchLimits(),
        options: SearchOptions = SearchOptions(),
    ): WorkspaceSearchResult {
        val needle = query.trim()
        val candidateRoot = root.takeIf { it.isDirectory }?.canonicalFile
            ?: return WorkspaceSearchResult.idle(needle)
        if (needle.isEmpty()) return WorkspaceSearchResult.idle(needle)
        // One compiled query for the whole run: what the list shows is what a replace would rewrite.
        val compiled = SearchQuery.compile(needle, options)
            ?: return WorkspaceSearchResult.idle(needle).copy(invalidQuery = true, options = options)

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
            collectMatches(relative, text, compiled, limits, matches)
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
            options = options,
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

    /** The walk itself lives in [WorkspaceFileWalk] because a replace must see the same world. */
    private fun collect(root: File, limits: WorkspaceSearchLimits): Candidates {
        val candidates = WorkspaceFileWalk.collect(root, limits.maxFiles)
        return Candidates(candidates.files, candidates.heldBack, candidates.filesTruncated)
    }

    private fun isRuntimeMetadata(relativePath: String): Boolean = WorkspaceFileWalk.isRuntimeMetadata(relativePath)

    private fun collectMatches(
        relative: String,
        text: String,
        query: SearchQuery,
        limits: WorkspaceSearchLimits,
        into: MutableList<WorkspaceSearchMatch>,
    ) {
        var lineNumber = 0
        for (rawLine in text.lineSequence()) {
            lineNumber++
            if (into.size >= limits.maxMatches) return
            val ranges = query.findAll(rawLine, limits.maxMatches - into.size)
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

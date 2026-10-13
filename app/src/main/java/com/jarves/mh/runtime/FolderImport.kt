package com.jarves.mh.runtime

import java.io.File
import java.io.InputStream

/**
 * Importing a folder the user picked on the phone into the project workspace.
 *
 * The phone is where real sources live (a download, a shared archive, another app's export), but the
 * app could only accept single files, which loses every directory. A tree import needs rules, and those
 * rules are here as plain functions so they can be tested with real directories instead of a device:
 *
 * - build output and tool caches are skipped, because a workspace that suddenly holds `build/` or
 *   `node_modules/` is unusable for the agents;
 * - every path stays inside the destination (no absolute paths, no `..`, no NUL);
 * - the import stops at hard caps instead of filling the phone's storage, and says that it stopped;
 * - names are sanitised and collisions get the app's usual `-2` suffix.
 *
 * [FolderImporter] works against a [FolderSource], so the same loop runs against a picked document tree
 * on Android and against a real directory in tests.
 */
object FolderImport {

    /** Directories whose contents are generated, cached or version-control data. */
    val SKIPPED_DIRECTORIES = setOf(
        ".git", ".gradle", ".idea", ".cxx", ".venv", "venv", "__pycache__", ".pytest_cache",
        "node_modules", "build", "dist", ".next", ".dart_tool", "Pods", ".mypy_cache", ".kotlin",
    )

    /** Editor and operating-system droppings that survive a copy. */
    val SKIPPED_FILES = setOf(".DS_Store", "Thumbs.db", "desktop.ini", ".localized")

    const val MAX_ENTRIES = 20_000
    const val MAX_BYTES = 2L * 1024L * 1024L * 1024L
    const val MAX_DEPTH = 24
    const val MAX_NAME_LENGTH = 120

    fun shouldSkipDirectory(name: String): Boolean = name.lowercase() in SKIPPED_DIRECTORIES

    fun shouldSkipFile(name: String): Boolean = name in SKIPPED_FILES

    /** "512 KB", "18.4 MB" — for the message the user reads, never for arithmetic. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kilobytes = bytes / 1024.0
        if (kilobytes < 1024) return "%.0f KB".format(kilobytes)
        val megabytes = kilobytes / 1024.0
        if (megabytes < 1024) return "%.1f MB".format(megabytes)
        return "%.2f GB".format(megabytes / 1024.0)
    }

    /**
     * A single path segment that may be written into the workspace: not blank, not a traversal, no
     * separator, no control characters. The check happens per segment, so it also holds for a whole
     * relative path.
     */
    fun isSafeSegment(name: String): Boolean {
        if (name.isBlank() || name == "." || name == "..") return false
        if (name.length > MAX_NAME_LENGTH) return false
        if (name.any { it.isISOControl() }) return false
        return !name.contains('/') && !name.contains('\\')
    }

    /** Replaces whatever a file system may dislike; the result is always a safe single segment. */
    fun sanitizeSegment(name: String): String {
        val cleaned = name.replace('/', '_').replace('\\', '_')
            .map { if (it.isISOControl()) '_' else it }
            .joinToString("")
            .trim()
            .trimEnd('.')
        val collapsed = cleaned.ifBlank { "folder" }
        return if (collapsed.length > MAX_NAME_LENGTH) collapsed.take(MAX_NAME_LENGTH) else collapsed
    }

    /**
     * The app's collision convention: `notes.txt` becomes `notes-2.txt`, then `notes-3.txt`. Deterministic
     * so a retry lands on the same name instead of scattering copies.
     */
    fun uniqueName(existing: Set<String>, name: String): String {
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var suffix = 2
        while (true) {
            val candidate = "$stem-$suffix$extension"
            if (candidate !in existing) return candidate
            suffix++
        }
    }
}

/** A directory tree to read from: the picked document tree on Android, a real directory in tests. */
interface FolderSource {

    /** The folder name the user picked, used as the destination folder name. */
    val rootName: String

    data class Node(val name: String, val isDirectory: Boolean, val size: Long)

    /** Direct children of [relativePath] ("" is the picked folder itself). */
    fun children(relativePath: String): List<Node>

    /** Opens one file for reading; the caller closes it. */
    fun open(relativePath: String): InputStream
}

/** A [FolderSource] over a real directory — used for imported local trees and in tests. */
class DirectoryFolderSource(private val root: File) : FolderSource {

    override val rootName: String get() = root.name

    override fun children(relativePath: String): List<FolderSource.Node> {
        val directory = if (relativePath.isEmpty()) root else File(root, relativePath)
        val listing = directory.listFiles() ?: return emptyList()
        return listing.map { FolderSource.Node(it.name, it.isDirectory, it.length()) }
    }

    override fun open(relativePath: String): InputStream = File(root, relativePath).inputStream()
}

/** What one import did, in words the user can check. */
data class FolderImportResult(
    val folderName: String,
    val entries: Int,
    val bytes: Long,
    val skippedDirectories: Int,
    val skippedFiles: Int,
    val skippedUnsafe: Int,
    val truncated: Boolean,
    val cancelled: Boolean,
)

/**
 * The limits one import obeys. The defaults are the shipping values; a test lowers them instead of
 * writing two gigabytes to disk.
 */
data class FolderImportCaps(
    val maxEntries: Int = FolderImport.MAX_ENTRIES,
    val maxBytes: Long = FolderImport.MAX_BYTES,
    val maxDepth: Int = FolderImport.MAX_DEPTH,
)

/**
 * Copies a [FolderSource] into [targetDirectory]. Never throws for a single unreadable file — it counts
 * the failure and continues, because one broken file should not lose a whole import.
 */
class FolderImporter(
    private val source: FolderSource,
    private val targetDirectory: File,
    private val caps: FolderImportCaps = FolderImportCaps(),
    private val onProgress: (entries: Int, bytes: Long) -> Unit = { _, _ -> },
    private val isCancelled: () -> Boolean = { false },
) {

    private var entries = 0
    private var bytes = 0L
    private var skippedDirectories = 0
    private var skippedFiles = 0
    private var skippedUnsafe = 0
    private var truncated = false
    private var cancelled = false

    fun run(): FolderImportResult {
        targetDirectory.mkdirs()
        val targetPath = targetDirectory.canonicalFile.toPath()
        walk("", targetDirectory, targetPath, depth = 0)
        return FolderImportResult(
            folderName = targetDirectory.name,
            entries = entries,
            bytes = bytes,
            skippedDirectories = skippedDirectories,
            skippedFiles = skippedFiles,
            skippedUnsafe = skippedUnsafe,
            truncated = truncated,
            cancelled = cancelled,
        )
    }

    private fun walk(relativePath: String, directory: File, targetPath: java.nio.file.Path, depth: Int) {
        if (truncated || cancelled) return
        if (depth > caps.maxDepth) {
            truncated = true
            return
        }
        val nodes = source.children(relativePath).sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        for (node in nodes) {
            if (cancelled) return
            if (isCancelled()) {
                cancelled = true
                return
            }
            if (!FolderImport.isSafeSegment(node.name)) {
                skippedUnsafe++
                continue
            }
            val childRelative = if (relativePath.isEmpty()) node.name else "$relativePath/${node.name}"
            if (node.isDirectory) {
                if (FolderImport.shouldSkipDirectory(node.name)) {
                    skippedDirectories++
                    continue
                }
                val child = File(directory, node.name)
                if (!child.canonicalFile.toPath().startsWith(targetPath)) {
                    skippedUnsafe++
                    continue
                }
                child.mkdirs()
                walk(childRelative, child, targetPath, depth + 1)
            } else {
                if (FolderImport.shouldSkipFile(node.name)) {
                    skippedFiles++
                    continue
                }
                if (entries >= caps.maxEntries || bytes >= caps.maxBytes) {
                    truncated = true
                    return
                }
                val child = File(directory, node.name)
                if (!child.canonicalFile.toPath().startsWith(targetPath)) {
                    skippedUnsafe++
                    continue
                }
                val copied = copyFile(childRelative, child)
                if (copied < 0) {
                    skippedFiles++
                } else {
                    entries++
                    bytes += copied
                    onProgress(entries, bytes)
                }
            }
        }
    }

    /** Returns the number of bytes copied, or -1 when the file could not be read. */
    private fun copyFile(relativePath: String, destination: File): Long {
        destination.parentFile?.mkdirs()
        return runCatching {
            var copied = 0L
            source.open(relativePath).use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (bytes + copied > caps.maxBytes) {
                            truncated = true
                            break
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            copied
        }.getOrElse {
            destination.delete()
            -1L
        }
    }
}

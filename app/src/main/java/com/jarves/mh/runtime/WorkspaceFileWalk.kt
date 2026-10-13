package com.jarves.mh.runtime

import java.io.File
import java.nio.file.Files

/**
 * The one directory walk the project-wide text features share.
 *
 * A search and a replace must see the same world, otherwise "find" would offer files that "replace"
 * refuses (or worse, the reverse). The rules are the file browser's visibility plus the generated and
 * private trees that only hold build output: no symlinks, nothing outside the project root, no
 * `.claude*` runtime metadata, and a file-name based binary guess so obviously binary files are never
 * read as text.
 */
object WorkspaceFileWalk {

    /** Generated or private trees the browser shows but text features refuse to enter. */
    val HELD_BACK_DIRECTORIES = listOf(
        ".git", ".gradle", ".kotlin", ".idea", ".cxx", ".externalNativeBuild", "build", "node_modules",
    )

    private val BINARY_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svgz", "apk", "aab", "apks", "jar", "aar",
        "dex", "so", "dylib", "dll", "class", "zip", "gz", "xz", "zst", "bz2", "7z", "tar", "rar",
        "pdf", "ttf", "otf", "woff", "woff2", "mp3", "m4a", "ogg", "opus", "wav", "flac", "mp4",
        "mov", "mkv", "webm", "db", "sqlite", "keystore", "jks", "bin", "exe", "whl", "pyc", "nupkg",
    )

    class Candidates(val files: List<File>, val heldBack: List<String>, val filesTruncated: Boolean)

    /**
     * Every file that may be read as text, in a stable order. [filesTruncated] says the file ceiling
     * stopped the walk, so the list is a prefix and not everything.
     */
    fun collect(root: File, maxFiles: Int): Candidates {
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
                // Never follow a link: the browser hides them and a text pass must not escape the root.
                if (Files.isSymbolicLink(child.toPath())) continue
                if (!runCatching { child.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)) continue
                when {
                    child.isDirectory -> {
                        if (child.name in HELD_BACK_DIRECTORIES) heldBack += relative else pending.add(child)
                    }

                    child.isFile -> {
                        if (looksBinary(child.name)) continue
                        if (files.size >= maxFiles) {
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

    fun looksBinary(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension.isNotEmpty() && extension in BINARY_EXTENSIONS
    }

    fun isRuntimeMetadata(relativePath: String): Boolean =
        relativePath == ".claude" || relativePath == ".claude.json" || relativePath.startsWith(".claude/")
}

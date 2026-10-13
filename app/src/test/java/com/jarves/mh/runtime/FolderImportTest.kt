package com.jarves.mh.runtime

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folder import runs against real files on disk: a real tree in, real files out, compared byte for
 * byte. Only the document-tree layer of Android is replaced (by a small in-memory tree), so the rules,
 * the caps, the crash-tolerance and the escape checks are exercised as written.
 */
class FolderImportTest {

    private val workspace = File("build/tmp/folder-import-test")
    private val root = File(workspace, UUID.randomUUID().toString())

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun writeTree(structure: Map<String, String>): File {
        val source = File(root, "source")
        structure.forEach { (path, content) ->
            val file = File(source, path)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
        return source
    }

    private fun target(name: String = "imported"): File = File(root, "target/$name")

    /** A tree that is not a directory on disk: used for names a real file system would refuse. */
    private class FakeSource(
        override val rootName: String,
        private val tree: Map<String, List<FolderSource.Node>>,
        private val contents: Map<String, ByteArray> = emptyMap(),
        private val failOn: Set<String> = emptySet(),
    ) : FolderSource {
        var opened = 0
            private set

        override fun children(relativePath: String): List<FolderSource.Node> = tree[relativePath] ?: emptyList()

        override fun open(relativePath: String): InputStream {
            opened++
            if (relativePath in failOn) throw IllegalStateException("unreadable")
            return ByteArrayInputStream(contents[relativePath] ?: "fake:$relativePath".toByteArray())
        }
    }

    private fun fileNode(name: String, size: Long = 4) = FolderSource.Node(name, isDirectory = false, size = size)
    private fun dirNode(name: String) = FolderSource.Node(name, isDirectory = true, size = 0)

    @Test
    fun `a real tree is copied with its structure and content`() {
        val source = writeTree(
            mapOf(
                "README.md" to "# Project\n",
                "app/src/main.kt" to "fun main() = Unit\n",
                "app/src/deep/deeper/file.txt" to "nested\n",
                "docs/notes/idea.md" to "idea\n",
            ),
        )
        val result = FolderImporter(DirectoryFolderSource(source), target()).run()

        assertEquals(4, result.entries)
        assertEquals(0, result.skippedDirectories)
        assertFalse(result.truncated)
        assertFalse(result.cancelled)
        assertEquals(
            "# Project\n",
            File(root, "target/imported/README.md").readText(),
        )
        assertEquals(
            "fun main() = Unit\n",
            File(root, "target/imported/app/src/main.kt").readText(),
        )
        assertEquals("nested\n", File(root, "target/imported/app/src/deep/deeper/file.txt").readText())
        val expectedBytes = listOf("README.md", "app/src/main.kt", "app/src/deep/deeper/file.txt", "docs/notes/idea.md")
            .sumOf { File(source, it).length() }
        assertEquals(expectedBytes, result.bytes)
    }

    @Test
    fun `build output and caches stay out of the workspace`() {
        val source = writeTree(
            mapOf(
                "src/main.kt" to "keep\n",
                "build/outputs/app.apk" to "generated\n",
                "node_modules/left-pad/index.js" to "cached\n",
                ".git/HEAD" to "ref\n",
                ".gradle/state.bin" to "cache\n",
                "app/build/intermediates/x.txt" to "generated\n",
                ".DS_Store" to "junk\n",
            ),
        )
        val result = FolderImporter(DirectoryFolderSource(source), target()).run()

        assertEquals(1, result.entries)
        assertTrue("build, node_modules, .git and .gradle must be counted", result.skippedDirectories >= 4)
        assertEquals(1, result.skippedFiles)
        assertTrue(File(root, "target/imported/src/main.kt").isFile)
        assertFalse(File(root, "target/imported/build").exists())
        assertFalse(File(root, "target/imported/node_modules").exists())
        assertFalse(File(root, "target/imported/.git").exists())
        assertFalse(File(root, "target/imported/.DS_Store").exists())
    }

    @Test
    fun `paths that would escape the destination are refused`() {
        val fake = FakeSource(
            rootName = "picked",
            tree = mapOf(
                "" to listOf(dirNode("ok"), fileNode(".."), fileNode("a/b"), fileNode(""), fileNode("with\u0000nul"), fileNode(".hidden-ok")),
                "ok" to listOf(fileNode("inside.txt")),
            ),
        )
        val destination = target()
        val result = FolderImporter(fake, destination).run()

        assertEquals(4, result.skippedUnsafe)
        assertEquals(2, result.entries)
        assertTrue(File(root, "target/imported/ok/inside.txt").isFile)
        assertTrue(File(root, "target/imported/.hidden-ok").isFile)
        // Nothing may appear beside the destination folder.
        assertEquals(listOf("imported"), File(root, "target").list()!!.sorted())
    }

    @Test
    fun `an unreadable file is counted and the import continues`() {
        val fake = FakeSource(
            rootName = "picked",
            tree = mapOf("" to listOf(fileNode("a.txt"), fileNode("broken.txt"), fileNode("b.txt"))),
            failOn = setOf("broken.txt"),
        )
        val result = FolderImporter(fake, target()).run()

        assertEquals(2, result.entries)
        assertEquals(1, result.skippedFiles)
        assertFalse(result.truncated)
        assertTrue(File(root, "target/imported/a.txt").isFile)
        assertTrue(File(root, "target/imported/b.txt").isFile)
        assertFalse("a failed file must not be left behind", File(root, "target/imported/broken.txt").exists())
    }

    @Test
    fun `too many entries stop the import and say so`() {
        val cap = 50
        val many = (1..cap + 30).map { fileNode("file-$it.txt") }
        val fake = FakeSource(rootName = "picked", tree = mapOf("" to many))
        val result = FolderImporter(fake, target(), FolderImportCaps(maxEntries = cap)).run()

        assertEquals(cap, result.entries)
        assertTrue(result.truncated)
        assertEquals(cap, File(root, "target/imported").list()!!.size)
    }

    @Test
    fun `the byte cap is a cap and is reported`() {
        val chunk = ByteArray(1 shl 20)
        val capBytes = 3L * 1024L * 1024L
        val fake = object : FolderSource {
            override val rootName = "picked"
            override fun children(relativePath: String): List<FolderSource.Node> =
                if (relativePath.isEmpty()) (1..8).map { fileNode("big-$it.bin", chunk.size.toLong()) } else emptyList()

            override fun open(relativePath: String): InputStream = ByteArrayInputStream(chunk)
        }
        val result = FolderImporter(fake, target(), FolderImportCaps(maxBytes = capBytes)).run()

        assertTrue("must stop at the cap", result.truncated)
        assertTrue("bytes copied must respect the cap", result.bytes <= capBytes)
        assertTrue("the cap is a total, not a per-file limit", result.bytes >= capBytes - chunk.size)
    }

    @Test
    fun `deep trees stop at the depth cap`() {
        val tree = mutableMapOf<String, List<FolderSource.Node>>()
        val path = StringBuilder()
        for (level in 1..FolderImport.MAX_DEPTH + 4) {
            val parent = path.toString()
            val name = "level$level"
            tree[parent] = listOf(dirNode(name))
            path.append(if (parent.isEmpty()) name else "/$name")
        }
        tree[path.toString()] = listOf(fileNode("bottom.txt"))
        val result = FolderImporter(FakeSource("picked", tree), target()).run()

        assertTrue(result.truncated)
        assertFalse("the deepest file must not be written", File(root, "target/imported/$path/bottom.txt").exists())
    }

    @Test
    fun `cancelling keeps what was copied and reports the cancellation`() {
        val fake = FakeSource(
            rootName = "picked",
            tree = mapOf("" to (1..20).map { fileNode("file-$it.txt") }),
        )
        var seen = 0
        val importer = FolderImporter(
            source = fake,
            targetDirectory = target(),
            onProgress = { _, _ -> seen++ },
            isCancelled = { seen >= 3 },
        )
        val result = importer.run()

        assertTrue(result.cancelled)
        assertFalse(result.truncated)
        assertEquals(3, seen)
    }

    @Test
    fun `progress reports every copied file once`() {
        val source = writeTree(mapOf("a.txt" to "aaaa", "b/c.txt" to "cc", "b/d.txt" to "dddddd"))
        val updates = mutableListOf<Pair<Int, Long>>()
        val result = FolderImporter(DirectoryFolderSource(source), target(), onProgress = { entries, bytes ->
            updates += entries to bytes
        }).run()

        assertEquals(3, result.entries)
        assertEquals(listOf(1, 2, 3), updates.map { it.first })
        assertEquals(12L, updates.last().second)
    }

    @Test
    fun `a collision with an existing name gets the app's suffix`() {
        val source = writeTree(mapOf("main.kt" to "new\n"))
        val destination = target()
        File(root, "target").mkdirs()
        File(destination, "main.kt").apply { parentFile?.mkdirs(); writeText("old\n") }

        val existing = destination.list()!!.toSet()
        val chosen = FolderImport.uniqueName(existing, "main.kt")
        assertEquals("main-2.kt", chosen)
        assertEquals("x", FolderImport.uniqueName(emptySet(), "x"))
        assertEquals("notes-2.txt", FolderImport.uniqueName(setOf("notes.txt"), "notes.txt"))
        // A suffix that is already taken is skipped, not reused.
        assertEquals("notes-3.txt", FolderImport.uniqueName(setOf("notes.txt", "notes-2.txt"), "notes.txt"))
        assertEquals("plain-3", FolderImport.uniqueName(setOf("plain", "plain-2"), "plain"))
    }

    @Test
    fun `segment checks and sanitising follow one rule`() {
        assertTrue(FolderImport.isSafeSegment("main.kt"))
        assertTrue(FolderImport.isSafeSegment(".env"))
        assertFalse(FolderImport.isSafeSegment(".."))
        assertFalse(FolderImport.isSafeSegment("."))
        assertFalse(FolderImport.isSafeSegment(""))
        assertFalse(FolderImport.isSafeSegment("   "))
        assertFalse(FolderImport.isSafeSegment("a/b"))
        assertFalse(FolderImport.isSafeSegment("a\\b"))
        assertFalse(FolderImport.isSafeSegment("n\u0000l"))
        assertFalse(FolderImport.isSafeSegment("x".repeat(FolderImport.MAX_NAME_LENGTH + 1)))

        assertEquals("a_b", FolderImport.sanitizeSegment("a/b"))
        assertEquals("folder", FolderImport.sanitizeSegment(" "))
        assertEquals("name", FolderImport.sanitizeSegment("name."))
        assertTrue(
            "a sanitised name must always be usable as one segment",
            FolderImport.isSafeSegment(FolderImport.sanitizeSegment("../etc")),
        )
        assertTrue(FolderImport.sanitizeSegment("../etc").length <= FolderImport.MAX_NAME_LENGTH)
    }

    @Test
    fun `directory skipping is case insensitive and file skipping is exact`() {
        assertTrue(FolderImport.shouldSkipDirectory("Build"))
        assertTrue(FolderImport.shouldSkipDirectory("NODE_MODULES"))
        assertFalse(FolderImport.shouldSkipDirectory("sources"))
        assertTrue(FolderImport.shouldSkipFile(".DS_Store"))
        assertFalse(FolderImport.shouldSkipFile("ds_store"))
    }
}

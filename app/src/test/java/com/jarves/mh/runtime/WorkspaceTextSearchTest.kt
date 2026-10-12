package com.jarves.mh.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The real project search, run over real files in a temporary tree. */
class WorkspaceTextSearchTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("mh-workspace-search-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(relative: String, content: String = "", bytes: ByteArray? = null): File {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        if (bytes != null) file.writeBytes(bytes) else file.writeText(content)
        return file
    }

    private fun search(query: String, limits: WorkspaceSearchLimits = WorkspaceSearchLimits()) =
        WorkspaceTextSearch.search(root, query, limits)

    @Test
    fun `finds every occurrence with line and column`() {
        write("src/Main.kt", "class Main {\n    val plan = plan()\n}\n")

        val result = search("plan")

        assertEquals(2, result.matches.size)
        assertEquals(listOf("src/Main.kt", "src/Main.kt"), result.matches.map { it.path })
        assertEquals(listOf(2, 2), result.matches.map { it.line })
        assertEquals(listOf(9, 16), result.matches.map { it.column })
        assertFalse(result.truncated)
        assertEquals(1, result.matchingFiles)
    }

    @Test
    fun `matching is literal and case-insensitive`() {
        write("notes.md", "Deploy the DEPLOY script\n")

        val result = search("deploy")

        assertEquals(2, result.matches.size)
        assertEquals(1, result.matches.first().line)
        assertEquals("Deploy the DEPLOY script", result.matches.first().preview)
    }

    @Test
    fun `regex metacharacters stay literal`() {
        write("file.txt", "a.c\naXc\n")

        val result = search("a.c")

        assertEquals(1, result.matches.size)
        assertEquals(1, result.matches.single().line)
    }

    @Test
    fun `binary files are skipped by extension and by content`() {
        write("image.png", "not really a png but the name decides")
        write("blob.dat", bytes = byteArrayOf(1, 2, 3, 0, 4, 5))

        val result = search("png")

        assertEquals(0, result.matches.size)
        assertEquals("the .dat file was read and classified as binary", 1, result.binaryFiles)
        assertEquals("the .png never entered the candidate set", 1, result.candidateFiles)
    }

    @Test
    fun `generated directories are held back and named`() {
        write("build/output.txt", "needle in the build output")
        write("node_modules/pkg/index.js", "needle in a dependency")
        write(".git/COMMIT_EDITMSG", "needle in git metadata")
        write("src/real.txt", "needle in the source")

        val result = search("needle")

        assertEquals(1, result.matches.size)
        assertEquals("src/real.txt", result.matches.single().path)
        assertEquals(listOf(".git", "build", "node_modules"), result.heldBackDirectories)
        assertEquals(1, result.candidateFiles)
    }

    @Test
    fun `runtime metadata is never searched`() {
        write(".claude/settings.json", "needle in runtime metadata")
        write(".claude.json", "needle again")

        val result = search("needle")

        assertEquals(0, result.matches.size)
        assertEquals(0, result.candidateFiles)
    }

    @Test
    fun `symlinks are not followed`() {
        // The target lives outside the project root, so a followed link would leak a foreign file.
        val outside = Files.createTempDirectory("mh-workspace-outside-").toFile()
        try {
            val secret = File(outside, "secret.txt").apply { writeText("needle outside the project") }
            val link = File(root, "src/link.txt")
            link.parentFile?.mkdirs()
            Files.createSymbolicLink(link.toPath(), secret.toPath())
            write("src/real.txt", "needle inside the project")

            val result = search("needle")

            assertEquals(1, result.matches.size)
            assertEquals("src/real.txt", result.matches.single().path)
            assertFalse("a symlink must not be listed", result.matches.any { it.path.contains("link") })
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `oversized files are counted, not read`() {
        val limits = WorkspaceSearchLimits(maxFileBytes = 32)
        write("small.txt", "needle")
        write("large.txt", "needle " + "x".repeat(200))

        val result = search("needle", limits)

        assertEquals(1, result.matches.size)
        assertEquals("small.txt", result.matches.single().path)
        assertEquals(1, result.oversizedFiles)
        assertEquals(1, result.scannedFiles)
        assertEquals(result.candidateFiles, result.scannedFiles + result.binaryFiles + result.oversizedFiles + result.unreadableFiles)
    }

    @Test
    fun `a match ceiling is reported as truncation`() {
        write("many.txt", "hit\nhit\nhit\nhit\n")
        write("more.txt", "hit\n")

        val result = search("hit", WorkspaceSearchLimits(maxMatches = 2))

        assertEquals(2, result.matches.size)
        assertTrue("a capped list is not a total", result.truncated)
    }

    @Test
    fun `a file ceiling is reported as truncation`() {
        repeat(5) { write("f$it.txt", "hit") }

        val result = search("hit", WorkspaceSearchLimits(maxFiles = 3))

        assertEquals(3, result.candidateFiles)
        assertTrue(result.truncated)
    }

    @Test
    fun `an unlimited search of the same tree is not truncated`() {
        repeat(3) { write("f$it.txt", "hit") }

        val result = search("hit")

        assertEquals(3, result.matches.size)
        assertFalse(result.truncated)
        assertEquals(3, result.candidateFiles)
        assertEquals(3, result.scannedFiles)
    }

    @Test
    fun `file set id is stable for one tree and changes with it`() {
        write("a.txt", "alpha")
        write("b/c.txt", "beta")
        val first = search("alpha").fileSetId
        assertEquals(first, search("beta").fileSetId)

        write("d.txt", "delta")
        val second = search("delta").fileSetId

        assertNotEquals("a different file set must not share the id", first, second)
        assertEquals(8, first.length)
    }

    @Test
    fun `a blank query does no work`() {
        write("a.txt", "alpha")

        val result = search("   ")

        assertEquals(0, result.candidateFiles)
        assertEquals(0, result.scannedFiles)
        assertTrue(result.matches.isEmpty())
        assertFalse(result.truncated)
    }

    @Test
    fun `a missing project root is refused without pretending to be empty`() {
        val missing = File(root, "does-not-exist")

        val result = WorkspaceTextSearch.search(missing, "needle")

        assertEquals(0, result.matches.size)
        assertEquals(0, result.candidateFiles)
        assertEquals("", result.fileSetId)
    }

    @Test
    fun `the preview is trimmed and bounded`() {
        val long = "prefix " + "y".repeat(300) + " needle"
        write("long.txt", long)

        val preview = search("needle").matches.single().preview

        assertTrue(preview.length <= WorkspaceSearchLimits().maxPreviewChars + 1)
        assertFalse(preview.startsWith(" "))
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun `counters reconcile on a mixed tree`() {
        write("src/one.kt", "needle here\n")
        write("src/two.kt", "nothing\n")
        write("data.dat", bytes = byteArrayOf(0, 1, 2))
        write("huge.log", "needle " + "z".repeat(600))

        val result = search("needle", WorkspaceSearchLimits(maxFileBytes = 512))

        assertEquals(1, result.matches.size)
        assertEquals(4, result.candidateFiles)
        assertEquals(2, result.scannedFiles)
        assertEquals(1, result.binaryFiles)
        assertEquals(1, result.oversizedFiles)
        assertEquals(0, result.unreadableFiles)
        assertEquals(
            result.candidateFiles,
            result.scannedFiles + result.binaryFiles + result.oversizedFiles + result.unreadableFiles,
        )
        assertEquals(1, result.matchingFiles)
    }
}

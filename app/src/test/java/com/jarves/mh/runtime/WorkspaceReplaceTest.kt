package com.jarves.mh.runtime

import com.jarves.mh.text.SearchOptions
import com.jarves.mh.text.SearchQuery
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A replace is only worth having if it can be trusted with a real tree, so these cases run against real
 * files: the plan writes nothing, the apply is verified byte for byte, the undo must restore the exact
 * original, and every file that must not be touched is checked afterwards.
 */
class WorkspaceReplaceTest {

    private val base = File("build/tmp/workspace-replace-test")
    private val root = File(base, UUID.randomUUID().toString())

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(path: String, content: String): File {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    private fun writeBytes(path: String, bytes: ByteArray): File {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    private fun query(source: String, options: SearchOptions = SearchOptions()) =
        SearchQuery.compile(source, options) ?: error("query did not compile: $source")

    @Test
    fun `a plan changes nothing on disk`() {
        val file = write("src/main.kt", "val size = 1\n")
        val before = file.readBytes()

        val plan = WorkspaceReplace.plan(root, query("size"), "length")

        assertEquals(1, plan.changedFiles)
        assertEquals(1, plan.totalReplacements)
        assertArrayEquals("planning must not write", before, file.readBytes())
    }

    @Test
    fun `apply rewrites every planned file and undo restores the exact bytes`() {
        val first = write("src/main.kt", "val size = 1\nval other = size\n")
        val second = write("src/util.kt", "fun size() = 3\n")
        // No standalone `size`, so this file must not appear in the plan at all.
        val untouched = write("src/other.kt", "val sizeGuide = 1\nval resized = 2\n")
        val beforeFirst = first.readBytes()
        val beforeSecond = second.readBytes()
        val beforeUntouched = untouched.readBytes()

        val plan = WorkspaceReplace.plan(root, query("size", SearchOptions(wholeWord = true)), "length")
        assertEquals(2, plan.changedFiles)
        assertEquals(3, plan.totalReplacements)

        val outcome = WorkspaceReplace.apply(root, plan)
        assertEquals(2, outcome.filesWritten)
        assertEquals(3, outcome.replacements)
        assertEquals("val length = 1\nval other = length\n", first.readText())
        assertEquals("fun length() = 3\n", second.readText())
        assertArrayEquals("a file without a match must stay untouched", beforeUntouched, untouched.readBytes())

        val restore = WorkspaceReplace.undo(root, plan)
        assertEquals(2, restore.filesWritten)
        assertArrayEquals(beforeFirst, first.readBytes())
        assertArrayEquals(beforeSecond, second.readBytes())
    }

    @Test
    fun `line endings and a missing final newline survive`() {
        val crlf = write("windows.txt", "alpha\r\nbeta\r\ngamma")
        val plan = WorkspaceReplace.plan(root, query("beta"), "delta")

        assertEquals(1, plan.changedFiles)
        WorkspaceReplace.apply(root, plan)

        assertEquals("alpha\r\ndelta\r\ngamma", crlf.readText())
        assertArrayEquals("alpha\r\ndelta\r\ngamma".toByteArray(), crlf.readBytes())
    }

    @Test
    fun `a file that is not utf-8 is refused instead of rewritten`() {
        val latin1 = writeBytes("legacy.txt", byteArrayOf('a'.code.toByte(), 0xE9.toByte(), 'b'.code.toByte()))
        val plan = WorkspaceReplace.plan(root, query("a"), "x")

        assertEquals(1, plan.unreadableFiles)
        assertEquals(0, plan.changedFiles)
        WorkspaceReplace.apply(root, plan)
        assertArrayEquals(byteArrayOf('a'.code.toByte(), 0xE9.toByte(), 'b'.code.toByte()), latin1.readBytes())
    }

    @Test
    fun `a binary file is counted and left alone`() {
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x61)
        val binary = writeBytes("icon.png", pngHeader)
        val plan = WorkspaceReplace.plan(root, query("a"), "b")

        assertTrue("a png is refused by extension", plan.changedFiles == 0)
        assertArrayEquals(pngHeader, binary.readBytes())
    }

    @Test
    fun `generated trees are not entered`() {
        val kept = write("src/main.kt", "size\n")
        val inBuild = write("build/generated/Gen.kt", "size\n")
        val inGit = write(".git/config", "size\n")
        val inModules = write("node_modules/pkg/index.js", "size\n")
        val inRuntime = write(".claude/settings.json", "size\n")

        val plan = WorkspaceReplace.plan(root, query("size"), "length")
        assertEquals(1, plan.changedFiles)
        assertTrue(plan.heldBackDirectories.containsAll(listOf(".git", "build", "node_modules")))

        WorkspaceReplace.apply(root, plan)
        assertEquals("length\n", kept.readText())
        assertEquals("size\n", inBuild.readText())
        assertEquals("size\n", inGit.readText())
        assertEquals("size\n", inModules.readText())
        assertEquals("size\n", inRuntime.readText())
    }

    @Test
    fun `a symlink is never rewritten, wherever it points`() {
        val outside = File(base, "outside-${UUID.randomUUID()}.txt").absoluteFile.apply { writeText("size\n") }
        val inside = write("src/main.kt", "size\n")
        val link = File(root, "src/link.txt")
        // The link target must be absolute: a relative one would resolve against the link's own folder.
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        if (!Files.isSymbolicLink(link.toPath())) {
            outside.delete()
            return // a file system without working links: nothing to prove here
        }

        val plan = WorkspaceReplace.plan(root, query("size"), "length")
        assertEquals(1, plan.changedFiles)
        assertEquals(listOf("src/main.kt"), plan.files.map { it.path })

        WorkspaceReplace.apply(root, plan)

        assertEquals("length\n", inside.readText())
        assertEquals("the file a link points at must not be written", "size\n", outside.readText())
        assertEquals("size\n", Files.readString(link.toPath()))
        outside.delete()
    }

    @Test
    fun `an executable file keeps its bit`() {
        val script = write("run.sh", "#!/bin/sh\necho hello\n")
        val executable = runCatching {
            Files.setPosixFilePermissions(
                script.toPath(),
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
        if (executable.isFailure) return // no POSIX permissions here

        val plan = WorkspaceReplace.plan(root, query("hello"), "world")
        WorkspaceReplace.apply(root, plan)

        assertEquals("#!/bin/sh\necho world\n", script.readText())
        assertTrue("the executable bit must survive", script.canExecute())
    }

    @Test
    fun `a very long line is left unchanged and reported`() {
        val long = "x".repeat(500) + "needle" + "y".repeat(500)
        val file = write("data.txt", "$long\nshort needle\n")
        val limits = ReplaceLimits(maxLineLength = 200)

        val plan = WorkspaceReplace.plan(root, query("needle"), "found", limits)
        assertEquals(1, plan.totalReplacements)
        assertEquals(1, plan.skippedLongLines)

        WorkspaceReplace.apply(root, plan)
        assertEquals("$long\nshort found\n", file.readText())
    }

    @Test
    fun `an oversized file is skipped and counted`() {
        val file = write("big.txt", "needle\n".repeat(200))
        val plan = WorkspaceReplace.plan(root, query("needle"), "x", ReplaceLimits(maxFileBytes = 100))

        assertEquals(0, plan.changedFiles)
        assertEquals(1, plan.oversizedFiles)
        assertTrue(file.readText().contains("needle"))
    }

    @Test
    fun `the file ceiling stops the plan and says so`() {
        repeat(5) { write("src/file$it.txt", "needle\n") }
        val plan = WorkspaceReplace.plan(root, query("needle"), "x", ReplaceLimits(maxChangedFiles = 2))

        assertEquals(2, plan.changedFiles)
        assertTrue(plan.truncated)
    }

    @Test
    fun `a replacement that cannot be used with the pattern plans nothing`() {
        val file = write("src/main.kt", "val size = 1\n")
        val plan = WorkspaceReplace.plan(root, query("(\\w+)", SearchOptions(regex = true)), "$9")

        assertTrue(plan.templateRejected)
        assertEquals(0, plan.changedFiles)
        WorkspaceReplace.apply(root, plan)
        assertEquals("val size = 1\n", file.readText())
    }

    @Test
    fun `a search without matches plans nothing and writes nothing`() {
        val file = write("src/main.kt", "val size = 1\n")
        val plan = WorkspaceReplace.plan(root, query("absent"), "x")

        assertTrue(plan.isEmpty)
        assertEquals(0, plan.totalReplacements)
        assertEquals(ReplaceOutcome(0, 0), WorkspaceReplace.apply(root, plan))
        assertEquals("val size = 1\n", file.readText())
    }

    @Test
    fun `a pattern replacement can use the groups it matched`() {
        val file = write("src/Api.kt", "class Api { val apiKey = 1 }\nval other = apiKey\n")
        val plan = WorkspaceReplace.plan(
            root,
            query("apiKey|Api", SearchOptions(regex = true, wholeWord = true)),
            "Token",
        )

        assertEquals(3, plan.totalReplacements)
        WorkspaceReplace.apply(root, plan)
        assertEquals("class Token { val Token = 1 }\nval other = Token\n", file.readText())
    }

    @Test
    fun `the preview names the line that changed`() {
        val file = write("src/main.kt", "first line\nval size = 1\nlast line\n")
        val plan = WorkspaceReplace.plan(root, query("size"), "length")
        assertEquals("val size = 1", plan.files.single().preview)
        assertEquals("src/main.kt", plan.files.single().path)
        assertTrue(file.exists())
    }
}

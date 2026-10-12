package com.jarves.mh.runtime

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loader check is exercised against real ELF files: two shared objects built with the project's own
 * NDK clang, one linked with `max-page-size=4096` and one with `16384`, so the two alignment classes a
 * device can actually meet are both covered by real bytes rather than a hand-written header.
 */
class NativeLoadAlignmentTest {

    private val page4k = 4096
    private val page16k = 16384

    private fun fixture(name: String): File {
        // The Gradle Java-resources task does not carry binary test resources, so the real NDK-built
        // fixtures are read from the module directory: unit tests run with `app/` as working directory.
        val candidates = listOf(
            File("src/test/resources/native/$name"),
            File("app/src/test/resources/native/$name"),
        )
        val source = candidates.firstOrNull { it.isFile }
            ?: error("missing test fixture native/$name (looked in ${candidates.joinToString()})")
        val file = File.createTempFile("mh-page-", "-$name").apply { deleteOnExit() }
        file.writeBytes(source.readBytes())
        return file
    }

    @Test
    fun `a 4k-aligned library is reported as below a 16k device page`() {
        val report = NativeLoadAlignment.inspect(fixture("page-align-4k.so"))
        assertTrue("must be an ELF", report.isElf)
        assertTrue("must be little endian", report.littleEndian)
        val minimum = report.minimumSegmentAlignment ?: error("a real library has LOAD segments")
        assertEquals(page4k.toLong(), minimum)
        assertTrue("segments must be congruent", report.allSegmentsCongruent)
        assertEquals(NativeLoadAlignment.Verdict.BELOW_DEVICE_PAGE, NativeLoadAlignment.verdict(report, page16k))
    }

    @Test
    fun `the same library is ready on a 4k device`() {
        val report = NativeLoadAlignment.inspect(fixture("page-align-4k.so"))
        assertEquals(NativeLoadAlignment.Verdict.READY, NativeLoadAlignment.verdict(report, page4k))
    }

    @Test
    fun `a 16k-aligned library is ready on both page sizes`() {
        val report = NativeLoadAlignment.inspect(fixture("page-align-16k.so"))
        assertTrue(report.isElf)
        val minimum = report.minimumSegmentAlignment ?: error("a real library has LOAD segments")
        assertTrue("must be aligned for 16 KiB pages, was $minimum", minimum >= page16k)
        assertTrue(report.allSegmentsCongruent)
        assertEquals(NativeLoadAlignment.Verdict.READY, NativeLoadAlignment.verdict(report, page16k))
        assertEquals(NativeLoadAlignment.Verdict.READY, NativeLoadAlignment.verdict(report, page4k))
    }

    @Test
    fun `an unreadable page size never becomes a passing verdict for a 4k library`() {
        val report = NativeLoadAlignment.inspect(fixture("page-align-4k.so"))
        assertEquals(NativeLoadAlignment.Verdict.PAGE_SIZE_UNKNOWN, NativeLoadAlignment.verdict(report, 0))
        assertEquals(NativeLoadAlignment.Verdict.PAGE_SIZE_UNKNOWN, NativeLoadAlignment.verdict(report, -1))
    }

    @Test
    fun `an unknown page size still accepts a 16k library`() {
        val report = NativeLoadAlignment.inspect(fixture("page-align-16k.so"))
        assertEquals(NativeLoadAlignment.Verdict.READY, NativeLoadAlignment.verdict(report, 0))
    }

    @Test
    fun `a real library is 64-bit arm64`() {
        val report = NativeLoadAlignment.inspect(fixture("page-align-16k.so"))
        assertTrue(report.is64Bit)
        assertEquals(183, report.machine)
    }

    @Test
    fun `an empty file is unreadable, not a verdict about alignment`() {
        val empty = File.createTempFile("mh-empty-", ".so").apply { deleteOnExit() }
        val report = NativeLoadAlignment.inspect(empty)
        assertFalse(report.isElf)
        assertEquals(NativeLoadAlignment.Verdict.UNREADABLE, NativeLoadAlignment.verdict(report, page16k))
    }

    @Test
    fun `a file that is not an elf is named as such`() {
        val text = File.createTempFile("mh-text-", ".so").apply { deleteOnExit() }
        text.writeText("this is not a native library at all, it is a note")
        assertEquals(NativeLoadAlignment.Verdict.NOT_ELF, NativeLoadAlignment.verdict(NativeLoadAlignment.inspect(text), page16k))
    }

    @Test
    fun `a big-endian elf is refused instead of being half-read`() {
        val file = File.createTempFile("mh-be-", ".so").apply { deleteOnExit() }
        file.writeBytes(elf64(listOf(Triple(0L, 0L, page16k.toLong())), littleEndian = false))
        val report = NativeLoadAlignment.inspect(file)
        assertTrue("magic matches, so it is an ELF", report.isElf)
        assertFalse(report.littleEndian)
        assertEquals(NativeLoadAlignment.Verdict.NOT_ELF, NativeLoadAlignment.verdict(report, page16k))
    }

    @Test
    fun `a program header table beyond the end of the file is unreadable`() {
        val bytes = elf64(listOf(Triple(0L, 0L, page16k.toLong())))
        val truncated = File.createTempFile("mh-trunc-", ".so").apply { deleteOnExit() }
        truncated.writeBytes(bytes.copyOf(70))
        assertEquals(NativeLoadAlignment.Verdict.UNREADABLE, NativeLoadAlignment.verdict(NativeLoadAlignment.inspect(truncated), page16k))
    }

    @Test
    fun `a forged header count cannot force a huge read`() {
        val bytes = elf64(listOf(Triple(0L, 0L, page16k.toLong())))
        // e_phnum (0x38) far above the cap, with the table missing from the file.
        bytes[0x38] = 0xFF.toByte()
        bytes[0x39] = 0xFF.toByte()
        val file = File.createTempFile("mh-forged-", ".so").apply { deleteOnExit() }
        file.writeBytes(bytes)
        assertEquals(NativeLoadAlignment.Verdict.UNREADABLE, NativeLoadAlignment.verdict(NativeLoadAlignment.inspect(file), page16k))
    }

    @Test
    fun `a load segment whose address disagrees with its offset is refused on any page size`() {
        // offset 0x1000, address 0x2000, align 0x4000: (0x2000 - 0x1000) % 0x4000 != 0
        val file = File.createTempFile("mh-misaligned-", ".so").apply { deleteOnExit() }
        file.writeBytes(elf64(listOf(Triple(0x1000L, 0x2000L, page16k.toLong()))))
        val report = NativeLoadAlignment.inspect(file)
        assertFalse("the congruence rule must fail here", report.allSegmentsCongruent)
        assertEquals(NativeLoadAlignment.Verdict.MISALIGNED_SEGMENT, NativeLoadAlignment.verdict(report, page16k))
        assertEquals(NativeLoadAlignment.Verdict.MISALIGNED_SEGMENT, NativeLoadAlignment.verdict(report, page4k))
    }

    @Test
    fun `an elf without load segments is not treated as loadable`() {
        val file = File.createTempFile("mh-noload-", ".so").apply { deleteOnExit() }
        file.writeBytes(elf64(emptyList()))
        assertEquals(NativeLoadAlignment.Verdict.NOT_ELF, NativeLoadAlignment.verdict(NativeLoadAlignment.inspect(file), page16k))
    }

    @Test
    fun `a large file is inspected from its headers only`() {
        // 64 MiB of sparse file: the check must read the header window, not the body.
        val big = File.createTempFile("mh-big-", ".so").apply { deleteOnExit() }
        RandomAccessFile(big, "rw").use { handle ->
            handle.write(elf64(listOf(Triple(0L, 0L, page16k.toLong()))))
            handle.setLength(64L * 1024 * 1024)
        }
        val started = System.nanoTime()
        val report = NativeLoadAlignment.inspect(big)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(NativeLoadAlignment.Verdict.READY, NativeLoadAlignment.verdict(report, page16k))
        assertTrue("inspection took $elapsedMs ms", elapsedMs < 2_000)
    }

    @Test
    fun `a mixed set is counted honestly and reports its worst verdict`() {
        val files = listOf(fixture("page-align-4k.so"), fixture("page-align-16k.so"), fixture("page-align-16k.so"))
        val onModernDevice = NativeLoadAlignment.summarize(files, page16k)
        assertEquals(3, onModernDevice.scanned)
        assertEquals(2, onModernDevice.ready)
        assertEquals(1, onModernDevice.belowDevicePage)
        assertFalse(onModernDevice.allReady)
        assertEquals(NativeLoadAlignment.Verdict.BELOW_DEVICE_PAGE, onModernDevice.worst)

        val onLegacyDevice = NativeLoadAlignment.summarize(files, page4k)
        assertEquals(3, onLegacyDevice.ready)
        assertTrue(onLegacyDevice.allReady)
        assertEquals(NativeLoadAlignment.Verdict.READY, onLegacyDevice.worst)
    }

    @Test
    fun `an empty candidate set is not reported as ready`() {
        val summary = NativeLoadAlignment.summarize(emptyList(), page16k)
        assertEquals(0, summary.scanned)
        assertFalse(summary.allReady)
        assertFalse(summary.pageSizeKnown == false && summary.scanned > 0)
    }

    /** Minimal ELF64 header plus LOAD program headers: (offset, virtualAddress, align). */
    private fun elf64(segments: List<Triple<Long, Long, Long>>, littleEndian: Boolean = true): ByteArray {
        val tableOffset = 64
        val entrySize = 56
        val bytes = ByteArray(tableOffset + segments.size * entrySize)
        bytes[0] = 0x7F.toByte()
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2
        bytes[5] = if (littleEndian) 1 else 2
        putU16(bytes, 0x12, 183)
        putU32(bytes, 0x14, 1)
        putU64(bytes, 0x20, tableOffset.toLong())
        putU16(bytes, 0x34, 64)
        putU16(bytes, 0x36, entrySize)
        putU16(bytes, 0x38, segments.size)
        segments.forEachIndexed { index, (offset, address, align) ->
            val base = tableOffset + index * entrySize
            putU32(bytes, base, 1) // PT_LOAD
            putU64(bytes, base + 0x08, offset)
            putU64(bytes, base + 0x10, address)
            putU64(bytes, base + 0x30, align)
        }
        return bytes
    }

    private fun putU16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putU32(bytes: ByteArray, at: Int, value: Long) {
        for (index in 0 until 4) bytes[at + index] = ((value shr (8 * index)) and 0xFF).toByte()
    }

    private fun putU64(bytes: ByteArray, at: Int, value: Long) {
        for (index in 0 until 8) bytes[at + index] = ((value shr (8 * index)) and 0xFF).toByte()
    }
}

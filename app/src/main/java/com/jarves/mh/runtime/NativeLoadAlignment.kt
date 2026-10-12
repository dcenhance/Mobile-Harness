package com.jarves.mh.runtime

import java.io.File
import java.io.RandomAccessFile

/**
 * Whether a native file can really be loaded on this device, read from its program headers instead of
 * assumed from the build flags.
 *
 * Mobile Harness executes real ELF files: `libproot.so` and `libprootloader.so` come from the APK, and
 * the extracted runtime adds more. A device with 16 KiB pages refuses a file whose LOAD segments are
 * only 4 KiB aligned, which is why this project pins `max-page-size` for the NDK and why the upstream
 * build comment warns that NDK 26 produced 4 KiB segments that crash on 16 KiB runtimes. The check
 * therefore reads exactly what the loader reads — the ELF program headers, in a small bounded window,
 * never the whole file — and reports a verdict per refusing rule instead of one opaque "not supported".
 */
object NativeLoadAlignment {

    const val FOUR_KILOBYTE: Int = 4096
    const val SIXTEEN_KILOBYTE: Int = 16384

    /** Read at most this many program header entries; real objects stay far below it. */
    private const val MAX_PROGRAM_HEADERS = 128

    /** Upper bound for a program header table, so a forged header count cannot force a huge read. */
    private const val MAX_TABLE_BYTES = 16 * 1024

    private const val PT_LOAD = 1L

    enum class Verdict {
        /** Every LOAD segment is aligned for this device's page size and congruent. */
        READY,

        /** The device reports a larger page size than the segments' alignment: loading may fail. */
        BELOW_DEVICE_PAGE,

        /** The page size could not be read and the file is only 4 KiB aligned: cannot be judged. */
        PAGE_SIZE_UNKNOWN,

        /** A LOAD segment's address does not match its file offset modulo its alignment. */
        MISALIGNED_SEGMENT,

        /** Not an ELF file, or an encoding this check cannot read (big-endian, malformed). */
        NOT_ELF,

        /** The file could not be read, or its headers claim more bytes than the file holds. */
        UNREADABLE,
    }

    data class LoadSegment(val offset: Long, val virtualAddress: Long, val align: Long) {
        /** The kernel's own mapping rule: address and offset agree modulo the alignment. */
        val isCongruent: Boolean get() = align <= 1L || (virtualAddress - offset) % align == 0L
    }

    data class ElfLoadReport(
        val isElf: Boolean = false,
        val is64Bit: Boolean = false,
        val littleEndian: Boolean = false,
        val machine: Int = 0,
        val segments: List<LoadSegment> = emptyList(),
        val truncated: Boolean = false,
        val unreadable: Boolean = false,
    ) {
        val minimumSegmentAlignment: Long? get() = segments.minOfOrNull { it.align }
        val allSegmentsCongruent: Boolean get() = segments.all { it.isCongruent }
    }

    data class Summary(
        val scanned: Int,
        val ready: Int,
        val belowDevicePage: Int,
        val misaligned: Int,
        val notElf: Int,
        val unreadable: Int,
        val pageSizeBytes: Int,
        val pageSizeKnown: Boolean,
    ) {
        /** The most severe verdict that actually occurred, for one honest sentence in the UI. */
        val worst: Verdict
            get() = when {
                unreadable > 0 -> Verdict.UNREADABLE
                misaligned > 0 -> Verdict.MISALIGNED_SEGMENT
                belowDevicePage > 0 -> Verdict.BELOW_DEVICE_PAGE
                notElf > 0 -> Verdict.NOT_ELF
                scanned == 0 -> Verdict.NOT_ELF
                else -> Verdict.READY
            }

        val allReady: Boolean get() = scanned > 0 && ready == scanned
    }

    /**
     * Reads the ELF header and the LOAD program headers from [file]. Only the small window of the
     * program header table is read, so checking a 600 MB binary costs the same as checking a small one.
     */
    fun inspect(file: File): ElfLoadReport {
        return runCatching {
            RandomAccessFile(file, "r").use { handle ->
                val length = handle.length()
                val header = ByteArray(64)
                val headerRead = handle.read(header)
                if (headerRead < 16) return@use ElfLoadReport(unreadable = true)
                if (!hasElfMagic(header)) return@use ElfLoadReport()
                val is64 = header[4].toInt() == 2
                val littleEndian = header[5].toInt() == 1
                if (!littleEndian) return@use ElfLoadReport(isElf = true, is64Bit = is64, littleEndian = false)
                val machine = readU16(header, 0x12)
                val programHeaderOffset: Long
                val entrySize: Int
                val entryCount: Int
                if (is64) {
                    if (headerRead < 0x3A) return@use ElfLoadReport(is64Bit = true, littleEndian = true, machine = machine, unreadable = true)
                    programHeaderOffset = readU64(header, 0x20)
                    entrySize = readU16(header, 0x36)
                    entryCount = readU16(header, 0x38)
                } else {
                    programHeaderOffset = readU32(header, 0x1C).toLong()
                    entrySize = readU16(header, 0x2A)
                    entryCount = readU16(header, 0x2C)
                }
                val minimumEntrySize = if (is64) 56 else 32
                if (entryCount <= 0 || entrySize < minimumEntrySize) {
                    return@use ElfLoadReport(is64Bit = is64, littleEndian = true, machine = machine)
                }
                val wanted = minOf(entryCount, MAX_PROGRAM_HEADERS)
                val tableBytes = wanted * entrySize
                if (tableBytes > MAX_TABLE_BYTES) {
                    return@use ElfLoadReport(is64Bit = is64, littleEndian = true, machine = machine, truncated = true)
                }
                if (programHeaderOffset < 0 || programHeaderOffset + tableBytes > length) {
                    return@use ElfLoadReport(is64Bit = is64, littleEndian = true, machine = machine, truncated = true)
                }
                val table = ByteArray(tableBytes)
                handle.seek(programHeaderOffset)
                if (handle.read(table) != tableBytes) {
                    return@use ElfLoadReport(is64Bit = is64, littleEndian = true, machine = machine, unreadable = true)
                }
                val segments = ArrayList<LoadSegment>()
                for (index in 0 until wanted) {
                    val base = index * entrySize
                    if (readU32(table, base) != PT_LOAD) continue
                    segments += if (is64) {
                        LoadSegment(
                            offset = readU64(table, base + 0x08),
                            virtualAddress = readU64(table, base + 0x10),
                            align = readU64(table, base + 0x30).takeIf { it > 0L } ?: 1L,
                        )
                    } else {
                        LoadSegment(
                            offset = readU32(table, base + 0x04).toLong(),
                            virtualAddress = readU32(table, base + 0x08).toLong(),
                            align = readU32(table, base + 0x1C).toLong().takeIf { it > 0L } ?: 1L,
                        )
                    }
                }
                ElfLoadReport(
                    isElf = true,
                    is64Bit = is64,
                    littleEndian = true,
                    machine = machine,
                    segments = segments,
                )
            }
        }.getOrElse { ElfLoadReport(unreadable = true) }
    }

    /**
     * The verdict for one report against the page size the device actually reports. An unknown page
     * size never turns into a passing verdict: without it a 4 KiB-aligned file cannot be judged.
     */
    fun verdict(report: ElfLoadReport, devicePageSizeBytes: Int): Verdict = when {
        report.unreadable || report.truncated -> Verdict.UNREADABLE
        !report.isElf || !report.littleEndian || report.segments.isEmpty() -> Verdict.NOT_ELF
        !report.allSegmentsCongruent -> Verdict.MISALIGNED_SEGMENT
        devicePageSizeBytes <= 0 ->
            if ((report.minimumSegmentAlignment ?: 0L) >= SIXTEEN_KILOBYTE) Verdict.READY else Verdict.PAGE_SIZE_UNKNOWN

        devicePageSizeBytes >= SIXTEEN_KILOBYTE && (report.minimumSegmentAlignment ?: 0L) < SIXTEEN_KILOBYTE ->
            Verdict.BELOW_DEVICE_PAGE

        else -> Verdict.READY
    }

    /** Checks every candidate file and counts the verdicts, so the UI can report real numbers. */
    fun summarize(files: List<File>, devicePageSizeBytes: Int): Summary {
        var ready = 0
        var below = 0
        var misaligned = 0
        var notElf = 0
        var unreadable = 0
        files.forEach { file ->
            when (verdict(inspect(file), devicePageSizeBytes)) {
                Verdict.READY -> ready++
                Verdict.BELOW_DEVICE_PAGE, Verdict.PAGE_SIZE_UNKNOWN -> below++
                Verdict.MISALIGNED_SEGMENT -> misaligned++
                Verdict.NOT_ELF -> notElf++
                Verdict.UNREADABLE -> unreadable++
            }
        }
        return Summary(
            scanned = files.size,
            ready = ready,
            belowDevicePage = below,
            misaligned = misaligned,
            notElf = notElf,
            unreadable = unreadable,
            pageSizeBytes = devicePageSizeBytes,
            pageSizeKnown = devicePageSizeBytes > 0,
        )
    }

    private fun hasElfMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()

    private fun readU16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun readU32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)

    private fun readU64(bytes: ByteArray, at: Int): Long =
        (readU32(bytes, at) and 0xFFFFFFFFL) or ((readU32(bytes, at + 4) and 0xFFFFFFFFL) shl 32)
}

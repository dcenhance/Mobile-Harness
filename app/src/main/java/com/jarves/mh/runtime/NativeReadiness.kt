package com.jarves.mh.runtime

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * The Android half of the readiness check: the page size the kernel really reports, and the native
 * files that would actually be loaded.
 *
 * Nothing here assumes a page size. [pageSizeBytes] returns 0 when the platform cannot answer, and the
 * verdict for that case is "cannot be judged", never a silent 4 KiB default — a 4 KiB-aligned binary
 * on a 16 KiB device fails to load, which is exactly the failure this project's NDK comment warns about.
 */
object NativeReadiness {

    /** Bounded sample size for the extracted runtime, so a huge rootfs is not walked on every check. */
    const val RUNTIME_SAMPLE_LIMIT = 24

    /** Page size reported by the kernel, or 0 when it cannot be read. */
    fun pageSizeBytes(): Int = runCatching {
        Os.sysconf(OsConstants._SC_PAGESIZE).toInt()
    }.getOrDefault(0)

    /** The libraries the APK installs for this device; with legacy packaging these are real files. */
    fun appLibraries(context: Context): List<File> {
        val directory = File(context.applicationInfo.nativeLibraryDir)
        return directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".so") }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    /**
     * A bounded, deterministic sample of the extracted runtime's binaries. The rootfs is large, so the
     * check names how many files it looked at instead of pretending it read all of them.
     */
    fun runtimeBinaries(context: Context, limit: Int = RUNTIME_SAMPLE_LIMIT): List<File> {
        val directory = File(context.filesDir, "runtime/ubuntu/usr/bin")
        return directory.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?.take(limit)
            .orEmpty()
    }

    fun candidateFiles(context: Context): List<File> = appLibraries(context) + runtimeBinaries(context)

    /** Runs the real check over the real files. File IO: call it off the main thread. */
    fun summarize(context: Context): NativeLoadAlignment.Summary =
        NativeLoadAlignment.summarize(candidateFiles(context), pageSizeBytes())
}

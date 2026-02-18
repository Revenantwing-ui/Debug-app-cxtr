package com.foss.appcloner.util

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.util.zip.CRC32

object ZipUtils {

    /** Extract a single entry from a ZIP/APK file, returns null if not found. */
    fun extractEntry(zipFile: File, entryName: String): ByteArray? =
        ZipFile(zipFile).use { zf ->
            val entry = zf.getEntry(entryName) ?: return null
            zf.getInputStream(entry).readBytes()
        }

    /**
     * Repack [sourceApk] into [destApk].
     *
     * KEY FIX: Override entries are ALWAYS written with DEFLATED compression.
     * Writing a STORED entry requires the caller to pre-compute and set the CRC32,
     * compressed size, and uncompressed size on the ZipArchiveEntry *before* calling
     * putArchiveEntry(), otherwise Commons Compress throws:
     *   "size, crc and compressed size must be set for STORED method"
     * Since override bytes have already changed (different content and size from the
     * original), there is no alignment benefit to keeping them STORED, so DEFLATED
     * is always the correct choice here.
     *
     * Pass-through entries (not overridden) use ZipArchiveEntry(entry) which copies
     * the original entry's method, CRC, and sizes verbatim — this is correct.
     */
    fun repackApk(
        sourceApk: File,
        destApk:   File,
        overrides:  Map<String, ByteArray>,
        additions:  Map<String, ByteArray> = emptyMap()
    ) {
        ZipFile(sourceApk).use { zf ->
            ZipArchiveOutputStream(destApk.outputStream().buffered()).use { zout ->

                // ── Write all entries from source (possibly replaced) ──────────
                for (entry in zf.entries) {
                    val name = entry.name

                    if (overrides.containsKey(name)) {
                        // Override: always DEFLATE to avoid CRC pre-computation
                        val data = overrides[name]!!
                        zout.putArchiveEntry(ZipArchiveEntry(name).also { it.method = ZipArchiveEntry.DEFLATED })
                        zout.write(data)
                        zout.closeArchiveEntry()
                    } else {
                        // Pass-through: copy entry metadata (method/CRC/size) + raw bytes
                        val passEntry = ZipArchiveEntry(entry)
                        zout.putArchiveEntry(passEntry)
                        zf.getInputStream(entry).copyTo(zout)
                        zout.closeArchiveEntry()
                    }
                }

                // ── Write additional entries not present in source ────────────
                for ((name, data) in additions) {
                    if (zf.getEntry(name) != null) continue
                    zout.putArchiveEntry(ZipArchiveEntry(name).also { it.method = ZipArchiveEntry.DEFLATED })
                    zout.write(data)
                    zout.closeArchiveEntry()
                }
            }
        }
    }

    /** List all entry names in a ZIP/APK. */
    fun listEntries(zipFile: File): List<String> =
        ZipFile(zipFile).use { zf ->
            zf.entries.asSequence().map { it.name }.toList()
        }

    /** Compute CRC32 of a byte array (utility for callers that need STORED entries). */
    fun crc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }
}

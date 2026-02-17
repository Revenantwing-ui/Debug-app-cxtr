package com.foss.appcloner.util

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.InputStream

object ZipUtils {

    /** Extract a single entry from a ZIP/APK file, returns null if not found. */
    fun extractEntry(zipFile: File, entryName: String): ByteArray? {
        return ZipFile(zipFile).use { zf ->
            val entry = zf.getEntry(entryName) ?: return null
            zf.getInputStream(entry).readBytes()
        }
    }

    /**
     * Repack [sourceApk] into [destApk], replacing or adding entries from [overrides],
     * while keeping all other entries from source intact (uncompressed entries stay uncompressed).
     */
    fun repackApk(
        sourceApk: File,
        destApk: File,
        overrides: Map<String, ByteArray>,
        additions: Map<String, ByteArray> = emptyMap()
    ) {
        ZipFile(sourceApk).use { zf ->
            ZipArchiveOutputStream(destApk.outputStream().buffered()).use { zout ->
                // Write overridden / passthrough entries
                for (entry in zf.entries) {
                    val name = entry.name
                    if (overrides.containsKey(name)) {
                        val data = overrides[name]!!
                        val newEntry = ZipArchiveEntry(name).apply {
                            method = if (entry.method == ZipArchiveEntry.STORED)
                                ZipArchiveEntry.STORED else ZipArchiveEntry.DEFLATED
                        }
                        zout.putArchiveEntry(newEntry)
                        zout.write(data)
                        zout.closeArchiveEntry()
                    } else {
                        val newEntry = ZipArchiveEntry(entry)
                        zout.putArchiveEntry(newEntry)
                        zf.getInputStream(entry).copyTo(zout)
                        zout.closeArchiveEntry()
                    }
                }
                // Add new entries (not in original)
                for ((name, data) in additions) {
                    if (zf.getEntry(name) == null) {
                        val newEntry = ZipArchiveEntry(name)
                        zout.putArchiveEntry(newEntry)
                        zout.write(data)
                        zout.closeArchiveEntry()
                    }
                }
            }
        }
    }

    /** List all entry names in a ZIP/APK. */
    fun listEntries(zipFile: File): List<String> =
        ZipFile(zipFile).use { zf -> zf.entries.asSequence().map { it.name }.toList() }
}

package com.foss.appcloner.cloner

import com.foss.appcloner.util.BinaryXmlModifier

/**
 * Modifies the binary AndroidManifest.xml of a source APK so it
 * can be installed alongside the original app without conflicts.
 *
 * Operations:
 *   - Replace source package name with clone package name (all occurrences)
 *   - Replace all ContentProvider authorities that start with source package
 *   - Add android:sharedUserId if requested
 */
object ManifestModifier {

    /**
     * @param manifestBytes  Raw bytes of AndroidManifest.xml (binary AXML format)
     * @param sourcePackage  Original package name (e.g. "com.example.app")
     * @param clonePackage   New clone package name (e.g. "com.example.app.clone1")
     * @return Modified manifest bytes
     */
    fun modify(
        manifestBytes: ByteArray,
        sourcePackage: String,
        clonePackage: String
    ): ByteArray {
        // Build a replacement map: any string equal to the source package → clone package
        // Also handle authority strings like "com.example.app.provider" → "com.example.app.clone1.provider"
        val replacements = buildReplacementMap(manifestBytes, sourcePackage, clonePackage)
        return BinaryXmlModifier.replaceStrings(manifestBytes, replacements)
    }

    /**
     * Scan the string pool to find all strings that should be replaced.
     * We replace:
     *   - Exact package name matches
     *   - Strings that start with sourcePackage (e.g. authorities, component names)
     *
     * We preserve the basic string pool structure; strings that are longer or shorter
     * than their replacement cause the pool to be rebuilt by [BinaryXmlModifier].
     */
    private fun buildReplacementMap(
        axmlBytes: ByteArray,
        sourcePackage: String,
        clonePackage: String
    ): Map<String, String> {
        val replacements = mutableMapOf<String, String>()

        // Read all strings from the pool
        val strings = extractStringPool(axmlBytes)
        for (str in strings) {
            when {
                str == sourcePackage ->
                    replacements[str] = clonePackage

                str.startsWith("$sourcePackage.") || str.startsWith("$sourcePackage/") ->
                    replacements[str] = clonePackage + str.substring(sourcePackage.length)

                // Handle permission strings (e.g. "com.example.app.permission.X")
                str.contains(sourcePackage) ->
                    replacements[str] = str.replace(sourcePackage, clonePackage)
            }
        }
        return replacements
    }

    /** Quick extraction of all UTF-8 and UTF-16 strings from the AXML string pool. */
    private fun extractStringPool(axmlBytes: ByteArray): List<String> {
        if (axmlBytes.size < 36) return emptyList()
        val strings = mutableListOf<String>()
        try {
            val buf = java.nio.ByteBuffer.wrap(axmlBytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)

            buf.position(0)
            val fileType  = buf.int   // 0x00080003
            val totalSize = buf.int
            val spType    = buf.int   // 0x001C0001
            val spSize    = buf.int
            val strCount  = buf.int
            val styleCnt  = buf.int
            val flags     = buf.int
            val strStart  = buf.int   // offset from SP start to string data
            val styleStart= buf.int

            val isUtf8   = (flags and 0x00000100) != 0
            val spBase   = 8          // SP starts right after 8-byte file header
            val offsets  = IntArray(strCount) { buf.int }
            val dataBase = spBase + strStart

            for (i in 0 until strCount) {
                val pos = dataBase + offsets[i]
                if (pos >= axmlBytes.size) continue
                val s = readOneString(axmlBytes, pos, isUtf8)
                strings.add(s)
            }
        } catch (e: Exception) {
            // Malformed manifest – return whatever we got
        }
        return strings
    }

    private fun readOneString(bytes: ByteArray, offset: Int, isUtf8: Boolean): String {
        return try {
            if (isUtf8) {
                var p = offset
                // First length field: UTF-16 char count
                val f1 = bytes[p].toInt() and 0xFF; p++
                if (f1 and 0x80 != 0) p++
                // Second length field: UTF-8 byte count
                val f2 = bytes[p].toInt() and 0xFF; p++
                val byteLen = if (f2 and 0x80 != 0) { val b2 = bytes[p].toInt() and 0xFF; p++; ((f2 and 0x7F) shl 8) or b2 } else f2
                String(bytes, p, minOf(byteLen, bytes.size - p), Charsets.UTF_8)
            } else {
                val lo = bytes[offset].toInt() and 0xFF
                val hi = bytes[offset + 1].toInt() and 0xFF
                val len = (hi shl 8) or lo
                if (len <= 0 || offset + 2 + len * 2 > bytes.size) ""
                else String(bytes, offset + 2, len * 2, Charsets.UTF_16LE)
            }
        } catch (e: Exception) { "" }
    }
}

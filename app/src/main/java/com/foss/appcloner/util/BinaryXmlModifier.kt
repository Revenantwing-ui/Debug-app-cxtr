package com.foss.appcloner.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal Android Binary XML (AXML) reader/writer that operates on the raw
 * binary content of AndroidManifest.xml inside an APK.
 *
 * The AXML format is:
 * - 8-byte file header  (type=0x0003, header_size=0x0008, file_size)
 * - StringPool chunk    (type=0x0001)
 * - XML event chunks    (start namespace, start element, attribute, end element…)
 *
 * We focus on two mutations needed for cloning:
 * 1. Replacing the "package" attribute in the manifest element
 * 2. Replacing authority strings in <provider> elements
 * 3. Injecting a BroadcastReceiver element for identity updates
 *
 * String replacement works by finding the target string in the StringPool
 * and rewriting it (expanding/shrinking the pool as needed).
 */
object BinaryXmlModifier {

    private const val CHUNK_XML          = 0x00080003
    private const val CHUNK_STRING_POOL  = 0x001C0001
    private const val STRING_FLAG_UTF8   = 0x00000100

    /**
     * Replace all occurrences of [oldStr] with [newStr] in the AXML string pool.
     * Returns the modified byte array.
     */
    fun replaceStrings(axmlBytes: ByteArray, replacements: Map<String, String>): ByteArray {
        val buf = ByteBuffer.wrap(axmlBytes).order(ByteOrder.LITTLE_ENDIAN)

        // Skip file header (8 bytes)
        buf.position(0)
        val fileType = buf.int        // must be 0x00080003
        val totalSize = buf.int

        // Find StringPool chunk
        val spType   = buf.int        // 0x001C0001
        val spSize   = buf.int
        val strCount = buf.int
        val styleCount = buf.int
        val flags    = buf.int
        val strStart = buf.int        // offset from start of StringPool chunk to string data
        val styleStart = buf.int      // offset to style data (unused)

        val spBase = 8                // StringPool starts at byte 8

        // Read string offsets (relative to strStart within the chunk)
        val offsets = IntArray(strCount) { buf.int }

        val isUtf8 = (flags and STRING_FLAG_UTF8) != 0
        val strDataBase = spBase + strStart

        // Read all strings
        val strings = Array(strCount) { i ->
            val pos = strDataBase + offsets[i]
            readString(axmlBytes, pos, isUtf8)
        }

        // Apply replacements
        var mutated = false
        for (i in strings.indices) {
            val replacement = replacements[strings[i]]
            if (replacement != null) {
                strings[i] = replacement
                mutated = true
            }
        }

        if (!mutated) return axmlBytes

        // Rebuild the AXML with updated strings
        return rebuildAxml(axmlBytes, spBase, spSize, strCount, styleCount, flags,
                           strStart, styleStart, offsets, strings, isUtf8)
    }

    // ─── String reading ───────────────────────────────────────────────────────

    private fun readString(bytes: ByteArray, offset: Int, isUtf8: Boolean): String {
        return try {
            if (isUtf8) {
                val utf16len = readUtf8Len(bytes, offset)
                val p = offset + utf16lenSize(bytes, offset)
                val byteLen = readUtf8Len(bytes, p)
                val p2 = p + utf8lenSize(bytes, p)
                String(bytes, p2, byteLen, Charsets.UTF_8)
            } else {
                val charLen = ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                              (bytes[offset].toInt() and 0xFF)
                val p = offset + 2
                String(bytes, p, charLen * 2, Charsets.UTF_16LE)
            }
        } catch (e: Exception) { "" }
    }

    private fun readUtf8Len(bytes: ByteArray, offset: Int): Int {
        val b = bytes[offset].toInt() and 0xFF
        return if (b and 0x80 != 0) ((b and 0x7F) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        else b
    }
    private fun utf16lenSize(bytes: ByteArray, offset: Int) =
        if (bytes[offset].toInt() and 0xFF and 0x80 != 0) 2 else 1
    private fun utf8lenSize(bytes: ByteArray, offset: Int) =
        if (bytes[offset].toInt() and 0xFF and 0x80 != 0) 2 else 1

    // ─── AXML rebuilding ─────────────────────────────────────────────────────

    private fun rebuildAxml(
        original: ByteArray,
        spBase: Int, spSize: Int,
        strCount: Int, styleCount: Int,
        flags: Int, strStart: Int, styleStart: Int,
        offsets: IntArray, strings: Array<String>, isUtf8: Boolean
    ): ByteArray {
        // 1. Encode new string data
        val newStrData = encodeStrings(strings, isUtf8)

        // 2. Build new offsets
        val newOffsets = IntArray(strCount)
        var pos = 0
        val strBufs = Array(strCount) { i ->
            val buf = encodeOneString(strings[i], isUtf8)
            newOffsets[i] = pos
            pos += buf.size
            buf
        }

        // 3. Build new StringPool chunk
        val headerSize = 7 * 4 + strCount * 4           // 7 ints header + offsets
        val newStrStart = headerSize                      // string data starts right after offsets
        val newStrDataSize = strBufs.sumOf { it.size }
        val newSpSize = headerSize + newStrDataSize
        // Align to 4 bytes
        val paddedSpSize = (newSpSize + 3) and 3.inv()

        val spOut = ByteArrayOutputStream()
        val spW = { n: Int -> spOut.writeInt32Le(n) }
        spW(CHUNK_STRING_POOL)
        spW(paddedSpSize)
        spW(strCount)
        
        // CRITICAL FIX: Force styleCount to 0. 
        // Since we are not writing any style data (spW(0) for styleStart below),
        // we must tell the parser there are no styles. Passing the original styleCount
        // while stripping the data causes "INSTALL_PARSE_FAILED_BAD_MANIFEST".
        spW(0) 
        
        spW(flags)
        spW(newStrStart)
        spW(0) // styleStart = 0 (no styles)
        newOffsets.forEach { spW(it) }
        strBufs.forEach { spOut.write(it) }
        // Pad to 4-byte boundary
        repeat(paddedSpSize - newSpSize) { spOut.write(0) }
        val newSp = spOut.toByteArray()

        // 4. Stitch: [8-byte header] + [new SP] + [rest of original after old SP]
        val restStart = spBase + spSize
        val rest = original.copyOfRange(restStart, original.size)
        val newTotalSize = 8 + newSp.size + rest.size

        val out = ByteArrayOutputStream()
        out.writeInt32Le(CHUNK_XML)
        out.writeInt32Le(newTotalSize)
        out.write(newSp)
        out.write(rest)

        // 5. Fix all chunk-size fields that reference absolute positions → not needed
        //    because string references in XML chunks are by INDEX (not offset),
        //    so the rest of the XML is valid as-is after the string pool replacement.
        return out.toByteArray()
    }

    private fun encodeOneString(str: String, isUtf8: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        if (isUtf8) {
            val utf8 = str.toByteArray(Charsets.UTF_8)
            val utf16len = str.length
            writeUtf8Len(out, utf16len)
            writeUtf8Len(out, utf8.size)
            out.write(utf8)
            out.write(0) // null terminator
        } else {
            val utf16 = str.toByteArray(Charsets.UTF_16LE)
            out.write(str.length and 0xFF)
            out.write((str.length shr 8) and 0xFF)
            out.write(utf16)
            out.write(0); out.write(0) // null terminator
        }
        return out.toByteArray()
    }

    private fun encodeStrings(strings: Array<String>, isUtf8: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        strings.forEach { out.write(encodeOneString(it, isUtf8)) }
        return out.toByteArray()
    }

    private fun writeUtf8Len(out: ByteArrayOutputStream, len: Int) {
        if (len > 0x7F) {
            out.write((len shr 8) or 0x80)
            out.write(len and 0xFF)
        } else {
            out.write(len)
        }
    }

    private fun ByteArrayOutputStream.writeInt32Le(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 24) and 0xFF)
    }
}

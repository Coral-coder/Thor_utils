package gg.thor.utils.core.roms

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Random-access view over some bytes.
 *
 * ROM identification needs to peek at a handful of scattered offsets (0x0, 0x100,
 * 0x8001, the end of the file…). Abstracting that lets the exact same signature
 * code run against an on-disk file, an entry streamed out of an archive, or a
 * fixture in a unit test.
 */
interface ByteSource : Closeable {
    val size: Long

    /**
     * Reads up to [length] bytes at [offset]. Returns a shorter array (possibly
     * empty) when the source ends first — never throws for an out-of-range read,
     * because "file too short to hold this header" is a normal negative result.
     */
    fun readAt(offset: Long, length: Int): ByteArray

    override fun close() {}
}

class ByteArraySource(private val data: ByteArray) : ByteSource {
    override val size: Long get() = data.size.toLong()

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= data.size || length <= 0) return ByteArray(0)
        val end = minOf(data.size.toLong(), offset + length).toInt()
        return data.copyOfRange(offset.toInt(), end)
    }
}

class FileByteSource(file: File) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun readAt(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= size || length <= 0) return ByteArray(0)
        val want = minOf(length.toLong(), size - offset).toInt()
        val buf = ByteArray(want)
        raf.seek(offset)
        raf.readFully(buf)
        return buf
    }

    override fun close() = raf.close()
}

// ---------------------------------------------------------------- helpers

/** True when the bytes at [offset] equal the ASCII [text]. */
internal fun ByteSource.matchesAscii(offset: Long, text: String): Boolean {
    val bytes = readAt(offset, text.length)
    if (bytes.size < text.length) return false
    for (i in text.indices) {
        if (bytes[i].toInt() and 0xFF != text[i].code) return false
    }
    return true
}

/** True when the bytes at [offset] equal the given unsigned byte values. */
internal fun ByteSource.matchesBytes(offset: Long, vararg expected: Int): Boolean {
    val bytes = readAt(offset, expected.size)
    if (bytes.size < expected.size) return false
    for (i in expected.indices) {
        if (bytes[i].toInt() and 0xFF != expected[i] and 0xFF) return false
    }
    return true
}

/** Reads an ASCII string, stopping at the first NUL and trimming padding. */
internal fun ByteSource.asciiAt(offset: Long, length: Int): String {
    val bytes = readAt(offset, length)
    val end = bytes.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) bytes.size else it }
    return String(bytes, 0, end, Charsets.US_ASCII).trim()
}

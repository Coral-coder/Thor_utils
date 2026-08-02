package gg.thor.utils.core.roms

import com.github.junrar.Archive as RarArchive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/** One file inside an archive, with just enough bytes to identify it. */
data class ArchiveEntry(
    val path: String,
    val size: Long,
    /** Leading bytes of the entry, capped at [ArchiveInspector.HEADER_BYTES]. */
    val header: ByteArray,
) {
    val name: String get() = path.substringAfterLast('/')

    /**
     * A [ByteSource] that reports the entry's true size but only serves the bytes
     * we actually read. Size matters because some checks — the SNES copier-header
     * test, the CD-vs-DVD split for PlayStation discs — are size-dependent.
     */
    fun asByteSource(): ByteSource = object : ByteSource {
        override val size: Long get() = this@ArchiveEntry.size
        override fun readAt(offset: Long, length: Int): ByteArray {
            if (offset < 0 || offset >= header.size || length <= 0) return ByteArray(0)
            val end = minOf(header.size.toLong(), offset + length).toInt()
            return header.copyOfRange(offset.toInt(), end)
        }
    }

    // Data class equality over a ByteArray field would compare references; these
    // overrides keep `==` meaning what callers expect.
    override fun equals(other: Any?): Boolean =
        other is ArchiveEntry && path == other.path && size == other.size &&
            header.contentEquals(other.header)

    override fun hashCode(): Int =
        31 * (31 * path.hashCode() + size.hashCode()) + header.contentHashCode()
}

/** Result of looking inside a container file. */
data class ArchiveContents(
    val kind: ArchiveKind,
    val entries: List<ArchiveEntry>,
    /** Set when the archive could not be read (encrypted, truncated, unsupported). */
    val error: String? = null,
) {
    val isReadable: Boolean get() = error == null
}

/**
 * Reads the inside of ROM archives.
 *
 * Backed-up libraries are overwhelmingly zipped, and the archive name is often
 * the least reliable thing about them (`Game (USA).zip` says nothing about the
 * console). Peeking at the entries lets the sorter identify the actual ROM.
 */
object ArchiveInspector {

    /**
     * How much of each entry to buffer for signature checks.
     *
     * 5 MiB is chosen deliberately: it clears the ISO 9660 primary volume
     * descriptor at 0x8000 and every SNES header location including ExHiROM at
     * 0x40FFC0, which is the deepest offset any signature needs.
     */
    const val HEADER_BYTES = 5 * 1024 * 1024

    /** Entries this small are metadata, not games. */
    private const val MIN_INTERESTING_SIZE = 512L

    fun looksLikeArchive(file: File): Boolean =
        FileByteSource(file).use { Signatures.archiveKind(it) != null }

    /**
     * Lists the contents of [file], or returns null when it is not an archive.
     * Never throws: a corrupt or encrypted archive comes back as an
     * [ArchiveContents] carrying [ArchiveContents.error] so the sorter can leave
     * it alone and tell the user why.
     */
    fun inspect(file: File): ArchiveContents? {
        val kind = FileByteSource(file).use { Signatures.archiveKind(it) } ?: return null
        return try {
            when (kind) {
                ArchiveKind.ZIP -> ArchiveContents(kind, readZip(file))
                ArchiveKind.SEVEN_Z -> ArchiveContents(kind, readSevenZ(file))
                ArchiveKind.RAR -> ArchiveContents(kind, readRar(file))
                ArchiveKind.GZIP, ArchiveKind.BZIP2, ArchiveKind.XZ ->
                    ArchiveContents(kind, readSingleStream(file, kind))
            }
        } catch (e: Exception) {
            ArchiveContents(kind, emptyList(), error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun readZip(file: File): List<ArchiveEntry> =
        ZipFile.builder().setFile(file).get().use { zip ->
            zip.entries.asSequence()
                .filter { !it.isDirectory && it.size >= MIN_INTERESTING_SIZE }
                .map { entry ->
                    ArchiveEntry(entry.name, entry.size, zip.getInputStream(entry).use { readHeader(it) })
                }
                .toList()
        }

    private fun readSevenZ(file: File): List<ArchiveEntry> =
        SevenZFile.builder().setFile(file).get().use { archive ->
            buildList {
                while (true) {
                    val entry = archive.nextEntry ?: break
                    if (entry.isDirectory || entry.size < MIN_INTERESTING_SIZE) continue
                    val want = minOf(entry.size, HEADER_BYTES.toLong()).toInt()
                    val buf = ByteArray(want)
                    var read = 0
                    while (read < want) {
                        val n = archive.read(buf, read, want - read)
                        if (n <= 0) break
                        read += n
                    }
                    add(ArchiveEntry(entry.name, entry.size, buf.copyOf(read)))
                }
            }
        }

    private fun readRar(file: File): List<ArchiveEntry> =
        RarArchive(file).use { archive ->
            if (archive.isEncrypted) error("RAR archive is encrypted")
            buildList {
                archive.fileHeaders.forEach { header ->
                    if (header.isDirectory || header.fullUnpackSize < MIN_INTERESTING_SIZE) return@forEach
                    val name = header.fileName.replace('\\', '/')
                    val bytes = archive.getInputStream(header).use { readHeader(it) }
                    add(ArchiveEntry(name, header.fullUnpackSize, bytes))
                }
            }
        }

    /**
     * gzip/bzip2/xz wrap exactly one stream and carry no reliable inner name, so
     * the entry is named after the archive with its compression suffix removed.
     */
    private fun readSingleStream(file: File, kind: ArchiveKind): List<ArchiveEntry> {
        val inner = file.name.removeSuffix(".gz").removeSuffix(".bz2")
            .removeSuffix(".xz").removeSuffix(".tgz")
        BufferedInputStream(file.inputStream()).use { raw ->
            val decompressed: InputStream = when (kind) {
                ArchiveKind.GZIP -> GzipCompressorInputStream(raw)
                ArchiveKind.BZIP2 -> BZip2CompressorInputStream(raw)
                ArchiveKind.XZ -> XZCompressorInputStream(raw)
                else -> return emptyList()
            }
            decompressed.use { stream ->
                // A .tar.gz holds many files; peek and unwrap it if so.
                if (file.name.endsWith(".tar.gz") || file.name.endsWith(".tgz")) {
                    return readTar(stream)
                }
                val bytes = readHeader(stream)
                return listOf(ArchiveEntry(inner, bytes.size.toLong(), bytes))
            }
        }
    }

    private fun readTar(stream: InputStream): List<ArchiveEntry> =
        TarArchiveInputStream(stream).use { tar ->
            buildList {
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (entry.isDirectory || entry.size < MIN_INTERESTING_SIZE) continue
                    add(ArchiveEntry(entry.name, entry.size, readHeader(tar)))
                }
            }
        }

    private fun readHeader(stream: InputStream): ByteArray {
        val buffer = ByteArray(minOf(HEADER_BYTES, 1 shl 16))
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (total < HEADER_BYTES) {
            val n = stream.read(buffer, 0, minOf(buffer.size, HEADER_BYTES - total))
            if (n <= 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}

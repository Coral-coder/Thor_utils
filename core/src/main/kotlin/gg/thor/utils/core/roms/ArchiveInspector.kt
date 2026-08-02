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

    /**
     * The same listing with the buffered header bytes released.
     *
     * Headers exist only to identify the archive. Once that is decided they are
     * dead weight, and a scan holds one [ArchiveContents] per game — on a large
     * library, keeping them is what exhausts the heap. Names and sizes are kept
     * because the UI still shows them.
     */
    fun withoutHeaders(): ArchiveContents =
        copy(entries = entries.map { it.copy(header = ByteArray(0)) })
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
     * 128 KiB covers every offset the signatures actually reach on a compressed
     * entry: the ISO 9660 primary volume descriptor at 0x8000, the raw-2352
     * descriptor at ~0x9318, and the SNES LoROM and HiROM headers at 0x7FC0 and
     * 0xFFC0.
     *
     * The one thing it does not reach is the SNES ExHiROM header at 0x40FFC0,
     * which would need 4.25 MiB. Buffering forty times as much for a handful of
     * games is what put this over Android's 256 MiB heap on a real library —
     * ExHiROM titles are `.sfc`/`.smc` and still resolve by extension, so the
     * trade is a small confidence drop on a few files against the sorter
     * working at all.
     */
    const val HEADER_BYTES = 128 * 1024

    /** Entries this small are metadata, not games. */
    private const val MIN_INTERESTING_SIZE = 512L

    /**
     * Cap on entries buffered per archive.
     *
     * A full-set archive can hold hundreds of ROMs, and identification only ever
     * needs the most convincing one. Without a cap the buffered headers scale
     * with set size and exhaust the heap on exactly the libraries this tool is
     * meant for.
     */
    private const val MAX_ENTRIES_EXAMINED = 32

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
                // Biggest first: in a mixed archive the game is the large entry,
                // so the cap below keeps what matters and drops the readmes.
                .sortedByDescending { it.size }
                .take(MAX_ENTRIES_EXAMINED)
                .map { entry ->
                    ArchiveEntry(
                        entry.name,
                        entry.size,
                        zip.getInputStream(entry).use { readHeader(it, entry.size) },
                    )
                }
                .toList()
        }

    private fun readSevenZ(file: File): List<ArchiveEntry> =
        SevenZFile.builder().setFile(file).get().use { archive ->
            buildList {
                while (size < MAX_ENTRIES_EXAMINED) {
                    val entry = archive.nextEntry ?: break
                    if (entry.isDirectory || entry.size < MIN_INTERESTING_SIZE) continue
                    // SevenZFile reads the current entry directly rather than
                    // handing out an InputStream, so it needs its own fill loop.
                    val capacity = minOf(entry.size, HEADER_BYTES.toLong()).toInt()
                    val buffer = ByteArray(capacity)
                    var read = 0
                    while (read < capacity) {
                        val n = archive.read(buffer, read, capacity - read)
                        if (n <= 0) break
                        read += n
                    }
                    val header = if (read == capacity) buffer else buffer.copyOf(read)
                    add(ArchiveEntry(entry.name, entry.size, header))
                }
            }
        }

    private fun readRar(file: File): List<ArchiveEntry> =
        RarArchive(file).use { archive ->
            if (archive.isEncrypted) error("RAR archive is encrypted")
            archive.fileHeaders
                .filter { !it.isDirectory && it.fullUnpackSize >= MIN_INTERESTING_SIZE }
                .sortedByDescending { it.fullUnpackSize }
                .take(MAX_ENTRIES_EXAMINED)
                .map { header ->
                    val name = header.fileName.replace('\\', '/')
                    val bytes = archive.getInputStream(header)
                        .use { readHeader(it, header.fullUnpackSize) }
                    ArchiveEntry(name, header.fullUnpackSize, bytes)
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
                while (size < MAX_ENTRIES_EXAMINED) {
                    val entry = tar.nextEntry ?: break
                    if (entry.isDirectory || entry.size < MIN_INTERESTING_SIZE) continue
                    add(ArchiveEntry(entry.name, entry.size, readHeader(tar, entry.size)))
                }
            }
        }

    /**
     * Buffers the leading bytes of [stream] into an exactly-sized array.
     *
     * Deliberately not a `ByteArrayOutputStream`: that grows by doubling, so
     * collecting an N-byte header transiently allocates up to 2N and leaves the
     * copy behind as garbage. On a constrained Android heap, filling a buffer
     * whose size is known up front is the difference between working and an OOM.
     *
     * [expectedSize] is the entry's uncompressed size from the archive index;
     * pass -1 when it is not known.
     */
    private fun readHeader(stream: InputStream, expectedSize: Long = -1L): ByteArray {
        val capacity = if (expectedSize in 0 until HEADER_BYTES.toLong()) {
            expectedSize.toInt()
        } else {
            HEADER_BYTES
        }
        if (capacity <= 0) return ByteArray(0)

        val buffer = ByteArray(capacity)
        var read = 0
        while (read < capacity) {
            val n = stream.read(buffer, read, capacity - read)
            if (n <= 0) break
            read += n
        }
        return if (read == capacity) buffer else buffer.copyOf(read)
    }
}

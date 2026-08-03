package gg.thor.utils.core.roms

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.IOException
import com.github.junrar.Archive as RarArchive

data class SortResult(
    val item: SortItem,
    val succeeded: Boolean,
    val destinationPaths: List<String> = emptyList(),
    val error: String? = null,
)

data class SortReport(
    val results: List<SortResult>,
    val dryRun: Boolean,
) {
    val moved: List<SortResult> get() = results.filter { it.succeeded }
    val failed: List<SortResult> get() = results.filter { !it.succeeded }
    val bytesHandled: Long get() = moved.sumOf { it.item.totalBytes }
}

/**
 * Carries out a [SortPlan].
 *
 * Every operation is defensive by construction: nothing is ever overwritten, a
 * failed group leaves its files where they were, and one bad file does not stop
 * the run. On a library that took years to assemble, "safe and resumable" beats
 * "fast".
 */
object SortExecutor {

    fun execute(
        plan: SortPlan,
        options: SorterOptions,
        onProgress: (done: Int, total: Int, item: SortItem) -> Unit = { _, _, _ -> },
    ): SortReport {
        val work = plan.actionable
        val results = mutableListOf<SortResult>()

        work.forEachIndexed { index, item ->
            onProgress(index, work.size, item)
            results += try {
                if (options.dryRun) {
                    SortResult(item, true, item.files.map { "${item.destination}/${it.name}" })
                } else {
                    apply(item, plan.romsRoot, options)
                }
            } catch (e: Exception) {
                SortResult(item, false, error = e.message ?: e.javaClass.simpleName)
            }
        }
        onProgress(work.size, work.size, work.lastOrNull() ?: return SortReport(results, options.dryRun))
        return SortReport(results, options.dryRun)
    }

    private fun apply(item: SortItem, romsRoot: File, options: SorterOptions): SortResult {
        val destinationDir = File(romsRoot, item.destination)
        if (!destinationDir.exists() && !destinationDir.mkdirs()) {
            return SortResult(item, false, error = "could not create ${destinationDir.absolutePath}")
        }

        if (item.action == SortAction.EXTRACT) {
            return extract(item, romsRoot, options)
        }

        val written = mutableListOf<String>()
        item.files.forEach { file ->
            val target = uniqueTarget(destinationDir, file.name, file.length())
            if (target == null) {
                // An identical file is already filed; drop the duplicate source when
                // moving, keep it when copying.
                if (item.action == SortAction.MOVE) file.delete()
                written += File(destinationDir, file.name).relativeToRootOrSelf(romsRoot)
                return@forEach
            }
            when (item.action) {
                SortAction.COPY -> file.copyTo(target, overwrite = false)
                else -> moveFile(file, target)
            }
            written += target.relativeToRootOrSelf(romsRoot)
        }
        return SortResult(item, true, written)
    }

    /**
     * Unpacks an archive, filing each game inside it individually.
     *
     * The archive itself is never moved into the ROMs tree — only its contents
     * are, and each one goes to the folder its *own* identification calls for.
     * That matters because a backup archive frequently holds more than one
     * system, and it means unpacking a mixed archive produces a correctly sorted
     * result rather than a pile in whichever folder the archive as a whole
     * matched.
     */
    private fun extract(item: SortItem, romsRoot: File, options: SorterOptions): SortResult {
        val archive = item.primary
        // Detect the container from its magic bytes rather than re-running a full
        // inspection: extraction needs the format, not the buffered headers, and
        // re-reading them would double the memory cost of applying a plan.
        val kind = FileByteSource(archive).use { Signatures.archiveKind(it) }
        val router = EntryRouter(item, romsRoot, options)

        val written = try {
            when (kind) {
                ArchiveKind.ZIP -> extractZip(archive, router)
                ArchiveKind.SEVEN_Z -> extractSevenZ(archive, router)
                ArchiveKind.RAR -> extractRar(archive, router)
                else -> return SortResult(item, false, error = "archive format cannot be extracted")
            }
        } catch (e: Exception) {
            return SortResult(item, false, error = "extract failed: ${e.message}")
        }

        if (written.isEmpty()) {
            return SortResult(item, false, error = "archive held no recognisable games")
        }
        if (options.deleteArchiveAfterExtract) archive.delete()
        return SortResult(item, true, written)
    }

    /**
     * Decides where a single archive entry should land, or that it should be
     * skipped entirely.
     */
    private class EntryRouter(
        private val item: SortItem,
        private val romsRoot: File,
        private val options: SorterOptions,
    ) {
        /** Files that are packaging, not games. Extracting them just litters. */
        private val junkExtensions = setOf(
            "txt", "nfo", "diz", "md", "jpg", "jpeg", "png", "gif", "webp", "pdf",
            "html", "htm", "url", "sfv", "md5", "sha1", "dat", "xml", "ds_store",
        )

        /** Disc sidecars carry no identity of their own; they follow their game. */
        private val sidecarExtensions = setOf(
            "cue", "bin", "img", "sub", "ccd", "toc", "gdi", "m3u", "raw", "sbi",
        )

        /**
         * Target file for [entryName], or null to skip it.
         *
         * Paths are flattened to the bare filename: ES-DE expects games directly
         * inside a system folder, and archives routinely wrap theirs in a folder
         * or two of their own. Cue sheets reference their tracks by bare name, so
         * flattening keeps a disc set working.
         */
        fun targetFor(entryName: String, size: Long): File? {
            val fileName = entryName.replace('\\', '/').substringAfterLast('/')
            if (fileName.isEmpty() || fileName.startsWith("._")) return null

            val extension = RomIdentifier.extensionOf(fileName)
            if (extension in junkExtensions) return null

            val system = item.identification.archiveEntrySystems[entryName]
                ?: item.identification.archiveEntrySystems[fileName]
                ?: when {
                    // A track or cue sheet belongs with the game it describes,
                    // which is whatever the archive as a whole was judged to be.
                    extension in sidecarExtensions -> item.systemId
                    else -> null
                }
                ?: options.unsortedFolder

            val directory = File(romsRoot, system)
            if (!directory.exists() && !directory.mkdirs()) return null
            return SortExecutor.uniqueTarget(directory, fileName, size)
        }

        fun relative(target: File): String = with(SortExecutor) { target.relativeToRootOrSelf(romsRoot) }
    }

    private fun extractZip(archive: File, router: EntryRouter): List<String> =
        ZipFile.builder().setFile(archive).get().use { zip ->
            buildList {
                zip.entries.asSequence().filter { !it.isDirectory }.forEach { entry ->
                    val target = router.targetFor(entry.name, entry.size) ?: return@forEach
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    add(router.relative(target))
                }
            }
        }

    private fun extractSevenZ(archive: File, router: EntryRouter): List<String> =
        SevenZFile.builder().setFile(archive).get().use { seven ->
            buildList {
                while (true) {
                    val entry = seven.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val target = router.targetFor(entry.name, entry.size) ?: continue
                    target.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val n = seven.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                        }
                    }
                    add(router.relative(target))
                }
            }
        }

    /**
     * Extracts a RAR file at a time.
     *
     * `Junrar.extract` would dump the whole archive, including its junk and its
     * folder structure, and gives no opportunity to route entries per system.
     */
    private fun extractRar(archive: File, router: EntryRouter): List<String> =
        RarArchive(archive).use { rar ->
            buildList {
                rar.fileHeaders.forEach { header ->
                    if (header.isDirectory) return@forEach
                    val name = header.fileName.replace('\\', '/')
                    val target = router.targetFor(name, header.fullUnpackSize) ?: return@forEach
                    target.outputStream().use { output -> rar.extractFile(header, output) }
                    add(router.relative(target))
                }
            }
        }

    /**
     * Resolves an archive entry name against [parent], refusing anything that
     * escapes it.
     *
     * Archive entries are attacker-controlled data — a crafted `../../` path is
     * the classic Zip Slip write-anywhere bug, and ROM archives come from
     * wherever the user downloaded them.
     */
    internal fun safeChild(parent: File, entryName: String): File? {
        val cleaned = entryName.replace('\\', '/').trimStart('/')
        if (cleaned.isEmpty()) return null
        val target = File(parent, cleaned).canonicalFile
        val root = parent.canonicalFile
        return if (target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            target
        } else {
            null
        }
    }

    /**
     * Returns where [name] should be written in [dir], or null when the same file
     * is already there and there is nothing to do.
     *
     * Two different rules are at work. A file of the same name *and* the same
     * size is treated as already filed, which is what makes re-running a sort or
     * an unpack idempotent instead of accumulating `Game (2).nes`,
     * `Game (3).nes` on every pass. A file of the same name but a *different*
     * size is a genuinely different dump — usually another region — so it gets a
     * suffix rather than being clobbered.
     *
     * [sourceSize] is the size of the incoming file; pass -1 when it is unknown,
     * which disables the dedup half and always suffixes.
     */
    internal fun uniqueTarget(dir: File, name: String, sourceSize: Long = -1L): File? {
        val direct = File(dir, name)
        if (!direct.exists()) return direct
        if (sourceSize >= 0 && direct.length() == sourceSize) return null

        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "")
        var counter = 2
        while (counter < 1000) {
            val candidate = File(dir, if (extension.isEmpty()) "$stem ($counter)" else "$stem ($counter).$extension")
            if (!candidate.exists()) return candidate
            // An earlier run already wrote this exact file under a suffix.
            if (sourceSize >= 0 && candidate.length() == sourceSize) return null
            counter++
        }
        throw IOException("too many name collisions for $name in ${dir.absolutePath}")
    }

    /**
     * Moves a file, falling back to copy-then-delete.
     *
     * [File.renameTo] fails across filesystems, which is the normal case on a
     * handheld: the library sits on the SD card and the ROMs root on internal
     * storage, or vice versa.
     */
    private fun moveFile(from: File, to: File) {
        if (from.renameTo(to)) return
        from.copyTo(to, overwrite = false)
        if (from.length() != to.length()) {
            to.delete()
            throw IOException("copy verification failed for ${from.name}")
        }
        if (!from.delete()) {
            throw IOException("copied ${from.name} but could not remove the original")
        }
    }

    private fun File.relativeToRootOrSelf(root: File): String =
        try {
            relativeTo(root).path
        } catch (_: IllegalArgumentException) {
            absolutePath
        }
}

package gg.thor.utils.core.roms

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.IOException
import com.github.junrar.Junrar

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
            return extract(item, destinationDir, options)
        }

        val written = mutableListOf<String>()
        item.files.forEach { file ->
            val target = uniqueTarget(destinationDir, file.name)
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

    private fun extract(item: SortItem, destinationDir: File, options: SorterOptions): SortResult {
        val archive = item.primary
        val written = try {
            when (ArchiveInspector.inspect(archive)?.kind) {
                ArchiveKind.ZIP -> extractZip(archive, destinationDir)
                ArchiveKind.SEVEN_Z -> extractSevenZ(archive, destinationDir)
                ArchiveKind.RAR -> {
                    Junrar.extract(archive, destinationDir)
                    destinationDir.listFiles()?.map { it.name } ?: emptyList()
                }
                else -> return SortResult(item, false, error = "archive format cannot be extracted")
            }
        } catch (e: Exception) {
            return SortResult(item, false, error = "extract failed: ${e.message}")
        }

        if (options.deleteArchiveAfterExtract && written.isNotEmpty()) archive.delete()
        return SortResult(item, true, written.map { "${item.destination}/$it" })
    }

    private fun extractZip(archive: File, into: File): List<String> =
        ZipFile.builder().setFile(archive).get().use { zip ->
            buildList {
                zip.entries.asSequence().filter { !it.isDirectory }.forEach { entry ->
                    val target = safeChild(into, entry.name) ?: return@forEach
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    add(entry.name)
                }
            }
        }

    private fun extractSevenZ(archive: File, into: File): List<String> =
        SevenZFile.builder().setFile(archive).get().use { seven ->
            buildList {
                while (true) {
                    val entry = seven.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val target = safeChild(into, entry.name) ?: continue
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val n = seven.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                        }
                    }
                    add(entry.name)
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
     * Returns where [name] should be written in [dir], or null when an identical
     * file is already there.
     *
     * A same-name-different-size file gets a ` (2)` suffix rather than being
     * clobbered — two ROMs with the same filename are usually different regional
     * dumps, and silently destroying one would be unforgivable.
     */
    internal fun uniqueTarget(dir: File, name: String): File? {
        val direct = File(dir, name)
        if (!direct.exists()) return direct

        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "")
        var counter = 2
        while (counter < 1000) {
            val candidate = File(dir, if (extension.isEmpty()) "$stem ($counter)" else "$stem ($counter).$extension")
            if (!candidate.exists()) return candidate
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

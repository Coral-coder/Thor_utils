package gg.thor.utils.core.roms

import java.io.File

enum class SortAction {
    /** Move the group into its ES-DE system folder. */
    MOVE,
    /** Copy instead of move (source library left untouched). */
    COPY,
    /** Unpack the archive into the system folder, then optionally drop the archive. */
    EXTRACT,
    /** Already in the right place — nothing to do. */
    ALREADY_SORTED,
    /**
     * Not provably a console game, so it is left exactly where it is.
     *
     * This is never an operation — the file is not moved, copied, renamed or
     * opened again. Anything the sorter cannot prove is a game is none of its
     * business.
     */
    LEAVE_ALONE,
}

/**
 * One game to be filed, which may be several files on disk.
 *
 * Disc-based games are the reason this is a group rather than a file: moving a
 * `.cue` without the `.bin` tracks it references produces a library that looks
 * sorted and does not boot.
 */
data class SortItem(
    val identification: RomIdentification,
    /** Primary file first, then sidecars that must travel with it. */
    val files: List<File>,
    val action: SortAction,
    /** Destination directory, relative to the ROMs root. */
    val destination: String,
    val note: String,
) {
    val primary: File get() = files.first()
    val systemId: String? get() = identification.systemId
    val totalBytes: Long get() = files.sumOf { it.length() }
}

data class SortPlan(
    val romsRoot: File,
    val items: List<SortItem>,
    /** Files skipped before identification (BIOS, saves, artwork, junk). */
    val ignored: List<Pair<File, String>> = emptyList(),
) {
    val bySystem: Map<String, List<SortItem>>
        get() = items.filter { it.systemId != null }.groupBy { it.systemId!! }

    /** Files the sorter deliberately will not touch. */
    val untouched: List<SortItem> get() = items.filter { it.action == SortAction.LEAVE_ALONE }

    /**
     * The items that will actually be written.
     *
     * Both [SortAction.ALREADY_SORTED] and [SortAction.LEAVE_ALONE] are excluded,
     * so a plan can only ever act on files proven to be console games.
     */
    val actionable: List<SortItem>
        get() = items.filter {
            it.action != SortAction.ALREADY_SORTED && it.action != SortAction.LEAVE_ALONE
        }
    val totalBytes: Long get() = actionable.sumOf { it.totalBytes }
}

data class SorterOptions(
    /** Never write anything; just produce the plan. */
    val dryRun: Boolean = true,
    /** Copy rather than move, leaving the source library intact. */
    val copyInsteadOfMove: Boolean = false,
    /** Unpack archives into the system folder instead of filing the archive itself. */
    val extractArchives: Boolean = false,
    /** Delete an archive after a successful extraction. */
    val deleteArchiveAfterExtract: Boolean = false,
    /** Put multi-disc games in their own subfolder, which ES-DE collapses nicely. */
    val groupMultiDisc: Boolean = true,
    /**
     * Minimum confidence required to file a game. Anything weaker is left where
     * it is rather than guessed into a console's folder.
     */
    val minimumConfidence: Confidence = Confidence.MEDIUM,
)

/**
 * Builds a sorting plan for a messy backup folder.
 *
 * Planning is always separate from execution: the plan is inspectable, so the
 * user can see *why* every game is going where it is going before a single byte
 * moves. Given how easy it is to shred a large library with a bad heuristic,
 * that separation is the whole point.
 */
class RomSorter(private val options: SorterOptions = SorterOptions()) {

    /** Extensions that are never games and should not reach identification. */
    private val ignoredExtensions = setOf(
        "txt", "nfo", "md", "jpg", "jpeg", "png", "gif", "webp", "pdf", "html", "htm",
        "sav", "srm", "state", "ss0", "ss1", "dat", "xml", "json", "ini", "cfg", "db",
        "log", "url", "lnk", "ds_store", "sfv", "md5", "sha1",
    )

    private val ignoredNames = setOf("thumbs.db", ".ds_store", "desktop.ini")

    /**
     * Extensions the sorter refuses to look at, let alone move.
     *
     * App packages are ZIP files, so without this an APK would be opened as an
     * archive and could be identified from some asset buried inside it. These
     * are never games, so the safest handling is to not consider them at all.
     */
    private val neverTouchExtensions = setOf(
        "apk", "apks", "xapk", "apkm", "aab", "obb", "msi", "exe", "dll", "so",
        "jar", "deb", "rpm", "dmg", "pkg", "appimage",
        "mp3", "mp4", "mkv", "avi", "mov", "wav", "flac", "m4a", "webm",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub", "mobi", "torrent",
    )

    /**
     * Walks [source] and decides where everything should go under [romsRoot].
     *
     * @param onProgress called with (filesScanned, currentFile) so a UI can show
     *   life during what may be a long walk over thousands of files.
     */
    fun plan(
        source: File,
        romsRoot: File,
        onProgress: (Int, File) -> Unit = { _, _ -> },
    ): SortPlan {
        val identifier = RomIdentifier(scanRoot = source)
        val ignored = mutableListOf<Pair<File, String>>()
        val candidates = mutableListOf<File>()

        var scanned = 0
        source.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                scanned++
                onProgress(scanned, file)
                val reason = ignoreReason(file)
                if (reason != null) ignored += file to reason else candidates += file
            }

        val grouped = groupSidecars(candidates)
        val items = grouped.map { group -> planGroup(group, identifier, romsRoot) }

        return SortPlan(romsRoot, items, ignored)
    }

    private fun ignoreReason(file: File): String? {
        val name = file.name.lowercase()
        if (name in ignoredNames) return "system file"
        if (name.startsWith("._")) return "macOS resource fork"
        val ext = RomIdentifier.extensionOf(file.name)
        if (ext in neverTouchExtensions) return "never touched (.$ext)"
        // Sidecars are only ignored when standalone; grouping runs before this
        // decision matters, so `.bin` and `.img` are deliberately absent here.
        if (ext in ignoredExtensions) return "not a ROM (.$ext)"
        if (file.length() == 0L) return "empty file"
        return null
    }

    /**
     * Collects files that belong to the same game.
     *
     * Two rules cover the formats that matter: a playlist or cue sheet names its
     * members explicitly, and disc tracks share a basename with their sheet.
     */
    internal fun groupSidecars(files: List<File>): List<List<File>> {
        val remaining = files.toMutableSet()
        val groups = mutableListOf<List<File>>()

        // Descriptors first, so their tracks are claimed before being seen alone.
        //
        // Playlists lead, because references nest: a .m3u names the .cue sheets of
        // each disc, and each .cue names its own .bin tracks. Letting the cues go
        // first would claim their tracks and leave the playlist holding nothing,
        // so the whole multi-disc set would scatter and the .m3u — which is what
        // the emulator actually loads — would end up unsorted.
        val descriptors = files.filter {
            RomIdentifier.extensionOf(it.name) in setOf("cue", "gdi", "m3u", "ccd", "toc")
        }.sortedByDescending { RomIdentifier.extensionOf(it.name) == "m3u" }

        descriptors.forEach { descriptor ->
            if (descriptor !in remaining) return@forEach
            val members = mutableListOf(descriptor)
            remaining -= descriptor

            referencedFiles(descriptor).forEach { referenced ->
                if (referenced in remaining) {
                    members += referenced
                    remaining -= referenced
                }
            }
            // Anything sharing the descriptor's basename (`Game (Track 02).bin`)
            // travels with it too.
            val stem = descriptor.nameWithoutExtension
            remaining.filter { it.parentFile == descriptor.parentFile && it.nameWithoutExtension.startsWith(stem) }
                .forEach { members += it; remaining -= it }

            groups += members
        }

        remaining.sortedBy { it.absolutePath }.forEach { groups += listOf(it) }
        return groups
    }

    /** Parses a cue/gdi/m3u/ccd descriptor for the files it points at. */
    private fun referencedFiles(descriptor: File): List<File> {
        val dir = descriptor.parentFile ?: return emptyList()
        val text = try {
            descriptor.readText()
        } catch (_: Exception) {
            return emptyList()
        }
        return when (RomIdentifier.extensionOf(descriptor.name)) {
            "cue" -> CUE_FILE.findAll(text).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            "gdi" -> GDI_TRACK.findAll(text).map { it.groupValues[1] }
            "m3u" -> text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            else -> emptySequence()
        }.map { File(dir, it.substringAfterLast('/').substringAfterLast('\\')) }
            .filter { it.exists() }
            .toList()
    }

    private fun planGroup(group: List<File>, identifier: RomIdentifier, romsRoot: File): SortItem {
        // Identify on the most informative member: the descriptor if there is one,
        // otherwise the largest file (readmes and tracks are poor witnesses).
        val primary = group.firstOrNull {
            RomIdentifier.extensionOf(it.name) in setOf("cue", "gdi", "m3u")
        } ?: group.maxByOrNull { it.length() } ?: group.first()

        val id = identifier.identify(primary)
        val ordered = listOf(primary) + group.filter { it != primary }

        if (id.systemId == null || id.confidence < options.minimumConfidence) {
            val why = if (id.systemId == null) {
                "not provably a console game"
            } else {
                "confidence ${id.confidence} below required ${options.minimumConfidence}"
            }
            return SortItem(
                identification = id,
                files = ordered,
                action = SortAction.LEAVE_ALONE,
                // Deliberately empty: there is no destination because nothing moves.
                destination = "",
                note = "left alone — $why",
            )
        }

        val destination = destinationFor(id, ordered)
        val destinationDir = File(romsRoot, destination)

        if (primary.parentFile?.absolutePath == destinationDir.absolutePath) {
            return SortItem(id, ordered, SortAction.ALREADY_SORTED, destination, "already in place")
        }

        val action = when {
            options.extractArchives && id.archive?.isReadable == true -> SortAction.EXTRACT
            options.copyInsteadOfMove -> SortAction.COPY
            else -> SortAction.MOVE
        }
        return SortItem(id, ordered, action, destination, id.primaryReason)
    }

    /** System folder, plus a per-game subfolder for multi-disc sets. */
    private fun destinationFor(id: RomIdentification, files: List<File>): String {
        val system = id.systemId!!
        if (!options.groupMultiDisc) return system
        val title = multiDiscTitle(id.file.name) ?: return system
        // Only worth a subfolder when the set really has several discs next to it.
        val siblings = id.file.parentFile?.listFiles()?.count { multiDiscTitle(it.name) == title } ?: 0
        return if (siblings > 1) "$system/$title" else system
    }

    companion object {
        private val CUE_FILE = Regex("""(?im)^\s*FILE\s+(?:"([^"]+)"|(\S+))""")
        private val GDI_TRACK = Regex("""(?im)^\s*\d+\s+\d+\s+\d+\s+\d+\s+"?([^"\s]+\.\w+)"?""")
        private val DISC_TAG = Regex("""(?i)\s*[\[(]\s*(?:disc|disk|cd)\s*\d+[^\[\])]*[])]""")

        /**
         * Title of a multi-disc set, or null when the name carries no disc tag.
         * `Final Fantasy VII (USA) (Disc 2).chd` -> `Final Fantasy VII (USA)`.
         */
        fun multiDiscTitle(fileName: String): String? {
            val base = fileName.substringBeforeLast('.')
            if (!DISC_TAG.containsMatchIn(base)) return null
            return DISC_TAG.replace(base, "").trim().ifEmpty { null }
        }
    }
}

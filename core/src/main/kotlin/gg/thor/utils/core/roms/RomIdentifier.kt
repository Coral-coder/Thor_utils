package gg.thor.utils.core.roms

import java.io.File

/** A single piece of evidence for (or against) a system assignment. */
data class Evidence(val systemId: String, val confidence: Confidence, val reason: String)

/** The verdict for one candidate ROM file. */
data class RomIdentification(
    val file: File,
    /** ES-DE system id, or null when nothing could be determined. */
    val systemId: String?,
    val confidence: Confidence,
    /** Every piece of evidence considered, strongest first. Shown in the UI. */
    val evidence: List<Evidence>,
    val archive: ArchiveContents? = null,
    /** For archives, the entry that decided the verdict. */
    val innerRom: String? = null,
    /**
     * Why this verdict was reached. This is the *resolved* explanation, which can
     * differ from the strongest single piece of evidence — a disc image placed by
     * its folder name says so here.
     */
    val resolvedReason: String? = null,
) {
    val system: GameSystem? get() = systemId?.let { SystemCatalog.byId(it) }
    val isIdentified: Boolean get() = systemId != null
    val primaryReason: String
        get() = resolvedReason ?: evidence.firstOrNull()?.reason ?: "no evidence"
}

/**
 * Decides which ES-DE system a file belongs to.
 *
 * Evidence is gathered from four independent sources and the strongest wins:
 *
 *  1. the bytes of the ROM (or of the best ROM inside an archive),
 *  2. an extension owned by exactly one system,
 *  3. the name of an enclosing folder,
 *  4. a console tag in the filename such as `[SNES]`.
 *
 * Content beats names, because names in a real backup are wrong often enough to
 * matter. Names still carry the day for formats whose bytes are genuinely
 * ambiguous — CHD being the common one.
 */
class RomIdentifier(
    /**
     * Directory the scan started from. Folder-name hints are only taken from
     * directories *below* this, so a scan rooted at `/Games/SNES` does not label
     * everything SNES by accident.
     */
    private val scanRoot: File? = null,
) {

    fun identify(file: File): RomIdentification {
        val evidence = mutableListOf<Evidence>()
        var archive: ArchiveContents? = null
        var innerRom: String? = null

        val extension = extensionOf(file.name)

        if (extension in SystemCatalog.ARCHIVE_EXTENSIONS || looksLikeArchive(file)) {
            archive = ArchiveInspector.inspect(file)
            if (archive != null && archive.isReadable) {
                val best = bestEntry(archive.entries)
                if (best != null) {
                    innerRom = best.first.path
                    evidence += best.second
                }
            } else if (archive != null) {
                evidence += Evidence(
                    "", Confidence.NONE,
                    "archive could not be read: ${archive.error}",
                )
            }
        } else {
            fileSignature(file)?.let { evidence += it }
        }

        SystemCatalog.UNIQUE_EXTENSIONS[extension]?.let { sys ->
            evidence += Evidence(sys.id, Confidence.MEDIUM, "extension .$extension is unique to ${sys.name}")
        }
        folderHint(file)?.let { evidence += it }
        filenameHint(file.name)?.let { evidence += it }

        val resolved = resolve(evidence)
        return RomIdentification(
            file = file,
            systemId = resolved?.systemId,
            confidence = resolved?.confidence ?: Confidence.NONE,
            evidence = evidence.sortedByDescending { it.confidence.ordinal },
            archive = archive,
            innerRom = innerRom,
            resolvedReason = resolved?.reason,
        )
    }

    /**
     * Picks a winner from the collected evidence.
     *
     * The one subtlety is [Signatures.UNKNOWN_DISC]: the bytes proved the file is
     * a disc image but not which console's. That verdict cannot stand on its own,
     * so it is traded for the best name-based hint available.
     */
    private fun resolve(evidence: List<Evidence>): Evidence? {
        val usable = evidence.filter { it.systemId.isNotEmpty() && it.confidence != Confidence.NONE }
        if (usable.isEmpty()) return null

        // A disc image whose header does not name its console can only ever be
        // placed by a name hint. If nothing named a system, say so rather than
        // inventing one.
        val sawAnonymousDisc = usable.any { it.systemId == Signatures.UNKNOWN_DISC }
        val best = usable.filter { it.systemId != Signatures.UNKNOWN_DISC }
            .maxByOrNull { it.confidence.ordinal } ?: return null

        if (!sawAnonymousDisc) return best
        return best.copy(
            // Placing a disc by its folder name is a guess, however good — cap it
            // at medium and record how the decision was reached so the plan can
            // be audited before anything moves.
            confidence = minOf(best.confidence, Confidence.MEDIUM),
            reason = "${best.reason} (disc image, console not in header)",
        )
    }

    private fun looksLikeArchive(file: File): Boolean = try {
        FileByteSource(file).use { Signatures.archiveKind(it) != null }
    } catch (_: Exception) {
        false
    }

    private fun fileSignature(file: File): Evidence? = try {
        FileByteSource(file).use { src ->
            Signatures.identify(src)?.let { Evidence(it.systemId, it.confidence, it.reason) }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Finds the most convincing ROM inside an archive.
     *
     * Ties are broken by size: a `.zip` holding a ROM plus a readme should be
     * judged on the ROM.
     */
    private fun bestEntry(entries: List<ArchiveEntry>): Pair<ArchiveEntry, Evidence>? {
        val scored = entries.mapNotNull { entry ->
            val bySignature = Signatures.identify(entry.asByteSource())
                ?.let { Evidence(it.systemId, it.confidence, "inside archive: ${entry.name} — ${it.reason}") }
            val byExtension = SystemCatalog.UNIQUE_EXTENSIONS[extensionOf(entry.name)]?.let { sys ->
                Evidence(
                    sys.id, Confidence.MEDIUM,
                    "inside archive: ${entry.name} has ${sys.name} extension",
                )
            }
            val best = listOfNotNull(bySignature, byExtension)
                .maxByOrNull { it.confidence.ordinal } ?: return@mapNotNull null
            entry to best
        }
        return scored.maxWithOrNull(
            compareBy<Pair<ArchiveEntry, Evidence>> { it.second.confidence.ordinal }
                .thenBy { it.first.size },
        )
    }

    /** Looks for a system name in the folders between [scanRoot] and the file. */
    private fun folderHint(file: File): Evidence? {
        var dir = file.parentFile ?: return null
        val rootPath = scanRoot?.absolutePath
        while (true) {
            if (rootPath != null && dir.absolutePath == rootPath) return null
            SystemCatalog.matchName(dir.name)?.let { sys ->
                return Evidence(sys.id, Confidence.MEDIUM, "folder '${dir.name}' names ${sys.name}")
            }
            dir = dir.parentFile ?: return null
            if (rootPath != null && !dir.absolutePath.startsWith(rootPath)) return null
        }
    }

    /** Looks for a `(SNES)` / `[PSX]` style console tag in the filename. */
    private fun filenameHint(name: String): Evidence? {
        TAG_PATTERN.findAll(name).forEach { match ->
            val tag = match.groupValues[1]
            SystemCatalog.matchName(tag)?.let { sys ->
                return Evidence(sys.id, Confidence.WEAK, "filename tag '$tag' names ${sys.name}")
            }
        }
        return null
    }

    companion object {
        private val TAG_PATTERN = Regex("""[\[(]([^\[\]()]{2,24})[])]""")

        /**
         * Lowercase extension without the dot.
         *
         * Handles the compound suffixes that show up in ROM sets — `.nkit.iso`
         * identifies a Wii/GameCube image and `.tar.gz` a tarball, and in both
         * cases the last component alone is the wrong answer.
         */
        fun extensionOf(name: String): String {
            val lower = name.lowercase()
            COMPOUND_EXTENSIONS.forEach { if (lower.endsWith(".$it")) return it }
            return lower.substringAfterLast('.', "")
        }

        private val COMPOUND_EXTENSIONS = listOf("nkit.iso", "nkit.gcz", "tar.gz", "tar.bz2", "tar.xz")
    }
}

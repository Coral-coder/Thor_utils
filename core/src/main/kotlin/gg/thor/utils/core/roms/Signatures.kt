package gg.thor.utils.core.roms

/**
 * How sure we are about a system assignment. Ordering matters: a later stage of
 * the identifier never downgrades an earlier, stronger verdict.
 */
enum class Confidence { NONE, WEAK, MEDIUM, STRONG, CERTAIN }

data class SignatureMatch(
    val systemId: String,
    val confidence: Confidence,
    /** Human-readable justification, surfaced in the UI so the user can audit a plan. */
    val reason: String,
)

/**
 * Identifies a system by looking at the actual bytes of a ROM.
 *
 * This is what separates a sorter that works on a real backup from one that
 * trusts filenames. Extensions lie constantly — `.bin` alone is used by at
 * least six systems, and repacked sets routinely carry the wrong suffix.
 */
object Signatures {

    /**
     * Sentinel system id meaning "this is definitely a disc image, but the bytes
     * do not say which console". Callers must resolve it from folder or filename
     * context, or route the file to the unsorted tray.
     */
    const val UNKNOWN_DISC = "?disc"

    /** The Nintendo boot logo, shared by GBA and NDS headers. */
    private val NINTENDO_LOGO = intArrayOf(0x24, 0xFF, 0xAE, 0x51, 0x69, 0x9A, 0xA2, 0x21)

    /** The Game Boy boot logo, checked by the DMG bootrom at 0x104. */
    private val GB_LOGO = intArrayOf(0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73)

    /**
     * Inspects [src] and returns the best content-based match, or null when the
     * bytes carry no recognisable signature (which is normal — plenty of systems,
     * Atari 2600 and Virtual Boy among them, use headerless raw dumps).
     */
    fun identify(src: ByteSource): SignatureMatch? {
        if (src.size <= 0) return null

        cartridges(src)?.let { return it }
        nintendoDiscs(src)?.let { return it }
        segaSystems(src)?.let { return it }
        discImages(src)?.let { return it }
        return null
    }

    // ------------------------------------------------------------ cartridges

    private fun cartridges(src: ByteSource): SignatureMatch? {
        if (src.matchesBytes(0, 0x4E, 0x45, 0x53, 0x1A)) {
            return SignatureMatch("nes", Confidence.CERTAIN, "iNES header magic 'NES\\x1A'")
        }
        if (src.matchesBytes(0, 0x46, 0x44, 0x53, 0x1A) || src.matchesAscii(1, "*NINTENDO-HVC*")) {
            return SignatureMatch("fds", Confidence.CERTAIN, "Famicom Disk System header")
        }
        if (src.matchesAscii(0, "LYNX")) {
            return SignatureMatch("atarilynx", Confidence.CERTAIN, "Lynx LNX header magic")
        }
        if (src.matchesAscii(1, "ATARI7800")) {
            return SignatureMatch("atari7800", Confidence.CERTAIN, "Atari 7800 A78 header")
        }

        // Nintendo 64 — the same ROM ships in three byte orders.
        when {
            src.matchesBytes(0, 0x80, 0x37, 0x12, 0x40) ->
                return SignatureMatch("n64", Confidence.CERTAIN, "N64 big-endian (.z64) magic")
            src.matchesBytes(0, 0x37, 0x80, 0x40, 0x12) ->
                return SignatureMatch("n64", Confidence.CERTAIN, "N64 byte-swapped (.v64) magic")
            src.matchesBytes(0, 0x40, 0x12, 0x37, 0x80) ->
                return SignatureMatch("n64", Confidence.CERTAIN, "N64 little-endian (.n64) magic")
        }

        // Nintendo DS: same boot logo as GBA but at 0xC0 instead of 0x04.
        if (src.matchesBytes(0xC0, *NINTENDO_LOGO)) {
            return SignatureMatch("nds", Confidence.CERTAIN, "Nintendo DS header logo at 0xC0")
        }
        // Game Boy Advance: boot logo at 0x04 plus the fixed 0x96 byte at 0xB2.
        if (src.matchesBytes(0x04, *NINTENDO_LOGO) && src.matchesBytes(0xB2, 0x96)) {
            return SignatureMatch("gba", Confidence.CERTAIN, "GBA header logo + fixed byte 0x96")
        }
        // Game Boy / Color: boot logo at 0x104, CGB flag at 0x143 splits the two.
        if (src.matchesBytes(0x104, *GB_LOGO)) {
            val cgb = src.readAt(0x143, 1).firstOrNull()?.toInt()?.and(0xFF) ?: 0
            return if (cgb == 0x80 || cgb == 0xC0) {
                SignatureMatch("gbc", Confidence.CERTAIN, "Game Boy logo + CGB flag 0x%02X".format(cgb))
            } else {
                SignatureMatch("gb", Confidence.CERTAIN, "Game Boy logo, no CGB flag")
            }
        }

        // Nintendo 3DS containers.
        if (src.matchesAscii(0x100, "NCSD")) {
            return SignatureMatch("n3ds", Confidence.CERTAIN, "3DS NCSD magic (.3ds/.cci)")
        }
        if (src.matchesAscii(0x100, "NCCH")) {
            return SignatureMatch("n3ds", Confidence.CERTAIN, "3DS NCCH magic (.cxi/.app)")
        }
        // CIA: 32-bit LE header size 0x2020, then type 0 and version 0. All eight
        // bytes are checked — the header-size word alone is far too common to
        // stake a whole system assignment on.
        if (src.matchesBytes(0, 0x20, 0x20, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)) {
            return SignatureMatch("n3ds", Confidence.STRONG, "3DS CIA header (size 0x2020, type 0)")
        }

        // Switch.
        if (src.matchesAscii(0x100, "HEAD")) {
            return SignatureMatch("switch", Confidence.CERTAIN, "Switch XCI 'HEAD' magic")
        }
        if (src.matchesAscii(0, "PFS0")) {
            return SignatureMatch("switch", Confidence.CERTAIN, "Switch NSP PFS0 magic")
        }

        // Xbox executables.
        if (src.matchesAscii(0, "XBEH")) {
            return SignatureMatch("xbox", Confidence.CERTAIN, "Xbox XBE executable header")
        }
        if (src.matchesAscii(0, "XEX2")) {
            return SignatureMatch("xbox360", Confidence.CERTAIN, "Xbox 360 XEX2 executable header")
        }

        snesHeader(src)?.let { return it }
        return null
    }

    /**
     * SNES carts have no magic number, only an internal header whose checksum and
     * complement must sum to 0xFFFF. The header sits at a different offset for
     * LoROM and HiROM, and copier dumps prepend 512 bytes, so all six
     * combinations get checked.
     */
    private fun snesHeader(src: ByteSource): SignatureMatch? {
        val copierOffset = if (src.size % 1024L == 512L) 512L else 0L
        for (base in longArrayOf(0x7FC0, 0xFFC0, 0x40FFC0)) {
            val at = base + copierOffset
            val header = src.readAt(at, 32)
            if (header.size < 32) continue
            val complement = (header[28].toInt() and 0xFF) or ((header[29].toInt() and 0xFF) shl 8)
            val checksum = (header[30].toInt() and 0xFF) or ((header[31].toInt() and 0xFF) shl 8)
            if (checksum == 0 && complement == 0) continue
            if (checksum xor complement == 0xFFFF) {
                // Guard against coincidence: the title field must be printable ASCII.
                val title = String(header, 0, 21, Charsets.US_ASCII)
                if (title.all { it.code in 0x20..0x7E }) {
                    return SignatureMatch(
                        "snes", Confidence.STRONG,
                        "SNES internal header at 0x%X (checksum/complement valid)".format(at),
                    )
                }
            }
        }
        return null
    }

    // -------------------------------------------------------- Nintendo discs

    private fun nintendoDiscs(src: ByteSource): SignatureMatch? {
        if (src.matchesBytes(0x1C, 0xC2, 0x33, 0x9F, 0x3D)) {
            return SignatureMatch("gc", Confidence.CERTAIN, "GameCube disc magic 0xC2339F3D")
        }
        if (src.matchesBytes(0x18, 0x5D, 0x1C, 0x9E, 0xA3)) {
            return SignatureMatch("wii", Confidence.CERTAIN, "Wii disc magic 0x5D1C9EA3")
        }
        if (src.matchesAscii(0, "WBFS")) {
            return SignatureMatch("wii", Confidence.CERTAIN, "WBFS container magic")
        }
        // Dolphin's compressed formats keep the platform byte in their own header.
        if (src.matchesBytes(0, 0x52, 0x56, 0x5A, 0x01) || src.matchesBytes(0, 0x57, 0x49, 0x41, 0x01)) {
            val kind = src.asciiAt(0, 3)
            // Byte 0x30 of the WIA/RVZ header is the disc type: 1 = GameCube, 2 = Wii.
            val discType = src.readAt(0x30, 1).firstOrNull()?.toInt()?.and(0xFF)
            val system = if (discType == 1) "gc" else "wii"
            return SignatureMatch(system, Confidence.STRONG, "$kind container, disc type $discType")
        }
        if (src.matchesBytes(0, 0x01, 0xC0, 0x0B, 0xB1)) {
            return SignatureMatch("gc", Confidence.STRONG, "GCZ compressed GameCube image")
        }
        return null
    }

    // ------------------------------------------------------------ Sega

    private fun segaSystems(src: ByteSource): SignatureMatch? {
        // Mega Drive family: console name string lives at 0x100.
        val consoleName = src.asciiAt(0x100, 16)
        if (consoleName.startsWith("SEGA")) {
            return when {
                consoleName.contains("32X") ->
                    SignatureMatch("sega32x", Confidence.CERTAIN, "Cartridge header '$consoleName'")
                consoleName.contains("SEGACD", ignoreCase = true) ->
                    SignatureMatch("megacd", Confidence.CERTAIN, "Cartridge header '$consoleName'")
                else ->
                    SignatureMatch("genesis", Confidence.STRONG, "Mega Drive header '$consoleName'")
            }
        }
        if (src.matchesAscii(0, "SEGADISCSYSTEM") || src.matchesAscii(0x10, "SEGADISCSYSTEM")) {
            return SignatureMatch("megacd", Confidence.CERTAIN, "Mega CD boot header")
        }
        if (src.matchesAscii(0, "SEGA SEGASATURN") || src.matchesAscii(0x10, "SEGA SEGASATURN")) {
            return SignatureMatch("saturn", Confidence.CERTAIN, "Saturn IP.BIN header")
        }
        if (src.matchesAscii(0, "SEGA SEGAKATANA") || src.matchesAscii(0x10, "SEGA SEGAKATANA")) {
            return SignatureMatch("dreamcast", Confidence.CERTAIN, "Dreamcast IP.BIN header")
        }
        // Master System / Game Gear: 'TMR SEGA' near the end of the first banks.
        for (off in longArrayOf(0x1FF0, 0x3FF0, 0x7FF0)) {
            if (src.matchesAscii(off, "TMR SEGA")) {
                // Byte 0x7FFF's high nibble is the region/machine code:
                // 0x5/0x6/0x7 = Game Gear, 0x3/0x4 = Master System.
                val region = src.readAt(off + 0x0F, 1).firstOrNull()?.toInt()?.and(0xF0)?.shr(4)
                val system = if (region in 5..7) "gamegear" else "mastersystem"
                return SignatureMatch(system, Confidence.STRONG, "'TMR SEGA' header at 0x%X".format(off))
            }
        }
        return null
    }

    // ------------------------------------------------------- generic discs

    private fun discImages(src: ByteSource): SignatureMatch? {
        if (src.matchesAscii(0, "MComprHD")) {
            // A CHD says "compressed disc" and nothing else — the wrapped console is
            // not recorded in the header. Claiming a system here would misfile every
            // Saturn, Dreamcast and PCE-CD set in a library, so report the honest
            // answer and let folder/filename context resolve it.
            return SignatureMatch(UNKNOWN_DISC, Confidence.WEAK, "CHD container (console not encoded in header)")
        }
        if (src.matchesAscii(0, "CISO") || src.matchesAscii(0, "ZISO")) {
            return SignatureMatch("psp", Confidence.MEDIUM, "CSO/ZSO compressed UMD image")
        }

        // ISO 9660: 'CD001' at 0x8001 for a 2048-byte-per-sector image.
        if (src.matchesAscii(0x8001, "CD001")) {
            return iso9660(src, 0x8000)
        }
        // Raw 2352-byte-per-sector image: same descriptor, shifted by the sync
        // pattern + header, and every sector is 2352 bytes wide.
        if (src.matchesBytes(0, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00)) {
            val descriptor = 16L * 2352 + 24
            if (src.matchesAscii(descriptor + 1, "CD001")) {
                return iso9660(src, descriptor)
            }
            return SignatureMatch(UNKNOWN_DISC, Confidence.WEAK, "Raw 2352-byte sector disc image")
        }
        return null
    }

    /**
     * Reads the ISO 9660 primary volume descriptor to name the console.
     *
     * The 32-byte system identifier at +0x08 is filled in by the mastering tools:
     * Sony writes `PLAYSTATION`, PSP UMDs write `PSP GAME`, and Sega writes its
     * own strings. It is the most reliable console marker a plain ISO carries.
     */
    private fun iso9660(src: ByteSource, descriptorStart: Long): SignatureMatch {
        val systemId = src.asciiAt(descriptorStart + 8, 32)
        val volumeId = src.asciiAt(descriptorStart + 40, 32)
        val upper = systemId.uppercase()
        return when {
            upper.contains("PSP GAME") ->
                SignatureMatch("psp", Confidence.CERTAIN, "ISO 9660 system id '$systemId'")
            upper.contains("PLAYSTATION") -> {
                // PS1 and PS2 share the identifier. PS1 discs are CD-sized; anything
                // past the ~800 MB CD ceiling is a DVD and therefore PS2.
                val isDvd = src.size > 900L * 1024 * 1024
                if (isDvd) {
                    SignatureMatch("ps2", Confidence.STRONG, "PlayStation ISO, DVD-sized ($volumeId)")
                } else {
                    SignatureMatch("psx", Confidence.MEDIUM, "PlayStation ISO, CD-sized ($volumeId)")
                }
            }
            upper.contains("SEGA SEGAKATANA") ->
                SignatureMatch("dreamcast", Confidence.CERTAIN, "ISO 9660 system id '$systemId'")
            upper.contains("SEGA SEGASATURN") ->
                SignatureMatch("saturn", Confidence.CERTAIN, "ISO 9660 system id '$systemId'")
            upper.contains("XBOX") ->
                SignatureMatch("xbox", Confidence.STRONG, "ISO 9660 system id '$systemId'")
            else ->
                SignatureMatch(UNKNOWN_DISC, Confidence.WEAK, "Unlabelled ISO 9660 image ('$volumeId')")
        }
    }

    // ------------------------------------------------------------ containers

    /** Detects archive containers so the caller knows to look inside instead. */
    fun archiveKind(src: ByteSource): ArchiveKind? = when {
        src.matchesBytes(0, 0x50, 0x4B, 0x03, 0x04) -> ArchiveKind.ZIP
        src.matchesBytes(0, 0x50, 0x4B, 0x05, 0x06) -> ArchiveKind.ZIP
        src.matchesBytes(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> ArchiveKind.SEVEN_Z
        src.matchesAscii(0, "Rar!") -> ArchiveKind.RAR
        src.matchesBytes(0, 0x1F, 0x8B) -> ArchiveKind.GZIP
        src.matchesAscii(0, "BZh") -> ArchiveKind.BZIP2
        src.matchesBytes(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> ArchiveKind.XZ
        else -> null
    }

    private fun containers(src: ByteSource): SignatureMatch? = null
}

enum class ArchiveKind { ZIP, SEVEN_Z, RAR, GZIP, BZIP2, XZ }

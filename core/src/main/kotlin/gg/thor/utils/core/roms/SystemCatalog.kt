package gg.thor.utils.core.roms

/**
 * A game system as ES-DE knows it.
 *
 * [id] is authoritative: it is the exact directory name ES-DE expects underneath
 * the ROMs root (`ROMs/<id>/`). Everything else on this type exists to help us
 * decide that a given file belongs to that directory.
 */
data class GameSystem(
    /** ES-DE folder name, e.g. `snes`. */
    val id: String,
    /** Human-readable name shown in the UI. */
    val name: String,
    val manufacturer: String,
    /**
     * Extensions (lowercase, no leading dot) that belong *unambiguously* to this
     * system. Extensions shared between systems live in [SystemCatalog.AMBIGUOUS]
     * instead and are resolved by content inspection.
     */
    val extensions: Set<String> = emptySet(),
    /**
     * Folder/name fragments seen in the wild that mean "this system". Used when a
     * backup already has some structure, or when the filename carries a tag such
     * as `(USA) [SNES]`.
     */
    val aliases: Set<String> = emptySet(),
    /** True for systems whose games use two screens (relevant on the AYN Thor). */
    val dualScreen: Boolean = false,
    /** Disc-based systems need sidecar grouping (`.cue`+`.bin`, `.gdi`+tracks…). */
    val discBased: Boolean = false,
) {
    /** Directory this system's games live in, relative to the ROMs root. */
    val folder: String get() = id
}

/**
 * The ES-DE system database.
 *
 * Folder names follow ES-DE's stock `es_systems.xml`. Where ES-DE accepts more
 * than one name for the same machine (`genesis` vs `megadrive`), the stock
 * name is the [GameSystem.id] and the other is an alias so that pre-sorted
 * backups still resolve.
 */
object SystemCatalog {

    val systems: List<GameSystem> = listOf(
        // ---------------------------------------------------------------- Nintendo
        GameSystem(
            id = "nes", name = "Nintendo Entertainment System", manufacturer = "Nintendo",
            extensions = setOf("nes", "unf", "unif"),
            aliases = setOf("famicom", "nintendo", "fc"),
        ),
        GameSystem(
            id = "fds", name = "Famicom Disk System", manufacturer = "Nintendo",
            extensions = setOf("fds"),
            aliases = setOf("famicomdisksystem", "disksystem"),
        ),
        GameSystem(
            id = "snes", name = "Super Nintendo", manufacturer = "Nintendo",
            extensions = setOf("sfc", "smc", "swc", "fig"),
            aliases = setOf("supernintendo", "superfamicom", "sfc", "super nes"),
        ),
        GameSystem(
            id = "satellaview", name = "Satellaview", manufacturer = "Nintendo",
            extensions = setOf("bs"),
        ),
        GameSystem(
            id = "n64", name = "Nintendo 64", manufacturer = "Nintendo",
            extensions = setOf("z64", "n64", "v64", "u64"),
            aliases = setOf("nintendo64"),
        ),
        GameSystem(
            id = "n64dd", name = "Nintendo 64DD", manufacturer = "Nintendo",
            extensions = setOf("ndd"),
        ),
        GameSystem(
            id = "gc", name = "Nintendo GameCube", manufacturer = "Nintendo",
            extensions = setOf("gcm", "gcz", "rvz", "nkit"),
            aliases = setOf("gamecube", "ngc", "dolphin-gc"),
            discBased = true,
        ),
        GameSystem(
            id = "wii", name = "Nintendo Wii", manufacturer = "Nintendo",
            extensions = setOf("wbfs", "wia"),
            aliases = setOf("nintendowii"),
            discBased = true,
        ),
        GameSystem(
            id = "wiiu", name = "Nintendo Wii U", manufacturer = "Nintendo",
            extensions = setOf("wud", "wux", "wua"),
            aliases = setOf("wii u", "nintendowiiu", "cemu"),
            dualScreen = true, discBased = true,
        ),
        GameSystem(
            id = "switch", name = "Nintendo Switch", manufacturer = "Nintendo",
            extensions = setOf("nsp", "xci", "nsz", "xcz"),
            aliases = setOf("nx", "nintendoswitch"),
        ),
        GameSystem(
            id = "gb", name = "Game Boy", manufacturer = "Nintendo",
            extensions = setOf("gb"),
            aliases = setOf("gameboy"),
        ),
        GameSystem(
            id = "gbc", name = "Game Boy Color", manufacturer = "Nintendo",
            extensions = setOf("gbc"),
            aliases = setOf("gameboycolor"),
        ),
        GameSystem(
            id = "gba", name = "Game Boy Advance", manufacturer = "Nintendo",
            extensions = setOf("gba", "agb"),
            aliases = setOf("gameboyadvance"),
        ),
        GameSystem(
            id = "nds", name = "Nintendo DS", manufacturer = "Nintendo",
            extensions = setOf("nds", "dsi", "ids"),
            aliases = setOf("nintendods", "ds", "melonds", "drastic"),
            dualScreen = true,
        ),
        GameSystem(
            id = "n3ds", name = "Nintendo 3DS", manufacturer = "Nintendo",
            // `.app` is deliberately absent. It is a 3DS NCCH container but far
            // more commonly something else entirely, and claiming it sent every
            // stray .app in a backup to the 3DS folder. Genuine 3DS .app files
            // are still caught with certainty by their NCCH magic at 0x100.
            extensions = setOf("3ds", "3dsx", "cci", "cxi", "cia"),
            aliases = setOf("3ds", "nintendo3ds", "citra", "azahar", "lime3ds"),
            dualScreen = true,
        ),
        GameSystem(
            id = "virtualboy", name = "Virtual Boy", manufacturer = "Nintendo",
            extensions = setOf("vb", "vboy"),
            aliases = setOf("vb"),
        ),
        GameSystem(
            id = "pokemonmini", name = "Pokémon Mini", manufacturer = "Nintendo",
            extensions = setOf("min"),
        ),
        GameSystem(
            id = "gameandwatch", name = "Game & Watch", manufacturer = "Nintendo",
            extensions = setOf("mgw"),
            aliases = setOf("gnw", "game and watch"),
        ),

        // -------------------------------------------------------------------- Sega
        GameSystem(
            id = "sg-1000", name = "SG-1000", manufacturer = "Sega",
            extensions = setOf("sg"),
            aliases = setOf("sg1000"),
        ),
        GameSystem(
            id = "mastersystem", name = "Master System", manufacturer = "Sega",
            extensions = setOf("sms"),
            aliases = setOf("sms", "segamastersystem", "markiii"),
        ),
        GameSystem(
            id = "genesis", name = "Mega Drive / Genesis", manufacturer = "Sega",
            extensions = setOf("md", "gen", "smd"),
            aliases = setOf("megadrive", "mega drive", "megadrive-japan", "sega genesis"),
        ),
        GameSystem(
            id = "sega32x", name = "Sega 32X", manufacturer = "Sega",
            extensions = setOf("32x"),
            aliases = setOf("32x", "sega32x", "mega32x"),
        ),
        GameSystem(
            id = "megacd", name = "Mega CD / Sega CD", manufacturer = "Sega",
            aliases = setOf("segacd", "sega cd", "mega-cd"),
            discBased = true,
        ),
        GameSystem(
            id = "saturn", name = "Sega Saturn", manufacturer = "Sega",
            aliases = setOf("segasaturn"),
            discBased = true,
        ),
        GameSystem(
            id = "dreamcast", name = "Dreamcast", manufacturer = "Sega",
            extensions = setOf("gdi", "cdi"),
            aliases = setOf("dc", "segadreamcast", "flycast", "redream"),
            discBased = true,
        ),
        GameSystem(
            id = "gamegear", name = "Game Gear", manufacturer = "Sega",
            extensions = setOf("gg"),
            aliases = setOf("gg", "segagamegear"),
        ),

        // ------------------------------------------------------------------- Sony
        GameSystem(
            id = "psx", name = "PlayStation", manufacturer = "Sony",
            aliases = setOf("ps1", "playstation", "psone", "duckstation", "epsxe"),
            discBased = true,
        ),
        GameSystem(
            id = "ps2", name = "PlayStation 2", manufacturer = "Sony",
            extensions = setOf("cso", "zso"),
            aliases = setOf("playstation2", "aethersx2", "nethersx2", "pcsx2"),
            discBased = true,
        ),
        GameSystem(
            id = "ps3", name = "PlayStation 3", manufacturer = "Sony",
            extensions = setOf("ps3", "pkg"),
            aliases = setOf("playstation3", "rpcs3"),
            discBased = true,
        ),
        GameSystem(
            id = "psp", name = "PlayStation Portable", manufacturer = "Sony",
            aliases = setOf("playstationportable", "ppsspp"),
            discBased = true,
        ),
        GameSystem(
            id = "psvita", name = "PlayStation Vita", manufacturer = "Sony",
            extensions = setOf("vpk"),
            aliases = setOf("vita", "vita3k"),
        ),

        // ------------------------------------------------------------------ Atari
        GameSystem(
            id = "atari2600", name = "Atari 2600", manufacturer = "Atari",
            extensions = setOf("a26"),
            aliases = setOf("2600", "vcs"),
        ),
        GameSystem(
            id = "atari5200", name = "Atari 5200", manufacturer = "Atari",
            extensions = setOf("a52"),
            aliases = setOf("5200"),
        ),
        GameSystem(
            id = "atari7800", name = "Atari 7800", manufacturer = "Atari",
            extensions = setOf("a78"),
            aliases = setOf("7800"),
        ),
        GameSystem(
            id = "atarilynx", name = "Atari Lynx", manufacturer = "Atari",
            extensions = setOf("lnx", "lyx"),
            aliases = setOf("lynx"),
        ),
        GameSystem(
            id = "atarijaguar", name = "Atari Jaguar", manufacturer = "Atari",
            extensions = setOf("j64", "jag"),
            aliases = setOf("jaguar"),
        ),
        GameSystem(
            id = "atarijaguarcd", name = "Atari Jaguar CD", manufacturer = "Atari",
            aliases = setOf("jaguarcd"),
            discBased = true,
        ),
        GameSystem(
            id = "atarist", name = "Atari ST", manufacturer = "Atari",
            extensions = setOf("st", "stx", "msa"),
            aliases = setOf("st"),
        ),
        GameSystem(
            id = "atari800", name = "Atari 800", manufacturer = "Atari",
            extensions = setOf("atr", "xex"),
            aliases = setOf("atarixe", "atari8bit"),
        ),

        // ------------------------------------------------------------------- NEC
        GameSystem(
            id = "pcengine", name = "PC Engine / TurboGrafx-16", manufacturer = "NEC",
            extensions = setOf("pce"),
            aliases = setOf("tg16", "turbografx", "turbografx16", "pce"),
        ),
        GameSystem(
            id = "pcenginecd", name = "PC Engine CD / TurboGrafx-CD", manufacturer = "NEC",
            aliases = setOf("tg-cd", "turbografxcd", "pcecd"),
            discBased = true,
        ),
        GameSystem(
            id = "supergrafx", name = "SuperGrafx", manufacturer = "NEC",
            extensions = setOf("sgx"),
        ),
        GameSystem(
            id = "pcfx", name = "PC-FX", manufacturer = "NEC",
            aliases = setOf("pc-fx"),
            discBased = true,
        ),

        // ------------------------------------------------------------------- SNK
        GameSystem(
            id = "neogeo", name = "Neo Geo", manufacturer = "SNK",
            aliases = setOf("neo geo", "neogeoaes", "neogeomvs"),
        ),
        GameSystem(
            id = "neogeocd", name = "Neo Geo CD", manufacturer = "SNK",
            aliases = setOf("neo geo cd"),
            discBased = true,
        ),
        GameSystem(
            id = "ngp", name = "Neo Geo Pocket", manufacturer = "SNK",
            extensions = setOf("ngp"),
        ),
        GameSystem(
            id = "ngpc", name = "Neo Geo Pocket Color", manufacturer = "SNK",
            extensions = setOf("ngc", "ngpc"),
        ),

        // ---------------------------------------------------------------- Bandai
        GameSystem(
            id = "wonderswan", name = "WonderSwan", manufacturer = "Bandai",
            extensions = setOf("ws"),
        ),
        GameSystem(
            id = "wonderswancolor", name = "WonderSwan Color", manufacturer = "Bandai",
            extensions = setOf("wsc"),
        ),

        // ---------------------------------------------------------------- Arcade
        GameSystem(
            id = "arcade", name = "Arcade", manufacturer = "Various",
            aliases = setOf("mame", "fba", "fbneo", "final burn", "finalburn", "capcom"),
        ),
        GameSystem(
            id = "mame", name = "MAME", manufacturer = "Various",
            aliases = setOf("mame2003", "mame2010", "mame2016"),
        ),
        GameSystem(
            id = "fbneo", name = "FinalBurn Neo", manufacturer = "Various",
            aliases = setOf("fbn", "fbalpha"),
        ),
        GameSystem(
            id = "cps1", name = "Capcom Play System", manufacturer = "Capcom",
        ),
        GameSystem(
            id = "cps2", name = "Capcom Play System II", manufacturer = "Capcom",
        ),
        GameSystem(
            id = "cps3", name = "Capcom Play System III", manufacturer = "Capcom",
        ),
        GameSystem(
            id = "naomi", name = "Sega NAOMI", manufacturer = "Sega",
            aliases = setOf("naomi2"),
        ),

        // --------------------------------------------------------------- Computers
        GameSystem(
            id = "amiga", name = "Commodore Amiga", manufacturer = "Commodore",
            extensions = setOf("adf", "adz", "dms", "ipf", "hdf", "lha"),
        ),
        GameSystem(
            id = "amigacd32", name = "Amiga CD32", manufacturer = "Commodore",
            aliases = setOf("cd32"),
            discBased = true,
        ),
        GameSystem(
            id = "c64", name = "Commodore 64", manufacturer = "Commodore",
            extensions = setOf("d64", "t64", "crt", "prg", "g64", "x64"),
            aliases = setOf("commodore64", "vice"),
        ),
        GameSystem(
            id = "vic20", name = "Commodore VIC-20", manufacturer = "Commodore",
            extensions = setOf("20", "40", "60"),
        ),
        GameSystem(
            id = "zxspectrum", name = "ZX Spectrum", manufacturer = "Sinclair",
            extensions = setOf("tzx", "z80", "sna", "szx"),
            aliases = setOf("spectrum", "zx"),
        ),
        GameSystem(
            id = "amstradcpc", name = "Amstrad CPC", manufacturer = "Amstrad",
            extensions = setOf("cpc", "cdt"),
            aliases = setOf("amstrad", "cpc"),
        ),
        GameSystem(
            id = "msx", name = "MSX", manufacturer = "Microsoft/ASCII",
            extensions = setOf("mx1"),
        ),
        GameSystem(
            id = "msx2", name = "MSX2", manufacturer = "Microsoft/ASCII",
            extensions = setOf("mx2"),
        ),
        GameSystem(
            id = "dos", name = "MS-DOS", manufacturer = "Microsoft",
            // Only the DOSBox archive format is claimed. ES-DE will also launch a
            // bare .exe/.com/.bat, but treating those as DOS games meant every
            // Windows installer sitting in a downloads folder was filed under
            // dos/ — and a downloads folder is exactly what people point this at.
            // A real DOS game is nearly always a .dosz or a zip these days.
            extensions = setOf("dosz"),
            aliases = setOf("msdos", "pc-dos", "dosbox"),
        ),
        GameSystem(
            id = "x68000", name = "Sharp X68000", manufacturer = "Sharp",
            extensions = setOf("xdf", "hdm", "dim"),
        ),

        // ------------------------------------------------------------------ Other
        GameSystem(
            id = "3do", name = "3DO Interactive Multiplayer", manufacturer = "3DO",
            aliases = setOf("panasonic3do", "3do interactive"),
            discBased = true,
        ),
        GameSystem(
            id = "coleco", name = "ColecoVision", manufacturer = "Coleco",
            extensions = setOf("col"),
            aliases = setOf("colecovision"),
        ),
        GameSystem(
            id = "intellivision", name = "Intellivision", manufacturer = "Mattel",
            extensions = setOf("int", "itv"),
            aliases = setOf("intv"),
        ),
        GameSystem(
            id = "odyssey2", name = "Magnavox Odyssey²", manufacturer = "Magnavox",
            extensions = setOf("o2"),
            aliases = setOf("videopac", "odyssey"),
        ),
        GameSystem(
            id = "vectrex", name = "Vectrex", manufacturer = "GCE",
            extensions = setOf("vec"),
        ),
        GameSystem(
            id = "xbox", name = "Microsoft Xbox", manufacturer = "Microsoft",
            extensions = setOf("xbe"),
            aliases = setOf("xbox original", "xemu"),
            discBased = true,
        ),
        GameSystem(
            id = "xbox360", name = "Xbox 360", manufacturer = "Microsoft",
            extensions = setOf("xex"),
            aliases = setOf("x360", "xenia"),
            discBased = true,
        ),
        GameSystem(
            id = "scummvm", name = "ScummVM", manufacturer = "Various",
            extensions = setOf("scummvm", "svm"),
        ),
        GameSystem(
            id = "pico8", name = "PICO-8", manufacturer = "Lexaloffle",
            extensions = setOf("p8"),
            aliases = setOf("pico-8"),
        ),
        GameSystem(
            id = "ports", name = "Ports", manufacturer = "Various",
            aliases = setOf("port"),
        ),
    )

    private val byId: Map<String, GameSystem> = systems.associateBy { it.id }

    /**
     * Extensions that must never decide a system on their own.
     *
     * Two kinds live here. Some are shared by several consoles (`.bin`, `.iso`)
     * and need header sniffing to tell apart. Others belong to a console *and*
     * to ordinary computer files — `.app` is a 3DS container but usually is not,
     * and `.exe`/`.com`/`.bat` are DOS games far less often than they are
     * Windows programs. Filing those by extension misplaces non-games, which is
     * worse than leaving them unsorted, so identification must come from the
     * bytes instead.
     */
    val AMBIGUOUS: Set<String> = setOf(
        "app", "exe", "com", "bat", "conf", "msi", "apk", "jar",
        "bin", "iso", "img", "chd", "cue", "ccd", "mds", "mdf", "nrg", "rom",
        "zip", "7z", "rar", "m3u", "pbp", "elf", "dsk", "cas", "tap", "wad",
        "cso", "zso", "xex",
    )

    /**
     * Extension -> system, for extensions owned by exactly one system.
     *
     * Built defensively on two counts. An extension claimed by more than one
     * system is dropped and left to content inspection, so a careless catalog
     * edit cannot silently misfile a library. An extension listed in [AMBIGUOUS]
     * is dropped for the same reason even if only one system claims it — that
     * list is the record of extensions known to appear on files that are not
     * games at all, and it is enforced here rather than merely documented.
     */
    val UNIQUE_EXTENSIONS: Map<String, GameSystem> = buildMap {
        val claims = mutableMapOf<String, MutableList<GameSystem>>()
        systems.forEach { sys ->
            sys.extensions.forEach { ext -> claims.getOrPut(ext) { mutableListOf() }.add(sys) }
        }
        claims.forEach { (ext, owners) ->
            if (owners.size == 1 && ext !in AMBIGUOUS) put(ext, owners.single())
        }
    }


    /** Archive containers we can look inside. */
    val ARCHIVE_EXTENSIONS: Set<String> = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz")

    /** Playlist / sidecar files that ride along with the game they describe. */
    val SIDECAR_EXTENSIONS: Set<String> = setOf(
        "cue", "gdi", "ccd", "sub", "m3u", "toc", "bin", "img", "iso", "raw", "sbi",
    )

    fun byId(id: String): GameSystem? = byId[id]

    /** Systems whose games genuinely use two screens. Drives the dual-screen guide. */
    val dualScreenSystems: List<GameSystem> get() = systems.filter { it.dualScreen }

    /**
     * Resolve a free-form folder or tag fragment ("Sega Genesis", "PS1", "nds")
     * to a system. Matching is case- and punctuation-insensitive.
     */
    fun matchName(fragment: String): GameSystem? {
        val norm = normalize(fragment)
        if (norm.isEmpty()) return null
        systems.forEach { sys ->
            if (normalize(sys.id) == norm) return sys
            if (normalize(sys.name) == norm) return sys
            sys.aliases.forEach { if (normalize(it) == norm) return sys }
        }
        return null
    }

    internal fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }
}

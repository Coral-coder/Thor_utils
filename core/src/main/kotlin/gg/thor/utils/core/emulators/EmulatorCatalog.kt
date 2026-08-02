package gg.thor.utils.core.emulators

/** How an emulator gets a picture onto the AYN Thor's second screen. */
enum class DualScreenSupport(val label: String) {
    /** Detects the second display and drives it itself. Nothing to configure. */
    NATIVE("Native second-screen output"),
    /** Works, but a setting has to be switched on first. */
    OPT_IN("Supported after enabling a setting"),
    /**
     * No second-display code, but the game's two screens can be arranged so the
     * bottom half lands on the bottom panel when the window spans both.
     */
    LAYOUT_WORKAROUND("Via screen-layout workaround"),
    /** Single screen only. */
    NONE("Single screen only"),
    /** Not verified on this device by the author of this catalog. */
    UNKNOWN("Unverified"),
}

/** How confident we are in the recorded Android application id. */
enum class IdConfidence { VERIFIED, LIKELY }

data class Emulator(
    val name: String,
    /** ES-DE system ids this emulator covers. */
    val systems: List<String>,
    /**
     * Android application ids. First entry is the primary; others are known
     * forks or store variants that should also be recognised when installed.
     */
    val packageIds: List<String>,
    val idConfidence: IdConfidence,
    val dualScreen: DualScreenSupport,
    /** Concrete, actionable setup note — what to actually tap. */
    val notes: String,
    val recommended: Boolean = false,
)

/**
 * Emulator recommendations for the AYN Thor, focused on the second screen.
 *
 * Application ids matter here beyond documentation: the launcher matches
 * installed packages against this list to group emulators and to offer
 * per-display launching. Ids marked [IdConfidence.LIKELY] are the widely used
 * ones but were not confirmed against a store listing while this was written, so
 * the launcher always falls back to scanning installed packages by name rather
 * than trusting the list.
 */
object EmulatorCatalog {

    val emulators: List<Emulator> = listOf(
        // ------------------------------------------------- genuinely dual-screen
        Emulator(
            name = "melonDS",
            systems = listOf("nds"),
            packageIds = listOf("me.magnum.melonds"),
            idConfidence = IdConfidence.VERIFIED,
            dualScreen = DualScreenSupport.NATIVE,
            notes = "The reference choice for DS on the Thor: it finds the second panel " +
                "on its own and puts the touch screen there. Check Settings → Video for " +
                "the dual-screen layout if it starts up mirrored.",
            recommended = true,
        ),
        Emulator(
            name = "MelonDualDS",
            systems = listOf("nds"),
            packageIds = listOf("me.magnum.melonds.dualscreen", "me.magnum.melonds"),
            idConfidence = IdConfidence.LIKELY,
            dualScreen = DualScreenSupport.NATIVE,
            notes = "Community melonDS fork built specifically for dual-screen handhelds. " +
                "Worth trying if stock melonDS puts the screens on the wrong panels.",
        ),
        Emulator(
            name = "DraStic",
            systems = listOf("nds"),
            packageIds = listOf("com.dsemu.drastic"),
            idConfidence = IdConfidence.LIKELY,
            dualScreen = DualScreenSupport.OPT_IN,
            notes = "Fastest DS option and now free. Second-screen output depends on the " +
                "build — set the screen layout manually if only one panel lights up.",
        ),
        Emulator(
            name = "Azahar",
            systems = listOf("n3ds"),
            packageIds = listOf("io.github.lime3ds.android"),
            idConfidence = IdConfidence.VERIFIED,
            dualScreen = DualScreenSupport.OPT_IN,
            notes = "The current 3DS recommendation — the merge of Lime3DS and PabloMK7's " +
                "Citra fork. Use the vanilla (non-Play) build for faster file access. " +
                "Set the screen layout to separate windows to feed the bottom panel.",
            recommended = true,
        ),
        Emulator(
            name = "Citra MMJ",
            systems = listOf("n3ds"),
            packageIds = listOf("org.citra.emu"),
            idConfidence = IdConfidence.LIKELY,
            dualScreen = DualScreenSupport.LAYOUT_WORKAROUND,
            notes = "Older but faster than Azahar on demanding titles. Less accurate; " +
                "keep it as a fallback for games Azahar cannot hold 60 fps in.",
        ),

        // ------------------------------------------------------- single screen
        Emulator(
            name = "Dolphin",
            systems = listOf("gc", "wii"),
            packageIds = listOf("org.dolphinemu.dolphinemu"),
            idConfidence = IdConfidence.LIKELY,
            dualScreen = DualScreenSupport.NONE,
            notes = "GameCube and Wii. The Wii U GamePad's second screen is a Wii U " +
                "feature and does not apply here.",
            recommended = true,
        ),
        Emulator(
            name = "PPSSPP",
            systems = listOf("psp"),
            packageIds = listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"),
            idConfidence = IdConfidence.LIKELY,
            dualScreen = DualScreenSupport.NONE,
            notes = "PSP. Excellent on the 8 Gen 2 — most of the library runs at 3x " +
                "resolution without trouble.",
            recommended = true,
        ),
        Emulator(
            name = "DuckStation",
            systems = listOf("psx"),
            packageIds = listOf("com.github.stenzek.duckstation"),
            idConfidence = IdConfidence.LIKELY,
            dualScreen = DualScreenSupport.NONE,
            notes = "PlayStation. Prefer CHD images to keep the library small.",
            recommended = true,
        ),
        Emulator(
            name = "NetherSX2 / AetherSX2",
            systems = listOf("ps2"),
            packageIds = listOf("xyz.aethersx2.android"),
            idConfidence = IdConfidence.LIKELY,
            dualScreen = DualScreenSupport.NONE,
            notes = "PS2. AetherSX2 is discontinued; the NetherSX2 patch is what most " +
                "people run, and it shares the application id.",
        ),
        Emulator(
            name = "Flycast",
            systems = listOf("dreamcast", "naomi"),
            packageIds = listOf("com.flycast.emulator"),
            idConfidence = IdConfidence.LIKELY,
            dualScreen = DualScreenSupport.NONE,
            notes = "Dreamcast and NAOMI arcade.",
            recommended = true,
        ),
        Emulator(
            name = "Redream",
            systems = listOf("dreamcast"),
            packageIds = listOf("io.recompiled.redream"),
            idConfidence = IdConfidence.LIKELY,
            dualScreen = DualScreenSupport.NONE,
            notes = "Dreamcast alternative with a cleaner upscaler; premium unlock is " +
                "needed for high internal resolutions.",
        ),
        Emulator(
            name = "RetroArch",
            systems = listOf(
                "nes", "snes", "genesis", "gb", "gbc", "gba", "n64", "pcengine",
                "mastersystem", "gamegear", "arcade", "neogeo", "atari2600",
            ),
            packageIds = listOf("com.retroarch.aarch64", "com.retroarch"),
            idConfidence = IdConfidence.LIKELY,
            dualScreen = DualScreenSupport.LAYOUT_WORKAROUND,
            notes = "Everything 2D. Its melonDS core can be pointed at the second panel, " +
                "but the standalone melonDS app handles the Thor's screens better.",
            recommended = true,
        ),
        Emulator(
            name = "Vita3K",
            systems = listOf("psvita"),
            packageIds = listOf("org.vita3k.emulator"),
            idConfidence = IdConfidence.LIKELY,
            dualScreen = DualScreenSupport.NONE,
            notes = "PS Vita. Compatibility is still patchy — check before committing a " +
                "library to it.",
        ),
        Emulator(
            name = "ES-DE",
            systems = emptyList(),
            packageIds = listOf("org.es_de.frontend", "org.es_de.frontend.gplay"),
            idConfidence = IdConfidence.LIKELY,
            dualScreen = DualScreenSupport.NONE,
            notes = "The frontend this app's ROM sorter targets. Point it at the same " +
                "ROMs root you sort into.",
            recommended = true,
        ),
    )

    /** Emulators that can actually use the Thor's bottom screen. */
    val dualScreenCapable: List<Emulator>
        get() = emulators.filter {
            it.dualScreen == DualScreenSupport.NATIVE || it.dualScreen == DualScreenSupport.OPT_IN
        }

    /** All known application ids, for matching against installed packages. */
    val knownPackageIds: Set<String>
        get() = emulators.flatMap { it.packageIds }.toSet()

    fun forSystem(systemId: String): List<Emulator> =
        emulators.filter { systemId in it.systems }
            .sortedByDescending { it.recommended }

    fun byPackageId(packageId: String): Emulator? =
        emulators.firstOrNull { packageId in it.packageIds }
}

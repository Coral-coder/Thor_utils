package gg.thor.utils.core.roms

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RomSorterTest {

    private val tmp: File = Files.createTempDirectory("thor-sorter").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun write(path: String, size: Int = 4096, build: ByteArray.() -> Unit = {}): File {
        val file = File(tmp, path)
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(size).apply(build))
        return file
    }

    private fun ByteArray.put(offset: Int, vararg values: Int) {
        values.forEachIndexed { i, v -> this[offset + i] = v.toByte() }
    }

    private fun ByteArray.putAscii(offset: Int, text: String) {
        text.forEachIndexed { i, c -> this[offset + i] = c.code.toByte() }
    }

    // ------------------------------------------------------------ catalog

    @Test
    fun `no two systems claim the same unique extension`() {
        // UNIQUE_EXTENSIONS drops contested extensions by construction; this
        // asserts the catalog has not quietly lost an extension to a collision.
        assertEquals(SystemCatalog.UNIQUE_EXTENSIONS["nes"]?.id, "nes")
        assertEquals(SystemCatalog.UNIQUE_EXTENSIONS["sfc"]?.id, "snes")
        assertEquals(SystemCatalog.UNIQUE_EXTENSIONS["gba"]?.id, "gba")
        assertNull(SystemCatalog.UNIQUE_EXTENSIONS["iso"], "iso is ambiguous and must not be claimed")
        assertNull(SystemCatalog.UNIQUE_EXTENSIONS["bin"], "bin is ambiguous and must not be claimed")
    }

    @Test
    fun `no ambiguous extension is ever claimed by a single system`() {
        // The invariant that keeps non-games out of system folders. It used to be
        // documentation only, which is how `.app` ended up filed as 3DS content.
        SystemCatalog.AMBIGUOUS.forEach { extension ->
            assertNull(
                SystemCatalog.UNIQUE_EXTENSIONS[extension],
                ".$extension is listed as ambiguous but is still claimed outright",
            )
        }
    }

    @Test
    fun `everyday computer files are not filed as games`() {
        // A downloads folder is the default scan source, so these are exactly the
        // files the sorter will meet most often.
        listOf("app", "exe", "com", "bat", "msi", "apk").forEach { extension ->
            assertNull(
                SystemCatalog.UNIQUE_EXTENSIONS[extension],
                ".$extension must not resolve to a system on its name alone",
            )
        }
    }

    @Test
    fun `a stray app file goes unsorted rather than to the 3DS folder`() {
        val file = write("backup/Some Installer.app")
        val id = RomIdentifier(tmp).identify(file)
        assertNull(id.systemId, "expected no system, got ${id.systemId} via ${id.primaryReason}")
    }

    @Test
    fun `a genuine 3DS app file is still recognised by its header`() {
        // Dropping the extension claim must not cost us real 3DS content: the
        // NCCH magic identifies it with certainty regardless of what it is called.
        val file = write("backup/Real Title.app") { putAscii(0x100, "NCCH") }
        val id = RomIdentifier(tmp).identify(file)
        assertEquals("n3ds", id.systemId)
        assertEquals(Confidence.CERTAIN, id.confidence)
    }

    @Test
    fun `resolves messy folder names to systems`() {
        assertEquals("genesis", SystemCatalog.matchName("Sega Genesis")?.id)
        assertEquals("genesis", SystemCatalog.matchName("megadrive")?.id)
        assertEquals("psx", SystemCatalog.matchName("PS1")?.id)
        assertEquals("n3ds", SystemCatalog.matchName("Nintendo 3DS")?.id)
        assertEquals("nds", SystemCatalog.matchName("  nintendo-ds ")?.id)
        assertNull(SystemCatalog.matchName("Screenshots"))
    }

    @Test
    fun `dual screen systems are flagged`() {
        val ids = SystemCatalog.dualScreenSystems.map { it.id }.toSet()
        assertContains(ids, "nds")
        assertContains(ids, "n3ds")
        assertFalse("snes" in ids)
    }

    // -------------------------------------------------------- identification

    @Test
    fun `content beats a wrong extension`() {
        // A GBA ROM mislabelled as a Mega Drive dump. Extension says genesis,
        // bytes say gba, and the bytes must win.
        val file = write("dump/Metroid Fusion.md") {
            put(0x04, 0x24, 0xFF, 0xAE, 0x51, 0x69, 0x9A, 0xA2, 0x21)
            put(0xB2, 0x96)
        }
        val id = RomIdentifier(tmp).identify(file)
        assertEquals("gba", id.systemId)
        assertEquals(Confidence.CERTAIN, id.confidence)
    }

    @Test
    fun `identifies a rom inside a zip`() {
        val inner = ByteArray(8192).apply { put(0, 0x4E, 0x45, 0x53, 0x1A) }
        val zip = File(tmp, "roms/Some Game.zip")
        zip.parentFile.mkdirs()
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("readme.txt"))
            out.write(ByteArray(1024))
            out.closeEntry()
            out.putNextEntry(ZipEntry("Some Game.nes"))
            out.write(inner)
            out.closeEntry()
        }
        val id = RomIdentifier(tmp).identify(zip)
        assertEquals("nes", id.systemId)
        assertEquals("Some Game.nes", id.innerRom)
        assertNotNull(id.archive)
    }

    @Test
    fun `buffers a bounded amount per archive regardless of its size`() {
        // Regression: identification used to buffer 5 MiB per entry with no cap on
        // entry count, and retained it for every game in the plan. On a real
        // library that exhausted Android's 256 MiB heap before the scan finished.
        val zip = File(tmp, "roms/Big Set.zip")
        zip.parentFile.mkdirs()
        val payload = ByteArray(400_000).apply { put(0, 0x4E, 0x45, 0x53, 0x1A) }
        ZipOutputStream(zip.outputStream()).use { out ->
            repeat(80) { index ->
                out.putNextEntry(ZipEntry("Game $index.nes"))
                out.write(payload)
                out.closeEntry()
            }
        }

        val contents = ArchiveInspector.inspect(zip)!!
        assertTrue(contents.isReadable)

        val buffered = contents.entries.sumOf { it.header.size.toLong() }
        val ceiling = ArchiveInspector.HEADER_BYTES.toLong() * 32
        assertTrue(
            buffered <= ceiling,
            "buffered $buffered bytes from an 80-entry archive, ceiling is $ceiling",
        )

        // And once identified, nothing is held on to at all.
        val id = RomIdentifier(tmp).identify(zip)
        assertEquals("nes", id.systemId)
        assertEquals(
            0L,
            id.archive!!.entries.sumOf { it.header.size.toLong() },
            "headers must be released after identification",
        )
        assertTrue(id.archive!!.entries.isNotEmpty(), "entry names should survive")
    }

    @Test
    fun `falls back to the folder name for a CHD`() {
        // CHD headers do not name the console, so the folder has to arbitrate.
        val file = write("Backups/Sega Saturn/Panzer Dragoon.chd") { putAscii(0, "MComprHD") }
        val id = RomIdentifier(tmp).identify(file)
        assertEquals("saturn", id.systemId)
        assertTrue(id.primaryReason.contains("disc image"), "reason should flag the disc fallback")
    }

    @Test
    fun `does not take a folder hint from above the scan root`() {
        val root = File(tmp, "SNES").apply { mkdirs() }
        val file = write("SNES/mystery.chd") { putAscii(0, "MComprHD") }
        // Scanning from inside SNES/, the folder itself must not be used as a hint.
        assertNull(RomIdentifier(root).identify(file).systemId)
    }

    @Test
    fun `reads compound extensions`() {
        assertEquals("nkit.iso", RomIdentifier.extensionOf("Game.nkit.iso"))
        assertEquals("iso", RomIdentifier.extensionOf("Game.iso"))
        assertEquals("tar.gz", RomIdentifier.extensionOf("set.tar.gz"))
        assertEquals("", RomIdentifier.extensionOf("noextension"))
    }

    // -------------------------------------------------------------- grouping

    @Test
    fun `keeps a cue sheet with the bin tracks it references`() {
        val cue = File(tmp, "disc/Game.cue")
        cue.parentFile.mkdirs()
        cue.writeText(
            """
            FILE "Game (Track 1).bin" BINARY
              TRACK 01 MODE2/2352
            FILE "Game (Track 2).bin" BINARY
              TRACK 02 AUDIO
            """.trimIndent(),
        )
        val track1 = write("disc/Game (Track 1).bin")
        val track2 = write("disc/Game (Track 2).bin")

        val groups = RomSorter().groupSidecars(listOf(track1, cue, track2))
        assertEquals(1, groups.size, "cue and both tracks must form a single group")
        assertEquals(3, groups.single().size)
        assertContains(groups.single(), cue)
    }

    @Test
    fun `keeps a multi-disc playlist with every disc it references`() {
        // The nesting is what makes this tricky: the .m3u names the .cue sheets,
        // and each .cue names its own tracks. All of it has to move as one unit or
        // the playlist the emulator actually loads points at missing files.
        val m3u = File(tmp, "ff7/Final Fantasy VII.m3u")
        m3u.parentFile.mkdirs()
        m3u.writeText("Final Fantasy VII (Disc 1).cue\nFinal Fantasy VII (Disc 2).cue\n")

        val discs = (1..2).map { disc ->
            val cue = File(tmp, "ff7/Final Fantasy VII (Disc $disc).cue")
            cue.writeText("FILE \"Final Fantasy VII (Disc $disc).bin\" BINARY\n  TRACK 01 MODE2/2352\n")
            val bin = write("ff7/Final Fantasy VII (Disc $disc).bin")
            listOf(cue, bin)
        }.flatten()

        val groups = RomSorter().groupSidecars(discs + m3u)

        assertEquals(1, groups.size, "the playlist and both discs must stay together")
        val group = groups.single()
        assertEquals(5, group.size, "expected m3u + 2 cues + 2 bins, got ${group.map { it.name }}")
        assertEquals(m3u, group.first(), "the playlist should lead the group")
    }

    @Test
    fun `recognises multi-disc titles`() {
        assertEquals(
            "Final Fantasy VII (USA)",
            RomSorter.multiDiscTitle("Final Fantasy VII (USA) (Disc 2).chd"),
        )
        assertEquals("Metal Gear Solid", RomSorter.multiDiscTitle("Metal Gear Solid [CD 1].bin"))
        assertNull(RomSorter.multiDiscTitle("Chrono Trigger (USA).sfc"))
    }

    // ------------------------------------------------------------ planning

    @Test
    fun `plans a mixed library into ES-DE folders`() {
        write("backup/Chrono Trigger.sfc", size = 0x20000) {
            putAscii(0xFFC0, "CHRONO TRIGGER       ")
            put(0xFFDC, 0x34, 0x12, 0xCB, 0xED)
        }
        write("backup/Super Mario Bros.nes") { put(0, 0x4E, 0x45, 0x53, 0x1A) }
        write("backup/Mario Kart DS.nds") { put(0xC0, 0x24, 0xFF, 0xAE, 0x51, 0x69, 0x9A, 0xA2, 0x21) }
        write("backup/notes.txt")

        val roms = File(tmp, "ROMs")
        val plan = RomSorter().plan(File(tmp, "backup"), roms)

        val destinations = plan.items.associate { it.primary.name to it.destination }
        assertEquals("snes", destinations["Chrono Trigger.sfc"])
        assertEquals("nes", destinations["Super Mario Bros.nes"])
        assertEquals("nds", destinations["Mario Kart DS.nds"])
        assertTrue(plan.ignored.any { it.first.name == "notes.txt" }, "text files are not ROMs")
    }

    @Test
    fun `unidentifiable files go to the unsorted tray, not a guessed folder`() {
        write("backup/mystery.bin")
        val plan = RomSorter().plan(File(tmp, "backup"), File(tmp, "ROMs"))
        val item = plan.items.single()
        assertEquals(SortAction.UNSORTED, item.action)
        assertEquals("_unsorted", item.destination)
    }

    @Test
    fun `a dry run reports moves without touching the disk`() {
        val source = write("backup/Super Mario Bros.nes") { put(0, 0x4E, 0x45, 0x53, 0x1A) }
        val roms = File(tmp, "ROMs")
        val plan = RomSorter().plan(File(tmp, "backup"), roms)

        val report = SortExecutor.execute(plan, SorterOptions(dryRun = true))
        assertTrue(report.results.all { it.succeeded })
        assertTrue(source.exists(), "dry run must leave the source in place")
        assertFalse(File(roms, "nes").exists(), "dry run must not create folders")
    }

    @Test
    fun `executing a plan moves the file into its system folder`() {
        val source = write("backup/Super Mario Bros.nes") { put(0, 0x4E, 0x45, 0x53, 0x1A) }
        val roms = File(tmp, "ROMs")
        val plan = RomSorter().plan(File(tmp, "backup"), roms)

        val report = SortExecutor.execute(plan, SorterOptions(dryRun = false))

        assertTrue(report.failed.isEmpty(), "unexpected failures: ${report.failed.map { it.error }}")
        assertFalse(source.exists(), "source should have moved")
        assertTrue(File(roms, "nes/Super Mario Bros.nes").exists())
    }

    @Test
    fun `re-running a sort is idempotent`() {
        write("backup/Super Mario Bros.nes") { put(0, 0x4E, 0x45, 0x53, 0x1A) }
        val roms = File(tmp, "ROMs")
        SortExecutor.execute(
            RomSorter().plan(File(tmp, "backup"), roms),
            SorterOptions(dryRun = false),
        )
        // Sorting the destination back onto itself must recognise the file is home.
        val second = RomSorter().plan(File(roms, "nes"), roms)
        assertTrue(
            second.items.all { it.action == SortAction.ALREADY_SORTED },
            "expected no work on a second pass, got ${second.items.map { it.action }}",
        )
    }

    // ---------------------------------------------------------- unpacking

    /** Builds a zip whose entries are given as name -> first-four-magic-bytes. */
    private fun buildZip(path: String, entries: List<Pair<String, IntArray?>>): File {
        val zip = File(tmp, path)
        zip.parentFile.mkdirs()
        ZipOutputStream(zip.outputStream()).use { out ->
            entries.forEach { (name, magic) ->
                out.putNextEntry(ZipEntry(name))
                val payload = ByteArray(8192)
                magic?.let { payload.put(0, *it) }
                out.write(payload)
                out.closeEntry()
            }
        }
        return zip
    }

    private val nesMagic = intArrayOf(0x4E, 0x45, 0x53, 0x1A)
    private val n64Magic = intArrayOf(0x80, 0x37, 0x12, 0x40)

    @Test
    fun `unpacking files each game into its own system folder`() {
        // A mixed backup archive: NES and N64 games plus packaging junk.
        buildZip(
            "backup/Mixed Backup.zip",
            listOf(
                "roms/nes/Contra.nes" to nesMagic,
                "roms/n64/Mario 64.z64" to n64Magic,
                "readme.txt" to null,
                "cover.jpg" to null,
            ),
        )

        val roms = File(tmp, "ROMs")
        val options = SorterOptions(dryRun = false, extractArchives = true)
        val plan = RomSorter(options).plan(File(tmp, "backup"), roms)
        val report = SortExecutor.execute(plan, options)

        assertTrue(report.failed.isEmpty(), "unexpected failures: ${report.failed.map { it.error }}")

        // Each game lands in the folder its own bytes call for, not the one the
        // archive as a whole happened to match.
        assertTrue(File(roms, "nes/Contra.nes").exists(), "NES game should be filed under nes/")
        assertTrue(File(roms, "n64/Mario 64.z64").exists(), "N64 game should be filed under n64/")

        // Packaging is not extracted at all.
        assertFalse(File(roms, "nes/readme.txt").exists())
        assertFalse(File(roms, "nes/cover.jpg").exists())
        assertFalse(File(roms, "_unsorted/readme.txt").exists())
    }

    @Test
    fun `unpacking flattens the archive's own folder structure`() {
        buildZip("backup/Nested.zip", listOf("some/deep/path/Contra.nes" to nesMagic))

        val roms = File(tmp, "ROMs")
        val options = SorterOptions(dryRun = false, extractArchives = true)
        SortExecutor.execute(RomSorter(options).plan(File(tmp, "backup"), roms), options)

        assertTrue(File(roms, "nes/Contra.nes").exists(), "game should sit directly in the system folder")
        assertFalse(File(roms, "nes/some").exists(), "the archive's folders must not be recreated")
    }

    @Test
    fun `unpacking leaves the archive where it was`() {
        val archive = buildZip("backup/Keep Me.zip", listOf("Contra.nes" to nesMagic))

        val roms = File(tmp, "ROMs")
        val options = SorterOptions(dryRun = false, extractArchives = true)
        SortExecutor.execute(RomSorter(options).plan(File(tmp, "backup"), roms), options)

        assertTrue(archive.exists(), "the archive must not be consumed")
        assertFalse(File(roms, "nes/Keep Me.zip").exists(), "the archive must not be filed into the ROMs tree")
        assertTrue(File(roms, "nes/Contra.nes").exists())
    }

    @Test
    fun `unpacking the same archive twice does not duplicate games`() {
        buildZip("backup/Again.zip", listOf("Contra.nes" to nesMagic))

        val roms = File(tmp, "ROMs")
        val options = SorterOptions(dryRun = false, extractArchives = true)
        repeat(2) {
            SortExecutor.execute(RomSorter(options).plan(File(tmp, "backup"), roms), options)
        }

        val filed = File(roms, "nes").listFiles()?.map { it.name }.orEmpty()
        assertEquals(
            listOf("Contra.nes"),
            filed,
            "a second unpack must not produce a suffixed duplicate",
        )
    }

    @Test
    fun `deletes the archive after unpacking only when asked`() {
        val archive = buildZip("backup/Consume.zip", listOf("Contra.nes" to nesMagic))

        val roms = File(tmp, "ROMs")
        val options = SorterOptions(
            dryRun = false,
            extractArchives = true,
            deleteArchiveAfterExtract = true,
        )
        SortExecutor.execute(RomSorter(options).plan(File(tmp, "backup"), roms), options)

        assertTrue(File(roms, "nes/Contra.nes").exists())
        assertFalse(archive.exists(), "opted in, so the archive should be gone")
    }

    // ----------------------------------------------------------- safety

    @Test
    fun `a colliding name is suffixed rather than overwritten`() {
        val dir = File(tmp, "dest").apply { mkdirs() }
        File(dir, "Game.nes").writeBytes(ByteArray(16))
        val target = SortExecutor.uniqueTarget(dir, "Game.nes")
        assertEquals("Game (2).nes", target?.name)
    }

    @Test
    fun `archive entries cannot escape the destination directory`() {
        val dest = File(tmp, "dest").apply { mkdirs() }
        assertNull(SortExecutor.safeChild(dest, "../../etc/passwd"), "path traversal must be refused")
        assertNull(SortExecutor.safeChild(dest, "sub/../../../evil"), "traversal via a subpath must be refused")

        // An absolute entry name is contained rather than rejected: the leading
        // separator is stripped, which is what every well-behaved unpacker does.
        val absolute = SortExecutor.safeChild(dest, "/absolute/evil")
        assertNotNull(absolute)
        assertTrue(
            absolute.canonicalPath.startsWith(dest.canonicalPath + java.io.File.separator),
            "an absolute entry name must be confined to the destination, got $absolute",
        )
        assertNotNull(SortExecutor.safeChild(dest, "sub/Game.nes"))
    }
}

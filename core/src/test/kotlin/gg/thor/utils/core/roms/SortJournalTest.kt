package gg.thor.utils.core.roms

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The journal is what makes a sort reversible. These tests exist because an
 * unrecoverable sort has already happened once.
 */
class SortJournalTest {

    private val tmp: File = Files.createTempDirectory("thor-journal").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun write(path: String, magic: IntArray? = null): File {
        val file = File(tmp, path)
        file.parentFile.mkdirs()
        val bytes = ByteArray(4096)
        magic?.forEachIndexed { i, v -> bytes[i] = v.toByte() }
        file.writeBytes(bytes)
        return file
    }

    private val nes = intArrayOf(0x4E, 0x45, 0x53, 0x1A)

    @Test
    fun `a sort can be put back exactly as it was`() {
        val source = write("backup/Contra.nes", nes)
        val originalPath = source.absolutePath
        val roms = File(tmp, "ROMs")
        val journal = SortJournal(File(tmp, "journal.log"))
        val options = SorterOptions(dryRun = false)

        SortExecutor.execute(RomSorter(options).plan(File(tmp, "backup"), roms), options, journal)
        assertTrue(File(roms, "nes/Contra.nes").exists())
        assertFalse(File(originalPath).exists())

        val undo = journal.undo()

        assertEquals(1, undo.restored)
        assertTrue(undo.isClean, "undo reported problems: ${undo.failed}")
        assertTrue(File(originalPath).exists(), "the file should be back where it started")
        assertFalse(File(roms, "nes/Contra.nes").exists(), "it should no longer be in the ROMs tree")
    }

    @Test
    fun `undo restores the original folder layout`() {
        write("backup/consoles/nintendo/Contra.nes", nes)
        write("backup/other/Mario.nes", nes)
        val roms = File(tmp, "ROMs")
        val journal = SortJournal(File(tmp, "journal.log"))
        val options = SorterOptions(dryRun = false)

        SortExecutor.execute(RomSorter(options).plan(File(tmp, "backup"), roms), options, journal)
        journal.undo()

        assertTrue(File(tmp, "backup/consoles/nintendo/Contra.nes").exists())
        assertTrue(File(tmp, "backup/other/Mario.nes").exists())
    }

    @Test
    fun `a partial run is still fully reversible`() {
        // Entries are written as each file lands, not batched at the end, so a
        // run that dies halfway leaves a usable record of what it had done.
        val journal = SortJournal(File(tmp, "journal.log"))
        val moved = write("ROMs/nes/Contra.nes", nes)
        journal.record(File(tmp, "backup/Contra.nes"), moved)

        assertEquals(1, journal.entries().size)
        val undo = journal.undo()
        assertEquals(1, undo.restored)
        assertTrue(File(tmp, "backup/Contra.nes").exists())
    }

    @Test
    fun `undo never overwrites a file already back at the source`() {
        val journal = SortJournal(File(tmp, "journal.log"))
        val moved = write("ROMs/nes/Contra.nes", nes)
        val occupied = write("backup/Contra.nes", nes)
        occupied.writeText("a different file the user put here")
        journal.record(occupied, moved)

        val undo = journal.undo()

        assertEquals(0, undo.restored)
        assertEquals(1, undo.failed.size)
        assertEquals(
            "a different file the user put here",
            occupied.readText(),
            "the file at the source must not be clobbered",
        )
    }

    @Test
    fun `paths with tabs and newlines survive a round trip`() {
        val journal = SortJournal(File(tmp, "journal.log"))
        val odd = write("backup/we ird\tname.nes", nes)
        val target = write("ROMs/nes/we ird\tname.nes", nes)
        journal.record(odd, target)

        assertEquals(odd.absolutePath, journal.entries().single().from)
    }
}

package gg.thor.utils.core.roms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Signature tests build synthetic ROM headers rather than shipping real dumps.
 * Every offset and magic value here is the one the hardware actually uses, so a
 * regression in [Signatures] shows up as a failure rather than a misfiled game.
 */
class SignaturesTest {

    private fun rom(size: Int = 64 * 1024, build: ByteArray.() -> Unit): ByteSource =
        ByteArraySource(ByteArray(size).apply(build))

    private fun ByteArray.put(offset: Int, vararg values: Int) {
        values.forEachIndexed { i, v -> this[offset + i] = v.toByte() }
    }

    private fun ByteArray.putAscii(offset: Int, text: String) {
        text.forEachIndexed { i, c -> this[offset + i] = c.code.toByte() }
    }

    @Test
    fun `detects iNES header`() {
        val match = Signatures.identify(rom { put(0, 0x4E, 0x45, 0x53, 0x1A) })
        assertEquals("nes", match?.systemId)
        assertEquals(Confidence.CERTAIN, match?.confidence)
    }

    @Test
    fun `does not mistake plain NES text for an iNES header`() {
        // Without the trailing 0x1A this is just a file that starts with "NES".
        val match = Signatures.identify(rom { putAscii(0, "NESTED DATA") })
        assertNull(match)
    }

    @Test
    fun `detects all three Nintendo 64 byte orders`() {
        assertEquals("n64", Signatures.identify(rom { put(0, 0x80, 0x37, 0x12, 0x40) })?.systemId)
        assertEquals("n64", Signatures.identify(rom { put(0, 0x37, 0x80, 0x40, 0x12) })?.systemId)
        assertEquals("n64", Signatures.identify(rom { put(0, 0x40, 0x12, 0x37, 0x80) })?.systemId)
    }

    @Test
    fun `separates Game Boy from Game Boy Color by the CGB flag`() {
        val logo = intArrayOf(0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73)
        val plain = rom { put(0x104, *logo) }
        val color = rom { put(0x104, *logo); put(0x143, 0xC0) }
        assertEquals("gb", Signatures.identify(plain)?.systemId)
        assertEquals("gbc", Signatures.identify(color)?.systemId)
    }

    @Test
    fun `separates GBA from NDS by boot logo offset`() {
        val logo = intArrayOf(0x24, 0xFF, 0xAE, 0x51, 0x69, 0x9A, 0xA2, 0x21)
        val gba = rom { put(0x04, *logo); put(0xB2, 0x96) }
        val nds = rom { put(0xC0, *logo) }
        assertEquals("gba", Signatures.identify(gba)?.systemId)
        assertEquals("nds", Signatures.identify(nds)?.systemId)
    }

    @Test
    fun `detects Mega Drive family from the console name field`() {
        assertEquals("genesis", Signatures.identify(rom { putAscii(0x100, "SEGA MEGA DRIVE ") })?.systemId)
        assertEquals("sega32x", Signatures.identify(rom { putAscii(0x100, "SEGA 32X       ") })?.systemId)
        assertEquals("genesis", Signatures.identify(rom { putAscii(0x100, "SEGA GENESIS   ") })?.systemId)
    }

    @Test
    fun `detects GameCube and Wii disc magics`() {
        assertEquals("gc", Signatures.identify(rom { put(0x1C, 0xC2, 0x33, 0x9F, 0x3D) })?.systemId)
        assertEquals("wii", Signatures.identify(rom { put(0x18, 0x5D, 0x1C, 0x9E, 0xA3) })?.systemId)
    }

    @Test
    fun `reads the console out of an ISO 9660 volume descriptor`() {
        val psp = rom(size = 0x9000) { putAscii(0x8001, "CD001"); putAscii(0x8008, "PSP GAME") }
        assertEquals("psp", Signatures.identify(psp)?.systemId)

        val ps1 = rom(size = 0x9000) { putAscii(0x8001, "CD001"); putAscii(0x8008, "PLAYSTATION") }
        assertEquals("psx", Signatures.identify(ps1)?.systemId)
    }

    @Test
    fun `treats a DVD-sized PlayStation image as PS2`() {
        // Same descriptor as a PS1 disc; only the size distinguishes them.
        val header = ByteArray(0x9000)
        header.putAscii(0x8001, "CD001")
        header.putAscii(0x8008, "PLAYSTATION")
        val dvd = object : ByteSource {
            override val size: Long = 4L * 1024 * 1024 * 1024
            override fun readAt(offset: Long, length: Int) =
                ByteArraySource(header).readAt(offset, length)
        }
        assertEquals("ps2", Signatures.identify(dvd)?.systemId)
    }

    @Test
    fun `reports a CHD as an unidentified disc rather than guessing`() {
        val match = Signatures.identify(rom { putAscii(0, "MComprHD") })
        assertEquals(Signatures.UNKNOWN_DISC, match?.systemId)
        assertEquals(Confidence.WEAK, match?.confidence)
    }

    @Test
    fun `validates the SNES header checksum pair`() {
        val valid = rom(size = 0x20000) {
            putAscii(0xFFC0, "SUPER GAME TITLE     ")
            // complement 0x1234 and checksum 0xEDCB xor to 0xFFFF.
            put(0xFFDC, 0x34, 0x12, 0xCB, 0xED)
        }
        val match = Signatures.identify(valid)
        assertEquals("snes", match?.systemId)

        val broken = rom(size = 0x20000) {
            putAscii(0xFFC0, "SUPER GAME TITLE     ")
            put(0xFFDC, 0x34, 0x12, 0x00, 0x11)
        }
        assertNull(Signatures.identify(broken))
    }

    @Test
    fun `recognises archive containers`() {
        assertEquals(ArchiveKind.ZIP, Signatures.archiveKind(rom { put(0, 0x50, 0x4B, 0x03, 0x04) }))
        assertEquals(ArchiveKind.SEVEN_Z, Signatures.archiveKind(rom { put(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) }))
        assertEquals(ArchiveKind.RAR, Signatures.archiveKind(rom { putAscii(0, "Rar!") }))
        assertNull(Signatures.archiveKind(rom { putAscii(0, "not an archive") }))
    }

    @Test
    fun `short files never throw`() {
        val tiny = ByteArraySource(byteArrayOf(0x4E, 0x45))
        assertNull(Signatures.identify(tiny))
        assertNotNull(tiny.readAt(0, 1024))
    }
}

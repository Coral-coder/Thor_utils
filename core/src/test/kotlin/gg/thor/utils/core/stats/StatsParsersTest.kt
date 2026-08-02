package gg.thor.utils.core.stats

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class StatsParsersTest {

    private val procStat = """
        cpu  1000 200 500 8000 100 50 25 0 0 0
        cpu0 500 100 250 4000 50 25 10 0 0 0
        cpu1 500 100 250 4000 50 25 15 0 0 0
        intr 12345
    """.trimIndent()

    @Test
    fun `parses the aggregate cpu line`() {
        val times = StatsParsers.parseCpuTimes(procStat)
        assertNotNull(times)
        assertEquals(1000, times.user)
        assertEquals(8000, times.idle)
        assertEquals(9875, times.total)
    }

    @Test
    fun `ignores guest columns so utilisation is not double counted`() {
        // Fields 9 and 10 are guest/guest_nice, already included in user/nice.
        val withGuest = "cpu  1000 200 500 8000 100 50 25 0 900 100"
        val times = StatsParsers.parseCpuTimes(withGuest)!!
        assertEquals(9875, times.total, "guest columns must not inflate the total")
    }

    @Test
    fun `computes utilisation between two snapshots`() {
        val first = StatsParsers.parseCpuTimes(procStat)!!
        // 100 ticks busy, 100 idle -> 50 %.
        val second = first.copy(user = first.user + 100, idle = first.idle + 100)
        val utilisation = second.utilisationSince(first)
        assertNotNull(utilisation)
        assertTrue(abs(utilisation - 0.5f) < 0.001f, "got $utilisation")
    }

    @Test
    fun `treats iowait as idle`() {
        val first = StatsParsers.parseCpuTimes(procStat)!!
        val second = first.copy(ioWait = first.ioWait + 200)
        assertEquals(0f, second.utilisationSince(first), "waiting on IO is not CPU work")
    }

    @Test
    fun `returns null when counters have not advanced`() {
        val times = StatsParsers.parseCpuTimes(procStat)!!
        assertNull(times.utilisationSince(times))
    }

    @Test
    fun `parses per core lines`() {
        assertEquals(2, StatsParsers.parsePerCoreTimes(procStat).size)
    }

    @Test
    fun `parses meminfo into bytes`() {
        val meminfo = """
            MemTotal:       16000000 kB
            MemFree:         1000000 kB
            MemAvailable:    8000000 kB
            Cached:          4000000 kB
            SwapTotal:       2000000 kB
            SwapFree:        1500000 kB
        """.trimIndent()
        val info = StatsParsers.parseMemInfo(meminfo)!!
        assertEquals(16_000_000L * 1024, info.totalBytes)
        assertEquals(8_000_000L * 1024, info.availableBytes)
        assertEquals(8_000_000L * 1024, info.usedBytes)
        assertTrue(abs(info.usedFraction - 0.5f) < 0.001f)
        assertEquals(500_000L * 1024, info.swapUsedBytes)
    }

    @Test
    fun `falls back when MemAvailable is missing`() {
        val meminfo = """
            MemTotal:       8000000 kB
            MemFree:        1000000 kB
            Cached:         2000000 kB
        """.trimIndent()
        val info = StatsParsers.parseMemInfo(meminfo)!!
        assertEquals(3_000_000L * 1024, info.availableBytes)
    }

    @Test
    fun `handles both temperature scales`() {
        assertEquals(45.5f, StatsParsers.parseTemperature("45500"))
        assertEquals(45f, StatsParsers.parseTemperature("45"))
        assertNull(StatsParsers.parseTemperature("garbage"))
        assertNull(StatsParsers.parseTemperature("999999"), "implausible readings are rejected")
    }

    @Test
    fun `converts cpufreq kHz to MHz`() {
        assertEquals(2400, StatsParsers.parseFrequencyKhz("2400000"))
        assertNull(StatsParsers.parseFrequencyKhz("0"))
    }

    // ------------------------------------------------------------ formatting

    @Test
    fun `formats sizes and runtimes for a status bar`() {
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.0 KB", Format.bytes(1024))
        assertEquals("1.5 GB", Format.bytes((1.5 * 1024 * 1024 * 1024).toLong()))

        assertEquals("4h 12m", Format.runtime(252.minutes))
        assertEquals("47m", Format.runtime(47.minutes))
        assertEquals("—", Format.runtime(null))
        assertEquals("<1m", Format.runtime(30.minutes / 60))
    }

    @Test
    fun `drain rate carries an explicit sign`() {
        assertEquals("-11.4%/h", Format.drainRate(11.4f))
        assertEquals("+22.0%/h", Format.drainRate(-22f))
        assertEquals("0%/h", Format.drainRate(0.001f))
    }

    @Test
    fun `formatting never uses a locale decimal comma`() {
        // Status-bar text mixes numbers with ASCII units; a locale-dependent
        // separator would render inconsistently across the device's locales.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1.5 GB", Format.bytes((1.5 * 1024 * 1024 * 1024).toLong()))
            assertEquals("42.5°C", Format.celsius(42.5f))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}

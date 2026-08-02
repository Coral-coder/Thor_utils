package gg.thor.utils.core.stats

/** Aggregate CPU time counters from one line of `/proc/stat`. */
data class CpuTimes(
    val user: Long,
    val nice: Long,
    val system: Long,
    val idle: Long,
    val ioWait: Long,
    val irq: Long,
    val softIrq: Long,
    val steal: Long,
) {
    val total: Long get() = user + nice + system + idle + ioWait + irq + softIrq + steal

    /** Time not spent idle. `iowait` counts as idle: the CPU was available. */
    val busy: Long get() = total - idle - ioWait

    /**
     * CPU utilisation between this snapshot and an earlier one, as 0..1.
     *
     * Returns null when the counters did not advance, which happens on a
     * back-to-back read and after the counters wrap on a core going offline.
     */
    fun utilisationSince(previous: CpuTimes): Float? {
        val totalDelta = total - previous.total
        val busyDelta = busy - previous.busy
        if (totalDelta <= 0 || busyDelta < 0) return null
        return (busyDelta.toFloat() / totalDelta).coerceIn(0f, 1f)
    }
}

/** Memory figures from `/proc/meminfo`, in bytes. */
data class MemoryInfo(
    val totalBytes: Long,
    val availableBytes: Long,
    val freeBytes: Long,
    val cachedBytes: Long,
    val swapTotalBytes: Long,
    val swapFreeBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0)
    val usedFraction: Float get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
    val swapUsedBytes: Long get() = (swapTotalBytes - swapFreeBytes).coerceAtLeast(0)
}

/**
 * Parsers for the kernel's text interfaces.
 *
 * These are kept free of Android types so the awkward parts — counter deltas,
 * unit conversions, the units `/proc/meminfo` actually uses — can be tested
 * directly rather than only on a device.
 */
object StatsParsers {

    /**
     * Parses the aggregate `cpu` line of `/proc/stat`.
     *
     * Fields after `steal` (guest time) are deliberately ignored: they are
     * already counted inside `user` and `nice`, so adding them would double-count
     * and depress the reported utilisation.
     */
    fun parseCpuTimes(procStat: String): CpuTimes? {
        val line = procStat.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val fields = line.trim().split(WHITESPACE).drop(1).mapNotNull { it.toLongOrNull() }
        if (fields.size < 4) return null
        return CpuTimes(
            user = fields[0],
            nice = fields[1],
            system = fields[2],
            idle = fields[3],
            ioWait = fields.getOrElse(4) { 0 },
            irq = fields.getOrElse(5) { 0 },
            softIrq = fields.getOrElse(6) { 0 },
            steal = fields.getOrElse(7) { 0 },
        )
    }

    /** Parses per-core `cpuN` lines, in core order. */
    fun parsePerCoreTimes(procStat: String): List<CpuTimes> =
        procStat.lineSequence()
            .filter { it.startsWith("cpu") && !it.startsWith("cpu ") }
            .mapNotNull { line ->
                val fields = line.trim().split(WHITESPACE).drop(1).mapNotNull { it.toLongOrNull() }
                if (fields.size < 4) null else CpuTimes(
                    fields[0], fields[1], fields[2], fields[3],
                    fields.getOrElse(4) { 0 }, fields.getOrElse(5) { 0 },
                    fields.getOrElse(6) { 0 }, fields.getOrElse(7) { 0 },
                )
            }
            .toList()

    /**
     * Parses `/proc/meminfo`.
     *
     * Values there are in kibibytes despite the `kB` label, so every figure is
     * multiplied by 1024 to reach bytes.
     */
    fun parseMemInfo(procMemInfo: String): MemoryInfo? {
        val values = mutableMapOf<String, Long>()
        procMemInfo.lineSequence().forEach { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@forEach
            val key = line.substring(0, colon).trim()
            val amount = line.substring(colon + 1).trim().split(WHITESPACE).firstOrNull()?.toLongOrNull()
            if (amount != null) values[key] = amount * 1024
        }
        val total = values["MemTotal"] ?: return null
        val free = values["MemFree"] ?: 0
        val cached = values["Cached"] ?: 0
        // MemAvailable is the kernel's own estimate and far better than
        // free+cached; fall back only on kernels too old to publish it.
        val available = values["MemAvailable"] ?: (free + cached)
        return MemoryInfo(
            totalBytes = total,
            availableBytes = available,
            freeBytes = free,
            cachedBytes = cached,
            swapTotalBytes = values["SwapTotal"] ?: 0,
            swapFreeBytes = values["SwapFree"] ?: 0,
        )
    }

    /**
     * Converts a thermal zone reading to degrees Celsius.
     *
     * Zones report in millidegrees, but a few report whole degrees. Values that
     * would imply a temperature above 200 °C are assumed to be millidegrees.
     */
    fun parseTemperature(raw: String): Float? {
        val value = raw.trim().toFloatOrNull() ?: return null
        val celsius = if (value > 200f) value / 1000f else value
        return if (celsius in -40f..150f) celsius else null
    }

    /** Parses a `scaling_cur_freq` style file (kHz) into MHz. */
    fun parseFrequencyKhz(raw: String): Int? =
        raw.trim().toLongOrNull()?.takeIf { it > 0 }?.let { (it / 1000).toInt() }

    private val WHITESPACE = Regex("\\s+")
}

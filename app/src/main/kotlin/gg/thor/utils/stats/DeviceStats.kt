package gg.thor.utils.stats

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import gg.thor.utils.core.stats.CpuTimes
import gg.thor.utils.core.stats.MemoryInfo
import gg.thor.utils.core.stats.StatsParsers
import java.io.File

/** One snapshot of everything the HUD shows. */
data class DeviceSnapshot(
    val cpuUtilisation: Float?,
    val perCoreUtilisation: List<Float>,
    val cpuMaxFrequencyMhz: Int?,
    val gpuUtilisation: Float?,
    val gpuFrequencyMhz: Int?,
    val memory: MemoryInfo?,
    val cpuTemperatureCelsius: Float?,
    val batteryTemperatureCelsius: Float?,
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
) {
    val storageUsedFraction: Float
        get() = if (storageTotalBytes > 0) {
            1f - storageFreeBytes.toFloat() / storageTotalBytes
        } else {
            0f
        }
}

/**
 * Reads hardware statistics from the kernel's sysfs and procfs interfaces.
 *
 * Everything here is best-effort by design. Vendors move, rename and lock down
 * these files freely, and an unreadable GPU counter must never take the whole
 * HUD down — each reader returns null and the UI simply omits that row.
 */
class DeviceStatsReader(private val context: Context) {

    private var previousCpuTimes: CpuTimes? = null
    private var previousPerCore: List<CpuTimes> = emptyList()

    fun snapshot(): DeviceSnapshot {
        val procStat = readFileOrNull("/proc/stat")
        val cpuTimes = procStat?.let { StatsParsers.parseCpuTimes(it) }
        val perCore = procStat?.let { StatsParsers.parsePerCoreTimes(it) } ?: emptyList()

        val utilisation = cpuTimes?.let { now ->
            previousCpuTimes?.let { now.utilisationSince(it) }
        }
        val perCoreUtilisation = perCore.mapIndexedNotNull { index, now ->
            previousPerCore.getOrNull(index)?.let { now.utilisationSince(it) }
        }

        previousCpuTimes = cpuTimes
        if (perCore.isNotEmpty()) previousPerCore = perCore

        val storage = storageStats()

        return DeviceSnapshot(
            cpuUtilisation = utilisation,
            perCoreUtilisation = perCoreUtilisation,
            cpuMaxFrequencyMhz = maxCpuFrequencyMhz(),
            gpuUtilisation = gpuUtilisation(),
            gpuFrequencyMhz = gpuFrequencyMhz(),
            memory = readMemory(),
            cpuTemperatureCelsius = cpuTemperature(),
            batteryTemperatureCelsius = null,
            storageFreeBytes = storage.first,
            storageTotalBytes = storage.second,
        )
    }

    /**
     * Prefers `/proc/meminfo`, which reports the whole machine.
     *
     * [ActivityManager.MemoryInfo] is the fallback: it is always readable but
     * reports what the framework is willing to tell an app, which understates
     * usage on a device running an emulator in the background.
     */
    private fun readMemory(): MemoryInfo? {
        readFileOrNull("/proc/meminfo")?.let { text ->
            StatsParsers.parseMemInfo(text)?.let { return it }
        }
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return MemoryInfo(
            totalBytes = info.totalMem,
            availableBytes = info.availMem,
            freeBytes = info.availMem,
            cachedBytes = 0,
            swapTotalBytes = 0,
            swapFreeBytes = 0,
        )
    }

    private fun maxCpuFrequencyMhz(): Int? =
        CPU_FREQ_GLOB.mapNotNull { path ->
            readFileOrNull(path)?.let { StatsParsers.parseFrequencyKhz(it) }
        }.maxOrNull()

    /**
     * Adreno reports busy time as `<busy> <total>` in a single file, which must be
     * read as a ratio; `gpu_load` on other kernels is already a percentage.
     */
    private fun gpuUtilisation(): Float? {
        readFileOrNull("/sys/class/kgsl/kgsl-3d0/gpubusy")?.let { raw ->
            val parts = raw.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            if (parts.size >= 2 && parts[1] > 0) {
                return (parts[0].toFloat() / parts[1]).coerceIn(0f, 1f)
            }
        }
        GPU_LOAD_PATHS.forEach { path ->
            readFileOrNull(path)?.trim()?.toFloatOrNull()?.let {
                return (it / 100f).coerceIn(0f, 1f)
            }
        }
        return null
    }

    private fun gpuFrequencyMhz(): Int? =
        GPU_FREQ_PATHS.firstNotNullOfOrNull { path ->
            readFileOrNull(path)?.trim()?.toLongOrNull()?.let { hzOrKhz ->
                // Some kernels report Hz here and others kHz; anything above a
                // gigahertz-scale number must be Hz.
                if (hzOrKhz > 10_000_000) (hzOrKhz / 1_000_000).toInt() else (hzOrKhz / 1000).toInt()
            }?.takeIf { it > 0 }
        }

    /**
     * Finds a CPU thermal zone by name and reads it.
     *
     * Zone numbering is not stable across devices, so the zone `type` is matched
     * rather than an index guessed.
     */
    private fun cpuTemperature(): Float? {
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?: return null
        var fallback: Float? = null
        zones.sortedBy { it.name }.forEach { zone ->
            val type = readFileOrNull("${zone.absolutePath}/type")?.trim()?.lowercase() ?: return@forEach
            val temperature = readFileOrNull("${zone.absolutePath}/temp")
                ?.let { StatsParsers.parseTemperature(it) } ?: return@forEach
            if (CPU_ZONE_HINTS.any { type.contains(it) }) return temperature
            if (fallback == null) fallback = temperature
        }
        return fallback
    }

    private fun storageStats(): Pair<Long, Long> = try {
        val path = Environment.getExternalStorageDirectory()
        val stat = StatFs(path.absolutePath)
        stat.availableBytes to stat.totalBytes
    } catch (_: Exception) {
        0L to 0L
    }

    private fun readFileOrNull(path: String): String? = try {
        val file = File(path)
        if (file.canRead()) file.readText() else null
    } catch (_: Exception) {
        // SELinux denials surface as exceptions here on locked-down vendor
        // kernels; an unreadable counter is expected, not an error worth logging.
        null
    }

    companion object {
        val deviceModel: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"

        private val CPU_FREQ_GLOB = (0 until 12).map {
            "/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq"
        }

        private val GPU_LOAD_PATHS = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/class/devfreq/gpufreq/gpu_load",
            "/sys/kernel/gpu/gpu_busy",
        )

        private val GPU_FREQ_PATHS = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/kernel/gpu/gpu_clock",
        )

        private val CPU_ZONE_HINTS = listOf("cpu", "soc", "tsens", "apc", "silver", "gold")
    }
}

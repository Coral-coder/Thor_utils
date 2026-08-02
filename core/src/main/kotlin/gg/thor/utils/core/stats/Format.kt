package gg.thor.utils.core.stats

import kotlin.math.abs
import kotlin.time.Duration

/** Display formatting shared by the status-bar chip, the overlay and the app UI. */
object Format {

    /**
     * Compact byte size, e.g. `1.4 GB`.
     *
     * Uses binary units (1024) but the familiar short labels, matching what
     * Android itself shows for storage.
     */
    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var size = value.toDouble() / 1024
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) {
            size /= 1024
            unit++
        }
        return if (size >= 100) "${size.toInt()} ${units[unit]}"
        else "${round(size, 1)} ${units[unit]}"
    }

    /**
     * Runtime for the status bar: `4h 12m`, or `47m` under an hour.
     *
     * Deliberately never shows seconds — a battery projection that ticks every
     * second reads as precision the estimate does not have.
     */
    fun runtime(duration: Duration?): String {
        if (duration == null) return "—"
        val totalMinutes = duration.inWholeMinutes
        if (totalMinutes < 1) return "<1m"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    /** Drain rate with an explicit sign, e.g. `-11.4%/h`. */
    fun drainRate(percentPerHour: Float): String {
        if (abs(percentPerHour) < 0.05f) return "0%/h"
        val sign = if (percentPerHour > 0) "-" else "+"
        return "$sign${round(abs(percentPerHour).toDouble(), 1)}%/h"
    }

    /** Percentage from a 0..1 fraction. */
    fun percent(fraction: Float, decimals: Int = 0): String =
        if (decimals == 0) "${(fraction * 100).toInt()}%"
        else "${round((fraction * 100).toDouble(), decimals)}%"

    fun celsius(value: Float?): String =
        if (value == null) "—" else "${round(value.toDouble(), 1)}°C"

    fun watts(value: Float?): String =
        if (value == null) "—" else "${round(value.toDouble(), 2)} W"

    fun megahertz(value: Int?): String =
        if (value == null) "—" else if (value >= 1000) "${round(value / 1000.0, 1)} GHz" else "$value MHz"

    /**
     * Rounds to a fixed number of decimals without pulling in a locale-sensitive
     * formatter — these strings go in a status bar, where a comma decimal
     * separator on some locales would look broken next to the ASCII units.
     */
    private fun round(value: Double, decimals: Int): String {
        var factor = 1L
        repeat(decimals) { factor *= 10 }
        val scaled = Math.round(value * factor)
        val whole = scaled / factor
        val fraction = abs(scaled % factor)
        return if (decimals == 0) "$whole" else "$whole.${fraction.toString().padStart(decimals, '0')}"
    }
}

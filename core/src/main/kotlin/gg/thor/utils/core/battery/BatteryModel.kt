package gg.thor.utils.core.battery

import kotlin.math.abs
import kotlin.math.exp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** One reading of the battery's state. All fields beyond level are optional. */
data class BatterySample(
    val timestampMs: Long,
    /** Charge level, 0..100. */
    val levelPercent: Float,
    /** Remaining charge in µAh, when the gauge exposes it. */
    val chargeCounterUah: Long? = null,
    /** Instantaneous current in µA. Sign convention varies by OEM; see [BatteryEstimator]. */
    val currentMicroAmps: Long? = null,
    val voltageMilliVolts: Int? = null,
    val temperatureCelsius: Float? = null,
    val isCharging: Boolean = false,
)

/** The three averaging windows, mirroring the familiar `uptime` load averages. */
enum class LoadWindow(val label: String, val timeConstant: Duration) {
    ONE_MINUTE("1m", 60.seconds),
    FIVE_MINUTE("5m", 300.seconds),
    FIFTEEN_MINUTE("15m", 900.seconds),
}

/**
 * What the estimator currently believes about the battery.
 *
 * [runtime] is the headline number — how much longer the device will last at the
 * observed rate — computed per window so a UI can show both a twitchy short-term
 * figure and a stable long-term one.
 */
data class BatteryEstimate(
    val levelPercent: Float,
    val isCharging: Boolean,
    /** Discharge rate in percent per hour, per window. Positive means draining. */
    val drainPercentPerHour: Map<LoadWindow, Float>,
    /** Projected time remaining (or time to full when charging), per window. */
    val runtime: Map<LoadWindow, Duration?>,
    /** Instantaneous power draw in watts, when current and voltage are available. */
    val powerWatts: Float?,
    val temperatureCelsius: Float?,
    /** False until enough samples have accumulated for the estimate to mean anything. */
    val isWarmedUp: Boolean,
) {
    /**
     * The figure to put in the status bar: the five-minute window, which settles
     * quickly enough to react to a game launch without flickering on every
     * background task.
     */
    val headlineRuntime: Duration? get() = runtime[LoadWindow.FIVE_MINUTE] ?: runtime[LoadWindow.ONE_MINUTE]

    val headlineDrain: Float get() = drainPercentPerHour[LoadWindow.FIVE_MINUTE] ?: 0f
}

/**
 * An exponentially weighted moving average that tolerates irregular sampling.
 *
 * Battery samples do not arrive on a metronome — Android throttles broadcasts,
 * and the device sleeps. Weighting each update by the elapsed time keeps the
 * average honest instead of letting a burst of quick samples dominate.
 */
internal class TimeWeightedAverage(private val timeConstant: Duration) {
    var value: Float = 0f
        private set
    var hasValue: Boolean = false
        private set

    fun update(newValue: Float, elapsed: Duration) {
        if (!hasValue) {
            value = newValue
            hasValue = true
            return
        }
        val tau = timeConstant.inWholeMilliseconds.toDouble()
        val dt = elapsed.inWholeMilliseconds.toDouble().coerceAtLeast(0.0)
        val alpha = 1.0 - exp(-dt / tau)
        value = (value + alpha * (newValue - value)).toFloat()
    }

    fun reset() {
        value = 0f
        hasValue = false
    }
}

/**
 * Turns a stream of battery samples into a projected runtime.
 *
 * Two independent measurements of drain are available and they have opposite
 * weaknesses. The fuel gauge's *current* reading responds within seconds but is
 * noisy and its sign convention is not standardised across OEMs. The *level*
 * delta is unambiguous but only moves in whole percentage points, which on a
 * 6000 mAh battery can be twenty minutes apart. This class prefers current when
 * it can trust it and falls back to level deltas otherwise, smoothing both.
 */
class BatteryEstimator(
    /** Design capacity in µAh, used to convert current into percent-per-hour. */
    private val capacityUah: Long? = null,
    /** Samples required before an estimate is reported as warmed up. */
    private val warmupSamples: Int = 3,
) {
    private val averages = LoadWindow.entries.associateWith { TimeWeightedAverage(it.timeConstant) }

    private var previous: BatterySample? = null
    private var sampleCount = 0

    /**
     * Learned sign convention for `CURRENT_NOW`.
     *
     * Android documents positive-when-charging, but a good number of devices
     * report the opposite, and getting this backwards would show "8 hours left"
     * while the battery drains. Rather than trust the spec, watch the reported
     * sign while the charging state is known and believe what the hardware does.
     */
    private var currentIsPositiveWhenCharging: Boolean? = null

    fun reset() {
        averages.values.forEach { it.reset() }
        previous = null
        sampleCount = 0
    }

    fun add(sample: BatterySample): BatteryEstimate {
        val last = previous
        sampleCount++

        learnCurrentSign(sample)

        val elapsed = if (last == null) Duration.ZERO
        else (sample.timestampMs - last.timestampMs).coerceAtLeast(0L).milliseconds()

        val instantaneousDrain = drainPercentPerHour(sample, last, elapsed)
        if (instantaneousDrain != null && elapsed > Duration.ZERO) {
            averages.values.forEach { it.update(instantaneousDrain, elapsed) }
        } else if (instantaneousDrain != null && last == null) {
            // Seed every window from the first usable reading so the UI is not
            // blank until the second sample lands.
            averages.values.forEach { it.update(instantaneousDrain, Duration.ZERO) }
        }

        previous = sample

        val drains = averages.mapValues { (_, avg) -> avg.value }
        val runtimes = drains.mapValues { (_, rate) -> projectRuntime(sample, rate) }

        return BatteryEstimate(
            levelPercent = sample.levelPercent,
            isCharging = sample.isCharging,
            drainPercentPerHour = drains,
            runtime = runtimes,
            powerWatts = powerWatts(sample),
            temperatureCelsius = sample.temperatureCelsius,
            isWarmedUp = sampleCount >= warmupSamples && averages.values.all { it.hasValue },
        )
    }

    private fun learnCurrentSign(sample: BatterySample) {
        val current = sample.currentMicroAmps ?: return
        if (current == 0L) return
        // Only learn while charging: that is the one state where the expected
        // direction of energy flow is unambiguous.
        if (sample.isCharging && currentIsPositiveWhenCharging == null) {
            currentIsPositiveWhenCharging = current > 0
        }
    }

    /**
     * Discharge rate in percent per hour, or null when it cannot be measured.
     * Positive means losing charge.
     */
    private fun drainPercentPerHour(
        sample: BatterySample,
        last: BatterySample?,
        elapsed: Duration,
    ): Float? {
        fromCurrent(sample)?.let { return it }

        // Fall back to how much the reported level actually moved.
        if (last == null || elapsed <= Duration.ZERO) return null
        if (sample.isCharging != last.isCharging) return null

        val chargeDelta = if (sample.chargeCounterUah != null && last.chargeCounterUah != null && capacityUah != null && capacityUah > 0) {
            (last.chargeCounterUah - sample.chargeCounterUah).toFloat() / capacityUah * 100f
        } else {
            last.levelPercent - sample.levelPercent
        }
        if (chargeDelta == 0f) return null

        val hours = elapsed.inWholeMilliseconds / 3_600_000f
        if (hours <= 0f) return null
        return chargeDelta / hours
    }

    /** Converts the fuel gauge's current reading into percent per hour. */
    private fun fromCurrent(sample: BatterySample): Float? {
        val current = sample.currentMicroAmps ?: return null
        if (current == 0L) return null
        val capacity = capacityUah ?: return null
        if (capacity <= 0) return null

        val positiveMeansCharging = currentIsPositiveWhenCharging ?: true
        // Normalise to "µA flowing out of the battery".
        val drainUa = if (positiveMeansCharging) -current else current

        // A sign that contradicts the charging state means the convention has not
        // been learned correctly yet; skip rather than report a wrong direction.
        if (sample.isCharging && drainUa > 0) return null
        if (!sample.isCharging && drainUa < 0) return null

        return drainUa.toFloat() / capacity * 100f
    }

    /** Time until empty, or until full when charging. */
    private fun projectRuntime(sample: BatterySample, ratePercentPerHour: Float): Duration? {
        if (abs(ratePercentPerHour) < MIN_MEANINGFUL_RATE) return null

        return if (sample.isCharging) {
            if (ratePercentPerHour >= 0f) return null
            val remaining = (100f - sample.levelPercent).coerceAtLeast(0f)
            if (remaining <= 0f) Duration.ZERO
            else (remaining / abs(ratePercentPerHour)).hoursOrNull()
        } else {
            if (ratePercentPerHour <= 0f) return null
            (sample.levelPercent.coerceAtLeast(0f) / ratePercentPerHour).hoursOrNull()
        }
    }

    private companion object {
        /**
         * Below this, the projection is meaningless: a 0.05 %/h reading would
         * claim eighty days of runtime, which is noise dressed up as a number.
         */
        const val MIN_MEANINGFUL_RATE = 0.1f
        const val MAX_PROJECTION_HOURS = 96f
    }

    private fun Float.hoursOrNull(): Duration? =
        if (isNaN() || isInfinite() || this > MAX_PROJECTION_HOURS) null else toDouble().hours
}

private fun Long.milliseconds(): Duration = (this / 1000.0).seconds

private fun powerWatts(sample: BatterySample): Float? {
    val current = sample.currentMicroAmps ?: return null
    val voltage = sample.voltageMilliVolts ?: return null
    if (voltage <= 0) return null
    // µA * mV = 1e-6 A * 1e-3 V = 1e-9 W
    return abs(current.toFloat() * voltage.toFloat()) / 1_000_000_000f
}

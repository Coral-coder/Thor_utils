package gg.thor.utils.core.battery

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The AYN Thor's pack, used so the numbers here mean something concrete. */
private const val THOR_CAPACITY_UAH = 6_000_000L

class BatteryModelTest {

    private fun sample(
        minutes: Long,
        level: Float,
        currentUa: Long? = null,
        charging: Boolean = false,
    ) = BatterySample(
        timestampMs = minutes * 60_000L,
        levelPercent = level,
        currentMicroAmps = currentUa,
        voltageMilliVolts = 3900,
        isCharging = charging,
    )

    @Test
    fun `projects runtime from a steady level decline`() {
        val estimator = BatteryEstimator(THOR_CAPACITY_UAH)
        // 10 %/hour: one point every six minutes.
        var estimate = estimator.add(sample(0, 100f))
        for (step in 1..10) {
            estimate = estimator.add(sample(step * 6L, 100f - step))
        }

        val drain = estimate.drainPercentPerHour[LoadWindow.FIVE_MINUTE]!!
        assertTrue(abs(drain - 10f) < 2f, "expected roughly 10 %/h, got $drain")

        val runtime = estimate.runtime[LoadWindow.FIVE_MINUTE]
        assertNotNull(runtime)
        // 90 % left at ~10 %/h is about nine hours.
        val hours = runtime.inWholeMinutes / 60.0
        assertTrue(abs(hours - 9.0) < 1.5, "expected about 9h remaining, got ${hours}h")
    }

    @Test
    fun `the one minute window reacts faster than the fifteen minute window`() {
        val estimator = BatteryEstimator(THOR_CAPACITY_UAH)
        // Idle for a while, then start a demanding game.
        estimator.add(sample(0, 100f))
        for (step in 1..10) estimator.add(sample(step * 6L, 100f - step * 0.5f))

        var estimate = estimator.add(sample(66L, 94f))
        for (step in 1..4) estimate = estimator.add(sample(66L + step, 94f - step * 0.5f))

        val fast = estimate.drainPercentPerHour[LoadWindow.ONE_MINUTE]!!
        val slow = estimate.drainPercentPerHour[LoadWindow.FIFTEEN_MINUTE]!!
        assertTrue(fast > slow, "1m ($fast) should outrun 15m ($slow) after a load spike")
    }

    @Test
    fun `learns an inverted current sign convention`() {
        val estimator = BatteryEstimator(THOR_CAPACITY_UAH)
        // This device reports negative while charging — the opposite of the docs.
        estimator.add(sample(0, 50f, currentUa = -1_500_000, charging = true))
        estimator.add(sample(1, 51f, currentUa = -1_500_000, charging = true))
        // Now unplugged: positive current must be read as discharge, not charge.
        val estimate = estimator.add(sample(5, 51f, currentUa = 600_000, charging = false))

        val drain = estimate.drainPercentPerHour[LoadWindow.ONE_MINUTE]!!
        assertTrue(drain > 0f, "discharge must be reported as a positive drain, got $drain")
        assertNotNull(estimate.runtime[LoadWindow.ONE_MINUTE])
    }

    @Test
    fun `uses current for a fast estimate before the level has moved`() {
        val estimator = BatteryEstimator(THOR_CAPACITY_UAH)
        // 600 mA out of a 6000 mAh pack is exactly 10 %/hour.
        estimator.add(sample(0, 80f, currentUa = -600_000, charging = false))
        val estimate = estimator.add(sample(1, 80f, currentUa = -600_000, charging = false))

        val drain = estimate.drainPercentPerHour[LoadWindow.ONE_MINUTE]!!
        assertTrue(abs(drain - 10f) < 0.5f, "expected 10 %/h from current alone, got $drain")
    }

    @Test
    fun `projects time to full while charging`() {
        val estimator = BatteryEstimator(THOR_CAPACITY_UAH)
        estimator.add(sample(0, 50f, charging = true))
        var estimate = estimator.add(sample(6, 52f, charging = true))
        for (step in 2..8) estimate = estimator.add(sample(step * 6L, 50f + step * 2f, charging = true))

        val runtime = estimate.runtime[LoadWindow.FIVE_MINUTE]
        assertNotNull(runtime, "charging should project a time-to-full")
        assertTrue(estimate.isCharging)
        assertTrue(runtime.inWholeMinutes in 1..600, "implausible time to full: $runtime")
    }

    @Test
    fun `refuses to project from noise`() {
        val estimator = BatteryEstimator(THOR_CAPACITY_UAH)
        estimator.add(sample(0, 75f))
        // Level flat for an hour: a 0.0x %/h rate would imply weeks of runtime.
        val estimate = estimator.add(sample(60, 75f))
        assertNull(
            estimate.runtime[LoadWindow.FIVE_MINUTE],
            "a flat battery reading must not produce a projection",
        )
    }

    @Test
    fun `reports power draw in watts`() {
        val estimator = BatteryEstimator(THOR_CAPACITY_UAH)
        // 1 A at 3.9 V is 3.9 W.
        val estimate = estimator.add(sample(0, 60f, currentUa = -1_000_000))
        assertNotNull(estimate.powerWatts)
        assertTrue(abs(estimate.powerWatts!! - 3.9f) < 0.05f, "got ${estimate.powerWatts}")
    }

    @Test
    fun `is not warmed up on the first sample`() {
        val estimator = BatteryEstimator(THOR_CAPACITY_UAH)
        assertEquals(false, estimator.add(sample(0, 100f)).isWarmedUp)
    }
}

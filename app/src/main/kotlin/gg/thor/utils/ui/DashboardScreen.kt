package gg.thor.utils.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gg.thor.utils.battery.BatteryMonitorService
import gg.thor.utils.core.battery.LoadWindow
import gg.thor.utils.core.stats.Format
import gg.thor.utils.data.SettingsRepository
import gg.thor.utils.data.ThorSettings
import gg.thor.utils.overlay.StatsOverlayService
import gg.thor.utils.stats.DeviceSnapshot
import gg.thor.utils.stats.DeviceStatsReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The in-app view of everything the HUD and the notification show, plus the
 * switches that start and stop them.
 */
@Composable
fun DashboardScreen(settings: ThorSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reader = remember { DeviceStatsReader(context) }
    var snapshot by remember { mutableStateOf<DeviceSnapshot?>(null) }
    val battery by BatteryMonitorService.estimate.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = reader.snapshot()
            delay(1000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionCard(
            title = "Battery",
            subtitle = if (battery?.isWarmedUp == true) {
                "Projected from a time-weighted average of the measured drain"
            } else {
                "Warming up — the estimate settles after a few samples"
            },
        ) {
            val estimate = battery
            StatRow(
                label = if (estimate?.isCharging == true) "Until full" else "Runtime left",
                value = Format.runtime(estimate?.headlineRuntime),
                emphasis = true,
            )
            StatRow("Charge", "${estimate?.levelPercent?.toInt() ?: 0}%")
            Box(Modifier.height(4.dp))
            MeterBar(
                fraction = (estimate?.levelPercent ?: 0f) / 100f,
                invertScale = true,
            )
            Box(Modifier.height(10.dp))

            // The three windows side by side are the whole point: a 1m spike
            // during a load screen should not be mistaken for the real rate.
            LoadWindow.entries.forEach { window ->
                StatRow(
                    label = "Drain (${window.label})",
                    value = Format.drainRate(estimate?.drainPercentPerHour?.get(window) ?: 0f),
                )
            }
            estimate?.powerWatts?.let { StatRow("Power draw", Format.watts(it)) }
            estimate?.temperatureCelsius?.let { StatRow("Battery temp", Format.celsius(it)) }

            Box(Modifier.height(8.dp))
            ToggleRow(
                label = "Battery monitor",
                checked = settings.batteryMonitorEnabled,
                description = "Keeps the projection in the notification shade",
            ) { enabled ->
                scope.launch {
                    repository.update { it.batteryMonitorEnabled = enabled }
                    if (enabled) BatteryMonitorService.start(context)
                    else BatteryMonitorService.stop(context)
                }
            }
        }

        SectionCard("Processor") {
            StatRow("CPU load", Format.percent(snapshot?.cpuUtilisation ?: 0f))
            MeterBar(snapshot?.cpuUtilisation ?: 0f)
            Box(Modifier.height(8.dp))
            StatRow("Peak core clock", Format.megahertz(snapshot?.cpuMaxFrequencyMhz))
            StatRow("Temperature", Format.celsius(snapshot?.cpuTemperatureCelsius))
            snapshot?.cpuTemperatureCelsius?.let {
                MeterBar(((it - 30f) / 50f).coerceIn(0f, 1f))
            }
        }

        SectionCard(
            title = "Graphics",
            subtitle = if (snapshot?.gpuUtilisation == null) {
                "This kernel does not expose GPU counters to apps"
            } else {
                null
            },
        ) {
            StatRow("GPU load", snapshot?.gpuUtilisation?.let { Format.percent(it) } ?: "—")
            snapshot?.gpuUtilisation?.let { MeterBar(it) }
            StatRow("GPU clock", Format.megahertz(snapshot?.gpuFrequencyMhz))
        }

        SectionCard("Memory") {
            val memory = snapshot?.memory
            StatRow("Used", memory?.let { Format.bytes(it.usedBytes) } ?: "—")
            StatRow("Total", memory?.let { Format.bytes(it.totalBytes) } ?: "—")
            memory?.let { MeterBar(it.usedFraction) }
            if (memory != null && memory.swapTotalBytes > 0) {
                Box(Modifier.height(6.dp))
                StatRow("Swap used", Format.bytes(memory.swapUsedBytes))
            }
        }

        SectionCard("Storage") {
            StatRow("Free", snapshot?.let { Format.bytes(it.storageFreeBytes) } ?: "—")
            StatRow("Total", snapshot?.let { Format.bytes(it.storageTotalBytes) } ?: "—")
            snapshot?.let { MeterBar(it.storageUsedFraction) }
        }

        SectionCard(
            title = "Second-screen HUD",
            subtitle = "Draws these figures over whatever is on the bottom panel",
        ) {
            ToggleRow(
                label = "Show HUD",
                checked = settings.hudEnabled,
                description = "Needs the display-over-other-apps permission",
            ) { enabled ->
                scope.launch {
                    repository.update { it.hudEnabled = enabled }
                    if (enabled) StatsOverlayService.start(context)
                    else StatsOverlayService.stop(context)
                }
            }
            Hint(
                "The HUD window is created non-focusable, so touching it never takes " +
                    "focus away from the game on the top screen — there is nothing to " +
                    "tap back to.",
            )
        }

        SectionCard("Device") {
            StatRow("Model", DeviceStatsReader.deviceModel)
            StatRow("Screens", "${displayCount(context)}")
        }

        Box(Modifier.height(24.dp))
    }
}

private fun displayCount(context: Context): Int =
    gg.thor.utils.overlay.Displays.all(context).size

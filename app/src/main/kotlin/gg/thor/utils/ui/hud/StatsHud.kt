package gg.thor.utils.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.thor.utils.core.battery.LoadWindow
import gg.thor.utils.core.stats.Format
import gg.thor.utils.battery.BatteryMonitorService
import gg.thor.utils.data.ThorSettings
import gg.thor.utils.stats.DeviceSnapshot
import gg.thor.utils.ui.theme.GlassColors
import gg.thor.utils.ui.theme.GlassPanel
import gg.thor.utils.ui.theme.LocalGlassColors
import gg.thor.utils.ui.theme.ThorTheme
import androidx.compose.runtime.collectAsState

/**
 * The always-on statistics strip.
 *
 * Sized for the Thor's 3.92" bottom panel: one row of tiles, monospaced digits
 * so values do not jitter as they change width, and a tap target that toggles
 * detail rather than opening anything.
 */
@Composable
fun StatsHud(
    snapshot: DeviceSnapshot?,
    settings: ThorSettings,
    onRequestFocusReturn: () -> Unit,
) {
    ThorTheme(
        hue = settings.accentHue,
        saturation = settings.accentSaturation,
        opacity = settings.glassOpacity,
        blurEnabled = settings.glassBlur,
    ) {
        val colors = LocalGlassColors.current
        var expanded by remember { mutableStateOf(false) }
        val battery by BatteryMonitorService.estimate.collectAsState()

        GlassPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    expanded = !expanded
                    // Focus was never taken — the window is non-focusable — but if
                    // the user asked for an explicit hand-back, do it now.
                    onRequestFocusReturn()
                },
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (settings.hudShowCpu) {
                        StatTile("CPU", Format.percent(snapshot?.cpuUtilisation ?: 0f), snapshot?.cpuUtilisation, colors)
                    }
                    if (settings.hudShowGpu) {
                        StatTile("GPU", snapshot?.gpuUtilisation?.let { Format.percent(it) } ?: "—", snapshot?.gpuUtilisation, colors)
                    }
                    if (settings.hudShowMemory) {
                        val memory = snapshot?.memory
                        StatTile(
                            label = "RAM",
                            value = memory?.let { Format.bytes(it.usedBytes) } ?: "—",
                            fraction = memory?.usedFraction,
                            colors = colors,
                        )
                    }
                    if (settings.hudShowTemperature) {
                        val temperature = snapshot?.cpuTemperatureCelsius
                        StatTile(
                            label = "TEMP",
                            value = Format.celsius(temperature),
                            fraction = temperature?.let { ((it - 30f) / 50f).coerceIn(0f, 1f) },
                            colors = colors,
                        )
                    }
                    if (settings.hudShowBattery) {
                        StatTile(
                            label = "BATT",
                            value = Format.runtime(battery?.headlineRuntime),
                            fraction = battery?.let { it.levelPercent / 100f },
                            colors = colors,
                            invertScale = true,
                        )
                    }
                    if (settings.hudShowStorage) {
                        StatTile(
                            label = "DISK",
                            value = snapshot?.let { Format.bytes(it.storageFreeBytes) } ?: "—",
                            fraction = snapshot?.storageUsedFraction,
                            colors = colors,
                        )
                    }
                }

                if (expanded) {
                    Box(Modifier.height(6.dp))
                    DetailRow(snapshot, colors)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(snapshot: DeviceSnapshot?, colors: GlassColors) {
    val battery by BatteryMonitorService.estimate.collectAsState()
    val drains = battery?.drainPercentPerHour
    val line = buildString {
        append("drain ")
        append(LoadWindow.entries.joinToString("  ") { window ->
            "${window.label} ${Format.drainRate(drains?.get(window) ?: 0f)}"
        })
        snapshot?.cpuMaxFrequencyMhz?.let { append("   cpu ${Format.megahertz(it)}") }
        snapshot?.gpuFrequencyMhz?.let { append("   gpu ${Format.megahertz(it)}") }
        battery?.powerWatts?.let { append("   ${Format.watts(it)}") }
    }
    Text(
        text = line,
        color = colors.textSecondary,
        size = 10,
        weight = FontWeight.Normal,
    )
}

/** One labelled figure with a load bar underneath. */
@Composable
private fun StatTile(
    label: String,
    value: String,
    fraction: Float?,
    colors: GlassColors,
    /** For battery, where a *low* number is the bad one. */
    invertScale: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, colors.textSecondary, size = 9, weight = FontWeight.Medium, letterSpacing = 1.2f)
        Text(value, colors.textPrimary, size = 14, weight = FontWeight.Bold)
        Box(Modifier.height(3.dp))
        LoadBar(fraction ?: 0f, colors, invertScale)
    }
}

@Composable
private fun LoadBar(fraction: Float, colors: GlassColors, invertScale: Boolean) {
    val clamped = fraction.coerceIn(0f, 1f)
    val severity = if (invertScale) 1f - clamped else clamped
    val barColor = when {
        severity > 0.85f -> colors.bad
        severity > 0.65f -> colors.warn
        else -> colors.good
    }
    Box(
        Modifier
            .width(46.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.16f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(clamped)
                .height(3.dp)
                .background(Brush.horizontalGradient(listOf(barColor.copy(alpha = 0.7f), barColor))),
        )
    }
}

/**
 * Monospaced text wrapper.
 *
 * Every figure in the HUD changes once a second; a proportional font makes the
 * whole row shuffle sideways as digits change width, which is unreadable out of
 * the corner of your eye while playing.
 */
@Composable
private fun Text(
    text: String,
    color: Color,
    size: Int,
    weight: FontWeight,
    letterSpacing: Float = 0f,
) {
    androidx.compose.material3.Text(
        text = text,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        fontFamily = FontFamily.Monospace,
        letterSpacing = letterSpacing.sp,
        maxLines = 1,
    )
}

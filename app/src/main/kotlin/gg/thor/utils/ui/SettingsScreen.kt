package gg.thor.utils.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.thor.utils.data.HudPosition
import gg.thor.utils.data.SettingsRepository
import gg.thor.utils.data.ThorSettings
import gg.thor.utils.overlay.Displays
import gg.thor.utils.overlay.FocusReturnService
import gg.thor.utils.overlay.StatsOverlayService
import gg.thor.utils.ui.theme.LocalGlassColors
import gg.thor.utils.ui.theme.supportsBackdropBlur
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(settings: ThorSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalGlassColors.current
    val displays = Displays.all(context)

    fun edit(block: (SettingsRepository.MutablePrefs) -> Unit) {
        scope.launch { repository.update(block) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SectionCard("Appearance", subtitle = "Glass tuned from a single base colour") {
            // A live strip of the current theme, so the sliders have something to
            // act on without hunting for a panel that happens to show the change.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.accentBrush),
            )
            Box(Modifier.height(10.dp))

            LabelledSlider("Base colour", settings.accentHue, 0f, 360f) { hue ->
                edit { it.accentHue = hue }
            }
            LabelledSlider("Saturation", settings.accentSaturation, 0f, 1f) { value ->
                edit { it.accentSaturation = value }
            }
            LabelledSlider("Glass opacity", settings.glassOpacity, 0.15f, 1f) { value ->
                edit { it.glassOpacity = value }
            }
            ToggleRow(
                label = "Blur behind glass",
                checked = settings.glassBlur,
                enabled = supportsBackdropBlur,
                description = if (supportsBackdropBlur) {
                    "Frosts whatever is behind a panel"
                } else {
                    "Needs Android 12 or newer"
                },
            ) { enabled -> edit { it.glassBlur = enabled } }
        }

        SectionCard("Battery monitor") {
            LabelledSlider(
                label = "Sample every ${settings.batterySampleSeconds}s",
                value = settings.batterySampleSeconds.toFloat(),
                min = 5f,
                max = 120f,
            ) { seconds -> edit { it.batterySampleSeconds = seconds.toInt() } }
            Hint(
                "Shorter intervals react faster to a game launch and cost a little more " +
                    "power themselves. Twenty seconds is a reasonable balance.",
            )
            Box(Modifier.height(6.dp))
            Hint(
                if (settings.batteryCapacityUah > 0) {
                    "Pack capacity set to ${settings.batteryCapacityUah / 1000} mAh."
                } else {
                    "Pack capacity is read from the fuel gauge. The Thor ships a 6000 mAh " +
                        "cell — set it manually below if the readings look wrong."
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                SmallButton("Use 6000 mAh") { edit { it.batteryCapacityUah = 6_000_000L } }
                SmallButton("Auto-detect") { edit { it.batteryCapacityUah = 0L } }
            }
        }

        SectionCard("HUD placement") {
            Hint("Screens detected: ${displays.joinToString { "${it.label} (id ${it.id})" }}")
            Box(Modifier.height(8.dp))

            Text("Show the HUD on", color = colors.textSecondary, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                SelectChip("Auto", settings.hudDisplayId == -1) {
                    edit { it.hudDisplayId = -1 }
                }
                displays.forEach { display ->
                    SelectChip(display.label, settings.hudDisplayId == display.id) {
                        edit { it.hudDisplayId = display.id }
                    }
                }
            }

            Box(Modifier.height(10.dp))
            Text("Position", color = colors.textSecondary, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                HudPosition.entries.forEach { position ->
                    SelectChip(
                        label = position.name.lowercase().replaceFirstChar { it.uppercase() },
                        selected = settings.hudPosition == position,
                    ) { edit { it.hudPosition = position } }
                }
            }

            Box(Modifier.height(10.dp))
            ToggleRow("CPU", settings.hudShowCpu) { v -> edit { it.hudShowCpu = v } }
            ToggleRow("GPU", settings.hudShowGpu) { v -> edit { it.hudShowGpu = v } }
            ToggleRow("Memory", settings.hudShowMemory) { v -> edit { it.hudShowMemory = v } }
            ToggleRow("Battery", settings.hudShowBattery) { v -> edit { it.hudShowBattery = v } }
            ToggleRow("Temperature", settings.hudShowTemperature) { v -> edit { it.hudShowTemperature = v } }
            ToggleRow("Storage", settings.hudShowStorage) { v -> edit { it.hudShowStorage = v } }

            Box(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Overlay permission") {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            .setData(Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                SmallButton("Restart HUD") {
                    StatsOverlayService.stop(context)
                    StatsOverlayService.start(context)
                }
            }
        }

        SectionCard(
            title = "Focus priority",
            subtitle = "Which screen keeps input focus when you touch the other one",
        ) {
            Hint(
                "The HUD window is non-focusable, so touching it does not take focus " +
                    "from the game in the first place. This section only matters if " +
                    "something else moves focus to the bottom screen.",
            )
            Box(Modifier.height(8.dp))
            Text("Keep focus on", color = colors.textSecondary, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                displays.forEach { display ->
                    SelectChip(display.label, settings.focusPriorityDisplayId == display.id) {
                        edit { it.focusPriorityDisplayId = display.id }
                    }
                }
            }
            Box(Modifier.height(8.dp))
            ToggleRow(
                label = "Hand focus back automatically",
                checked = settings.focusReturnEnabled,
                description = "Needs the accessibility service enabled",
            ) { v -> edit { it.focusReturnEnabled = v } }

            Hint(
                if (FocusReturnService.isEnabled(context)) {
                    "Accessibility service is enabled."
                } else {
                    "Accessibility service is off — automatic hand-back will do nothing."
                },
            )
            Box(Modifier.height(8.dp))
            SmallButton("Accessibility settings") { FocusReturnService.openSettings(context) }
        }

        Box(Modifier.height(24.dp))
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
    val colors = LocalGlassColors.current
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = colors.textSecondary, fontSize = 11.sp)
            Text("${value.toInt()}", color = colors.textSecondary, fontSize = 11.sp)
        }
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onChange,
            valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = colors.accentBright,
                activeTrackColor = colors.accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.16f),
            ),
        )
    }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalGlassColors.current
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) colors.accentBrush
                else Brush.horizontalGradient(
                    listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.08f)),
                ),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) colors.textPrimary else colors.textSecondary,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    val colors = LocalGlassColors.current
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, color = colors.textPrimary, fontSize = 11.sp)
    }
}

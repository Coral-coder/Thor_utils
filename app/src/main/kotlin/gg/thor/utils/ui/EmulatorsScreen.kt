package gg.thor.utils.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.thor.utils.core.emulators.DualScreenSupport
import gg.thor.utils.core.emulators.Emulator
import gg.thor.utils.core.emulators.EmulatorCatalog
import gg.thor.utils.core.roms.SystemCatalog
import gg.thor.utils.launcher.AppLauncher
import gg.thor.utils.ui.theme.GlassPanel
import gg.thor.utils.ui.theme.LocalGlassColors

/**
 * The dual-screen emulator guide.
 *
 * Installed emulators are marked so the list doubles as a checklist rather than
 * a generic article — the point is what to do on *this* device, right now.
 */
@Composable
fun EmulatorsScreen() {
    val context = LocalContext.current
    val installed = remember {
        runCatching { AppLauncher(context).installedApps(includeIcons = false) }
            .getOrDefault(emptyList())
            .map { it.packageName }
            .toSet()
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SectionCard(
                title = "Dual-screen emulation",
                subtitle = "What actually uses the bottom panel on the Thor",
            ) {
                Hint(
                    "Only DS, 3DS and Wii U games were designed for two screens. " +
                        "Everything else is single-screen by nature — the bottom panel is " +
                        "then best used for the stats HUD or a second app.",
                )
                Box(Modifier.height(8.dp))
                SystemCatalog.dualScreenSystems.forEach { system ->
                    StatRow(system.name, system.id)
                }
            }
        }

        item {
            SectionCard("Recommended for two screens") {
                Hint(
                    "melonDS is the one that needs no setup: it finds the second panel " +
                        "and puts the touch screen there by itself. Azahar is the current " +
                        "3DS choice and needs its screen layout set once.",
                )
            }
        }

        items(EmulatorCatalog.emulators, key = { it.name }) { emulator ->
            EmulatorCard(emulator, isInstalled = emulator.packageIds.any { it in installed })
        }

        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun EmulatorCard(emulator: Emulator, isInstalled: Boolean) {
    val colors = LocalGlassColors.current
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    emulator.name,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(0.6f),
                )
                Box(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth()) {
                        if (emulator.recommended) Badge("Pick", colors.good)
                        if (isInstalled) {
                            Box(Modifier.padding(start = 4.dp)) { Badge("Installed", colors.accent) }
                        }
                    }
                }
            }

            Box(Modifier.height(4.dp))
            val supportColor = when (emulator.dualScreen) {
                DualScreenSupport.NATIVE -> colors.good
                DualScreenSupport.OPT_IN -> colors.accentBright
                DualScreenSupport.LAYOUT_WORKAROUND -> colors.warn
                else -> colors.textSecondary
            }
            Text(emulator.dualScreen.label, color = supportColor, fontSize = 11.sp)

            if (emulator.systems.isNotEmpty()) {
                Text(
                    emulator.systems.joinToString(" · "),
                    color = colors.textSecondary,
                    fontSize = 9.sp,
                )
            }

            Box(Modifier.height(6.dp))
            Text(emulator.notes, color = colors.textSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Badge(label: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.24f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

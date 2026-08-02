package gg.thor.utils.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import gg.thor.utils.data.SettingsRepository
import gg.thor.utils.data.ThorSettings
import gg.thor.utils.launcher.AppLauncher
import gg.thor.utils.launcher.LaunchResult
import gg.thor.utils.launcher.LaunchableApp
import gg.thor.utils.overlay.DisplayTarget
import gg.thor.utils.overlay.Displays
import gg.thor.utils.ui.theme.GlassPanel
import gg.thor.utils.ui.theme.LocalGlassColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Launches apps onto a chosen screen.
 *
 * Each row offers one button per physical display, so an emulator can be sent
 * straight to the bottom panel — or two instances started, one on each — without
 * going through Android's own launcher first.
 */
@Composable
fun LauncherScreen(settings: ThorSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val launcher = remember { AppLauncher(context) }
    val displays = remember { Displays.all(context) }

    var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        // Icon loading for a few hundred packages is far too slow for the main
        // thread on a handheld.
        apps = withContext(Dispatchers.IO) { launcher.installedApps() }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        SectionCard(
            title = "Launch on a screen",
            subtitle = if (displays.size > 1) {
                "${displays.size} screens detected"
            } else {
                "Only one screen detected — connect or enable the second panel"
            },
        ) {
            displays.forEach { display ->
                StatRow(display.label, "${display.resolution}  ·  id ${display.id}")
            }
            message?.let {
                Box(Modifier.height(8.dp))
                Hint(it)
            }
        }

        if (loading) {
            SectionCard("Apps") { Hint("Reading installed apps…") }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(apps, key = { it.packageName }) { app ->
                AppRow(app, displays) { packageName, displayId ->
                    message = when (val result = launcher.launch(packageName, displayId)) {
                        is LaunchResult.Success -> "Launched ${app.label} on display $displayId."
                        is LaunchResult.MovedToDefaultDisplay -> result.reason
                        is LaunchResult.Failed -> result.reason
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: LaunchableApp,
    displays: List<DisplayTarget>,
    onLaunch: (String, Int) -> Unit,
) {
    val colors = LocalGlassColors.current
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            app.icon?.let { drawable ->
                val bitmap = remember(app.packageName) {
                    runCatching { drawable.toBitmap(96, 96).asImageBitmap() }.getOrNull()
                }
                if (bitmap != null) {
                    Image(bitmap, contentDescription = null, modifier = Modifier.size(36.dp))
                    Box(Modifier.width(10.dp))
                }
            }
            Column(Modifier.fillMaxWidth(0.52f)) {
                Text(
                    app.label,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                val subtitle = app.emulator?.let { emulator ->
                    "${emulator.name} · ${emulator.dualScreen.label}"
                } ?: app.packageName
                Text(subtitle, color = colors.textSecondary, fontSize = 9.sp, maxLines = 1)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                displays.forEach { display ->
                    LaunchChip(
                        label = if (display.isPrimary) "Top" else "Bottom",
                        onClick = { onLaunch(app.packageName, display.id) },
                    )
                    Box(Modifier.width(6.dp))
                }
            }
        }
    }
}

@Composable
private fun LaunchChip(label: String, onClick: () -> Unit) {
    val colors = LocalGlassColors.current
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.accentBrush)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

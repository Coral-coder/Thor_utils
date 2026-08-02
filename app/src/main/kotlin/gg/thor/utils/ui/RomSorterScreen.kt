package gg.thor.utils.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.thor.utils.core.roms.Confidence
import gg.thor.utils.core.roms.RomSorter
import gg.thor.utils.core.roms.SortAction
import gg.thor.utils.core.roms.SortPlan
import gg.thor.utils.core.roms.SortReport
import gg.thor.utils.core.roms.SortExecutor
import gg.thor.utils.core.roms.SorterOptions
import gg.thor.utils.core.stats.Format
import gg.thor.utils.data.SettingsRepository
import gg.thor.utils.data.ThorSettings
import gg.thor.utils.ui.theme.GlassPanel
import gg.thor.utils.ui.theme.LocalGlassColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The ROM sorter.
 *
 * The flow is deliberately scan → review → apply. A library of backed-up games
 * is not something to reorganise on a hunch, so the plan is shown in full, with
 * the reason for every decision, before anything is written.
 */
@Composable
fun RomSorterScreen(settings: ThorSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf(settings.lastSortSource.ifEmpty { defaultSource() }) }
    var romsRoot by remember { mutableStateOf(settings.romsRoot.ifEmpty { detectRomsRoot() }) }
    var plan by remember { mutableStateOf<SortPlan?>(null) }
    var report by remember { mutableStateOf<SortReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var copyInstead by remember { mutableStateOf(false) }
    var extractArchives by remember { mutableStateOf(false) }

    val hasStorageAccess = hasAllFilesAccess()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        if (!hasStorageAccess) {
            SectionCard("Storage permission needed") {
                Hint(
                    "Sorting moves thousands of files between folders, which needs " +
                        "all-files access. Android's per-file picker cannot do it.",
                )
                Box(Modifier.height(8.dp))
                ActionButton("Grant access") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                .setData(Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }

        SectionCard("Folders", subtitle = "Where the games are now, and where they should end up") {
            PathField("Source folder", source) { source = it }
            Box(Modifier.height(8.dp))
            PathField("ES-DE ROMs root", romsRoot) { romsRoot = it }
            Box(Modifier.height(6.dp))
            Hint(
                if (File(romsRoot).isDirectory) {
                    "Found ${File(romsRoot).listFiles()?.count { it.isDirectory } ?: 0} system folders here."
                } else {
                    "This folder does not exist yet — it will be created when you apply a plan."
                },
            )
        }

        SectionCard("Options") {
            ToggleRow(
                label = "Copy instead of move",
                checked = copyInstead,
                description = "Leaves the source library untouched. Needs twice the space.",
            ) { copyInstead = it }
            ToggleRow(
                label = "Unpack archives",
                checked = extractArchives,
                description = "Extract zip/7z/rar into the system folder instead of filing the archive",
            ) { extractArchives = it }
        }

        SectionCard("Scan") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(if (busy) "Working…" else "Scan library", enabled = !busy) {
                    scope.launch {
                        busy = true
                        status = "Scanning…"
                        report = null
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                RomSorter(
                                    SorterOptions(
                                        dryRun = true,
                                        copyInsteadOfMove = copyInstead,
                                        extractArchives = extractArchives,
                                    ),
                                ).plan(File(source), File(romsRoot)) { scanned, file ->
                                    // Snapshot state is safe to write from a
                                    // background thread. Throttled because a large
                                    // library would otherwise recompose thousands
                                    // of times a second for no benefit.
                                    if (scanned % 50 == 0) {
                                        status = "Scanning… $scanned files (${file.name})"
                                    }
                                }
                            }
                        }
                        busy = false
                        result.onSuccess {
                            plan = it
                            status = "Found ${it.items.size} games, ${it.ignored.size} files skipped."
                            repository.update { prefs ->
                                prefs.lastSortSource = source
                                prefs.romsRoot = romsRoot
                            }
                        }.onFailure { status = "Scan failed: ${it.message}" }
                    }
                }
                plan?.let { current ->
                    ActionButton("Apply plan", enabled = !busy && current.actionable.isNotEmpty()) {
                        scope.launch {
                            busy = true
                            status = "Moving files…"
                            val result = withContext(Dispatchers.IO) {
                                SortExecutor.execute(
                                    current,
                                    SorterOptions(
                                        dryRun = false,
                                        copyInsteadOfMove = copyInstead,
                                        extractArchives = extractArchives,
                                    ),
                                )
                            }
                            busy = false
                            report = result
                            status = "Filed ${result.moved.size} games" +
                                if (result.failed.isEmpty()) "." else ", ${result.failed.size} failed."
                            plan = null
                        }
                    }
                }
            }
            status?.let {
                Box(Modifier.height(8.dp))
                Hint(it)
            }
        }

        plan?.let { current ->
            SectionCard(
                title = "Plan",
                subtitle = "${current.actionable.size} to file · ${Format.bytes(current.totalBytes)}",
            ) {
                current.bySystem.entries.sortedBy { it.key }.forEach { (system, items) ->
                    StatRow(system, "${items.size}")
                }
                if (current.unidentified.isNotEmpty()) {
                    Box(Modifier.height(6.dp))
                    StatRow("unsorted", "${current.unidentified.size}")
                }
            }

            SectionCard("Every decision", subtitle = "Check these before applying") {
                current.items.take(MAX_LISTED).forEach { item ->
                    PlanRow(
                        name = item.primary.name,
                        destination = item.destination,
                        reason = item.note,
                        confidence = item.identification.confidence,
                        action = item.action,
                    )
                }
                if (current.items.size > MAX_LISTED) {
                    Hint("…and ${current.items.size - MAX_LISTED} more.")
                }
            }
        }

        report?.let { finished ->
            SectionCard("Result") {
                StatRow("Filed", "${finished.moved.size}")
                StatRow("Failed", "${finished.failed.size}")
                StatRow("Moved", Format.bytes(finished.bytesHandled))
                finished.failed.take(10).forEach { failure ->
                    Hint("${failure.item.primary.name}: ${failure.error}")
                }
            }
        }

        Box(Modifier.height(24.dp))
    }
}

private const val MAX_LISTED = 60

@Composable
private fun PlanRow(
    name: String,
    destination: String,
    reason: String,
    confidence: Confidence,
    action: SortAction,
) {
    val colors = LocalGlassColors.current
    val tint = when {
        action == SortAction.UNSORTED -> colors.warn
        confidence >= Confidence.STRONG -> colors.good
        else -> colors.textSecondary
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                name,
                color = colors.textPrimary,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(0.6f),
            )
            Text(
                "→ $destination",
                color = tint,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        Text(reason, color = colors.textSecondary, fontSize = 9.sp, maxLines = 2)
    }
}

@Composable
private fun PathField(label: String, value: String, onChange: (String) -> Unit) {
    val colors = LocalGlassColors.current
    Column {
        Text(label, color = colors.textSecondary, fontSize = 11.sp)
        Box(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(colors.accentBright),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.28f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalGlassColors.current
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) colors.accentBrush else SolidColor(Color.White.copy(alpha = 0.10f)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            color = if (enabled) colors.textPrimary else colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

private fun defaultSource(): String =
    File(Environment.getExternalStorageDirectory(), "Download").absolutePath

/**
 * Finds an existing ES-DE ROMs root.
 *
 * ES-DE defaults to `ROMs` at the top of shared storage, but plenty of people
 * keep the library on an SD card, so the common locations are probed before
 * falling back to the default path.
 */
private fun detectRomsRoot(): String {
    val shared = Environment.getExternalStorageDirectory()
    val candidates = listOf(
        File(shared, "ROMs"),
        File(shared, "roms"),
        File(shared, "ES-DE/ROMs"),
        File("/storage/emulated/0/ROMs"),
    )
    candidates.firstOrNull { it.isDirectory }?.let { return it.absolutePath }

    // Any mounted SD card with a ROMs folder on it.
    File("/storage").listFiles()?.forEach { volume ->
        val romsOnVolume = File(volume, "ROMs")
        if (romsOnVolume.isDirectory) return romsOnVolume.absolutePath
    }
    return File(shared, "ROMs").absolutePath
}

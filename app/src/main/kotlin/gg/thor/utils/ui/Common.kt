package gg.thor.utils.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.thor.utils.ui.theme.GlassPanel
import gg.thor.utils.ui.theme.LocalGlassColors

/** A titled glass card — the standard block every screen is built from. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalGlassColors.current
    GlassPanel(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(subtitle, color = colors.textSecondary, fontSize = 11.sp)
            }
            Box(Modifier.height(10.dp))
            content()
        }
    }
}

/** Label on the left, monospaced value on the right. */
@Composable
fun StatRow(label: String, value: String, emphasis: Boolean = false) {
    val colors = LocalGlassColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.textSecondary, fontSize = 12.sp)
        Text(
            text = value,
            color = if (emphasis) colors.accentBright else colors.textPrimary,
            fontSize = if (emphasis) 15.sp else 12.sp,
            fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Horizontal meter with severity colouring. */
@Composable
fun MeterBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    invertScale: Boolean = false,
) {
    val colors = LocalGlassColors.current
    val clamped = fraction.coerceIn(0f, 1f)
    val severity = if (invertScale) 1f - clamped else clamped
    val barColor = when {
        severity > 0.85f -> colors.bad
        severity > 0.65f -> colors.warn
        else -> colors.good
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.14f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(clamped)
                .height(7.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(barColor.copy(alpha = 0.55f), barColor),
                    ),
                ),
        )
    }
}

/** Label, optional description and a switch. */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    description: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val colors = LocalGlassColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.fillMaxWidth(0.72f)) {
            Text(
                label,
                color = if (enabled) colors.textPrimary else colors.textSecondary,
                fontSize = 13.sp,
            )
            if (description != null) {
                Text(description, color = colors.textSecondary, fontSize = 10.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colors.accent,
                uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
            ),
        )
    }
}

/** Short explanatory paragraph in the secondary colour. */
@Composable
fun Hint(text: String) {
    val colors = LocalGlassColors.current
    Text(
        text = text,
        color = colors.textSecondary,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
}

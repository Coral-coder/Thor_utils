package gg.thor.utils.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/**
 * The palette, derived from a single user-chosen hue.
 *
 * Everything is generated rather than hand-picked so that moving the hue slider
 * produces a coherent theme instead of one recoloured accent on a fixed grey
 * chrome.
 */
@Immutable
data class GlassColors(
    val accent: Color,
    val accentBright: Color,
    val accentDeep: Color,
    val background: Color,
    val panelTop: Color,
    val panelBottom: Color,
    val panelBorder: Color,
    val highlight: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
    val opacity: Float,
    val blurEnabled: Boolean,
) {
    /**
     * The Aero body gradient: a brighter upper half over a darker lower half,
     * which is what reads as "glass" before any blur is involved.
     */
    val panelBrush: Brush
        get() = Brush.verticalGradient(listOf(panelTop, panelBottom))

    /**
     * The specular sheen across the top of a panel — the single most recognisable
     * piece of the XP/Vista look. Vista drew it as a glossy band over the top
     * ~45% of the surface, fading to nothing at the midpoint.
     */
    val sheenBrush: Brush
        get() = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.30f * opacity),
            0.45f to Color.White.copy(alpha = 0.08f * opacity),
            0.46f to Color.Transparent,
            1f to Color.Transparent,
        )

    val accentBrush: Brush
        get() = Brush.horizontalGradient(listOf(accentDeep, accent, accentBright))
}

val LocalGlassColors = compositionLocalOf { glassColors(205f, 0.72f, 0.55f, true) }

/**
 * Builds the palette from a hue, saturation and opacity.
 *
 * Colours are constructed in HSL because that is the space the controls expose:
 * dragging one hue slider should rotate the whole theme while preserving the
 * relative lightness that makes text stay readable.
 */
fun glassColors(
    hue: Float,
    saturation: Float,
    opacity: Float,
    blurEnabled: Boolean,
): GlassColors {
    fun hsl(h: Float, s: Float, l: Float, alpha: Float = 1f) =
        Color.hsl(h.mod(360f), s.coerceIn(0f, 1f), l.coerceIn(0f, 1f), alpha)

    val accent = hsl(hue, saturation, 0.55f)
    val accentBright = hsl(hue - 8f, (saturation + 0.12f), 0.70f)
    val accentDeep = hsl(hue + 8f, saturation, 0.34f)

    return GlassColors(
        accent = accent,
        accentBright = accentBright,
        accentDeep = accentDeep,
        // The backdrop keeps a trace of the accent so panels sit in a tinted
        // room rather than floating on neutral black.
        background = hsl(hue, saturation * 0.55f, 0.07f),
        panelTop = hsl(hue, saturation * 0.55f, 0.42f, 0.55f * opacity + 0.10f),
        panelBottom = hsl(hue + 6f, saturation * 0.65f, 0.16f, 0.72f * opacity + 0.14f),
        panelBorder = hsl(hue - 4f, saturation * 0.35f, 0.78f, 0.42f),
        highlight = Color.White.copy(alpha = 0.75f),
        textPrimary = Color(0xFFF3F8FF),
        textSecondary = hsl(hue, 0.25f, 0.80f, 0.82f),
        good = Color(0xFF5CD68A),
        warn = Color(0xFFF2C14E),
        bad = Color(0xFFF2685C),
        opacity = opacity,
        blurEnabled = blurEnabled,
    )
}

@Composable
fun ThorTheme(
    hue: Float = 205f,
    saturation: Float = 0.72f,
    opacity: Float = 0.55f,
    blurEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = glassColors(hue, saturation, opacity, blurEnabled)
    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = Color.White,
        secondary = colors.accentBright,
        background = colors.background,
        onBackground = colors.textPrimary,
        surface = colors.panelBottom.compositeOver(colors.background),
        onSurface = colors.textPrimary,
        error = colors.bad,
    )
    CompositionLocalProvider(LocalGlassColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}

/** Rounded, translucent, with a sheen — the workhorse container. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalGlassColors.current
    Box(
        modifier = modifier
            .background(brush = colors.panelBrush, shape = shape)
            .border(width = 1.dp, color = colors.panelBorder, shape = shape),
    ) {
        // The sheen is a sibling of the content, not a background, so it lies
        // over child views the way a real glass surface would.
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(colors.sheenBrush, shape),
        )
        Box(Modifier.padding(1.dp), content = content)
    }
}

/**
 * True when the platform can blur what is behind a window.
 *
 * `RenderEffect` blur arrived in Android 12; below that the theme leans on the
 * gradient and sheen alone, which still reads as glass.
 */
val supportsBackdropBlur: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** ARGB int for the places that need a platform colour rather than a Compose one. */
fun Color.toPlatformInt(): Int = toArgb()

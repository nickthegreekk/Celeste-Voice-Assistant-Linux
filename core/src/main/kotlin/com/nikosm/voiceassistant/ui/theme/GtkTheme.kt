package com.nikosm.voiceassistant.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/**
 * Which palette the application paints with (2026-10-18).
 *
 * [CELESTE] is the app's own scheme — the static Material 3 palette this port has always used.
 * [GTK] takes its colours from the desktop's current GTK theme, probed through GTK itself at
 * launch (see `:platform`'s `GtkThemeProbe`), so the window sits inside an XFCE/GNOME session
 * without fighting it. The shape of every screen is unchanged either way: this is a palette and
 * geometry choice, not a second UI toolkit (PLAN.md D2).
 */
enum class DesktopStyle {
    CELESTE,
    GTK,
}

/**
 * The GTK theme's resolved colours, as ARGB ints.
 *
 * Ints rather than `Color` on purpose: `:platform` produces this and does not depend on Compose;
 * [gtkColorScheme] is where they become a [ColorScheme]. The names are GTK's own, so a reader can
 * match every field to a `gtk_style_context_lookup_color` call.
 */
data class GtkPalette(
    val background: Int,
    val foreground: Int,
    val base: Int,
    val text: Int,
    val selectedBackground: Int,
    val selectedForeground: Int,
    val borders: Int,
    val insensitiveForeground: Int,
    val error: Int,
    val warning: Int,
    val success: Int,
) {
    /** Whether this is a dark theme, from the window background — not from its name. */
    val isDark: Boolean get() = relativeLuminance(background) < 0.5f
}

/**
 * Maps a GTK palette onto the Material 3 roles the ported screens actually read.
 *
 * Material asks for more roles than GTK names, so the remainder are derived the way Adwaita
 * derives its own surfaces: containers are the view background blended toward the foreground in
 * small steps, containers for the accent and error are the base blended toward those colours.
 * Sizes, weights and the type scale are untouched; the system font is already in.
 */
fun gtkColorScheme(palette: GtkPalette): ColorScheme {
    val background = Color(palette.background)
    val foreground = Color(palette.foreground)
    val base = Color(palette.base)
    val text = Color(palette.text)
    val accent = Color(palette.selectedBackground)
    val onAccent = Color(palette.selectedForeground)
    val outline = Color(palette.borders)
    val error = Color(palette.error)
    val success = Color(palette.success)
    val warning = Color(palette.warning)

    fun towardForeground(fraction: Float): Color = blend(base, foreground, fraction)
    fun readableOn(color: Color): Color = if (relativeLuminance(color.toArgb()) > 0.5f) Color.Black else Color.White

    return if (palette.isDark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = blend(base, accent, 0.30f),
            onPrimaryContainer = foreground,
            inversePrimary = accent,
            secondary = accent,
            onSecondary = onAccent,
            secondaryContainer = blend(base, accent, 0.18f),
            onSecondaryContainer = foreground,
            tertiary = success,
            onTertiary = readableOn(success),
            tertiaryContainer = blend(base, success, 0.22f),
            onTertiaryContainer = foreground,
            background = background,
            onBackground = foreground,
            surface = base,
            onSurface = foreground,
            surfaceVariant = towardForeground(0.08f),
            onSurfaceVariant = text,
            surfaceTint = accent,
            inverseSurface = foreground,
            inverseOnSurface = background,
            error = error,
            onError = readableOn(error),
            errorContainer = blend(base, error, 0.25f),
            onErrorContainer = foreground,
            outline = outline,
            outlineVariant = blend(outline, background, 0.45f),
            scrim = Color.Black,
            surfaceBright = towardForeground(0.12f),
            surfaceDim = background,
            surfaceContainerLowest = background,
            surfaceContainerLow = towardForeground(0.03f),
            surfaceContainer = towardForeground(0.05f),
            surfaceContainerHigh = towardForeground(0.08f),
            surfaceContainerHighest = towardForeground(0.11f),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = blend(background, accent, 0.16f),
            onPrimaryContainer = foreground,
            inversePrimary = accent,
            secondary = accent,
            onSecondary = onAccent,
            secondaryContainer = blend(background, accent, 0.10f),
            onSecondaryContainer = foreground,
            tertiary = success,
            onTertiary = readableOn(success),
            tertiaryContainer = blend(background, success, 0.16f),
            onTertiaryContainer = foreground,
            background = background,
            onBackground = foreground,
            surface = base,
            onSurface = foreground,
            surfaceVariant = towardForeground(0.06f),
            onSurfaceVariant = text,
            surfaceTint = accent,
            inverseSurface = foreground,
            inverseOnSurface = background,
            error = error,
            onError = readableOn(error),
            errorContainer = blend(background, error, 0.14f),
            onErrorContainer = foreground,
            outline = outline,
            outlineVariant = blend(outline, background, 0.45f),
            scrim = Color.Black,
            surfaceBright = base,
            surfaceDim = towardForeground(0.06f),
            surfaceContainerLowest = base,
            surfaceContainerLow = towardForeground(0.02f),
            surfaceContainer = towardForeground(0.04f),
            surfaceContainerHigh = towardForeground(0.06f),
            surfaceContainerHighest = towardForeground(0.09f),
        )
    }
}

/**
 * GTK's geometry: buttons at 6 dp, popovers/cards around 12 dp, entries at 4 dp.
 * Only components that read `MaterialTheme.shapes` take these; Material's pill-shaped
 * buttons and switches draw their own geometry and are a later pass.
 */
val GtkShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/** Straight alpha blend in sRGB; the amounts here are small enough that gamma is not worth it. */
internal fun blend(from: Color, to: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = 1f
    )
}

/** WCAG relative luminance of an ARGB int, used for light/dark detection and readable text. */
internal fun relativeLuminance(argb: Int): Float {
    fun channel(value: Int): Float {
        val s = (value and 0xFF) / 255f
        return if (s <= 0.03928f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
    }
    val r = channel(argb shr 16)
    val g = channel(argb shr 8)
    val b = channel(argb)
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

/** The `warning` role has no Material 3 slot; exposed for callers that need it. */
@Suppress("unused")
fun GtkPalette.warningColor(): Color = Color(warning)

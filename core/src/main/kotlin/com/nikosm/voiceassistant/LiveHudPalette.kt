package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The Live HUD's colour language, derived from the active persona.
 *
 * The first Live HUD was one fixed warm material. The second is a nebula whose hue is the active
 * persona's [Persona.themeColor]: switching persona re-tints the cloud and the chrome, which is
 * what makes the window feel like *that* assistant rather than a generic reactor.
 *
 * ## Why the theme colour is lifted
 *
 * `themeColor` was chosen against the app's surfaces, not against a near-black HUD backdrop. A
 * persona may legitimately pick a dark or a neutral colour. Rendering the raw value would make
 * the cloud disappear; forcing saturation would invent a hue for a grey persona (grey's HSV hue
 * is 0, which is red). So a coloured theme keeps its hue and has its saturation and value raised
 * to a floor that is visible on black, while a neutral theme stays neutral and is lifted in
 * value only.
 *
 * ## State does not change the hue
 *
 * The four states are still visually distinct, but with one persona hue they differ in value:
 * STANDBY is banked, LISTENING is the plain persona colour, COMPUTING is lifted toward the hot
 * centre, and TRANSMITTING is the brightest. The motion energy is a separate axis because the
 * frame loop already has the measured level; hue would be the third signal for one meaning.
 */
internal data class LiveHudPalette(
    /** The persona hue at a value that reads on the HUD's near-black backdrop. */
    val primary: Color,
    /** A hue-rotated second tone that gives the cloud depth without leaving the family. */
    val secondary: Color,
    /** The white-hot centre of the cloud and the body text. */
    val hot: Color,
    /** The darkest body tone inside the cloud. */
    val deep: Color,
    /** The top of the window backdrop. */
    val background: Color,
    /** The bottom of the window backdrop. */
    val backgroundDeep: Color,
    /** Labels and secondary chrome. */
    val muted: Color,
)

internal fun liveHudPalette(themeColor: Color): LiveHudPalette {
    val (hue, saturation, value) = themeColor.toHsv()
    val neutral = saturation < 0.12f

    val primary = if (neutral) {
        // An achromatic persona must stay achromatic. A very slight cool bias keeps the cloud
        // from reading as flat grey without claiming a colour the user did not pick.
        val grey = max(value, 0.84f).coerceAtMost(0.96f)
        Color(grey * 0.97f, grey * 0.99f, grey, 1f)
    } else {
        hsvToColor(hue, max(saturation, 0.60f), max(value, 0.90f))
    }

    val secondary = if (neutral) {
        val grey = max(value, 0.58f).coerceAtMost(0.78f)
        Color(grey * 0.94f, grey * 0.97f, grey, 1f)
    } else {
        hsvToColor(
            hue = (hue + 0.075f) % 1f,
            saturation = max(saturation, 0.55f),
            value = min(max(value, 0.66f), 0.82f),
        )
    }

    return LiveHudPalette(
        primary = primary,
        secondary = secondary,
        hot = mix(primary, Color.White, 0.86f),
        deep = mix(primary.scaleRgb(0.06f), Color(0.004f, 0.010f, 0.022f), 0.55f),
        background = mix(primary.scaleRgb(0.055f), Color(0.008f, 0.016f, 0.030f), 0.62f),
        backgroundDeep = mix(primary.scaleRgb(0.014f), Color.Black, 0.72f),
        muted = mix(primary, Color.Black, 0.42f),
    )
}

/**
 * The accent for [state], keeping [themeColor]'s hue and moving only its brightness.
 *
 * The ordering is deliberate: STANDBY is the dimmest, TRANSMITTING the brightest. A state that
 * cannot be told apart by brightness would be back to needing a second hue.
 */
internal fun liveHudAccent(state: AssistantState, themeColor: Color): Color {
    val palette = liveHudPalette(themeColor)
    return when (state) {
        AssistantState.IDLE -> mix(palette.primary, palette.deep, 0.42f)
        AssistantState.LISTENING -> palette.primary
        AssistantState.THINKING -> mix(palette.primary, palette.hot, 0.25f)
        AssistantState.SPEAKING -> mix(palette.primary, palette.hot, 0.45f)
    }
}

/**
 * How hard the cloud churns, independent of the measured level.
 *
 * COMPUTING is the most turbulent state; TRANSMITTING is driven by [liveHudLevel] on top of this,
 * so its baseline is lower. These are shader inputs, not a measurement.
 */
internal fun liveHudEnergy(state: AssistantState): Float = when (state) {
    AssistantState.IDLE -> 0.15f
    AssistantState.LISTENING -> 0.45f
    AssistantState.THINKING -> 0.95f
    AssistantState.SPEAKING -> 0.70f
}

/** Perceptual value of a colour, used by the palette tests and the state ordering. */
internal fun liveHudValue(color: Color): Float = maxOf(color.red, color.green, color.blue)

private fun mix(a: Color, b: Color, t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = a.alpha + (b.alpha - a.alpha) * f,
    )
}

/** Scales RGB without touching alpha: `Color * Float` would fade the colour out too. */
private fun Color.scaleRgb(factor: Float): Color =
    Color(red * factor, green * factor, blue * factor, alpha)

/** Hue in 0..1, saturation in 0..1, value in 0..1. */
private fun Color.toHsv(): Triple<Float, Float, Float> {
    val r = red
    val g = green
    val b = blue
    val high = maxOf(r, g, b)
    val low = minOf(r, g, b)
    val delta = high - low
    val saturation = if (high <= 0f) 0f else delta / high
    if (delta <= 0f) return Triple(0f, saturation, high)
    val hue = when (high) {
        r -> ((g - b) / delta).let { if (it < 0f) it + 6f else it }
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    } / 6f
    return Triple(hue % 1f, saturation, high)
}

private fun hsvToColor(hue: Float, saturation: Float, value: Float): Color {
    val h = (hue % 1f + 1f) % 1f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val sector = floor(h * 6f).toInt()
    val f = h * 6f - floor(h * 6f)
    val p = v * (1f - s)
    val q = v * (1f - f * s)
    val t = v * (1f - (1f - f) * s)
    return when (sector % 6) {
        0 -> Color(v, t, p, 1f)
        1 -> Color(q, v, p, 1f)
        2 -> Color(p, v, t, 1f)
        3 -> Color(p, q, v, 1f)
        4 -> Color(t, p, v, 1f)
        else -> Color(v, p, q, 1f)
    }
}

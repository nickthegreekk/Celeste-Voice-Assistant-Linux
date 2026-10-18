package com.nikosm.voiceassistant.ui.theme

// Deleted: `android.app.Activity`, which was an unused import upstream, and `android.os.Build`,
// whose two uses are the Material You gate below and the SDK_INT the theme passed into it.
// `dynamicDarkColorScheme` / `dynamicLightColorScheme` and `LocalContext` went with them — they
// are the only things in this file that needed a platform.
//
// The file lands in :desktop rather than :core for the same reason (AGENTS.md §2): it is Compose
// UI, and :core must not depend on Compose. Its three siblings land there too.
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// Static fallback schemes — the app's identity colors. They are `internal` rather
// than private so the instrumented Adaptive Theme tests can assert exact equality
// (i.e. that the static theme really is what gets used when dynamic is off or the
// platform cannot provide dynamic color).
internal val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = Color.White,
    onSurface = Color.White
)

internal val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = LightBackground,
    surface = LightSurface,
    onBackground = Color.Black,
    onSurface = Color.Black
)

/**
 * Whether this platform can derive the colour scheme from the user's wallpaper.
 *
 * **Always `false` here, and that is the whole answer rather than a placeholder.** Upstream this
 * was `sdkInt >= Build.VERSION_CODES.S`: Material You's `dynamicLightColorScheme` /
 * `dynamicDarkColorScheme` take an Android `Context`, reach the wallpaper service through it, and
 * have no counterpart in Compose Multiplatform. No desktop this targets hands an application the
 * user's wallpaper to derive a palette from, so the gate's argument — `Build.VERSION.SDK_INT` — is
 * gone with it.
 *
 * It is kept as a named function rather than deleted, for the reason upstream kept the gate at all
 * after raising minSdk to 31 and making it always true: it is where the Settings > Appearance
 * toggle reads *why* its switch does nothing. PLAN.md D2's measured table records the deletion of
 * the dynamic branches; this is the one line that says what the user sees as a result.
 */
internal fun isDynamicColorSupported(): Boolean = false

/**
 * Resolves the base [ColorScheme] the app paints with.
 *
 * Upstream had a three-way `when`: the dynamic scheme when the toggle was on and the platform
 * supported it, then static dark, then static light. Only two survive, because the first can
 * never be taken — see [isDynamicColorSupported]. The dynamic branch is deleted rather than left
 * unreachable: a branch that cannot be entered is one that will convince whoever reads this next
 * that dynamic colour is a supported configuration here.
 *
 * Upstream's `dynamicColor` and `sdkInt` parameters went with the branch. [VoiceAssistantTheme]
 * still takes the toggle — every caller passes it — but a parameter that cannot change a result is
 * better absent from the function that has no use for it than present and ignored inside it.
 */
@Composable
internal fun baseColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) DarkColorScheme else LightColorScheme

@Composable
fun VoiceAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Adaptive Theme: when the Settings toggle is on (and the platform supports it)
    // the base scheme is derived from the user's wallpaper. Persona accents are NOT
    // remapped here — callers keep painting `persona.themeColor` directly (mic ring,
    // badges, HUD starfield/planet), so each persona stays visually distinct on top
    // of whatever the wallpaper produces.
    //
    // **On this platform the toggle has no effect, and this is where that is recorded.**
    // [isDynamicColorSupported] is false and `baseColorScheme` has no dynamic branch to reach, so
    // the Settings > Appearance switch flips a stored value and changes nothing on screen. The
    // parameter stays because every caller passes it, and removing it would put a diff in eleven
    // call sites to no purpose. M3's settings screen is where the switch should say so in words
    // rather than silently no-opping — upstream's own comment on the gate asked for exactly that,
    // for the same reason.
    dynamicColor: Boolean = false, // Disabled by default to maintain the green/amber vibe
    /**
     * M17: the desktop's own UI font — resolved in `:desktop` through `fc-match` in `:platform`
     * (ProcessBuilder may not appear in `:core`) and passed in here. Null keeps Compose's
     * default family, which is what tests, previews and a machine without fontconfig get.
     */
    fontFamily: FontFamily? = null,
    /**
     * M17 follow-up (2026-10-18): the palette. `CELESTE` is this app's own scheme; `GTK` paints
     * with [gtkPalette], read from the desktop's current GTK theme in `:desktop`. A `GTK` choice
     * with no palette (the probe failed, or there is no GTK) falls back to Celeste rather than
     * painting nothing.
     */
    desktopStyle: DesktopStyle = DesktopStyle.CELESTE,
    gtkPalette: GtkPalette? = null,
    content: @Composable () -> Unit
) {
    val useGtk = desktopStyle == DesktopStyle.GTK && gtkPalette != null
    val colorScheme = if (useGtk) gtkColorScheme(gtkPalette!!) else baseColorScheme(darkTheme)
    // M17: hover, press and focus feedback for every plain `clickable` row. Provided INSIDE
    // MaterialTheme because MaterialTheme installs its own LocalIndication for descendants; this
    // replaces that one rather than being shadowed by it. Material buttons keep their ripple.
    val indication = remember(colorScheme.primary) {
        DesktopIndication(
            hoverColor = colorScheme.primary.copy(alpha = 0.08f),
            focusColor = colorScheme.primary.copy(alpha = 0.7f)
        )
    }
    // The text-field context menu (Cut/Copy/Paste/Select all) in the app's own colours and item
    // style, instead of Compose Desktop's light default. Installed here so every field — the
    // composer, the editors, Settings — follows the palette, Celeste or GTK.
    val contextMenuRepresentation = remember { MaterialContextMenuRepresentation() }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography.withFontFamily(fontFamily),
        shapes = if (useGtk) GtkShapes else Shapes,
    ) {
        CompositionLocalProvider(
            LocalIndication provides indication,
            LocalContextMenuRepresentation provides contextMenuRepresentation,
            content = content,
        )
    }
}

/**
 * Applies [family] to every Material type style.
 *
 * `Type.kt` is a byte-identical COPY file and may not change, so the desktop's font arrives here,
 * in the port-side theme that owns presentation. Copying each style rather than replacing the
 * typography wholesale keeps the sizes, weights and line heights `Type.kt` declares — the font is
 * the only thing being substituted.
 */
private fun Typography.withFontFamily(family: FontFamily?): Typography =
    if (family == null) this else copy(
        displayLarge = displayLarge.copy(fontFamily = family),
        displayMedium = displayMedium.copy(fontFamily = family),
        displaySmall = displaySmall.copy(fontFamily = family),
        headlineLarge = headlineLarge.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family),
        headlineSmall = headlineSmall.copy(fontFamily = family),
        titleLarge = titleLarge.copy(fontFamily = family),
        titleMedium = titleMedium.copy(fontFamily = family),
        titleSmall = titleSmall.copy(fontFamily = family),
        bodyLarge = bodyLarge.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family),
        labelMedium = labelMedium.copy(fontFamily = family),
        labelSmall = labelSmall.copy(fontFamily = family),
    )

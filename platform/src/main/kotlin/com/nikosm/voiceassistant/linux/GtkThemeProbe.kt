package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.ui.theme.GtkPalette
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Reads the colours of the desktop's CURRENT GTK theme, by asking GTK (2026-10-18).
 *
 * ## Why a helper process instead of reading theme files
 *
 * A GTK theme is not a stylesheet on disk. Arc-Dark, the theme this was developed against, has
 * one `gtk.css` whose entire content is `@import url("resource:///org/gnome/arc-theme/…)` — the
 * real definitions live in a compiled `gtk.gresource` bundle, and other themes nest `@import`s or
 * define colours through `shade()`/`alpha()` expressions. Parsing that is a second GTK theme
 * engine, and it would be wrong for the first theme that does something clever.
 *
 * GTK itself exposes the resolved values through `Gtk.StyleContext.lookup_color(name)`, which
 * handles gresource bundles, imports and expressions. The process is PyGObject + GTK 3, present on
 * a GTK3 desktop like XFCE; if it is absent, the probe returns null and the caller keeps the
 * Celeste palette. No build dependency is added, and nothing here runs unless the GTK style is
 * selected.
 *
 * The probe opens no window and writes nothing; it constructs a `Gtk.Window` only because a
 * style context needs a widget, then exits.
 */
fun probeGtkPalette(): GtkPalette? = runCatching {
    val process = ProcessBuilder("python3", "-c", PROBE_SCRIPT)
        .redirectErrorStream(true)
        .start()
    // Wait before reading: the output is a dozen short lines (well under a pipe buffer), and a
    // probe that hangs must not block the caller on `readText()`. A child this chatty cannot fill
    // the pipe and deadlock in the other direction.
    if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching null
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    parseGtkProbeOutput(output)
}.getOrNull()

/**
 * Parses `name r g b a` lines into a [GtkPalette]; null when the theme did not yield the three
 * colours without which a scheme cannot be built (window background, window foreground, selection
 * colour). Lines that do not match — GTK warnings, deprecation notes — are ignored.
 */
internal fun parseGtkProbeOutput(output: String): GtkPalette? {
    val colors = mutableMapOf<String, Int>()
    output.lineSequence().forEach { line ->
        val parts = line.trim().split(WHITESPACE)
        if (parts.size != 5) return@forEach
        val name = parts[0]
        val r = parts[1].toIntOrNull() ?: return@forEach
        val g = parts[2].toIntOrNull() ?: return@forEach
        val b = parts[3].toIntOrNull() ?: return@forEach
        val alpha = parts[4].toFloatOrNull() ?: return@forEach
        if (r !in 0..255 || g !in 0..255 || b !in 0..255 || alpha !in 0f..1f) return@forEach
        colors[name] = argb((alpha * 255).roundToInt().coerceIn(0, 255), r, g, b)
    }

    fun color(name: String): Int? = colors[name]

    val background = color("theme_bg_color") ?: return null
    val foreground = color("theme_fg_color") ?: return null
    val selected = color("theme_selected_bg_color") ?: return null

    return GtkPalette(
        background = background,
        foreground = foreground,
        base = color("theme_base_color") ?: background,
        text = color("theme_text_color") ?: foreground,
        selectedBackground = selected,
        selectedForeground = color("theme_selected_fg_color") ?: 0xFFFFFFFF.toInt(),
        borders = color("borders") ?: 0x66888888,
        insensitiveForeground = color("insensitive_fg_color") ?: 0x99FFFFFF.toInt(),
        error = color("error_color") ?: 0xFFE01B24.toInt(),
        warning = color("warning_color") ?: 0xFFF5A623.toInt(),
        success = color("success_color") ?: 0xFF33D17A.toInt(),
    )
}

private fun argb(alpha: Int, r: Int, g: Int, b: Int): Int =
    (alpha shl 24) or (r shl 16) or (g shl 8) or b

private val WHITESPACE = Regex("\\s+")
private const val PROBE_TIMEOUT_SECONDS = 3L

/**
 * Prints one `name r g b a` line per colour found. Exit code 0 even when GTK cannot start: the
 * caller treats "no lines" as "no GTK palette", not as an application failure.
 */
private val PROBE_SCRIPT = """
import sys
try:
    import gi
    gi.require_version('Gtk', '3.0')
    from gi.repository import Gtk
    Gtk.init([])
    context = Gtk.Window().get_style_context()
    for name in ('theme_bg_color', 'theme_fg_color', 'theme_base_color', 'theme_text_color',
                 'theme_selected_bg_color', 'theme_selected_fg_color',
                 'insensitive_fg_color', 'borders',
                 'error_color', 'warning_color', 'success_color'):
        ok, color = context.lookup_color(name)
        if ok:
            print('%s %d %d %d %.3f' % (name, round(color.red * 255),
                                        round(color.green * 255), round(color.blue * 255),
                                        color.alpha))
except Exception:
    sys.exit(0)
""".trimIndent()

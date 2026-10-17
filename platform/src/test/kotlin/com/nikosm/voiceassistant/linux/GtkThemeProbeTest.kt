package com.nikosm.voiceassistant.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The GTK probe's parser (2026-10-18).
 *
 * The probe itself is a Python/GTK process and is not started here — a test may not need a
 * display. What is testable is the contract with it: `name r g b a` lines become a palette, GTK's
 * warnings are ignored, and a theme that did not yield the three essential colours is reported as
 * "no palette" rather than guessed at.
 */
class GtkThemeProbeTest {

    /** The exact output of the probe against Arc-Dark on the development machine. */
    private val arcDark = """
        theme_bg_color 56 60 74 1.000
        theme_fg_color 211 218 227 1.000
        theme_base_color 64 69 82 1.000
        theme_text_color 211 218 227 1.000
        theme_selected_bg_color 82 148 226 1.000
        theme_selected_fg_color 255 255 255 1.000
        insensitive_fg_color 211 218 227 0.500
        borders 43 46 57 1.000
        error_color 252 65 56 1.000
        warning_color 242 120 53 1.000
        success_color 115 210 22 1.000
    """.trimIndent()

    @Test
    fun `resolved GTK colours become a palette`() {
        val palette = parseGtkProbeOutput(arcDark)!!

        assertEquals(0xFF383C4A.toInt(), palette.background)
        assertEquals(0xFFD3DAE3.toInt(), palette.foreground)
        assertEquals(0xFF404552.toInt(), palette.base)
        assertEquals(0xFF5294E2.toInt(), palette.selectedBackground)
        assertEquals(0xFFFFFFFF.toInt(), palette.selectedForeground)
        assertEquals(0xFF2B2E39.toInt(), palette.borders)
        assertEquals(0xFFFC4138.toInt(), palette.error)
    }

    @Test
    fun `translucent colours keep their alpha`() {
        // GTK reports the insensitive foreground at 0.5 alpha; dropping it would make disabled
        // text look enabled.
        val palette = parseGtkProbeOutput(arcDark)!!
        assertEquals(0x80, (palette.insensitiveForeground ushr 24) and 0xFF)
    }

    @Test
    fun `GTK warnings are ignored and missing essentials are not guessed`() {
        val warning = "(python3:1234): Gtk-WARNING **: cannot open display:"

        // Nothing at all: no GTK, no palette.
        assertNull(parseGtkProbeOutput(warning))
        // Background only: a scheme needs a foreground and a selection colour too.
        assertNull(parseGtkProbeOutput("theme_bg_color 56 60 74 1.000\n$warning"))
    }

    @Test
    fun `out-of-range numbers are rejected rather than colour-shifted`() {
        val bad = arcDark.replace("theme_bg_color 56 60 74 1.000", "theme_bg_color 256 -1 74 1.000")
        assertNull(parseGtkProbeOutput(bad))
    }
}

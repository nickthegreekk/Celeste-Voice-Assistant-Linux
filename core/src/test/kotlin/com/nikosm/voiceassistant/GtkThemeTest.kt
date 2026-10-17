package com.nikosm.voiceassistant

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.nikosm.voiceassistant.ui.theme.GtkPalette
import com.nikosm.voiceassistant.ui.theme.gtkColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GTK palette's mapping onto Material 3 (2026-10-18).
 *
 * The probe's own output is covered by `:platform`'s `GtkThemeProbeTest`; what is verified here is
 * that the colours GTK reports land in the roles the ported screens read, and that light/dark is
 * decided by the window background rather than by the theme's name.
 */
class GtkThemeTest {

    private val arcDark = GtkPalette(
        background = 0xFF383C4A.toInt(),
        foreground = 0xFFD3DAE3.toInt(),
        base = 0xFF404552.toInt(),
        text = 0xFFD3DAE3.toInt(),
        selectedBackground = 0xFF5294E2.toInt(),
        selectedForeground = 0xFFFFFFFF.toInt(),
        borders = 0xFF2B2E39.toInt(),
        insensitiveForeground = 0x80D3DAE3.toInt(),
        error = 0xFFFC4138.toInt(),
        warning = 0xFFF27835.toInt(),
        success = 0xFF73D216.toInt(),
    )

    private val adwaitaLight = GtkPalette(
        background = 0xFFFAFAFA.toInt(),
        foreground = 0xFF333333.toInt(),
        base = 0xFFFFFFFF.toInt(),
        text = 0xFF333333.toInt(),
        selectedBackground = 0xFF3584E4.toInt(),
        selectedForeground = 0xFFFFFFFF.toInt(),
        borders = 0xFFC8C8C8.toInt(),
        insensitiveForeground = 0x80333333.toInt(),
        error = 0xFFE01B24.toInt(),
        warning = 0xFFF5A623.toInt(),
        success = 0xFF33D17A.toInt(),
    )

    @Test
    fun `darkness comes from the window background`() {
        assertTrue(arcDark.isDark)
        assertFalse(adwaitaLight.isDark)
    }

    @Test
    fun `GTK's own colours land in the roles the screens read`() {
        val scheme = gtkColorScheme(arcDark)

        assertEquals(Color(arcDark.primaryArgb), scheme.primary)
        assertEquals(Color(arcDark.selectedForeground), scheme.onPrimary)
        assertEquals(Color(arcDark.background), scheme.background)
        assertEquals(Color(arcDark.foreground), scheme.onSurface)
        assertEquals(Color(arcDark.base), scheme.surface)
        assertEquals(Color(arcDark.borders), scheme.outline)
        assertEquals(Color(arcDark.error), scheme.error)
    }

    @Test
    fun `containers are derived from the theme, not left as Material's defaults`() {
        val scheme = gtkColorScheme(arcDark)
        val material = darkColorScheme()

        assertNotEquals(material.surfaceContainer, scheme.surfaceContainer)
        assertNotEquals(material.surfaceContainerHigh, scheme.surfaceContainerHigh)
        // Derived surfaces stay in the theme's own neighbourhood: a step between base and text.
        assertNotEquals(scheme.surface, scheme.surfaceContainer)
        assertNotEquals(scheme.onSurface, scheme.surfaceContainer)
    }

    @Test
    fun `a light theme maps without pretending to be dark`() {
        val scheme = gtkColorScheme(adwaitaLight)

        assertEquals(Color(adwaitaLight.background), scheme.background)
        assertEquals(Color(adwaitaLight.selectedBackground), scheme.primary)
        // Material cannot know which factory built a scheme; the roles must simply be the light
        // theme's own colours rather than the dark defaults.
        assertNotEquals(darkColorScheme().background, scheme.background)
    }

    private val GtkPalette.primaryArgb: Int get() = selectedBackground
}

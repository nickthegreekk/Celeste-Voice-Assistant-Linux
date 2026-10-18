package com.nikosm.voiceassistant

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.nikosm.voiceassistant.ui.theme.DarkColorScheme
import com.nikosm.voiceassistant.ui.theme.LightColorScheme
import com.nikosm.voiceassistant.ui.theme.baseColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Celeste palette (2026-10-18), after the Adaptive Theme switch was removed.
 *
 * Two checks outlive that switch: the Celeste style resolves to exactly the static schemes, and
 * the built-in personas keep pairwise-distinct accent colours. The wallpaper-derived cases and the
 * closed-gate case went with the feature; the GTK desktop style is the system-following control
 * now, and `GtkThemeTest` covers its mapping.
 *
 * The comparison is field-wise because `ColorScheme` is not a data class.
 */
@OptIn(ExperimentalTestApi::class)
class ThemeSchemeTest {

    private fun assertSameScheme(expected: ColorScheme, actual: ColorScheme, tag: String) {
        assertEquals("$tag.primary", expected.primary, actual.primary)
        assertEquals("$tag.onPrimary", expected.onPrimary, actual.onPrimary)
        assertEquals("$tag.secondary", expected.secondary, actual.secondary)
        assertEquals("$tag.tertiary", expected.tertiary, actual.tertiary)
        assertEquals("$tag.background", expected.background, actual.background)
        assertEquals("$tag.surface", expected.surface, actual.surface)
        assertEquals("$tag.onSurface", expected.onSurface, actual.onSurface)
        assertEquals("$tag.error", expected.error, actual.error)
    }

    @Test
    fun `the Celeste style resolves to the exact static schemes`() = runComposeUiTest {
        lateinit var dark: ColorScheme
        lateinit var light: ColorScheme
        setContent {
            dark = baseColorScheme(darkTheme = true)
            light = baseColorScheme(darkTheme = false)
        }
        waitForIdle()

        assertSameScheme(DarkColorScheme, dark, "static-dark")
        assertSameScheme(LightColorScheme, light, "static-light")
    }

    @Test
    fun `the persona accents stay pairwise distinct`() {
        val accents = (DEFAULT_PERSONAS + CLOUD_PERSONAS + TRANSLATOR_PERSONA)
            .map { it.name to it.themeColor }

        assertEquals(
            "built-in personas must keep pairwise-distinct accents",
            accents.size,
            accents.map { it.second }.distinct().size,
        )
    }
}

package com.nikosm.voiceassistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The application's own light/dark choice (2026-10-17).
 *
 * **The default is the behaviour that matters**: a fresh installation opens dark, because
 * Compose's `isSystemInDarkTheme()` reports light on this platform (Skiko's probe returns UNKNOWN
 * for an Xfce session, and the theme maps that to light), so following the system had meant always
 * opening white on a dark desktop.
 */
class ThemeSettingsTest {

    private val store = InMemoryStore()
    private val manager = SettingsManager(FakeAppEnv(), store, NoImageCodec)

    @Test
    fun `a fresh installation starts in the dark scheme`() {
        // No `dark_theme` key exists and the getter's default is the answer. The user asked for
        // exactly this: "keep the dark as the default on new installations".
        assertTrue(manager.getDarkTheme())
    }

    @Test
    fun `the light choice is stored and read back`() {
        manager.saveDarkTheme(false)

        assertFalse(manager.getDarkTheme())
    }

    @Test
    fun `turning dark back on is stored too`() {
        manager.saveDarkTheme(false)
        manager.saveDarkTheme(true)

        assertTrue(manager.getDarkTheme())
    }
}

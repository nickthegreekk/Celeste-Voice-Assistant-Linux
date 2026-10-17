package com.nikosm.voiceassistant

import com.nikosm.voiceassistant.ui.theme.DesktopStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Desktop style choice in the settings file (2026-10-18).
 *
 * Default Celeste, round-trip by name, and an unknown stored value falls back rather than failing
 * the settings load — the same recovery rule every other setting follows.
 */
class DesktopStyleSettingTest {

    private val store = InMemoryStore()
    private val manager = SettingsManager(FakeAppEnv(), store, NoImageCodec)

    @Test
    fun `a fresh install paints with Celeste`() {
        assertEquals(DesktopStyle.CELESTE, manager.getDesktopStyle())
    }

    @Test
    fun `the GTK choice round-trips`() {
        manager.saveDesktopStyle(DesktopStyle.GTK)
        assertEquals(DesktopStyle.GTK, manager.getDesktopStyle())

        manager.saveDesktopStyle(DesktopStyle.CELESTE)
        assertEquals(DesktopStyle.CELESTE, manager.getDesktopStyle())
    }

    @Test
    fun `an unknown stored value keeps Celeste instead of failing the load`() {
        store.edit().putString("desktop_style", "SOMETHING_FROM_THE_FUTURE").commit()

        assertEquals(DesktopStyle.CELESTE, manager.getDesktopStyle())
    }
}

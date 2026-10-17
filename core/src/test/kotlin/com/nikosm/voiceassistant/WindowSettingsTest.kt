package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window's remembered size (2026-10-17, by request), and the only interesting part of it: what a
 * value this build cannot read does.
 *
 * It falls back to the default window rather than to a guess — the shape every other setting here
 * uses, and the one that matters for a value a person can edit by hand. A window opened at a
 * nonsensical size is not a crash, but it is a startup a user has to fix with no way to see why.
 */
class WindowSettingsTest {

    private val env = FakeAppEnv()
    private val store = InMemoryStore()
    private val manager = SettingsManager(env, store, NoImageCodec)

    @Test
    fun `a size round-trips, and remembering it is on unless turned off`() {
        assertNull("no size until one is saved", manager.getWindowSize())
        assertTrue("and remembering is the default", manager.getRememberWindowSize())

        manager.saveWindowSize(1024, 768)
        assertEquals(1024 to 768, manager.getWindowSize())

        manager.saveRememberWindowSize(false)
        assertFalse(manager.getRememberWindowSize())
        assertEquals("turning the switch off does not erase the size", 1024 to 768, manager.getWindowSize())
    }

    @Test
    fun `an unreadable size falls back to the default window, never to a broken one`() {
        listOf("", "garbage", "1024", "1024x", "x768", "abcxdef", "0x0", "10x10", "99999x99999").forEach { raw ->
            // The literal key, like the other settings tests here: the companion holding it is
            // private to the manager, which is the point of a settings key.
            store.edit().putString("window_size", raw).commit()
            assertNull("'$raw' must fall back, not open something unusable", manager.getWindowSize())
        }
    }
}

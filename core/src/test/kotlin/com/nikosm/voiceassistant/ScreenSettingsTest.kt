package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen policy in the settings file (PLAN.md §10/M19): off by default, global and
 * per-persona, name-keyed so a rename carries it and a delete drops it — and **never in a
 * backup**, because permission to look at the screen is a grant, not conversation content. The
 * same rule the filesystem mode, MCP trust and the terminal policy follow.
 */
class ScreenSettingsTest {

    private val store = InMemoryStore()
    private val manager = SettingsManager(FakeAppEnv(), store, NoImageCodec)

    private fun persona(name: String = "A") = Persona(
        name = name,
        themeColor = Color(0xFF7C5CE0),
        model = "[Home] model",
        systemPrompt = "prompt",
    )

    @Test
    fun `screen access is off on a fresh install`() {
        assertEquals(ScreenToolMode.OFF, manager.getScreenMode())
        assertNull("a persona that was never configured follows the global", manager.getPersonaScreenMode("A"))
    }

    @Test
    fun `the global policy round-trips`() {
        manager.saveScreenMode(ScreenToolMode.ASK)
        assertEquals(ScreenToolMode.ASK, manager.getScreenMode())

        manager.saveScreenMode(ScreenToolMode.OFF)
        assertEquals(ScreenToolMode.OFF, manager.getScreenMode())
    }

    @Test
    fun `a persona override round-trips and null removes it`() {
        manager.savePersonaScreenMode("A", ScreenToolMode.ASK)
        assertEquals(ScreenToolMode.ASK, manager.getPersonaScreenMode("A"))

        manager.savePersonaScreenMode("A", null)
        assertNull(manager.getPersonaScreenMode("A"))
    }

    @Test
    fun `an unreadable persona value resolves to off, never to the global`() {
        store.edit().putString("persona_screen_mode_A", "LOOK_MAYBE").commit()

        assertEquals(
            "a corrupt byte must only ever narrow access",
            ScreenToolMode.OFF,
            manager.getPersonaScreenMode("A"),
        )
    }

    @Test
    fun `a rename carries the override and a delete drops it`() {
        manager.savePersonaScreenMode("A", ScreenToolMode.ASK)

        manager.migratePersonaScreenMode("A", "B")

        assertNull("the old key is gone", manager.getPersonaScreenMode("A"))
        assertEquals(ScreenToolMode.ASK, manager.getPersonaScreenMode("B"))

        manager.removePersonaScreenMode("B")
        assertNull(manager.getPersonaScreenMode("B"))
    }

    @Test
    fun `deleting a persona history also drops its screen grant`() {
        manager.savePersonaScreenMode("A", ScreenToolMode.ASK)

        manager.deletePersonaHistory("A")

        assertNull(manager.getPersonaScreenMode("A"))
    }

    @Test
    fun `a backup never carries a screen grant`() {
        manager.savePersonas(listOf(persona("A")))
        manager.saveScreenMode(ScreenToolMode.ASK)
        manager.savePersonaScreenMode("A", ScreenToolMode.ASK)

        val exported = manager.exportBackup()

        val restored = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        assertTrue(restored.importBackup(exported))
        assertEquals("the global policy is a grant, not content", ScreenToolMode.OFF, restored.getScreenMode())
        assertNull(restored.getPersonaScreenMode("A"))
    }

    @Test
    fun `a restore sweeps a grant left behind for a persona it did not bring back`() {
        val backupSource = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        backupSource.savePersonas(listOf(persona("A")))
        val backup = backupSource.exportBackup()

        manager.savePersonaScreenMode("Ghost", ScreenToolMode.ASK)
        manager.savePersonas(listOf(persona("A")))
        assertTrue(manager.importBackup(backup))

        assertNull(
            "a future persona named Ghost must not inherit this grant",
            manager.getPersonaScreenMode("Ghost"),
        )
    }
}

package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal policy in the settings file (PLAN.md §10/M16): off by default, global and
 * per-persona, name-keyed so a rename carries it and a delete drops it — and **never in a
 * backup**, because a grant to run commands is not conversation content, the same rule the
 * filesystem mode and MCP trust follow.
 */
class CommandSettingsTest {

    private val store = InMemoryStore()
    private val manager = SettingsManager(FakeAppEnv(), store, NoImageCodec)

    private fun persona(name: String = "A") = Persona(
        name = name,
        themeColor = Color(0xFF7C5CE0),
        model = "[Home] model",
        systemPrompt = "prompt",
    )

    @Test
    fun `commands are off on a fresh install`() {
        assertEquals(CommandToolMode.OFF, manager.getCommandMode())
        assertNull("a persona that was never configured follows the global", manager.getPersonaCommandMode("A"))
    }

    @Test
    fun `the global policy round-trips`() {
        manager.saveCommandMode(CommandToolMode.ASK)
        assertEquals(CommandToolMode.ASK, manager.getCommandMode())

        manager.saveCommandMode(CommandToolMode.OFF)
        assertEquals(CommandToolMode.OFF, manager.getCommandMode())
    }

    @Test
    fun `a persona override round-trips and null removes it`() {
        manager.savePersonaCommandMode("A", CommandToolMode.ASK)
        assertEquals(CommandToolMode.ASK, manager.getPersonaCommandMode("A"))

        manager.savePersonaCommandMode("A", null)
        assertNull(manager.getPersonaCommandMode("A"))
    }

    @Test
    fun `an unreadable persona value resolves to off, never to the global`() {
        store.edit().putString("persona_command_mode_A", "ASK_FIRST_MAYBE").commit()

        assertEquals(
            "a corrupt byte must only ever narrow access",
            CommandToolMode.OFF,
            manager.getPersonaCommandMode("A")
        )
    }

    @Test
    fun `a rename carries the override and a delete drops it`() {
        manager.savePersonaCommandMode("A", CommandToolMode.ASK)

        manager.migratePersonaCommandMode("A", "B")

        assertNull("the old key is gone", manager.getPersonaCommandMode("A"))
        assertEquals(CommandToolMode.ASK, manager.getPersonaCommandMode("B"))

        manager.removePersonaCommandMode("B")
        assertNull(manager.getPersonaCommandMode("B"))
    }

    @Test
    fun `deleting a persona history also drops its command grant`() {
        manager.savePersonaCommandMode("A", CommandToolMode.ASK)

        manager.deletePersonaHistory("A")

        assertNull(manager.getPersonaCommandMode("A"))
    }

    @Test
    fun `a backup never carries a command grant`() {
        manager.savePersonas(listOf(persona("A")))
        manager.saveCommandMode(CommandToolMode.ASK)
        manager.savePersonaCommandMode("A", CommandToolMode.ASK)

        val exported = manager.exportBackup()

        val restored = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        assertTrue(restored.importBackup(exported))
        assertEquals("the global policy is a grant, not content", CommandToolMode.OFF, restored.getCommandMode())
        assertNull(restored.getPersonaCommandMode("A"))
    }

    @Test
    fun `a restore sweeps a grant left behind for a persona it did not bring back`() {
        val backupSource = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        backupSource.savePersonas(listOf(persona("A")))
        val backup = backupSource.exportBackup()

        manager.savePersonaCommandMode("Ghost", CommandToolMode.ASK)
        manager.savePersonas(listOf(persona("A")))
        assertTrue(manager.importBackup(backup))

        assertNull(
            "a future persona named Ghost must not inherit this grant",
            manager.getPersonaCommandMode("Ghost")
        )
    }
}

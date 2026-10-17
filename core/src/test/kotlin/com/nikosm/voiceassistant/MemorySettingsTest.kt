package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory in the settings file: off by default, round-tripped, capped, moved on rename, removed with
 * the persona, and carried by a backup (entries and switch, unlike a filesystem path or a grant).
 */
class MemorySettingsTest {

    private val store = InMemoryStore()
    private val manager = SettingsManager(FakeAppEnv(), store, NoImageCodec)

    private fun persona(name: String = "A") = Persona(
        name = name,
        themeColor = Color(0xFF7C5CE0),
        model = "model",
        systemPrompt = "prompt",
    )

    @Test
    fun `memory is off and empty by default`() {
        assertFalse(manager.isPersonaMemoryEnabled("A"))
        assertTrue(manager.getPersonaMemory("A").isEmpty())
    }

    @Test
    fun `notes and the switch round-trip`() {
        manager.savePersonaMemory("A", listOf("first", "  second  ", ""))
        manager.setPersonaMemoryEnabled("A", true)

        assertEquals(listOf("first", "second"), manager.getPersonaMemory("A"))
        assertTrue(manager.isPersonaMemoryEnabled("A"))

        manager.setPersonaMemoryEnabled("A", false)
        assertFalse(manager.isPersonaMemoryEnabled("A"))
    }

    @Test
    fun `unreadable JSON reads as no notes`() {
        store.edit().putString("persona_memory_A", "{ not json").commit()

        assertTrue(manager.getPersonaMemory("A").isEmpty())
    }

    @Test
    fun `the store caps the count defensively`() {
        manager.savePersonaMemory("A", List(MEMORY_MAX_ENTRIES + 20) { "note $it" })

        assertEquals(MEMORY_MAX_ENTRIES, manager.getPersonaMemory("A").size)
    }

    @Test
    fun `a rename moves both keys and a delete removes them`() {
        manager.savePersonaMemory("A", listOf("note"))
        manager.setPersonaMemoryEnabled("A", true)

        manager.migratePersonaMemory("A", "B")

        assertTrue(manager.getPersonaMemory("A").isEmpty())
        assertFalse(manager.isPersonaMemoryEnabled("A"))
        assertEquals(listOf("note"), manager.getPersonaMemory("B"))
        assertTrue(manager.isPersonaMemoryEnabled("B"))

        manager.removePersonaMemory("B")
        assertTrue(manager.getPersonaMemory("B").isEmpty())
        assertFalse(manager.isPersonaMemoryEnabled("B"))
    }

    @Test
    fun `a backup carries the notes and the switch for the personas it carries`() {
        manager.savePersonas(listOf(persona("A")))
        manager.savePersonaMemory("A", listOf("the user's name is Nikos"))
        manager.setPersonaMemoryEnabled("A", true)

        val exported = manager.exportBackup()
        val restored = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        assertTrue(restored.importBackup(exported))

        assertEquals(listOf("the user's name is Nikos"), restored.getPersonaMemory("A"))
        assertTrue(restored.isPersonaMemoryEnabled("A"))
    }

    @Test
    fun `an old backup without memory does not wipe memory this install already has`() {
        manager.savePersonas(listOf(persona("A")))
        manager.savePersonaMemory("A", listOf("kept"))

        // A backup decoded from before the feature has the defaulted empty maps.
        val oldBackup = manager.exportBackup()
        val withoutMemory = oldBackup
            .replace(Regex(",\"personaMemories\":\\{[^}]*\\}"), "")
            .replace(Regex(",\"personaMemoryEnabled\":\\[[^]]*\\]"), "")
        assertTrue(withoutMemory != oldBackup)

        assertTrue(manager.importBackup(withoutMemory))
        assertEquals(listOf("kept"), manager.getPersonaMemory("A"))
    }
}

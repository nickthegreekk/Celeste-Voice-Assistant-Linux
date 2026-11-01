package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M18's storage half: the shared library, the persona side map and the used-context ledger — the
 * three records that cannot live on `Persona` or `ChatMessage` because `Models.kt` is a COPY file.
 */
class LorebookSettingsTest {

    private val store = InMemoryStore()
    private val manager = SettingsManager(FakeAppEnv(), store, NoImageCodec)

    private fun persona(name: String = "A") = Persona(
        name = name,
        themeColor = Color(0xFF7C5CE0),
        model = "model",
        systemPrompt = "prompt",
    )

    private fun book(id: String = "book", name: String = "World") = Lorebook(
        id = id,
        name = name,
        entries = listOf(
            LorebookEntry(id = "entry", name = "Vee", content = "Vee is the user's nickname.", keywords = listOf("Vee")),
        ),
    )

    @Test
    fun `the library round-trips and unreadable JSON reads empty`() {
        manager.saveLorebooks(listOf(book()))

        assertEquals(listOf(book()), manager.getLorebooks())

        store.edit().putString("lorebooks_v1", "{ not json").commit()
        assertTrue(manager.getLorebooks()!!.isEmpty())
    }

    @Test
    fun `the persona side map round-trips, migrates and is removed`() {
        manager.savePersonaLorebooks("A", listOf("book", " other ", "book", ""))
        assertEquals(listOf("book", "other"), manager.getPersonaLorebooks("A"))

        manager.migratePersonaLorebooks("A", "B")
        assertTrue(manager.getPersonaLorebooks("A").isEmpty())
        assertEquals(listOf("book", "other"), manager.getPersonaLorebooks("B"))

        manager.removePersonaLorebooks("B")
        assertTrue(manager.getPersonaLorebooks("B").isEmpty())
    }

    @Test
    fun `the used-context ledger round-trips and keeps the newest bounded entries`() {
        manager.savePersonaUsedContext(
            "A",
            mapOf("10" to listOf(UsedContextItem("lorebook", "World / Vee", "keyword: Vee"))),
        )

        val loaded = manager.getPersonaUsedContext("A")
        assertEquals(1, loaded.size)
        assertEquals("keyword: Vee", loaded["10"]!!.single().detail)

        val many = (1..600).associate { it.toString() to listOf(UsedContextItem("skill", "s$it")) }
        manager.savePersonaUsedContext("A", many)
        val capped = manager.getPersonaUsedContext("A")
        assertEquals(USED_CONTEXT_MAX_ENTRIES, capped.size)
        assertEquals("101", capped.keys.minOf { it.toInt() }.toString())
        assertEquals("600", capped.keys.maxOf { it.toInt() }.toString())
    }

    @Test
    fun `a backup carries the library, the enablement and the ledger`() {
        val book = book()
        manager.savePersonas(listOf(persona("A")))
        manager.saveLorebooks(listOf(book))
        manager.savePersonaLorebooks("A", listOf(book.id))
        manager.savePersonaUsedContext(
            "A",
            mapOf("123" to listOf(UsedContextItem("lorebook", "World / Vee", "always"))),
        )

        val exported = manager.exportBackup()

        val restoredStore = InMemoryStore()
        val restored = SettingsManager(FakeAppEnv(), restoredStore, NoImageCodec)
        assertTrue(restored.importBackup(exported))

        assertEquals(listOf(book), restored.getLorebooks())
        assertEquals(listOf(book.id), restored.getPersonaLorebooks("A"))
        assertEquals(
            "always",
            restored.getPersonaUsedContext("A")["123"]!!.single().detail,
        )
    }

    @Test
    fun `an import prunes a persona reference the backup did not carry`() {
        manager.savePersonas(listOf(persona("A")))
        manager.saveLorebooks(emptyList())
        manager.savePersonaLorebooks("A", listOf("gone"))

        val exported = manager.exportBackup()

        val restoredStore = InMemoryStore()
        val restored = SettingsManager(FakeAppEnv(), restoredStore, NoImageCodec)
        assertTrue(restored.importBackup(exported))

        assertFalse("gone" in restored.getPersonaLorebooks("A"))
    }
}

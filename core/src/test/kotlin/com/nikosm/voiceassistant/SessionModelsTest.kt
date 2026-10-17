package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session model override's pure layer (PLAN.md §10/M11).
 *
 * The contract these tests pin: an override replaces **only** the model on a copy, is keyed per
 * persona, is cleared by null or blank, and the header's picker lists the server the persona
 * already uses — not the first one configured.
 */
class SessionModelsTest {

    private fun persona(model: String = "local-model") = Persona(
        name = "A",
        themeColor = Color(0xFF7C5CE0),
        model = model,
        systemPrompt = "prompt",
        backendUrl = "",
    )

    @Test
    fun `no override leaves the persona object untouched`() {
        val original = persona()

        assertSame(original, withSessionModel(emptyMap(), original))
    }

    @Test
    fun `an override replaces the model and nothing else`() {
        val original = persona()

        val copy = withSessionModel(mapOf("A" to "[server] big-model"), original)

        assertEquals("[server] big-model", copy.model)
        assertEquals("A", copy.name)
        assertEquals("prompt", copy.systemPrompt)
        assertEquals(original.themeColor, copy.themeColor)
        assertEquals("local-model", original.model)
    }

    @Test
    fun `overrides are per persona`() {
        val copy = withSessionModel(mapOf("SomeoneElse" to "[s] x"), persona())

        assertEquals("local-model", copy.model)
    }

    @Test
    fun `setting and clearing`() {
        var overrides = updatedSessionModels(emptyMap(), "A", "[s] x")
        assertEquals(mapOf("A" to "[s] x"), overrides)

        overrides = updatedSessionModels(overrides, "A", null)
        assertTrue(overrides.isEmpty())

        overrides = updatedSessionModels(overrides, "A", "   ")
        assertTrue("a blank selection is a clear", overrides.isEmpty())
    }

    @Test
    fun `an empty persona name cannot enter the map`() {
        assertTrue(updatedSessionModels(emptyMap(), " ", "m").isEmpty())
    }

    @Test
    fun `the picker lists the persona's own server`() {
        val bases = listOf(ServerConfig("one", "http://one:11434"), ServerConfig("two", "http://two:11434"))

        assertEquals("two", sessionModelServer(persona("[two] m"), bases)?.name)
        assertEquals("one", sessionModelServer(persona("m").copy(backendUrl = "http://one:11434/"), bases)?.name)
        assertEquals("one", sessionModelServer(persona("m"), bases)?.name)
        assertNull(sessionModelServer(persona("m"), emptyList()))
    }
}

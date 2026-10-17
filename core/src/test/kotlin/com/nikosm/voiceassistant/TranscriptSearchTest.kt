package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transcript search (PLAN.md §10/M13): the snippet rule, and the scan over the three transcript
 * kinds the settings file holds.
 *
 * The cases pinned here are the ones a user would notice: matching is case-insensitive, a hit
 * carries readable context and an ellipsis where it was cut, whitespace collapses to one line,
 * results are newest first, and a blank query searches nothing.
 */
class TranscriptSearchTest {

    private fun persona(name: String) = Persona(
        name = name,
        themeColor = Color(0xFF7C5CE0),
        model = "model",
        systemPrompt = "prompt",
    )

    @Test
    fun `a match in a long message is cut with an ellipsis on both sides`() {
        val text = "x".repeat(60) + " kalimera " + "y".repeat(60)

        val snippet = transcriptSnippet(text, "KALIMERA", contextChars = 10)!!

        assertTrue(snippet, snippet.startsWith("…"))
        assertTrue(snippet, snippet.endsWith("…"))
        assertTrue(snippet, snippet.contains("kalimera"))
    }

    @Test
    fun `a short message needs no ellipsis`() {
        assertEquals("Hello there", transcriptSnippet("Hello there", "there"))
    }

    @Test
    fun `newlines and runs of whitespace collapse to one line`() {
        val snippet = transcriptSnippet("first\nsecond   KALIMERA\nthird", "kalimera")!!

        assertFalse(snippet.contains("\n"))
        assertTrue(snippet, snippet.contains("KALIMERA", ignoreCase = true))
    }

    @Test
    fun `no match and a blank query are null`() {
        assertNull(transcriptSnippet("hello", "world"))
        assertNull(transcriptSnippet("text", "   "))
    }

    @Test
    fun `search covers persona, room and main chat, newest first`() {
        val manager = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        manager.savePersonas(listOf(persona("Celeste"), persona("Work")))
        manager.savePersonaMessages(
            "Celeste",
            listOf(
                ChatMessage(role = "user", text = "Kalimera, how are you", timestamp = 100),
                ChatMessage(role = "assistant", text = "Fine thanks", timestamp = 200),
            ),
        )
        manager.saveRooms(listOf(Room(id = "r1", name = "Book club")))
        manager.saveRoomMessages(
            "r1",
            listOf(RoomMessage(speakerName = RoomMessage.USER_SPEAKER, text = "the KALIMERA book", timestamp = 300)),
        )
        manager.saveMessages(listOf(ChatMessage(role = "user", text = "global kalimera", timestamp = 50)))

        val hits = manager.searchTranscripts("kalimera")

        assertEquals(
            listOf(TranscriptKind.ROOM, TranscriptKind.PERSONA, TranscriptKind.CHAT),
            hits.map { it.kind },
        )
        assertEquals("Book club", hits[0].sourceName)
        assertEquals("user", hits[0].speaker)
        assertTrue(hits[0].snippet, hits[0].snippet.contains("KALIMERA"))
        assertEquals("Celeste", hits[1].sourceName)
        assertEquals("user", hits[1].speaker)
    }

    @Test
    fun `the limit keeps the newest hits`() {
        val manager = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        manager.savePersonas(listOf(persona("Celeste")))
        manager.savePersonaMessages(
            "Celeste",
            listOf(
                ChatMessage(role = "user", text = "kalimera one", timestamp = 100),
                ChatMessage(role = "user", text = "kalimera two", timestamp = 200),
                ChatMessage(role = "user", text = "kalimera three", timestamp = 300),
            ),
        )

        val hits = manager.searchTranscripts("kalimera", limit = 2)

        assertEquals(2, hits.size)
        assertTrue(hits[0].snippet, hits[0].snippet.contains("three"))
        assertTrue(hits[1].snippet, hits[1].snippet.contains("two"))
    }

    @Test
    fun `reasoning is not searched, and a blank query returns nothing`() {
        val manager = SettingsManager(FakeAppEnv(), InMemoryStore(), NoImageCodec)
        manager.savePersonas(listOf(persona("Celeste")))
        manager.savePersonaMessages(
            "Celeste",
            listOf(ChatMessage(role = "assistant", text = "the answer", reasoning = "kalimera in reasoning", timestamp = 100)),
        )

        assertTrue(manager.searchTranscripts("kalimera").isEmpty())
        assertTrue(manager.searchTranscripts("  ").isEmpty())
    }
}

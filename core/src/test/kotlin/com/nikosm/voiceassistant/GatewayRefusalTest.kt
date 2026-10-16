package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence a gateway synthesis with no audio carries, and the words in it that make it
 * actionable.
 *
 * The 2026-10-16 run is the reason this exists. Three replies were never spoken and the log said
 * "chunk 0 could not be synthesised" for each — no route, no engine, no language, and the gateway's
 * own explanation ("Russian has no TTS voice available — text only", because the reply quoted one
 * Russian phrase and the on-device script heuristic is right to call that Russian) sitting in a
 * DEBUG line that `java.util.logging` never prints.
 *
 * So what is asserted here is what a reader needs to act: that the failure is a *gateway* one
 * rather than the local engine's, what was asked of it, and — verbatim, because it is the server's
 * own diagnosis and it may name something no code here knows — what came back. The bodies below
 * are the gateway's, measured on 2026-10-16 rather than invented.
 */
class GatewayRefusalTest {

    @Test
    fun `the measured refusal names the route, what was asked, and the gateway's own words`() {
        assertEquals(
            "gateway TTS produced no audio (engine kokoro, voice af_heart, language Russian) — " +
                "Russian has no TTS voice available — text only.",
            gatewayRefusal(
                detail = "Russian has no TTS voice available — text only.",
                language = "Russian",
                engine = "kokoro",
                voice = "af_heart",
            ),
        )
    }

    @Test
    fun `an engine with no voice of its own leaves the voice out rather than inventing one`() {
        val refusal = gatewayRefusal("the request failed", language = "English", engine = "piper", voice = null)

        assertEquals("gateway TTS produced no audio (engine piper, language English) — the request failed", refusal)
    }

    @Test
    fun `a note that spans lines arrives as one line`() {
        // A note is a field in a JSON body and can hold anything; this message is read from a
        // terminal and kept by journald, where a second line of it would look like a second event.
        val refusal = gatewayRefusal(
            detail = "Voice unavailable for this language.\nTry a different voice.",
            language = "Greek",
            engine = "kokoro",
            voice = "af_heart",
        )

        assertTrue(refusal, "Voice unavailable for this language. Try a different voice." in refusal)
        assertEquals("the refusal must be one line", 1, refusal.lines().size)
    }

    @Test
    fun `a server that dumps a paragraph does not push a screenful into the log`() {
        // The same cap the HTTP error reasons use, and for the same reason: the server's words are
        // shown, but a stack trace or an essay in `note` must not become the log line.
        val refusal = gatewayRefusal(
            detail = "x".repeat(1_000),
            language = "English",
            engine = "kokoro",
            voice = "af_heart",
        )

        assertTrue("the refusal must be capped", refusal.length < 400)
        assertTrue("the cap is marked rather than silent", refusal.endsWith("…"))
    }

    @Test
    fun `the route is named first, because it is the first thing being ruled out`() {
        // A reply goes unspoken through the gateway or through the local engine, and the two are
        // repaired in different places — so the word has to survive whatever else is in the line.
        val refusal = gatewayRefusal("no audio", language = "English", engine = "kokoro", voice = "af_heart")

        assertTrue(refusal.startsWith("gateway TTS"))
        assertTrue(
            "the engine is named after the route, not instead of it",
            refusal.indexOf("engine kokoro") > refusal.indexOf("gateway TTS"),
        )
    }
}

package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Live HUD's decisions, separated from its drawing.
 *
 * The drawing itself is exercised by [LiveHudRenderTest]; this class covers what the frame loop
 * decides: the warm state colours, the two measurements, the fallback cadence and the level
 * smoothing.
 */
class LiveHudTest {

    @Test
    fun `each state has its own warm signal colour`() {
        val idle = liveHudAccent(AssistantState.IDLE)
        val listening = liveHudAccent(AssistantState.LISTENING)
        val thinking = liveHudAccent(AssistantState.THINKING)
        val speaking = liveHudAccent(AssistantState.SPEAKING)

        // The reference image is the amber reactor, not a cool interface: every signal must be
        // warm, and no two states may collapse to the same colour.
        listOf(idle, listening, thinking, speaking).forEach { colour ->
            assertTrue("${colour.value.toString(16)} is not warm", colour.red >= colour.green && colour.green >= colour.blue)
        }
        assertNotEquals(idle, listening)
        assertNotEquals(listening, thinking)
        assertNotEquals(thinking, speaking)
    }

    @Test
    fun `the state labels are the HUD's vocabulary`() {
        assertEquals("STANDBY", liveHudStateLabel(AssistantState.IDLE))
        assertEquals("LISTENING", liveHudStateLabel(AssistantState.LISTENING))
        assertEquals("COMPUTING", liveHudStateLabel(AssistantState.THINKING))
        assertEquals("TRANSMITTING", liveHudStateLabel(AssistantState.SPEAKING))
    }

    @Test
    fun `listening and speaking read their own measurements`() {
        assertEquals(0.8f, liveHudLevel(AssistantState.LISTENING, 0.8f, 0.1f, 0.0), 0.0f)
        assertEquals(0.42f, liveHudLevel(AssistantState.SPEAKING, 0.9f, 0.42f, 0.0), 0.0f)
        // Both clamp an out-of-range producer rather than letting it paint past the ring.
        assertEquals(1f, liveHudLevel(AssistantState.LISTENING, 2f, 0f, 0.0), 0.0f)
        assertEquals(0f, liveHudLevel(AssistantState.LISTENING, -1f, 0f, 0.0), 0.0f)
    }

    @Test
    fun `idle and thinking do not react to a microphone they did not open`() {
        val idle = liveHudLevel(AssistantState.IDLE, micAmplitude = 1f, speechAmplitude = 1f, timeSeconds = 0.0)
        val thinking = liveHudLevel(AssistantState.THINKING, micAmplitude = 1f, speechAmplitude = 1f, timeSeconds = 0.0)
        assertEquals(0.08f, idle, 0.0001f)
        assertTrue("thinking is a decorative scan, in 0.04..0.55, was $thinking", thinking in 0.04f..0.55f)
    }

    @Test
    fun `the system-tts fallback is a bounded cadence, not a measurement claim`() {
        // speechAmplitude stays at zero for system TTS because there are no samples to measure.
        for (i in 0..40) {
            val t = i * 0.037
            val level = liveHudLevel(AssistantState.SPEAKING, 0f, 0f, t)
            assertTrue("fallback level at t=$t was $level", level in 0.06f..0.55f)
        }
    }

    @Test
    fun `the level meter attacks quickly and releases slowly`() {
        val meter = LevelMeter(attackPerSecond = 10f, releasePerSecond = 2f)
        assertEquals(0.5f, meter.next(1f, 0.05f), 0.0001f)
        assertEquals(1f, meter.next(1f, 0.05f), 0.0001f)
        assertEquals(0.8f, meter.next(0f, 0.1f), 0.0001f)
        assertEquals("a zero dt never moves the level", 0.8f, meter.next(2f, 0f), 0.0001f)
        assertEquals("a long frame is capped at 250 ms", 0.3f, meter.next(0f, 10f), 0.0001f)
    }

    @Test
    fun `the level meter clamps its target`() {
        val meter = LevelMeter(attackPerSecond = 100f, releasePerSecond = 100f)
        assertEquals(1f, meter.next(4f, 1f), 0.0f)
        assertEquals(0f, meter.next(-4f, 1f), 0.0f)
    }

    @Test
    fun `the history returns values newest first and wraps`() {
        val history = LevelHistory(4)
        history.push(0.1f)
        history.push(0.2f)
        history.push(0.3f)
        assertEquals(0.3f, history.at(0), 0.0f)
        assertEquals(0.2f, history.at(1), 0.0f)
        assertEquals(0.1f, history.at(2), 0.0f)
        assertEquals("a slot never written is silence", 0f, history.at(3), 0.0f)

        history.push(0.4f)
        history.push(0.5f)
        assertEquals(0.5f, history.at(0), 0.0f)
        assertEquals(0.4f, history.at(1), 0.0f)
        assertEquals(0.3f, history.at(2), 0.0f)
        assertEquals(0.2f, history.at(3), 0.0f)
    }
}

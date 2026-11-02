package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Live HUD's decisions, separated from its drawing.
 *
 * The drawing itself is exercised by [LiveHudRenderTest], which drives the test clock by hand
 * because the body owns an infinite frame loop. This class covers what the loop decides: the
 * state colours, the two measurements, the fallback cadence and the level smoothing.
 */
class LiveHudTest {

    private val persona = Color(0xFF4ADE80)

    @Test
    fun `each state has its own signal colour and speaking keeps the persona's`() {
        val idle = liveHudAccent(AssistantState.IDLE, persona)
        val listening = liveHudAccent(AssistantState.LISTENING, persona)
        val thinking = liveHudAccent(AssistantState.THINKING, persona)
        val speaking = liveHudAccent(AssistantState.SPEAKING, persona)

        assertEquals("SPEAKING is the persona's own colour", persona, speaking)
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

package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Live HUD's decisions, separated from its drawing.
 *
 * The drawing itself is exercised by [LiveHudRenderTest]; this class covers what the frame loop
 * decides: the persona-tinted state palette, the two measurements, the fallback cadence and the
 * level smoothing.
 */
class LiveHudTest {

    private fun luminance(color: Color): Float =
        0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

    @Test
    fun `each state is a distinct brightness of one persona hue`() {
        val theme = Color.Cyan
        val idle = liveHudAccent(AssistantState.IDLE, theme)
        val listening = liveHudAccent(AssistantState.LISTENING, theme)
        val thinking = liveHudAccent(AssistantState.THINKING, theme)
        val speaking = liveHudAccent(AssistantState.SPEAKING, theme)

        // The persona owns the hue; the state owns the brightness. A state that could only be
        // told apart by hue would put two meanings on one channel.
        listOf(idle, listening, thinking, speaking).forEach { colour ->
            assertTrue(
                "the cyan persona drifted to ${colour.value.toString(16)}",
                colour.green > colour.red && colour.blue > colour.red,
            )
        }
        assertTrue(luminance(idle) < luminance(listening))
        assertTrue(luminance(listening) < luminance(thinking))
        assertTrue(luminance(thinking) < luminance(speaking))
        assertNotEquals(idle, listening)
        assertNotEquals(listening, thinking)
        assertNotEquals(thinking, speaking)
    }

    @Test
    fun `a neutral persona stays neutral`() {
        val palette = liveHudPalette(Color(0xFF808080))
        listOf(palette.primary, palette.secondary).forEach { colour ->
            assertTrue("${colour.value.toString(16)} invented a hue", kotlin.math.abs(colour.red - colour.green) < 0.03f)
            assertTrue("${colour.value.toString(16)} invented a hue", kotlin.math.abs(colour.green - colour.blue) < 0.03f)
        }
        assertTrue("the grey cloud is too dark to see", luminance(palette.primary) > 0.55f)
    }

    @Test
    fun `a dark theme is lifted but keeps its hue`() {
        val dark = Color(0xFF101820)
        val palette = liveHudPalette(dark)
        assertTrue("a dark theme did not lift", luminance(palette.primary) > 0.35f)
        assertTrue("the lifted theme lost its cool hue", palette.primary.blue > palette.primary.green)
        assertTrue(palette.primary.green > palette.primary.red)
    }

    @Test
    fun `computing is the most turbulent state`() {
        assertTrue(liveHudEnergy(AssistantState.THINKING) > liveHudEnergy(AssistantState.SPEAKING))
        assertTrue(liveHudEnergy(AssistantState.SPEAKING) > liveHudEnergy(AssistantState.LISTENING))
        assertTrue(liveHudEnergy(AssistantState.LISTENING) > liveHudEnergy(AssistantState.IDLE))
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

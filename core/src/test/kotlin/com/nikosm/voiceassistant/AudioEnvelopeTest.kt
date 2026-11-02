package com.nikosm.voiceassistant

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The Live HUD's loudness curve, pinned without a sound server, a player or a TTS engine.
 *
 * The vectors are fabricated tones rather than real speech on purpose: AGENTS.md §5 forbids a
 * real transcript or a real output as a fixture, and a tone has an RMS a person can check by
 * hand — which is what makes the square-root mapping assertable instead of "looks about right".
 */
class AudioEnvelopeTest {

    // ------------------------------------------------------------------ pcm16Envelope

    @Test
    fun `silence is zero everywhere`() {
        val envelope = pcm16Envelope(pcm16(IntArray(1600)), 16000)
        assertEquals(2, envelope.levels.size)
        assertArrayEquals(floatArrayOf(0f, 0f), envelope.levels, 0.0f)
        assertEquals(100L, envelope.durationMs)
    }

    @Test
    fun `a full scale square wave reaches full scale`() {
        val samples = IntArray(800) { if (it % 2 == 0) 32767 else -32768 }
        val envelope = pcm16Envelope(pcm16(samples), 8000)
        assertEquals(2, envelope.levels.size)
        envelope.levels.forEach { level ->
            assertEquals("a ±full-scale square is RMS full scale", 1.0f, level, 0.005f)
        }
    }

    @Test
    fun `a half scale square maps through the square root`() {
        val samples = IntArray(400) { if (it % 2 == 0) 16384 else -16384 }
        val envelope = pcm16Envelope(pcm16(samples), 8000)
        // RMS 16384 -> 0.5 of full scale -> sqrt(0.5) = 0.7071.
        assertEquals(0.7071f, envelope.levels.single(), 0.005f)
    }

    @Test
    fun `a sine is measured by RMS rather than its peak`() {
        val rate = 8000
        val samples = IntArray(rate) { i ->
            (32767.0 * sin(2.0 * PI * 440.0 * i / rate)).toInt()
        }
        val envelope = pcm16Envelope(pcm16(samples), rate)
        // A full-scale sine's RMS is 1/sqrt(2); the square-root mapping turns that into ~0.8409.
        assertEquals(0.8409f, envelope.levels.average().toFloat(), 0.01f)
    }

    @Test
    fun `a partial final window still has a level`() {
        val rate = 8000
        val samples = IntArray(500) { if (it % 2 == 0) 16384 else -16384 }
        val envelope = pcm16Envelope(pcm16(samples), rate, windowMs = 50)
        // 400 samples per window, so 500 samples are two windows: 400 full and 100 partial —
        // the partial window keeps the same RMS because the tone is constant.
        assertEquals(2, envelope.levels.size)
        assertEquals(0.7071f, envelope.levels[1], 0.005f)
    }

    @Test
    fun `an empty buffer is an empty envelope rather than a crash`() {
        val envelope = pcm16Envelope(ByteArray(0), 16000)
        assertEquals(0, envelope.levels.size)
        assertEquals(0f, envelope.levelAt(0), 0.0f)
    }

    @Test
    fun `an invalid sample rate is an empty envelope`() {
        val envelope = pcm16Envelope(pcm16(IntArray(16)), 0)
        assertEquals(0, envelope.levels.size)
    }

    // ------------------------------------------------------------------ levelAt

    @Test
    fun `levelAt clamps before the start and after the end`() {
        val envelope = AudioEnvelope(floatArrayOf(0.1f, 0.5f, 0.9f), windowMs = 50)
        assertEquals(0.1f, envelope.levelAt(-1), 0.0f)
        assertEquals(0.1f, envelope.levelAt(0), 0.0f)
        assertEquals(0.1f, envelope.levelAt(49), 0.0f)
        assertEquals(0.5f, envelope.levelAt(50), 0.0f)
        assertEquals(0.5f, envelope.levelAt(99), 0.0f)
        assertEquals(0.9f, envelope.levelAt(100), 0.0f)
        assertEquals("past the end holds the last window", 0.9f, envelope.levelAt(10_000), 0.0f)
    }

    @Test
    fun `duration is the window count times the window`() {
        val envelope = AudioEnvelope(FloatArray(20), windowMs = 50)
        assertEquals(1000L, envelope.durationMs)
    }

    // ------------------------------------------------------------------ append

    @Test
    fun `append concatenates envelopes with the same window`() {
        val first = AudioEnvelope(floatArrayOf(0.1f, 0.2f), windowMs = 50)
        val second = AudioEnvelope(floatArrayOf(0.3f, 0.4f), windowMs = 50)
        val merged = first.append(second)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), merged.levels, 0.0f)
        assertEquals(0.3f, merged.levelAt(100), 0.0f)
    }

    @Test
    fun `append resamples an envelope with a different window`() {
        val first = AudioEnvelope(floatArrayOf(1f, 1f), windowMs = 50)
        val second = AudioEnvelope(floatArrayOf(0.5f, 0.5f), windowMs = 100)
        val merged = first.append(second)
        // The second envelope is 200 ms, which is four 50 ms buckets on the first's grid.
        assertEquals(6, merged.levels.size)
        assertEquals(0.5f, merged.levelAt(100), 0.0f)
        assertEquals(0.5f, merged.levelAt(200), 0.0f)
    }

    @Test
    fun `appending an empty envelope changes nothing`() {
        val envelope = AudioEnvelope(floatArrayOf(0.5f), windowMs = 50)
        val merged = envelope.append(AudioEnvelope(FloatArray(0), windowMs = 50))
        assertArrayEquals(floatArrayOf(0.5f), merged.levels, 0.0f)
    }

    // ------------------------------------------------------------------ WAV parsing

    @Test
    fun `a wav data chunk produces the same envelope as raw pcm`() {
        val rate = 8000
        val samples = IntArray(1600) { if (it % 2 == 0) 8192 else -8192 }
        val pcm = pcm16(samples)
        val wav = WavFile.header(pcm.size, rate) + pcm

        val raw = pcm16Envelope(pcm, rate)
        val parsed = wav16Envelope(wav)
        assertNotNull(parsed)
        assertEquals(raw.windowMs, parsed!!.windowMs)
        assertArrayEquals(raw.levels, parsed.levels, 0.0f)
    }

    @Test
    fun `a LIST chunk before data is skipped`() {
        val rate = 8000
        val samples = IntArray(800) { 8192 }
        val pcm = pcm16(samples)
        val wav = riff(fmtChunk(rate) + chunk("LIST", "INFOISFT".toByteArray()) + chunk("data", pcm))
        val parsed = wav16Envelope(wav)
        assertNotNull(parsed)
        assertTrue(parsed!!.levels.all { it > 0f })
    }

    @Test
    fun `a streaming data size of all ones uses the remaining bytes`() {
        val rate = 8000
        val pcm = pcm16(IntArray(800) { 8192 })
        val data = chunk("data", pcm).copyOf()
        // The data chunk's size field is four bytes at offset 4 inside the chunk.
        for (i in 4..7) data[i] = 0xFF.toByte()
        val wav = riff(fmtChunk(rate) + data)
        assertNotNull(wav16Envelope(wav))
    }

    @Test
    fun `a non-pcm wav is rejected`() {
        val pcm = pcm16(IntArray(800) { 8192 })
        val wav = riff(fmtChunk(8000, format = 3) + chunk("data", pcm))
        assertNull(wav16Envelope(wav))
    }

    @Test
    fun `an eight bit wav is rejected`() {
        val wav = riff(fmtChunk(8000, bits = 8) + chunk("data", ByteArray(800)))
        assertNull(wav16Envelope(wav))
    }

    @Test
    fun `malformed wav inputs are rejected`() {
        assertNull(wav16Envelope(ByteArray(0)))
        assertNull(wav16Envelope("RIFF".toByteArray()))
        assertNull(wav16Envelope("not a wav at all".toByteArray()))
        // RIFF/WAVE and a fmt chunk, but no data chunk.
        assertNull(wav16Envelope(riff(fmtChunk(8000))))
        // Zero sample rate.
        assertNull(wav16Envelope(riff(fmtChunk(0) + chunk("data", pcm16(IntArray(8))))))
    }

    @Test
    fun `the 22050 hz window is rounded to 50 milliseconds`() {
        val pcm = pcm16(IntArray(22050))
        val envelope = pcm16Envelope(pcm, 22050)
        assertEquals("one second at 22.05 kHz is twenty windows", 20, envelope.levels.size)
        assertEquals(50, envelope.windowMs)
    }

    // ------------------------------------------------------------------ helpers

    /** Signed 16-bit little-endian, the one layout every producer in this project writes. */
    private fun pcm16(samples: IntArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, sample ->
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun le16(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun le32(value: Int): ByteArray =
        ByteArray(4) { ((value shr (8 * it)) and 0xFF).toByte() }

    private fun chunk(id: String, body: ByteArray): ByteArray {
        val padding = if (body.size % 2 == 0) ByteArray(0) else byteArrayOf(0)
        return id.toByteArray(Charsets.ISO_8859_1) + le32(body.size) + body + padding
    }

    private fun fmtChunk(
        sampleRate: Int,
        channels: Int = 1,
        bits: Int = 16,
        format: Int = 1,
    ): ByteArray = chunk(
        "fmt ",
        le16(format) + le16(channels) + le32(sampleRate) +
            le32(sampleRate * channels * bits / 8) +
            le16(channels * bits / 8) + le16(bits),
    )

    private fun riff(parts: ByteArray): ByteArray {
        val body = "WAVE".toByteArray(Charsets.ISO_8859_1) + parts
        return "RIFF".toByteArray(Charsets.ISO_8859_1) + le32(body.size) + body
    }
}

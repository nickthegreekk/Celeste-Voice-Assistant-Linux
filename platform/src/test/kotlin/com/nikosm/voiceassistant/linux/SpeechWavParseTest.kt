package com.nikosm.voiceassistant.linux

import com.nikosm.voiceassistant.AppLogger
import com.nikosm.voiceassistant.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `parseSpeechWav` against synthetic streams.
 *
 * Every failure this function has is a parsing failure, and reaching one through a real `espeak-ng`
 * would need the package, a working engine and real speech — so the failures would go unexercised
 * on any machine that does not have all three. The bytes below are fabricated here rather than
 * committed as fixtures: AGENTS.md §5 draws the line at committing the user's actual audio, and a
 * header is easier to read as a constructor call than as a hexdump anyway. The one case that *is*
 * a measurement — the placeholder `data` size — carries the numbers it was measured with.
 */
class SpeechWavParseTest {

    private val log = RecordingLogger()

    @Test
    fun `a complete header parses, and the duration is the payload's`() {
        // 2205 frames at 22050 Hz = exactly 100 ms.
        val pcm = pcmBytes(frames = 2205)
        val samples = parseSpeechWav(wav(pcm = pcm), log)!!

        assertEquals(22050, samples.sampleRate)
        assertEquals(pcm.size, samples.pcm.size)
        assertEquals(100, samples.durationMs)
    }

    @Test
    fun `a placeholder data size means the rest of the stream`() {
        // Measured, not invented: `espeak-ng -v en --stdout "Hello from the local voice."` emits
        // 76284 bytes whose `data` chunk declares 2147479552 — 0x7FFFF000, a size stamped before
        // the synthesis ran and never patched, because stdout is a pipe and cannot be seeked back
        // into. 76240 bytes of payload follow the 44-byte header; at 22050 Hz mono that is 1.729 s.
        val pcm = pcmBytes(frames = 38120)
        val samples = parseSpeechWav(
            // The RIFF size is a placeholder too — the real header reads `RIFF$\xf0\xff\x7fWAVE` —
            // and setting it wrong here records that the parse never reads it: only `fmt ` and
            // `data` are consulted, so a stream that is honest about nothing except its chunk ids
            // still parses.
            wav(pcm = pcm, declaredDataSize = 0x7FFFF000, declaredRiffSize = 0x7FFFF024),
            log,
        )!!

        assertEquals(22050, samples.sampleRate)
        assertEquals(76240, samples.pcm.size)
        // 38120 frames at 22050 Hz is 1.7288 s, and `durationMs` truncates to whole milliseconds —
        // which is what the UI reveal's timing has always been built on, so it is asserted rather
        // than rounded here.
        assertEquals(1728, samples.durationMs)
        assertEquals(76284, 44 + samples.pcm.size)
    }

    @Test
    fun `an oversized size on a chunk that is not data is still refused`() {
        // The fix is not "trust any size that is too big". A `LIST` that claims to be longer than
        // the file is corruption, the walk stops on it, and the `data` behind it is never reached
        // — which is the pre-fix behaviour, deliberately kept for every chunk but this one.
        val bytes = wav(
            pcm = pcmBytes(frames = 32),
            precedingChunks = listOf(chunk("LIST", ByteArray(4), declaredSize = 1_000_000)),
        )

        assertNull(parseSpeechWav(bytes, log))
        assertTrue(log.errors.any { it.contains("no usable data chunk") })
    }

    @Test
    fun `an odd-sized chunk is skipped by its padded length`() {
        // RIFF pads an odd chunk to a word boundary, and the walk has to add that byte or it lands
        // one short of the next chunk id — where it reads garbage and stops. A five-byte chunk
        // exercises the branch a four-byte one would miss.
        val pcm = pcmBytes(frames = 64)
        val samples = parseSpeechWav(
            wav(pcm = pcm, precedingChunks = listOf(chunk("LIST", ByteArray(5)))),
            log,
        )!!

        assertEquals(64 * 2, samples.pcm.size)
    }

    @Test
    fun `stereo is refused rather than misplayed as noise`() {
        val samples = parseSpeechWav(wav(pcm = pcmBytes(frames = 64), channels = 2), log)

        assertNull(samples)
        assertTrue(log.errors.any { it.contains("2ch/16bit") })
    }

    @Test
    fun `a stream that is not RIFF at all is refused`() {
        assertNull(parseSpeechWav("OggS".toByteArray() + ByteArray(64), log))
        assertTrue(log.errors.any { it.contains("RIFF/WAVE") })
    }

    // ---------------------------------------------------------------- fixture builders

    /** `frames` of 16-bit mono audio. The pattern is arbitrary: nothing here is ever played. */
    private fun pcmBytes(frames: Int): ByteArray =
        ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 0 until frames) putShort((i % 256).toShort())
        }.array()

    private fun wav(
        pcm: ByteArray,
        sampleRate: Int = 22050,
        channels: Int = 1,
        bits: Int = 16,
        declaredDataSize: Int = pcm.size,
        declaredRiffSize: Int = -1,
        precedingChunks: List<ByteArray> = emptyList(),
    ): ByteArray {
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(1)                             // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * bits / 8) // byte rate
            putShort((channels * bits / 8).toShort()) // block align
            putShort(bits.toShort())
        }.array()

        val body = precedingChunks.sumOf { it.size } + chunk("fmt ", fmt).size +
            header("data", declaredDataSize).size + pcm.size
        return ByteBuffer.allocate(12 + body).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            // The real size unless a test is deliberately lying about it; see the placeholder case.
            putInt(if (declaredRiffSize >= 0) declaredRiffSize else body)
            put("WAVE".toByteArray())
            precedingChunks.forEach { put(it) }
            put(chunk("fmt ", fmt))
            put(header("data", declaredDataSize))
            put(pcm)
        }.array()
    }

    private fun chunk(id: String, body: ByteArray, declaredSize: Int = body.size): ByteArray =
        header(id, declaredSize) + body + if (body.size and 1 == 1) byteArrayOf(0) else ByteArray(0)

    private fun header(id: String, size: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(id.toByteArray())
            putInt(size)
        }.array()

    private class RecordingLogger : AppLogger {
        val errors = mutableListOf<String>()
        override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
            if (level == LogLevel.ERROR) errors.add(message)
        }
    }
}

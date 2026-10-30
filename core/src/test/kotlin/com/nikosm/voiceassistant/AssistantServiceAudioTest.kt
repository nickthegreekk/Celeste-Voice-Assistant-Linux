package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The portable third of upstream's `AssistantServiceAudio.kt` — the functions that read text and
 * bytes and need no platform at all, so they ported unchanged.
 *
 * **Upstream has no test for any of these three.** They arrived in M1.6/M1.7 with none to copy, so
 * everything below is new and each case is here because something depends on it:
 *
 *  - [cleanTextForTts] and [splitIntoTtsChunks] decide what the on-device engines actually say out
 *    loud. A regression here is audible — markdown read as "asterisk asterisk", a sentence split
 *    mid-way through "Dr. Smith", a decimal point treated as the end of a paragraph — and none of
 *    those throw, which is exactly why they need pinning rather than trusting.
 *  - [wavDurationSeconds] is one half of a clock. The progress fraction and the word-timestamp
 *    offsets are plotted against the same timeline, so a duration that is wrong by a factor makes
 *    the karaoke highlight drift progressively early across a long reply — a symptom that only
 *    appears after several chunks and would be blamed on the player.
 */
class AssistantServiceAudioTest {

    /**
     * A canonical 44-byte RIFF/WAVE header with [dataSize] bytes of (empty) payload after it.
     *
     * `byteRate` is passed explicitly rather than derived, because the function under test
     * deliberately prefers the header's own byteRate over computing one from the format fields —
     * and the case where it is zero is a branch it has to handle.
     */
    private fun wav(
        sampleRate: Int = 22_050,
        channels: Int = 1,
        bitsPerSample: Int = 16,
        byteRate: Int = 44_100,
        dataSize: Int = 44_100,
    ): ByteArray {
        val bytes = ByteArray(44 + dataSize)

        fun ascii(offset: Int, s: String) = s.forEachIndexed { i, c -> bytes[offset + i] = c.code.toByte() }
        fun le32(offset: Int, value: Int) {
            for (i in 0 until 4) bytes[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
        fun le16(offset: Int, value: Int) {
            for (i in 0 until 2) bytes[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }

        ascii(0, "RIFF"); le32(4, 36 + dataSize); ascii(8, "WAVE")
        ascii(12, "fmt "); le32(16, 16); le16(20, 1)
        le16(22, channels); le32(24, sampleRate); le32(28, byteRate)
        le16(32, channels * bitsPerSample / 8); le16(34, bitsPerSample)
        ascii(36, "data"); le32(40, dataSize)
        return bytes
    }

    // ------------------------------------------------------------------ wavDurationSeconds

    @Test
    fun `a canonical header gives the duration from its own byteRate`() {
        // 44100 bytes at 44100 bytes/second — one second of audio.
        assertEquals(1.0, wavDurationSeconds(wav()), 1e-9)
    }

    @Test
    fun `the header's byteRate wins over the format fields`() {
        // 8000 Hz, 2 channels, 16 bits would compute to 32000 bytes/sec, but the header claims
        // 16000 — the function must believe the header, because that is what the encoder wrote.
        val bytes = wav(sampleRate = 8_000, channels = 2, bitsPerSample = 16, byteRate = 16_000, dataSize = 32_000)
        assertEquals(2.0, wavDurationSeconds(bytes), 1e-9)
    }

    @Test
    fun `a zero byteRate falls back to channels times bits per sample`() {
        // With byteRate = 0 the rate is sampleRate x channels x bits / 8 = 8000 x 1 x 16 / 8 =
        // 16000 bytes per second, so 400 bytes is 0.025 s. The previous version of this test
        // expected 200.0, because the fallback then omitted the sample rate and divided by
        // bytes per FRAME (2) instead of bytes per SECOND; that value was the bug, not a contract.
        val bytes = wav(sampleRate = 8_000, channels = 1, bitsPerSample = 16, byteRate = 0, dataSize = 400)
        assertEquals(0.025, wavDurationSeconds(bytes), 1e-9)
    }

    @Test
    fun `anything that is not a RIFF WAVE file is zero rather than a guess`() {
        val notWav = ByteArray(100) { 0x41 }
        assertEquals(0.0, wavDurationSeconds(notWav), 1e-9)
    }

    @Test
    fun `a buffer shorter than the header is zero`() {
        assertEquals(0.0, wavDurationSeconds(ByteArray(43)), 1e-9)
        assertEquals(0.0, wavDurationSeconds(ByteArray(0)), 1e-9)
    }

    @Test
    fun `a header claiming no audio data is zero`() {
        assertEquals(0.0, wavDurationSeconds(wav(dataSize = 0)), 1e-9)
    }

    // -------------------------------------------------------------------- cleanTextForTts

    @Test
    fun `bold and italic markers are stripped but their text is kept`() {
        assertEquals("This is bold and italic.", cleanTextForTts("This is **bold** and *italic*."))
    }

    @Test
    fun `heading markers and backticks are removed`() {
        assertEquals("Title\nbody text\n", cleanTextForTts("## Title\n`body` ```text```\n"))
    }

    @Test
    fun `both ASCII and unicode arrows become spaces`() {
        // eSpeak reads them literally — "hyphen greater than" — so they must not survive.
        assertEquals("A B", cleanTextForTts("A -> B"))
        assertEquals("A B", cleanTextForTts("A → B"))
    }

    @Test
    fun `astral-plane emoji are removed by code point, not by surrogate pair`() {
        // This is the test that caught a silent platform difference. Upstream writes the astral
        // half of the emoji strip as the surrogate-pair class `[\uD83C-\uD83E][\uDC00-\uDFFF]`,
        // which works on Android's ICU-backed engine and matches NOTHING on the JVM — OpenJDK
        // pairs a well-formed surrogate pair into one code point before a character class sees
        // it. Nothing threw; the strip simply did nothing, and the emoji would have reached
        // eSpeak and speech-dispatcher, which is the exact symptom the line exists to prevent.
        // The port rewrites it as the code-point range the surrogate class denotes, and these
        // three cases are what prove the rewrite covers it.
        assertEquals("Nice one indeed", cleanTextForTts("Nice one 😀 indeed"))
        assertEquals("Go now", cleanTextForTts("Go 🚀 now"))
        assertEquals("Flag here", cleanTextForTts("Flag 🇬🇷 here"))
    }

    @Test
    fun `BMP symbols are removed`() {
        assertEquals("Done and dusted", cleanTextForTts("Done ✅ and dusted"))
    }

    @Test
    fun `ordinary prose is untouched by the emoji strip`() {
        // The other half of the contract: the strip must remove emoji and nothing else. A class
        // widened too far would eat accented letters and CJK, which is worse than the bug.
        assertEquals("The quick brown fox.", cleanTextForTts("The quick brown fox."))
        assertEquals("Καλημέρα, κόσμε.", cleanTextForTts("Καλημέρα, κόσμε."))
    }

    @Test
    fun `runs of newlines collapse to a single blank line`() {
        assertEquals("a\n\nb", cleanTextForTts("a\n\n\n\n\nb"))
    }

    @Test
    fun `runs of spaces left by the removals collapse to one`() {
        assertEquals("A B", cleanTextForTts("A  ->   B"))
    }

    @Test
    fun `ordinary prose passes through unchanged`() {
        val prose = "The quick brown fox jumps over the lazy dog."
        assertEquals(prose, cleanTextForTts(prose))
    }

    // ------------------------------------------------------------------ splitIntoTtsChunks

    @Test
    fun `blank input produces no chunks`() {
        assertEquals(emptyList<String>(), splitIntoTtsChunks(""))
        assertEquals(emptyList<String>(), splitIntoTtsChunks("   \n  "))
    }

    @Test
    fun `text shorter than the minimum is one chunk`() {
        assertEquals(listOf("Hello there."), splitIntoTtsChunks("Hello there."))
    }

    @Test
    fun `sentences accumulate past the minimum, and a short tail is still emitted`() {
        // Minimum set explicitly so the arithmetic is visible. Two sentences of 24 and 25
        // characters: neither clears 30 alone, so they merge into one chunk — which is the whole
        // point, since a five-word sentence paying its own HTTP round trip is what the minimum
        // exists to prevent. "End." is 4 characters, well under the minimum, and is emitted
        // anyway: the tail is the end of the response, and dropping it would swallow the last
        // thing the model said with nothing to show it had.
        val chunks = splitIntoTtsChunks(
            "First sentence is here. Second sentence is here. End.",
            minChunkChars = 30,
        )
        assertEquals(
            listOf("First sentence is here. Second sentence is here.", "End."),
            chunks,
        )
    }

    @Test
    fun `with the default minimum a short reply is a single chunk`() {
        // The same text against the real 80-character minimum stays whole, so the previous test's
        // split is the minimum's doing and not something the splitter does unconditionally.
        val chunks = splitIntoTtsChunks("First sentence is here. Second sentence is here. End.")
        assertEquals(1, chunks.size)
    }

    @Test
    fun `an abbreviation is not treated as the end of a sentence`() {
        // "Dr." would otherwise split, and the two halves would be synthesized as separate
        // utterances with an audible break in the middle of a name.
        val chunks = splitIntoTtsChunks("Dr. Smith went home early today and said nothing more about it.")
        assertEquals(1, chunks.size)
    }

    @Test
    fun `a decimal point is not treated as the end of a sentence`() {
        val chunks = splitIntoTtsChunks("Pi is 3.14159 and that is all you need to know about it today.")
        assertEquals(1, chunks.size)
    }

    @Test
    fun `every chunk is non-blank`() {
        val text = "First sentence. Second sentence. Third one. Fourth one. Fifth one."
        assertTrue(splitIntoTtsChunks(text).none { it.isBlank() })
    }

    @Test
    fun `a playback is current only under the generation it started with`() {
        assertTrue(isPlaybackGenerationCurrent(5, 5))
        assertTrue(!isPlaybackGenerationCurrent(6, 5))
    }

    @Test
    fun `a numbered list marker is not split from its text`() {
        val chunks = splitIntoTtsChunks("1. Open the settings and turn the option on now. 2. Save it and close the window.", minChunkChars = 10)
        assertTrue(chunks.none { it.trim() == "1." || it.trim() == "2." })
    }

    @Test
    fun `greek sentences are split after the minimum`() {
        val chunks = splitIntoTtsChunks("Καλημέρα σας, πώς είστε σήμερα; Είμαι πολύ καλά ευχαριστώ για την ερώτηση.", minChunkChars = 10)
        assertTrue(chunks.size >= 2)
    }

    @Test
    fun `russian sentences are split after the minimum`() {
        val chunks = splitIntoTtsChunks("Привет, как у тебя дела сегодня? Спасибо, у меня всё хорошо сегодня.", minChunkChars = 10)
        assertTrue(chunks.size >= 2)
    }

    @Test
    fun `cjk sentences are split without needing a space`() {
        val chunks = splitIntoTtsChunks("今日は天気がいいですね。明日も晴れるといいですね。", minChunkChars = 5)
        assertTrue(chunks.size >= 2)
    }

    @Test
    fun `word timestamps with quotes and backslashes are valid json`() {
        val json = formatWordTimestampsJson(listOf(mapOf("word" to "say \"hi\" \\ now", "start" to 0.0, "end" to 0.5)))
        val parsed = org.json.JSONArray(json)
        assertEquals("say \"hi\" \\ now", parsed.getJSONObject(0).getString("word"))
    }

    /** Puts another chunk between the format chunk and the data chunk. */
    private fun wavWithChunkBeforeData(chunkId: String, chunkSize: Int, dataSize: Int): ByteArray {
        val base = wav(dataSize = dataSize)
        val extra = ByteArray(8 + chunkSize + (chunkSize and 1))
        chunkId.forEachIndexed { i, c -> extra[i] = c.code.toByte() }
        for (i in 0 until 4) extra[4 + i] = ((chunkSize shr (8 * i)) and 0xFF).toByte()
        return base.copyOfRange(0, 36) + extra + base.copyOfRange(36, base.size)
    }

    @Test
    fun `a LIST chunk before data does not hide the audio`() {
        assertEquals(1.0, wavDurationSeconds(wavWithChunkBeforeData("LIST", 26, 44_100)), 1e-9)
    }

    @Test
    fun `an odd sized chunk is padded to an even boundary`() {
        assertEquals(1.0, wavDurationSeconds(wavWithChunkBeforeData("fact", 5, 44_100)), 1e-9)
    }

    @Test
    fun `a data size of zero uses the bytes that follow`() {
        val bytes = wav(dataSize = 44_100)
        for (i in 0 until 4) bytes[40 + i] = 0
        assertEquals(1.0, wavDurationSeconds(bytes), 1e-9)
    }

    @Test
    fun `a streaming data size of 0xFFFFFFFF uses the bytes that follow`() {
        val bytes = wav(dataSize = 44_100)
        for (i in 0 until 4) bytes[40 + i] = 0xFF.toByte()
        assertEquals(1.0, wavDurationSeconds(bytes), 1e-9)
    }

    @Test
    fun `a header cut off inside a chunk is zero and does not hang`() {
        assertEquals(0.0, wavDurationSeconds(wav().copyOfRange(0, 30)), 1e-9)
    }

    @Test
    fun `a huge declared chunk size cannot loop or overflow`() {
        val bytes = wavWithChunkBeforeData("JUNK", 8, 100)
        for (i in 0 until 4) bytes[36 + 4 + i] = 0xFF.toByte()
        assertEquals(0.0, wavDurationSeconds(bytes), 1e-9)
    }

    @Test
    fun `a number followed by a period does not end a sentence`() {
        val chunks = splitIntoTtsChunks("Step 1. Open the settings. Then step 2. Save it.", minChunkChars = 1)
        assertTrue(chunks.none { it.trim().endsWith("Step 1.") || it.trim() == "1." })
    }
}

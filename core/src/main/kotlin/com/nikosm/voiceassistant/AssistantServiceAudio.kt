package com.nikosm.voiceassistant

/**
 * The audio file, being ported from the bottom up.
 *
 * Upstream's `AssistantServiceAudio.kt` is 1,379 lines and fuses three different things: pure text
 * and byte handling, a `MediaPlayer`/`AudioTrack` state machine, and a pile of Android audio-focus
 * machinery that PLAN.md §9 deletes outright. Only the first third can land on its own — the other
 * two need the service class they hang off, which is why they arrive with it.
 *
 * So this file currently holds the three functions that need nothing: they are ports, not
 * rewrites, and they are here rather than in a new file because upstream keeps them here and a
 * future diff against upstream is easier to read when the layout has not moved.
 *
 * **What arrives later, and where it goes.** The chunked sequencing — chunk order, one at a time,
 * cancel-previous, the progress arithmetic — is moving out to its own class rather than being
 * copied, because upstream's shape makes it untestable: it is unreachable except through a live
 * service with a real player, which is why upstream has no test for it. What is left in this file
 * will be the parts the platform genuinely forces to be shaped that way. `docs/PROVENANCE.md`
 * records the extraction.
 */

/**
 * Strips markdown formatting from text before on-device TTS synthesis.
 * Mirrors the server-side clean_text_for_tts() in tts_server.py so that the
 * eSpeak and System TTS engines don't read markup symbols aloud (**bold**,
 * *italic*, # headers, `backticks`). The Gateway/Kokoro path already gets this
 * treatment server-side, but on-device engines never did — until now.
 *
 * - **bold** / *italic* → keep enclosed text, strip the asterisks
 * - # / ## / etc. heading markers → removed
 * - ` / `` code markers → removed
 * - Excessive blank lines (3+) → collapsed to a single blank line
 */
fun cleanTextForTts(text: String): String {
    var cleaned = text
    // **bold** markers — keep the enclosed text, strip the asterisks
    cleaned = cleaned.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    // *italic* markers — keep the enclosed text, strip the asterisks
    cleaned = cleaned.replace(Regex("\\*(.+?)\\*"), "$1")
    // # / ## / ### heading markers (line-start, 1–6 hashes followed by whitespace)
    cleaned = cleaned.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
    // Backtick code markers (single and triple)
    cleaned = cleaned.replace(Regex("`+"), "")
    // ASCII arrows — eSpeak/System TTS read "->" literally ("hyphen greater
    // than"). Space replacement so "A -> B" becomes "A B". Mirrors the
    // server-side clean_text_for_tts() arrow strip.
    cleaned = cleaned.replace(Regex("-->|->|=>|<-"), " ")
    // Unicode arrows (→ ← ↔ ⟶ etc., U+2190–21FF)
    cleaned = cleaned.replace(Regex("[\\u2190-\\u21FF]"), " ")
    // Emoji + misc symbols/dingbats + variation selectors + ZWJ + keycap +
    // invisible formatting (U+200B–200F). Kokoro and the on-device engines read
    // these as garbage or skip them awkwardly. Mirrors the server-side
    // clean_text_for_tts() emoji strip.
    //
    // NOT US: upstream writes the astral half as the surrogate-pair class
    // `[\uD83C-\uD83E][\uDC00-\uDFFF]`. That works on Android, whose regex engine is ICU-backed,
    // and matches NOTHING on the JVM: OpenJDK pairs a well-formed surrogate pair into a single
    // code point before the class is applied, so neither half of the class ever sees a value it
    // contains. The failure is silent — the strip simply does nothing and emoji reach eSpeak and
    // speech-dispatcher, which is the exact symptom this line exists to prevent. A JVM test caught
    // it as a surviving 😀 rather than as an error.
    //
    // The replacement is the same set, written as the code points the surrogate class denotes:
    // high D83C–D83E over low DC00–DFFF is U+1F000–U+1FBFF, which is the emoji, symbol, transport
    // and enclosed-alphanumeric blocks including the regional indicators that make flags. The BMP
    // half needs no change — those are single code units and match identically on both engines.
    cleaned = cleaned.replace(
        Regex(
            "[\\x{1F000}-\\x{1FBFF}]" +
                "|[\\u2600-\\u27BF\\u2B00-\\u2BFF\\uFE0E\\uFE0F\\u200B-\\u200F\\u20E3]"
        ),
        ""
    )
    // Collapse leftover multi-spaces from the removals above
    cleaned = cleaned.replace(Regex(" {2,}"), " ")
    // Collapse 3+ consecutive newlines to a single blank line
    cleaned = cleaned.replace(Regex("\\n{3,}"), "\n\n")
    return cleaned
}

/**
 * Parses the duration (seconds) of a WAV file from its raw bytes by reading
 * the canonical 44-byte RIFF header: sample rate at offset 24, data-subchunk
 * size at offset 40, channels at offset 22, bits-per-sample at offset 34.
 * Duration = dataSize / (sampleRate * bytesPerSample). Falls back to the
 * header's byteRate (offset 28) when present. Returns 0.0 for anything that
 * isn't a standard WAV.
 *
 * Used to keep the accumulated word-timestamp offset on the same clock as
 * the playback durations that drive ttsPlaybackFraction. Upstream compares
 * against `MediaPlayer.duration` directly; here the same number arrives as
 * [PlaybackHandle.durationMs], and the reason the two must share a clock is
 * unchanged — the word positions and the progress fraction are both plotted
 * against it.
 *
 * Ported unchanged: it reads bytes and does arithmetic, and it has no platform
 * dependency to substitute.
 */
internal fun wavDurationSeconds(wavBytes: ByteArray): Double {
    if (wavBytes.size < 44) return 0.0
    // "RIFF" magic
    if (wavBytes[0] != 0x52.toByte() || wavBytes[1] != 0x49.toByte()) return 0.0
    if (wavBytes[2] != 0x46.toByte() || wavBytes[3] != 0x46.toByte()) return 0.0
    // "WAVE" magic
    if (wavBytes[8] != 0x57.toByte() || wavBytes[9] != 0x41.toByte()) return 0.0
    if (wavBytes[10] != 0x56.toByte() || wavBytes[11] != 0x45.toByte()) return 0.0
    var sampleRate = 0
    for (i in 24 until 28) {
        sampleRate = sampleRate or ((wavBytes[i].toInt() and 0xFF) shl (8 * (i - 24)))
    }
    if (sampleRate <= 0) return 0.0
    var byteRate = 0L
    for (i in 28 until 32) {
        byteRate = byteRate or ((wavBytes[i].toInt() and 0xFF).toLong() shl (8 * (i - 28)))
    }
    var dataSizeLong = 0L
    for (i in 40 until 44) {
        dataSizeLong = dataSizeLong or (((wavBytes[i].toInt() and 0xFF).toLong()) shl (8 * (i - 40)))
    }
    if (dataSizeLong <= 0) return 0.0
    // dataSize is in BYTES. Prefer the header's byteRate (bytes/sec); fall back
    // to channels*bitsPerSample/8 when byteRate is absent.
    val bytesPerSec = if (byteRate > 0) byteRate.toDouble()
        else 1.0 * (wavBytes[22].toInt() and 0xFF) * ((wavBytes[34].toInt() and 0xFF) or ((wavBytes[35].toInt() and 0xFF) shl 8)) / 8.0
    if (bytesPerSec <= 0) return 0.0
    return dataSizeLong.toDouble() / bytesPerSec
}

// ─── Stage-2 streaming TTS: sentence-boundary splitter ─────────────────────
// Splits accumulated LLM output into TTS-sized chunks at natural sentence
// boundaries. Runs BEFORE cleanTextForTts() per chunk (cleaning collapses
// newlines and can join sentences; splitting first preserves boundaries).
//
// Guards:
//   - Decimal points: "3.14" — period followed by a digit, not a sentence end
//   - Abbreviations: Dr., Mr., Mrs., vs., e.g., i.e., etc., St., Inc., U.S.
//     (checked against a small blocklist before splitting at a period)
//   - Double newlines (paragraph breaks) are also valid split points
//
// Chunks shorter than minChunkChars are accumulated until they exceed it —
// avoids the HTTP round-trip overhead of 5-word chunks. The last chunk is
// always emitted even if short (it's the tail of the response).
internal fun splitIntoTtsChunks(text: String, minChunkChars: Int = 80): List<String> {
    if (text.isBlank()) return emptyList()

    // Abbreviations that end in a period but are NOT sentence boundaries.
    val abbreviations = setOf(
        "dr", "mr", "mrs", "ms", "vs", "e.g", "i.e", "etc", "st", "inc",
        "u.s", "no", "vol", "approx", "dept", "est", "fig", "gen", "gov",
        "sen", "rep", "prof", "rev", "hon", "lt", "col", "sgt", "capt"
    )

    // Sentence-ending punctuation followed by whitespace and a capital letter
    // or digit (heuristic for "actually the next sentence"). The lookbehind
    // allows . ! ? … and the lookahead checks it's a real boundary.
    val sentenceEnd = Regex(
        """(?<=[.!?…])\s+(?=[A-Z0-9"'(])""",
        setOf(RegexOption.MULTILINE)
    )

    // First: split into candidate sentences (naive split on sentence-end regex)
    val rawSentences = text.split(sentenceEnd).map { it.trim() }.filter { it.isNotBlank() }
    if (rawSentences.isEmpty()) return listOf(text.trim())

    // Second: merge adjacent sentences that were falsely split (abbreviation
    // or decimal guard — if the fragment before the split ends with a known
    // abbreviation pattern or a digit, it was NOT a sentence boundary)
    val sentences = mutableListOf<String>()
    var pending = rawSentences[0]
    for (i in 1 until rawSentences.size) {
        val lastWord = pending.substringAfterLast(" ").substringBeforeLast(".")
        val isAbbrev = lastWord.lowercase() in abbreviations ||
            pending.endsWith(".") && pending.lastOrNull()?.isDigit() == true
        if (isAbbrev) {
            pending = pending + " " + rawSentences[i]
        } else {
            sentences.add(pending)
            pending = rawSentences[i]
        }
    }
    sentences.add(pending)

    // Third: accumulate into chunks of at least minChunkChars
    val chunks = mutableListOf<String>()
    var chunk = ""
    for (sentence in sentences) {
        val candidate = if (chunk.isEmpty()) sentence else "$chunk $sentence"
        if (candidate.length >= minChunkChars) {
            chunks.add(candidate)
            chunk = ""
        } else {
            chunk = candidate
        }
    }
    // Emit the tail chunk even if below minimum (it's the end of the response)
    if (chunk.isNotBlank()) chunks.add(chunk)

    return chunks.ifEmpty { listOf(text.trim()) }
}

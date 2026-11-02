package com.nikosm.voiceassistant

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A coarse loudness curve for the Live HUD, aligned to playback position.
 *
 * ## Why this exists rather than a tap on the player
 *
 * The output side of the audio path is a `pw-play` process (seam 2), and the process reads a file
 * path rather than handing us samples as it plays. Tapping it would mean either changing the
 * player's contract to stream PCM through a pipe (a seam change, and a second code path that would
 * only be exercised on Linux), or reading the player's own volume meters (a second source of
 * truth). The application already holds the audio: the local engine returns [SpeechSamples], the
 * Gateway writes each chunk's WAV, and a replay is an app-owned file. Computing the envelope from
 * that is exact, cheap, runs once per utterance or chunk, and is pure Kotlin — so it is testable
 * without a sound server.
 *
 * ## What one value means
 *
 * [levels] holds one value per [windowMs] of audio, in the range 0..1. A window's value is the
 * root-mean-square of its signed 16-bit samples, normalised to full scale and passed through a
 * square root, so that quiet speech still visibly moves the HUD: a full-scale square is ~1.0, a
 * full-scale sine ~0.84, a half-scale sine ~0.59, and silence is 0. The curve is deliberately not
 * a peak hold — a peak envelope makes a single plosive dominate several hundred milliseconds of
 * ring, which reads as a broken visual rather than as speech. The UI applies its own attack/release
 * smoothing on top; this layer is the measurement, not the animation.
 *
 * ## Alignment
 *
 * [levelAt] samples by playback position, which is the same clock [SpeechSamples.durationMs] and
 * `PlaybackHandle.positionMs` use. The sample rate is part of the conversion, so a 22,050 Hz
 * Gateway chunk and a 16,000 Hz capture do not need to agree on a bucket size to line up with
 * their own audio.
 */
internal class AudioEnvelope(
    val levels: FloatArray,
    /** How many milliseconds of audio one [levels] entry covers. Always at least 1. */
    val windowMs: Int,
) {
    init {
        require(windowMs > 0) { "windowMs must be positive, was $windowMs" }
    }

    /** The audio this envelope covers. Used to build a timeline from consecutive chunks. */
    val durationMs: Long get() = levels.size.toLong() * windowMs

    /**
     * The level at [positionMs], clamped to the envelope.
     *
     * Before the start is the first window rather than silence: a player that reports a position
     * a few milliseconds negative (or a progress poll that lands at zero) must not flash the ring
     * off and on. Past the end is the last window for the same reason — the player can outlive the
     * measured audio by a scheduler tick — and the service resets the published amplitude when
     * playback really ends.
     */
    fun levelAt(positionMs: Long): Float {
        if (levels.isEmpty()) return 0f
        if (positionMs <= 0L) return levels[0]
        val index = positionMs / windowMs
        return levels[if (index >= levels.size) levels.lastIndex else index.toInt()]
    }

    /**
     * This envelope followed by [other], for the chunked Gateway path.
     *
     * Chunks from one engine share a sample rate and therefore a [windowMs], so the common path is
     * a concatenation. A mismatch is still accepted — a future engine or a resampled chunk should
     * not crash a live turn — by resampling [other] onto this envelope's grid with a nearest-window
     * read. That is deliberately approximate: the HUD needs the shape of the speech, and inventing
     * fractional buckets here would create a second envelope format for no visible benefit.
     */
    fun append(other: AudioEnvelope): AudioEnvelope {
        if (other.levels.isEmpty()) return this
        if (levels.isEmpty()) return other
        if (other.windowMs == windowMs) {
            return AudioEnvelope(levels + other.levels, windowMs)
        }
        val extra = ((other.durationMs + windowMs - 1) / windowMs).toInt()
        if (extra <= 0) return this
        val merged = FloatArray(levels.size + extra)
        levels.copyInto(merged)
        for (i in 0 until extra) {
            merged[levels.size + i] = other.levelAt(i.toLong() * windowMs)
        }
        return AudioEnvelope(merged, windowMs)
    }
}

/** One 50 ms bucket: twenty values a second, enough for a 96-segment ring to look continuous. */
internal const val ENVELOPE_WINDOW_MS = 50

/**
 * Envelopes [pcm] — signed 16-bit little-endian mono — at [sampleRate].
 *
 * This is the shape [SpeechSamples] carries, so the local eSpeak route needs no container
 * parsing. A malformed or empty buffer yields an empty envelope rather than an exception: the
 * caller is a playback path that must still land in IDLE.
 */
internal fun pcm16Envelope(
    pcm: ByteArray,
    sampleRate: Int,
    windowMs: Int = ENVELOPE_WINDOW_MS,
): AudioEnvelope = envelopeOf(pcm, 0, pcm.size, sampleRate, channels = 1, windowMs = windowMs)

/**
 * Envelopes the `data` chunk of a RIFF/WAVE file, or null when the file is not 16-bit PCM.
 *
 * The scan is the same defensive one [wavDurationSeconds] uses, and for the same reason: the
 * Gateway's WAV may carry `LIST`/metadata chunks before `data`, its size fields are untrusted
 * input, and an odd-sized chunk is padded. A null here is not a failure of the turn — the audio
 * still plays; it only means the HUD has no measurement and falls back to its own cadence.
 */
internal fun wav16Envelope(
    wav: ByteArray,
    windowMs: Int = ENVELOPE_WINDOW_MS,
): AudioEnvelope? {
    if (wav.size < 12) return null
    fun tag(at: Int) = String(wav, at, 4, Charsets.ISO_8859_1)
    if (tag(0) != "RIFF" || tag(8) != "WAVE") return null

    fun u16(at: Int) = (wav[at].toInt() and 0xFF) or ((wav[at + 1].toInt() and 0xFF) shl 8)
    fun u32(at: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((wav[at + i].toLong() and 0xFF) shl (8 * i)) }

    var format = -1
    var channels = 0
    var sampleRate = 0
    var bitsPerSample = 0
    var dataOffset = -1
    var dataSize = 0L

    var offset = 12L
    while (offset + 8 <= wav.size) {
        val at = offset.toInt()
        val id = tag(at)
        val size = u32(at + 4)
        val body = offset + 8
        when (id) {
            "fmt " -> if (size >= 16 && body + 16 <= wav.size) {
                val b = body.toInt()
                format = u16(b)
                channels = u16(b + 2)
                sampleRate = u32(b + 4).toInt()
                bitsPerSample = u16(b + 14)
            }
            "data" -> {
                dataOffset = body.toInt()
                val remaining = (wav.size - body).coerceAtLeast(0L)
                dataSize = if (size == 0L || size == 0xFFFFFFFFL) remaining else size
                break
            }
        }
        val next = body + size + (size and 1L)
        if (next <= offset || next > wav.size) break
        offset = next
    }

    // `1` is WAVE_FORMAT_PCM. WAVE_FORMAT_EXTENSIBLE (0xFFFE) is a container around the same
    // samples, but every producer in this project writes the plain header (WavFile is the only
    // writer), so accepting it without reading the sub-format would be a guess. Null is honest.
    if (format != 1 || bitsPerSample != 16 || channels <= 0 || sampleRate <= 0) return null
    if (dataOffset < 0 || dataSize <= 0) return null
    val available = (wav.size - dataOffset).coerceAtLeast(0)
    val bytes = minOf(dataSize, available.toLong()).toInt()
    // A trailing odd byte cannot form a sample; a one-byte chunk has no audio.
    val usable = bytes and 1.inv()
    if (usable < 2) return null

    return envelopeOf(wav, dataOffset, usable, sampleRate, channels, windowMs)
}

/**
 * The one implementation behind both entry points.
 *
 * [channels] matters only to the window size: a stereo WAV stores left/right interleaved, so one
 * 50 ms window is `rate * channels * 50 / 1000` samples long. The RMS is taken across all of them,
 * which is the same number for identical channels and a reasonable average for a real stereo
 * recording — the HUD is a loudness visual, not a channel analyser.
 */
private fun envelopeOf(
    pcm: ByteArray,
    offset: Int,
    length: Int,
    sampleRate: Int,
    channels: Int,
    windowMs: Int,
): AudioEnvelope {
    if (sampleRate <= 0 || windowMs <= 0 || channels <= 0 || length < 2) {
        return AudioEnvelope(FloatArray(0), windowMs.coerceAtLeast(1))
    }
    val samples = length / 2
    // Round rather than truncate: 22,050 Hz × 50 ms is 1102.5 samples. Truncating to 1102 would
    // leave a 10-sample tail bucket at the end of a one-second reply — a twenty-first window that
    // adds a visible extra tick to a 20-value curve, and drifts durationMs by five percent.
    val samplesPerWindow = (sampleRate.toDouble() * channels * windowMs / 1000.0)
        .roundToInt().coerceAtLeast(1)
    // The real bucket is `samplesPerWindow` samples wide, which may be a fraction of a
    // millisecond away from the requested 50 — report the width we actually used, because
    // levelAt divides a player position by it.
    val effectiveWindowMs = (samplesPerWindow.toDouble() * 1000.0 /
        (sampleRate.toDouble() * channels)).roundToInt().coerceAtLeast(1)

    val count = (samples + samplesPerWindow - 1) / samplesPerWindow
    val levels = FloatArray(count)
    var sampleIndex = 0
    for (bucket in 0 until count) {
        val end = minOf((bucket + 1) * samplesPerWindow, samples)
        var sumSquares = 0.0
        val start = sampleIndex
        while (sampleIndex < end) {
            val at = offset + sampleIndex * 2
            val sample = ((pcm[at].toInt() and 0xFF) or (pcm[at + 1].toInt() shl 8))
            sumSquares += sample.toDouble() * sample.toDouble()
            sampleIndex++
        }
        val n = end - start
        if (n <= 0) continue
        val rms = sqrt(sumSquares / n)
        // Normalise to full scale, then soften the dynamic range: speech at a comfortable level
        // is around 10 % of full scale, and a linear ring would barely move for most of a reply.
        levels[bucket] = sqrt((rms / 32768.0).coerceIn(0.0, 1.0)).toFloat()
    }
    return AudioEnvelope(levels, effectiveWindowMs)
}

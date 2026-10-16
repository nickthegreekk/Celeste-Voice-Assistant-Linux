package com.nikosm.voiceassistant

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * The one place that writes a WAV in this project.
 *
 * It exists because there are two producers — [VADAudioRecorder]'s hands-free utterances and the
 * push-to-talk capture in `AssistantService.kt` — and one reader, `wavDurationSeconds` in
 * `AssistantServiceAudio.kt`, which parses the same 44 bytes back to answer "how long is this
 * audio?". Two writers and a reader is three copies of one layout, and the interesting failure is
 * silent: a header that disagrees with the reader does not throw, it reports a wrong duration, and
 * `voiceDuration` is what the UI's reveal is timed against.
 *
 * The header is upstream's, byte for byte — the same one `VADAudioRecorder` wrote before this
 * extraction and the same one the reader parses. PCM, mono, signed 16-bit little-endian.
 *
 * ## What it is not
 *
 * **Public, not `internal`, and the reason is a seam.** `PwPlayback` — seam 2's Linux implementation
 * — writes the temporary WAV that `playPcm` promises to materialise, and `:platform` is a different
 * module, so Kotlin's `internal` keeps it out. This is the one shape the module boundary forces:
 * `:core` declares the seam *and* the shared helpers a seam implementation needs; `:platform` may
 * import `:core` (AGENTS.md §2), which means what it needs has to be reachable.
 *
 * Upstream's push-to-talk recorded AAC in an MP4 container through `MediaRecorder`, which a
 * `CaptureStream` cannot produce: seam 1 hands over raw 16-bit frames and nothing else. Hands-free
 * already sends a 16 kHz mono WAV to `/transcribe`, so push-to-talk sends the same container at the
 * same rate rather than introducing a second format for the same gateway endpoint to accept.
 */
object WavFile {

    /** The canonical 44-byte RIFF header for [dataSize] bytes of mono 16-bit PCM at [sampleRate]. */
    fun header(dataSize: Int, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1.toShort()) // PCM
        header.putShort(1.toShort()) // Mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // Byte rate
        header.putShort(2.toShort()) // Block align
        header.putShort(16.toShort()) // Bits per sample
        header.put("data".toByteArray())
        header.putInt(dataSize)
        return header.array()
    }

    /**
     * Writes [chunks] — each a block of signed 16-bit mono samples — to [file] as a WAV.
     *
     * The header's size derives from the same snapshot the samples come from, so the two cannot
     * disagree; that is the whole reason this is one function rather than a `header()` call the
     * caller has to size correctly.
     */
    fun write(file: Path, chunks: List<ShortArray>, sampleRate: Int) {
        val dataSize = chunks.sumOf { it.size } * 2
        file.parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(file).use { out ->
            out.write(header(dataSize, sampleRate))
            // One reusable buffer rather than a copy per chunk: these are 512-sample blocks and a
            // long recording is thousands of them.
            val buffer = ByteBuffer.allocate(chunks.maxOfOrNull { it.size }?.times(2) ?: 2)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (chunk in chunks) {
                buffer.clear()
                for (s in chunk) buffer.putShort(s)
                out.write(buffer.array(), 0, chunk.size * 2)
            }
        }
    }
}

package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PLAN.md §6's two budget spans, against a clock the test owns.
 *
 * The reason this class exists at all is that the numbers it reports are the evidence M2's
 * acceptance run is judged on, and a wrong number there is worse than no number: it would be
 * recorded in PLAN.md as a measurement. So the arithmetic, the budget comparison and the wording of
 * the line are pinned here, with the clock advanced by hand — the message text is asserted whole
 * rather than by substring, because the whole line is what a human reads out of a terminal.
 *
 * What is *not* here is the loop: [VADAudioRecorder] cannot be constructed in a test (its detector
 * is a concrete [VADDetector] wrapping ONNX), and PLAN.md §11 gives the voice loop its fake gateway
 * and VAD fixtures in M7. What is proven here is everything the loop's five call sites delegate.
 */
class AudioLatencyTest {

    /** Records rather than prints, as `LogSinkTest` does, so the line can be asserted on. */
    private class RecordingLogger : AppLogger {
        val entries = mutableListOf<Pair<LogLevel, String>>()

        override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
            entries += level to message
        }
    }

    /** A monotonic clock the test advances by hand: every millisecond in the output is the test's. */
    private class FakeClock {
        private var nanos = 0L

        fun read(): Long = nanos

        fun advance(millis: Long) {
            nanos += millis * 1_000_000
        }
    }

    private val clock = FakeClock()
    private val logger = RecordingLogger()
    private val latency = AudioLatency(clock::read, logger)

    private fun line(index: Int = 0) = logger.entries[index].second

    // ---------------------------------------------------------------- capture start → first chunk

    @Test
    fun `the capture span reports the total, the open and the first read separately`() {
        // The split is the point: 400 ms inside `open` (the liveness grace ProcessAudioCapture waits
        // out) and 400 ms waiting on the pipe are the same total and different repairs.
        latency.captureStarted()
        clock.advance(412)
        latency.captureOpened()
        clock.advance(19)
        latency.firstChunkArrived()

        assertEquals(
            "capture start → first chunk: 431 ms (381 ms over the 50 ms budget; open 412 ms, first read 19 ms)",
            line(),
        )
    }

    @Test
    fun `a capture span inside the budget says how much room is left`() {
        latency.captureStarted()
        clock.advance(7)
        latency.captureOpened()
        clock.advance(11)
        latency.firstChunkArrived()

        assertEquals(
            "capture start → first chunk: 18 ms (32 ms under the 50 ms budget; open 7 ms, first read 11 ms)",
            line(),
        )
    }

    @Test
    fun `only the first chunk of a session is reported`() {
        // The marks are called from the hot loop, which passes once per 32 ms chunk for as long as
        // hands-free is on. A line per chunk would be a stream, and §6's span is the first one.
        latency.captureStarted()
        latency.captureOpened()
        latency.firstChunkArrived()
        clock.advance(5_000)
        latency.firstChunkArrived()

        assertEquals(1, logger.entries.size)
    }

    @Test
    fun `a reopened capture is measured as a new span`() {
        latency.captureStarted()
        latency.captureOpened()
        latency.firstChunkArrived()

        clock.advance(60_000)
        latency.captureStarted()
        clock.advance(9)
        latency.captureOpened()
        clock.advance(3)
        latency.firstChunkArrived()

        assertEquals(2, logger.entries.size)
        assertEquals(
            "capture start → first chunk: 12 ms (38 ms under the 50 ms budget; open 9 ms, first read 3 ms)",
            line(1),
        )
    }

    // ---------------------------------------------------------------- utterance end → WAV on disk

    @Test
    fun `the WAV span reports the write time and the size that proves the utterance is whole`() {
        latency.utteranceEnded()
        clock.advance(6)
        latency.wavWritten(bytes = 129_068)

        assertEquals(
            "utterance end → WAV: 6 ms (94 ms under the 100 ms budget; 129068 bytes)",
            line(),
        )
    }

    @Test
    fun `a size that could not be read back is said so rather than shown as zero`() {
        // Zero bytes would read as an empty recording, which is a different claim from "the stat
        // failed" — and the utterance itself was written either way.
        latency.utteranceEnded()
        clock.advance(3)
        latency.wavWritten(bytes = null)

        assertEquals(
            "utterance end → WAV: 3 ms (97 ms under the 100 ms budget; size unreadable)",
            line(),
        )
    }

    @Test
    fun `a span exactly at its budget is over it, because the plan's bounds are strict`() {
        // §6 says "< 50 ms". The equality case cannot be hit by accident in a real run at these
        // magnitudes, so it is pinned here rather than discovered in one.
        latency.captureStarted()
        clock.advance(50)
        latency.captureOpened()
        latency.firstChunkArrived()

        assertEquals(
            "capture start → first chunk: 50 ms (0 ms over the 50 ms budget; open 50 ms, first read 0 ms)",
            line(),
        )
    }

    // ------------------------------------------------------------------------------------ visibility

    @Test
    fun `both spans reach the terminal, which means INFO and not DEBUG`() {
        // StderrLogger is java.util.logging at its default level: DEBUG is FINE and never printed,
        // so a measurement logged at DEBUG would be invisible in exactly the run that needs it —
        // the same trap that keeps "VAD monitoring started" out of the terminal today.
        latency.captureStarted()
        latency.captureOpened()
        latency.firstChunkArrived()
        latency.utteranceEnded()
        latency.wavWritten(bytes = 1)

        assertEquals(listOf(LogLevel.INFO, LogLevel.INFO), logger.entries.map { it.first })
    }
}

package com.nikosm.voiceassistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The capture loop, driven with a fake microphone.
 *
 * This is the first test [VADAudioRecorder] has had, and it is a narrow one on purpose: PLAN.md §11
 * gives the voice loop its WAV fixtures and its fake detector in M7, and until then the detector
 * here is the real [VADDetector] running Silero over silence. What that is enough for — and what
 * this file is for — is the *lifecycle*: the loop is started, paused, stopped, and what it writes
 * to the log along the way is asserted. The 2026-10-16 acceptance run turned up a SEVERE on every
 * stop ("Recording error: StandaloneCoroutine was cancelled"), and a log that reports normal
 * operation as a fault is worth a test rather than a promise.
 *
 * The capture is silent, so nothing is ever recorded, no WAV is written, and `Dispatchers.Main` is
 * never reached (it is used only on a speech event) — which is what lets this run on a plain JVM
 * with no audio hardware and no Main dispatcher.
 */
class VADAudioRecorderTest {

    private class RecordingLogger : AppLogger {
        private val entries = mutableListOf<Triple<LogLevel, String, String>>()

        @Synchronized
        override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
            entries += Triple(level, tag, message)
        }

        @Synchronized
        fun messages(): List<String> = entries.map { it.third }

        @Synchronized
        fun levels(): List<LogLevel> = entries.map { it.first }
    }

    /**
     * Silence at the requested rate, as fast as the loop asks for it.
     *
     * `read` is the loop's only blocking call, and it counts its calls: the count is how the test
     * knows the loop has *left* the reading branch and parked itself in the paused one.
     */
    private class SilentCapture : AudioCapture {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val reads = AtomicInteger()

        /** Counted rather than ignored: the drain is the half of the resume span worth asserting. */
        val drains = AtomicInteger()

        override fun open(sampleRate: Int, chunkFrames: Int, target: AudioDevice?): CaptureStream {
            opened.countDown()
            return object : CaptureStream {
                override fun read(buffer: ShortArray, frames: Int): Int {
                    java.util.Arrays.fill(buffer, 0, frames, 0)
                    reads.incrementAndGet()
                    // A courtesy rather than a requirement: without it the loop would run Silero
                    // as fast as the CPU allows for the length of the test.
                    Thread.sleep(2)
                    return frames
                }

                override fun drain() {
                    drains.incrementAndGet()
                }

                override fun close() {
                    closed.countDown()
                }
            }
        }
    }

    // LogSink is process-wide, so it is restored after every test — a leftover recording logger
    // would capture another test's output (LogSinkTest sets the same rule).
    private val originalSink = LogSink.logger
    private val logger = RecordingLogger()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val capture = SilentCapture()
    private var recorder: VADAudioRecorder? = null

    @After
    fun tearDown() {
        // Register what you build and stop it (AGENTS.md §6): the loop here owns a real thread
        // pool and a real capture stream, and leaving either behind would surface in a later test.
        runCatching { recorder?.stop() }
        scope.cancel()
        LogSink.logger = originalSink
    }

    private fun startRecording(): VADAudioRecorder {
        LogSink.logger = logger
        val r = VADAudioRecorder(
            capture = capture,
            detector = VADDetector(),
            cacheDir = java.nio.file.Files.createTempDirectory("vad-recorder-test").also {
                it.toFile().deleteOnExit()
            },
            scope = scope,
            inputDevice = null,
            onSpeechStart = {},
            onSpeechEnd = {},
        )
        recorder = r
        r.start()
        return r
    }

    private fun awaitLine(fragment: String, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (logger.messages().any { fragment in it }) return true
            Thread.sleep(10)
        }
        return false
    }

    @Test
    fun `stopping the loop is silent — a cancellation is not a recording error`() {
        val r = startRecording()
        assertTrue("the capture should have been opened", capture.opened.await(5, TimeUnit.SECONDS))

        // The loop is reading. Pausing it puts it in the branch that sleeps 200 ms per iteration,
        // which is where the real run's loop was when the user switched hands-free off — and the
        // reads stopping is what proves it is parked there rather than still reading.
        r.pause()
        Thread.sleep(250)
        val readsWhenPaused = capture.reads.get()
        Thread.sleep(250)
        assertEquals("the loop should not read while paused", readsWhenPaused, capture.reads.get())

        // `stop()` cancels the coroutine; the cancellation is delivered at the paused branch's
        // delay and reaches the loop's catch. Before this was fixed that catch reported it as
        // "Recording error: StandaloneCoroutine was cancelled" at SEVERE — on every stop.
        r.stop()
        assertTrue("the capture stream should have been closed", capture.closed.await(5, TimeUnit.SECONDS))

        val errors = logger.levels().filter { it == LogLevel.ERROR || it == LogLevel.ASSERT }
        assertEquals("a stop must not be reported as a fault: ${logger.messages()}", emptyList<LogLevel>(), errors)
    }

    @Test
    fun `a resume drains what the paused pipe collected, and reports the span`() {
        // The backlog is the half of §6's third span that this project owns: while the VAD is
        // paused the pipe fills (up to ~2 s), so without a drain the first chunk after a resume is
        // audio from before the reply, and "how long until it can hear me" is measured against
        // something already old. The drain count is the assertion because the fake has no pipe.
        val r = startRecording()
        assertTrue(capture.opened.await(5, TimeUnit.SECONDS))
        assertTrue("the loop should be reading", awaitLine("capture start → first chunk:"))

        r.pause()
        Thread.sleep(250)
        val readsWhenPaused = capture.reads.get()
        Thread.sleep(250)
        assertEquals("the loop should not read while paused", readsWhenPaused, capture.reads.get())
        assertEquals("nothing to drain until the resume", 0, capture.drains.get())

        r.resume()

        assertTrue("the resume should be reported", awaitLine("resume → first live chunk:"))
        assertEquals("the backlog should have been discarded once", 1, capture.drains.get())
    }

    @Test
    fun `the first chunk of a session is reported, which is how the loop marks the budget`() {
        // The wiring half of AudioLatency, which pins the arithmetic and the wording: the mark is
        // taken by the loop itself, at the first full chunk, and this is what says so.
        startRecording()
        assertTrue(
            "the loop should report §6's first span",
            awaitLine("capture start → first chunk:"),
        )
        assertTrue("the capture should have been opened", capture.opened.await(5, TimeUnit.SECONDS))
    }
}

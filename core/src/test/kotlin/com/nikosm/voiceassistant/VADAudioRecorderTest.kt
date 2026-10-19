package com.nikosm.voiceassistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
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
 * The capture is silent, so nothing is ever recorded and no WAV is written. The loop reaches
 * `Dispatchers.Main` only for speech callbacks and for `onDied`; the death tests install an
 * unconfined Main dispatcher, which runs the report inline on the IO thread and keeps the test on
 * a plain JVM with no audio hardware.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    /**
     * A capture whose stream has already ended: `read` returns [result] forever, which is what a
     * `pw-record` that exited or a device that was unplugged looks like from the loop.
     */
    private class DyingCapture(private val result: Int) : AudioCapture {
        val closed = CountDownLatch(1)

        override fun open(sampleRate: Int, chunkFrames: Int, target: AudioDevice?): CaptureStream =
            object : CaptureStream {
                override fun read(buffer: ShortArray, frames: Int): Int = result
                override fun drain() = Unit
                override fun close() {
                    closed.countDown()
                }
            }
    }

    /** A capture that could not open anything — no sound server, no input device. */
    private class UnopenableCapture : AudioCapture {
        override fun open(sampleRate: Int, chunkFrames: Int, target: AudioDevice?): CaptureStream? = null
    }

    // LogSink is process-wide, so it is restored after every test — a leftover recording logger
    // would capture another test's output (LogSinkTest sets the same rule).
    private val originalSink = LogSink.logger
    private val logger = RecordingLogger()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val capture = SilentCapture()
    private var recorder: VADAudioRecorder? = null

    /** Reasons the recorder reported as a microphone loss, in order. */
    private val died: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        // `onDied` is the first callback this test can reach, and it hops to Main like the speech
        // callbacks. Unconfined keeps that hop on the IO thread, so there is no scheduler to pump.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        // Register what you build and stop it (AGENTS.md §6): the loop here owns a real thread
        // pool and a real capture stream, and leaving either behind would surface in a later test.
        runCatching { recorder?.stop() }
        scope.cancel()
        LogSink.logger = originalSink
        Dispatchers.resetMain()
    }

    private fun startRecording(withCapture: AudioCapture = capture): VADAudioRecorder {
        LogSink.logger = logger
        val r = VADAudioRecorder(
            capture = withCapture,
            detector = VADDetector(),
            cacheDir = java.nio.file.Files.createTempDirectory("vad-recorder-test").also {
                it.toFile().deleteOnExit()
            },
            scope = scope,
            inputDevice = null,
            onSpeechStart = {},
            onSpeechEnd = {},
            onDied = { reason -> died += reason },
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

    @Test
    fun `a stream that ends is reported, so hands-free cannot stay on over a dead microphone`() {
        // The 2026-10-19 review's silent-failure gap: the loop ended on read 0 and told nobody, so
        // `handsFreeMode` stayed true, the tray said listening, and the IDLE watcher's resume() was
        // a no-op. The reason is the contract with the service — it becomes the notification body.
        val dying = DyingCapture(result = 0)
        startRecording(withCapture = dying)

        assertTrue("the stream should have been closed", dying.closed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("the capture stream ended"), synchronized(died) { died.toList() })
    }

    @Test
    fun `a negative read is the stream ending too, not a short frame to spin on`() {
        // AudioCapture's contract allows "negative when the stream has ended"; only 0 was handled,
        // and an implementation using the negative form would have produced the very per-iteration
        // hot loop the 0 check exists to prevent.
        val dying = DyingCapture(result = -1)
        startRecording(withCapture = dying)

        assertTrue("the stream should have been closed", dying.closed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("the capture stream ended"), synchronized(died) { died.toList() })
    }

    @Test
    fun `a microphone that cannot be opened is reported too`() {
        // Monitoring never starts, but the service sets `handsFreeMode` immediately after start();
        // without this report the toggle would sit on over a recorder that opened nothing.
        startRecording(withCapture = UnopenableCapture())

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && synchronized(died) { died.isEmpty() }) {
            Thread.sleep(10)
        }
        assertEquals(
            listOf("no capture stream could be opened"),
            synchronized(died) { died.toList() },
        )
    }

    @Test
    fun `stopping on purpose is not a lost microphone`() {
        // A user toggle and a hardware loss leave the loop through the same teardown. Only the
        // cancelled job distinguishes them, and a false report here would notify on every switch.
        val r = startRecording()
        assertTrue(capture.opened.await(5, TimeUnit.SECONDS))
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && capture.reads.get() == 0) Thread.sleep(5)

        r.stop()
        assertTrue(capture.closed.await(5, TimeUnit.SECONDS))
        // Give a mis-timed report time to arrive before asserting its absence.
        Thread.sleep(150)

        assertEquals("a deliberate stop must not report a death", emptyList<String>(), synchronized(died) { died.toList() })
    }
}

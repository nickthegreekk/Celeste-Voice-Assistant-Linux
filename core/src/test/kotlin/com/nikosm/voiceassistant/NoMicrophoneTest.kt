package com.nikosm.voiceassistant

import java.nio.file.Path
import java.util.Collections
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
import java.util.concurrent.atomic.AtomicReference

/**
 * M7's "no microphone" error path: the capture seam refuses to open.
 *
 * The requirement is the same shape as the gateway-down test — the failure is reported once and
 * the application stays alive — but the failure mode this guards is worse than an error bubble. A
 * silent retry loop here would spin the CPU for as long as the app stays open (the recorder's own
 * comment records exactly that defect: a read of 0 used to produce a warning per iteration), and a
 * throw on the IO coroutine would look like a crash with no explanation. So the assertions are:
 * **one** log line, no speech callbacks, and the detector never asked (nothing was heard).
 *
 * The detector is a fake because the real [VADDetector] loads the ONNX session in its constructor;
 * [SpeechDetector] exists for this test, and the recorder never reaches it on this path.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NoMicrophoneTest {

    private val root: Path = TempRoots.newRoot("celeste-nomic")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val originalLogger = LogSink.logger

    private class RecordingLogger : AppLogger {
        val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
            if (level == LogLevel.ERROR) lines.add(message)
        }
    }

    private class FakeDetector : SpeechDetector {
        var calls = 0

        override fun isSpeech(samples: FloatArray): Float {
            calls++
            return 0f
        }

        override fun reset() {}
    }

    private object NoCapture : AudioCapture {
        override fun open(sampleRate: Int, chunkFrames: Int, target: AudioDevice?): CaptureStream? = null
    }

    @Before
    fun setUp() {
        // The failure now reaches the service as well as the log (onDied, dispatched like the
        // speech callbacks). Unconfined keeps the report on the IO thread.
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        LogSink.logger = originalLogger
        Dispatchers.resetMain()
    }

    @Test
    fun `a microphone that cannot be opened stops monitoring and says so once`() {
        val logger = RecordingLogger()
        LogSink.logger = logger
        val detector = FakeDetector()
        var speechStarted = 0
        var speechEnded = 0
        val diedReason = AtomicReference<String?>(null)
        val recorder = VADAudioRecorder(
            capture = NoCapture,
            detector = detector,
            cacheDir = root,
            scope = scope,
            inputDevice = null,
            onSpeechStart = { speechStarted++ },
            onSpeechEnd = { speechEnded++ },
            onDied = { diedReason.set(it) },
        )

        recorder.start()

        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (logger.lines.isNotEmpty()) break
            Thread.sleep(5)
        }
        // Give a hot loop time to show itself: a second line within this window would be the
        // per-iteration retry the recorder's comment says must not exist.
        Thread.sleep(100)
        recorder.stop()

        assertEquals("the failure is reported exactly once: ${logger.lines}", 1, logger.lines.size)
        assertTrue(
            "the message names the missing capture stream: ${logger.lines.single()}",
            logger.lines.single().contains("no capture stream could be opened"),
        )
        assertEquals("nothing was heard, so no utterance may start", 0, speechStarted)
        assertEquals("and none may end", 0, speechEnded)
        assertEquals("the detector is never asked about audio that does not exist", 0, detector.calls)
        // The service is told as well: otherwise startVadListening's handsFreeMode=true would
        // stand over a recorder that never opened anything (the review's silent-failure gap).
        assertEquals(
            "the missing microphone must be reported to the service",
            "no capture stream could be opened",
            diedReason.get(),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
    }
}

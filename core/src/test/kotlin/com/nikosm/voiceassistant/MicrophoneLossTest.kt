package com.nikosm.voiceassistant

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A microphone that goes away must not leave hands-free pretending to listen (2026-10-19 review).
 *
 * `VADAudioRecorder` reports the loss through `onDied`; this is the service half of the contract:
 * `handsFreeMode` switches off, the recorder is released, and a notification says why. Before the
 * fix, the recorder stopped itself and told nobody — `handsFreeMode` stayed true, the tray said
 * "listening", the IDLE watcher's `resume()` was a no-op on a released stream, and the only way
 * out was to toggle hands-free by hand, if the user ever noticed.
 *
 * Both ways a session can fail to be listening are covered: the stream ends after opening (the
 * unplugged microphone, the crashed `pw-record`, the restarted sound server) and no stream could
 * be opened at all. The second one matters here because `startVadListening` sets the mode **after**
 * `start()` returns, so without a report it would switch the toggle on over an empty recorder.
 *
 * Not covered, deliberately: automatic reopening. The decision is recorded in docs/DECISIONS.md —
 * a missing device is not retried into a hot loop, and starting again is both one press and the
 * moment a changed device choice applies.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MicrophoneLossTest {

    private val notifier = RecordingNotifier()
    private val env = FakeAppEnv(notifier = notifier)
    private val store = InMemoryStore()
    private val settings = SettingsManager(env, store, NoImageCodec)
    private val mainDispatcher = StandardTestDispatcher()
    private val services = mutableListOf<AssistantService>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        Files.createDirectories(env.cacheDir)
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        drainServiceScopes(services, mainDispatcher.scheduler)
        services.clear()
        Dispatchers.resetMain()
    }

    /** A capture whose stream ends immediately — the device is already gone. */
    private class EndedCapture : AudioCapture {
        val closed = CountDownLatch(1)

        override fun open(sampleRate: Int, chunkFrames: Int, target: AudioDevice?): CaptureStream =
            object : CaptureStream {
                override fun read(buffer: ShortArray, frames: Int): Int = 0
                override fun drain() = Unit
                override fun close() {
                    closed.countDown()
                }
            }
    }

    private fun service(capture: AudioCapture): AssistantService =
        AssistantService(
            appEnv = env,
            audioPlayback = FakeAudioPlayback(),
            audioDevices = NoAudioDevices,
            tts = NoTextToSpeech,
            audioCapture = capture,
            fileDialogs = NoFileDialogs,
            settingsManager = settings,
        ).also {
            services += it
            // startVadListening refuses to start without a server; it does not contact one until
            // an utterance ends, and these tests never get that far.
            it._serverBases.value = listOf(
                ServerConfig(name = "MicrophoneLossProbe", url = "http://127.0.0.1:1"),
            )
        }

    @Test
    fun `a stream that ends switches hands-free off and says why`() {
        val capture = EndedCapture()
        val service = service(capture)

        service.startVadListening()
        assertTrue("the recorder should have started listening", service.handsFreeMode.value)

        awaitMain("hands-free should switch off when its recorder dies") {
            !service.handsFreeMode.value
        }
        awaitMain("the loss should reach the notification seam") {
            notifier.posted.any { it.id == MICROPHONE_LOST_NOTIFICATION_ID }
        }

        val post = notifier.posted.single { it.id == MICROPHONE_LOST_NOTIFICATION_ID }
        assertEquals("Celeste", post.title)
        assertEquals("Hands-free listening stopped — the capture stream ended", post.body)
        assertTrue("the recorder should have been closed", capture.closed.await(5, TimeUnit.SECONDS))
        assertEquals(
            "the status line must stop claiming to listen",
            "Ready to help",
            service.statusLine.value,
        )
    }

    @Test
    fun `a microphone that cannot open does not leave the toggle claiming to listen`() {
        val service = service(NoAudioCapture)

        service.startVadListening()
        // startVadListening sets this after start() returns; the report from the recorder is what
        // must take it back down.
        assertTrue("the toggle starts on, which is why the report matters", service.handsFreeMode.value)

        awaitMain("a recorder that never opened must not leave hands-free on") {
            !service.handsFreeMode.value
        }
        awaitMain("the failure should reach the notification seam") {
            notifier.posted.any { it.id == MICROPHONE_LOST_NOTIFICATION_ID }
        }

        val post = notifier.posted.single { it.id == MICROPHONE_LOST_NOTIFICATION_ID }
        assertEquals(
            "Hands-free listening stopped — no capture stream could be opened",
            post.body,
        )
    }

    private fun awaitMain(failure: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            if (condition()) return
            Thread.sleep(10)
        }
        mainDispatcher.scheduler.runCurrent()
        assertTrue(failure, condition())
    }
}

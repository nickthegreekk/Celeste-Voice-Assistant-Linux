package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The eSpeak (local PCM) route must feed the karaoke gate.
 *
 * The gate is `ttsPlaybackFraction != null && speaking`; [ttsActiveWordRange] estimates each
 * word char-proportionally from the text and the duration when the engine has no word
 * timestamps, which is exactly the local route. But `speakWithEspeak` only set `voiceDuration`
 * and never published a fraction, so the teleprompter stayed closed for eSpeak while the
 * chunked Gateway path, driven by its sequencer, highlighted normally.
 *
 * This probe drives the production local speech path through a fake PCM player: the fraction
 * must appear and advance with `PlaybackHandle.positionMs`, and the estimated range must exist
 * at half the audio. Before the fix the fraction stayed null and this test failed.
 */
class EspeakHighlightTest {
    private val mainDispatcher = StandardTestDispatcher()


    private val env = FakeAppEnv(notifier = RecordingNotifier())
    private val settings = SettingsManager(env, InMemoryStore(), NoImageCodec)
    private val services = mutableListOf<AssistantService>()

    private lateinit var playback: PcmPlayback
    private lateinit var service: AssistantService

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        playback = PcmPlayback()
        service = AssistantService(
            appEnv = env,
            audioPlayback = playback,
            audioDevices = NoAudioDevices,
            tts = LocalSpeech(),
            audioCapture = NoAudioCapture,
            fileDialogs = NoFileDialogs,
            settingsManager = settings,
        ).also { services += it }
    }

    @After
    fun tearDown() {
        services.forEach { runCatching { it.shutdown() } }
        drainServiceScopes(services, mainDispatcher.scheduler)
        services.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `local speech publishes the fraction the karaoke gate needs`() = runTest {
        val persona = Persona(
            name = "EspeakHighlightProbe",
            themeColor = Color(0xFF4ADE80),
            model = "",
            systemPrompt = "",
            voiceMode = VoiceMode.BUNDLED_ESPEAK,
            targetLanguage = "English",
        )
        service.currentPersonaName = persona.name
        service.messagesOwnerName = persona.name

        service.speakWithEspeak("hello there", persona)

        // Synthesis runs on Dispatchers.IO; wait for it to reach the Main-thread playback.
        val deadline = System.currentTimeMillis() + 10_000
        while (!playback.playing && System.currentTimeMillis() < deadline) {
            mainDispatcher.scheduler.runCurrent()
            Thread.sleep(10)
        }
        assertTrue("the local speech never reached playback", playback.playing)

        playback.handle.positionMs = 1_000
        mainDispatcher.scheduler.advanceTimeBy(150)
        mainDispatcher.scheduler.runCurrent()

        val fraction = service.ttsPlaybackFraction.value
        assertNotNull(
            "the karaoke gate needs a playback fraction; the eSpeak path published none",
            fraction,
        )
        assertTrue("the fraction must advance with the playback", fraction!! > 0f)
        assertNotNull(
            "the estimated word range must exist at half the audio",
            ttsActiveWordRange("hello there", null, fraction, 2_000),
        )

        // Complete the playback so the fraction poller ends instead of keeping a delayed
        // coroutine on the test dispatcher.
        playback.finish()
        mainDispatcher.scheduler.advanceTimeBy(200)
        mainDispatcher.scheduler.runCurrent()
    }

    private class PcmPlayback : AudioPlayback {
        class Handle : PlaybackHandle {
            override var positionMs: Long = 0
            override var durationMs: Long = 2_000
            override fun stop() = Unit
        }

        val handle = Handle()
        var playing = false
        private var onEndCallback: ((PlaybackEnd) -> Unit)? = null

        override fun play(file: Path, target: AudioDevice?, onEnd: (PlaybackEnd) -> Unit): PlaybackHandle =
            error("file playback is not part of this probe")

        override fun playPcm(
            samples: ByteArray,
            sampleRate: Int,
            target: AudioDevice?,
            onEnd: (PlaybackEnd) -> Unit,
        ): PlaybackHandle {
            playing = true
            onEndCallback = onEnd
            return handle
        }

        fun finish() = onEndCallback?.invoke(PlaybackEnd.Finished)

        override fun stopAll() = Unit
    }

    private class LocalSpeech : TextToSpeech {
        override val systemReady: Boolean = false
        override fun speakSystem(text: String, utteranceId: String, onEnd: (PlaybackEnd) -> Unit): Boolean = false
        override fun stopSystem() = Unit
        // 64,000 bytes at 16 kHz mono = 2,000 ms, matching the fake handle's duration.
        override fun synthesizeLocal(text: String, voice: String): SpeechSamples =
            SpeechSamples(ByteArray(64_000), 16_000)
        override fun releaseLocal() = Unit
    }
}
